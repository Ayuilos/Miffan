#!/bin/sh
# Miffan remote helper. Installed by the Miffan app at ~/.miffan/bin/miffan over SSH and run
# from non-interactive SSH sessions, which lack the graphical session's environment. Every
# command here only touches the current user's session; nothing needs root.
#
#   miffan version          print the helper version
#   miffan probe            describe the machine as one line of JSON
#   miffan env              print the graphical session environment as shell exports
#   miffan cua              run `cua-driver mcp` (MCP over stdio) inside the session
#   miffan cua check-update ask cua-driver whether a newer release exists (one line of JSON)
#   miffan cua install      install or update cua-driver through the computer's own proxy
#   miffan vnc start|stop|status
#                           manage a VNC server only this user can reach (JSON on stdout)
#   miffan rdp start        password on stdin; safe per-user RDP startup (JSON)
#   miffan sunshine probe|start|enforce-encryption|pair|unpair|wake|open-settings screen|accessibility
#                           inspect Sunshine; encryption enforcement; unpair <client-cert-sha256>
#   miffan clip             set the session clipboard from stdin (UTF-8)
#   miffan mac-shortcut desktop-left|desktop-right|mission-control|app-windows
#                           macOS: press a Mission Control shortcut that streamed keys cannot reach
#   miffan task probe|which|stage|commit|list|remove|enable|disable|run|sync|log|exec
#                           scheduled tasks run by the system timer (JSON on stdout)
#   miffan space probe|install|create|info|start|stop|delete|reset|resize|netcheck|handoff
#                           a partner's own cua Space on this computer (JSON on stdout)

MIFFAN_HELPER_VERSION=29
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

# cua-driver types on Wayland through wtype, which decodes its text in the C library's locale.
# SSH commands start in the C locale, where any non-ASCII text fails and the driver falls back
# to a portal wait that times out on compositors without RemoteDesktop (niri). Prefer the
# session's UTF-8 LANG, else C.UTF-8; only the character type changes.
utf8_ctype() {
    case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) return 0 ;; esac
    ctype=$(systemctl --user show-environment 2>/dev/null | sed -n 's/^LANG=//p' | head -n 1)
    case "$ctype" in *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) ;; *) ctype=C.UTF-8 ;; esac
    unset LC_ALL
    export LC_CTYPE="$ctype"
}

# Sunshine posts keys with modifier flags only, and macOS matches Control+arrow system shortcuts
# only with the fn flag a real arrow key carries, so these go through cua-driver's daemon (which
# holds the Accessibility grant) with fn added. Exit 127 when cua-driver is missing.
mac_shortcut() {
    case "$1" in
        desktop-left) key=left ;; desktop-right) key=right ;;
        mission-control) key=up ;; app-windows) key=down ;;
        *) echo "usage: miffan mac-shortcut desktop-left|desktop-right|mission-control|app-windows" >&2; return 2 ;;
    esac
    [ "$(os)" = macos ] || { echo "mac-shortcut is only for macOS" >&2; return 2; }
    cua=$(cua_path) || { echo "cua-driver not found" >&2; return 127; }
    "$cua" hotkey "{\"scope\":\"desktop\",\"keys\":[\"ctrl\",\"fn\",\"$key\"]}" >/dev/null
}

cua_version() {
    "$1" --version 2>/dev/null | head -n 1 | sed -n 's/.*cua-driver \([0-9][0-9.]*\).*/\1/p'
}

# cua-driver's own release check, fresh rather than from its on-disk cache, flattened to one line
# (its JSON strings hold no raw newlines). Read-only: nothing is downloaded or installed.
cua_check_update() {
    cua=$(cua_path) || { echo '{"error":"not_installed"}'; return 0; }
    out=$("$cua" check-update --json --no-cache 2>/dev/null) && [ -n "$out" ] ||
        { echo '{"error":"check_failed"}'; return 0; }
    printf '%s\n' "$out" | tr '\n\r' '  '
    echo
}

# A fixed release download that stays published; only its first MiB is fetched, to measure speed.
CUA_PROBE_ASSET=https://github.com/trycua/cua/releases/download/cua-driver-rs-v0.34.0/cua-driver-rs-0.34.0-darwin-universal.tar.gz

# The computer's own proxy for downloads. SSH commands lack the proxy variables a login shell sets,
# and on some networks GitHub downloads crawl without one: the macOS system proxy, else the
# desktop session's environment. Variables already set win.
cua_proxy() {
    [ -n "${HTTPS_PROXY:-}${https_proxy:-}" ] && return 0
    if [ "$(os)" = macos ]; then
        p=$(scutil --proxy 2>/dev/null | awk '/HTTPSEnable : 1/ {e=1} / HTTPSProxy : / {h=$3} / HTTPSPort : / {port=$3}
            END {if (e && h != "" && port != "") print "http://" h ":" port}')
    else
        p=$(systemctl --user show-environment 2>/dev/null | sed -n 's/^\(https_proxy\|HTTPS_PROXY\|all_proxy\|ALL_PROXY\)=//p' | head -n 1)
    fi
    [ -n "$p" ] && export HTTPS_PROXY="$p" https_proxy="$p" HTTP_PROXY="$p" http_proxy="$p"
    return 0
}

# 0 when 1 MiB of a GitHub release arrives within 20 s; the release itself is tens of MB, so a slower
# network would leave the installer running for many minutes.
cua_download_fast() {
    got=$(curl -sS -L -o /dev/null -r 0-1048575 --max-time 20 -w '%{size_download}' "$CUA_PROBE_ASSET" 2>/dev/null) || return 1
    [ "${got%%.*}" -ge 1048576 ] 2>/dev/null
}

# Installs cua-driver, or updates the installed one (`update --apply`, falling back to the installer:
# 0.24's update fails its own release check). The installer stops a running daemon, and a systemd
# user service (Restart=on-failure) stays down after that clean stop, so bring it back.
cua_install() {
    cua_proxy
    cua_download_fast || { echo 'Cua download failed; nothing was changed' >&2; return 1; }
    if cua=$(cua_path) && "$cua" update --apply; then
        status=0
    else
        /bin/bash -c "$(curl -fsSL https://cua.ai/driver/install.sh)"
        status=$?
    fi
    systemctl --user is-enabled cua-driver.service >/dev/null 2>&1 && systemctl --user restart cua-driver.service
    return "$status"
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
        # wl-copy guesses the type from the content and labels one character as
        # application/octet-stream, which browsers will not paste; always offer text.
        */wl-copy) wl-copy --type 'text/plain;charset=utf-8' >/dev/null 2>&1 ;;
        xclip) xclip -selection clipboard >/dev/null 2>&1 ;;
        xsel) xsel --clipboard --input >/dev/null 2>&1 ;;
        # pbcopy decodes stdin by locale; an SSH command has no LANG, and then it stores nothing.
        */pbcopy) LANG=en_US.UTF-8 LC_CTYPE=UTF-8 "$tool" ;;
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

# --- Sunshine (read-only probe; explicit, backed-up encryption change) --------------------

sunshine() {
    case "$1" in probe|start|enforce-encryption|pair|unpair|wake|open-settings) ;; *) echo 'usage: miffan sunshine probe|start|enforce-encryption|pair|unpair|wake|open-settings screen|accessibility' >&2; return 2 ;; esac
    if ! command -v python3 >/dev/null 2>&1; then
        printf '{"success":false,"detail":"python3 is required for Sunshine inspection"}\n'
        return 1
    fi
    if [ "$1" = pair ] && [ "$#" -gt 1 ]; then
        printf '{"success":false,"detail":"Sunshine pair accepts name and certificate only via stdin"}\n'
        return 1
    fi
    python3 - "$1" "$(os)" "${2:-}" 3<&0 <<'SUNSHINE_PY'
import base64, datetime, errno, hashlib, ipaddress, json, os, pathlib, plistlib, re, shlex, shutil, signal, ssl, stat, subprocess, sys, tempfile, time, unicodedata, uuid

def run(args, timeout=10):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=dict(os.environ, LC_ALL='C'))
    except (OSError, subprocess.TimeoutExpired):
        return None

def output(args):
    result = run(args)
    return result.stdout.strip() if result and result.returncode == 0 else ''

def reply(success, detail, **extra):
    print(json.dumps(dict(success=success, detail=detail, **extra)))

def finish(success, detail, **extra):
    reply(success, detail, **extra); sys.exit(0 if success else 1)

def display_asleep():
    # Only classify the unambiguous endpoints; intermediate/absent power states are unknown.
    data = output(['ioreg', '-a', '-r', '-n', 'IODisplayWrangler'])
    try:
        for item in plistlib.loads(data.encode()):
            power = item.get('IOPowerManagement', {})
            current, maximum = power.get('CurrentPowerState'), power.get('MaxPowerState')
            if isinstance(current, int) and isinstance(maximum, int) and maximum > 0:
                if current == 0: return True
                if current == maximum: return False
    except (ValueError, TypeError, plistlib.InvalidFileException): pass
    # pmset exposes the driver current/max states even when ioreg omits the dictionary.
    for line in output(['pmset', '-g', 'powerstate', 'IODisplayWrangler']).splitlines():
        match = re.match(r'^\s*IODisplayWrangler\s+\S+\s+(\d+)\s+(\d+)(?:\s|$)', line)
        if match:
            current, maximum = map(int, match.groups())
            if maximum > 0 and current == 0: return True
            if maximum > 0 and current == maximum: return False
    return None

def screen_locked():
    # This user's console session reports CGSSessionScreenIsLocked only while it is locked.
    try: users = plistlib.loads(output(['ioreg', '-a', '-n', 'Root', '-d1']).encode()).get('IOConsoleUsers', [])
    except (ValueError, TypeError, AttributeError, plistlib.InvalidFileException): return None
    for user in users:
        if user.get('kCGSSessionUserIDKey') == os.getuid() and user.get('kCGSSessionOnConsoleKey'):
            return bool(user.get('CGSSessionScreenIsLocked'))
    return None

def mac_processes(timeout=10):
    result = run(['ps', '-ww', '-axo', 'uid=,pid=,comm='], timeout=timeout)
    if not result or result.returncode != 0: return None
    found = []
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 2)
        if len(fields) != 3 or fields[0] != str(os.getuid()): continue
        if pathlib.Path(fields[2]).name.lower() == 'sunshine':
            found.append((fields[1], fields[2]))
    return found

def stop_sunshine():
    # SIGTERM lets Sunshine save state without Apple Event Automation authorization.
    detail = 'Could not stop Sunshine to change pairing'
    deadline = time.monotonic() + 10
    if mac:
        current = mac_processes()
        if current is None: raise ValueError(detail)
        for pid, _ in current:
            try: os.kill(int(pid), signal.SIGTERM)
            except ProcessLookupError: pass  # Already exited after discovery.
            except OSError as error:
                if error.errno in (errno.EACCES, errno.EPERM): raise
                raise ValueError(detail) from None
    else:
        result = run(['systemctl', '--user', 'stop', unit])
        if not result or result.returncode != 0: raise ValueError(detail)
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise ValueError(detail)
        if mac: exited = mac_processes(timeout=remaining) == []
        else:
            status = run(['systemctl', '--user', 'is-active', unit], timeout=remaining)
            exited = bool(status and status.returncode == 3 and status.stdout.strip() == 'inactive')
        if exited: return
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))

mode = sys.argv[1]
mac = sys.argv[2] == 'macos'
if mode == 'open-settings':
    permission = sys.argv[3]
    if permission not in ('screen', 'accessibility'): finish(False, 'Unknown Sunshine permission')
    if not mac: finish(False, 'Sunshine permission settings are only supported on macOS')
    pane = 'Privacy_ScreenCapture' if permission == 'screen' else 'Privacy_Accessibility'
    result = run(['open', 'x-apple.systempreferences:com.apple.preference.security?' + pane])
    if result and result.returncode == 0: finish(True, 'Permission settings opened')
    result = run(['open', 'x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension'])
    finish(bool(result and result.returncode == 0), 'Privacy & Security settings opened' if result and result.returncode == 0 else 'Could not open permission settings')
if mode == 'wake':
    if not mac: finish(False, 'Display wake is only supported on macOS', display_asleep=None)
    result = run(['caffeinate', '-u', '-t', '5'])
    finish(bool(result and result.returncode == 0), 'Display wake requested' if result and result.returncode == 0 else 'Display wake failed', display_asleep=display_asleep())

