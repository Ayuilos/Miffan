#!/bin/sh
# Miffan remote helper. Installed by the Miffan app at ~/.miffan/bin/miffan over SSH and run
# from non-interactive SSH sessions, which lack the graphical session's environment. Every
# command here only touches the current user's session; nothing needs root.
#
#   miffan version          print the helper version
#   miffan probe            describe the machine as one line of JSON
#   miffan env              print the graphical session environment as shell exports
#   miffan cua              run `cua-driver mcp` (MCP over stdio) inside the session
#   miffan vnc start|stop|status
#                           manage a VNC server only this user can reach (JSON on stdout)
#   miffan rdp start        password on stdin; safe per-user RDP startup (JSON)
#   miffan clip             set the session clipboard from stdin (UTF-8)

MIFFAN_HELPER_VERSION=9
MIFFAN_CUA_MIN_VERSION=0.34.0

set -u

os() {
    case "$(uname -s)" in
        Darwin) echo macos ;;
        Linux) echo linux ;;
        *) echo unknown ;;
    esac
}

json_str() {
    # Escapes backslash, quote and control characters for a JSON string value.
    printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\t\r' '   ')"
}

json_or_null() {
    if [ -n "$1" ]; then json_str "$1"; else printf null; fi
}

# --- Graphical session discovery (Linux) -------------------------------------------------

SESSION_VARS="WAYLAND_DISPLAY DISPLAY DBUS_SESSION_BUS_ADDRESS XDG_RUNTIME_DIR XDG_CURRENT_DESKTOP XDG_SESSION_TYPE KDE_SESSION_VERSION NIRI_SOCKET SWAYSOCK HYPRLAND_INSTANCE_SIGNATURE XAUTHORITY"

# Prints KEY=VALUE lines for the session variables. The systemd user manager usually has them
# (desktops import their environment there); otherwise borrow them from a running process of
# this user that has a display.
session_env_lines() {
    uid=$(id -u)
    found=""
    if command -v systemctl >/dev/null 2>&1; then
        found=$(systemctl --user show-environment 2>/dev/null | grep -E '^(WAYLAND_DISPLAY|DISPLAY)=' | head -n 1)
        if [ -n "$found" ]; then
            systemctl --user show-environment 2>/dev/null | while IFS= read -r line; do
                key=${line%%=*}
                for var in $SESSION_VARS; do
                    [ "$key" = "$var" ] && printf '%s\n' "$line"
                done
            done
            return 0
        fi
    fi
    for pid in $(ps -u "$uid" -o pid= 2>/dev/null); do
        environ="/proc/$pid/environ"
        [ -r "$environ" ] || continue
        if tr '\0' '\n' < "$environ" 2>/dev/null | grep -qE '^(WAYLAND_DISPLAY|DISPLAY)='; then
            tr '\0' '\n' < "$environ" | while IFS= read -r line; do
                key=${line%%=*}
                for var in $SESSION_VARS; do
                    [ "$key" = "$var" ] && printf '%s\n' "$line"
                done
            done
            return 0
        fi
    done
    return 1
}

# Exports the session environment into the current shell. Values are passed through `export`
# one variable at a time, never evaluated as shell code.
load_session_env() {
    # An explicitly chosen display wins over discovery (machines with several sessions, tests).
    if [ -n "${WAYLAND_DISPLAY:-}" ] || [ -n "${DISPLAY:-}" ]; then
        [ -n "${XDG_RUNTIME_DIR:-}" ] || [ ! -d "/run/user/$(id -u)" ] || export XDG_RUNTIME_DIR="/run/user/$(id -u)"
        return 0
    fi
    lines=$(session_env_lines) || return 1
    old_ifs=$IFS
    IFS='
'
    for line in $lines; do
        key=${line%%=*}
        value=${line#*=}
        export "$key=$value"
    done
    IFS=$old_ifs
    [ -n "${XDG_RUNTIME_DIR:-}" ] || [ ! -d "/run/user/$(id -u)" ] || export XDG_RUNTIME_DIR="/run/user/$(id -u)"
    return 0
}

session_type() {
    if [ "$(os)" = macos ]; then echo quartz; return; fi
    if [ -n "${WAYLAND_DISPLAY:-}" ]; then echo wayland
    elif [ -n "${DISPLAY:-}" ]; then echo x11
    else echo none
    fi
}

# --- cua-driver -------------------------------------------------------------------------

cua_path() {
    for candidate in "$(command -v cua-driver 2>/dev/null)" "$HOME/.local/bin/cua-driver" \
        /opt/homebrew/bin/cua-driver /usr/local/bin/cua-driver; do
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

cua_version() {
    "$1" --version 2>/dev/null | head -n 1 | sed -n 's/.*cua-driver \([0-9][0-9.]*\).*/\1/p'
}

# 0 when version $1 >= $2 (dotted numeric).
version_at_least() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -t. -k1,1n -k2,2n -k3,3n | head -n 1)" = "$2" ]
}

