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
#   miffan relay status|install|upgrade|pair|unpair|uninstall
#                           the relay connector that lets the phone reach this computer from outside
#   miffan window probe|list|focus|resize|shots|serve|locate
#                           one window of a Linux desktop on the phone (niri), video and input

MIFFAN_HELPER_VERSION=43
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
        if tr '\0' '\n' 2>/dev/null < "$environ" | grep -qE '^(WAYLAND_DISPLAY|DISPLAY)='; then
            # Only a desktop someone is logged in to: not a headless one a service runs (a Space's
            # Xvfb) nor what is left of a session being closed, as at the login screen.
            in_login_session "$pid" || continue
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

# Whether process $1 belongs to a logind session that is active or online. Without logind this
# cannot be told, so every process counts.
in_login_session() {
    command -v loginctl >/dev/null 2>&1 || return 0
    [ -r "/proc/$1/sessionid" ] || return 0
    sid=$(cat "/proc/$1/sessionid" 2>/dev/null)
    case "$sid" in ''|4294967295|*[!0-9]*) return 1 ;; esac
    case "$(loginctl show-session "$sid" -p State --value 2>/dev/null)" in
        active|online) return 0 ;;
    esac
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

# The screen page over a relay shows still pictures instead of a stream (snapshot mode). `snapshot serve`
# stays open for the whole screen: it first answers `ready PATH`, where cua-driver writes each capture,
# then reads one request line per picture, `QUALITY [SINCE]`, and answers with one header line, raw
# picture bytes after it, so nothing is base64 and no command starts per picture:
#   same DIGEST                the capture's checksum equals SINCE; no picture follows
#   jpeg|png DIGEST LENGTH     then LENGTH bytes of the picture
#   error CODE                 no picture follows
# A capture is re-encoded as JPEG when the computer has an encoder (sips, ImageMagick, ffmpeg or
# Pillow), else sent as the PNG. EOF on stdin ends it.
SNAPSHOT_DIR="$HOME/.miffan/snapshot"

snapshot() {
    case "${1:-}" in
        serve) snapshot_serve ;;
        *) echo "usage: miffan snapshot serve" >&2; return 2 ;;
    esac
}

snapshot_serve() {
    mkdir -p "$SNAPSHOT_DIR" && chmod 700 "$SNAPSHOT_DIR" || { echo 'error io_error'; return 1; }
    printf 'ready %s\n' "$SNAPSHOT_DIR/desktop.png"
    while IFS=' ' read -r quality since _; do
        snapshot_picture "$quality" "$since"
    done
}

snapshot_picture() {
    quality=$1
    case "$quality" in ''|*[!0-9]*) quality=55 ;; esac
    [ "$quality" -ge 20 ] || quality=20
    [ "$quality" -le 90 ] || quality=90
    png="$SNAPSHOT_DIR/desktop.png"
    jpg="$SNAPSHOT_DIR/desktop.jpg"
    [ -s "$png" ] || { echo 'error no_snapshot'; return 0; }
    digest=$(cksum < "$png" | awk '{print $1 "-" $2}')
    if [ -n "$2" ] && [ "$2" = "$digest" ]; then
        printf 'same %s\n' "$digest"
        return 0
    fi
    rm -f "$jpg"
    case "$(os)" in
        macos) sips -s format jpeg -s formatOptions "$quality" "$png" --out "$jpg" >/dev/null 2>&1 ;;
        *)
            if command -v magick >/dev/null 2>&1; then
                magick "$png" -quality "$quality" "$jpg" >/dev/null 2>&1
            elif command -v convert >/dev/null 2>&1; then
                convert "$png" -quality "$quality" "$jpg" >/dev/null 2>&1
            elif command -v ffmpeg >/dev/null 2>&1; then
                # ffmpeg's -q:v runs 2 (best) to 31; 55 lands near 18.
                ffmpeg -loglevel error -y -i "$png" -q:v "$(( (100 - quality) * 29 / 80 + 2 ))" "$jpg" >/dev/null 2>&1
            elif command -v python3 >/dev/null 2>&1; then
                python3 -c 'import sys
from PIL import Image
Image.open(sys.argv[1]).convert("RGB").save(sys.argv[2], "JPEG", quality=int(sys.argv[3]))' "$png" "$jpg" "$quality" >/dev/null 2>&1
            fi
            ;;
    esac </dev/null
    if [ -s "$jpg" ]; then file=$jpg; format=jpeg; else file=$png; format=png; fi
    # The app captures only between requests, so the file stays the same while it is sent.
    size=$(wc -c < "$file" | tr -d ' ')
    case "$size" in ''|*[!0-9]*) echo 'error io_error'; rm -f "$jpg"; return 0 ;; esac
    printf '%s %s %s\n' "$format" "$digest" "$size"
    cat "$file"
    rm -f "$jpg"
}