appdata = pathlib.Path.home() / '.config/sunshine'
config = appdata / 'sunshine.conf'
args = []
configuration_known = False
unit = None
app = None
version = None
if mac:
    # Prefer a per-user bundle; Homebrew casks also normally install into /Applications.
    for candidate in (pathlib.Path.home() / 'Applications/Sunshine.app', pathlib.Path('/Applications/Sunshine.app')):
        if candidate.is_dir():
            app = candidate
            try:
                with (app / 'Contents/Info.plist').open('rb') as source:
                    version = plistlib.load(source).get('CFBundleShortVersionString')
            except (OSError, ValueError, plistlib.InvalidFileException): pass
            break
    brew_version = ''
    if app is None:
        for brew_args in (['brew', 'list', '--versions', '--cask', 'sunshine'], ['brew', 'list', '--versions', 'sunshine']):
            brew_version = output(brew_args)
            if brew_version: break
        fields = brew_version.split()
        if len(fields) >= 2 and fields[0].lower() == 'sunshine': version = fields[1]
        prefix = output(['brew', '--prefix', 'sunshine']) if version else ''
        if prefix:
            for candidate in (pathlib.Path(prefix) / 'Sunshine.app', pathlib.Path(prefix) / 'share/Sunshine.app'):
                if candidate.is_dir(): app = candidate; break
    installed = app is not None or version is not None or shutil.which('sunshine') is not None
    processes = mac_processes()
    running = bool(processes)
    installed = installed or running
    configuration_known = processes is not None and len(processes) <= 1
    if processes and len(processes) == 1:
        pid, executable = processes[0]
        if '.app/Contents/' in executable:
            candidate = pathlib.Path(executable.split('.app/Contents/', 1)[0] + '.app')
            if candidate.is_dir(): app = candidate
        command = output(['ps', '-ww', '-p', pid, '-o', 'command='])
        try:
            if not command.startswith(executable): configuration_known = False
            else:
                args = shlex.split(command[len(executable):].strip())
                for arg in args:
                    if arg.startswith('--'): break
                    if arg and not arg.startswith('-') and '=' not in arg:
                        candidate = pathlib.Path(arg)
                        if candidate.is_absolute(): config = candidate
                        else:
                            cwd = next((line[1:] for line in output(['lsof', '-a', '-p', pid, '-d', 'cwd', '-Fn']).splitlines() if line.startswith('n')), '')
                            if cwd: config = pathlib.Path(cwd) / candidate
                            else: configuration_known = False
        except ValueError: configuration_known = False
else:
    units = set()
    for args in (['systemctl', '--user', 'list-units', '--all', '--type=service', '--no-legend', '--plain'],
                 ['systemctl', '--user', 'list-unit-files', '--type=service', '--no-legend']):
        for line in output(args).splitlines():
            name = line.split()[0] if line.split() else ''
            if 'sunshine' in name.lower() and name.endswith('.service'):
                # Packages alias the unit (sunshine.service -> app-dev.lizardbyte.app.Sunshine.service): count it once.
                units.add(output(['systemctl', '--user', 'show', name, '--property=Id', '--value']) or name)
    active_units = sorted(name for name in units if output(['systemctl', '--user', 'is-active', name]) == 'active')
    unit = active_units[0] if len(active_units) == 1 else (next(iter(units)) if len(units) == 1 else None)
    running = bool(active_units)
    binary = shutil.which('sunshine')
    installed = binary is not None or bool(units)
    # Never run Sunshine, even --version: it loads config and rotates a live instance's logs.
    version = None
    packages = [(['pacman', '-Q', 'sunshine'], 'pacman'),
                (['dpkg-query', '-W', '-f=${Version}', 'sunshine'], 'plain'),
                (['rpm', '-q', '--qf', '%{VERSION}', 'sunshine'], 'plain'),
                (['flatpak', 'info', 'dev.lizardbyte.app.Sunshine'], 'flatpak')]
    for command, kind in packages:
        value = output(command)
        if not value: continue
        if kind == 'pacman':
            fields = value.split()
            value = fields[1] if len(fields) == 2 and fields[0] == 'sunshine' else ''
        elif kind == 'flatpak':
            match = re.search(r'^\s*Version:\s*(.+)$', value, re.MULTILINE)
            value = match.group(1).strip() if match else ''
        if value:
            version = value; installed = True; break
    appdata = pathlib.Path(os.environ.get('XDG_CONFIG_HOME', str(pathlib.Path.home() / '.config'))) / 'sunshine'
    config = appdata / 'sunshine.conf'
    args = []
    configuration_known = False
    if unit:
        try:
            if running:
                pid = output(['systemctl', '--user', 'show', unit, '--property=MainPID', '--value'])
                command = pathlib.Path('/proc/' + pid + '/cmdline').read_bytes().decode().split('\0')
                cwd = pathlib.Path(os.readlink('/proc/' + pid + '/cwd'))
            else:
                description = output(['systemctl', '--user', 'show', unit, '--property=ExecStart', '--value'])
                match = re.search(r'argv\[\]=(.*?)\s*;', description)
                command = shlex.split(match.group(1)) if match else []
                working = output(['systemctl', '--user', 'show', unit, '--property=WorkingDirectory', '--value'])
                cwd = pathlib.Path(working) if working else pathlib.Path.home()
            # Refuse to modify a guessed config for a wrapper, Flatpak launcher or unknown unit.
            if command and pathlib.Path(command[0]).name == 'sunshine':
                configuration_known = True
                for arg in command[1:]:
                    if arg.startswith('--'): break
                    args.append(arg)
                    if arg and not arg.startswith('-') and '=' not in arg:
                        candidate = pathlib.Path(arg)
                        config = candidate if candidate.is_absolute() else cwd / candidate
        except (OSError, UnicodeError, ValueError):
            pass
def start():
    if running: finish(True, 'Sunshine is already running')
    if mac:
        if not app: finish(False, 'No Sunshine application bundle found')
        command = ['open', '-a', str(app)]
        if args: command += ['--args'] + args
    else:
        if not installed or not unit: finish(False, 'No unambiguous Sunshine user unit found')
        command = ['systemctl', '--user', 'start', unit]
    result = run(command)
    finish(bool(result and result.returncode == 0), 'Sunshine start requested' if result and result.returncode == 0 else 'Sunshine start failed')

if mode == 'start':
    if mac and processes is None: finish(False, 'Cannot inspect the current user Sunshine processes')
    start()
if mode in ('enforce-encryption', 'unpair', 'pair') and not configuration_known:
    reply(False, 'Cannot verify the Sunshine configuration'); sys.exit(1)
try:
    content = config.read_text() if config.exists() else ''
except (OSError, UnicodeError) as error:
    if mode in ('unpair', 'pair'):
        detail = 'Cannot read Sunshine state or configuration; check administrator rights' if isinstance(error, OSError) and error.errno in (errno.EACCES, errno.EPERM) else 'Cannot read Sunshine state or configuration'
        finish(False, detail)
    content = ''
values = {}
for line in content.splitlines():
    line = line.split('#', 1)[0]
    match = re.match(r'^\s*([a-zA-Z0-9_]+)\s*=\s*(.*?)\s*$', line)
    if match: values[match[1]] = match[2]
overrides = dict(arg.split('=', 1) for arg in args if '=' in arg)
values.update(overrides)
def client_certificate(pem):
    # Entire input must be exactly one public X.509 block. Diagnostics never echo it.
    match = re.fullmatch(r'\s*-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----\s*', pem)
    if not match: raise ValueError('Invalid Sunshine client certificate')
    try:
        der = base64.b64decode(re.sub(r'\s', '', match[1]), validate=True)
        # X.509 is one DER SEQUENCE; reject bytes trailing a valid certificate too.
        if len(der) < 2 or der[0] != 0x30: raise ValueError()
        size = der[1]
        header = 2
        if size & 0x80:
            octets = size & 0x7f
            if not octets or octets > len(der) - 2: raise ValueError()
            size = int.from_bytes(der[2:2 + octets], 'big'); header += octets
        if header + size != len(der): raise ValueError()
        canonical = ssl.DER_cert_to_PEM_cert(der)
        ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT).load_verify_locations(cadata=canonical)
    except (ValueError, ssl.SSLError): raise ValueError('Invalid Sunshine client certificate') from None
    return der, canonical

pair_request = None
if mode == 'pair':
    try:
        with os.fdopen(3) as request_input: request = json.loads(request_input.read())
        if not isinstance(request, dict) or set(request) != {'name', 'cert'}:
            raise ValueError('Invalid Sunshine pair request')
        name = request['name']
        if not isinstance(name, str) or not name.strip() or len(name) > 64 or any(unicodedata.category(c) == 'Cc' for c in name):
            raise ValueError('Invalid Sunshine client name')
        if not isinstance(request['cert'], str): raise ValueError('Invalid Sunshine client certificate')
        pair_der, pem = client_certificate(request['cert'])
        pair_request = dict(name=name, cert=pem, uuid=str(uuid.uuid4()).upper(), enabled=True)
        fingerprint = hashlib.sha256(pair_der).hexdigest()
    except (ValueError, TypeError, UnicodeError): finish(False, 'Invalid Sunshine pair request or client certificate')
elif mode == 'unpair':
    fingerprint = sys.argv[3].lower()
    if not re.fullmatch(r'[0-9a-f]{64}', fingerprint):
        finish(False, 'Expected a SHA-256 client certificate fingerprint (64 hex digits)')

def edit_pairing():
    state = pathlib.Path(values.get('file_state', 'sunshine_state.json'))
    if not state.is_absolute(): state = appdata / state
    def read_state():
        try: original = state.lstat()
        except (FileNotFoundError, NotADirectoryError):
            if pair_request is None: return {}, [], 0, False
            try: parent = state.parent.lstat()
            except (FileNotFoundError, NotADirectoryError): raise ValueError('Unrecognized Sunshine state format') from None
            if not stat.S_ISDIR(parent.st_mode): raise ValueError('Unrecognized Sunshine state format')
            original = None
        if original is not None and not stat.S_ISREG(original.st_mode):
            raise ValueError('Sunshine state must be a regular file')
        try:
            data = json.loads(state.read_text()) if original is not None else {}
            if pair_request is not None:
                if not isinstance(data, dict): raise ValueError()
                if 'root' not in data: data['root'] = {}
                root = data['root']
                if not isinstance(root, dict): raise ValueError()
                if not isinstance(root.get('uniqueid'), str): root['uniqueid'] = str(uuid.uuid4()).upper()
                devices = root.get('named_devices', [])
                if not isinstance(devices, list) or any(not isinstance(d, dict) or
                    any(not isinstance(d.get(k), str) for k in ('name', 'cert', 'uuid')) for d in devices):
                    raise ValueError()
            else:
                devices = data.get('root', {}).get('named_devices', [])
                if not isinstance(devices, list): raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise ValueError('Unrecognized Sunshine state format') from None
        kept = []
        for device in devices:
            pem = device.get('cert') if isinstance(device, dict) else None
            if not isinstance(pem, str):
                kept.append(device); continue
            try: der = ssl.PEM_cert_to_DER_cert(pem)
            except (ValueError, TypeError): raise ValueError('Unrecognized Sunshine state format') from None
            same = der == pair_der if pair_request is not None else hashlib.sha256(der).hexdigest() == fingerprint
            if not same: kept.append(device)
        return data, kept, len(devices) - len(kept), original is not None
    stopped = False
    failure = None
    action = 'pair' if pair_request is not None else 'unpair'
    try:
        _, _, count, _ = read_state()
        if not count and pair_request is None:
            print(json.dumps(dict(removed=0, restarted=False))); return
        if running:
            # A requested stop may finish after a timeout; always restore the prior running state.
            stopped = True
            stop_sunshine()
        # Sunshine may flush a newer state during shutdown; edit that final version.
        data, kept, count, existed = read_state()
        if count or pair_request is not None:
            data['root']['named_devices'] = kept + ([pair_request] if pair_request is not None else [])
            fd, temporary = tempfile.mkstemp(prefix='.miffan-sunshine-state-', dir=state.parent)
            try:
                with os.fdopen(fd, 'w') as stream:
                    # New mkstemp files belong to this user and stay 0600. Existing files retain ACLs.
                    if existed:
                        copied = run(['cp', '-p', str(state), temporary])
                        if not copied or copied.returncode != 0: raise ValueError('Could not preserve Sunshine state permissions')
                    else: os.fchmod(stream.fileno(), 0o600)
                    stream.seek(0); stream.truncate()
                    json.dump(data, stream, ensure_ascii=False); stream.write('\n')
                    stream.flush(); os.fsync(stream.fileno())
                os.replace(temporary, state)
            finally:
                if os.path.exists(temporary): os.unlink(temporary)
    except (OSError, ValueError, TypeError, AttributeError) as error:
        # Only real EACCES/EPERM failures may suggest administrator rights.
        if isinstance(error, OSError) and error.errno in (errno.EACCES, errno.EPERM):
            failure = 'Cannot edit Sunshine state or control its service; check administrator rights'
        else:
            failure = str(error) if isinstance(error, ValueError) else 'Could not edit Sunshine pairing state'
    finally:
        if stopped:
            command = ['open', '-a', str(app)] if mac else ['systemctl', '--user', 'start', unit]
            if mac and args: command += ['--args'] + args
            result = run(command)
            if not result or result.returncode != 0: failure = 'Sunshine ' + action + ' attempted; could not restart Sunshine'
    if failure: finish(False, failure)
    result = dict(added=True, replaced=count, restarted=stopped, client_sha256=fingerprint) if pair_request is not None else dict(removed=count, restarted=stopped)
    print(json.dumps(result))

if mode in ('unpair', 'pair'):
    # Sunshine uninstalled since pairing: nothing is left that trusts this phone.
    if not installed:
        if mode == 'pair': finish(False, 'Sunshine is not installed')
        print(json.dumps(dict(removed=0, restarted=False))); sys.exit(0)
    if not app if mac else not unit: finish(False, 'Cannot verify Sunshine service for pairing changes')
    edit_pairing(); sys.exit(0)