# --- VNC ---------------------------------------------------------------------------------

runtime_dir() {
    # Prefer the per-user runtime directory; machines without logind (containers, minimal
    # servers) have none, so fall back to a private directory in $HOME.
    if [ "$(os)" != macos ] && [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -d "$XDG_RUNTIME_DIR" ] && [ -w "$XDG_RUNTIME_DIR" ]; then
        dir="$XDG_RUNTIME_DIR/miffan"
    else
        dir="$HOME/.miffan/run"
    fi
    mkdir -p "$dir" && chmod 700 "$dir"
    printf '%s\n' "$dir"
}

port_listening() {
    if command -v nc >/dev/null 2>&1; then
        nc -z 127.0.0.1 "$1" >/dev/null 2>&1
    elif command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | grep -qE "[:.]$1[[:space:]]"
    else
        return 1
    fi
}

vnc_server_kind() {
    if [ "$(os)" = macos ]; then echo macos-screen-sharing; return; fi
    case "$(session_type)" in
        wayland)
            # wayvnc needs wlroots screencopy/virtual-input protocols (sway, Hyprland, niri,
            # river, labwc, Wayfire); GNOME's mutter and KDE's KWin do not provide them.
            case "${XDG_CURRENT_DESKTOP:-}" in
                *GNOME*|*gnome*|*KDE*|*kde*|*Plasma*) echo none; return ;;
            esac
            if command -v wayvnc >/dev/null 2>&1; then echo wayvnc; return; fi
            ;;
        x11)
            if command -v x11vnc >/dev/null 2>&1; then echo x11vnc; return; fi
            ;;
    esac
    echo none
}

# $1 = "with-secret" to include the x11vnc password (only `vnc start` hands it to the app).
vnc_status_json() {
    kind=$(vnc_server_kind)
    if [ "$kind" = macos-screen-sharing ]; then
        if port_listening 5900; then running=true; else running=false; fi
        printf '{"server":"%s","running":%s,"endpoint":"tcp:5900"}\n' "$kind" "$running"
        return
    fi
    dir=$(runtime_dir)
    running=false
    if [ -f "$dir/vnc.pid" ] && kill -0 "$(cat "$dir/vnc.pid")" 2>/dev/null; then running=true; fi
    if [ "$kind" = x11vnc ]; then
        # x11vnc's -unixsock mode drops clients after the version handshake, so it listens on
        # loopback TCP with a random per-start password only this account can read.
        port=$(cat "$dir/vnc.port" 2>/dev/null)
        password=$(cat "$dir/vnc.secret" 2>/dev/null)
        if [ "$running" = true ] && [ -n "$port" ] && [ "${1:-}" = with-secret ]; then
            printf '{"server":"%s","running":true,"endpoint":"tcp:%s","password":%s}\n' "$kind" "$port" "$(json_str "$password")"
        elif [ "$running" = true ] && [ -n "$port" ]; then
            printf '{"server":"%s","running":true,"endpoint":"tcp:%s"}\n' "$kind" "$port"
        else
            printf '{"server":"%s","running":false,"endpoint":null}\n' "$kind"
        fi
        return
    fi
    socket="$dir/vnc.sock"
    [ -S "$socket" ] || running=false
    printf '{"server":"%s","running":%s,"endpoint":%s}\n' "$kind" "$running" "$(json_str "unix:$socket")"
}

free_port() {
    port=5950
    while [ $port -lt 6000 ]; do
        port_listening $port || { echo $port; return 0; }
        port=$((port + 1))
    done
    return 1
}