# One window of a Linux desktop on the screen page (see PROTOCOL.md 「单个窗口」): the compositor's
# org.gnome.Mutter.ScreenCast records it (niri provides it; no picker, no prompt), GStreamer encodes
# H.264, and input goes through uinput. Only niri lists and focuses windows so far. Elsewhere
# `window probe` answers that single windows are not supported. Requests the program reads (shots,
# serve) arrive on the helper's stdin, handed to Python as fd 3 since its own stdin is the program.
window_command() {
    if [ "$(os)" != linux ] || ! load_session_env || ! command -v python3 >/dev/null 2>&1; then
        if [ "${1:-}" = probe ]; then echo '{"supported":false}'; return 0; fi
        echo "single windows need a Linux desktop session and python3" >&2
        return 1
    fi
    # How the program runs itself again (locating a window runs apart from the stream).
    MIFFAN_WINDOW_FD3=1 MIFFAN_WINDOW_COMMAND=$(printf '%s\twindow' "$0") python3 - "$@" 3<&0 <<'WINDOW_PY'
# miffan window: one window of a Linux desktop on the phone (see PROTOCOL.md, "单个窗口").
#   probe                      one JSON line: whether this desktop can show single windows
#   list                       one JSON line: windows and workspaces
#   focus ID                   brings window ID to the front
#   resize ID W H              sets its size in logical pixels; prints the size it got
#   authorize                  KDE: lets this helper use the RemoteDesktop portal without a dialog
#   install-extension          GNOME: installs the window-list extension (active from the next login)
#   shots                      per stdin line "ID MAXDIM QUALITY": "jpeg LEN" + bytes, or "error CODE"
#   serve ID MAXDIM FPS KBPS   H.264 of window ID on stdout, input lines on stdin
# niri: capture goes through the compositor's org.gnome.Mutter.ScreenCast (no picker, no prompt) and
# input through uinput (absolute pointer + keyboard), so the user needs /dev/uinput.
# KDE Plasma: the window is cropped from its output's RemoteDesktop portal stream, which also takes input.
# X11 (any EWMH window manager): ximagesrc records the window, XTest takes input, through libX11 itself.
# GNOME on Wayland: Mutter records the window and takes input; a small shell extension lists windows.
import ctypes
import ctypes.util
import fcntl
import glob
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time

OUT = sys.stdout.buffer
OUT_LOCK = threading.Lock()


def emit(line, payload=None):
    with OUT_LOCK:
        OUT.write(line.encode() + b"\n")
        if payload is not None:
            OUT.write(payload)
        OUT.flush()


def session_env():
    run = os.environ.get("XDG_RUNTIME_DIR") or "/run/user/%d" % os.getuid()
    os.environ["XDG_RUNTIME_DIR"] = run
    os.environ.setdefault("DBUS_SESSION_BUS_ADDRESS", "unix:path=%s/bus" % run)
    if not os.environ.get("NIRI_SOCKET"):
        socks = sorted(glob.glob(run + "/niri.*.sock"), key=os.path.getmtime)
        if socks:
            os.environ["NIRI_SOCKET"] = socks[-1]
    if not os.environ.get("WAYLAND_DISPLAY"):
        names = [p for p in glob.glob(run + "/wayland-*") if not p.endswith(".lock")]
        names.sort(key=os.path.getmtime)
        if names:
            os.environ["WAYLAND_DISPLAY"] = os.path.basename(names[0])


# ---------- apps ----------

APP_NAMES = {}


def locale_names():
    lang = os.environ.get("LC_ALL") or os.environ.get("LC_MESSAGES") or os.environ.get("LANG") or ""
    if not lang:
        try:
            env = subprocess.run(["systemctl", "--user", "show-environment"], capture_output=True, text=True, timeout=3).stdout
            lang = next((l[5:] for l in env.splitlines() if l.startswith("LANG=")), "")
        except (OSError, subprocess.TimeoutExpired):
            lang = ""
    lang = lang.split(".")[0].split("@")[0]
    keys = ["Name[%s]" % lang] if lang else []
    if "_" in lang:
        keys.append("Name[%s]" % lang.split("_")[0])
    return keys + ["Name"]


def desktop_entry(path):
    entry, inside = {}, False
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("["):
                    inside = line == "[Desktop Entry]"
                elif inside and "=" in line:
                    k, v = line.split("=", 1)
                    entry.setdefault(k.strip(), v.strip())
    except OSError:
        pass
    return entry


def app_name(app_id):
    """The app's name from its desktop entry, as the launcher shows it; else the id made readable."""
    if not app_id:
        return ""
    if app_id in APP_NAMES:
        return APP_NAMES[app_id]
    home = os.path.expanduser("~")
    roots = [os.environ.get("XDG_DATA_HOME") or home + "/.local/share"]
    roots += (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":")
    roots += [home + "/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share"]
    dirs = [r + "/applications" for r in roots if os.path.isdir(r + "/applications")]
    found = None
    for d in dirs:
        path = os.path.join(d, app_id + ".desktop")
        if os.path.isfile(path):
            found = desktop_entry(path)
            break
    wanted = app_id.lower()
    entries = [os.path.join(d, n) for d in dirs for n in sorted(os.listdir(d)) if n.endswith(".desktop")]
    if found is None:
        # Another case, or a reverse-DNS name ending in the id (org.gnome.Nautilus for "nautilus").
        path = next((e for e in entries if os.path.basename(e)[:-8].lower() in (wanted,)
                     or os.path.basename(e)[:-8].lower().endswith("." + wanted)), None)
        found = desktop_entry(path) if path else None
    if found is None:
        found = next((entry for entry in map(desktop_entry, entries)
                      if entry.get("StartupWMClass", "").lower() == wanted), None)
    label = None
    if found:
        label = next((found[k] for k in locale_names() if found.get(k)), None)
    if not label:
        label = " ".join(w[:1].upper() + w[1:] for w in app_id.rsplit(".", 1)[-1].replace("-", " ").replace("_", " ").split())
    APP_NAMES[app_id] = label
    return label


# ---------- niri ----------

def niri(request):
    path = os.environ.get("NIRI_SOCKET")
    if not path:
        return None
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(3)
    try:
        s.connect(path)
        s.sendall(json.dumps(request).encode() + b"\n")
        data = b""
        while not data.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
    except OSError:
        return None
    finally:
        s.close()
    try:
        reply = json.loads(data)
    except ValueError:
        return None
    return reply.get("Ok") if isinstance(reply, dict) else None


def niri_list():
    windows = (niri("Windows") or {}).get("Windows")
    spaces = (niri("Workspaces") or {}).get("Workspaces")
    if windows is None or spaces is None:
        return None
    focused_output = next((w.get("output") for w in spaces if w.get("is_focused")), None)
    current = next((w["id"] for w in spaces if w.get("is_active") and w.get("output") == focused_output), None)
    out = []
    for w in windows:
        layout = w.get("layout") or {}
        size = layout.get("window_size") or [0, 0]
        out.append({
            "id": str(w["id"]), "pid": w.get("pid") or 0, "app": app_name(w.get("app_id") or ""), "app_id": w.get("app_id") or "",
            "title": w.get("title") or "", "workspace": w.get("workspace_id"),
            "focused": bool(w.get("is_focused")), "width": size[0], "height": size[1],
        })
    ws = [{"id": w["id"], "index": w.get("idx"), "output": w.get("output"), "name": w.get("name"),
           "active": bool(w.get("is_active"))} for w in spaces]
    outputs = sorted({w.get("output") for w in spaces if w.get("output")})
    return {"desktop": "niri", "windows": out, "workspaces": ws, "current": current, "outputs": outputs}


def niri_place(window_id):
    """
    Where the window is shown: its output (name, logical box), its frame when niri tells it (floating
    windows), and a signature of the layout around it that changes when it may have moved. None when
    its workspace is not shown.
    """
    windows = (niri("Windows") or {}).get("Windows") or []
    w = next((w for w in windows if str(w["id"]) == str(window_id)), None)
    if w is None:
        return None
    spaces = (niri("Workspaces") or {}).get("Workspaces") or []
    space = next((s for s in spaces if s["id"] == w.get("workspace_id")), None)
    if space is None or not space.get("is_active"):
        return None
    outputs = (niri("Outputs") or {}).get("Outputs") or {}
    output = outputs.get(space.get("output")) or {}
    logical = output.get("logical") or {}
    if not logical:
        return None
    box = (logical["x"], logical["y"], logical["width"], logical["height"])
    layout = w.get("layout") or {}
    tile = layout.get("tile_pos_in_workspace_view")
    rect = None
    if tile is not None:
        offset = layout.get("window_offset_in_tile") or [0, 0]
        size = layout.get("window_size") or [0, 0]
        rect = (box[0] + tile[0] + offset[0], box[1] + tile[1] + offset[1], size[0], size[1])
    signature = (space["id"], tuple(sorted(
        (x["id"], bool(x.get("is_floating")), tuple((x.get("layout") or {}).get("pos_in_scrolling_layout") or ()),
         tuple((x.get("layout") or {}).get("tile_size") or ()))
        for x in windows if x.get("workspace_id") == space["id"])))
    return {"output": space.get("output"), "box": box, "rect": rect, "signature": signature}


def niri_bounds():
    outputs = (niri("Outputs") or {}).get("Outputs") or {}
    boxes = [o["logical"] for o in outputs.values() if o.get("logical")]
    if not boxes:
        return None
    x0 = min(b["x"] for b in boxes)
    y0 = min(b["y"] for b in boxes)
    return (x0, y0, max(b["x"] + b["width"] for b in boxes) - x0, max(b["y"] + b["height"] for b in boxes) - y0)


def niri_focused():
    w = (niri("FocusedWindow") or {}).get("FocusedWindow")
    return str(w["id"]) if w else None


def niri_focus(window_id):
    return niri({"Action": {"FocusWindow": {"id": int(window_id)}}}) is not None


def niri_window(window_id):
    windows = (niri("Windows") or {}).get("Windows") or []
    return next((w for w in windows if str(w["id"]) == str(window_id)), None)


def niri_resize(window_id, width, height):
    """Sets the window's size in logical pixels (a tiled column takes the width), then prints what it got."""
    for action in ("SetWindowWidth", "SetWindowHeight"):
        niri({"Action": {action: {"id": int(window_id), "change": {"SetFixed": int(width if action == "SetWindowWidth" else height)}}}})
    last = None
    for _ in range(20):
        time.sleep(0.05)
        w = niri_window(window_id)
        size = tuple((w or {}).get("layout", {}).get("window_size") or [])
        if size and size == last:
            break
        last = size
    if not last:
        return False
    print("%d %d" % last)
    return True


# ---------- capture ----------

def gst():
    import gi
    gi.require_version("Gst", "1.0")
    gi.require_version("GstVideo", "1.0")
    from gi.repository import Gio, GLib, Gst, GstVideo
    Gst.init(None)
    return Gio, GLib, Gst, GstVideo


ENCODERS = ["nvh264enc", "vah264lpenc", "vah264enc", "vaapih264enc", "x264enc", "openh264enc"]


def pick_encoder(Gst):
    for name in ENCODERS:
        if Gst.ElementFactory.find(name):
            return name
    return None


def encoder_props(name, kbps, fps):
    gop = max(fps * 4, 30)
    if name == "nvh264enc":
        return "preset=low-latency-hq rc-mode=cbr bitrate=%d gop-size=%d zerolatency=true bframes=0" % (kbps, gop)
    if name in ("vah264lpenc", "vah264enc"):
        return "rate-control=cbr bitrate=%d key-int-max=%d b-frames=0" % (kbps, gop)
    if name == "vaapih264enc":
        return "rate-control=cbr bitrate=%d keyframe-period=%d max-bframes=0" % (kbps, gop)
    if name == "x264enc":
        # SPS/PPS on every keyframe, for when no h264parse is installed to add them.
        return ('tune=zerolatency speed-preset=ultrafast bitrate=%d key-int-max=%d bframes=0 byte-stream=true '
                'option-string="repeat-headers=1"' % (kbps, gop))
    return "bitrate=%d gop-size=%d" % (kbps * 1000, gop)


class Cast:
    """
    One org.gnome.Mutter.ScreenCast recording of a window (or an output's monitor): its PipeWire node.
    Waits for the node on a private main context, so any thread may make one.
    """

    def __init__(self, Gio, GLib, window_id=None, monitor=None, on_closed=None):
        self.GLib = GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.session = self._call("/org/gnome/Mutter/ScreenCast", "org.gnome.Mutter.ScreenCast", "CreateSession",
                                  GLib.Variant("(a{sv})", ({},)), "(o)")[0]
        if monitor is not None:
            self.stream = self._call(self.session, "org.gnome.Mutter.ScreenCast.Session", "RecordMonitor",
                                     GLib.Variant("(sa{sv})", (monitor, {"cursor-mode": GLib.Variant("u", 0)})), "(o)")[0]
        else:
            self.stream = self._call(self.session, "org.gnome.Mutter.ScreenCast.Session", "RecordWindow",
                                     GLib.Variant("(a{sv})", ({"window-id": GLib.Variant("t", int(window_id)),
                                                               "cursor-mode": GLib.Variant("u", 1)},)), "(o)")[0]
        self.node = None
        self._subs = []
        context = GLib.MainContext.new()
        context.push_thread_default()
        try:
            def added(_c, _s, _p, _i, _n, params):
                self.node = params[0]
            sub = self.bus.signal_subscribe(None, "org.gnome.Mutter.ScreenCast.Stream", "PipeWireStreamAdded",
                                            self.stream, None, 0, added)
            self._call(self.session, "org.gnome.Mutter.ScreenCast.Session", "Start", None, None)
            deadline = time.monotonic() + 5
            while self.node is None and time.monotonic() < deadline:
                if not context.iteration(False):
                    time.sleep(0.005)
            self.bus.signal_unsubscribe(sub)
        finally:
            context.pop_thread_default()
        if self.node is None:
            self.stop()
            raise RuntimeError("no_stream")
        if on_closed:
            self._subs.append(self.bus.signal_subscribe(None, "org.gnome.Mutter.ScreenCast.Session", "Closed",
                                                        self.session, None, 0, lambda *a: on_closed()))

    def _call(self, path, iface, method, args, rtype):
        return self.bus.call_sync("org.gnome.Mutter.ScreenCast", path, iface, method, args,
                                  self.GLib.VariantType(rtype) if rtype else None, 0, 5000, None)

    def stop(self):
        for sub in self._subs:
            self.bus.signal_unsubscribe(sub)
        self._subs = []
        try:
            self._call(self.session, "org.gnome.Mutter.ScreenCast.Session", "Stop", None, None)
        except Exception:
            pass


def scaled(width, height, limit):
    scale = min(1.0, float(limit) / max(width, height, 1))
    return max(2, int(width * scale) // 2 * 2), max(2, int(height * scale) // 2 * 2)


def gl_env():
    # GStreamer GL without a window: EGL surfaceless works on Mesa and NVIDIA alike.
    os.environ.setdefault("GST_GL_PLATFORM", "egl")
    os.environ.setdefault("GST_GL_WINDOW", "surfaceless")


def shots():
    gl_env()
    Gio, GLib, Gst, _ = gst()
    for line in REQUESTS:
        parts = line.split()
        if not parts:
            continue
        try:
            window_id, limit, quality = parts[0], int(parts[1]), int(parts[2])
        except (IndexError, ValueError):
            emit("error usage")
            continue
        if niri_window(window_id) is None:
            emit("error no_window")
            continue
        try:
            picture = shot(Gio, GLib, Gst, window_id, limit, quality)
        except Exception:
            picture = None
        if picture:
            emit("jpeg %d" % len(picture), picture)
        else:
            emit("error no_picture")


def follow_size(Gst, pipeline, limit, changed=None, fit=None):
    """
    Scales to fit [limit] (or to what [fit] says for the frame size) from the stream's own caps; the
    cast reports its size only once frames flow.
    """
    sizer = pipeline.get_by_name("size")
    current = {"size": None}

    def on_caps(_pad, info):
        event = info.get_event()
        if event.type == Gst.EventType.CAPS:
            s = event.parse_caps().get_structure(0)
            ok_w, cw = s.get_int("width")
            ok_h, ch = s.get_int("height")
            if ok_w and ok_h and cw > 2 and ch > 2:
                size = fit(cw, ch) if fit else scaled(cw, ch, limit)
                if size != current["size"]:
                    first = current["size"] is None
                    current["size"] = size
                    sizer.set_property("caps", Gst.Caps.from_string(
                        "video/x-raw(memory:GLMemory),format=RGBA,width=%d,height=%d" % size))
                    if changed:
                        changed(size, first)
        return Gst.PadProbeReturn.OK
    pipeline.get_by_name("src").get_static_pad("src").add_probe(Gst.PadProbeType.EVENT_DOWNSTREAM, on_caps)


def shot(Gio, GLib, Gst, window_id, limit, quality):
    cast = Cast(Gio, GLib, window_id)
    try:
        pipeline = Gst.parse_launch(
            "pipewiresrc name=src path=%d ! glupload ! glcolorconvert ! glcolorscale ! "
            "capsfilter name=size caps=video/x-raw(memory:GLMemory),format=RGBA ! gldownload ! videoconvert ! "
            "jpegenc quality=%d ! appsink name=sink sync=false max-buffers=1" % (cast.node, quality))
        follow_size(Gst, pipeline, limit)
        sink = pipeline.get_by_name("sink")
        pipeline.set_state(Gst.State.PLAYING)
        sample = sink.emit("try-pull-sample", Gst.SECOND * 5 // 2)
        data = None
        if sample:
            buf = sample.get_buffer()
            data = buf.extract_dup(0, buf.get_size())
        pipeline.set_state(Gst.State.NULL)
        return data
    finally:
        cast.stop()


# ---------- locating a tiled window ----------

LOCATE_WIDTH = 960


def gray_frame(Gst, GstVideo, cast, fit):
    """One small GRAY8 frame of [cast], scaled by [fit]: (bytes, width, height, stride) or None."""
    pipeline = Gst.parse_launch(
        "pipewiresrc name=src path=%d ! glupload ! glcolorconvert ! glcolorscale ! "
        "capsfilter name=size caps=video/x-raw(memory:GLMemory),format=RGBA ! gldownload ! videoconvert ! "
        "video/x-raw,format=GRAY8 ! appsink name=sink sync=false max-buffers=1" % cast.node)
    follow_size(Gst, pipeline, 0, fit=fit)
    pipeline.set_state(Gst.State.PLAYING)
    try:
        sample = pipeline.get_by_name("sink").emit("try-pull-sample", 3 * Gst.SECOND)
        if not sample:
            return None
        info = GstVideo.VideoInfo.new_from_caps(sample.get_caps())
        buf = sample.get_buffer()
        return buf.extract_dup(0, buf.get_size()), info.width, info.height, info.stride[0]
    finally:
        pipeline.set_state(Gst.State.NULL)


def rows_of(data, w, h, stride):
    return [list(data[y * stride:y * stride + w]) for y in range(h)]

def shrink_rows(rows, f):
    h, w = len(rows) // f, len(rows[0]) // f
    out = []
    for y in range(h):
        acc = [0] * w
        for dy in range(f):
            line = rows[y * f + dy]
            for x in range(w):
                acc[x] += sum(line[x * f:(x + 1) * f])
        out.append([v // (f * f) for v in acc])
    return out

def features(rows, cells, extra):
    """Sample points: the strongest edge in each cell of a grid (weight 4), plus a sparse even grid (weight 1)."""
    h, w = len(rows), len(rows[0])
    cw, ch = max(2, w // cells), max(2, h // cells)
    points = []
    for cy in range(0, h - 1, ch):
        for cx in range(0, w - 1, cw):
            best, at = 0, None
            for y in range(cy, min(cy + ch, h - 1)):
                row, below = rows[y], rows[y + 1]
                for x in range(cx, min(cx + cw, w - 1)):
                    g = abs(row[x + 1] - row[x]) + abs(below[x] - row[x])
                    if g > best:
                        best, at = g, (x, y)
            if at is not None and best >= 12:
                points.append((at[0], at[1], 4))
    sx, sy = max(1, w // extra), max(1, h // extra)
    points += [(x, y, 1) for y in range(sy // 2, h, sy) for x in range(sx // 2, w, sx)]
    # Edges first, so a wrong offset is given up early.
    points.sort(key=lambda p: -p[2])
    return points


def score(screen, window, ox, oy, samples, limit):
    total = 0
    for x, y, weight in samples:
        # Truncated, so a part covered by another window costs no more than any mismatch.
        total += min(abs(screen[oy + y][ox + x] - window[y][x]), 80) * weight
        if total >= limit:
            return limit
    return total


def best_of(screen, window, candidates, samples, keep):
    found = []
    bound = float("inf")
    for ox, oy in candidates:
        t = score(screen, window, ox, oy, samples, bound)
        if t < bound:
            found.append((t, (ox, oy)))
            found.sort()
            del found[keep:]
            if len(found) == keep:
                bound = found[-1][0]
    return found


def match(screen, sw, sh, window, ww, wh):
    """Where [window] sits in [screen] (top-left, in their pixels), and how many sample points agree there."""
    coarse_s, coarse_w = shrink_rows(screen, 4), shrink_rows(window, 4)
    cw, ch = len(coarse_w[0]), len(coarse_w)
    if cw < 2 or ch < 2 or len(coarse_s) < ch or len(coarse_s[0]) < cw:
        return None, 0.0
    cands = [(x, y) for y in range(len(coarse_s) - ch + 1) for x in range(len(coarse_s[0]) - cw + 1)]
    top = best_of(coarse_s, coarse_w, cands, features(coarse_w, 12, 6), 5)
    fine = features(window, 24, 10)
    near = set()
    for _, (x0, y0) in top:
        for y in range(y0 * 4 - 6, y0 * 4 + 7):
            for x in range(x0 * 4 - 6, x0 * 4 + 7):
                if 0 <= x <= sw - ww and 0 <= y <= sh - wh:
                    near.add((x, y))
    found = best_of(screen, window, sorted(near), fine, 1)
    if not found:
        return None, 0.0
    at = found[0][1]
    edges = [p for p in fine if p[2] > 1] if sum(1 for p in fine if p[2] > 1) >= 8 else fine
    agree = sum(1 for x, y, _ in edges if abs(screen[at[1] + y][at[0] + x] - window[y][x]) <= 24)
    return at, agree / float(len(edges))


def locate(Gio, GLib, Gst, GstVideo, window_id, place):
    """
    Finds a tiled window on its output, which niri does not report: one small grey frame of each, then
    a coarse search refined around the best match. Returns its frame in global logical pixels, or None.
    """
    box = place["box"]
    scale = {}

    def fit_screen(w, h):
        scale["k"] = float(LOCATE_WIDTH) / w
        return LOCATE_WIDTH, max(2, int(h * scale["k"]))

    screen_cast = Cast(Gio, GLib, monitor=place["output"])
    try:
        screen = gray_frame(Gst, GstVideo, screen_cast, fit_screen)
    finally:
        screen_cast.stop()
    if not screen:
        return None
    k = scale["k"]
    window_cast = Cast(Gio, GLib, window_id)
    try:
        window = gray_frame(Gst, GstVideo, window_cast, lambda w, h: (max(2, int(w * k)), max(2, int(h * k))))
    finally:
        window_cast.stop()
    if not window:
        return None
    sw, sh, ww, wh = screen[1], screen[2], window[1], window[2]
    if ww > sw or wh > sh:
        return None
    at, agree = match(rows_of(*screen), sw, sh, rows_of(*window), ww, wh)
    # Below this, too little of the window shows where it was found (covered, or not found at all).
    if at is None or agree < 0.3:
        return None
    unit = box[2] / float(sw)
    return (box[0] + at[0] * unit, box[1] + at[1] * unit, ww * unit, wh * unit)


def locate_apart(window_id):
    """
    [locate] in a process of its own: GL pipelines in one process share the EGL display, and one torn
    down beside the running stream breaks the stream's.
    """
    self_command = os.environ.get("MIFFAN_WINDOW_COMMAND")
    command = self_command.split("\t") if self_command else [sys.executable, os.path.abspath(__file__)]
    try:
        out = subprocess.run(command + ["locate", str(window_id)], capture_output=True, text=True, timeout=10,
                             stdin=subprocess.DEVNULL).stdout.split()
        return tuple(float(v) for v in out) if len(out) == 4 else None
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return None


# ---------- input ----------

LOGIN_SESSION = []


def screen_locked():
    """Whether the graphical session is locked (logind's LockedHint, which screen lockers set)."""
    def ask(*args):
        try:
            return subprocess.run(["loginctl"] + list(args), capture_output=True, text=True, timeout=3,
                                  stdin=subprocess.DEVNULL).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            return ""
    if not LOGIN_SESSION:
        LOGIN_SESSION.append(ask("show-user", str(os.getuid()), "-p", "Display", "--value"))
    return bool(LOGIN_SESSION[0]) and ask("show-session", LOGIN_SESSION[0], "-p", "LockedHint", "--value") == "yes"


EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112}
REL_HWHEEL, REL_WHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0x06, 0x08, 0x0B, 0x0C
ABS_MAX = 65535
KEYS = {
    "escape": 1, "1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11, "-": 12, "=": 13,
    "backspace": 14, "tab": 15, "q": 16, "w": 17, "e": 18, "r": 19, "t": 20, "y": 21, "u": 22, "i": 23, "o": 24,
    "p": 25, "[": 26, "]": 27, "enter": 28, "control": 29, "a": 30, "s": 31, "d": 32, "f": 33, "g": 34, "h": 35,
    "j": 36, "k": 37, "l": 38, ";": 39, "'": 40, "`": 41, "shift": 42, "\\": 43, "z": 44, "x": 45, "c": 46, "v": 47,
    "b": 48, "n": 49, "m": 50, ",": 51, ".": 52, "/": 53, "alt": 56, "space": 57, "capslock": 58,
    "f1": 59, "f2": 60, "f3": 61, "f4": 62, "f5": 63, "f6": 64, "f7": 65, "f8": 66, "f9": 67, "f10": 68,
    "numlock": 69, "scrolllock": 70, "f11": 87, "f12": 88, "printscreen": 99, "home": 102, "arrowup": 103,
    "pageup": 104, "arrowleft": 105, "arrowright": 106, "end": 107, "arrowdown": 108, "pagedown": 109,
    "insert": 110, "delete": 111, "pause": 119, "meta": 125, "contextmenu": 127,
}
# Characters typed with Shift on a US layout.
SHIFTED = dict(zip('!@#$%^&*()_+{}|:"~<>?', "1234567890-=[]\\;'`,./"))


class Uinput:
    def __init__(self):
        self.pointer = self._device("Miffan pointer", absolute=True)
        self.keyboard = self._device("Miffan keyboard", absolute=False)

    @staticmethod
    def _device(name, absolute):
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        ioctl = fcntl.ioctl
        ioctl(fd, 0x40045564, EV_KEY)  # UI_SET_EVBIT
        if absolute:
            for code in BTN.values():
                ioctl(fd, 0x40045565, code)  # UI_SET_KEYBIT
            ioctl(fd, 0x40045564, EV_ABS)
            ioctl(fd, 0x40045564, EV_REL)
            for code in (REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES):
                ioctl(fd, 0x40045566, code)  # UI_SET_RELBIT
            for code in (0, 1):  # ABS_X, ABS_Y
                ioctl(fd, 0x40045567, code)  # UI_SET_ABSBIT
                ioctl(fd, 0x401C5504, struct.pack("<H2xiiiiii", code, 0, 0, ABS_MAX, 0, 0, 0))  # UI_ABS_SETUP
        else:
            for code in range(1, 249):
                ioctl(fd, 0x40045565, code)
        ioctl(fd, 0x405C5503, struct.pack("<HHHH80sI", 0x06, 0x1209, 0x6d66 if absolute else 0x6d67, 1,
                                          name.encode(), 0))  # UI_DEV_SETUP, BUS_VIRTUAL
        ioctl(fd, 0x5501)  # UI_DEV_CREATE
        return fd

    @staticmethod
    def _write(fd, events):
        data = b"".join(struct.pack("<qqHHi", 0, 0, t, c, v) for t, c, v in events)
        os.write(fd, data + struct.pack("<qqHHi", 0, 0, EV_SYN, 0, 0))

    def move(self, ax, ay):
        self._write(self.pointer, [(EV_ABS, 0, ax), (EV_ABS, 1, ay)])

    def button(self, name, down):
        code = BTN.get(name)
        if code:
            self._write(self.pointer, [(EV_KEY, code, 1 if down else 0)])

    def scroll(self, dx, dy):
        events = []
        if dy:
            events += [(EV_REL, REL_WHEEL_HI_RES, int(-dy * 1.2)), (EV_REL, REL_WHEEL, int(round(-dy / 100.0)))]
        if dx:
            events += [(EV_REL, REL_HWHEEL_HI_RES, int(dx * 1.2)), (EV_REL, REL_HWHEEL, int(round(dx / 100.0)))]
        if events:
            self._write(self.pointer, events)

    def key(self, name, down):
        code = KEYS.get(name)
        shift = False
        if code is None and name in SHIFTED:
            code, shift = KEYS[SHIFTED[name]], True
        if code is None:
            return
        events = [(EV_KEY, 42, 1)] if shift and down else []
        events.append((EV_KEY, code, 1 if down else 0))
        if shift and not down:
            events.append((EV_KEY, 42, 0))
        self._write(self.keyboard, events)

    def close(self):
        for fd in (self.pointer, self.keyboard):
            try:
                fcntl.ioctl(fd, 0x5502)
                os.close(fd)
            except OSError:
                pass


def type_text(text, keys):
    if shutil.which("wtype") and os.environ.get("WAYLAND_DISPLAY"):
        if subprocess.run(["wtype", "--", text], stdin=subprocess.DEVNULL, timeout=10).returncode == 0:
            return
    if shutil.which("wl-copy") and keys:
        subprocess.run(["wl-copy", "--type", "text/plain;charset=utf-8"], input=text.encode(), timeout=5)
        keys.key("control", True)
        keys.key("v", True)
        keys.key("v", False)
        keys.key("control", False)


class LockGuard:
    """Input on a locked screen would land on the lock screen: it is dropped, and the phone told once."""

    def __init__(self):
        self.locked = False
        self.lock_checked = 0.0

    def watch_lock(self):
        """Says when the lock that held input back is gone; called now and then from the main loop."""
        if self.locked and not screen_locked():
            self.locked = False
            emit("resumed")
        return True

    def held_by_lock(self):
        now = time.monotonic()
        if now - self.lock_checked > 1.0:
            self.lock_checked = now
            if screen_locked():
                if not self.locked:
                    self.locked = True
                    emit("suspended locked")
            elif self.locked:
                self.locked = False
                emit("resumed")
        return self.locked


class Input(LockGuard):
    """
    Applies input lines to a niri window. Input lands on what is under the pointer, so the window is
    brought to the front first whenever something else is, and found on screen again when the layout changed.
    """

    def __init__(self, window_id):
        LockGuard.__init__(self)
        self.window_id = window_id
        self.device = None
        self.rect = None
        self.bounds = None
        self.signature = None
        self.checked = 0.0

    def ensure_front(self):
        now = time.monotonic()
        if self.rect is not None and now - self.checked < 1.0:
            return True
        self.checked = now
        if niri_focused() != self.window_id:
            niri_focus(self.window_id)
            self.rect = None
            # niri scrolls the window into view with an animation.
            time.sleep(0.45)
        place = niri_place(self.window_id)
        if place is None:
            self.rect = None
            return False
        if place["rect"] is not None:
            self.rect = place["rect"]
        elif self.rect is None or place["signature"] != self.signature:
            started = time.monotonic()
            self.rect = locate_apart(self.window_id)
            emit("info %s" % json.dumps({"located": self.rect is not None, "ms": int((time.monotonic() - started) * 1000)}))
        self.signature = place["signature"]
        self.bounds = niri_bounds()
        self.checked = time.monotonic()
        return self.rect is not None and self.bounds is not None

    def point(self, nx, ny):
        x, y, w, h = self.rect
        bx, by, bw, bh = self.bounds
        gx = x + max(0.0, min(1.0, nx)) * w
        gy = y + max(0.0, min(1.0, ny)) * h
        return (int((gx - bx) / bw * ABS_MAX), int((gy - by) / bh * ABS_MAX))

    def apply(self, parts):
        if self.held_by_lock():
            return
        if self.device is None:
            try:
                self.device = Uinput()
                # Compositors pick a new input device up asynchronously.
                time.sleep(0.2)
            except OSError:
                emit("info {\"input\":\"unavailable\"}")
                self.device = False
        kind = parts[0]
        if kind == "text":
            import base64
            self.ensure_front()
            type_text(base64.b64decode(parts[1]).decode("utf-8", "replace"), self.device or None)
            return
        if not self.device:
            return
        if kind == "key":
            self.ensure_front()
            self.device.key(parts[1], parts[2] == "1")
            return
        if not self.ensure_front():
            return
        # move X Y | down BUTTON X Y | up BUTTON X Y | scroll X Y DX DY, X and Y 0..1 of the window.
        at = 2 if kind in ("down", "up") else 1
        self.device.move(*self.point(float(parts[at]), float(parts[at + 1])))
        if kind in ("down", "up"):
            self.device.button(parts[1], kind == "down")
        elif kind == "scroll":
            self.device.scroll(float(parts[3]), float(parts[4]))

    def close(self):
        if self.device:
            self.device.close()


# ---------- KDE Plasma (KWin) ----------
# KWin keeps window capture to apps it trusts, so a window is cropped from its output's stream, taken
# through the RemoteDesktop portal; Plasma 6.3+ lets the user pre-authorize that once ("authorize"),
# after which no dialog shows. KWin scripts list, raise and measure windows exactly.

APP_ID = "me.ayuilos.miffan"
UUID = __import__("re").compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def kde_session():
    """Plasma on Wayland; Plasma on X11 is an ordinary X11 desktop here."""
    return ("KDE" in (os.environ.get("XDG_CURRENT_DESKTOP") or "").upper().split(":")
            and bool(os.environ.get("WAYLAND_DISPLAY")) and os.environ.get("XDG_SESSION_TYPE") != "x11")


class KWin:
    """Runs KWin scripts; each sends its result back by D-Bus to a name this process owns."""
    IFACE = "org.miffan.Window"

    def __init__(self, Gio, GLib):
        self.Gio, self.GLib = Gio, GLib
        self.name = "org.miffan.Window.p%d" % os.getpid()
        self.results = {}
        self.ready = threading.Event()
        self.lock = threading.Lock()
        self.count = 0
        threading.Thread(target=self._listen, daemon=True).start()
        if not self.ready.wait(5):
            raise RuntimeError("kwin_listener")
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)

    def _listen(self):
        Gio, GLib = self.Gio, self.GLib
        context = GLib.MainContext.new()
        context.push_thread_default()
        bus = Gio.DBusConnection.new_for_address_sync(
            Gio.dbus_address_get_for_bus_sync(Gio.BusType.SESSION),
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION)
        xml = ('<node><interface name="%s"><method name="Report"><arg type="s" direction="in"/>'
               '<arg type="s" direction="in"/></method></interface></node>' % self.IFACE)

        def call(_conn, _sender, _path, _iface, _method, params, invocation):
            token, data = params[0], params[1]
            event = self.results.get(token)
            if isinstance(event, threading.Event):
                self.results[token] = data
                event.set()
            invocation.return_value(None)
        bus.register_object("/", Gio.DBusNodeInfo.new_for_xml(xml).interfaces[0], call, None, None)
        bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                      GLib.Variant("(su)", (self.name, 4)), None, 0, 5000, None)
        self.ready.set()
        while True:
            context.iteration(True)

    def run(self, body, timeout=3):
        """Runs [body] (JavaScript returning a JSON-able value) in KWin; its value, or None."""
        with self.lock:
            self.count += 1
            token = "t%d" % self.count
        event = threading.Event()
        self.results[token] = event
        script = '(function () { const r = (function () { %s })(); callDBus("%s", "/", "%s", "Report", "%s", JSON.stringify(r === undefined ? null : r)); })();' % (
            body, self.name, self.IFACE, token)
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
            f.write(script)
        plugin = "miffan-%d-%s" % (os.getpid(), token)
        V = self.GLib.Variant
        try:
            sid = self.bus.call_sync("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "loadScript",
                                     V("(ss)", (f.name, plugin)), self.GLib.VariantType("(i)"), 0, 5000, None)[0]
            self.bus.call_sync("org.kde.KWin", "/Scripting/Script%d" % sid, "org.kde.kwin.Script", "run",
                               None, None, 0, 5000, None)
            event.wait(timeout)
        finally:
            try:
                self.bus.call_sync("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "unloadScript",
                                   V("(s)", (plugin,)), None, 0, 5000, None)
            except Exception:
                pass
            os.unlink(f.name)
        value = self.results.pop(token, None)
        return json.loads(value) if isinstance(value, str) else None

    FIND = 'const w = workspace.windowList().find(x => x.internalId.toString() === "{%s}");'

    def snapshot(self):
        return self.run("""
            const current = workspace.currentDesktop;
            const shown = x => !x.minimized && (x.onAllDesktops || x.desktops.includes(current));
            const order = workspace.stackingOrder;
            const meet = (a, b) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
            const windows = workspace.windowList().filter(x => (x.normalWindow || x.dialog) && !x.skipTaskbar).map(x => {
              const g = x.frameGeometry;
              const above = order.slice(order.indexOf(x) + 1).filter(o => o !== x && shown(o) && (o.normalWindow || o.dialog));
              return {id: x.internalId.toString().replace(/[{}]/g, ""), pid: x.pid, app: x.desktopFileName || x.resourceClass,
                      cls: x.resourceClass, title: x.caption, x: g.x, y: g.y, width: g.width, height: g.height,
                      minimized: x.minimized, focused: x === workspace.activeWindow, output: x.output ? x.output.name : null,
                      desktops: x.onAllDesktops ? [] : x.desktops.map(d => workspace.desktops.indexOf(d) + 1),
                      visible: shown(x) && !above.some(o => meet(o.frameGeometry, g))};
            });
            return {windows: windows, current: workspace.desktops.indexOf(current) + 1,
                    desktops: workspace.desktops.map((d, i) => ({id: i + 1, name: d.name})),
                    outputs: workspace.screens.map(s => s.name)};
        """)

    def geometry(self, window_id):
        assert UUID.match(window_id)
        return self.run(self.FIND % window_id + """
            if (!w) return null;
            const g = w.frameGeometry, current = workspace.currentDesktop;
            return {x: g.x, y: g.y, width: g.width, height: g.height, output: w.output ? w.output.name : null,
                    minimized: w.minimized, focused: w === workspace.activeWindow,
                    shown: !w.minimized && (w.onAllDesktops || w.desktops.includes(current))};
        """)

    def focus(self, window_id):
        assert UUID.match(window_id)
        return self.run(self.FIND % window_id + """
            if (!w) return false;
            w.minimized = false;
            if (!w.onAllDesktops && !w.desktops.includes(workspace.currentDesktop)) workspace.currentDesktop = w.desktops[0];
            workspace.activeWindow = w;
            return true;
        """) is True

    def resize(self, window_id, width, height):
        assert UUID.match(window_id)
        return self.run(self.FIND % window_id + """
            if (!w) return null;
            const g = w.frameGeometry;
            w.frameGeometry = {x: g.x, y: g.y, width: %d, height: %d};
            return [w.frameGeometry.width, w.frameGeometry.height];
        """ % (int(width), int(height)))


def kde_list(kwin):
    snap = kwin.snapshot()
    if snap is None:
        return None
    current = snap["current"]
    windows = []
    for w in snap["windows"]:
        desk = w["desktops"][0] if w["desktops"] else current
        windows.append({"id": w["id"], "pid": w["pid"] or 0, "app": app_name(w["app"]) if w["app"] else "",
                        "app_id": w["app"] or w["cls"] or "", "title": w["title"] or "", "workspace": desk,
                        "focused": w["focused"], "width": w["width"], "height": w["height"],
                        "minimized": w["minimized"], "visible": w["visible"]})
    workspaces = [{"id": d["id"], "index": d["id"], "output": None, "name": d["name"], "active": d["id"] == current}
                  for d in snap["desktops"]]
    return {"desktop": "kde", "windows": windows, "workspaces": workspaces, "current": current, "outputs": []}


def kde_authorized(Gio, GLib):
    if not os.path.isfile(desktop_file_path()):
        return False
    try:
        bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        table = bus.call_sync("org.freedesktop.impl.portal.PermissionStore", "/org/freedesktop/impl/portal/PermissionStore",
                              "org.freedesktop.impl.portal.PermissionStore", "Lookup",
                              GLib.Variant("(ss)", ("kde-authorized", "remote-desktop")), None, 0, 5000, None)[0]
        return "yes" in (table or {}).get(APP_ID, [])
    except Exception:
        return False


def desktop_file_path():
    return os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "applications", APP_ID + ".desktop")


def kde_authorize(Gio, GLib):
    """
    Lets this helper use the RemoteDesktop portal without a dialog: a hidden desktop entry names the app
    (the portal registry needs one), and Plasma's kde-authorized table pre-authorizes it.
    """
    path = desktop_file_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write("[Desktop Entry]\nType=Application\nName=Miffan\n"
                "Comment=Lets the Miffan phone app show and use this computer's windows\nExec=true\nNoDisplay=true\n")
    bus = Gio.bus_get_sync(Gio.BusType.SESSION)
    bus.call_sync("org.freedesktop.impl.portal.PermissionStore", "/org/freedesktop/impl/portal/PermissionStore",
                  "org.freedesktop.impl.portal.PermissionStore", "SetPermission",
                  GLib.Variant("(sbssas)", ("kde-authorized", True, "remote-desktop", APP_ID, ["yes"])), None, 0, 5000, None)
    return kde_authorized(Gio, GLib)


class NotAuthorized(Exception):
    pass


def open_portal(Gio, GLib, devices=True):
    """
    A Portal session. The portal service can outlive the desktop session that started it (logging out of
    niri or GNOME and into Plasma without a reboot) and keep serving that desktop's backends, which give
    Plasma no RemoteDesktop: then it is restarted under this session first.
    """
    if not portal_serves_kde():
        subprocess.run(["systemctl", "--user", "restart", "xdg-desktop-portal.service"],
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    return Portal(Gio, GLib, devices)


def portal_serves_kde():
    """Whether the running portal service was started for KDE (true too when that cannot be told)."""
    try:
        pid = subprocess.run(["systemctl", "--user", "show", "-p", "MainPID", "--value", "xdg-desktop-portal.service"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
        if not pid.isdigit() or pid == "0":
            return True  # not running: D-Bus starts it for this session
        with open("/proc/%s/environ" % pid, "rb") as f:
            env = dict(item.split(b"=", 1) for item in f.read().split(b"\0") if b"=" in item)
    except Exception:
        return True
    return b"KDE" in env.get(b"XDG_CURRENT_DESKTOP", b"").split(b":")


class Portal:
    """A RemoteDesktop portal session streaming every output, with pointer and keyboard."""
    DESKTOP = "/org/freedesktop/portal/desktop"

    def __init__(self, Gio, GLib, devices=True):
        self.Gio, self.GLib = Gio, GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.sender = self.bus.get_unique_name()[1:].replace(".", "_")
        self.count = 0
        V = GLib.Variant
        try:
            self._call("org.freedesktop.host.portal.Registry", "Register", V("(sa{sv})", (APP_ID, {})))
        except Exception:
            pass  # older portals know host apps by their systemd unit only
        self.session = self._request("org.freedesktop.portal.RemoteDesktop", "CreateSession",
                                     lambda t: V("(a{sv})", ({"handle_token": V("s", t), "session_handle_token": V("s", t + "s")},)))["session_handle"]
        if devices:
            self._request("org.freedesktop.portal.RemoteDesktop", "SelectDevices",
                          lambda t: V("(oa{sv})", (self.session, {"handle_token": V("s", t), "types": V("u", 3)})))
        self._request("org.freedesktop.portal.ScreenCast", "SelectSources",
                      lambda t: V("(oa{sv})", (self.session, {"handle_token": V("s", t), "types": V("u", 1), "multiple": V("b", True)})))
        try:
            started = self._request("org.freedesktop.portal.RemoteDesktop", "Start",
                                    lambda t: V("(osa{sv})", (self.session, "", {"handle_token": V("s", t)})), timeout=8)
        except TimeoutError:
            # Not pre-authorized: Plasma shows a dialog on the computer instead of answering.
            self.close()
            raise NotAuthorized()
        self.streams = [(node, dict(props)) for node, props in started.get("streams", [])]

    def _call(self, iface, method, args, path=None):
        return self.bus.call_sync("org.freedesktop.portal.Desktop", path or self.DESKTOP, iface, method, args, None, 0, 10000, None)

    def _request(self, iface, method, args, timeout=10):
        self.count += 1
        token = "miffan%d_%d" % (os.getpid(), self.count)
        path = "%s/request/%s/%s" % (self.DESKTOP, self.sender, token)
        box = {}
        context = self.GLib.MainContext.new()
        context.push_thread_default()
        try:
            sub = self.bus.signal_subscribe("org.freedesktop.portal.Desktop", "org.freedesktop.portal.Request", "Response",
                                            path, None, 0, lambda c, s, p, i, n, params: box.update(r=params))
            self._call(iface, method, args(token))
            end = time.monotonic() + timeout
            while "r" not in box and time.monotonic() < end:
                if not context.iteration(False):
                    time.sleep(0.005)
            self.bus.signal_unsubscribe(sub)
        finally:
            context.pop_thread_default()
        if "r" not in box:
            raise TimeoutError(method)
        code, results = box["r"]
        if code != 0:
            raise NotAuthorized() if method == "Start" else RuntimeError(method)
        return results

    def stream_for(self, output):
        """The stream showing [output] (by connector name), else the first: (node, (x, y), (w, h)) in logical pixels."""
        chosen = next((s for s in self.streams if s[1].get("mapping_id") == output), self.streams[0] if self.streams else None)
        if chosen is None:
            return None
        node, props = chosen
        return node, tuple(props.get("position", (0, 0))), tuple(props.get("size", (0, 0)))

    def remote(self):
        reply = self.bus.call_with_unix_fd_list_sync("org.freedesktop.portal.Desktop", self.DESKTOP, "org.freedesktop.portal.ScreenCast",
                                                     "OpenPipeWireRemote", self.GLib.Variant("(oa{sv})", (self.session, {})),
                                                     self.GLib.VariantType("(h)"), 0, 5000, None, None)
        return reply[1].get(reply[0][0])

    def _notify(self, method, signature, *args):
        self._call("org.freedesktop.portal.RemoteDesktop", method, self.GLib.Variant(signature, (self.session, {}) + args))

    def pointer(self, node, x, y):
        self._notify("NotifyPointerMotionAbsolute", "(oa{sv}udd)", node, float(x), float(y))

    def button(self, code, down):
        self._notify("NotifyPointerButton", "(oa{sv}iu)", code, 1 if down else 0)

    def scroll(self, dx, dy):
        # Plasma's portal turns dy around before handing it to KWin (whose positive axis scrolls down),
        # so a positive dy scrolls up; the phone's negative delta is up. Its discrete call passes steps
        # on as pixels, which barely moves, so only the smooth one is used: 15 per notch (100 from the
        # phone), what a real wheel click gives.
        V = self.GLib.Variant
        self._call("org.freedesktop.portal.RemoteDesktop", "NotifyPointerAxis",
                   V("(oa{sv}dd)", (self.session, {"finish": V("b", True)}, float(dx) * 0.15, float(-dy) * 0.15)))

    def key(self, code, down):
        self._notify("NotifyKeyboardKeycode", "(oa{sv}iu)", code, 1 if down else 0)

    def keysym(self, sym, down):
        self._notify("NotifyKeyboardKeysym", "(oa{sv}iu)", sym, 1 if down else 0)

    def close(self):
        try:
            self._call("org.freedesktop.portal.Session", "Close", None, path=self.session)
        except Exception:
            pass


def crop_box(rect, origin, size, frame_w, frame_h):
    """[rect] (global logical) inside a stream of [size] at [origin], as videocrop's left, top, right, bottom pixels."""
    k = frame_w / float(size[0] or frame_w)
    left = int(round((rect[0] - origin[0]) * k))
    top = int(round((rect[1] - origin[1]) * k))
    width = int(round(rect[2] * k))
    height = int(round(rect[3] * k))
    left = max(0, min(left, frame_w - 2))
    top = max(0, min(top, frame_h - 2))
    width = max(2, min(width, frame_w - left))
    height = max(2, min(height, frame_h - top))
    return left, top, frame_w - left - width, frame_h - top - height


def keysym_of(ch):
    code = ord(ch)
    if ch == "\n":
        return 0xff0d
    if ch == "\t":
        return 0xff09
    return code if 0x20 <= code <= 0x7e or 0xa0 <= code <= 0xff else 0x1000000 + code


class KdeInput(LockGuard):
    """Applies input lines through the portal session, raising the window when something else is in front."""

    def __init__(self, window_id, kwin, portal, view):
        LockGuard.__init__(self)
        self.window_id, self.kwin, self.portal, self.view = window_id, kwin, portal, view
        self.checked = 0.0

    def ensure_front(self):
        now = time.monotonic()
        if now - self.checked < 1.0:
            return self.view.get("rect") is not None
        self.checked = now
        g = self.kwin.geometry(self.window_id)
        if g is None:
            return False
        if not g["focused"] or not g["shown"]:
            self.kwin.focus(self.window_id)
            time.sleep(0.15)
            g = self.kwin.geometry(self.window_id) or g
        self.view["rect"] = (g["x"], g["y"], g["width"], g["height"])
        return True

    def apply(self, parts):
        if self.held_by_lock():
            return
        kind = parts[0]
        if kind == "text":
            import base64
            self.ensure_front()
            for ch in base64.b64decode(parts[1]).decode("utf-8", "replace"):
                sym = keysym_of(ch)
                self.portal.keysym(sym, True)
                self.portal.keysym(sym, False)
            return
        if kind == "key":
            self.ensure_front()
            name = parts[1]
            code = KEYS.get(name)
            if code is None and name in SHIFTED:
                self.portal.key(42, True) if parts[2] == "1" else None
                self.portal.key(KEYS[SHIFTED[name]], parts[2] == "1")
                self.portal.key(42, False) if parts[2] != "1" else None
            elif code is not None:
                self.portal.key(code, parts[2] == "1")
            return
        if not self.ensure_front():
            return
        at = 2 if kind in ("down", "up") else 1
        x, y, w, h = self.view["rect"]
        ox, oy = self.view["origin"]
        nx, ny = max(0.0, min(1.0, float(parts[at]))), max(0.0, min(1.0, float(parts[at + 1])))
        self.portal.pointer(self.view["node"], x - ox + nx * w, y - oy + ny * h)
        if kind in ("down", "up") and parts[1] in BTN:
            self.portal.button(BTN[parts[1]], kind == "down")
        elif kind == "scroll":
            self.portal.scroll(float(parts[3]), float(parts[4]))

    def close(self):
        pass


def kde_shots(Gio, GLib, Gst):
    """Pictures of windows fully shown on the current desktop, cropped from their output; others give none."""
    kwin = KWin(Gio, GLib)
    portal = None
    try:
        for line in REQUESTS:
            parts = line.split()
            try:
                window_id, limit, quality = parts[0], int(parts[1]), int(parts[2])
                assert UUID.match(window_id)
            except (IndexError, ValueError, AssertionError):
                emit("error usage")
                continue
            snap = kwin.snapshot() or {"windows": []}
            w = next((x for x in snap["windows"] if x["id"] == window_id), None)
            if w is None:
                emit("error no_window")
                continue
            if not w["visible"]:
                emit("error no_picture")
                continue
            try:
                if portal is None:
                    portal = open_portal(Gio, GLib, devices=False)
                picture = kde_shot(Gst, portal, w, limit, quality)
            except NotAuthorized:
                emit("error not_authorized")
                continue
            except Exception:
                picture = None
            if picture:
                emit("jpeg %d" % len(picture), picture)
            else:
                emit("error no_picture")
    finally:
        if portal:
            portal.close()


def pin_size(Gst, pipeline, width, height):
    """
    Holds the stream at the output's own size. Otherwise the smaller size wanted downstream travels up
    to the compositor, which then sends a scaled-down output (blurring the crop), or with a virtual
    output even resizes the output itself.
    """
    pin = pipeline.get_by_name("pin")
    if pin is not None and pin.get_property("caps").get_structure(0).get_value("width") is None:
        # Keep what the pin already says (its format), adding the size.
        pin.set_property("caps", Gst.Caps.from_string("%s,width=%d,height=%d" % (pin.get_property("caps").to_string(), width, height)))


def kde_shot(Gst, portal, w, limit, quality):
    node, origin, size = portal.stream_for(w["output"])
    rect = (w["x"], w["y"], w["width"], w["height"])
    pipeline = Gst.parse_launch(
        "pipewiresrc name=src fd=%d path=%d ! glupload ! glcolorconvert ! gldownload ! capsfilter name=pin caps=video/x-raw,format=RGBA ! "
        "videocrop name=crop ! videoscale ! videoconvert ! capsfilter name=size ! jpegenc quality=%d ! "
        "appsink name=sink sync=false max-buffers=1" % (portal.remote(), node, quality))
    crop, sizer = pipeline.get_by_name("crop"), pipeline.get_by_name("size")

    def on_caps(_pad, info):
        event = info.get_event()
        if event.type == Gst.EventType.CAPS:
            s = event.parse_caps().get_structure(0)
            fw, fh = s.get_int("width")[1], s.get_int("height")[1]
            pin_size(Gst, pipeline, fw, fh)
            left, top, right, bottom = crop_box(rect, origin, size, fw, fh)
            for name, value in (("left", left), ("top", top), ("right", right), ("bottom", bottom)):
                crop.set_property(name, value)
            out = scaled(fw - left - right, fh - top - bottom, limit)
            sizer.set_property("caps", Gst.Caps.from_string("video/x-raw,width=%d,height=%d" % out))
        return Gst.PadProbeReturn.OK
    pipeline.get_by_name("src").get_static_pad("src").add_probe(Gst.PadProbeType.EVENT_DOWNSTREAM, on_caps)
    pipeline.set_state(Gst.State.PLAYING)
    try:
        sample = pipeline.get_by_name("sink").emit("try-pull-sample", 3 * Gst.SECOND)
        if not sample:
            return None
        buf = sample.get_buffer()
        return buf.extract_dup(0, buf.get_size())
    finally:
        pipeline.set_state(Gst.State.NULL)


# ---------- X11 (any EWMH window manager: XFCE, Cinnamon, MATE, GNOME/KDE on Xorg) ----------
# libX11 and libXtst through ctypes, so nothing beyond the X libraries every X desktop has.


X11_KEYSYMS = {
    "escape": "Escape", "enter": "Return", "backspace": "BackSpace", "tab": "Tab", "space": "space",
    "arrowleft": "Left", "arrowright": "Right", "arrowup": "Up", "arrowdown": "Down", "shift": "Shift_L",
    "control": "Control_L", "alt": "Alt_L", "meta": "Super_L", "delete": "Delete", "insert": "Insert",
    "home": "Home", "end": "End", "pageup": "Prior", "pagedown": "Next", "capslock": "Caps_Lock",
    "numlock": "Num_Lock", "scrolllock": "Scroll_Lock", "printscreen": "Print", "pause": "Pause", "contextmenu": "Menu",
}


class XClientMessage(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                ("display", ctypes.c_void_p), ("window", ctypes.c_ulong), ("message_type", ctypes.c_ulong),
                ("format", ctypes.c_int), ("data", ctypes.c_long * 5)]


class XEvent(ctypes.Union):
    _fields_ = [("client", XClientMessage), ("pad", ctypes.c_long * 24)]


class XWindowAttributes(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int), ("height", ctypes.c_int),
                ("border_width", ctypes.c_int), ("depth", ctypes.c_int), ("visual", ctypes.c_void_p),
                ("root", ctypes.c_ulong), ("class_", ctypes.c_int), ("bit_gravity", ctypes.c_int),
                ("win_gravity", ctypes.c_int), ("backing_store", ctypes.c_int), ("backing_planes", ctypes.c_ulong),
                ("backing_pixel", ctypes.c_ulong), ("save_under", ctypes.c_int), ("colormap", ctypes.c_ulong),
                ("map_installed", ctypes.c_int), ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long),
                ("your_event_mask", ctypes.c_long), ("do_not_propagate_mask", ctypes.c_long),
                ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p)]


class X11:
    """One connection to the X display: EWMH window list, raising, resizing and XTest input."""

    def __init__(self):
        self.x = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
        self.xtst = ctypes.CDLL(ctypes.util.find_library("Xtst") or "libXtst.so.6")
        x = self.x
        x.XOpenDisplay.restype = ctypes.c_void_p
        x.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x.XDefaultRootWindow.restype = ctypes.c_ulong
        x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
        x.XInternAtom.restype = ctypes.c_ulong
        x.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
        x.XGetWindowProperty.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_long, ctypes.c_long,
                                         ctypes.c_int, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int),
                                         ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong),
                                         ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
        x.XFree.argtypes = [ctypes.c_void_p]
        x.XGetWindowAttributes.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XWindowAttributes)]
        x.XTranslateCoordinates.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
                                            ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_ulong)]
        x.XSendEvent.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_long, ctypes.POINTER(XEvent)]
        x.XFlush.argtypes = [ctypes.c_void_p]
        x.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        x.XStringToKeysym.restype = ctypes.c_ulong
        x.XStringToKeysym.argtypes = [ctypes.c_char_p]
        x.XKeysymToKeycode.restype = ctypes.c_ubyte
        x.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        x.XKeycodeToKeysym.restype = ctypes.c_ulong
        x.XKeycodeToKeysym.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_int]
        x.XDisplayKeycodes.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int)]
        x.XGetKeyboardMapping.restype = ctypes.POINTER(ctypes.c_ulong)
        x.XGetKeyboardMapping.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        x.XChangeKeyboardMapping.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_ulong), ctypes.c_int]
        x.XCloseDisplay.argtypes = [ctypes.c_void_p]
        t = self.xtst
        t.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
        t.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
        t.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
        # Xlib's default error handler exits the process; a window that goes away or is minimized while
        # ximagesrc (which shares this process's Xlib) reads it would end the stream that way.
        X11.HANDLER = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda _d, _e: 0)
        x.XSetErrorHandler.argtypes = [ctypes.c_void_p]
        x.XSetErrorHandler(ctypes.cast(X11.HANDLER, ctypes.c_void_p))
        self.dpy = x.XOpenDisplay(None)
        if not self.dpy:
            raise RuntimeError("no_display")
        self.root = x.XDefaultRootWindow(self.dpy)
        self.atoms = {}
        self.lock = threading.Lock()

    def atom(self, name):
        if name not in self.atoms:
            self.atoms[name] = self.x.XInternAtom(self.dpy, name.encode(), 0)
        return self.atoms[name]

    def prop(self, window, name, kind="CARDINAL"):
        """A property's values: ints for 32-bit formats, bytes for 8-bit ones; None when absent."""
        actual, fmt = ctypes.c_ulong(), ctypes.c_int()
        count, after = ctypes.c_ulong(), ctypes.c_ulong()
        data = ctypes.POINTER(ctypes.c_ubyte)()
        want = 0 if kind is None else self.atom(kind)  # 0: AnyPropertyType
        if self.x.XGetWindowProperty(self.dpy, window, self.atom(name), 0, 1 << 16, 0, want, ctypes.byref(actual),
                                     ctypes.byref(fmt), ctypes.byref(count), ctypes.byref(after), ctypes.byref(data)) != 0:
            return None
        try:
            if not data or actual.value == 0:
                return None
            if fmt.value == 32:
                # Xlib hands 32-bit items over as C longs.
                return list(ctypes.cast(data, ctypes.POINTER(ctypes.c_ulong))[:count.value])
            return bytes(data[:count.value * (fmt.value // 8)])
        finally:
            if data:
                self.x.XFree(data)

    def text(self, window, name):
        value = self.prop(window, name, "UTF8_STRING") if name.startswith("_NET") else self.prop(window, name, None)
        return value.decode("utf-8", "replace") if isinstance(value, bytes) else ""

    def frame(self, window):
        """The window's frame in root coordinates, decorations included (from _NET_FRAME_EXTENTS)."""
        a = XWindowAttributes()
        if not self.x.XGetWindowAttributes(self.dpy, window, ctypes.byref(a)):
            return None
        rx, ry, child = ctypes.c_int(), ctypes.c_int(), ctypes.c_ulong()
        self.x.XTranslateCoordinates(self.dpy, window, self.root, 0, 0, ctypes.byref(rx), ctypes.byref(ry), ctypes.byref(child))
        left, right, top, bottom = (self.prop(window, "_NET_FRAME_EXTENTS") or [0, 0, 0, 0])[:4]
        return {"x": rx.value, "y": ry.value, "width": a.width, "height": a.height, "viewable": a.map_state == 2,
                "frame": (rx.value - left, ry.value - top, a.width + left + right, a.height + top + bottom)}

    def snapshot(self):
        with self.lock:
            stacking = self.prop(self.root, "_NET_CLIENT_LIST_STACKING", "WINDOW") or self.prop(self.root, "_NET_CLIENT_LIST", "WINDOW") or []
            current = (self.prop(self.root, "_NET_CURRENT_DESKTOP") or [0])[0]
            count = (self.prop(self.root, "_NET_NUMBER_OF_DESKTOPS") or [1])[0]
            names = (self.prop(self.root, "_NET_DESKTOP_NAMES", "UTF8_STRING") or b"").decode("utf-8", "replace").split("\0")
            active = (self.prop(self.root, "_NET_ACTIVE_WINDOW", "WINDOW") or [0])[0]
            normal, dialog = self.atom("_NET_WM_WINDOW_TYPE_NORMAL"), self.atom("_NET_WM_WINDOW_TYPE_DIALOG")
            hidden, skip = self.atom("_NET_WM_STATE_HIDDEN"), self.atom("_NET_WM_STATE_SKIP_TASKBAR")
            windows = []
            for w in stacking:
                types = self.prop(w, "_NET_WM_WINDOW_TYPE", "ATOM") or [normal]
                if normal not in types and dialog not in types:
                    continue
                state = self.prop(w, "_NET_WM_STATE", "ATOM") or []
                if skip in state:
                    continue
                geometry = self.frame(w)
                if geometry is None:
                    continue
                cls = (self.prop(w, "WM_CLASS", "STRING") or b"").split(b"\0")
                desk = (self.prop(w, "_NET_WM_DESKTOP") or [current])[0]
                windows.append({"id": str(w), "pid": (self.prop(w, "_NET_WM_PID") or [0])[0],
                                "cls": cls[1].decode("utf-8", "replace") if len(cls) > 1 else "",
                                "instance": cls[0].decode("utf-8", "replace") if cls else "",
                                "title": self.text(w, "_NET_WM_NAME") or self.text(w, "WM_NAME"),
                                "minimized": hidden in state, "focused": w == active,
                                "desktop": None if desk == 0xFFFFFFFF else desk, **geometry})
            shown = [w for w in windows if not w["minimized"] and w["viewable"] and w["desktop"] in (None, current)]

            def meet(a, b):
                return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]
            for i, w in enumerate(windows):
                above = [o for o in shown[shown.index(w) + 1:]] if w in shown else []
                w["visible"] = w in shown and not any(meet(o["frame"], w["frame"]) for o in above)
            return {"windows": windows, "current": current,
                    "desktops": [{"id": i, "name": names[i] if i < len(names) else ""} for i in range(count)]}

    def window(self, window_id):
        return next((w for w in self.snapshot()["windows"] if w["id"] == str(window_id)), None)

    def _client_message(self, window, name, values):
        e = XEvent()
        e.client.type = 33  # ClientMessage
        e.client.window = window
        e.client.message_type = self.atom(name)
        e.client.format = 32
        for i, v in enumerate(values[:5]):
            e.client.data[i] = v
        # SubstructureRedirectMask | SubstructureNotifyMask, to the window manager on the root.
        self.x.XSendEvent(self.dpy, self.root, 0, (1 << 20) | (1 << 19), ctypes.byref(e))

    def focus(self, window_id):
        w = self.window(window_id)
        if w is None:
            return False
        with self.lock:
            if w["desktop"] is not None:
                self._client_message(self.root, "_NET_CURRENT_DESKTOP", [w["desktop"], 0])
            # Source 2: a pager, which window managers obey without focus-stealing checks.
            self._client_message(int(window_id), "_NET_ACTIVE_WINDOW", [2, 0, 0, 0, 0])
            self.x.XFlush(self.dpy)
        return True

    def resize(self, window_id, width, height):
        with self.lock:
            # Width and height only (flags bits 10-11), gravity from the window, source 2.
            self._client_message(int(window_id), "_NET_MOVERESIZE_WINDOW", [(2 << 12) | (1 << 10) | (1 << 11), 0, 0, int(width), int(height)])
            self.x.XFlush(self.dpy)
        time.sleep(0.3)
        w = self.window(window_id)
        return [w["width"], w["height"]] if w else None

    def pointer(self, x, y):
        with self.lock:
            self.xtst.XTestFakeMotionEvent(self.dpy, -1, int(x), int(y), 0)
            self.x.XFlush(self.dpy)

    def button(self, number, down):
        with self.lock:
            self.xtst.XTestFakeButtonEvent(self.dpy, number, 1 if down else 0, 0)
            self.x.XFlush(self.dpy)

    def keysym(self, sym, down):
        """Presses [sym], with Shift when the key gives it on its second level; a sym no key has borrows a spare keycode."""
        with self.lock:
            code = self.x.XKeysymToKeycode(self.dpy, sym)
            temporary = False
            if code == 0:
                code = self._spare_keycode()
                if code == 0:
                    return
                syms = (ctypes.c_ulong * 1)(sym)
                self.x.XChangeKeyboardMapping(self.dpy, code, 1, syms, 1)
                self.x.XSync(self.dpy, 0)
                temporary = True
            shift = not temporary and self.x.XKeycodeToKeysym(self.dpy, code, 0) != sym and self.x.XKeycodeToKeysym(self.dpy, code, 1) == sym
            shift_code = self.x.XKeysymToKeycode(self.dpy, 0xffe1)
            if shift and down:
                self.xtst.XTestFakeKeyEvent(self.dpy, shift_code, 1, 0)
            self.xtst.XTestFakeKeyEvent(self.dpy, code, 1 if down else 0, 0)
            if shift and not down:
                self.xtst.XTestFakeKeyEvent(self.dpy, shift_code, 0, 0)
            self.x.XSync(self.dpy, 0)
            if temporary and not down:
                syms = (ctypes.c_ulong * 1)(0)
                self.x.XChangeKeyboardMapping(self.dpy, code, 1, syms, 1)
                self.x.XSync(self.dpy, 0)

    def _spare_keycode(self):
        low, high = ctypes.c_int(), ctypes.c_int()
        self.x.XDisplayKeycodes(self.dpy, ctypes.byref(low), ctypes.byref(high))
        per = ctypes.c_int()
        mapping = self.x.XGetKeyboardMapping(self.dpy, low.value, high.value - low.value + 1, ctypes.byref(per))
        try:
            for code in range(high.value, low.value - 1, -1):
                base = (code - low.value) * per.value
                if all(mapping[base + i] == 0 for i in range(per.value)):
                    return code
        finally:
            self.x.XFree(mapping)
        return 0

    def named_keysym(self, name):
        if name in X11_KEYSYMS:
            return self.x.XStringToKeysym(X11_KEYSYMS[name].encode())
        if name.startswith("f") and name[1:].isdigit():
            return self.x.XStringToKeysym(name.upper().encode())
        if len(name) == 1:
            return keysym_of(name)
        return 0


def x11_session():
    return bool(os.environ.get("DISPLAY")) and (os.environ.get("XDG_SESSION_TYPE") == "x11" or not os.environ.get("WAYLAND_DISPLAY"))


def x11_list(x):
    snap = x.snapshot()
    windows = []
    for w in snap["windows"]:
        app_id = w["cls"] or w["instance"]
        windows.append({"id": w["id"], "pid": w["pid"] or 0, "app": app_name(app_id) if app_id else "", "app_id": app_id,
                        "title": w["title"], "workspace": (w["desktop"] if w["desktop"] is not None else snap["current"]) + 1,
                        "focused": w["focused"], "width": w["frame"][2], "height": w["frame"][3],
                        "minimized": w["minimized"], "visible": w["visible"]})
    workspaces = [{"id": d["id"] + 1, "index": d["id"] + 1, "output": None, "name": d["name"] or None,
                   "active": d["id"] == snap["current"]} for d in snap["desktops"]]
    return {"desktop": "x11", "windows": windows, "workspaces": workspaces, "current": snap["current"] + 1, "outputs": []}


class X11Input(LockGuard):
    """XTest input, after bringing the window to the front when something else is."""

    def __init__(self, window_id, x, view):
        LockGuard.__init__(self)
        self.window_id, self.x, self.view = window_id, x, view
        self.checked = 0.0

    def ensure_front(self):
        now = time.monotonic()
        if now - self.checked < 1.0 and self.view.get("rect"):
            return True
        self.checked = now
        w = self.x.window(self.window_id)
        if w is None:
            return False
        if not w["focused"] or w["minimized"]:
            self.x.focus(self.window_id)
            time.sleep(0.15)
            w = self.x.window(self.window_id) or w
        self.view["rect"] = (w["x"], w["y"], w["width"], w["height"])
        return True

    def apply(self, parts):
        if self.held_by_lock():
            return
        kind = parts[0]
        if kind == "text":
            import base64
            self.ensure_front()
            for ch in base64.b64decode(parts[1]).decode("utf-8", "replace"):
                sym = keysym_of(ch)
                self.x.keysym(sym, True)
                self.x.keysym(sym, False)
            return
        if kind == "key":
            self.ensure_front()
            sym = self.x.named_keysym(parts[1])
            if sym:
                self.x.keysym(sym, parts[2] == "1")
            return
        if not self.ensure_front():
            return
        # The picture is the window's own area (ximagesrc), not its frame.
        x, y, w, h = self.view["rect"]
        at = 2 if kind in ("down", "up") else 1
        nx, ny = max(0.0, min(1.0, float(parts[at]))), max(0.0, min(1.0, float(parts[at + 1])))
        self.x.pointer(x + nx * (w - 1), y + ny * (h - 1))
        if kind in ("down", "up"):
            number = {"left": 1, "middle": 2, "right": 3}.get(parts[1])
            if number:
                self.x.button(number, kind == "down")
        elif kind == "scroll":
            dx, dy = float(parts[3]), float(parts[4])
            # X wheels are buttons: 4 up, 5 down, 6 left, 7 right; one click per notch.
            for number, amount in ((4 if dy < 0 else 5, abs(dy)), (6 if dx < 0 else 7, abs(dx))):
                for _ in range(int(round(amount / 100.0)) or (1 if amount else 0)):
                    self.x.button(number, True)
                    self.x.button(number, False)

    def close(self):
        pass


def x11_source(window_id, fps):
    return ("ximagesrc name=src xid=%s use-damage=false show-pointer=true ! video/x-raw,framerate=%d/1 ! "
            "queue max-size-buffers=1 leaky=downstream ! videoconvert ! videoscale" % (window_id, max(1, fps)))


def x11_shots(Gst):
    x = X11()
    for line in REQUESTS:
        parts = line.split()
        try:
            window_id, limit, quality = int(parts[0]), int(parts[1]), int(parts[2])
        except (IndexError, ValueError):
            emit("error usage")
            continue
        w = x.window(window_id)
        if w is None:
            emit("error no_window")
            continue
        if not w["viewable"]:
            emit("error no_picture")
            continue
        out = scaled(w["width"], w["height"], limit)
        pipeline = Gst.parse_launch("%s ! video/x-raw,width=%d,height=%d ! videoconvert ! jpegenc quality=%d ! "
                                    "appsink name=sink sync=false max-buffers=1" % (x11_source(window_id, 5), out[0], out[1], quality))
        pipeline.set_state(Gst.State.PLAYING)
        sample = pipeline.get_by_name("sink").emit("try-pull-sample", 3 * Gst.SECOND)
        pipeline.set_state(Gst.State.NULL)
        if sample:
            buf = sample.get_buffer()
            emit("jpeg %d" % buf.get_size(), buf.extract_dup(0, buf.get_size()))
        else:
            emit("error no_picture")


def serve_x11(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps):
    """An X11 window: raised, then captured on its own by ximagesrc, which follows its size."""
    try:
        x = X11()
    except Exception:
        emit("closed no_stream")
        return
    if not window_id.isdigit() or not x.focus(window_id):
        emit("closed window_closed")
        return
    time.sleep(0.2)
    w = x.window(window_id)
    if w is None:
        emit("closed window_closed")
        return
    view = {"rect": (w["x"], w["y"], w["width"], w["height"]), "minimized": False}
    size = scaled(w["width"], w["height"], limit)

    def setup(pipeline, resized, closed):
        sizer = pipeline.get_by_name("size")
        current = {"size": size}

        def follow():
            g = x.window(window_id)
            if g is None:
                closed("window_closed")
                return False
            if g["minimized"] != view["minimized"]:
                view["minimized"] = g["minimized"]
                # Nothing to read while it is unmapped; ximagesrc would only fail.
                pipeline.set_state(Gst.State.PAUSED if g["minimized"] else Gst.State.PLAYING)
                emit("suspended minimized" if g["minimized"] else "resumed")
            view["rect"] = (g["x"], g["y"], g["width"], g["height"])
            out = scaled(g["width"], g["height"], limit)
            if out != current["size"]:
                current["size"] = out
                sizer.set_property("caps", Gst.Caps.from_string("video/x-raw,format=NV12,width=%d,height=%d" % out))
                resized(out)
            return True
        GLib.timeout_add(500, follow)

    run_stream(Gio, GLib, Gst, GstVideo,
               "%s ! capsfilter name=size caps=video/x-raw,format=NV12,width=%d,height=%d" % (x11_source(window_id, fps), size[0], size[1]),
               encoder, fps, kbps, X11Input(window_id, x, view), setup, size, lambda: None)


GNOME_METADATA_JSON = r'''{
  "uuid": "miffan-windows@ayuilos.me",
  "name": "Miffan windows",
  "description": "Lets the Miffan phone app list, raise and resize this computer's windows to show one at a time.",
  "shell-version": ["45", "46", "47", "48", "49", "50", "51"],
  "url": "https://github.com/Ayuilos/miffan"
}
'''

GNOME_EXTENSION_JS = r'''// Miffan windows: lists, raises and resizes windows over D-Bus for the Miffan helper, which
// GNOME's own window list (org.gnome.Shell.Introspect) keeps to its own components.
import Gio from 'gi://Gio';
import Meta from 'gi://Meta';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const IFACE = `<node><interface name="me.ayuilos.Miffan.Windows">
  <method name="List"><arg type="s" direction="out"/></method>
  <method name="Activate"><arg type="t" direction="in"/><arg type="b" direction="out"/></method>
  <method name="Resize"><arg type="t" direction="in"/><arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="s" direction="out"/></method>
</interface></node>`;

const shown = w => w.get_window_type() === Meta.WindowType.NORMAL || w.get_window_type() === Meta.WindowType.DIALOG;

export default class MiffanWindows extends Extension {
    enable() {
        this._object = Gio.DBusExportedObject.wrapJSObject(IFACE, this);
        this._object.export(Gio.DBus.session, '/me/ayuilos/Miffan/Windows');
        this._name = Gio.bus_own_name_on_connection(Gio.DBus.session, 'me.ayuilos.Miffan.Windows', 0, null, null);
    }

    disable() {
        Gio.bus_unown_name(this._name);
        this._object.unexport();
        this._object = null;
    }

    _find(id) {
        return global.display.list_all_windows().find(w => w.get_id() === id) ?? null;
    }

    List() {
        const manager = global.workspace_manager;
        const current = manager.get_active_workspace();
        const all = global.display.list_all_windows().filter(w => shown(w) && !w.skip_taskbar);
        const stacked = global.display.sort_windows_by_stacking(all);
        const onScreen = w => !w.minimized && (w.is_on_all_workspaces() || w.get_workspace() === current);
        const meet = (a, b) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
        const windows = all.map(w => {
            const r = w.get_frame_rect();
            const b = w.get_buffer_rect();
            const above = stacked.slice(stacked.indexOf(w) + 1).filter(onScreen);
            return {
                id: String(w.get_id()), pid: w.get_pid(), app: w.get_sandboxed_app_id() || w.get_gtk_application_id() || '',
                cls: w.get_wm_class() || '', title: w.get_title() || '', x: r.x, y: r.y, width: r.width, height: r.height,
                buffer: [b.x - r.x, b.y - r.y, b.width, b.height],
                minimized: w.minimized, focused: w.has_focus(),
                desktop: w.is_on_all_workspaces() ? null : w.get_workspace().index() + 1,
                visible: onScreen(w) && !above.some(o => meet(o.get_frame_rect(), r)),
            };
        });
        const desktops = [];
        for (let i = 0; i < manager.get_n_workspaces(); i++)
            desktops.push({id: i + 1, name: null});
        return JSON.stringify({windows, desktops, current: current.index() + 1});
    }

    Activate(id) {
        const w = this._find(id);
        if (!w)
            return false;
        const time = global.get_current_time();
        // GNOME opens on the overview; input meant for the window would go to its search instead.
        if (Main.overview.visible)
            Main.overview.hide();
        if (w.minimized)
            w.unminimize();
        if (!w.is_on_all_workspaces())
            w.get_workspace().activate_with_focus(w, time);
        else
            w.activate(time);
        return true;
    }

    Resize(id, width, height) {
        const w = this._find(id);
        if (!w)
            return '';
        const r = w.get_frame_rect();
        w.move_resize_frame(true, r.x, r.y, width, height);
        const after = w.get_frame_rect();
        return `${after.width} ${after.height}`;
    }
}
'''


# ---------- GNOME (Wayland) ----------
# Mutter's own ScreenCast and RemoteDesktop D-Bus APIs record one window and take input with no prompt,
# but GNOME keeps its window list (org.gnome.Shell.Introspect) to its own components. A small shell
# extension the helper installs ("install-extension") lists, raises and resizes windows instead; GNOME
# loads a new extension only at the next login.

GNOME_EXTENSION = "miffan-windows@ayuilos.me"
GNOME_EXTENSION_FILES = {
    "metadata.json": GNOME_METADATA_JSON,
    "extension.js": GNOME_EXTENSION_JS,
}


def gnome_session():
    return ("GNOME" in (os.environ.get("XDG_CURRENT_DESKTOP") or "").upper().split(":")
            and bool(os.environ.get("WAYLAND_DISPLAY")) and os.environ.get("XDG_SESSION_TYPE") != "x11")


def gnome_extension_dir():
    return os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"),
                        "gnome-shell", "extensions", GNOME_EXTENSION)


def gnome_windows_call(Gio, GLib, method, args=None, rtype="(s)"):
    bus = Gio.bus_get_sync(Gio.BusType.SESSION)
    return bus.call_sync("me.ayuilos.Miffan.Windows", "/me/ayuilos/Miffan/Windows", "me.ayuilos.Miffan.Windows", method,
                         args, GLib.VariantType(rtype), 0, 5000, None)[0]


def gnome_extension_state(Gio, GLib):
    """"active" once GNOME runs it, "installed" when it waits for the next login, else "missing"."""
    try:
        gnome_windows_call(Gio, GLib, "List")
        return "active"
    except Exception:
        pass
    current = {name: open(os.path.join(gnome_extension_dir(), name)).read() for name in GNOME_EXTENSION_FILES
               if os.path.isfile(os.path.join(gnome_extension_dir(), name))}
    return "installed" if current == GNOME_EXTENSION_FILES else "missing"


def gnome_install_extension():
    """Writes the extension and turns it on for the next login (GNOME does not load new ones before)."""
    os.makedirs(gnome_extension_dir(), exist_ok=True)
    for name, text in GNOME_EXTENSION_FILES.items():
        with open(os.path.join(gnome_extension_dir(), name), "w") as f:
            f.write(text)
    try:
        enabled = subprocess.run(["gsettings", "get", "org.gnome.shell", "enabled-extensions"], capture_output=True,
                                 text=True, timeout=5).stdout.strip()
        names = [] if enabled in ("", "@as []") else json.loads(enabled.replace("'", '"'))
        if GNOME_EXTENSION not in names:
            names.append(GNOME_EXTENSION)
            subprocess.run(["gsettings", "set", "org.gnome.shell", "enabled-extensions", json.dumps(names)], timeout=5)
        subprocess.run(["gsettings", "set", "org.gnome.shell", "disable-user-extensions", "false"], timeout=5)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return False
    # Takes effect now when the shell already knows the extension (an update), else at the next login.
    subprocess.run(["gnome-extensions", "enable", GNOME_EXTENSION], capture_output=True, timeout=5)
    return True


def gnome_snapshot(Gio, GLib):
    return json.loads(gnome_windows_call(Gio, GLib, "List"))


def gnome_list(Gio, GLib):
    try:
        snap = gnome_snapshot(Gio, GLib)
    except Exception:
        return {"desktop": "gnome", "error": "needs_extension", "extension": gnome_extension_state(Gio, GLib),
                "windows": [], "workspaces": [], "current": None, "outputs": []}
    current = snap["current"]
    windows = []
    for w in snap["windows"]:
        app_id = w["app"] or w["cls"]
        windows.append({"id": w["id"], "pid": w["pid"] or 0, "app": app_name(app_id) if app_id else "", "app_id": app_id,
                        "title": w["title"], "workspace": w["desktop"] or current, "focused": w["focused"],
                        "width": w["width"], "height": w["height"], "minimized": w["minimized"], "visible": w["visible"]})
    workspaces = [{"id": d["id"], "index": d["id"], "output": None, "name": d["name"], "active": d["id"] == current}
                  for d in snap["desktops"]]
    return {"desktop": "gnome", "windows": windows, "workspaces": workspaces, "current": current, "outputs": []}


def gnome_window(Gio, GLib, window_id):
    return next((w for w in gnome_snapshot(Gio, GLib)["windows"] if w["id"] == str(window_id)), None)


def gnome_focus(Gio, GLib, window_id):
    return bool(gnome_windows_call(Gio, GLib, "Activate", GLib.Variant("(t)", (int(window_id),)), "(b)"))


def gnome_resize(Gio, GLib, window_id, width, height):
    got = gnome_windows_call(Gio, GLib, "Resize", GLib.Variant("(tii)", (int(window_id), int(width), int(height))))
    return [int(v) for v in got.split()] if got else None


def crop_meta(buf):
    """
    A Mutter window stream's buffers are as large as the output, the window at the region its
    GstVideoCropMeta names: (x, y, width, height), or None. PyGObject cannot read that meta's fields,
    so they are read from its C struct (a GstMeta header of flags and an info pointer, then four guints).
    """
    try:
        meta = buf.get_meta("GstVideoCropMetaAPI")
    except Exception:
        return None
    if meta is None:
        return None
    import re
    found = re.search(r"at (0x[0-9a-f]+)\)>$", repr(meta))
    if not found:
        return None
    return tuple((ctypes.c_uint * 4).from_address(int(found.group(1), 16) + 2 * ctypes.sizeof(ctypes.c_void_p)))


class MutterRemote:
    """A Mutter RemoteDesktop session recording one window through its linked ScreenCast session."""

    def __init__(self, Gio, GLib, window_id):
        self.Gio, self.GLib = Gio, GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        V = GLib.Variant
        self.rd = self._call("org.gnome.Mutter.RemoteDesktop", "/org/gnome/Mutter/RemoteDesktop", "org.gnome.Mutter.RemoteDesktop",
                             "CreateSession", None, "(o)")[0]
        sid = self._call("org.gnome.Mutter.RemoteDesktop", self.rd, "org.freedesktop.DBus.Properties", "Get",
                         V("(ss)", ("org.gnome.Mutter.RemoteDesktop.Session", "SessionId")), "(v)")[0]
        sc = self._call("org.gnome.Mutter.ScreenCast", "/org/gnome/Mutter/ScreenCast", "org.gnome.Mutter.ScreenCast", "CreateSession",
                        V("(a{sv})", ({"remote-desktop-session-id": V("s", sid)},)), "(o)")[0]
        self.stream = self._call("org.gnome.Mutter.ScreenCast", sc, "org.gnome.Mutter.ScreenCast.Session", "RecordWindow",
                                 V("(a{sv})", ({"window-id": V("t", int(window_id)), "cursor-mode": V("u", 1)},)), "(o)")[0]
        self.node = None
        self.text = ""
        context = GLib.MainContext.new()
        context.push_thread_default()
        try:
            sub = self.bus.signal_subscribe(None, "org.gnome.Mutter.ScreenCast.Stream", "PipeWireStreamAdded", self.stream,
                                            None, 0, lambda *a: setattr(self, "node", a[5][0]))
            self.session("Start")
            end = time.monotonic() + 5
            while self.node is None and time.monotonic() < end:
                if not context.iteration(False):
                    time.sleep(0.005)
            self.bus.signal_unsubscribe(sub)
        finally:
            context.pop_thread_default()
        if self.node is None:
            self.close()
            raise RuntimeError("no_stream")

    def _call(self, name, path, iface, method, args, rtype=None):
        return self.bus.call_sync(name, path, iface, method, args, self.GLib.VariantType(rtype) if rtype else None, 0, 5000, None)

    def session(self, method, signature=None, *args):
        return self._call("org.gnome.Mutter.RemoteDesktop", self.rd, "org.gnome.Mutter.RemoteDesktop.Session", method,
                          self.GLib.Variant(signature, args) if signature else None)

    def watch_closed(self, on_closed):
        self.bus.signal_subscribe(None, "org.gnome.Mutter.RemoteDesktop.Session", "Closed", self.rd, None, 0, lambda *a: on_closed())

    def enable_clipboard(self):
        """Serves the phone's text when an app pastes it; called on the main loop's thread."""
        V = self.GLib.Variant
        self.session("EnableClipboard", "(a{sv})", {})

        def transfer(_c, _s, _p, _i, _n, params):
            serial = params[1]
            try:
                reply = self.bus.call_with_unix_fd_list_sync(
                    "org.gnome.Mutter.RemoteDesktop", self.rd, "org.gnome.Mutter.RemoteDesktop.Session", "SelectionWrite",
                    V("(u)", (serial,)), self.GLib.VariantType("(h)"), 0, 5000, None, None)
                fd = reply[1].get(reply[0][0])
                with os.fdopen(fd, "wb") as out:
                    out.write(self.text.encode("utf-8"))
                self.session("SelectionWriteDone", "(ub)", serial, True)
            except Exception:
                try:
                    self.session("SelectionWriteDone", "(ub)", serial, False)
                except Exception:
                    pass
        self.bus.signal_subscribe(None, "org.gnome.Mutter.RemoteDesktop.Session", "SelectionTransfer", self.rd, None, 0, transfer)

    def offer_text(self, text):
        V = self.GLib.Variant
        self.text = text
        self.session("SetSelection", "(a{sv})", {"mime-types": V("as", ["text/plain;charset=utf-8", "text/plain", "UTF8_STRING"])})

    def close(self):
        try:
            self.session("Stop")
        except Exception:
            pass


class GnomeInput(LockGuard):
    """Input through the RemoteDesktop session, in the window stream's own coordinates."""

    def __init__(self, window_id, mutter, Gio, GLib, view):
        LockGuard.__init__(self)
        self.window_id, self.mutter, self.Gio, self.GLib, self.view = window_id, mutter, Gio, GLib, view
        self.checked = 0.0
        # Mutter makes its virtual keyboard and pointer on first use, losing the event that made them.
        self.warm = set()

    def warm_up(self, kind):
        if kind in self.warm:
            return
        self.warm.add(kind)
        if kind == "keyboard":
            # A tap of Shift alone does nothing (Mutter refuses releasing a key that is not down).
            self.mutter.session("NotifyKeyboardKeycode", "(ub)", 42, True)
            self.mutter.session("NotifyKeyboardKeycode", "(ub)", 42, False)
        time.sleep(0.1)

    def ensure_front(self):
        now = time.monotonic()
        if now - self.checked < 1.0:
            return True
        self.checked = now
        w = gnome_window(self.Gio, self.GLib, self.window_id)
        if w is None:
            return False
        # Mutter takes pointer positions in the window's own logical pixels (its buffer, shadows and all).
        self.view["size"] = tuple(w["buffer"][2:4]) if w.get("buffer") else (w["width"], w["height"])
        if not w["focused"] or w["minimized"]:
            gnome_focus(self.Gio, self.GLib, self.window_id)
            # GNOME animates the raise and moves keyboard focus after it; input sent sooner is lost.
            for _ in range(10):
                time.sleep(0.05)
                w = gnome_window(self.Gio, self.GLib, self.window_id) or w
                if w["focused"]:
                    break
            time.sleep(0.25)
        return True

    def key(self, code, down):
        self.warm_up("keyboard")
        self.mutter.session("NotifyKeyboardKeycode", "(ub)", code, down)

    def apply(self, parts):
        if self.held_by_lock():
            return
        kind = parts[0]
        if kind == "text":
            import base64
            self.ensure_front()
            text = base64.b64decode(parts[1]).decode("utf-8", "replace")
            if all(0x20 <= ord(c) <= 0x7e or c in "\n\t" for c in text):
                self.warm_up("keyboard")
                for ch in text:
                    sym = keysym_of(ch)
                    self.mutter.session("NotifyKeyboardKeysym", "(ub)", sym, True)
                    self.mutter.session("NotifyKeyboardKeysym", "(ub)", sym, False)
            else:
                # Mutter types only keysyms the keymap has; other text goes through the session's clipboard.
                self.mutter.offer_text(text)
                time.sleep(0.05)
                for code, down in ((29, True), (47, True), (47, False), (29, False)):
                    self.key(code, down)
            return
        if kind == "key":
            self.ensure_front()
            name, down = parts[1], parts[2] == "1"
            code = KEYS.get(name)
            if code is None and name in SHIFTED:
                if down:
                    self.key(42, True)
                self.key(KEYS[SHIFTED[name]], down)
                if not down:
                    self.key(42, False)
            elif code is not None:
                self.key(code, down)
            return
        if not self.ensure_front():
            return
        width, height = self.view["size"]
        at = 2 if kind in ("down", "up") else 1
        nx, ny = max(0.0, min(1.0, float(parts[at]))), max(0.0, min(1.0, float(parts[at + 1])))
        self.mutter.session("NotifyPointerMotionAbsolute", "(sdd)", self.mutter.stream, nx * width, ny * height)
        if "pointer" not in self.warm:
            self.warm_up("pointer")
            self.mutter.session("NotifyPointerMotionAbsolute", "(sdd)", self.mutter.stream, nx * width, ny * height)
        if kind in ("down", "up") and parts[1] in BTN:
            self.mutter.session("NotifyPointerButton", "(ib)", BTN[parts[1]], kind == "down")
        elif kind == "scroll":
            dx, dy = float(parts[3]), float(parts[4])
            # Discrete steps: positive scrolls down and right; the phone's negative delta is up.
            if dy:
                self.mutter.session("NotifyPointerAxisDiscrete", "(ui)", 0, int(round(dy / 100.0)) or (1 if dy > 0 else -1))
            if dx:
                self.mutter.session("NotifyPointerAxisDiscrete", "(ui)", 1, int(round(dx / 100.0)) or (1 if dx > 0 else -1))

    def close(self):
        pass


def gnome_crop_pipeline(source):
    # Shared-memory frames: Mutter may switch DMA-BUF modifiers mid-stream, which GL import then refuses.
    # No size pin here (fixing caps from a probe deadlocks pipewiresrc on stop): if Mutter scales its
    # buffer to what downstream wants, the crop meta scales with it.
    return ("%s ! capsfilter caps=video/x-raw ! queue max-size-buffers=1 leaky=downstream ! "
            "videoconvert ! videocrop name=crop ! videoscale ! videoconvert" % source)


def follow_crop(Gst, pipeline, limit, on_size):
    """Crops each buffer to the window region its crop meta names; [on_size] hears the scaled size when it changes."""
    crop, current = pipeline.get_by_name("crop"), {"region": None, "frame": None}

    def on_caps(_pad, info):
        event = info.get_event()
        if event.type == Gst.EventType.CAPS:
            s = event.parse_caps().get_structure(0)
            current["frame"] = (s.get_int("width")[1], s.get_int("height")[1])
            current["region"] = None
        return Gst.PadProbeReturn.OK

    def on_buffer(_pad, info):
        region = crop_meta(info.get_buffer())
        if region and current["frame"] and region != current["region"] and region[2] > 1 and region[3] > 1:
            current["region"] = region
            fw, fh = current["frame"]
            x, y, w, h = region
            for name, value in (("left", x), ("top", y), ("right", max(0, fw - x - w)), ("bottom", max(0, fh - y - h))):
                crop.set_property(name, value)
            on_size((w, h))
        return Gst.PadProbeReturn.OK
    pad = pipeline.get_by_name("src").get_static_pad("src")
    pad.add_probe(Gst.PadProbeType.EVENT_DOWNSTREAM, on_caps)
    pad.add_probe(Gst.PadProbeType.BUFFER, on_buffer)


def gnome_shots(Gio, GLib, Gst):
    for line in REQUESTS:
        parts = line.split()
        try:
            window_id, limit, quality = int(parts[0]), int(parts[1]), int(parts[2])
        except (IndexError, ValueError):
            emit("error usage")
            continue
        try:
            w = gnome_window(Gio, GLib, window_id)
        except Exception:
            emit("error needs_extension")
            continue
        if w is None:
            emit("error no_window")
            continue
        if w["minimized"]:
            emit("error no_picture")
            continue
        picture = None
        try:
            cast = Cast(Gio, GLib, window_id)
            try:
                pipeline = Gst.parse_launch("%s ! capsfilter name=size ! jpegenc quality=%d ! appsink name=sink sync=false max-buffers=1"
                                            % (gnome_crop_pipeline("pipewiresrc name=src path=%d" % cast.node), quality))
                sizer = pipeline.get_by_name("size")
                follow_crop(Gst, pipeline, limit, lambda size: sizer.set_property(
                    "caps", Gst.Caps.from_string("video/x-raw,width=%d,height=%d" % scaled(size[0], size[1], limit))))
                pipeline.set_state(Gst.State.PLAYING)
                sample = pipeline.get_by_name("sink").emit("try-pull-sample", Gst.SECOND * 5 // 2)
                if sample:
                    buf = sample.get_buffer()
                    picture = buf.extract_dup(0, buf.get_size())
                del sample
                # The pipeline first: pipewiresrc waits forever for a stream Mutter already took away.
                pipeline.set_state(Gst.State.NULL)
            finally:
                cast.stop()
        except Exception:
            picture = None
        if picture:
            emit("jpeg %d" % len(picture), picture)
        else:
            emit("error no_picture")


def serve_gnome(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps):
    """A GNOME window: raised, recorded by Mutter on its own (cropped to it), with input in its coordinates."""
    try:
        if not window_id.isdigit() or not gnome_focus(Gio, GLib, window_id):
            emit("closed window_closed")
            return
        w = gnome_window(Gio, GLib, window_id)
    except Exception:
        emit("closed needs_extension")
        return
    if w is None:
        emit("closed window_closed")
        return
    try:
        mutter = MutterRemote(Gio, GLib, window_id)
    except Exception:
        emit("closed no_stream")
        return
    view = {"size": tuple(w["buffer"][2:4]) if w.get("buffer") else (w["width"], w["height"]), "minimized": False}
    size = scaled(view["size"][0], view["size"][1], limit)

    def setup(pipeline, resized, closed):
        mutter.watch_closed(lambda: closed("window_closed"))
        mutter.enable_clipboard()
        sizer, current = pipeline.get_by_name("size"), {"size": size}

        def on_size(region):
            out = scaled(region[0], region[1], limit)
            if out != current["size"]:
                current["size"] = out
                sizer.set_property("caps", Gst.Caps.from_string("video/x-raw,format=NV12,width=%d,height=%d" % out))
                resized(out)
        follow_crop(Gst, pipeline, limit, on_size)

        def follow():
            try:
                g = gnome_window(Gio, GLib, window_id)
            except Exception:
                g = None
            if g is None:
                closed("window_closed")
                return False
            if g["minimized"] != view["minimized"]:
                view["minimized"] = g["minimized"]
                emit("suspended minimized" if g["minimized"] else "resumed")
            return True
        GLib.timeout_add(1000, follow)

    run_stream(Gio, GLib, Gst, GstVideo,
               "%s ! capsfilter name=size caps=video/x-raw,format=NV12,width=%d,height=%d" % (
                   gnome_crop_pipeline("pipewiresrc name=src path=%d do-timestamp=true keepalive-time=1000" % mutter.node), size[0], size[1]),
               encoder, fps, kbps, GnomeInput(window_id, mutter, Gio, GLib, view), setup, size, mutter.close)


# ---------- serve ----------

def run_stream(Gio, GLib, Gst, GstVideo, description, encoder, fps, kbps, inputs, setup, size, cleanup):
    """
    Encodes what [description] produces (ending in a NV12 capsfilter named "size", after a pipewiresrc named
    "src") and writes it to stdout, while stdin lines feed [inputs]. [setup] gets the pipeline, a function
    to call when the encoded size changes, and one that ends the stream with a reason.
    """
    loop = GLib.MainLoop()
    state = {"seq": 0, "epoch": 0, "got": False, "closed": False}

    def closed(reason):
        if not state["closed"]:
            state["closed"] = True
            emit("closed " + reason)
        GLib.idle_add(loop.quit)

    # h264parse (gst-plugins-bad) may be missing on a plain desktop; x264enc then repeats SPS/PPS itself.
    parse = "h264parse config-interval=-1 ! " if Gst.ElementFactory.find("h264parse") else ""
    pipeline = Gst.parse_launch(
        "%s ! %s name=enc %s ! %svideo/x-h264,stream-format=byte-stream,alignment=au ! "
        "appsink name=sink emit-signals=true sync=false max-buffers=8" % (description, encoder, encoder_props(encoder, kbps, fps), parse))
    enc = pipeline.get_by_name("enc")

    def resized(new_size):
        state["epoch"] += 1
        emit("geometry %d %d" % new_size)
    setup(pipeline, resized, closed)

    # At most [fps]: the compositor sends a frame per redraw (variable rate, which videorate cannot pace).
    interval = 1.0 / max(1, fps)
    last = [0.0]

    def pace(_pad, _info):
        now = time.monotonic()
        if now - last[0] < interval * 0.9:
            return Gst.PadProbeReturn.DROP
        last[0] = now
        return Gst.PadProbeReturn.OK
    pipeline.get_by_name("src").get_static_pad("src").add_probe(Gst.PadProbeType.BUFFER, pace)

    def on_sample(appsink):
        sample = appsink.emit("pull-sample")
        if sample is None:
            return Gst.FlowReturn.OK
        buf = sample.get_buffer()
        data = buf.extract_dup(0, buf.get_size())
        key = 0 if buf.has_flags(Gst.BufferFlags.DELTA_UNIT) else 1
        caps = sample.get_caps().get_structure(0)
        fw = caps.get_int("width")[1]
        fh = caps.get_int("height")[1]
        state["seq"] += 1
        if not state["got"]:
            state["got"] = True
            emit("resumed")
        ts = buf.pts // 1000 if buf.pts != Gst.CLOCK_TIME_NONE else 0
        emit("frame %d %d %d %d %d %d %d" % (state["seq"], state["epoch"], key, fw, fh, ts, len(data)), data)
        return Gst.FlowReturn.OK
    pipeline.get_by_name("sink").connect("new-sample", on_sample)

    def on_message(_bus, message):
        if message.type == Gst.MessageType.ERROR:
            err, _ = message.parse_error()
            sys.stderr.write("gst error: %s\n" % err.message)
            closed("capture_failed")
        elif message.type == Gst.MessageType.EOS:
            closed("ended")
    gbus = pipeline.get_bus()
    gbus.add_signal_watch()
    gbus.connect("message", on_message)

    def keyframe():
        enc.get_static_pad("src").send_event(GstVideo.video_event_new_upstream_force_key_unit(Gst.CLOCK_TIME_NONE, True, 0))
        return False

    queue = []
    queue_lock = threading.Lock()
    wake = threading.Event()

    def input_worker():
        while True:
            wake.wait()
            with queue_lock:
                lines = list(queue)
                queue.clear()
                wake.clear()
            for parts in lines:
                if parts is None:
                    return
                try:
                    inputs.apply(parts)
                except Exception as error:
                    sys.stderr.write("input error: %s\n" % error)

    def reader():
        for raw in REQUESTS:
            parts = raw.split()
            if not parts:
                continue
            if parts[0] == "kf":
                GLib.idle_add(keyframe)
                continue
            with queue_lock:
                queue.append(parts)
            wake.set()
        with queue_lock:
            queue.append(None)
        wake.set()
        GLib.idle_add(loop.quit)

    emit("opened %d %d %s" % (size[0], size[1], encoder))
    pipeline.set_state(Gst.State.PLAYING)
    GLib.timeout_add(2000, inputs.watch_lock)
    threading.Thread(target=input_worker, daemon=True).start()
    threading.Thread(target=reader, daemon=True).start()
    try:
        loop.run()
    finally:
        pipeline.set_state(Gst.State.NULL)
        cleanup()
        inputs.close()


def serve(window_id, limit, fps, kbps):
    gl_env()
    Gio, GLib, Gst, GstVideo = gst()
    encoder = pick_encoder(Gst)
    if not encoder:
        emit("closed no_encoder")
        return
    kind = desktop_kind()
    if kind == "kde":
        return serve_kde(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps)
    if kind == "x11":
        return serve_x11(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps)
    if kind == "gnome":
        return serve_gnome(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps)
    # niri redraws a window that is not on screen about once a second: bring it into view first.
    if niri_focused() != str(window_id):
        niri_focus(window_id)
    ending = []
    try:
        cast = Cast(Gio, GLib, window_id, on_closed=lambda: ending and ending[0]("window_closed"))
    except Exception:
        emit("closed no_stream")
        return
    # A starting size for the pipeline; the first frame's caps set the real one.
    window_size = ((niri_window(window_id) or {}).get("layout") or {}).get("window_size") or [0, 0]
    width, height = (window_size[0], window_size[1]) if window_size[0] >= 64 and window_size[1] >= 64 else (1280, 720)
    w, h = scaled(width, height, limit)

    def setup(pipeline, resized, closed):
        ending.append(closed)
        follow_size(Gst, pipeline, limit, lambda new_size, first: resized(new_size) if not first or new_size != (w, h) else None)
    run_stream(Gio, GLib, Gst, GstVideo,
               "pipewiresrc name=src path=%d do-timestamp=true keepalive-time=1000 ! "
               "queue max-size-buffers=1 leaky=downstream ! glupload ! glcolorconvert ! glcolorscale ! "
               "capsfilter name=size caps=video/x-raw(memory:GLMemory),format=RGBA,width=%d,height=%d ! glcolorconvert ! gldownload ! "
               "video/x-raw,format=NV12" % (cast.node, w, h),
               encoder, fps, kbps, Input(window_id), setup, (w, h), cast.stop)


def serve_kde(Gio, GLib, Gst, GstVideo, encoder, window_id, limit, fps, kbps):
    """A KDE window: raised, then cropped from its output's portal stream, following it as it moves."""
    if not UUID.match(window_id):
        emit("closed no_window")
        return
    kwin = KWin(Gio, GLib)
    if not kwin.focus(window_id):
        emit("closed window_closed")
        return
    time.sleep(0.15)
    g = kwin.geometry(window_id)
    if g is None:
        emit("closed window_closed")
        return
    try:
        portal = open_portal(Gio, GLib)
    except NotAuthorized:
        emit("closed not_authorized")
        return
    except Exception:
        emit("closed no_stream")
        return
    found = portal.stream_for(g["output"])
    if found is None:
        portal.close()
        emit("closed no_stream")
        return
    node, origin, size = found
    view = {"rect": (g["x"], g["y"], g["width"], g["height"]), "node": node, "origin": origin, "frame": None, "minimized": False}
    w, h = scaled(g["width"], g["height"], limit)

    def setup(pipeline, resized, closed):
        crop, sizer = pipeline.get_by_name("crop"), pipeline.get_by_name("size")
        current = {"size": (w, h)}

        def apply_crop():
            if view["frame"] is None:
                return
            fw, fh = view["frame"]
            left, top, right, bottom = crop_box(view["rect"], view["origin"], size, fw, fh)
            for name, value in (("left", left), ("top", top), ("right", right), ("bottom", bottom)):
                if crop.get_property(name) != value:
                    crop.set_property(name, value)
            out = scaled(fw - left - right, fh - top - bottom, limit)
            if out != current["size"]:
                current["size"] = out
                sizer.set_property("caps", Gst.Caps.from_string("video/x-raw,format=NV12,width=%d,height=%d" % out))
                resized(out)

        def on_caps(_pad, info):
            event = info.get_event()
            if event.type == Gst.EventType.CAPS:
                s = event.parse_caps().get_structure(0)
                view["frame"] = (s.get_int("width")[1], s.get_int("height")[1])
                pin_size(Gst, pipeline, *view["frame"])
                apply_crop()
            return Gst.PadProbeReturn.OK
        pipeline.get_by_name("src").get_static_pad("src").add_probe(Gst.PadProbeType.EVENT_DOWNSTREAM, on_caps)

        def follow():
            g = kwin.geometry(window_id)
            if g is None:
                closed("window_closed")
                return False
            if g["minimized"] != view["minimized"]:
                view["minimized"] = g["minimized"]
                emit("suspended minimized" if g["minimized"] else "resumed")
            rect = (g["x"], g["y"], g["width"], g["height"])
            if rect != view["rect"]:
                view["rect"] = rect
                apply_crop()
            return True
        GLib.timeout_add(500, follow)

    run_stream(Gio, GLib, Gst, GstVideo,
               "pipewiresrc name=src fd=%d path=%d do-timestamp=true keepalive-time=1000 ! "
               "queue max-size-buffers=1 leaky=downstream ! glupload ! glcolorconvert ! gldownload ! "
               "capsfilter name=pin caps=video/x-raw,format=RGBA ! videocrop name=crop ! videoscale ! videoconvert ! "
               "capsfilter name=size caps=video/x-raw,format=NV12,width=%d,height=%d" % (portal.remote(), node, w, h),
               encoder, fps, kbps, KdeInput(window_id, kwin, portal, view), setup, (w, h), portal.close)


# ---------- commands ----------

def desktop_kind():
    if niri("Version") is not None:
        return "niri"
    if kde_session():
        return "kde"
    if gnome_session():
        return "gnome"
    if x11_session():
        return "x11"
    return None


def probe():
    info = {"supported": False, "desktop": desktop_kind()}
    try:
        Gio, GLib, Gst, _ = gst()
        info["encoder"] = pick_encoder(Gst)
        info["capture"] = bool(Gst.ElementFactory.find("ximagesrc")) if info["desktop"] == "x11" else bool(
            Gst.ElementFactory.find("pipewiresrc") and Gst.ElementFactory.find("glupload"))
    except Exception:
        Gio = GLib = None
        info["encoder"] = None
        info["capture"] = False
    if info["desktop"] == "gnome":
        info["input"] = True
        info["extension"] = gnome_extension_state(Gio, GLib) if Gio else "missing"
    elif info["desktop"] == "x11":
        info["input"] = True
    elif info["desktop"] == "kde":
        info["input"] = True
        info["authorized"] = bool(Gio) and kde_authorized(Gio, GLib)
    else:
        info["input"] = os.access("/dev/uinput", os.W_OK)
    info["supported"] = bool(info["desktop"] and info["encoder"] and info["capture"])
    print(json.dumps(info))


# Requests come on stdin, or on fd 3 when the helper runs this program from a heredoc (and says so).
REQUESTS = os.fdopen(3, "r", encoding="utf-8", errors="replace") if os.environ.get("MIFFAN_WINDOW_FD3") else sys.stdin


def main(argv):
    session_env()
    command = argv[0] if argv else ""
    kind = desktop_kind() if command in ("list", "focus", "resize", "shots", "authorize", "install-extension") else None
    if command == "probe":
        probe()
    elif command == "list":
        if kind == "kde":
            Gio, GLib, _, _ = gst()
            listed = kde_list(KWin(Gio, GLib))
        elif kind == "x11":
            listed = x11_list(X11())
        elif kind == "gnome":
            Gio, GLib, _, _ = gst()
            listed = gnome_list(Gio, GLib)
        else:
            listed = niri_list()
        print(json.dumps(listed if listed is not None else {"error": "unsupported"}, ensure_ascii=False))
    elif command == "focus" and len(argv) == 2:
        if kind == "kde":
            Gio, GLib, _, _ = gst()
            sys.exit(0 if UUID.match(argv[1]) and KWin(Gio, GLib).focus(argv[1]) else 1)
        if kind == "x11":
            sys.exit(0 if argv[1].isdigit() and X11().focus(argv[1]) else 1)
        if kind == "gnome":
            Gio, GLib, _, _ = gst()
            try:
                sys.exit(0 if argv[1].isdigit() and gnome_focus(Gio, GLib, argv[1]) else 1)
            except SystemExit:
                raise
            except Exception:
                sys.exit(1)
        sys.exit(0 if niri_focus(argv[1]) else 1)
    elif command == "resize" and len(argv) == 4:
        if kind == "kde":
            Gio, GLib, _, _ = gst()
            got = KWin(Gio, GLib).resize(argv[1], float(argv[2]), float(argv[3])) if UUID.match(argv[1]) else None
            if not got:
                sys.exit(1)
            print("%d %d" % tuple(got))
        elif kind in ("x11", "gnome"):
            if kind == "x11":
                got = X11().resize(argv[1], float(argv[2]), float(argv[3])) if argv[1].isdigit() else None
            else:
                Gio, GLib, _, _ = gst()
                try:
                    got = gnome_resize(Gio, GLib, argv[1], float(argv[2]), float(argv[3])) if argv[1].isdigit() else None
                except Exception:
                    got = None
            if not got:
                sys.exit(1)
            print("%d %d" % tuple(got))
        else:
            sys.exit(0 if niri_resize(argv[1], float(argv[2]), float(argv[3])) else 1)
    elif command == "install-extension":
        if kind != "gnome":
            sys.exit(1)
        ok = gnome_install_extension()
        Gio, GLib, _, _ = gst()
        print(json.dumps({"installed": ok, "extension": gnome_extension_state(Gio, GLib)}))
        sys.exit(0 if ok else 1)
    elif command == "authorize":
        if kind != "kde":
            sys.exit(1)
        Gio, GLib, _, _ = gst()
        ok = kde_authorize(Gio, GLib)
        print(json.dumps({"authorized": ok}))
        sys.exit(0 if ok else 1)
    elif command == "locate" and len(argv) == 2:
        gl_env()
        place = niri_place(argv[1])
        rect = locate(*gst(), argv[1], place) if place else None
        if rect is None:
            sys.exit(1)
        print("%.1f %.1f %.1f %.1f" % rect)
    elif command == "shots":
        if kind == "kde":
            gl_env()
            Gio, GLib, Gst, _ = gst()
            kde_shots(Gio, GLib, Gst)
        elif kind == "x11":
            _, _, Gst, _ = gst()
            x11_shots(Gst)
        elif kind == "gnome":
            gl_env()
            Gio, GLib, Gst, _ = gst()
            gnome_shots(Gio, GLib, Gst)
        else:
            shots()
    elif command == "serve" and len(argv) == 5:
        serve(argv[1], int(argv[2]), int(argv[3]), int(argv[4]))
    else:
        sys.stderr.write("usage: miffan window probe|list|focus ID|resize ID W H|authorize|install-extension|shots|serve ID MAXDIM FPS KBPS\n")
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv[1:])
WINDOW_PY
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
    [ -f "$SPACE_SHARE_DIR/spaces/$1" ] || return 0
    if [ "$(os)" = macos ]; then
        cat "$SPACE_SHARE_DIR/spaces/$1"
        return 0
    fi
    [ "$(os)" = linux ] || return 0
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

# --- The Mac's own proxy ---
# On a Mac, Spaces run in cua's Linux VM and reach the Mac's loopback at SPACE_MAC_HOST, so a proxy set in
# macOS (often a local one such as Clash) serves them too. Without it, sites the Mac only reaches through
# that proxy stay out of reach from the Space while others work.

SPACE_MAC_HOST=192.168.5.2

# The Mac's HTTPS (else HTTP) proxy from its system settings as a Space reaches it; empty when there is none.
space_mac_proxy() {
    p=$(scutil --proxy 2>/dev/null | awk -v gw="$SPACE_MAC_HOST" '
        / HTTPSEnable : / {se=$3} / HTTPSProxy : / {sh=$3} / HTTPSPort : / {sp=$3}
        / HTTPEnable : / {he=$3} / HTTPProxy : / {hh=$3} / HTTPPort : / {hp=$3}
        END {
            if (se == 1 && sh != "" && sp != "") { h = sh; port = sp }
            else if (he == 1 && hh != "" && hp != "") { h = hh; port = hp }
            else exit
            if (h == "127.0.0.1" || h == "localhost" || h == "::1") h = gw
            print "http://" h ":" port
        }')
    # It goes into scripts run in the Space: nothing but a host name and a port.
    printf '%s' "$p" | grep -Eq '^http://[A-Za-z0-9.-]+:[0-9]{1,5}$' && printf '%s' "$p"
}

# Takes Space $1 off a proxy it no longer has: the variables, apt's and sudo's settings and the browsers' proxy.
space_share_unconfigure() {
    script="set -e
grep -viE '^(http|https|no)_proxy=' /etc/environment > /etc/environment.miffan || true
cat /etc/environment.miffan > /etc/environment
rm -f /etc/environment.miffan /etc/apt/apt.conf.d/95miffan-proxy /etc/sudoers.d/miffan-proxy /etc/chromium/policies/managed/miffan-proxy.json
mkdir -p /etc/firefox/policies
printf '%s\n' '{\"policies\":{\"DisableTelemetry\":true,\"SkipTermsOfUse\":true,\"OverrideFirstRunPage\":\"\",\"OverridePostUpdatePage\":\"\",\"DontCheckDefaultBrowser\":true}}' > /etc/firefox/policies/policies.json"
    out=$("$SPACE_CUA" sb exec "$1" "sudo -n sh -c $(sh_quote "$script") && echo SHAREOFF ok" 2>/dev/null)
    case "$out" in *"SHAREOFF ok"*) return 0 ;; esac
    return 1
}

# netcheck for Space $1 on a Mac whose settings name proxy $2: the Space goes out through it, like through a
# Linux host's share. Status 1, printing nothing, when the proxy does not answer from the Space.
space_mac_netcheck() {
    space_share_check "$1" "$2"
    [ "$SHARE_REACH" = true ] || return 1
    if [ "$SHARE_CONF" = false ] && ! space_share_configure "$1" "$2"; then
        printf '{"dns":false,"https":false,"detail":"could not set the proxy in the Space","hint":"share_failed","via":"share"}\n'
        return 0
    fi
    mkdir -p "$SPACE_SHARE_DIR/spaces"
    printf '%s\n' "$2" > "$SPACE_SHARE_DIR/spaces/$1"
    printf '{"dns":%s,"https":%s,"detail":%s,"hint":null,"via":"share","proxy":%s}\n' \
        "$SHARE_HTTPS" "$SHARE_HTTPS" "$(json_or_null "$SHARE_FAIL")" "$(json_str "$2")"
}

space_netcheck() {
    space_start_do "$1"
    if [ "$(os)" = macos ]; then
        mp=$(space_mac_proxy)
        [ -n "$mp" ] && space_mac_netcheck "$1" "$mp" && return
        # No proxy any more, or one the Space cannot reach: it goes out on its own again.
        if [ -f "$SPACE_SHARE_DIR/spaces/$1" ] && space_share_unconfigure "$1"; then
            rm -f "$SPACE_SHARE_DIR/spaces/$1"
        fi
    fi
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
    # A relative folder is under the host account's home (the app stores it that way).
    case "$SPACE_FOLDER" in /*) ;; ?*) SPACE_FOLDER="$HOME/$SPACE_FOLDER" ;; esac
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

# "<size>:<mtime>" of a host file, to recognise one the helper wrote that nobody has touched since.
space_file_sig() {
    stat -c '%s:%Y' "$1" 2>/dev/null || stat -f '%z:%m' "$1" 2>/dev/null
}

# Files `out` put on the host; `in` must not bring them back as new (they would return as "x (2)").
space_handoff_given() {
    printf '%s\n' "$HOME/.miffan/space/$SPACE_NAME.handoff-out"
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
    given=$(space_handoff_given)
    mkdir -p "${given%/*}"
    tab=$(printf '\t')
    while IFS= read -r rel; do
        [ -n "$rel" ] || continue
        space_unique space_host_exists "$SPACE_FOLDER/$rel"
        dest=$SPACE_UNIQUE
        mkdir -p "${dest%/*}" || space_fail exec_failed "cannot create a folder on the host"
        "$SPACE_CUA" sb cp "$SPACE_NAME:$SPACE_HANDOFF_DIR/$rel" "$dest" >/dev/null 2>&1 || space_fail exec_failed "copy failed: $rel"
        printf '%s%s%s\n' "${dest#"$SPACE_FOLDER"/}" "$tab" "$(space_file_sig "$dest")" >> "$given"
        copied="$copied$sep$(json_str "${dest#"$SPACE_FOLDER"/}")"
        sep=","
    done <<EOF
$list
EOF
    printf '{"copied":[%s]}\n' "$copied"
}

space_handoff_in() {
    space_handoff_input in
    mkdir -p "$SPACE_FOLDER" && chmod 700 "$SPACE_FOLDER" 2>/dev/null
    [ -d "$SPACE_FOLDER" ] || space_fail exec_failed "cannot create the host folder"
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
    given=$(space_handoff_given)
    tab=$(printf '\t')
    # What the partner handed out, untouched since, is already in the Space.
    if [ -n "$list" ] && [ -f "$given" ]; then
        list=$(printf '%s\n' "$list" | while IFS= read -r rel; do
            grep -Fqx -- "${rel#./}$tab$(space_file_sig "$SPACE_FOLDER/${rel#./}")" "$given" || printf '%s\n' "$rel"
        done)
    fi
    # Runs every turn: with nothing new, the Space is left alone and this answers at once.
    if [ -n "$list" ]; then
        space_start_do "$SPACE_NAME"
        "$SPACE_CUA" sb exec "$SPACE_NAME" "mkdir -p $(sh_quote "$SPACE_HANDOFF_DIR")" >/dev/null 2>&1 || space_fail exec_failed "cannot create the handoff folder in the Space"
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
    # Keep only handed-out files a later run could still list: unchanged, and not older than this run.
    if [ -f "$given" ]; then
        since=$(space_file_sig "$state"); since=${since#*:}
        while IFS="$tab" read -r rel sig; do
            [ "$(space_file_sig "$SPACE_FOLDER/$rel")" = "$sig" ] && [ "${sig#*:}" -ge "${since:-0}" ] &&
                printf '%s%s%s\n' "$rel" "$tab" "$sig"
        done < "$given" > "$given.new"
        mv -f "$given.new" "$given"
    fi
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

# --- Relay connector (helper 33) -------------------------------------------------------------
# Installs, pairs and removes miffan-connector, the agent that lets the phone reach this computer
# through a Miffan relay (relay/PROTOCOL.md). Every `relay` command prints one JSON line on stdout.
# User data arrives on stdin and goes to the connector unchanged (it parses and validates it), never
# through argv or an eval. Nothing here needs root; the service runs as this user.
#
# Release binaries are pinned by version and SHA-256, taken from the release's SHA256SUMS when
# relay-connector-v$MIFFAN_CONNECTOR_VERSION was published.

MIFFAN_CONNECTOR_VERSION=1.2.0
MIFFAN_CONNECTOR_SHA256_DARWIN_ARM64=d80631d7df0416ea6666c130d2486712ee96c994e0e6d1b1d76ac12036fb89d2
MIFFAN_CONNECTOR_SHA256_DARWIN_AMD64=3cb2ed11c8285c408ee58bc31c6d142fbe6c4f673d0bcdd9e70a99a8ca8ea91e
MIFFAN_CONNECTOR_SHA256_LINUX_AMD64=35a226f8d10c466481962ed7670aa0a078b736e60ab34417a4bfef5b5d609440
MIFFAN_CONNECTOR_SHA256_LINUX_ARM64=dc1d30517071be9ef44fa8781592d53063b850ee004d8adc6e8fc344cd4c2755
MIFFAN_CONNECTOR_RELEASE_URL="https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v$MIFFAN_CONNECTOR_VERSION"
# Extra download locations tried after the GitHub release, space separated, same file names. None yet.
MIFFAN_CONNECTOR_MIRRORS=""

RELAY_BIN="$HOME/.miffan/bin/miffan-connector"
RELAY_DIR="$HOME/.miffan/connector"
RELAY_LOG="$RELAY_DIR/connector.log"
RELAY_LABEL=me.ayuilos.miffan.connector
RELAY_PLIST="$HOME/Library/LaunchAgents/$RELAY_LABEL.plist"
RELAY_UNIT=miffan-connector.service
RELAY_UNIT_FILE="$HOME/.config/systemd/user/$RELAY_UNIT"

# {"success":false,"reason":code,"detail":text}, exit 1. Never call it inside $(...).
relay_fail() {
    printf '{"success":false,"reason":%s,"detail":%s}\n' "$(json_str "$1")" "$(json_str "$2")"
    exit 1
}

# darwin-arm64, darwin-amd64, linux-amd64 or linux-arm64; status 1 elsewhere.
relay_platform() {
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64) echo darwin-arm64 ;;
        Darwin-x86_64) echo darwin-amd64 ;;
        Linux-x86_64) echo linux-amd64 ;;
        Linux-aarch64|Linux-arm64) echo linux-arm64 ;;
        *) return 1 ;;
    esac
}

# The pinned SHA-256 of the binary for platform $1. MIFFAN_CONNECTOR_SHA256 overrides it (tests, development).
relay_expected_sha() {
    if [ -n "${MIFFAN_CONNECTOR_SHA256:-}" ]; then
        printf '%s' "$MIFFAN_CONNECTOR_SHA256" | tr 'A-F' 'a-f'
        return
    fi
    case "$1" in
        darwin-arm64) printf '%s' "$MIFFAN_CONNECTOR_SHA256_DARWIN_ARM64" ;;
        darwin-amd64) printf '%s' "$MIFFAN_CONNECTOR_SHA256_DARWIN_AMD64" ;;
        linux-amd64) printf '%s' "$MIFFAN_CONNECTOR_SHA256_LINUX_AMD64" ;;
        linux-arm64) printf '%s' "$MIFFAN_CONNECTOR_SHA256_LINUX_ARM64" ;;
    esac
}

relay_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    fi
}

# Downloads release file $1 to $2: the GitHub release first, then each mirror. Goes through the
# computer's own proxy like the cua download does.
relay_download() {
    cua_proxy
    for base in "$MIFFAN_CONNECTOR_RELEASE_URL" $MIFFAN_CONNECTOR_MIRRORS; do
        if curl -fsSL --connect-timeout 15 --max-time 300 --max-filesize 67108864 -o "$2" "$base/$1" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

# Version of the installed connector, from its own `version` output; empty when unknown.
relay_installed_version() {
    [ -x "$RELAY_BIN" ] || return 0
    "$RELAY_BIN" version 2>/dev/null | tail -n 1 | sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p'
}

# Gets the connector for this platform into $RELAY_BIN, verified. Sets RELAY_REPLACED=1 when the binary changed.
# Status 0 on success, otherwise it ends the command with a JSON failure.
relay_fetch_binary() {
    platform=$1
    RELAY_REPLACED=0
    if [ -z "${MIFFAN_CONNECTOR_FILE:-}" ] && [ "$(relay_installed_version)" = "$MIFFAN_CONNECTOR_VERSION" ]; then
        return 0
    fi
    expected=$(relay_expected_sha "$platform")
    mkdir -p "$HOME/.miffan/bin" || relay_fail install_failed "cannot create the install directory"
    tmp=$(mktemp "$HOME/.miffan/bin/.miffan-connector.XXXXXX") || relay_fail install_failed "cannot create a temporary file"
    if [ -n "${MIFFAN_CONNECTOR_FILE:-}" ]; then
        cp "$MIFFAN_CONNECTOR_FILE" "$tmp" 2>/dev/null || { rm -f "$tmp"; relay_fail install_failed "cannot read MIFFAN_CONNECTOR_FILE"; }
    else
        relay_download "miffan-connector-$platform" "$tmp" || { rm -f "$tmp"; relay_fail download_failed "could not download the connector"; }
    fi
    actual=$(relay_sha256 "$tmp")
    if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
        rm -f "$tmp"
        relay_fail checksum_mismatch "the connector does not match its pinned SHA-256; nothing was installed"
    fi
    chmod 755 "$tmp" && mv -f "$tmp" "$RELAY_BIN" || { rm -f "$tmp"; relay_fail install_failed "cannot install the connector"; }
    RELAY_REPLACED=1
}

relay_plist_write() {
    mkdir -p "$HOME/Library/LaunchAgents" "$RELAY_DIR" || return 1
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n'
        printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        printf '<plist version="1.0">\n<dict>\n<key>Label</key><string>%s</string>\n' "$RELAY_LABEL"
        printf '<key>ProgramArguments</key><array><string>%s</string><string>run</string></array>\n' "$(task_xml "$RELAY_BIN")"
        printf '<key>RunAtLoad</key><true/>\n<key>KeepAlive</key><true/>\n<key>ThrottleInterval</key><integer>10</integer>\n'
        printf '<key>ProcessType</key><string>Background</string>\n'
        printf '<key>StandardOutPath</key><string>/dev/null</string>\n<key>StandardErrorPath</key><string>%s</string>\n' "$(task_xml "$RELAY_LOG")"
        printf '</dict>\n</plist>\n'
    } >"$RELAY_PLIST.tmp" && mv -f "$RELAY_PLIST.tmp" "$RELAY_PLIST"
}

relay_unit_write() {
    mkdir -p "$HOME/.config/systemd/user" "$RELAY_DIR" || return 1
    {
        printf '[Unit]\nDescription=Miffan relay connector\nAfter=network-online.target\nWants=network-online.target\n\n'
        printf '[Service]\nExecStart=%%h/.miffan/bin/miffan-connector run\nRestart=always\nRestartSec=5\n\n'
        printf '[Install]\nWantedBy=default.target\n'
    } >"$RELAY_UNIT_FILE.tmp" && mv -f "$RELAY_UNIT_FILE.tmp" "$RELAY_UNIT_FILE"
}

# Keeps the connector's log from growing without bound: past 1 MiB only the last 128 KiB stay.
relay_trim_log() {
    [ -f "$RELAY_LOG" ] || return 0
    size=$(wc -c <"$RELAY_LOG" 2>/dev/null) || return 0
    if [ "${size:-0}" -gt 1048576 ]; then
        tail -c 131072 "$RELAY_LOG" >"$RELAY_LOG.tmp" && mv -f "$RELAY_LOG.tmp" "$RELAY_LOG"
    fi
    return 0
}

# Registers the login service and starts it. Ends the command with a JSON failure when it cannot.
relay_service_start() {
    relay_trim_log
    case "$(os)" in
        macos)
            relay_plist_write || relay_fail service_failed "cannot write the LaunchAgent"
            task_launchd_load "$RELAY_LABEL" "$RELAY_PLIST" ||
                relay_fail login_required "launchd has no login session for this user; sign in on the computer once"
            ;;
        linux)
            command -v systemctl >/dev/null 2>&1 || relay_fail no_service_manager "systemd is required"
            task_user_bus
            systemctl --user show-environment >/dev/null 2>&1 ||
                relay_fail no_service_manager "the systemd user manager is not reachable"
            relay_unit_write || relay_fail service_failed "cannot write the systemd unit"
            systemctl --user daemon-reload >/dev/null 2>&1 &&
                systemctl --user enable --now "$RELAY_UNIT" >/dev/null 2>&1 ||
                relay_fail service_failed "systemctl could not enable the service"
            [ "${RELAY_REPLACED:-0}" = 1 ] && systemctl --user restart "$RELAY_UNIT" >/dev/null 2>&1
            # Keeps the service running without a login session. Not an error when it is refused.
            command -v loginctl >/dev/null 2>&1 && loginctl enable-linger "$(id -un)" >/dev/null 2>&1
            ;;
        *) relay_fail unsupported_platform "this operating system is not supported" ;;
    esac
    return 0
}

relay_service_stop() {
    case "$(os)" in
        macos)
            launchctl bootout "gui/$(id -u)/$RELAY_LABEL" >/dev/null 2>&1
            rm -f "$RELAY_PLIST"
            ;;
        linux)
            if command -v systemctl >/dev/null 2>&1; then
                task_user_bus
                systemctl --user disable --now "$RELAY_UNIT" >/dev/null 2>&1
                rm -f "$RELAY_UNIT_FILE"
                systemctl --user daemon-reload >/dev/null 2>&1
            else
                rm -f "$RELAY_UNIT_FILE"
            fi
            ;;
    esac
    return 0
}

# running, stopped or missing.
relay_service_state() {
    case "$(os)" in
        macos)
            [ -f "$RELAY_PLIST" ] || { echo missing; return 0; }
            out=$(launchctl print "gui/$(id -u)/$RELAY_LABEL" 2>/dev/null) || { echo stopped; return 0; }
            case "$out" in *"state = running"*) echo running ;; *) echo stopped ;; esac
            ;;
        linux)
            [ -f "$RELAY_UNIT_FILE" ] || { echo missing; return 0; }
            task_user_bus
            if systemctl --user is-active --quiet "$RELAY_UNIT" 2>/dev/null; then echo running; else echo stopped; fi
            ;;
        *) echo missing ;;
    esac
}

# true or false on Linux (does the service run without a login session?), null elsewhere.
relay_linger() {
    [ "$(os)" = linux ] || { printf null; return 0; }
    command -v loginctl >/dev/null 2>&1 || { printf false; return 0; }
    case "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)" in yes) printf true ;; *) printf false ;; esac
}

relay_status() {
    if [ ! -x "$RELAY_BIN" ]; then
        printf '{"installed":false}\n'
        return 0
    fi
    extras=$(printf '"installed":true,"service":"%s","pinnedVersion":%s,"linger":%s' \
        "$(relay_service_state)" "$(json_str "$MIFFAN_CONNECTOR_VERSION")" "$(relay_linger)")
    line=$("$RELAY_BIN" status 2>/dev/null | tail -n 1)
    if printf '%s' "$line" | grep -Eq '^\{.*"success" *: *true.*\}$'; then
        printf '%s,%s}\n' "${line%\}}" "$extras"
    else
        reason=$(printf '%s' "$line" | sed -n 's/.*"reason" *: *"\([^"]*\)".*/\1/p')
        printf '{%s,"error":%s}\n' "$extras" "$(json_str "${reason:-connector_failed}")"
    fi
}

# stdin {"relay":"https://…","accessToken":"…"?}
relay_install() {
    [ "$(os)" != unknown ] || relay_fail unsupported_platform "this operating system is not supported"
    platform=$(relay_platform) || relay_fail unsupported_platform "no connector build for $(uname -s) $(uname -m)"
    raw=$(head -c 65536)
    [ -n "$raw" ] || relay_fail bad_input "relay is required"
    relay_fetch_binary "$platform"
    mkdir -p "$RELAY_DIR" && chmod 700 "$RELAY_DIR" || relay_fail install_failed "cannot create the data directory"
    out=$(printf '%s' "$raw" | "$RELAY_BIN" init --stdin 2>/dev/null)
    if [ "$?" -ne 0 ]; then
        line=$(printf '%s' "$out" | tail -n 1)
        case "$line" in '{'*) printf '%s\n' "$line" ;; *) printf '{"success":false,"reason":"init_failed","detail":"the connector could not be set up"}\n' ;; esac
        exit 1
    fi
    relay_service_start
    relay_status
}