def integer(name, default):
    try: return int(values.get(name, default))
    except ValueError: return None
# These sockets are created by stream::start_broadcast on the first session reference and
# released by end_broadcast on the last. Pending sessions count as active for safe restart.
base = integer('port', 47989)
ports = {base + offset for offset in (9, 10, 11)} if base is not None else {47998, 47999, 48000}
def stream_activity(strict=False):
    if mac:
        sockets = run(['lsof', '-nP', '-iUDP'])
        # lsof uses exit 1 with empty output for no matching sockets.
        if sockets is None or sockets.returncode not in (0, 1) or (sockets.returncode == 1 and (sockets.stdout or sockets.stderr)):
            return True if strict else running
        current = mac_processes()
        if current is None: return True if strict else running
        pids = {pid for pid, _ in current}
        for line in sockets.stdout.splitlines():
            fields = line.split()
            if len(fields) < 2 or fields[1] not in pids: continue
            if any(int(port) in ports for port in re.findall(r':(\d+)(?:->|\s|$)', line)): return True
        return False
    sockets = run(['ss', '-H', '-uanp'])
    if not sockets or sockets.returncode != 0: return True if strict else running
    for line in sockets.stdout.splitlines():
        columns = line.split()
        local = columns[3] if len(columns) > 3 else ''
        try: port = int(local.rsplit(':', 1)[-1])
        except ValueError: continue
        if port in ports and (strict or 'sunshine' in line.lower() or 'users:' not in line): return True
    return False

active_stream = stream_activity()
cert = pathlib.Path(values.get('cert', str(appdata / 'credentials/cacert.pem')))
if not cert.is_absolute(): cert = appdata / cert
certificate_sha256 = None
try:
    der = subprocess.run(['openssl', 'x509', '-in', str(cert), '-outform', 'DER'], capture_output=True, timeout=10)
    if der.returncode == 0: certificate_sha256 = hashlib.sha256(der.stdout).hexdigest()
except (OSError, subprocess.TimeoutExpired): pass
candidates = []
def add_candidate(value):
    try: ip = ipaddress.IPv4Address(value)
    except ValueError: return
    if any(ip in ipaddress.ip_network(net) for net in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10')):
        if str(ip) not in candidates: candidates.append(str(ip))
if mac:
    interface = ''
    for line in output(['ifconfig']).splitlines():
        match = re.match(r'^(\S+):', line)
        if match: interface = match[1]
        if re.match(r'^(lo\d|bridge|awdl|llw|vmenet|vmnet|vboxnet|virbr|docker|veth|tap|gif|stf)', interface): continue
        match = re.match(r'^\s+inet\s+(\S+)', line)
        if match: add_candidate(match[1])
else:
    try:
        interfaces = json.loads(output(['ip', '-j', '-4', 'address', 'show']))
        for interface in interfaces:
            name = interface.get('ifname', '')
            if re.match(r'^(lo|docker|br-|virbr|veth|cni|podman|lxc)', name): continue
            # A Linux bridge can have an arbitrary name; inspect its sysfs type too.
            if pathlib.Path('/sys/class/net', name, 'bridge').exists(): continue
            for item in interface.get('addr_info', []):
                ip = ipaddress.IPv4Address(item['local'])
                if any(ip in net for net in map(ipaddress.ip_network, ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10'])):
                    if str(ip) not in candidates: candidates.append(str(ip))
    except (ValueError, KeyError, TypeError): pass
permissions = dict(screen_recording=None, accessibility=None)
permissions_from_log = False
if mac:
    # No TCC database access. Only explicit denial in the latest Sunshine startup is evidence.
    log = pathlib.Path(values.get('log_path', str(appdata / 'sunshine.log')))
    if not log.is_absolute(): log = appdata / log
    try:
        with log.open('rb') as source:
            source.seek(0, 2); source.seek(max(0, source.tell() - 262144))
            recent = source.read().decode('utf-8', errors='replace')
        starts = list(re.finditer(r'Sunshine version:', recent))
        if starts:
            recent = recent[starts[-1].start():]
            if 'No screen capture permission!' in recent:
                permissions['screen_recording'] = False
                permissions_from_log = True
    except OSError: pass
if mode == 'probe':
    print(json.dumps(dict(installed=installed, version=version, running=running,
        lan_encryption_mode=integer('lan_encryption_mode', 0), wan_encryption_mode=integer('wan_encryption_mode', 1),
        certificate_sha256=certificate_sha256, active_stream=active_stream, candidates=candidates,
        display_asleep=display_asleep() if mac else None, screen_locked=screen_locked() if mac else None,
        permissions=permissions if mac else None,
        permissions_from_log=permissions_from_log if mac else None)))
    sys.exit(0)
if not installed or (not app if mac else not unit):
    finish(False, 'No Sunshine application bundle found' if mac else 'No unambiguous Sunshine user unit found')
if active_stream:
    reply(False, 'Sunshine has an active or pending stream; encryption was not changed'); sys.exit(1)
if any(key in overrides for key in ('lan_encryption_mode', 'wan_encryption_mode')):
    reply(False, 'Encryption is overridden on the Sunshine command line'); sys.exit(1)
if not config.is_file() or config.is_symlink():
    reply(False, 'Sunshine configuration must be an existing regular file'); sys.exit(1)
try:
    if config.read_text() != content:
        reply(False, 'Sunshine configuration changed during inspection'); sys.exit(1)
    backup = str(config) + '.bak-miffan-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    shutil.copy2(config, backup)
    # Preserve unrelated settings and comments, remove every duplicate of these two keys.
    lines = [line for line in content.splitlines() if not re.match(r'^\s*(lan_encryption_mode|wan_encryption_mode)\s*=', line)]
    lines += ['lan_encryption_mode = 2', 'wan_encryption_mode = 2']
    fd, temporary = tempfile.mkstemp(prefix='.miffan-sunshine-', dir=config.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write('\n'.join(lines) + '\n'); stream.flush(); os.fsync(stream.fileno())
        shutil.copymode(config, temporary)
        # Recheck just before replacement/restart: a stream could have started during the backup.
        if stream_activity(strict=True):
            reply(False, 'Stream activity changed or cannot be verified; configuration was not changed'); sys.exit(1)
        if config.read_text() != content:
            reply(False, 'Sunshine configuration changed; no settings were replaced'); sys.exit(1)
        os.replace(temporary, config)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    if stream_activity(strict=True):
        reply(False, 'Encryption saved with backup; stream activity prevents restart'); sys.exit(1)
    if mac:
        stop_failed = False
        if running:
            try: stop_sunshine()
            except (OSError, ValueError): stop_failed = True
        # Reopen even if the stop timed out: Sunshine may still finish shutting down.
        command = ['open', '-a', str(app)]
        if args: command += ['--args'] + args
        restarted = run(command)
        if stop_failed: finish(False, 'Encryption saved with backup; Sunshine graceful quit failed')
    else:
        restarted = run(['systemctl', '--user', 'restart', unit])
    success = bool(restarted and restarted.returncode == 0)
    finish(success, 'Encryption enforced; Sunshine restarted' if success else 'Encryption saved with backup; Sunshine restart failed')
except ValueError as error:
    finish(False, str(error))
except OSError as error:
    detail = 'Cannot edit Sunshine state or control its service; check administrator rights' if error.errno in (errno.EACCES, errno.EPERM) else 'Could not back up or update Sunshine configuration'
    finish(False, detail)
SUNSHINE_PY
}

# --- Scheduled tasks ---------------------------------------------------------------------
# The app renders each task into files (meta.json, run, prompt) and the helper only installs
# the system timer and runs `run`; it never parses task JSON or knows which agent a task uses.
# Nothing here may need python3: the unattended runner must not trigger macOS's CLT prompt.

TASKS_DIR="$HOME/.miffan/tasks"
TASK_LOG_LIMIT=1048576
TASK_EXCERPT_BYTES=16384
TASK_KEEP_RUNS=30
TASK_PART_LIMIT=262144
# Timers run this copy, not ~/.miffan/bin/miffan: an older app connecting later reinstalls its
# own helper version, which must not silently break tasks a newer app created.
TASK_RUNNER="$HOME/.miffan/bin/miffan-task-runner"

task_fail() {
    printf '{"success":false,"detail":%s}\n' "$(json_str "$1")"
    exit 1
}

task_id_ok() {
    case "$1" in ''|*[!A-Za-z0-9-]*) return 1 ;; esac
    [ "${#1}" -le 64 ]
}

task_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# Kills a process and all of its descendants, children first.
task_kill_tree() {
    for child in $(pgrep -P "$1" 2>/dev/null); do task_kill_tree "$child" "$2"; done
    kill "-$2" "$1" 2>/dev/null
}

# Runs a command with stdout kept, killing it after $1 seconds. Used where a user's shell
# startup files or a foreign CLI could hang an SSH call.
task_limited() {
    limit=$1
    shift
    "$@" &
    limited_pid=$!
    (sleep "$limit"; task_kill_tree "$limited_pid" KILL) >/dev/null 2>&1 </dev/null &
    limited_watcher=$!
    wait "$limited_pid"
    limited_status=$?
    task_kill_tree "$limited_watcher" KILL
    return "$limited_status"
}

task_user_bus() {
    [ -n "${XDG_RUNTIME_DIR:-}" ] || XDG_RUNTIME_DIR="/run/user/$(id -u)"
    [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
    export XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS
}

task_scheduler() {
    case "$(os)" in
        macos) echo launchd; return ;;
        linux)
            task_user_bus
            if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
                echo systemd
                return
            fi
            ;;
    esac
    if command -v crontab >/dev/null 2>&1; then echo cron; else echo none; fi
}

task_timezone() {
    zone=""
    command -v timedatectl >/dev/null 2>&1 && zone=$(timedatectl show -p Timezone --value 2>/dev/null)
    if [ -z "$zone" ]; then
        link=$(readlink /etc/localtime 2>/dev/null) || link=""
        case "$link" in *zoneinfo/*) zone=${link##*zoneinfo/} ;; esac
    fi
    printf '%s' "$zone"
}

task_probe() {
    scheduler=$(task_scheduler)
    linger=null
    login_required=false
    case "$scheduler" in
        launchd) login_required=true ;;
        systemd)
            value=$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null) || value=""
            case "$value" in yes) linger=true ;; no) linger=false; login_required=true ;; esac
            ;;
    esac
    printf '{"scheduler":"%s","linger":%s,"login_required":%s,"timezone":%s,"utc_offset":"%s"}\n' \
        "$scheduler" "$linger" "$login_required" "$(json_or_null "$(task_timezone)")" "$(date +%z)"
}

# Non-POSIX shells cannot run the -c snippets below; their users can still give absolute paths.
task_login_shell() {
    user_shell=${SHELL:-/bin/sh}
    case "${user_shell##*/}" in fish|nu|xonsh|elvish) user_shell=/bin/sh ;; esac
    printf '%s' "$user_shell"
}

# Exports the variables named in $1 (one per line) with their current values from the user's
# interactive login shell. Timers start without the user's proxy or tool settings, and reading
# them at run time keeps their values on this machine and follows later changes.
task_apply_env() {
    names=$(tr '\n' ' ' <"$1")
    [ -n "${names% }" ] || return 0
    sep=$(printf '\036')
    # shellcheck disable=SC2016,SC2086
    out=$(task_limited 15 "$(task_login_shell)" -lic 'for n in "$@"; do eval "[ -n \"\${$n+x}\" ]" || continue; eval "v=\${$n}"; printf "\036%s=%s" "$n" "$v"; done; printf "\036\n"' miffan $names </dev/null 2>/dev/null) || :
    case "$out" in *"$sep"*) ;; *) return 0 ;; esac
    # Lets the app's run script keep its fallback PATH only when the login shell gave nothing.
    export MIFFAN_LOGIN_ENV=ok
    rest=${out#*"$sep"}
    while [ -n "$rest" ]; do
        pair=${rest%%"$sep"*}
        case "$rest" in *"$sep"*) rest=${rest#*"$sep"} ;; *) rest="" ;; esac
        case "$pair" in *=*) ;; *) continue ;; esac
        name=${pair%%=*}
        case " $names " in *" $name "*) export "$name=${pair#*=}" ;; esac
    done
}