vnc_start() {
    # macOS Screen Sharing is a system service; there is no session environment to find.
    if [ "$(os)" = macos ]; then
        vnc_status_json with-secret
        return 0
    fi
    load_session_env || { echo '{"error":"no_graphical_session"}'; return 1; }
    kind=$(vnc_server_kind)
    dir=$(runtime_dir)
    if [ -f "$dir/vnc.pid" ] && kill -0 "$(cat "$dir/vnc.pid")" 2>/dev/null; then
        vnc_status_json with-secret
        return 0
    fi
    socket="$dir/vnc.sock"
    rm -f "$socket" "$dir/vnc.port" "$dir/vnc.secret" "$dir/vnc.passwd"
    case "$kind" in
        wayvnc)
            # Own control socket so a wayvnc the user runs separately is left alone.
            nohup wayvnc -u -S "$dir/wayvncctl" "$socket" >"$dir/vnc.log" 2>&1 </dev/null &
            echo $! >"$dir/vnc.pid"
            ready() { [ -S "$socket" ]; }
            ;;
        x11vnc)
            port=$(free_port) || { echo '{"error":"vnc_start_failed","log":"no free port"}'; return 1; }
            umask 077
            secret=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 8)
            printf '%s' "$secret" >"$dir/vnc.secret"
            x11vnc -storepasswd "$secret" "$dir/vnc.passwd" >/dev/null 2>&1
            nohup x11vnc -display "$DISPLAY" -localhost -rfbport "$port" -rfbauth "$dir/vnc.passwd" \
                -forever -shared -noxdamage -quiet >"$dir/vnc.log" 2>&1 </dev/null &
            echo $! >"$dir/vnc.pid"
            echo "$port" >"$dir/vnc.port"
            ready() { port_listening "$port"; }
            ;;
        *)
            printf '{"error":"no_vnc_server","session":"%s","desktop":%s}\n' "$(session_type)" "$(json_or_null "${XDG_CURRENT_DESKTOP:-}")"
            return 1
            ;;
    esac
    i=0
    while [ $i -lt 50 ] && ! ready; do
        sleep 0.1
        i=$((i + 1))
    done
    if ready; then
        [ -S "$socket" ] && chmod 600 "$socket" 2>/dev/null
        vnc_status_json with-secret
    else
        printf '{"error":"vnc_start_failed","log":%s}\n' "$(json_str "$(tail -n 5 "$dir/vnc.log" 2>/dev/null)")"
        return 1
    fi
}

vnc_stop() {
    dir=$(runtime_dir)
    if [ -f "$dir/vnc.pid" ]; then
        kill "$(cat "$dir/vnc.pid")" 2>/dev/null
        rm -f "$dir/vnc.pid"
    fi
    rm -f "$dir/vnc.sock" "$dir/vnc.port" "$dir/vnc.secret" "$dir/vnc.passwd"
    echo '{"stopped":true}'
}

# --- Clipboard ---------------------------------------------------------------------------

clip_tool() {
    if [ "$(os)" = macos ]; then command -v pbcopy && return; fi
    case "$(session_type)" in
        wayland) command -v wl-copy && return ;;
    esac
    command -v xclip >/dev/null 2>&1 && { echo "xclip"; return; }
    command -v xsel >/dev/null 2>&1 && { echo "xsel"; return; }
    return 1
}

clip() {
    [ "$(os)" = macos ] || load_session_env || { echo "no graphical session" >&2; return 1; }
    tool=$(clip_tool) || { echo "no clipboard tool" >&2; return 1; }
    # wl-copy and xclip stay in the background to own the selection; detach them from our
    # stdout/stderr or the SSH channel would stay open until they exit.
    case "$tool" in
        */wl-copy) wl-copy >/dev/null 2>&1 ;;
        xclip) xclip -selection clipboard >/dev/null 2>&1 ;;
        xsel) xsel --clipboard --input >/dev/null 2>&1 ;;
        *) "$tool" ;;
    esac
}

# --- RDP -------------------------------------------------------------------------------

rdp_env() {
    load_session_env >/dev/null 2>&1 || :
    [ -n "${XDG_RUNTIME_DIR:-}" ] || export XDG_RUNTIME_DIR="/run/user/$(id -u)"
    [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
    if [ -z "${XDG_CURRENT_DESKTOP:-}" ]; then
        if pgrep -u "$(id -u)" -x gnome-shell >/dev/null 2>&1; then
            export XDG_CURRENT_DESKTOP=GNOME
        elif pgrep -u "$(id -u)" -f '(^|/)kwin_wayland([[:space:]]|$)' >/dev/null 2>&1; then
            export XDG_CURRENT_DESKTOP=KDE
        fi
    fi
}

rdp_kind() {
    [ "$(os)" = linux ] || { echo none; return; }
    case "${XDG_CURRENT_DESKTOP:-}" in
        *GNOME*|*gnome*) command -v grdctl >/dev/null 2>&1 && { echo gnome-remote-desktop; return; } ;;
        *KDE*|*kde*|*Plasma*) command -v krdpserver >/dev/null 2>&1 && { echo krdp; return; } ;;
    esac
    echo none
}