# Restarts a registered connector service so a replaced binary takes over. One request to the service
# manager, which finishes the restart itself even if this helper's session ends with it.
relay_service_restart() {
    case "$(os)" in
        macos)
            [ -f "$RELAY_PLIST" ] || return 0
            launchctl kickstart -k "gui/$(id -u)/$RELAY_LABEL" >/dev/null 2>&1 ||
                task_launchd_load "$RELAY_LABEL" "$RELAY_PLIST" ||
                relay_fail login_required "launchd has no login session for this user; sign in on the computer once"
            ;;
        linux)
            [ -f "$RELAY_UNIT_FILE" ] || return 0
            command -v systemctl >/dev/null 2>&1 || relay_fail no_service_manager "systemd is required"
            task_user_bus
            systemctl --user restart "$RELAY_UNIT" >/dev/null 2>&1 || relay_fail service_failed "systemctl could not restart the service"
            ;;
    esac
    return 0
}

# Brings an installed connector to the pinned version and restarts its service. The relay, the computer's
# identity and the paired devices stay as they are; a connector of the pinned version is left alone.
relay_upgrade() {
    [ -x "$RELAY_BIN" ] || relay_fail not_installed "the connector is not installed"
    platform=$(relay_platform) || relay_fail unsupported_platform "no connector build for $(uname -s) $(uname -m)"
    relay_fetch_binary "$platform"
    if [ "$RELAY_REPLACED" = 1 ]; then
        relay_trim_log
        relay_service_restart
    fi
    relay_status
}