# Resolves an executable the way the user's interactive login shell would, because timers start
# with a bare PATH and tools installed through npm/nvm often live only in shell startup files.
task_which() {
    name=${1:-}
    case "$name" in ''|*'
'*) task_fail "invalid executable" ;; esac
    case "${2:-}" in ''|-[A-Za-z-]*) ;; *) task_fail "invalid version argument" ;; esac
    case "${2:-}" in *[!A-Za-z-]*) task_fail "invalid version argument" ;; esac
    sep=$(printf '\036')
    user_shell=$(task_login_shell)
    # shellcheck disable=SC2016
    out=$(task_limited 15 "$user_shell" -lic 'p=$(command -v -- "$1" 2>/dev/null) || p=""; printf "\036%s\036%s\036\n" "$p" "$PATH"' miffan "$name" </dev/null 2>/dev/null) || :
    case "$out" in *"$sep"*"$sep"*"$sep"*) ;; *) out="$sep$sep$PATH$sep" ;; esac
    rest=${out#*"$sep"}
    path=${rest%%"$sep"*}
    rest=${rest#*"$sep"}
    path_env=${rest%%"$sep"*}
    case "$name" in /*) path=$name ;; esac
    # An alias or shell function is not something a timer can run.
    case "$path" in /*) [ -x "$path" ] && [ ! -d "$path" ] || path="" ;; *) path="" ;; esac
    version=""
    if [ -n "$path" ] && [ -n "${2:-}" ]; then
        version=$(task_limited 15 "$path" "$2" </dev/null 2>&1 | sed -n '/[^[:space:]]/{p;q;}' | cut -c1-200)
    fi
    printf '{"path":%s,"path_env":%s,"version":%s}\n' "$(json_or_null "$path")" "$(json_or_null "$path_env")" "$(json_or_null "$version")"
}

task_time_ok() {
    case "$1" in [01][0-9]:[0-5][0-9]|2[0-3]:[0-5][0-9]) return 0 ;; *) return 1 ;; esac
}

task_days_ok() {
    case "$1" in ''|*[!0-6,]*|,*|*,|*,,*) return 1 ;; esac
    case ",$1," in *[0-6][0-6]*) return 1 ;; esac
    [ -z "$(printf '%s\n' "$1" | tr ',' '\n' | sort | uniq -d)" ]
}

task_schedule_ok() {
    case "${1:-}" in
        daily) [ "$#" -eq 2 ] && task_time_ok "$2" ;;
        weekly) [ "$#" -eq 3 ] && task_days_ok "$2" && task_time_ok "$3" ;;
        interval) [ "$#" -eq 2 ] && case "$2" in 15|30|60|120|180|240|360|480|720) true ;; *) false ;; esac ;;
        *) return 1 ;;
    esac
}

task_install_runner() {
    mkdir -p "${TASK_RUNNER%/*}" && cp "$0" "$TASK_RUNNER.tmp" && chmod 700 "$TASK_RUNNER.tmp" && mv -f "$TASK_RUNNER.tmp" "$TASK_RUNNER"
}

task_hour() { h=${1%:*}; echo $((${h#0} + 0)); }
task_minute() { m=${1#*:}; echo $((${m#0} + 0)); }

task_unit() { echo "miffan-task-$1"; }
task_label() { echo "me.ayuilos.miffan.task.$1"; }
task_plist() { echo "$HOME/Library/LaunchAgents/$(task_label "$1").plist"; }

# bootout returns before the job is gone, so an immediate bootstrap can fail with EIO.
task_launchd_load() {
    launchctl bootout "gui/$(id -u)/$1" >/dev/null 2>&1
    for _ in 1 2 3 4 5; do
        launchctl bootstrap "gui/$(id -u)" "$2" >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}

task_xml() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

task_systemd_write() {
    id=$1
    shift
    unit_dir="$HOME/.config/systemd/user"
    unit=$(task_unit "$id")
    mkdir -p "$unit_dir" || return 1
    {
        printf '[Unit]\nDescription=Miffan task %s\n\n[Service]\nType=oneshot\nTimeoutStartSec=infinity\n' "$id"
        printf 'ExecStart=/bin/sh "%%h/.miffan/bin/miffan-task-runner" task exec %s\n' "$id"
    } >"$unit_dir/$unit.service.tmp" && mv -f "$unit_dir/$unit.service.tmp" "$unit_dir/$unit.service" || return 1
    {
        # The default AccuracySec=1min would start a task up to a minute after the time the user set.
        printf '[Unit]\nDescription=Miffan task %s timer\n\n[Timer]\nAccuracySec=1s\n' "$id"
        case "$1" in
            daily) printf 'OnCalendar=*-*-* %s:00\nPersistent=true\n' "$2" ;;
            weekly)
                names=$(printf '%s\n' "$2" | tr ',' '\n' | sed 's/0/Sun/;s/1/Mon/;s/2/Tue/;s/3/Wed/;s/4/Thu/;s/5/Fri/;s/6/Sat/' | paste -sd, -)
                printf 'OnCalendar=%s *-*-* %s:00\nPersistent=true\n' "$names" "$3"
                ;;
            interval) printf 'OnActiveSec=%smin\nOnUnitActiveSec=%smin\n' "$2" "$2" ;;
        esac
        printf '\n[Install]\nWantedBy=timers.target\n'
    } >"$unit_dir/$unit.timer.tmp" && mv -f "$unit_dir/$unit.timer.tmp" "$unit_dir/$unit.timer"
}

task_launchd_write() {
    id=$1
    shift
    mkdir -p "$HOME/Library/LaunchAgents" || return 1
    plist=$(task_plist "$id")
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n'
        printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        printf '<plist version="1.0">\n<dict>\n<key>Label</key><string>%s</string>\n' "$(task_label "$id")"
        printf '<key>ProgramArguments</key><array><string>/bin/sh</string><string>%s</string><string>task</string><string>exec</string><string>%s</string></array>\n' \
            "$(task_xml "$TASK_RUNNER")" "$id"
        case "$1" in
            daily) printf '<key>StartCalendarInterval</key><dict><key>Hour</key><integer>%s</integer><key>Minute</key><integer>%s</integer></dict>\n' "$(task_hour "$2")" "$(task_minute "$2")" ;;
            weekly)
                printf '<key>StartCalendarInterval</key><array>'
                for day in $(printf '%s\n' "$2" | tr ',' ' '); do
                    printf '<dict><key>Weekday</key><integer>%s</integer><key>Hour</key><integer>%s</integer><key>Minute</key><integer>%s</integer></dict>' "$day" "$(task_hour "$3")" "$(task_minute "$3")"
                done
                printf '</array>\n'
                ;;
            interval) printf '<key>StartInterval</key><integer>%s</integer>\n' "$(($2 * 60))" ;;
        esac
        printf '<key>ProcessType</key><string>Standard</string>\n'
        printf '<key>StandardOutPath</key><string>/dev/null</string>\n<key>StandardErrorPath</key><string>/dev/null</string>\n'
        printf '</dict>\n</plist>\n'
    } >"$plist.tmp" && mv -f "$plist.tmp" "$plist"
}

task_cron_spec() {
    case "$1" in
        daily) printf '%s %s * * *' "$(task_minute "$2")" "$(task_hour "$2")" ;;
        weekly) printf '%s %s * * %s' "$(task_minute "$3")" "$(task_hour "$3")" "$2" ;;
        interval)
            if [ "$2" -lt 60 ]; then printf '*/%s * * * *' "$2"; else printf '0 */%s * * *' "$(($2 / 60))"; fi
            ;;
    esac
}

task_crontab_without() {
    crontab -l 2>/dev/null | grep -v "# miffan-task:$1\$"
}

# Installs (or refreshes) the timer for an enabled task. $1 id; the schedule follows.
task_timer_install() {
    id=$1
    task_install_runner || return 1
    backend=$(cat "$TASKS_DIR/$id/backend")
    case "$backend" in
        systemd)
            task_user_bus
            task_systemd_write "$@" || return 1
            systemctl --user daemon-reload && systemctl --user enable "$(task_unit "$id").timer" >/dev/null 2>&1 &&
                systemctl --user restart "$(task_unit "$id").timer"
            ;;
        launchd)
            task_launchd_write "$@" || return 1
            task_launchd_load "$(task_label "$id")" "$(task_plist "$id")"
            ;;
        cron)
            case "$HOME" in *%*|*'"'*) return 1 ;; esac
            shift
            line="$(task_cron_spec "$@") /bin/sh \"$TASK_RUNNER\" task exec $id # miffan-task:$id"
            { task_crontab_without "$id"; printf '%s\n' "$line"; } | crontab -
            ;;
        *) return 1 ;;
    esac
}

# Stops the timer but keeps what `task run` needs. Also used before removing a task.
task_timer_uninstall() {
    id=$1
    backend=$(cat "$TASKS_DIR/$id/backend" 2>/dev/null) || backend=""
    case "$backend" in
        systemd)
            task_user_bus
            systemctl --user disable --now "$(task_unit "$id").timer" >/dev/null 2>&1
            ;;
        launchd)
            launchctl bootout "gui/$(id -u)/$(task_label "$id")" >/dev/null 2>&1
            rm -f "$(task_plist "$id")"
            ;;
        cron)
            current=$(crontab -l 2>/dev/null) || current=""
            case "$current" in *"# miffan-task:$id"*) task_crontab_without "$id" | crontab - ;; esac
            ;;
    esac
    return 0
}

task_timer_ok() {
    id=$1
    case "$(cat "$TASKS_DIR/$id/backend" 2>/dev/null)" in
        systemd) task_user_bus; systemctl --user is-active --quiet "$(task_unit "$id").timer" ;;
        launchd) launchctl print "gui/$(id -u)/$(task_label "$id")" >/dev/null 2>&1 ;;
        cron) crontab -l 2>/dev/null | grep -q "# miffan-task:$id\$" ;;
        *) return 1 ;;
    esac
}

task_stage() {
    id=${1:-}
    part=${2:-}
    task_id_ok "$id" || task_fail "invalid task id"
    case "$part" in meta) file=meta.json ;; run|prompt|env) file=$part ;; *) task_fail "invalid part" ;; esac
    staging="$TASKS_DIR/.staging/$id"
    mkdir -p "$staging" || task_fail "cannot create task directory"
    head -c $((TASK_PART_LIMIT + 1)) >"$staging/$file.tmp" || task_fail "cannot write task file"
    size=$(wc -c <"$staging/$file.tmp" | tr -d ' ')
    if [ "$size" -gt "$TASK_PART_LIMIT" ]; then
        rm -f "$staging/$file.tmp"
        task_fail "task file too large"
    fi
    if [ "$part" = env ]; then
        count=0
        while IFS= read -r name || [ -n "$name" ]; do
            count=$((count + 1))
            case "$name" in [A-Za-z_]*) ;; *) rm -f "$staging/$file.tmp"; task_fail "invalid variable name" ;; esac
            case "$name" in *[!A-Za-z0-9_]*) rm -f "$staging/$file.tmp"; task_fail "invalid variable name" ;; esac
        done <"$staging/$file.tmp"
        [ "$count" -le 64 ] || { rm -f "$staging/$file.tmp"; task_fail "too many variables"; }
    fi
    mv -f "$staging/$file.tmp" "$staging/$file" || task_fail "cannot write task file"
    printf '{"success":true}\n'
}

task_commit_fail() {
    rm -rf "$staging"
    task_fail "$1"
}

# task commit <id> <timeout-seconds> <schedule...>
task_commit() {
    id=${1:-}
    timeout=${2:-}
    task_id_ok "$id" || task_fail "invalid task id"
    # Staged files are consumed by every commit, failed or not, so stale parts never leak in.
    staging="$TASKS_DIR/.staging/$id"
    [ "$#" -ge 3 ] || task_commit_fail "missing schedule"
    shift 2
    case "$timeout" in ''|*[!0-9]*) task_commit_fail "invalid timeout" ;; esac
    if [ "$timeout" -lt 60 ] || [ "$timeout" -gt 86400 ]; then task_commit_fail "invalid timeout"; fi
    task_schedule_ok "$@" || task_commit_fail "invalid schedule"
    for file in meta.json run prompt env; do
        [ -f "$staging/$file" ] || task_commit_fail "missing staged $file"
    done
    backend=$(task_scheduler)
    [ "$backend" != none ] || task_commit_fail "no scheduler available"
    dir="$TASKS_DIR/$id"
    created=false
    if [ ! -d "$dir" ]; then
        created=true
        mkdir -p "$dir/runs" || task_fail "cannot create task directory"
        : >"$dir/enabled"
    fi
    mkdir -p "$dir/runs"
    [ "$created" = true ] || task_timer_uninstall "$id"
    printf '%s\n' "$*" >"$staging/schedule"
    printf '%s\n' "$timeout" >"$staging/timeout"
    printf '%s\n' "$backend" >"$staging/backend"
    for file in meta.json run prompt env schedule timeout backend; do
        mv -f "$staging/$file" "$dir/$file" || task_fail "cannot write task file"
    done
    rm -rf "$staging"
    if [ -f "$dir/enabled" ] && ! task_timer_install "$id" "$@"; then
        task_timer_uninstall "$id"
        if [ "$created" = true ]; then
            rm -rf "${TASKS_DIR:?}/$id"
        else
            rm -f "$TASKS_DIR/$id/enabled"
        fi
        if [ "$backend" = launchd ]; then task_fail "login_required"; fi
        task_fail "cannot install $backend timer"
    fi
    printf '{"success":true,"backend":"%s","enabled":%s}\n' "$backend" "$([ -f "$dir/enabled" ] && echo true || echo false)"
}

task_dir_or_fail() {
    task_id_ok "${1:-}" || task_fail "invalid task id"
    [ -f "$TASKS_DIR/$1/run" ] || task_fail "no such task"
}

task_set_enabled() {
    task_dir_or_fail "$2"
    if [ "$1" = true ]; then
        : >"$TASKS_DIR/$2/enabled"
        # shellcheck disable=SC2046
        if ! task_timer_install "$2" $(cat "$TASKS_DIR/$2/schedule"); then
            task_timer_uninstall "$2"
            rm -f "$TASKS_DIR/$2/enabled"
            task_fail "cannot install $(cat "$TASKS_DIR/$2/backend") timer"
        fi
    else
        rm -f "$TASKS_DIR/$2/enabled"
        task_timer_uninstall "$2"
    fi
    printf '{"success":true}\n'
}