rdp_dimensions() {
    rdp_width=null
    rdp_height=null
    if [ "${rdp_mode:-}" = headless ]; then
        rdp_width=1920; rdp_height=1080
    elif [ -n "${DISPLAY:-}" ] && command -v xrandr >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
        geometry=$(timeout 2 xrandr --current 2>/dev/null | awk '/ connected/ {
            for (i=1; i<=NF; i++) if ($i ~ /^[0-9]+x[0-9]+[+]/) { split($i,d,/[x+]/); print d[1],d[2]; exit }
        }')
        if [ -n "$geometry" ]; then
            rdp_width=${geometry%% *}; rdp_height=${geometry#* }
            case "$rdp_width:$rdp_height" in *[!0-9:]*) rdp_width=null; rdp_height=null ;; esac
        fi
    fi
}

rdp_json() {
    rdp_dimensions
    rdp_error=${1:-}
    rdp_fingerprint=""
    # Only successful helper-owned starts attest the certificate used by both servers.
    if [ -z "$rdp_error" ]; then
        rdp_fingerprint=$(rdp_certificate_sha256) || rdp_error=rdp_start_failed
    fi
    # Only fixed diagnostics leave the helper. Never return upstream credential output.
    printf '{"server":%s,"port":%s,"username":%s,"desktop":%s,"mode":%s,"error":%s,"log":%s,"width":%s,"height":%s,"certificate_sha256":%s}\n' \
        "$(json_or_null "$rdp_server")" "${rdp_port:-null}" "$(json_or_null "${rdp_user:-}")" \
        "$(json_or_null "${XDG_CURRENT_DESKTOP:-}")" "$(json_or_null "${rdp_mode:-}")" \
        "$(json_or_null "$rdp_error")" "$(json_or_null "${2:-}")" "$rdp_width" "$rdp_height" "$(json_or_null "$rdp_fingerprint")"
    [ -z "$rdp_error" ]
}

rdp_certificate_sha256() (
    # Decode first: a failed x509 command must never attest the digest of empty input.
    der="$rdp_dir/start.lock/cert.der"
    trap 'rm -f "$der"' 0
    openssl x509 -in "$rdp_dir/cert.pem" -outform DER -out "$der" 2>/dev/null || return 1
    fingerprint=$(openssl dgst -sha256 -r "$der" 2>/dev/null) || return 1
    fingerprint=${fingerprint%% *}
    case "$fingerprint" in *[!0-9a-f]*) return 1 ;; esac
    [ "${#fingerprint}" -eq 64 ] || return 1
    printf '%s' "$fingerprint"
)

rdp_probe_json() {
    rdp_env
    rdp_server=$(rdp_kind)
    rdp_version=""
    rdp_running=false
    case "$rdp_server" in
        gnome-remote-desktop)
            rdp_version=$(grdctl --version 2>/dev/null | head -n 1)
            pgrep -u "$(id -u)" -f '(^|/)gnome-remote-desktop-daemon([[:space:]]|$)' >/dev/null 2>&1 && rdp_running=true
            ;;
        krdp)
            rdp_version=$(krdpserver --version 2>/dev/null | head -n 1)
            pgrep -u "$(id -u)" -x krdpserver >/dev/null 2>&1 && rdp_running=true
            ;;
    esac
    printf '{"server":%s,"version":%s,"running":%s}\n' \
        "$(json_or_null "$rdp_server")" "$(json_or_null "$rdp_version")" "$rdp_running"
}

rdp_random_port() {
    n=0
    while [ "$n" -lt 32 ]; do
        candidate=$((20000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 40000))
        if ! port_listening "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
        n=$((n + 1))
    done
    return 1
}

