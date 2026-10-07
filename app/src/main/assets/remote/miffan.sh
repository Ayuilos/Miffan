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
#   miffan clip             set the session clipboard from stdin (UTF-8)

MIFFAN_HELPER_VERSION=1
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

SESSION_VARS="WAYLAND_DISPLAY DISPLAY DBUS_SESSION_BUS_ADDRESS XDG_RUNTIME_DIR XDG_CURRENT_DESKTOP XDG_SESSION_TYPE NIRI_SOCKET SWAYSOCK HYPRLAND_INSTANCE_SIGNATURE XAUTHORITY"

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
    [ -n "${XDG_RUNTIME_DIR:-}" ] || export XDG_RUNTIME_DIR="/run/user/$(id -u)"
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
    if [ "$(os)" = macos ]; then
        dir="$HOME/.miffan/run"
    else
        dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/miffan"
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
            if command -v wayvnc >/dev/null 2>&1; then echo wayvnc; return; fi
            ;;
        x11)
            if command -v x11vnc >/dev/null 2>&1; then echo x11vnc; return; fi
            ;;
    esac
    echo none
}

vnc_status_json() {
    kind=$(vnc_server_kind)
    if [ "$kind" = macos-screen-sharing ]; then
        if port_listening 5900; then running=true; else running=false; fi
        printf '{"server":"%s","running":%s,"endpoint":"tcp:5900"}\n' "$kind" "$running"
        return
    fi
    dir=$(runtime_dir)
    socket="$dir/vnc.sock"
    running=false
    if [ -S "$socket" ] && [ -f "$dir/vnc.pid" ] && kill -0 "$(cat "$dir/vnc.pid")" 2>/dev/null; then
        running=true
    fi
    printf '{"server":"%s","running":%s,"endpoint":%s}\n' "$kind" "$running" "$(json_str "unix:$socket")"
}

vnc_start() {
    load_session_env || { echo '{"error":"no_graphical_session"}'; return 1; }
    kind=$(vnc_server_kind)
    if [ "$kind" = macos-screen-sharing ]; then
        vnc_status_json
        return 0
    fi
    dir=$(runtime_dir)
    socket="$dir/vnc.sock"
    if [ -S "$socket" ] && [ -f "$dir/vnc.pid" ] && kill -0 "$(cat "$dir/vnc.pid")" 2>/dev/null; then
        vnc_status_json
        return 0
    fi
    rm -f "$socket"
    case "$kind" in
        wayvnc)
            # Own control socket so a wayvnc the user runs separately is left alone.
            nohup wayvnc -u -S "$dir/wayvncctl" "$socket" >"$dir/vnc.log" 2>&1 </dev/null &
            ;;
        x11vnc)
            nohup x11vnc -display "$DISPLAY" -unixsock "$socket" -rfbport 0 -forever -shared \
                -noxdamage -localhost -quiet >"$dir/vnc.log" 2>&1 </dev/null &
            ;;
        *)
            printf '{"error":"no_vnc_server","session":"%s"}\n' "$(session_type)"
            return 1
            ;;
    esac
    echo $! >"$dir/vnc.pid"
    i=0
    while [ $i -lt 50 ] && [ ! -S "$socket" ]; do
        sleep 0.1
        i=$((i + 1))
    done
    if [ -S "$socket" ]; then
        chmod 600 "$socket" 2>/dev/null
        vnc_status_json
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
    rm -f "$dir/vnc.sock"
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

# --- probe -------------------------------------------------------------------------------

probe() {
    platform=$(os)
    has_session=false
    if [ "$platform" = macos ]; then
        has_session=true
    elif load_session_env; then
        has_session=true
    fi
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
    printf '"vnc":%s,"clipboard":%s}\n' "$(vnc_status_json)" "$(json_or_null "${clipboard##*/}")"
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
    clip) clip ;;
    *) echo "usage: miffan version|probe|env|cua|vnc|clip" >&2; exit 2 ;;
esac