task_remove_one() {
    task_timer_uninstall "$1"
    case "$(cat "$TASKS_DIR/$1/backend" 2>/dev/null)" in
        systemd)
            rm -f "$HOME/.config/systemd/user/$(task_unit "$1").service" "$HOME/.config/systemd/user/$(task_unit "$1").timer"
            systemctl --user daemon-reload >/dev/null 2>&1
            ;;
        launchd) launchctl bootout "gui/$(id -u)/$(task_label "$1").now" >/dev/null 2>&1 ;;
    esac
    rm -rf "${TASKS_DIR:?}/$1"
}

task_remove() {
    if [ "${1:-}" = --all ]; then
        for dir in "$TASKS_DIR"/*/; do
            [ -d "$dir" ] || continue
            id=${dir%/}
            id=${id##*/}
            task_id_ok "$id" && task_remove_one "$id"
        done
        rm -rf "$TASKS_DIR/.staging"
    else
        task_id_ok "${1:-}" || task_fail "invalid task id"
        [ -d "$TASKS_DIR/$1" ] || task_fail "no such task"
        task_remove_one "$1"
    fi
    for dir in "$TASKS_DIR"/*/; do
        [ -d "$dir" ] && { printf '{"success":true}\n'; return; }
    done
    rm -f "$TASK_RUNNER"
    printf '{"success":true}\n'
}

# Starts one run now, through the same scheduler as timed runs so it sees the same environment.
task_run_now() {
    task_dir_or_fail "${1:-}"
    id=$1
    task_install_runner || task_fail "cannot install task runner"
    case "$(cat "$TASKS_DIR/$id/backend")" in
        systemd)
            task_user_bus
            systemctl --user start --no-block "$(task_unit "$id").service" >/dev/null 2>&1 || task_fail "cannot start task"
            ;;
        launchd)
            # A one-off job kept outside LaunchAgents, so it never loads again at login.
            label="$(task_label "$id").now"
            plist="$TASKS_DIR/$id/now.plist"
            {
                printf '<?xml version="1.0" encoding="UTF-8"?>\n'
                printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
                printf '<plist version="1.0">\n<dict>\n<key>Label</key><string>%s</string>\n' "$label"
                printf '<key>ProgramArguments</key><array><string>/bin/sh</string><string>%s</string><string>task</string><string>exec</string><string>%s</string></array>\n' \
                    "$(task_xml "$TASK_RUNNER")" "$id"
                printf '<key>RunAtLoad</key><true/>\n<key>ProcessType</key><string>Standard</string>\n'
                printf '<key>StandardOutPath</key><string>/dev/null</string>\n<key>StandardErrorPath</key><string>/dev/null</string>\n'
                printf '</dict>\n</plist>\n'
            } >"$plist"
            task_launchd_load "$label" "$plist" || task_fail "login_required"
            ;;
        cron)
            nohup /bin/sh "$TASK_RUNNER" task exec "$id" </dev/null >/dev/null 2>&1 &
            ;;
        *) task_fail "no scheduler available" ;;
    esac
    printf '{"success":true}\n'
}

task_record() {
    # $1 dir, $2 id, $3 started, $4 status, $5 exit code or null, $6 log bytes, $7 truncated, $8 log file
    run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
    [ -z "$8" ] || mv -f "$8" "$1/runs/$run_id.log"
    printf '{"run_id":"%s","task_id":"%s","started_at":"%s","ended_at":"%s","exit_code":%s,"status":"%s","log_bytes":%s,"truncated":%s}\n' \
        "$run_id" "$2" "$3" "$(task_now)" "$5" "$4" "$6" "$7" >"$1/runs/.$run_id.json" &&
        mv -f "$1/runs/.$run_id.json" "$1/runs/$run_id.json"
    count=$(find "$1/runs" -name '*.json' ! -name '.*' | wc -l | tr -d ' ')
    if [ "$count" -gt "$TASK_KEEP_RUNS" ]; then
        find "$1/runs" -name '*.json' ! -name '.*' | sort | head -n $((count - TASK_KEEP_RUNS)) | while IFS= read -r old; do
            rm -f "$old" "${old%.json}.log"
        done
    fi
}

task_exec() {
    task_id_ok "${1:-}" || exit 2
    id=$1
    dir="$TASKS_DIR/$id"
    [ -f "$dir/run" ] || exit 2
    cd "$HOME" || exit 2
    mkdir -p "$dir/runs"
    started=$(task_now)
    lock="$dir/lock"
    if ! mkdir "$lock" 2>/dev/null; then
        holder=$(cat "$lock/pid" 2>/dev/null) || holder=""
        stale=false
        if [ -n "$holder" ]; then
            kill -0 "$holder" 2>/dev/null || stale=true
        elif [ -n "$(find "$lock" -maxdepth 0 -mmin +1440 2>/dev/null)" ]; then
            stale=true
        fi
        if [ "$stale" = true ]; then rm -rf "$lock"; fi
        if [ "$stale" != true ] || ! mkdir "$lock" 2>/dev/null; then
            task_record "$dir" "$id" "$started" skipped_overlap null 0 false ""
            exit 0
        fi
    fi
    echo "$$" >"$lock/pid"
    trap 'rm -rf "$lock"' EXIT
    timeout=$(cat "$dir/timeout" 2>/dev/null) || timeout=600
    input=/dev/null
    [ -s "$dir/prompt" ] && input="$dir/prompt"
    [ ! -f "$dir/env" ] || task_apply_env "$dir/env"
    log="$dir/runs/.current.log"
    /bin/sh "$dir/run" <"$input" >"$log" 2>&1 &
    child=$!
    (sleep "$timeout"; : >"$lock/timed-out"; task_kill_tree "$child" TERM; sleep 10; task_kill_tree "$child" KILL) >/dev/null 2>&1 </dev/null &
    watcher=$!
    wait "$child"
    code=$?
    task_kill_tree "$watcher" KILL
    status=ok
    if [ -f "$lock/timed-out" ]; then
        status=timeout
    elif [ "$code" -ne 0 ]; then
        status=failed
    fi
    bytes=$(wc -c <"$log" | tr -d ' ')
    truncated=false
    if [ "$bytes" -gt "$TASK_LOG_LIMIT" ]; then
        tail -c "$TASK_LOG_LIMIT" "$log" >"$log.tail" && mv -f "$log.tail" "$log"
        truncated=true
    fi
    task_record "$dir" "$id" "$started" "$status" "$code" "$bytes" "$truncated" "$log"
}

task_last_run() {
    last=$(find "$1/runs" -name '*.json' ! -name '.*' 2>/dev/null | sort | tail -n 1)
    if [ -n "$last" ]; then tr -d '\n' <"$last"; else printf null; fi
}

task_list() {
    printf '{"scheduler":"%s","tasks":[' "$(task_scheduler)"
    first=true
    for dir in "$TASKS_DIR"/*/; do
        [ -f "${dir}run" ] || continue
        id=${dir%/}
        id=${id##*/}
        task_id_ok "$id" || continue
        [ "$first" = true ] || printf ','
        first=false
        enabled=false
        [ -f "${dir}enabled" ] && enabled=true
        timer_ok=false
        task_timer_ok "$id" && timer_ok=true
        printf '{"id":"%s","enabled":%s,"timer_ok":%s,"backend":%s,"schedule":%s,"timeout":%s,"meta_b64":"%s","last_run":%s}' \
            "$id" "$enabled" "$timer_ok" "$(json_or_null "$(cat "${dir}backend" 2>/dev/null)")" \
            "$(json_or_null "$(cat "${dir}schedule" 2>/dev/null)")" "$(cat "${dir}timeout" 2>/dev/null || echo null)" \
            "$(base64 <"${dir}meta.json" | tr -d '\n')" "$(task_last_run "$dir")"
    done
    printf ']}\n'
}

task_sync() {
    cursor=${1:-}
    case "$cursor" in *[!0-9TZ-]*) task_fail "invalid cursor" ;; esac
    latest=$cursor
    printf '{"runs":['
    first=true
    for record in "$TASKS_DIR"/*/runs/*.json; do
        [ -f "$record" ] || continue
        run_id=${record##*/}
        run_id=${run_id%.json}
        if [ -n "$cursor" ] && ! expr "x$run_id" \> "x$cursor" >/dev/null; then continue; fi
        if [ -z "$latest" ] || expr "x$run_id" \> "x$latest" >/dev/null; then latest=$run_id; fi
        [ "$first" = true ] || printf ','
        first=false
        excerpt=""
        [ -f "${record%.json}.log" ] && excerpt=$(tail -c "$TASK_EXCERPT_BYTES" "${record%.json}.log" | base64 | tr -d '\n')
        body=$(tr -d '\n' <"$record")
        printf '%s,"excerpt_b64":"%s"}' "${body%\}}" "$excerpt"
    done
    printf '],"cursor":%s}\n' "$(json_or_null "$latest")"
}

task_log() {
    task_id_ok "${1:-}" || task_fail "invalid task id"
    case "${2:-}" in ''|*[!0-9TZ-]*) task_fail "invalid run id" ;; esac
    [ -f "$TASKS_DIR/$1/runs/$2.log" ] || task_fail "no such run"
    cat "$TASKS_DIR/$1/runs/$2.log"
}

task() {
    umask 077
    action=${1:-}
    [ "$#" -eq 0 ] || shift
    case "$action" in
        probe) task_probe ;;
        which) task_which "$@" ;;
        stage) task_stage "$@" ;;
        commit) task_commit "$@" ;;
        list) task_list ;;
        remove) task_remove "$@" ;;
        enable) task_set_enabled true "${1:-}" ;;
        disable) task_set_enabled false "${1:-}" ;;
        run) task_run_now "$@" ;;
        sync) task_sync "$@" ;;
        log) task_log "$@" ;;
        exec) task_exec "$@" ;;
        *) echo "usage: miffan task probe|which|stage|commit|list|remove|enable|disable|run|sync|log|exec" >&2; exit 2 ;;
    esac
}

# --- Cua Spaces (a partner's own computer) ------------------------------------------------
# Wraps the `cua` CLI. Every `space` command prints one JSON line on stdout; diagnostics go to
# stderr and never carry the Space token.

# On the Space's desktop, so it is in sight once its screen is shown.
SPACE_HANDOFF_DIR=/home/cua/Desktop/Shared

# {"error":code,"detail":text|null}, exit 1.
space_fail() {
    printf '{"error":%s,"detail":%s}\n' "$(json_str "$1")" "$(json_or_null "${2:-}")"
    exit 1
}

# The last non-empty line of $1, as the human-readable detail of a failure.
space_last_line() {
    printf '%s\n' "$1" | sed -e 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | tail -n 1
}

# Space names are app-generated.
space_name_ok() {
    printf '%s' "$1" | grep -Eq '^miffan-[0-9a-f]{8}$'
}

space_need_name() {
    space_name_ok "${1:-}" || { printf '{"error":"bad_name","detail":null}\n'; exit 2; }
}

space_cua_path() {
    for candidate in "$(command -v cua 2>/dev/null)" "$HOME/.local/bin/cua" /opt/homebrew/bin/cua /usr/local/bin/cua; do
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

space_cua_version() {
    "$1" --version 2>/dev/null | head -n 1 | sed -n 's/.*[^0-9.]\([0-9][0-9]*\.[0-9][0-9.]*\).*/\1/p'
}

# The daemon and everything it spawns get the computer's proxy, but never for loopback: the
# Space ports and the daemon itself live there.
space_env() {
    cua_proxy
    export PATH="$HOME/.local/bin:$PATH" CUA_TELEMETRY=0
    export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}" no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"
}

space_daemon_running() {
    out=$("$SPACE_CUA" daemon status 2>&1) || return 1
    printf '%s' "$out" | grep -q 'pid [0-9]'
}

space_daemon_ensure() {
    space_daemon_running && return 0
    err=$("$SPACE_CUA" daemon start 2>&1 >/dev/null) || space_fail daemon_failed "$(space_last_line "$err")"
    tries=0
    while [ "$tries" -lt 20 ]; do
        space_daemon_running && return 0
        sleep 1
        tries=$((tries + 1))
    done
    space_fail daemon_failed "daemon did not start"
}

# Finds cua and makes sure its daemon runs.
space_ready() {
    SPACE_CUA=$(space_cua_path) || space_fail cua_missing
    space_env
    space_daemon_ensure
}

# running, stopped, ... or missing when cua does not know the Space or cannot report it.
space_state() {
    out=$("$SPACE_CUA" --json sb info "$1" 2>/dev/null) || { echo missing; return 0; }
    st=$(printf '%s' "$out" | tr -d '\n\r' | grep -o '"state": *"[^"]*"' | head -n 1 | sed 's/.*: *"\(.*\)"/\1/')
    echo "${st:-missing}"
}

space_port() {
    "$SPACE_CUA" --json sb info "$1" 2>/dev/null | tr -d '\n\r' | grep -o '"env": *"[^"]*"' | head -n 1 | sed -n 's/.*:\([0-9][0-9]*\)"$/\1/p'
}