rdp_certificate() {
    # Certificate identity persists across starts; never silently replace a partial pair.
    if [ -f "$rdp_dir/cert.pem" ] && [ -f "$rdp_dir/key.pem" ]; then
        chmod 600 "$rdp_dir/cert.pem" "$rdp_dir/key.pem"
        return
    fi
    [ ! -e "$rdp_dir/cert.pem" ] && [ ! -e "$rdp_dir/key.pem" ] || return 1
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj /CN=Miffan-RDP \
        -keyout "$rdp_dir/key.new" -out "$rdp_dir/cert.new" >/dev/null 2>&1 || return 1
    chmod 600 "$rdp_dir/key.new" "$rdp_dir/cert.new" || return 1
    mv "$rdp_dir/key.new" "$rdp_dir/key.pem" && mv "$rdp_dir/cert.new" "$rdp_dir/cert.pem"
}

grd() {
    if [ "$rdp_mode" = headless ]; then grdctl --headless "$@"; else grdctl "$@"; fi
}

rdp_gnome() {
    rdp_mode=user
    if pgrep -u "$(id -u)" -f '(^|/)gnome-shell .*--headless' >/dev/null 2>&1; then rdp_mode=headless; fi
    rdp_unit=gnome-remote-desktop.service
    [ "$rdp_mode" != headless ] || rdp_unit=gnome-remote-desktop-headless.service
    rdp_owner="$rdp_dir/gnome-$rdp_mode.owner"
    if [ "$rdp_mode" = user ]; then
        command -v gdbus >/dev/null 2>&1 || { rdp_json credential_setup_unavailable; return 1; }
        locked=$(gdbus call --session --dest org.freedesktop.secrets \
            --object-path /org/freedesktop/secrets/aliases/default \
            --method org.freedesktop.DBus.Properties.Get org.freedesktop.Secret.Collection Locked 2>/dev/null) || {
                rdp_json keyring_locked 'Unlock the session keyring first'; return 1;
            }
        printf '%s' "$locked" | grep -q false || { rdp_json keyring_locked; return 1; }
    fi
    # Refuse both running and configured services unless this helper created this mode.
    if [ ! -f "$rdp_owner" ]; then
        if pgrep -u "$(id -u)" -f '(^|/)gnome-remote-desktop-daemon([[:space:]]|$)' >/dev/null 2>&1 || \
            grd status 2>/dev/null | grep -Eq 'Status:[[:space:]]+enabled|TLS (certificate|key):[[:space:]]+[^[:space:]]|Username:[[:space:]]+\(hidden\)'; then
            rdp_json rdp_already_configured 'Existing GNOME RDP configuration is not owned by Miffan'; return 1
        fi
    else
        # A user changing the owned configuration revokes ownership: do not overwrite it.
        rdp_port=$(cat "$rdp_owner")
        case "$rdp_port" in ''|*[!0-9]*) rdp_json rdp_already_configured; return 1 ;; esac
        owned_status=$(grd status 2>/dev/null) || { rdp_json rdp_start_failed; return 1; }
        if ! printf '%s\n' "$owned_status" | grep -F "TLS certificate: $rdp_dir/cert.pem" >/dev/null || \
            ! printf '%s\n' "$owned_status" | grep -F "TLS key: $rdp_dir/key.pem" >/dev/null || \
            ! printf '%s\n' "$owned_status" | grep -Eq "Port:[[:space:]]+$rdp_port$"; then
            rdp_json rdp_already_configured 'GNOME configuration changed outside Miffan'; return 1
        fi
    fi
    command -v systemctl >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1 || {
        rdp_json credential_setup_unavailable; return 1;
    }
    rdp_certificate || { rdp_json rdp_start_failed 'Certificate creation failed'; return 1; }
    [ -n "${rdp_port:-}" ] || rdp_port=$(rdp_random_port) || { rdp_json rdp_start_failed; return 1; }
    rdp_digest=$(printf '%s' "$rdp_secret" | openssl dgst -sha256 | sed 's/^.*= //')
    if [ -f "$rdp_owner" ] && [ "$rdp_digest" = "$(cat "$rdp_owner.digest" 2>/dev/null)" ] && port_listening "$rdp_port"; then
        unset rdp_secret
        rdp_json; return $?
    fi
    # grdctl uses separate buffered GIO readers for username and password. A canonical
    # PTY keeps each read to one line (a plain pipe may lose its prefetched second line).
    # Disable echo and discard all transcript/output; neither argv nor logs contain secrets.
    command -v script >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || {
        rdp_json credential_setup_unavailable 'util-linux script and timeout are required'; return 1;
    }
    unset ENV BASH_ENV SCRIPT_DEBUG ULPTY_DEBUG
    export SHELL=/bin/sh
    credential_command='grdctl rdp set-credentials'
    [ "$rdp_mode" != headless ] || credential_command='grdctl --headless rdp set-credentials'
    if ! printf '%s\n%s\n' "$rdp_user" "$rdp_secret" | timeout 15 script --quiet --return --echo never --command "$credential_command" /dev/null >/dev/null 2>&1; then
        rdp_json keyring_locked 'Credential store unavailable or locked'; return 1
    fi
    unset rdp_secret
    # Record ownership of partial setup; failure must never affect another service.
    printf '%s\n' "$rdp_port" > "$rdp_owner"
    if ! grd rdp set-tls-cert "$rdp_dir/cert.pem" >/dev/null 2>&1 || \
        ! grd rdp set-tls-key "$rdp_dir/key.pem" >/dev/null 2>&1 || \
        ! grd rdp set-port "$rdp_port" >/dev/null 2>&1 || \
        ! grd rdp disable-port-negotiation >/dev/null 2>&1 || \
        ! grd rdp disable-view-only >/dev/null 2>&1; then
        rdp_json rdp_start_failed 'GRD configuration failed'; return 1
    fi
    # GRD always authenticates with NLA. The standard unit is now exclusively helper-owned.
    printf '%s\n' "$rdp_port" > "$rdp_owner"
    grd rdp enable >/dev/null 2>&1 && systemctl --user restart "$rdp_unit" >/dev/null 2>&1 || {
        rdp_json rdp_start_failed 'GRD service startup failed'; return 1;
    }
    if rdp_wait; then printf '%s\n' "$rdp_digest" > "$rdp_owner.digest"; else return 1; fi
}