# stdin {"device":"<b64u SPKI>","name":"…"}
relay_pair() {
    [ -x "$RELAY_BIN" ] || relay_fail not_installed "the connector is not installed"
    raw=$(head -c 65536)
    out=$(printf '%s' "$raw" | "$RELAY_BIN" pair --stdin 2>/dev/null)
    code=$?
    line=$(printf '%s' "$out" | tail -n 1)
    case "$line" in
        '{'*) printf '%s\n' "$line" ;;
        *) printf '{"success":false,"reason":"connector_failed","detail":"the connector gave no answer"}\n'; code=1 ;;
    esac
    return "$code"
}

# stdin {"deviceId":"…"}
relay_unpair() {
    [ -x "$RELAY_BIN" ] || relay_fail not_installed "the connector is not installed"
    raw=$(head -c 65536 | tr -d '\n\r')
    id=$(space_json_string "$raw" deviceId) || relay_fail bad_input "deviceId is required"
    printf '%s' "$id" | grep -Eq '^[a-z2-7]{26}$' || relay_fail bad_input "deviceId is not valid"
    out=$("$RELAY_BIN" unpair "--device-id=$id" 2>/dev/null)
    code=$?
    line=$(printf '%s' "$out" | tail -n 1)
    case "$line" in
        '{'*) printf '%s\n' "$line" ;;
        *) printf '{"success":false,"reason":"connector_failed","detail":"the connector gave no answer"}\n'; code=1 ;;
    esac
    return "$code"
}