space_known() {
    "$SPACE_CUA" --json sb ls 2>/dev/null | tr -d '\n\r' | grep -Eq "\"id\" *: *\"local:$1\""
}

# Waits until the Space runs and its loopback port is published (it changes on every start).
space_wait_running() {
    tries=0
    limit=${MIFFAN_SPACE_WAIT:-90}
    while [ "$tries" -lt "$limit" ]; do
        if [ "$(space_state "$1")" = running ] && [ -n "$(space_port "$1")" ]; then return 0; fi
        sleep 1
        tries=$((tries + 1))
    done
    return 1
}

space_start_do() {
    space_known "$1" || space_fail not_found
    if [ "$(space_state "$1")" != running ]; then
        err=$("$SPACE_CUA" spaces start "local:$1" 2>&1 >/dev/null) || space_fail start_failed "$(space_last_line "$err")"
    fi
    space_wait_running "$1" || space_fail start_failed "not running after start"
}

space_token() {
    [ -r "$HOME/.cua/spaces-credentials.json" ] || return 1
    tok=$(tr -d '\n\r ' < "$HOME/.cua/spaces-credentials.json" | sed -n 's/.*"local:'"$1"'":{[^}]*"token":"\([^"]*\)".*/\1/p')
    [ -n "$tok" ] && printf '%s' "$tok"
}

# The cua-driver version inside the Space, from the MCP initialize reply; null when unreadable.
# The token goes to curl through its config on stdin, not through argv.
space_driver_version() {
    tok=$(space_token "$1") || return 0
    port=$(space_port "$1")
    [ -n "$port" ] || return 0
    printf 'header = "authorization: Bearer %s"\n' "$tok" |
        curl -sS -m 5 --noproxy '*' -K - "http://127.0.0.1:$port/mcp" -H 'content-type: application/json' \
            -H 'accept: application/json, text/event-stream' \
            -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"miffan","version":"1"}}}' 2>/dev/null |
        tr -d '\n\r' | grep -o '"serverInfo": *{[^}]*}' | grep -o '"version": *"[^"]*"' | head -n 1 | sed 's/.*: *"\(.*\)"/\1/'
}

space_disk_free_gb() {
    df -Pk "$HOME" 2>/dev/null | awk 'NR == 2 {print int($4 / 1048576)}'
}

space_docker_json() {
    command -v docker >/dev/null 2>&1 || { printf '{"ok":false,"detail":"docker not installed"}'; return 0; }
    if err=$(docker info --format '{{.ServerVersion}}' 2>&1 >/dev/null); then
        printf '{"ok":true,"detail":null}'
    else
        printf '{"ok":false,"detail":%s}' "$(json_or_null "$(space_last_line "$err")")"
    fi
}

space_list_json() {
    ids=$("$SPACE_CUA" --json sb ls 2>/dev/null | tr -d '\n\r' | grep -o '"id": *"local:miffan-[0-9a-z]*"' | sed 's/.*"local:\(.*\)"/\1/')
    sep=""
    printf '['
    for id in $ids; do
        space_name_ok "$id" || continue
        printf '%s{"id":"local:%s","state":%s}' "$sep" "$id" "$(json_str "$(space_state "$id")")"
        sep=","
    done
    printf ']'
}

space_probe() {
    path=""
    version=""
    running=false
    spaces='[]'
    if SPACE_CUA=$(space_cua_path); then
        path=$SPACE_CUA
        version=$(space_cua_version "$SPACE_CUA")
        space_env
        if space_daemon_running; then
            running=true
            spaces=$(space_list_json)
        fi
    fi
    if [ "$(os)" = linux ]; then docker=$(space_docker_json); else docker=null; fi
    printf '{"cua":{"path":%s,"version":%s},"daemon":{"running":%s},"docker":%s,"disk_free_gb":%s,"host":%s,"spaces":%s}\n' \
        "$(json_or_null "$path")" "$(json_or_null "$version")" "$running" "$docker" "$(space_disk_free_gb)" "$(space_host_json)" "$spaces"
}

# This computer's CPUs and memory and, on macOS, the Linux VM cua runs every Space in (they all share it).
space_host_json() {
    if [ "$(os)" = macos ]; then
        cpus=$(sysctl -n hw.ncpu 2>/dev/null)
        mem=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1048576 ))
        conf="$HOME/.cua/runtimes/linux/vm/cua/colima.yaml"
        vcpu=$(sed -n 's/^cpu: *\([0-9][0-9]*\).*/\1/p' "$conf" 2>/dev/null | head -n 1)
        vmem=$(sed -n 's/^memory: *\([0-9][0-9]*\).*/\1/p' "$conf" 2>/dev/null | head -n 1)
        if [ -n "$vcpu" ] && [ -n "$vmem" ]; then runtime="{\"cpus\":$vcpu,\"memory_mb\":$((vmem * 1024))}"; else runtime=null; fi
    else
        cpus=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null)
        mem=$(awk '/^MemTotal:/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null)
        runtime=null
    fi
    printf '{"cpus":%s,"memory_mb":%s,"runtime":%s}' "${cpus:-null}" "${mem:-null}" "$runtime"
}

# Validated CPU count and memory (MiB) for a Space: $SPACE_CPUS and $SPACE_MEMORY, or bad_input.
space_need_size() {
    case "${1:-}" in ''|*[!0-9]*) space_fail bad_input "cpus" ;; esac
    case "${2:-}" in ''|*[!0-9]*) space_fail bad_input "memory" ;; esac
    { [ "$1" -ge 1 ] && [ "$1" -le 64 ]; } || space_fail bad_input "cpus"
    { [ "$2" -ge 512 ] && [ "$2" -le 262144 ]; } || space_fail bad_input "memory"
    SPACE_CPUS=$1
    SPACE_MEMORY=$2
}

space_install() {
    space_env
    if ! SPACE_CUA=$(space_cua_path); then
        script=$(mktemp "${TMPDIR:-/tmp}/miffan-cua-install.XXXXXX") || space_fail download_failed "no temp file"
        if ! err=$(curl -fsSL --max-time 120 -o "$script" https://cua.ai/install.sh 2>&1); then
            rm -f "$script"
            space_fail download_failed "$(space_last_line "$err")"
        fi
        # The installer's output must not reach stdout, where the JSON line goes.
        if ! sh "$script" --cli-only --yes --no-modify-path --no-onboarding >&2; then
            rm -f "$script"
            space_fail download_failed "the cua installer failed"
        fi
        rm -f "$script"
        SPACE_CUA=$(space_cua_path) || space_fail download_failed "cua is still missing after the installer"
    fi
    space_daemon_ensure
    printf '{"cua":{"path":%s,"version":%s},"daemon":{"running":true}}\n' \
        "$(json_str "$SPACE_CUA")" "$(json_or_null "$(space_cua_version "$SPACE_CUA")")"
}

# Maps the text of a failed create to an error code.
space_create_code() {
    low=$(printf '%s' "$1" | tr 'A-Z' 'a-z')
    case "$low" in
        *"no space left"*|*"not enough space"*|*"disk full"*|*"insufficient disk"*|*"disk space"*) echo disk_low ;;
        *pull*|*download*|*proxy*|*"i/o timeout"*|*"tls handshake"*|*"dial tcp"*|*"no such host"*) echo download_failed ;;
        *docker*) echo needs_docker ;;
        *) echo create_failed ;;
    esac
}

space_create_do() {
    name=$1
    if [ "$(os)" = linux ]; then
        docker=$(space_docker_json)
        case "$docker" in '{"ok":true'*) ;; *) space_fail needs_docker "$(printf '%s' "$docker" | sed -n 's/.*"detail":"\([^"]*\)".*/\1/p')" ;; esac
    fi
    free=$(space_disk_free_gb)
    if [ -n "$free" ] && [ "$free" -lt 2 ]; then space_fail disk_low "${free} GB free"; fi
    errfile=$(mktemp "${TMPDIR:-/tmp}/miffan-space.XXXXXX") || space_fail create_failed "no temp file"
    out=$("$SPACE_CUA" spaces create linux --on local --kind container --runtime runc --name "$name" \
        --cpus "$SPACE_CPUS" --memory-mb "$SPACE_MEMORY" --json 2>"$errfile")
    status=$?
    stderr_text=$(cat "$errfile")
    rm -f "$errfile"
    flat=$(printf '%s' "$out" | tr -d '\n\r')
    msg=$(printf '%s' "$flat" | sed -n 's/.*"error": *"\([^"]*\)".*/\1/p' | head -n 1)
    if [ "$status" -ne 0 ] || [ -n "$msg" ] || ! printf '%s' "$flat" | grep -Eq "\"id\" *: *\"local:$name\""; then
        text="$msg"
        [ -n "$text" ] || text=$(space_last_line "$stderr_text")
        [ -n "$text" ] || text="cua spaces create failed"
        space_fail "$(space_create_code "$msg $stderr_text")" "$text"
    fi
    space_wait_running "$name" || space_fail create_failed "not running after create"
}

space_create() {
    space_create_do "$1"
    printf '{"id":"local:%s","state":"running"}\n' "$1"
}

space_info() {
    space_known "$1" || space_fail not_found
    space_start_do "$1"
    tok=$(space_token "$1") || space_fail not_found "no credentials for the Space"
    port=$(space_port "$1")
    proxy=$(space_share_ensure "$1")
    printf '{"id":"local:%s","state":"running","port":%s,"token":%s,"driver_version":%s,"proxy":%s}\n' \
        "$1" "$port" "$(json_str "$tok")" "$(json_or_null "$(space_driver_version "$1")")" "$(json_or_null "$proxy")"
}

space_start() {
    space_start_do "$1"
    printf '{"id":"local:%s","state":"running"}\n' "$1"
}

space_stop() {
    space_known "$1" || space_fail not_found
    err=$("$SPACE_CUA" spaces stop "local:$1" 2>&1 >/dev/null) || space_fail exec_failed "$(space_last_line "$err")"
    st=$(space_state "$1")
    case "$st" in running|missing) st=stopped ;; esac
    printf '{"id":"local:%s","state":%s}\n' "$1" "$(json_str "$st")"
}

space_delete_do() {
    space_known "$1" || space_fail not_found
    err=$("$SPACE_CUA" spaces delete "local:$1" --force 2>&1 >/dev/null) || space_fail exec_failed "$(space_last_line "$err")"
    ! space_known "$1" || space_fail exec_failed "the Space is still listed after delete"
}

space_delete() {
    space_delete_do "$1"
    space_share_forget "$1"
    printf '{"id":"local:%s","deleted":true}\n' "$1"
}

space_reset() {
    space_delete_do "$1"
    space_create_do "$1"
    # The new Space goes out the way the old one did.
    if [ "$(os)" = linux ] && [ -f "$SPACE_SHARE_DIR/spaces/$1" ] && space_share_net "$1"; then
        space_share_start && space_share_configure "$1" "$(space_share_proxy)"
    fi
    printf '{"id":"local:%s","state":"running"}\n' "$1"
}

# Changes a running Space's CPUs and memory in place (files stay). Linux only: on macOS the Spaces live in
# cua's own VM, where the app cannot reach their containers.
space_resize() {
    space_known "$1" || space_fail not_found
    [ "$(os)" = linux ] || space_fail unsupported "resizing needs a Linux host"
    err=$(docker update --cpus "$SPACE_CPUS" --memory "${SPACE_MEMORY}m" --memory-swap "${SPACE_MEMORY}m" "$1" 2>&1 >/dev/null) ||
        space_fail exec_failed "$(space_last_line "$err")"
    printf '{"id":"local:%s","cpus":%s,"memory_mb":%s}\n' "$1" "$SPACE_CPUS" "$SPACE_MEMORY"
}

# --- Network share (Linux) ---
# A proxy on the host, listening only on a Space bridge's gateway, through which Spaces reach the internet
# as the host does. Programs on the host (VPNs, transparent proxies such as v2rayA) often take over traffic
# forwarded from containers in ways that never get out, while the host's own connections work; the share
# makes the Space's connections the host's own. It refuses the host itself, loopback, link-local and other
# containers on the bridge, so a Space reaches nothing on the host through it.

SPACE_SHARE_DIR="$HOME/.miffan/netshare"
SPACE_SHARE_PORT=${MIFFAN_SPACE_SHARE_PORT:-32190}

space_share_relay_source() {
    cat <<'PYEOF'
import ipaddress, select, socket, subprocess, sys, threading, time, urllib.parse

LISTEN, PORT = sys.argv[1], int(sys.argv[2])
SUBNET = ipaddress.ip_network(sys.argv[3], strict=False)
slots = threading.BoundedSemaphore(256)
own, own_at = set(), 0.0

def own_addresses():
    global own, own_at
    if time.time() - own_at > 30:
        found = set()
        try:
            out = subprocess.run(['ip', '-o', 'addr'], capture_output=True, text=True, timeout=5).stdout
            for line in out.splitlines():
                parts = line.split()
                if len(parts) > 3:
                    found.add(ipaddress.ip_interface(parts[3]).ip)
        except Exception:
            pass
        own, own_at = found, time.time()
    return own

def allowed(text):
    ip = ipaddress.ip_address(text.split('%')[0])
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return not (ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_multicast
                or ip in SUBNET or ip in own_addresses())

def connect(host, port):
    error = OSError('no address')
    for family, kind, proto, _, address in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM):
        if not allowed(address[0]):
            error = PermissionError('refused')
            continue
        upstream = socket.socket(family, kind, proto)
        upstream.settimeout(15)
        try:
            upstream.connect(address)
            upstream.settimeout(None)
            return upstream
        except OSError as failure:
            error = failure
            upstream.close()
    raise error