rdp_kde() {
    rdp_mode=user
    command -v secret-tool >/dev/null 2>&1 && command -v gdbus >/dev/null 2>&1 && \
        command -v timeout >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1 && \
        command -v systemd-run >/dev/null 2>&1 && command -v systemctl >/dev/null 2>&1 || {
            rdp_json credential_setup_unavailable 'secret-tool, gdbus and systemd tools are required'; return 1;
        }
    rdp_config="$rdp_dir/krdp-config"
    rdp_owner="$rdp_dir/krdp.owner"
    # An independent XDG_CONFIG_HOME prevents changing the user's KRDP settings.
    if [ ! -f "$rdp_owner" ] && { pgrep -u "$(id -u)" -x krdpserver >/dev/null 2>&1 || \
        [ -s "${XDG_CONFIG_HOME:-$HOME/.config}/krdpserverrc" ]; }; then
        rdp_json rdp_already_configured 'Existing KDE RDP configuration is not owned by Miffan'; return 1
    fi
    owned_pid=$(systemctl --user show miffan-rdp-kde.service -p MainPID --value 2>/dev/null)
    for active_pid in $(pgrep -u "$(id -u)" -x krdpserver 2>/dev/null); do
        if [ ! -f "$rdp_owner" ] || [ "$active_pid" != "$owned_pid" ]; then
            rdp_json rdp_already_configured; return 1
        fi
    done
    rdp_collection=$(rdp_kde_collection) || {
        rdp_json keyring_locked 'Unlock the default session keyring first'; return 1;
    }
    rdp_digest=$(printf '%s' "$rdp_secret" | openssl dgst -sha256 | sed 's/^.*= //')
    if [ -f "$rdp_owner" ]; then
        rdp_port=$(cat "$rdp_owner")
        case "$rdp_port" in ''|*[!0-9]*) rdp_json rdp_already_configured; return 1 ;; esac
        if ! grep -Fx "Users=$rdp_user" "$rdp_config/krdpserverrc" >/dev/null 2>&1 || \
            ! grep -Fx "ListenPort=$rdp_port" "$rdp_config/krdpserverrc" >/dev/null 2>&1 || \
            ! grep -Fx "Certificate=$rdp_dir/cert.pem" "$rdp_config/krdpserverrc" >/dev/null 2>&1 || \
            ! grep -Fx "CertificateKey=$rdp_dir/key.pem" "$rdp_config/krdpserverrc" >/dev/null 2>&1; then
            rdp_json rdp_already_configured 'KRDP configuration changed outside Miffan'; return 1
        fi
        # A v8-owned unit still uses kwallet6; migrate it even if its password is unchanged.
        if [ "$(cat "$rdp_owner.backend" 2>/dev/null)" = libsecret ] && \
            [ "$rdp_digest" = "$(cat "$rdp_owner.digest" 2>/dev/null)" ] && port_listening "$rdp_port"; then
            unset rdp_secret
            rdp_json; return $?
        fi
    elif [ "$(systemctl --user show miffan-rdp-kde.service -p LoadState --value 2>/dev/null)" = loaded ]; then
        rdp_json rdp_already_configured; return 1
    fi
    # QtKeychain 0.17 libsecret.cpp: org.qt.keychain schema, user=key, server=service,
    # type=plaintext. secret-tool reads ALL stdin bytes, so do not append a newline.
    # Pin the existing collection path, never let libsecret create a missing default alias.
    if ! printf '%s' "$rdp_secret" | timeout 5 secret-tool store --label="KRDP/$rdp_user" \
        --collection="$rdp_collection" xdg:schema org.qt.keychain user "$rdp_user" \
        server KRDP type plaintext >/dev/null 2>&1; then
        rdp_json keyring_locked 'Secret Service write denied'; return 1
    fi
    unset rdp_secret
    rdp_certificate || { rdp_json rdp_start_failed 'Certificate creation failed'; return 1; }
    mkdir -p "$rdp_config" && chmod 700 "$rdp_config" || return 1
    [ -n "$rdp_port" ] || rdp_port=$(rdp_random_port) || { rdp_json rdp_start_failed; return 1; }
    printf '[General]\nUsers=%s\nSystemUserEnabled=false\nListenPort=%s\nCertificate=%s/cert.pem\nCertificateKey=%s/key.pem\nAutogenerateCertificates=false\n' \
        "$rdp_user" "$rdp_port" "$rdp_dir" "$rdp_dir" > "$rdp_config/krdpserverrc"
    # Only restart our named transient unit, never krdp.service or another PID.
    if [ -f "$rdp_owner" ] && [ "$(systemctl --user show miffan-rdp-kde.service -p LoadState --value 2>/dev/null)" = loaded ]; then
        systemctl --user stop miffan-rdp-kde.service >/dev/null 2>&1 || { rdp_json rdp_start_failed; return 1; }
        systemctl --user reset-failed miffan-rdp-kde.service >/dev/null 2>&1 || :
    fi
    systemd-run --user --unit=miffan-rdp-kde --collect --quiet \
        --setenv="XDG_CONFIG_HOME=$rdp_config" --setenv=XDG_CURRENT_DESKTOP=KDE --setenv=KDE_SESSION_VERSION=6 \
        --setenv=QTKEYCHAIN_BACKEND=libsecret --setenv="WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}" \
        --setenv="DBUS_SESSION_BUS_ADDRESS=$DBUS_SESSION_BUS_ADDRESS" \
        krdpserver --address 127.0.0.1 --plasma >/dev/null 2>&1 || { rdp_json rdp_start_failed 'KRDP startup failed'; return 1; }
    printf '%s\n' "$rdp_port" > "$rdp_owner"
    if rdp_wait; then
        printf '%s\n' "$rdp_digest" > "$rdp_owner.digest"
        printf '%s\n' libsecret > "$rdp_owner.backend"
    else return 1; fi
}