# stdin (optional) {"forgetIdentity":true}. host.key and the paired-device list stay unless asked,
# so a reinstall keeps the same hostId and the paired phones keep working.
relay_uninstall() {
    raw=""
    [ -t 0 ] || raw=$(head -c 4096 | tr -d '\n\r')
    forget=false
    printf '%s' "$raw" | grep -Eq '"forgetIdentity"[[:space:]]*:[[:space:]]*true' && forget=true
    relay_service_stop
    rm -f "$RELAY_BIN" "$RELAY_LOG" "$RELAY_LOG.tmp" "$RELAY_DIR/state.json" "$RELAY_DIR/run.lock" "$RELAY_DIR/config.lock"
    if [ "$forget" = true ]; then
        rm -rf "$RELAY_DIR"
    fi
    printf '{"success":true,"identityKept":%s}\n' "$([ "$forget" = true ] && echo false || echo true)"
}

relay() {
    umask 077
    action=${1:-}
    [ "$#" -eq 0 ] || shift
    case "$action" in
        status) relay_status ;;
        install) relay_install ;;
        upgrade) relay_upgrade ;;
        pair) relay_pair ;;
        unpair) relay_unpair ;;
        uninstall) relay_uninstall ;;
        *) echo "usage: miffan relay status|install|upgrade|pair|unpair|uninstall" >&2; exit 2 ;;
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
    snapshot) snapshot "${2:-}" ;;
    task) shift; task "$@" ;;
    space) shift; space "$@" ;;
    relay) shift; relay "$@" ;;
    window) shift; window_command "$@" ;;
    *) echo "usage: miffan version|probe|env|cua|vnc|rdp|sunshine|clip|mac-shortcut|snapshot|task|space|relay|window" >&2; exit 2 ;;
esac