def pipe(a, b):
    try:
        while True:
            ready, _, _ = select.select([a, b], [], [], 600)
            if not ready:
                return
            for side in ready:
                data = side.recv(65536)
                if not data:
                    return
                (b if side is a else a).sendall(data)
    except OSError:
        pass

def head(client):
    data = b''
    while b'\r\n\r\n' not in data:
        chunk = client.recv(8192)
        if not chunk or len(data) > 65536:
            return None, b''
        data += chunk
    top, rest = data.split(b'\r\n\r\n', 1)
    return top.decode('latin-1').split('\r\n'), rest

def reply(client, status):
    try:
        client.sendall(('HTTP/1.1 %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n' % status).encode())
    except OSError:
        pass

def serve(client, peer):
    upstream = None
    try:
        if ipaddress.ip_address(peer[0].split('%')[0]) not in SUBNET:
            return
        client.settimeout(30)
        lines, rest = head(client)
        if not lines:
            return
        method, target, version = lines[0].split(' ', 2)
        if method == 'CONNECT':
            host, _, port = target.rpartition(':')
            upstream = connect(host.strip('[]'), int(port))
            client.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
        else:
            url = urllib.parse.urlsplit(target)
            if url.scheme != 'http' or not url.hostname:
                return reply(client, '400 Bad Request')
            upstream = connect(url.hostname, url.port or 80)
            path = (url.path or '/') + ('?' + url.query if url.query else '')
            kept = [line for line in lines[1:] if line.split(':', 1)[0].strip().lower()
                    not in ('proxy-connection', 'proxy-authorization', 'connection', 'keep-alive')]
            upstream.sendall(('%s %s %s\r\n' % (method, path, version) + ''.join(line + '\r\n' for line in kept)
                              + 'Connection: close\r\n\r\n').encode('latin-1'))
        if rest:
            upstream.sendall(rest)
        client.settimeout(None)
        pipe(client, upstream)
    except PermissionError:
        reply(client, '403 Forbidden')
    except (OSError, ValueError):
        reply(client, '502 Bad Gateway')
    finally:
        client.close()
        if upstream is not None:
            upstream.close()
        slots.release()

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind((LISTEN, PORT))
server.listen(128)
print('listening', flush=True)
while True:
    client, peer = server.accept()
    if not slots.acquire(blocking=False):
        client.close()
        continue
    threading.Thread(target=serve, args=(client, peer), daemon=True).start()
PYEOF
}

# Sets SHARE_GW, SHARE_SUBNET and SHARE_IF (the bridge interface) for Space $1. Status 1 when unknown.
space_share_net() {
    line=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{$v.Gateway}} {{$v.IPAddress}}/{{$v.IPPrefixLen}}{{"\n"}}{{end}}' "$1" 2>/dev/null |
        grep . | head -n 1)
    net=${line%% *}
    rest=${line#* }
    SHARE_GW=${rest%% *}
    SHARE_SUBNET=${rest#* }
    printf '%s' "$SHARE_GW" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || return 1
    printf '%s' "$SHARE_SUBNET" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$' || return 1
    SHARE_IF=$(docker network inspect -f '{{index .Options "com.docker.network.bridge.name"}}' "$net" 2>/dev/null)
    [ -n "$SHARE_IF" ] || SHARE_IF="br-$(docker network inspect -f '{{.Id}}' "$net" 2>/dev/null | cut -c1-12)"
    printf '%s' "$SHARE_IF" | grep -Eq '^[A-Za-z0-9_.-]{1,15}$'
}

space_share_proxy() {
    printf 'http://%s:%s' "$SHARE_GW" "$SPACE_SHARE_PORT"
}

space_share_running() {
    pid=$(cat "$SPACE_SHARE_DIR/relay-$1.pid" 2>/dev/null) || return 1
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$pid" 2>/dev/null || return 1
    [ ! -r "/proc/$pid/cmdline" ] || tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q 'netshare/relay.py'
}

space_share_stop_all() {
    for pidfile in "$SPACE_SHARE_DIR"/relay-*.pid; do
        [ -f "$pidfile" ] || continue
        gw=${pidfile##*/relay-}
        gw=${gw%.pid}
        space_share_running "$gw" && kill "$(cat "$pidfile")" 2>/dev/null
        rm -f "$pidfile"
    done
}

# Makes sure the share for SHARE_GW runs (a new helper's relay replaces the old one). Status 1, with the
# reason in SHARE_ERROR, when it cannot.
space_share_start() {
    SHARE_ERROR=""
    command -v python3 >/dev/null 2>&1 || { SHARE_ERROR="python3 is not installed"; return 1; }
    mkdir -p "$SPACE_SHARE_DIR/spaces"
    space_share_relay_source > "$SPACE_SHARE_DIR/relay.py.new"
    if cmp -s "$SPACE_SHARE_DIR/relay.py.new" "$SPACE_SHARE_DIR/relay.py"; then
        rm -f "$SPACE_SHARE_DIR/relay.py.new"
    else
        space_share_stop_all
        mv "$SPACE_SHARE_DIR/relay.py.new" "$SPACE_SHARE_DIR/relay.py"
    fi
    space_share_running "$SHARE_GW" && return 0
    log="$SPACE_SHARE_DIR/relay-$SHARE_GW.log"
    : > "$log"
    if command -v setsid >/dev/null 2>&1; then
        setsid nohup python3 "$SPACE_SHARE_DIR/relay.py" "$SHARE_GW" "$SPACE_SHARE_PORT" "$SHARE_SUBNET" >"$log" 2>&1 </dev/null &
    else
        nohup python3 "$SPACE_SHARE_DIR/relay.py" "$SHARE_GW" "$SPACE_SHARE_PORT" "$SHARE_SUBNET" >"$log" 2>&1 </dev/null &
    fi
    echo "$!" > "$SPACE_SHARE_DIR/relay-$SHARE_GW.pid"
    tries=0
    while [ "$tries" -lt 30 ]; do
        grep -q '^listening' "$log" 2>/dev/null && return 0
        space_share_running "$SHARE_GW" || break
        sleep 0.1
        tries=$((tries + 1))
    done
    SHARE_ERROR=$(space_last_line "$(cat "$log" 2>/dev/null)")
    [ -n "$SHARE_ERROR" ] || SHARE_ERROR="the network share did not start"
    return 1
}

# Points Space $1's programs at proxy $2: login sessions, apt, sudo (which would drop the variables), and the
# browsers by policy (desktop apps started by cua-driver never see /etc/environment). Firefox also skips its
# first-run and terms pages, which would stand between a partner and the page it opened.
space_share_configure() {
    p=$2
    hp=${p#http://}
    script="set -e
grep -viE '^(http|https|no)_proxy=' /etc/environment > /etc/environment.miffan || true
printf '%s\n' http_proxy=$p https_proxy=$p HTTP_PROXY=$p HTTPS_PROXY=$p no_proxy=localhost,127.0.0.1,::1 NO_PROXY=localhost,127.0.0.1,::1 >> /etc/environment.miffan
cat /etc/environment.miffan > /etc/environment
rm -f /etc/environment.miffan
mkdir -p /etc/apt/apt.conf.d
printf 'Acquire::http::Proxy \"%s/\";\nAcquire::https::Proxy \"%s/\";\n' $p $p > /etc/apt/apt.conf.d/95miffan-proxy
printf 'Defaults env_keep += \"http_proxy https_proxy HTTP_PROXY HTTPS_PROXY no_proxy NO_PROXY\"\n' > /etc/sudoers.d/miffan-proxy
chmod 0440 /etc/sudoers.d/miffan-proxy
mkdir -p /etc/firefox/policies /etc/chromium/policies/managed
printf '%s\n' '{\"policies\":{\"Proxy\":{\"Mode\":\"manual\",\"HTTPProxy\":\"$hp\",\"UseHTTPProxyForAllProtocols\":true,\"Passthrough\":\"localhost, 127.0.0.1\"},\"DisableTelemetry\":true,\"SkipTermsOfUse\":true,\"OverrideFirstRunPage\":\"\",\"OverridePostUpdatePage\":\"\",\"DontCheckDefaultBrowser\":true}}' > /etc/firefox/policies/policies.json
printf '%s\n' '{\"ProxyMode\":\"fixed_servers\",\"ProxyServer\":\"$hp\",\"ProxyBypassList\":\"localhost;127.0.0.1\"}' > /etc/chromium/policies/managed/miffan-proxy.json"
    out=$("$SPACE_CUA" sb exec "$1" "sudo -n sh -c $(sh_quote "$script") && echo SHARECONF ok" 2>/dev/null)
    case "$out" in *"SHARECONF ok"*) return 0 ;; esac
    return 1
}

# A script for the Space that fetches the probe sites in parallel (curl options $1) and stops at the first
# that answers: one slow or blocked site must not hold up the answer. Leaves h=1 on success, else the
# failures in f.
space_race_script() {
    printf '%s' "t=\$(mktemp); p=''; for u in www.baidu.com www.cloudflare.com; do (curl -s -o /dev/null -m 5 --connect-timeout 3 $1 \"https://\$u\" && echo ok >> \"\$t\") >/dev/null 2>&1 & p=\"\$p \$!\"; done; while [ ! -s \"\$t\" ]; do a=0; for i in \$p; do kill -0 \$i 2>/dev/null && a=1; done; [ \$a = 1 ] || break; sleep 0.1; done; h=0; f=''; if [ -s \"\$t\" ]; then h=1; else f=' https:www.baidu.com https:www.cloudflare.com'; fi; kill \$p 2>/dev/null; rm -f \"\$t\";"
}

# Space $1 through the share at $2, in one round: SHARE_CONF (its settings are in place), SHARE_REACH (the
# share answers) and SHARE_HTTPS, failures in SHARE_FAIL.
space_share_check() {
    script="c=0; grep -q '^https_proxy=$2\$' /etc/environment 2>/dev/null && [ -f /etc/apt/apt.conf.d/95miffan-proxy ] && grep -q '${2#http://}' /etc/firefox/policies/policies.json 2>/dev/null && c=1; r=0; h=0; f=''; code=\$(curl -s -o /dev/null -w '%{http_code}' -m 3 --connect-timeout 2 --noproxy '*' $2/ 2>/dev/null); case \"\$code\" in ''|000) f=' share:unreachable' ;; *) r=1 ;; esac; if [ \$r = 1 ]; then $(space_race_script "-x $2") fi; echo \"SHARECHECK conf=\$c reach=\$r https=\$h fail=\$f\""
    line=$("$SPACE_CUA" sb exec "$1" "$script" 2>/dev/null | grep '^SHARECHECK ' | tail -n 1)
    [ -n "$line" ] || line="SHARECHECK conf=0 reach=0 https=0 fail= check did not finish"
    SHARE_CONF=false
    SHARE_REACH=false
    SHARE_HTTPS=false
    case "$line" in *" conf=1 "*) SHARE_CONF=true ;; esac
    case "$line" in *" reach=1 "*) SHARE_REACH=true ;; esac
    case "$line" in *" https=1 "*) SHARE_HTTPS=true ;; esac
    SHARE_FAIL=$(printf '%s' "$line" | sed -n 's/.* fail=//p' | sed 's/^ *//')
}

# Prints netcheck's JSON for Space $1 going out through the share: starts it, checks it, and (re)writes
# the Space's proxy settings when they are missing (a new or reset Space).
space_share_netcheck() {
    if ! space_share_start; then
        printf '{"dns":false,"https":false,"detail":%s,"hint":"share_failed","via":"share"}\n' "$(json_str "$SHARE_ERROR")"
        return
    fi
    proxy=$(space_share_proxy)
    space_share_check "$1" "$proxy"
    if [ "$SHARE_CONF" = false ] && ! space_share_configure "$1" "$proxy"; then
        printf '{"dns":false,"https":false,"detail":"could not set the proxy in the Space","hint":"share_failed","via":"share"}\n'
        return
    fi
    printf '%s\n' "$proxy" > "$SPACE_SHARE_DIR/spaces/$1"
    hint=""
    if [ "$SHARE_REACH" = false ]; then
        if [ "$(systemctl is-active ufw 2>/dev/null)" = active ]; then hint=ufw; else hint=firewall; fi
    fi
    printf '{"dns":%s,"https":%s,"detail":%s,"hint":%s,"via":"share","proxy":%s,"bridge":%s,"port":%s}\n' \
        "$SHARE_HTTPS" "$SHARE_HTTPS" "$(json_or_null "$SHARE_FAIL")" "$(json_or_null "$hint")" "$(json_str "$proxy")" \
        "$(json_str "$SHARE_IF")" "$SPACE_SHARE_PORT"
}