rdp_kde_collection() (
    # Read-only preflight on the active Secret Service, regardless of desktop/provider.
    # ReadAlias returns '/' when absent. No CreateCollection, Unlock or session fallback.
    alias=$(timeout 3 gdbus call --session --dest org.freedesktop.secrets \
        --object-path /org/freedesktop/secrets \
        --method org.freedesktop.Secret.Service.ReadAlias default 2>/dev/null) || return 1
    collection=$(printf '%s' "$alias" | sed -n "s|^(objectpath '\(/org/freedesktop/secrets/collection/[a-zA-Z0-9_/]*\)',)$|\1|p")
    [ -n "$collection" ] && [ "$collection" != /org/freedesktop/secrets/collection/session ] || return 1
    locked=$(timeout 3 gdbus call --session --dest org.freedesktop.secrets \
        --object-path "$collection" --method org.freedesktop.DBus.Properties.Get \
        org.freedesktop.Secret.Collection Locked 2>/dev/null) || return 1
    [ "$locked" = '(<false>,)' ] || return 1
    printf '%s' "$collection"
)

rdp_wait() {
    attempt=0
    while [ "$attempt" -lt 80 ]; do
        if port_listening "$rdp_port"; then rdp_json; return $?; fi
        sleep 0.1
        attempt=$((attempt + 1))
    done
    rdp_json rdp_start_failed 'RDP listener did not become ready'; return 1
}