# The proxy Space $1 uses, its share running; empty when it has none.
space_share_ensure() {
    [ "$(os)" = linux ] && [ -f "$SPACE_SHARE_DIR/spaces/$1" ] || return 0
    space_share_net "$1" || return 0
    space_share_start || return 0
    space_share_proxy
}

# Forgets Space $1's share; the last Space gone stops it.
space_share_forget() {
    rm -f "$SPACE_SHARE_DIR/spaces/$1"
    for marker in "$SPACE_SHARE_DIR"/spaces/*; do [ -e "$marker" ] && return 0; done
    space_share_stop_all
}

space_netcheck() {
    space_start_do "$1"
    shared=false
    [ "$(os)" = linux ] && [ -f "$SPACE_SHARE_DIR/spaces/$1" ] && space_share_net "$1" && shared=true
    if [ "$shared" = true ]; then
        space_share_netcheck "$1"
        return
    fi
    # Every probe at once, bounded, and the first success is the answer; the resolver is only asked for
    # the detail of a failure (a site that answered by name needed no more proof).
    script="(timeout 3 getent hosts www.baidu.com >/dev/null 2>&1 && echo dns > /tmp/miffan-dns.\$\$) >/dev/null 2>&1 & $(space_race_script "") d=0; [ \$h = 1 ] && d=1; [ -s /tmp/miffan-dns.\$\$ ] && d=1; [ \$d = 1 ] || f=\" dns:www.baidu.com\$f\"; rm -f /tmp/miffan-dns.\$\$; echo \"NETCHECK dns=\$d https=\$h fail=\$f\""
    out=$("$SPACE_CUA" sb exec "$1" "$script" 2>/dev/null)
    line=$(printf '%s\n' "$out" | grep '^NETCHECK ' | tail -n 1)
    # A check that could not finish counts as offline.
    [ -n "$line" ] || line="NETCHECK dns=0 https=0 fail= check did not finish"
    dns=false
    https=false
    case "$line" in *" dns=1 "*) dns=true ;; esac
    case "$line" in *" https=1 "*|*" https=1") https=true ;; esac
    fails=$(printf '%s' "$line" | sed -n 's/.* fail=//p' | sed 's/^ *//')
    # Offline on its own: go out through the host instead, which works whatever the host runs.
    if [ "$https" = false ] && [ "$(os)" = linux ] && command -v python3 >/dev/null 2>&1 && space_share_net "$1"; then
        space_share_netcheck "$1"
        return
    fi
    hint=""
    if { [ "$dns" = false ] || [ "$https" = false ]; } && [ "$(os)" = linux ] &&
        [ "$(systemctl is-active ufw 2>/dev/null)" = active ]; then
        hint=ufw
    fi
    printf '{"dns":%s,"https":%s,"detail":%s,"hint":%s,"via":"direct"}\n' "$dns" "$https" "$(json_or_null "$fails")" "$(json_or_null "$hint")"
}

# --- Handoff folders ---

# Decodes the JSON string value of key $2 in $1 (one line) to stdout. Status 1 when the key is missing,
# empty or uses an escape other than \" \\ \/ (control characters have no place in folder names).
space_json_string() {
    enc=$(printf '%s' "$1" | sed -n -E 's/.*"'"$2"'" *: *"(([^"\\]|\\.)*)".*/\1/p')
    [ -n "$enc" ] || return 1
    printf '%s' "$enc" | awk 'BEGIN { ORS = ""; bad = 0 }
        { n = length($0); i = 1
          while (i <= n) {
              c = substr($0, i, 1)
              if (c == "\\") {
                  d = substr($0, i + 1, 1); i += 2
                  if (d == "\"" || d == "\\" || d == "/") print d; else bad = 1
              } else { print c; i++ }
          } }
        END { exit bad }'
}

space_bad_input() {
    space_fail bad_input "$1"
}

# Reads {"folder":…,"path":…} from stdin into SPACE_FOLDER and, when $1 is out, SPACE_REL.
space_handoff_input() {
    raw=$(cat | tr -d '\n\r')
    SPACE_FOLDER=$(space_json_string "$raw" folder) || space_bad_input "folder"
    case "$SPACE_FOLDER" in /*) ;; *) space_bad_input "folder must be absolute" ;; esac
    while :; do case "$SPACE_FOLDER" in */) SPACE_FOLDER=${SPACE_FOLDER%/} ;; *) break ;; esac; done
    [ -n "$SPACE_FOLDER" ] || space_bad_input "folder"
    case "$SPACE_FOLDER/" in */../*|*/./*|*//*) space_bad_input "folder must be a clean absolute path" ;; esac
    SPACE_REL=""
    if [ "$1" = out ]; then
        SPACE_REL=$(space_json_string "$raw" path) || space_bad_input "path"
        while :; do case "$SPACE_REL" in */) SPACE_REL=${SPACE_REL%/} ;; *) break ;; esac; done
        case "/$SPACE_REL/" in */../*|*/./*|*//*) space_bad_input "path must stay inside the handoff folder" ;; esac
        [ -n "$SPACE_REL" ] || space_bad_input "path"
    fi
}

sh_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

space_host_exists() { [ -e "$1" ] || [ -L "$1" ]; }

# Exit 0 when $1 exists in the Space; anything but a clear answer fails the command.
space_remote_exists() {
    q=$(sh_quote "$1")
    ans=$("$SPACE_CUA" sb exec "$SPACE_NAME" "if [ -e $q ] || [ -L $q ]; then echo EXISTS; else echo FREE; fi" 2>/dev/null | tail -n 1) ||
        space_fail exec_failed "could not look inside the Space"
    case "$ans" in EXISTS) return 0 ;; FREE) return 1 ;; *) space_fail exec_failed "could not look inside the Space" ;; esac
}

# Sets SPACE_UNIQUE to $2 or, when $1 (an exists-check) finds it taken, the same name with " (2)", " (3)"…
# before the extension. Not run in a subshell: the checks may end the command with an error.
space_unique() {
    check=$1
    p=$2
    SPACE_UNIQUE=$p
    "$check" "$p" || return 0
    dir=${p%/*}
    base=${p##*/}
    case "$base" in
        ?*.tar.*) stem=${base%.tar.*}; ext=.tar.${base##*.tar.} ;;
        ?*.*) stem=${base%.*}; ext=.${base##*.} ;;
        *) stem=$base; ext="" ;;
    esac
    n=2
    while "$check" "$dir/$stem ($n)$ext"; do n=$((n + 1)); done
    SPACE_UNIQUE="$dir/$stem ($n)$ext"
}

space_handoff_out() {
    space_handoff_input out
    space_start_do "$SPACE_NAME"
    mkdir -p "$SPACE_FOLDER" && chmod 700 "$SPACE_FOLDER" 2>/dev/null
    [ -d "$SPACE_FOLDER" ] || space_fail exec_failed "cannot create the host folder"
    "$SPACE_CUA" sb exec "$SPACE_NAME" "mkdir -p $(sh_quote "$SPACE_HANDOFF_DIR")" >/dev/null 2>&1 || space_fail exec_failed "cannot create the handoff folder in the Space"
    q=$(sh_quote "$SPACE_REL")
    list=$("$SPACE_CUA" sb exec "$SPACE_NAME" "cd $(sh_quote "$SPACE_HANDOFF_DIR") || exit 3; if [ -L $q ]; then exit 4; elif [ -d $q ]; then find $q -type f; elif [ -f $q ]; then printf '%s\\n' $q; else exit 4; fi" 2>/dev/null)
    status=$?
    [ "$status" -ne 4 ] || space_fail not_found "$SPACE_REL"
    [ "$status" -eq 0 ] || space_fail exec_failed "could not list the handoff folder"
    copied=""
    sep=""
    while IFS= read -r rel; do
        [ -n "$rel" ] || continue
        space_unique space_host_exists "$SPACE_FOLDER/$rel"
        dest=$SPACE_UNIQUE
        mkdir -p "${dest%/*}" || space_fail exec_failed "cannot create a folder on the host"
        "$SPACE_CUA" sb cp "$SPACE_NAME:$SPACE_HANDOFF_DIR/$rel" "$dest" >/dev/null 2>&1 || space_fail exec_failed "copy failed: $rel"
        copied="$copied$sep$(json_str "${dest#"$SPACE_FOLDER"/}")"
        sep=","
    done <<EOF
$list
EOF
    printf '{"copied":[%s]}\n' "$copied"
}

space_handoff_in() {
    space_handoff_input in
    space_start_do "$SPACE_NAME"
    mkdir -p "$SPACE_FOLDER" && chmod 700 "$SPACE_FOLDER" 2>/dev/null
    [ -d "$SPACE_FOLDER" ] || space_fail exec_failed "cannot create the host folder"
    "$SPACE_CUA" sb exec "$SPACE_NAME" "mkdir -p $(sh_quote "$SPACE_HANDOFF_DIR")" >/dev/null 2>&1 || space_fail exec_failed "cannot create the handoff folder in the Space"
    state_dir="$HOME/.miffan/space"
    mkdir -p "$state_dir"
    state="$state_dir/$SPACE_NAME.handoff"
    marker="$state.new"
    : > "$marker"
    if [ -f "$state" ]; then
        list=$(cd "$SPACE_FOLDER" && find . -type f ! -name .DS_Store -newer "$state" | sort)
    else
        list=$(cd "$SPACE_FOLDER" && find . -type f ! -name .DS_Store | sort)
    fi
    copied=""
    sep=""
    while IFS= read -r rel; do
        rel=${rel#./}
        [ -n "$rel" ] || continue
        space_unique space_remote_exists "$SPACE_HANDOFF_DIR/$rel"
        dest=$SPACE_UNIQUE
        "$SPACE_CUA" sb exec "$SPACE_NAME" "mkdir -p $(sh_quote "${dest%/*}")" >/dev/null 2>&1 || space_fail exec_failed "cannot create a folder in the Space"
        "$SPACE_CUA" sb cp "$SPACE_FOLDER/$rel" "$SPACE_NAME:$dest" >/dev/null 2>&1 || space_fail exec_failed "copy failed: $rel"
        copied="$copied$sep$(json_str "${dest#"$SPACE_HANDOFF_DIR"/}")"
        sep=","
    done <<EOF
$list
EOF
    mv -f "$marker" "$state"
    printf '{"copied":[%s]}\n' "$copied"
}

space_handoff() {
    direction=${1:-}
    SPACE_NAME=${2:-}
    space_need_name "$SPACE_NAME"
    case "$direction" in
        in) space_handoff_in ;;
        out) space_handoff_out ;;
        *) echo "usage: miffan space handoff in|out <name>" >&2; exit 2 ;;
    esac
}

space() {
    umask 077
    action=${1:-}
    [ "$#" -eq 0 ] || shift
    case "$action" in
        probe) space_probe; return ;;
        install) space_install; return ;;
        create|reset|resize)
            # create|reset|resize <name> <cpus> <memory MiB>
            space_need_name "${1:-}"
            space_need_size "${2:-}" "${3:-}"
            space_ready
            "space_$action" "$1"
            ;;
        info|start|stop|delete|netcheck)
            space_need_name "${1:-}"
            space_ready
            "space_$action" "$1"
            ;;
        handoff)
            space_need_name "${2:-}"
            space_ready
            space_handoff "$@"
            ;;
        *) echo "usage: miffan space probe|install|create|info|start|stop|delete|reset|resize|netcheck|handoff" >&2; exit 2 ;;
    esac
}

# --- probe -------------------------------------------------------------------------------

computer_name() {
    name=""
    case "$1" in
        macos)
            name=$(scutil --get ComputerName 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            [ -n "$name" ] || name=$(hostname -s 2>/dev/null)
            ;;
        linux)
            name=$(hostnamectl --pretty 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            [ -n "$name" ] || name=$(hostnamectl --static 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            [ -n "$name" ] || name=$(cat /etc/hostname 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            [ -n "$name" ] || name=$(hostname 2>/dev/null)
            ;;
    esac
    printf '%s' "$name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

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
    printf '{"helper":%s,"os":"%s","arch":%s,"hostname":%s,"session":{"present":%s,"type":"%s","desktop":%s},' \
        "$MIFFAN_HELPER_VERSION" "$platform" "$(json_str "$(uname -m)")" "$(json_or_null "$(computer_name "$platform")")" "$has_session" "$type" "$(json_or_null "$desktop")"
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
        if [ "${2:-}" = check-update ]; then cua_check_update; exit 0; fi
        if [ "${2:-}" = install ]; then cua_install; exit $?; fi
        [ "$(os)" = macos ] || load_session_env || { echo "no graphical session" >&2; exit 1; }
        [ "$(session_type)" = wayland ] && export CUA_DRIVER_RS_ENABLE_WAYLAND=1
        [ "$(os)" = macos ] || utf8_ctype
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
    sunshine) shift; if [ "$#" -eq 0 ]; then sunshine probe; else sunshine "$@"; fi ;;
    clip) clip ;;
    mac-shortcut) mac_shortcut "${2:-}" ;;
    task) shift; task "$@" ;;
    space) shift; space "$@" ;;
    *) echo "usage: miffan version|probe|env|cua|vnc|rdp|sunshine|clip|mac-shortcut|task|space" >&2; exit 2 ;;
esac