rdp_start() (
    # Subshell contains the secret and cleanup traps, independent of other helper commands.
    set +x
    umask 077
    export LC_ALL=C
    rdp_env
    rdp_server=$(rdp_kind)
    rdp_user="miffan-$(id -u)"
    rdp_dir="$HOME/.miffan/rdp"
    rdp_port=""
    [ "$rdp_server" != none ] || { rdp_json no_rdp_server; exit 1; }
    IFS= read -r rdp_secret || { rdp_json rdp_start_failed 'Password must be provided on stdin'; exit 1; }
    case "$rdp_secret" in *[!a-zA-Z0-9]*) rdp_json rdp_start_failed 'Invalid generated password'; exit 1 ;; esac
    [ "${#rdp_secret}" -ge 32 ] && [ "${#rdp_secret}" -le 256 ] || { rdp_json rdp_start_failed 'Invalid generated password length'; exit 1; }
    mkdir -p "$rdp_dir" && chmod 700 "$HOME/.miffan" "$rdp_dir" || { rdp_json rdp_start_failed; exit 1; }
    mkdir "$rdp_dir/start.lock" 2>/dev/null || { rdp_json rdp_start_failed 'Another RDP start is in progress'; exit 1; }
    trap 'unset rdp_secret; rmdir "$rdp_dir/start.lock" 2>/dev/null' 0
    trap 'exit 1' 1 2 15
    case "$rdp_server" in gnome-remote-desktop) rdp_gnome ;; krdp) rdp_kde ;; esac
)

# --- probe -------------------------------------------------------------------------------

probe() {
    platform=$(os)
    has_session=false
    if [ "$platform" = macos ]; then
        has_session=true
    elif load_session_env; then
        has_session=true
    fi
    [ "$platform" != linux ] || rdp_env
    type=$(session_type)
    desktop=""
    if [ "$platform" = macos ]; then desktop=macos; else desktop="${XDG_CURRENT_DESKTOP:-}"; fi
    cua=$(cua_path) || cua=""
    version=""
    meets=false
    if [ -n "$cua" ]; then
        version=$(cua_version "$cua")
        if [ -n "$version" ] && version_at_least "$version" "$MIFFAN_CUA_MIN_VERSION"; then meets=true; fi
    fi
    clipboard=$(clip_tool) || clipboard=""
    printf '{"helper":%s,"os":"%s","arch":%s,"session":{"present":%s,"type":"%s","desktop":%s},' \
        "$MIFFAN_HELPER_VERSION" "$platform" "$(json_str "$(uname -m)")" "$has_session" "$type" "$(json_or_null "$desktop")"
    printf '"cua":{"path":%s,"version":%s,"min":"%s","ok":%s},' \
        "$(json_or_null "$cua")" "$(json_or_null "$version")" "$MIFFAN_CUA_MIN_VERSION" "$meets"
    printf '"rdp":%s,"vnc":%s,"clipboard":%s}\n' "$(rdp_probe_json)" "$(vnc_status_json)" "$(json_or_null "${clipboard##*/}")"
}

case "${1:-}" in
    version) echo "$MIFFAN_HELPER_VERSION" ;;
    probe) probe ;;
    env)
        session_env_lines | while IFS= read -r line; do
            printf 'export %s=%s\n' "${line%%=*}" "$(printf '%s' "${line#*=}" | sed "s/'/'\\\\''/g; s/^/'/; s/\$/'/")"
        done
        ;;
    cua)
        [ "$(os)" = macos ] || load_session_env || { echo "no graphical session" >&2; exit 1; }
        [ "$(session_type)" = wayland ] && export CUA_DRIVER_RS_ENABLE_WAYLAND=1
        cua=$(cua_path) || { echo "cua-driver not found" >&2; exit 127; }
        exec "$cua" mcp
        ;;
    vnc)
        case "${2:-status}" in
            start) vnc_start ;;
            stop) vnc_stop ;;
            status) [ "$(os)" = macos ] || load_session_env; vnc_status_json ;;
            *) echo "usage: miffan vnc start|stop|status" >&2; exit 2 ;;
        esac
        ;;
    rdp)
        case "${2:-}" in start) rdp_start ;; *) echo "usage: miffan rdp start" >&2; exit 2 ;; esac
        ;;
    clip) clip ;;
    *) echo "usage: miffan version|probe|env|cua|vnc|rdp|clip" >&2; exit 2 ;;
esac
