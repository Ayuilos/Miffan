#!/usr/bin/env python3
"""Checks for `miffan window` (one window of a Linux desktop) without a desktop: the shell entry on other
systems, and the embedded program's niri listing, input lines and window locating against fakes."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import types

HELPER = Path(__file__).resolve().parents[1] / "src/miffan.sh"
SH = os.environ.get("HELPER_SH", "/bin/sh")


def program():
    """The embedded Python program, loaded as a module (its main only runs as __main__)."""
    text = HELPER.read_text()
    start = text.index("<<'WINDOW_PY'\n") + len("<<'WINDOW_PY'\n")
    source = text[start:text.index("WINDOW_PY\n}", start)]
    module = types.ModuleType("window_program")
    module.__file__ = "<window>"
    exec(compile(source, "<window>", "exec"), module.__dict__)
    return module


def run_helper(os_name, *args):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "bin").mkdir()
        uname = root / "bin/uname"
        uname.write_text(f"#!/bin/sh\necho {os_name}\n")
        uname.chmod(0o755)
        for tool in ["sh", "cat", "sed", "tr", "printf"]:
            found = shutil.which(tool)
            if found:
                (root / "bin" / tool).symlink_to(found)
        done = subprocess.run([SH, str(HELPER), "window", *args], capture_output=True, text=True, timeout=30,
                              env={"HOME": str(root), "PATH": str(root / "bin")})
        return done.returncode, done.stdout.strip(), done.stderr


def test_other_systems_say_unsupported():
    code, out, _ = run_helper("Darwin", "probe")
    assert code == 0 and json.loads(out) == {"supported": False}
    code, out, err = run_helper("Darwin", "list")
    assert code == 1 and out == "" and "Linux" in err


class FakeNiri:
    """A niri IPC socket answering one JSON request per connection."""

    def __init__(self, replies):
        self.replies = replies
        self.requests = []
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "niri.sock")
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen(8)
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            with conn:
                data = b""
                while not data.endswith(b"\n"):
                    data += conn.recv(4096)
                request = json.loads(data)
                self.requests.append(request)
                key = request if isinstance(request, str) else next(iter(request))
                conn.sendall(json.dumps({"Ok": self.replies.get(key, {"Handled": None})}).encode() + b"\n")

    def close(self):
        self.server.close()
        self.dir.cleanup()


WINDOWS = [
    {"id": 7, "title": "Notes", "app_id": "org.example.Notes", "pid": 42, "workspace_id": 2, "is_focused": False,
     "is_floating": False, "layout": {"pos_in_scrolling_layout": [1, 1], "tile_size": [800.0, 600.0],
                                      "window_size": [800, 600], "tile_pos_in_workspace_view": None,
                                      "window_offset_in_tile": [0.0, 0.0]}},
    {"id": 9, "title": "Float", "app_id": "kitty", "pid": 43, "workspace_id": 1, "is_focused": True,
     "is_floating": True, "layout": {"pos_in_scrolling_layout": None, "tile_size": [400.0, 300.0],
                                     "window_size": [396, 296], "tile_pos_in_workspace_view": [100.0, 50.0],
                                     "window_offset_in_tile": [2.0, 2.0]}},
]
WORKSPACES = [
    {"id": 1, "idx": 1, "name": None, "output": "HDMI-A-1", "is_active": True, "is_focused": True},
    {"id": 2, "idx": 2, "name": None, "output": "HDMI-A-1", "is_active": False, "is_focused": False},
]
OUTPUTS = {"HDMI-A-1": {"logical": {"x": 0, "y": 0, "width": 2560, "height": 1440, "scale": 1.0}}}


def with_niri(fn):
    w = program()
    fake = FakeNiri({"Windows": {"Windows": WINDOWS}, "Workspaces": {"Workspaces": WORKSPACES},
                     "Outputs": {"Outputs": OUTPUTS}, "FocusedWindow": {"FocusedWindow": WINDOWS[1]}})
    os.environ["NIRI_SOCKET"] = fake.path
    try:
        fn(w, fake)
    finally:
        fake.close()


def test_list_names_apps_and_workspaces():
    def check(w, _):
        w.APP_NAMES["org.example.Notes"] = "Notes"  # no desktop entries on the test machine
        listed = w.niri_list()
        assert listed["current"] == 1 and listed["outputs"] == ["HDMI-A-1"]
        notes = next(x for x in listed["windows"] if x["id"] == "7")
        assert notes == {"id": "7", "pid": 42, "app": "Notes", "app_id": "org.example.Notes", "title": "Notes",
                         "workspace": 2, "focused": False, "width": 800, "height": 600}
        assert [s["id"] for s in listed["workspaces"] if s["active"]] == [1]
    with_niri(check)


def test_app_name_without_desktop_entry():
    w = program()
    os.environ["XDG_DATA_HOME"] = tempfile.mkdtemp()
    os.environ["XDG_DATA_DIRS"] = tempfile.mkdtemp()
    assert w.app_name("org.gnome.text-editor") == "Text Editor"
    assert w.app_name("") == ""


def test_floating_window_place_and_pointer():
    def check(w, _):
        place = w.niri_place("9")
        assert place["rect"] == (102.0, 52.0, 396, 296) and place["box"] == (0, 0, 2560, 1440)
        # A window on a workspace that is not shown has no place.
        assert w.niri_place("7") is None
        inputs = w.Input("9")
        inputs.rect, inputs.bounds = place["rect"], w.niri_bounds()
        assert inputs.point(0.0, 0.0) == (int(102 / 2560 * 65535), int(52 / 1440 * 65535))
        assert inputs.point(2.0, -1.0) == (int(498 / 2560 * 65535), int(52 / 1440 * 65535))
    with_niri(check)


def test_focus_and_resize_requests():
    def check(w, fake):
        assert w.niri_focus("7")
        assert {"Action": {"FocusWindow": {"id": 7}}} in fake.requests
        w.niri_resize("7", 640, 480)
        assert {"Action": {"SetWindowWidth": {"id": 7, "change": {"SetFixed": 640}}}} in fake.requests
        assert {"Action": {"SetWindowHeight": {"id": 7, "change": {"SetFixed": 480}}}} in fake.requests
    with_niri(check)


def test_match_finds_a_window_with_little_texture():
    w = program()
    sw, sh = 240, 140
    # A dark screen, and a dark window with one line of "text" and a lighter frame around it.
    screen = [[30 for _ in range(sw)] for _ in range(sh)]
    window = [[34 for _ in range(90)] for _ in range(70)]
    for x in range(6, 40):
        window[6][x] = window[7][x] = 220 if x % 3 else 34
    for y in range(70):
        window[y][0] = window[y][89] = 60
    ox, oy = 131, 47
    for y in range(70):
        screen[oy + y][ox:ox + 90] = window[y]
    at, agree = w.match(screen, sw, sh, window, 90, 70)
    assert at == (ox, oy) and agree > 0.9, (at, agree)


def test_match_rejects_a_window_that_is_not_there():
    w = program()
    screen = [[(x * 7 + y * 13) % 256 for x in range(200)] for y in range(120)]
    window = [[(x * 31 + y * 5) % 256 for x in range(60)] for y in range(40)]
    _, agree = w.match(screen, 200, 120, window, 60, 40)
    assert agree < 0.3


def test_keys():
    w = program()
    written = []

    class Device(w.Uinput):
        def __init__(self):
            self.pointer, self.keyboard = "pointer", "keyboard"

        @staticmethod
        def _write(fd, events):
            written.append((fd, events))
    keys = Device()
    keys.key("a", True)
    keys.key("!", True)
    keys.key("!", False)
    keys.key("unknown-key", True)
    assert written == [("keyboard", [(1, 30, 1)]), ("keyboard", [(1, 42, 1), (1, 2, 1)]),
                       ("keyboard", [(1, 2, 0), (1, 42, 0)])]
    written.clear()
    keys.scroll(0, -100)
    assert written == [("pointer", [(2, 0x0B, 120), (2, 0x08, 1)])]


def test_kde_crop_follows_the_window_and_stays_inside_the_frame():
    w = program()
    # A 1920x1080 logical output streamed at 2x: the window's frame doubles.
    assert w.crop_box((100, 50, 800, 600), (0, 0), (1920, 1080), 3840, 2160) == (200, 100, 2040, 860)
    # An output to the right of another: the stream starts at its own origin.
    assert w.crop_box((2020, 0, 400, 300), (1920, 0), (1920, 1080), 1920, 1080) == (100, 0, 1420, 780)
    # A window partly off the output is cut at the edge, never past it.
    left, top, right, bottom = w.crop_box((1800, 1000, 400, 300), (0, 0), (1920, 1080), 1920, 1080)
    assert (left, top) == (1800, 1000) and right == 0 and bottom == 0


def test_kde_text_goes_as_keysyms():
    w = program()
    assert [w.keysym_of(c) for c in "a\n\té你"] == [0x61, 0xff0d, 0xff09, 0xe9, 0x1000000 + ord("你")]


def test_kde_list_from_a_kwin_snapshot():
    w = program()
    w.APP_NAMES["org.kde.konsole"] = "Konsole"

    class FakeKWin:
        def snapshot(self):
            return {"current": 2, "outputs": ["DP-1"], "desktops": [{"id": 1, "name": "Mail"}, {"id": 2, "name": "Code"}],
                    "windows": [{"id": "0f9c", "pid": 7, "app": "org.kde.konsole", "cls": "konsole", "title": "zsh",
                                 "x": 0, "y": 0, "width": 900, "height": 600, "minimized": True, "focused": False,
                                 "output": "DP-1", "desktops": [1], "visible": False},
                                {"id": "77aa", "pid": 8, "app": "", "cls": "firefox", "title": "News",
                                 "x": 10, "y": 10, "width": 1200, "height": 800, "minimized": False, "focused": True,
                                 "output": "DP-1", "desktops": [], "visible": True}]}
    listed = w.kde_list(FakeKWin())
    assert listed["current"] == 2 and listed["desktop"] == "kde"
    konsole, firefox = listed["windows"]
    assert konsole["app"] == "Konsole" and konsole["workspace"] == 1 and konsole["minimized"] and not konsole["visible"]
    # A window on every desktop counts as on the current one; with no desktop entry its class names it.
    assert firefox["workspace"] == 2 and firefox["app"] == "" and firefox["app_id"] == "firefox"
    assert [d["active"] for d in listed["workspaces"]] == [False, True]


class FakeKWin:
    """A Wayland socket offering KWin's screencast and fake input globals; records the requests it gets."""

    def __init__(self, globals_):
        import struct
        self.struct = struct
        self.globals = globals_
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "wayland-test")
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen(4)
        self.requests = []
        self.bound = {}
        self.last_conn = None
        self.fail_with = None  # stream_window answers failed(this) instead of created
        threading.Thread(target=self._accept, daemon=True).start()

    def _string(self, text):
        raw = text.encode() + b"\0"
        return self.struct.pack("<I", len(raw)) + raw + b"\0" * (-len(raw) % 4)

    def _event(self, conn, obj, op, body=b""):
        conn.sendall(self.struct.pack("<IHH", obj, op, 8 + len(body)) + body)

    def _accept(self):
        while True:
            conn, _ = self.server.accept()
            self.last_conn = conn
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn):
        st, buf = self.struct, b""
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                return
            buf += chunk
            while len(buf) >= 8:
                obj, op, size = st.unpack("<IHH", buf[:8])
                if len(buf) < size:
                    break
                body, buf = buf[8:size], buf[size:]
                self.requests.append((obj, op, body))
                if obj == 1 and op == 1:  # get_registry
                    registry = st.unpack("<I", body)[0]
                    for name, (interface, version) in enumerate(self.globals, 1):
                        self._event(conn, registry, 0, st.pack("<I", name) + self._string(interface) + st.pack("<I", version))
                elif obj == 1 and op == 0:  # sync
                    self._event(conn, st.unpack("<I", body)[0], 0, st.pack("<I", 1))
                elif op == 0 and obj == 2:  # registry.bind
                    n = st.unpack("<I", body[4:8])[0]
                    interface = body[8:8 + n - 1].decode()
                    self.bound[interface] = st.unpack("<II", body[8 + n + (-n % 4):][:8])
                elif self.bound.get("zkde_screencast_unstable_v1", (0, -1))[1] == obj and op == 1:  # stream_window
                    stream = st.unpack("<I", body[:4])[0]
                    if self.fail_with:
                        self._event(conn, stream, 2, self._string(self.fail_with))  # failed(error)
                        continue
                    self._event(conn, stream, 3, st.pack("<II", 0, 7))  # serial, as KWin 6 sends first
                    self._event(conn, stream, 1, st.pack("<I", 4242))  # created(node)


def test_kde_direct_records_one_window_and_injects_input():
    import struct
    w = program()
    kwin = FakeKWin([("wl_compositor", 6), ("zkde_screencast_unstable_v1", 6), ("org_kde_kwin_fake_input", 6)])
    saved = os.environ.get("WAYLAND_DISPLAY")
    os.environ["WAYLAND_DISPLAY"] = kwin.path
    try:
        check_kde_direct(w, kwin)
    finally:
        if saved is None:
            os.environ.pop("WAYLAND_DISPLAY", None)
        else:
            os.environ["WAYLAND_DISPLAY"] = saved


def check_kde_direct(w, kwin):
    import struct
    wl = w.kde_direct()
    assert wl is not None
    closed = []
    cast = w.KwinCast(wl, "708d5d37-b7d0-4508-a139-8c42f605b068", on_closed=lambda: closed.append(True))
    assert cast.node == 4242
    fake = w.FakeInput(wl)
    fake.pointer(None, 100.5, 20)
    fake.key(30, True)
    fake.keysym(0x1000000 + ord("中"), True)
    fake.scroll(0, -100)
    wl.roundtrip()
    screencast, fake_input = kwin.bound["zkde_screencast_unstable_v1"][1], kwin.bound["org_kde_kwin_fake_input"][1]
    sent = [(op, body) for obj, op, body in kwin.requests if obj == fake_input]
    assert sent[0][0] == 0  # authenticate first
    assert (9, struct.pack("<ii", int(100.5 * 256), 20 * 256)) in sent
    assert (10, struct.pack("<II", 30, 1)) in sent
    assert (12, struct.pack("<II", 0x1000000 + ord("中"), 1)) in sent
    assert (3, struct.pack("<Ii", 0, int(-15 * 256))) in sent  # a notch up
    window = [body for obj, op, body in kwin.requests if obj == screencast and op == 1][0]
    assert b"708d5d37-b7d0-4508-a139-8c42f605b068\0" in window and window.endswith(struct.pack("<I", 2))
    cast.stop()
    wl.close()
    # Without the globals (an older KWin hiding them), the portal path is used.
    old = FakeKWin([("wl_compositor", 6), ("org_kde_kwin_fake_input", 4)])
    os.environ["WAYLAND_DISPLAY"] = old.path
    assert w.kde_direct() is None


def test_kde_window_ids_must_be_uuids():
    w = program()
    assert w.UUID.match("708d5d37-b7d0-4508-a139-8c42f605b068")
    assert not w.UUID.match('708d5d37"); workspace.activeWindow = null; ("')


def test_kde_portal_left_by_another_desktop_is_restarted():
    w = program()
    calls = []
    environ = {"value": b""}

    systemd_env = {"value": "XDG_CURRENT_DESKTOP=KDE\nWAYLAND_DISPLAY=wayland-0\n"}

    def run(args, **kwargs):
        calls.append(args)
        if "show-environment" in args:
            return types.SimpleNamespace(stdout=systemd_env["value"], returncode=0)
        return types.SimpleNamespace(stdout="4242\n" if "show" in args else "", returncode=0)

    real_open = open

    def fake_open(path, *args, **kwargs):
        if path == "/proc/4242/environ":
            from io import BytesIO
            return BytesIO(environ["value"])
        return real_open(path, *args, **kwargs)

    w.subprocess = types.SimpleNamespace(run=run, DEVNULL=subprocess.DEVNULL, TimeoutExpired=subprocess.TimeoutExpired)
    w.open = fake_open
    w.Portal = lambda Gio, GLib, devices: ("portal", devices)
    # Started for niri, or before any desktop said which it is: restarted, then used.
    for env in [b"HOME=/h\0XDG_CURRENT_DESKTOP=niri\0", b"HOME=/h\0"]:
        environ["value"] = env
        calls.clear()
        assert w.open_portal(None, None, devices=False) == ("portal", False)
        assert ["systemctl", "--user", "restart", "xdg-desktop-portal.service"] in calls
    # Started for this Plasma session: left alone.
    environ["value"] = b"XDG_CURRENT_DESKTOP=KDE\0WAYLAND_DISPLAY=wayland-0\0"
    calls.clear()
    assert w.open_portal(None, None) == ("portal", True)
    assert not any("restart" in args for args in calls)
    # Plasma not started through systemd: a restarted service would come back the same, so no restart
    # (it would end other apps' portal sessions for nothing, on every stream).
    environ["value"] = b"XDG_CURRENT_DESKTOP=GNOME\0"
    systemd_env["value"] = "WAYLAND_DISPLAY=wayland-0\n"
    calls.clear()
    try:
        w.open_portal(None, None)
        raise AssertionError("expected PortalNotKde")
    except w.PortalNotKde:
        pass
    assert not any("restart" in args for args in calls)


def test_gnome_list_without_the_extension_asks_for_it():
    w = program()

    def failing(*_args, **_kwargs):
        raise RuntimeError("no extension")
    w.gnome_snapshot = failing
    os.environ["XDG_DATA_HOME"] = tempfile.mkdtemp()
    listed = w.gnome_list(None, None)
    assert listed["error"] == "needs_extension" and listed["extension"] == "missing" and listed["windows"] == []
    # Written but not loaded yet: GNOME takes it at the next login.
    os.makedirs(w.gnome_extension_dir())
    for name, text in w.GNOME_EXTENSION_FILES.items():
        open(os.path.join(w.gnome_extension_dir(), name), "w").write(text)
    assert w.gnome_list(None, None)["extension"] == "installed"


def test_gnome_list_from_the_extension():
    w = program()
    w.APP_NAMES["org.gnome.TextEditor"] = "Text Editor"
    w.gnome_snapshot = lambda *_: {"current": 1, "desktops": [{"id": 1, "name": None}, {"id": 2, "name": None}],
                                   "windows": [{"id": "567034158", "pid": 9, "app": "org.gnome.TextEditor", "cls": "gnome-text-editor",
                                                "title": "Notes", "x": 0, "y": 0, "width": 800, "height": 600, "buffer": [0, 0, 800, 600],
                                                "minimized": False, "focused": True, "desktop": None, "visible": True}]}
    listed = w.gnome_list(None, None)
    window = listed["windows"][0]
    assert window["app"] == "Text Editor" and window["workspace"] == 1 and window["id"] == "567034158"
    assert [d["active"] for d in listed["workspaces"]] == [True, False]


def test_gnome_extension_is_valid_metadata():
    w = program()
    meta = json.loads(w.GNOME_METADATA_JSON)
    # A new GNOME needs its number here (a release-check item, see PROTOCOL.md).
    assert meta["uuid"] == w.GNOME_EXTENSION and "52" in meta["shell-version"]
    assert "me.ayuilos.Miffan.Windows" in w.GNOME_EXTENSION_JS and "export default class" in w.GNOME_EXTENSION_JS


def test_crop_meta_is_none_without_meta():
    w = program()

    class Buffer:
        def get_meta(self, _name):
            return None
    assert w.crop_meta(Buffer()) is None


def test_x11_list_numbers_desktops_from_one():
    w = program()
    w.APP_NAMES["XTerm"] = "XTerm"

    class FakeX11:
        def snapshot(self):
            return {"current": 0, "desktops": [{"id": 0, "name": "Main"}, {"id": 1, "name": ""}],
                    "windows": [{"id": "29360140", "pid": 5, "cls": "XTerm", "instance": "xterm", "title": "shell",
                                 "minimized": False, "focused": True, "desktop": 1, "x": 10, "y": 30, "width": 500,
                                 "height": 300, "viewable": True, "frame": (8, 2, 504, 330), "visible": True}]}
    listed = w.x11_list(FakeX11())
    assert listed["current"] == 1 and listed["windows"][0]["workspace"] == 2
    assert listed["windows"][0]["width"] == 504 and listed["workspaces"][0]["name"] == "Main"
    assert listed["workspaces"][1]["name"] is None


# ---------- KDE: the window stream's frame and input mapping ----------

def test_kde_point_maps_against_the_frame_and_its_popups():
    w = program()
    # The window at (100, 50) 800x600; a menu opened near its bottom-right corner reaches 150 px past
    # its right edge and 100 px below it. KWin's stream then frames the union (100, 50)-(1050, 750).
    place = {"x": 100, "y": 50, "width": 950, "height": 700, "frame": [100, 50, 800, 600]}
    view = {"rect": w.kde_rect(place), "origin": (0, 0)}
    # The stream's corners are the union's corners.
    assert w.kde_point(view, 0.0, 0.0) == (100, 50)
    assert w.kde_point(view, 1.0, 1.0) == (1050, 750)
    # A menu item in the part outside the window, at 0.9, 0.95 of the stream.
    x, y = w.kde_point(view, 0.9, 0.95)
    assert (round(x), round(y)) == (955, 715) and x > 900 and y > 650
    # The same tap mapped against the frame alone (the old way) lands inside the window instead.
    old = {"rect": (100, 50, 800, 600), "origin": (0, 0)}
    assert w.kde_point(old, 0.9, 0.95) == (820.0, 620.0)
    # A menu opened up and left of the window moves the stream's origin too.
    left = {"rect": w.kde_rect({"x": 40, "y": 10, "width": 860, "height": 640}), "origin": (0, 0)}
    assert w.kde_point(left, 0.0, 0.0) == (40, 10)
    # The portal stream: relative to its output's origin.
    portal = {"rect": (2020, 100, 400, 300), "origin": (1920, 0)}
    assert w.kde_point(portal, 0.5, 0.5) == (300.0, 250.0)


def node():
    found = shutil.which("node")
    if not found:
        print("SKIP: node not installed; KWin script checks not run")
    return found


FAKE_WORKSPACE_JS = r"""
const signal = () => { const s = {fns: [], connect(f) { s.fns.push(f); }, fire(...a) { s.fns.forEach(f => f(...a)); }}; return s; };
const win = (id, g, extra) => Object.assign({internalId: {toString: () => "{" + id + "}"}, frameGeometry: g,
    popupWindow: false, transientFor: null, minimized: false, onAllDesktops: true, desktops: [], output: {name: "DP-1"},
    frameGeometryChanged: signal(), minimizedChanged: signal(), desktopsChanged: signal()}, extra || {});
const main = win("708d5d37-b7d0-4508-a139-8c42f605b068", {x: 100, y: 50, width: 800, height: 600});
const menu = win("m1", {x: 700, y: 500, width: 350, height: 250}, {popupWindow: true, transientFor: main});
const sub = win("m2", {x: 1040, y: 600, width: 200, height: 100}, {popupWindow: true, transientFor: menu});
const dialog = win("d1", {x: 0, y: 0, width: 3000, height: 3000}, {transientFor: main});
const other = win("o1", {x: 0, y: 0, width: 50, height: 50}, {popupWindow: true, transientFor: null});
const list = [main, menu, sub, dialog, other];
const workspace = {windowList: () => list, activeWindow: main, currentDesktop: 1, windowActivated: signal(),
    currentDesktopChanged: signal(), windowAdded: signal(), windowRemoved: signal()};
const sent = [];
"""


def run_node(script):
    done = subprocess.run([node(), "-e", script], capture_output=True, text=True, timeout=30)
    assert done.returncode == 0, done.stderr
    return [json.loads(line) for line in done.stdout.splitlines()]


def test_kwin_place_script_unites_the_frame_with_its_popups():
    w = program()
    if not node():
        return
    body = (w.KWin.FIND % "708d5d37-b7d0-4508-a139-8c42f605b068") + "if (!w) return null;" + w.KWIN_PLACE_JS + "return place();"
    out = run_node(FAKE_WORKSPACE_JS + "console.log(JSON.stringify((function () { %s })()));" % body)
    place = out[0]
    # The menu and its submenu (a popup of a popup) count; a dialog (not a popup) and a popup of
    # another window do not, as in KWin's WindowScreenCastSource.
    assert (place["x"], place["y"], place["width"], place["height"]) == (100, 50, 1140, 700)
    assert place["frame"] == [100, 50, 800, 600] and place["focused"] and place["shown"]


def test_kwin_watch_script_reports_changes_and_the_end():
    w = program()
    if not node():
        return
    body = ((w.KWin.FIND % "708d5d37-b7d0-4508-a139-8c42f605b068") + "if (!w) { send(null); return; }"
            + w.KWIN_PLACE_JS + w.KWIN_WATCH_JS)
    script = FAKE_WORKSPACE_JS + """
const send = v => console.log(JSON.stringify(v));
(function () { %s })();
list.splice(1, 2);               // the menus close
workspace.windowRemoved.fire(menu);
main.frameGeometry = {x: 10, y: 20, width: 300, height: 200};
main.frameGeometryChanged.fire();
main.minimized = true;
main.minimizedChanged.fire();
const late = win("m3", {x: 300, y: 210, width: 100, height: 100}, {popupWindow: true, transientFor: main});
list.push(late);
workspace.windowAdded.fire(late);
late.frameGeometry = {x: 300, y: 210, width: 100, height: 300};
late.frameGeometryChanged.fire();
workspace.windowRemoved.fire(main);
""" % body
    out = run_node(script)
    rects = [(o["x"], o["y"], o["width"], o["height"]) if o and "x" in o else o for o in out]
    assert rects[0] == (100, 50, 1140, 700)            # at the start, with both menus
    assert rects[1] == (100, 50, 800, 600)             # menus gone
    assert rects[2] == (10, 20, 300, 200)              # moved and resized
    assert out[3]["minimized"] is True and out[3]["shown"] is False
    assert rects[4] == (10, 20, 390, 290)              # a new popup reaching out of the window
    assert rects[5] == (10, 20, 390, 490)              # that popup grew
    assert out[-1] == {"gone": True}


def test_embedded_scripts_parse():
    w = program()
    if not node():
        return
    tmp = tempfile.mkdtemp()
    for name, text in [("place.js", "(function(){ const w = {};" + w.KWIN_PLACE_JS + w.KWIN_WATCH_JS + "})"),
                       ("extension.mjs", w.GNOME_EXTENSION_JS)]:
        path = os.path.join(tmp, name)
        with open(path, "w") as f:
            f.write(text)
        done = subprocess.run([node(), "--check", path], capture_output=True, text=True, timeout=30)
        assert done.returncode == 0, (name, done.stderr)


# ---------- KDE: KWin scripts that do not answer ----------

class FakeScripting:
    """KWin's Scripting D-Bus calls, answering each script the way [answer] says."""

    def __init__(self, kwin, answer):
        self.kwin, self.answer, self.calls, self.loaded = kwin, answer, [], {}

    def call_sync(self, _name, path, _iface, method, args, *_rest):
        self.calls.append(method)
        if method == "loadScript":
            file, plugin = args
            with open(file) as f:
                self.loaded[plugin] = (file, f.read())
            return (len(self.loaded),)
        if method == "run":
            kind = self.answer
            if kind == "raise":
                raise RuntimeError("org.freedesktop.DBus.Error.NoReply")
            file, script = list(self.loaded.values())[-1]
            token = script.split('"Report", "')[1].split('"')[0]
            if kind != "silent":
                threading.Thread(target=self.kwin.report, args=(token, kind)).start()
        return None


def fake_kwin(w, answer):
    kwin = object.__new__(w.KWin)
    kwin.name, kwin.results, kwin.watchers, kwin.lock, kwin.count = "org.miffan.Window.p1", {}, {}, threading.Lock(), 0
    kwin.GLib = types.SimpleNamespace(Variant=lambda _sig, value: value, VariantType=lambda sig: sig)
    kwin.bus = FakeScripting(kwin, answer)
    return kwin


def test_kwin_run_tells_null_from_no_answer():
    w = program()
    os.environ["XDG_RUNTIME_DIR"] = tempfile.mkdtemp()
    uuid = "708d5d37-b7d0-4508-a139-8c42f605b068"
    # An explicit null: the window is gone.
    kwin = fake_kwin(w, "null")
    assert kwin.geometry(uuid) is None
    # Scripts live in a directory only this user reads, and go once answered.
    folder = os.path.join(os.environ["XDG_RUNTIME_DIR"], "miffan-kwin")
    assert os.stat(folder).st_mode & 0o777 == 0o700 and os.listdir(folder) == []
    assert kwin.bus.calls == ["loadScript", "run", "unloadScript"]
    assert json.dumps({"x": 1}) and fake_kwin(w, '{"x": 1}').run("return {x: 1};") == {"x": 1}
    # No answer in time (KWin busy), or the call failing: an error, never "gone".
    for answer in ("silent", "raise"):
        kwin = fake_kwin(w, answer)
        try:
            kwin.run("return 1;", timeout=0.2)
            raise AssertionError("expected KWinError for " + answer)
        except w.KWinError:
            pass
        assert "unloadScript" in kwin.bus.calls and os.listdir(folder) == []
    # A late answer to a run that gave up is dropped, not taken for a later run's.
    kwin = fake_kwin(w, "silent")
    try:
        kwin.run("return 1;", timeout=0.05)
    except w.KWinError:
        pass
    kwin.report("t1", "true")
    assert kwin.results == {}


class InlineGLib:
    @staticmethod
    def idle_add(fn, *args):
        fn(*args)
        return 0


def test_kde_follow_ends_only_when_kwin_says_the_window_is_gone():
    w = program()
    events = []
    w.emit = lambda line, payload=None: events.append(line)
    readings = [w.KWinError("no answer"), RuntimeError("D-Bus"), None]

    class Kwin:
        def watch(self, *_args):
            raise w.KWinError("watch did not start")

        def geometry(self, _window_id):
            value = readings.pop(0) if readings else None
            if isinstance(value, Exception):
                raise value
            return value
    closed = []
    view = {"rect": (0, 0, 10, 10), "minimized": False}
    follow = w.KdeFollow(InlineGLib, Kwin(), "id", view, closed.append)
    assert follow.stop_watch is None and follow.interval == 0.5
    deadline = time.monotonic() + 5
    while not closed and time.monotonic() < deadline:
        time.sleep(0.05)
    follow.stop()
    # Two failed readings were reported once as info and tried again; only the explicit null closed it.
    assert closed == ["window_closed"]
    assert [e for e in events if e.startswith("info")] == [
        'info {"kwin_watch": false}', 'info {"kwin": "no_answer", "detail": "no answer"}']


def test_kde_follow_takes_the_watch_reports():
    w = program()
    events = []
    w.emit = lambda line, payload=None: events.append(line)
    reports = []

    class Kwin:
        def watch(self, _window_id, on_place):
            reports.append(on_place)
            return lambda: reports.append("stopped")

        def geometry(self, _window_id):
            raise AssertionError("no polling within 5 s while the watch runs")
    moved, closed = [], []
    view = {"rect": (0, 0, 10, 10), "minimized": False}
    follow = w.KdeFollow(InlineGLib, Kwin(), "id", view, closed.append, moved=lambda: moved.append(view["rect"]))
    assert follow.interval == 5.0
    on_place = reports[0]
    on_place({"x": 5, "y": 6, "width": 70, "height": 80, "minimized": False})
    on_place({"x": 5, "y": 6, "width": 70, "height": 80, "minimized": True})
    assert view["rect"] == (5, 6, 70, 80) and moved == [(5, 6, 70, 80)]
    assert "suspended minimized" in events
    on_place({"gone": True})
    assert closed == ["window_closed"] and reports[-1] == "stopped"


# ---------- KDE: the Wayland connection's failures ----------

def test_wayland_error_and_lost_connection():
    import struct
    w = program()
    kwin = FakeKWin([("zkde_screencast_unstable_v1", 6), ("org_kde_kwin_fake_input", 6)])
    kwin.fail_with = "unknown window"
    os.environ["WAYLAND_DISPLAY"] = kwin.path
    wl = w.kde_direct()
    # KWin refusing the stream names the reason.
    try:
        w.KwinCast(wl, "708d5d37-b7d0-4508-a139-8c42f605b068")
        raise AssertionError("expected the stream to fail")
    except RuntimeError as error:
        assert str(error) == "unknown window"
    # wl_display.error, then KWin closing the connection: the reader reports the loss.
    lost = threading.Event()
    wl.on_lost = lost.set
    stderr = []
    w.sys = types.SimpleNamespace(stderr=types.SimpleNamespace(write=stderr.append), executable=sys.executable)
    kwin._event(kwin.last_conn, 1, 0, struct.pack("<II", 3, 1) + kwin._string("invalid object"))
    kwin.last_conn.shutdown(socket.SHUT_RDWR)
    assert lost.wait(3)
    assert any("invalid object" in s for s in stderr)


def test_kwin_cast_closed_event_ends_the_stream():
    import struct
    w = program()
    kwin = FakeKWin([("zkde_screencast_unstable_v1", 6), ("org_kde_kwin_fake_input", 6)])
    os.environ["WAYLAND_DISPLAY"] = kwin.path
    wl = w.kde_direct()
    closed = threading.Event()
    cast = w.KwinCast(wl, "708d5d37-b7d0-4508-a139-8c42f605b068", on_closed=closed.set)
    kwin._event(kwin.last_conn, cast.stream, 0)
    assert closed.wait(3)
    wl.close()


def test_kde_direct_is_not_used_where_the_stream_lacks_title_bar_and_popups():
    w = program()
    kwin = FakeKWin([("zkde_screencast_unstable_v1", 6), ("org_kde_kwin_fake_input", 6)])
    os.environ["WAYLAND_DISPLAY"] = kwin.path
    w.KWIN_VERSION[:] = [(6, 4)]
    assert w.kde_direct() is None
    w.KWIN_VERSION[:] = [(6, 8)]
    wl = w.kde_direct()
    assert wl is not None
    wl.close()


def test_kde_version_gates():
    w = program()
    w.KWIN_VERSION[:] = [(5, 27)]
    assert w.plasma5() and not w.kde_prompts_every_time()
    w.KWIN_VERSION[:] = [(6, 2)]
    assert not w.plasma5() and w.kde_prompts_every_time()
    w.KWIN_VERSION[:] = [(6, 3)]
    assert not w.kde_prompts_every_time()
    # Unknown KWin: the session's own word.
    w.KWIN_VERSION[:] = [None]
    os.environ["KDE_SESSION_VERSION"] = "5"
    assert w.plasma5()
    os.environ["KDE_SESSION_VERSION"] = "6"
    assert not w.plasma5() and not w.kde_prompts_every_time()


# ---------- input lines on each desktop ----------

def test_key_lines_send_characters_as_keysyms_and_named_keys_as_codes():
    w = program()
    assert w.key_action("a") == ("sym", 0x61)
    assert w.key_action("A") == ("sym", 0x41)
    assert w.key_action("!") == ("sym", 0x21)
    assert w.key_action("é") == ("sym", 0xe9)
    assert w.key_action("Control") == ("named", "control") and w.key_action("f5") == ("named", "f5")
    assert w.key_action("unknown-key") is None and w.key_action(" ") is None
    assert [w.notches(v) for v in (0, 100, 30, -30, -250)] == [0, 1, 1, -1, -2]
    move = lambda x: ["move", x, "0.5"]
    lines = [move("0.1"), move("0.2"), ["down", "left", "0.2", "0.5"], move("0.3"), move("0.4"), None]
    assert w.coalesce_moves(lines) == [move("0.2"), ["down", "left", "0.2", "0.5"], move("0.4"), None]


class Recorder:
    def __init__(self):
        self.calls = []

    def __getattr__(self, name):
        return lambda *args: self.calls.append((name,) + args)


def unlocked(inputs):
    inputs.held_by_lock = lambda: False
    return inputs


def test_kde_input_keys_follow_the_layout():
    import base64
    w = program()
    device = Recorder()
    view = {"rect": (100, 50, 950, 700), "origin": (0, 0), "node": 7}
    inputs = unlocked(w.KdeInput("id", None, device, view))
    inputs.ensure_front = lambda: True
    for line in ["key control 1", "key a 1", "key a 0", "key control 0", "move 1 1", "down left 0 0",
                 "scroll 0.5 0.5 0 -100", "text " + base64.b64encode("é中".encode()).decode()]:
        inputs.apply(line.split())
    assert device.calls[:4] == [("key", 29, True), ("keysym", 0x61, True), ("keysym", 0x61, False), ("key", 29, False)]
    assert ("pointer", 7, 1050, 750) in device.calls and ("button", 0x110, True) in device.calls
    assert ("scroll", 0.0, -100.0) in device.calls
    assert device.calls[-4:] == [("keysym", 0xe9, True), ("keysym", 0xe9, False),
                                 ("keysym", 0x1000000 + ord("中"), True), ("keysym", 0x1000000 + ord("中"), False)]
    # Paste is Ctrl (code) + V (keysym): the paste shortcut on Dvorak too.
    device.calls.clear()
    inputs.paste()
    assert device.calls == [("key", 29, True), ("keysym", 0x76, True), ("keysym", 0x76, False), ("key", 29, False)]


def test_gnome_input_mapping():
    import base64
    w = program()
    sessions = []
    mutter = types.SimpleNamespace(stream="/stream", session=lambda *a: sessions.append(a), offer_text=lambda t: sessions.append(("offer", t)))
    w.time = types.SimpleNamespace(sleep=lambda _s: None, monotonic=time.monotonic)
    view = {"size": (800, 600)}
    inputs = unlocked(w.GnomeInput("1", mutter, None, None, view))
    inputs.ensure_front = lambda: True
    inputs.apply("key a 1".split())
    # Mutter's virtual keyboard is made on first use: a Shift tap first.
    assert sessions[:3] == [("NotifyKeyboardKeycode", "(ub)", 42, True), ("NotifyKeyboardKeycode", "(ub)", 42, False),
                            ("NotifyKeyboardKeysym", "(ub)", 0x61, True)]
    sessions.clear()
    inputs.apply("key control 1".split())
    inputs.apply("move 0.5 0.25".split())
    inputs.apply("scroll 0.5 0.25 30 -250".split())
    assert sessions[0] == ("NotifyKeyboardKeycode", "(ub)", 29, True)
    assert ("NotifyPointerMotionAbsolute", "(sdd)", "/stream", 400.0, 150.0) in sessions
    assert ("NotifyPointerAxisDiscrete", "(ui)", 0, -2) in sessions and ("NotifyPointerAxisDiscrete", "(ui)", 1, 1) in sessions
    sessions.clear()
    inputs.apply(["text", base64.b64encode("hi".encode()).decode()])
    assert [s[2] for s in sessions] == [0x68, 0x68, 0x69, 0x69]
    sessions.clear()
    inputs.apply(["text", base64.b64encode("你好".encode()).decode()])
    assert sessions == [("offer", "你好"), ("NotifyKeyboardKeycode", "(ub)", 29, True), ("NotifyKeyboardKeysym", "(ub)", 0x76, True),
                        ("NotifyKeyboardKeysym", "(ub)", 0x76, False), ("NotifyKeyboardKeycode", "(ub)", 29, False)]


def test_x11_input_mapping():
    import base64
    w = program()
    x = Recorder()
    x.named_keysym = lambda name: {"control": 0xffe3, "f5": 0xffc2}.get(name, 0)
    view = {"rect": (10, 30, 501, 301)}
    inputs = unlocked(w.X11Input("5", x, view))
    inputs.ensure_front = lambda: True
    for line in ["key control 1", "key a 1", "key f5 1", "move 1 0.5", "down right 0 0", "scroll 0 0 -250 100",
                 "text " + base64.b64encode("aé".encode()).decode()]:
        inputs.apply(line.split())
    assert x.calls[:3] == [("keysym", 0xffe3, True), ("keysym", 0x61, True), ("keysym", 0xffc2, True)]
    assert ("pointer", 510.0, 180.0) in x.calls and ("button", 3, True) in x.calls
    # One click a notch: DY 100 is one down (button 5), DX -250 two left (button 6).
    clicks = [c[1] for c in x.calls if c[0] == "button" and c[2] is True and c[1] in (4, 5, 6, 7)]
    assert clicks == [5, 6, 6]
    assert x.calls[-1] == ("type_keysyms", [0x61, 0xe9])


def fake_xlib(mapped, spares):
    """A keyboard mapping (keysym -> keycode) with [spares] free keycodes; records what changes."""
    changes, keys = [], []
    table = dict(mapped)

    class X:
        @staticmethod
        def XKeysymToKeycode(_dpy, sym):
            return table.get(sym, 0)

        @staticmethod
        def XKeycodeToKeysym(_dpy, code, level):
            return next((s for s, c in table.items() if c == code), 0) if level == 0 else 0

        @staticmethod
        def XChangeKeyboardMapping(_dpy, code, _n, syms, _per):
            changes.append((code, syms[0]))

        @staticmethod
        def XSync(_dpy, _discard):
            pass

    class T:
        @staticmethod
        def XTestFakeKeyEvent(_dpy, code, down, _delay):
            keys.append((code, down))
    x11 = object.__new__(w_program.X11)
    x11.x, x11.xtst, x11.dpy, x11.lock, x11.borrowed = X(), T(), None, threading.Lock(), {}
    x11.GIVE_BACK_DELAY = 0
    x11._spare_keycodes = lambda: list(spares)
    return x11, changes, keys


w_program = None


def test_x11_borrowed_keycodes_last_the_whole_text():
    global w_program
    w_program = program()
    x11, changes, keys = fake_xlib({0x61: 38}, [250, 251])
    a, ni, hao = 0x61, 0x1000000 + ord("你"), 0x1000000 + ord("好")
    x11.type_keysyms([ni, a, hao, ni])
    # Each sym no key has gets a spare, mapped once; both are given back after the whole text.
    assert changes == [(251, ni), (250, hao), (251, 0), (250, 0)]
    assert keys == [(251, 1), (251, 0), (38, 1), (38, 0), (250, 1), (250, 0), (251, 1), (251, 0)]
    # More such syms than spares: the spares are given back, then borrowed again.
    x11, changes, keys = fake_xlib({}, [250])
    x11.type_keysyms([ni, hao])
    assert changes == [(250, ni), (250, 0), (250, hao), (250, 0)]
    # A key line holding a borrowed key keeps it until its release.
    x11, changes, keys = fake_xlib({}, [250])
    x11.keysym(ni, True)
    assert changes == [(250, ni)] and x11.borrowed == {ni: 250}
    x11.keysym(ni, False)
    assert changes == [(250, ni), (250, 0)] and x11.borrowed == {} and keys == [(250, 1), (250, 0)]


def test_niri_character_keys_go_through_wtype_with_held_modifiers():
    import base64
    w = program()
    ran = []
    w.run_quiet = lambda args, **kwargs: ran.append((args, kwargs)) or 0
    w.shutil = types.SimpleNamespace(which=lambda name: "/usr/bin/" + name)
    os.environ["WAYLAND_DISPLAY"] = "wayland-1"
    written = []
    device = types.SimpleNamespace(code=lambda c, d: written.append(("code", c, d)),
                                   key=lambda n, d: written.append(("key", n, d)))
    inputs = unlocked(w.Input("7"))
    inputs.device = device
    inputs.ensure_front = lambda: True
    for line in ["key control 1", "key shift 1", "key a 1", "key a 0", "key shift 0", "key control 0", "key b 1"]:
        inputs.apply(line.split())
    assert written == [("code", 29, True), ("code", 42, True), ("code", 42, False), ("code", 29, False)]
    assert [a for a, _ in ran] == [["wtype", "-M", "ctrl", "-M", "shift", "-k", "U0061", "-m", "shift", "-m", "ctrl"],
                                   ["wtype", "-k", "U0062"]]
    # Text: wtype; when it fails, the clipboard (wl-copy apart from the stream's stdout) and paste.
    ran.clear()
    w.run_quiet = lambda args, **kwargs: ran.append((args, kwargs)) or (1 if args[:2] == ["wtype", "--"] else 0)
    inputs.apply(["text", base64.b64encode("你好".encode()).decode()])
    assert ran[0][0] == ["wtype", "--", "你好"]
    assert ran[1][0][0] == "wl-copy" and ran[1][1]["detach"] and ran[1][1]["data"] == "你好".encode()
    assert ran[2][0] == ["wtype", "-M", "ctrl", "-k", "U0076", "-m", "ctrl"]
    # Without wtype: the US key through uinput.
    w.shutil = types.SimpleNamespace(which=lambda name: None)
    written.clear()
    inputs.apply("key A 1".split())
    assert written == [("key", "A", True)]


def test_uinput_types_capitals_with_shift():
    w = program()
    written = []

    class Device(w.Uinput):
        def __init__(self):
            self.pointer, self.keyboard = "pointer", "keyboard"

        @staticmethod
        def _write(fd, events):
            written.append(events)
    keys = Device()
    keys.key("A", True)
    keys.key("A", False)
    assert written == [[(1, 42, 1), (1, 30, 1)], [(1, 30, 0), (1, 42, 0)]]


def test_helper_tools_never_write_to_the_frame_stream():
    w = program()
    seen = {}

    def run(args, **kwargs):
        seen.update(kwargs)
        return types.SimpleNamespace(returncode=0)
    w.subprocess = types.SimpleNamespace(run=run, DEVNULL=subprocess.DEVNULL, TimeoutExpired=subprocess.TimeoutExpired)
    assert w.run_quiet(["wl-copy"], data=b"x", detach=True) == 0
    assert seen["stdout"] is subprocess.DEVNULL and seen["stderr"] is subprocess.DEVNULL
    assert seen["start_new_session"] is True and seen["input"] == b"x" and seen["stdin"] is None


# ---------- the stream's protocol ----------

def test_frame_writer_bumps_the_epoch_when_new_size_frames_come_out():
    import io
    w = program()
    w.OUT = io.BytesIO()
    writer = w.FrameWriter((640, 360))
    writer.write(b"AAAA", 1, 640, 360, 10)
    writer.write(b"BB", 0, 640, 360, 20)
    writer.write(b"CCC", 1, 800, 450, 30)
    out = w.OUT.getvalue()
    assert out == (b"resumed\nframe 1 0 1 640 360 10 4\nAAAA" b"frame 2 0 0 640 360 20 2\nBB"
                   b"geometry 800 450\nframe 3 1 1 800 450 30 3\nCCC")


def test_frame_queue_skips_to_a_keyframe_when_the_channel_falls_behind():
    w = program()
    asked = []
    q = w.FrameQueue(3, lambda: asked.append(True))
    for i, key in enumerate([1, 0, 0]):
        q.put(i, key)
    q.put(3, 0)       # a fourth waiting frame: drop them all, wait for a keyframe
    q.put(4, 0)       # still no keyframe: skipped
    q.put(5, 1)
    q.put(6, 0)
    q.end()
    assert asked == [True]
    got = []
    while True:
        frame = q.take()
        if frame is None:
            break
        got.append(frame)
    assert got == [5, 6]


class FakeElement:
    def __init__(self, name):
        self.name, self.handlers, self.props, self.events = name, {}, {}, []

    def connect(self, signal, fn):
        self.handlers[signal] = fn

    def get_static_pad(self, _name):
        return types.SimpleNamespace(add_probe=lambda *_a: None, send_event=self.events.append)

    def set_property(self, key, value):
        self.props[key] = value

    def emit(self, _signal):
        return self.sample


class FakePipeline:
    def __init__(self, description):
        self.description, self.elements, self.states = description, {}, []
        self.bus = FakeElement("bus")
        self.bus.add_signal_watch = lambda: None

    def get_by_name(self, name):
        return self.elements.setdefault(name, FakeElement(name))

    def get_bus(self):
        return self.bus

    def set_state(self, state):
        self.states.append(state)


class FakeLoop:
    def __init__(self):
        self.done = threading.Event()

    def run(self):
        assert self.done.wait(10)

    def quit(self):
        self.done.set()


def fake_gst():
    made = []

    class Buffer:
        def __init__(self, data, key):
            self.data, self.key, self.pts = data, key, 5000

        def extract_dup(self, _o, _n):
            return self.data

        def get_size(self):
            return len(self.data)

        def has_flags(self, _flag):
            return not self.key

    def sample(data, key, width, height):
        caps = types.SimpleNamespace(get_int=lambda n: (True, width if n == "width" else height))
        return types.SimpleNamespace(get_buffer=lambda: Buffer(data, key), get_caps=lambda: types.SimpleNamespace(get_structure=lambda _i: caps))
    Gst = types.SimpleNamespace(
        parse_launch=lambda d: made.append(FakePipeline(d)) or made[-1], ElementFactory=types.SimpleNamespace(find=lambda _n: True),
        State=types.SimpleNamespace(PLAYING="PLAYING", NULL="NULL"), FlowReturn=types.SimpleNamespace(OK=0),
        BufferFlags=types.SimpleNamespace(DELTA_UNIT=1), CLOCK_TIME_NONE=-1, PadProbeType=types.SimpleNamespace(BUFFER=1),
        PadProbeReturn=types.SimpleNamespace(OK=0, DROP=1),
        MessageType=types.SimpleNamespace(ERROR="error", EOS="eos"), SECOND=10 ** 9)
    loop = FakeLoop()
    GLib = types.SimpleNamespace(MainLoop=lambda: loop, idle_add=lambda fn, *a: fn(*a), timeout_add=lambda *_a: 0)
    GstVideo = types.SimpleNamespace(video_event_new_upstream_force_key_unit=lambda *a: "force-key-unit")
    return Gst, GLib, GstVideo, made, sample, loop


class Lines:
    """stdin for the stream: lines the test hands over one at a time, then EOF."""

    def __init__(self):
        self.items, self.cond = [], threading.Condition()

    def put(self, line):
        with self.cond:
            self.items.append(line)
            self.cond.notify()

    def __iter__(self):
        while True:
            with self.cond:
                while not self.items:
                    self.cond.wait()
                line = self.items.pop(0)
            if line is None:
                return
            yield line


def test_run_stream_protocol_and_input_isolation():
    import io
    w = program()
    w.OUT = io.BytesIO()
    w.sys = types.SimpleNamespace(stderr=io.StringIO(), executable=sys.executable)
    Gst, GLib, GstVideo, made, sample, loop = fake_gst()
    lines = Lines()
    w.REQUESTS = lines
    applied, closed_by = [], []

    class Inputs:
        def watch_lock(self):
            return True

        def apply(self, parts):
            applied.append(parts)
            if parts[0] == "down":
                raise ValueError("bad input")

        def close(self):
            applied.append("closed")
    stream = threading.Thread(target=w.run_stream, args=(None, GLib, Gst, GstVideo, "pipewiresrc name=src ! capsfilter name=size",
                                                           "x264enc", 30, 4000, Inputs(), lambda p, c: closed_by.append(c),
                                                           (640, 360), lambda: applied.append("cleanup")))
    stream.start()
    deadline = time.monotonic() + 5
    while not made and time.monotonic() < deadline:
        time.sleep(0.01)
    pipeline = made[0]
    assert "appsink name=sink" in pipeline.description and "drop=true" in pipeline.description
    sink = pipeline.get_by_name("sink")
    while "new-sample" not in sink.handlers and time.monotonic() < deadline:
        time.sleep(0.01)
    for data, key, size in [(b"KEY1", True, (640, 360)), (b"d", False, (640, 360)), (b"KEY2", True, (720, 404))]:
        sink.sample = sample(data, key, *size)
        sink.handlers["new-sample"](sink)
    lines.put("kf\n")
    lines.put("down left 0.5 0.5\n")   # raises inside apply: the stream goes on
    lines.put("move 0.1 0.1\n")
    while len([a for a in applied if a != "closed"]) < 2 and time.monotonic() < deadline:
        time.sleep(0.01)
    lines.put(None)                    # stdin EOF ends the stream
    stream.join(5)
    assert not stream.is_alive()
    while b"KEY2" not in w.OUT.getvalue() and time.monotonic() < deadline:
        time.sleep(0.01)
    out = w.OUT.getvalue()
    assert out.startswith(b"opened 640 360 x264enc\n")
    assert b"resumed\nframe 1 0 1 640 360 5 4\nKEY1frame 2 0 0 640 360 5 1\ndgeometry 720 404\nframe 3 1 1 720 404 5 4\nKEY2" in out
    assert pipeline.get_by_name("enc").events == ["force-key-unit"]
    assert applied[:2] == [["down", "left", "0.5", "0.5"], ["move", "0.1", "0.1"]]
    assert "input error: bad input" in w.sys.stderr.getvalue()
    assert applied[-2:] == ["cleanup", "closed"] and pipeline.states[-1] == "NULL"
    # A closing reason is said once.
    w.OUT = io.BytesIO()
    closed_by[0]("window_closed")
    closed_by[0]("capture_failed")
    assert w.OUT.getvalue() == b"closed window_closed\n"


# ---------- pictures ----------

def test_kwin_picture_rows_and_formats():
    w = program()
    # 2x2 BGRA with 4 bytes of padding a row.
    data = b"aaaabbbb----ccccdddd----"
    assert w.kwin_picture_frame({"format": 5, "width": 2, "height": 2, "stride": 12}, data) == ("BGRA", 2, 2, b"aaaabbbbccccdddd")
    # HDR (QImage RGBA64): 8 bytes a pixel.
    data64 = bytes(range(32))
    assert w.kwin_picture_frame({"format": 26, "width": 2, "height": 2, "stride": 16}, data64) == ("RGBA64_LE", 2, 2, data64)
    # Unknown formats and short data: no picture (the crop path takes over).
    assert w.kwin_picture_frame({"format": 22, "width": 2, "height": 2, "stride": 8}, b"x" * 16) is None
    assert w.kwin_picture_frame({"format": 5, "width": 2, "height": 2, "stride": 8}, b"x" * 12) is None


def test_picture_reader_owns_and_closes_its_descriptor():
    w = program()
    r, wr = os.pipe()
    chunks = []
    os.write(wr, b"pixels")
    os.close(wr)
    assert w.read_until_eof(r, 2, chunks) and chunks == [b"pixels"]
    try:
        os.fstat(r)
        raise AssertionError("descriptor left open")
    except OSError:
        pass
    # A writer that never finishes: given up after the time, and closed all the same.
    r, wr = os.pipe()
    started = time.monotonic()
    assert not w.read_until_eof(r, 0.2, [])
    assert time.monotonic() - started < 2
    os.close(wr)


def test_crop_meta_reads_the_region_from_the_c_struct():
    import ctypes
    w = program()
    # A GstMeta header (flags, info pointer), then x, y, width, height.
    raw = (ctypes.c_byte * (2 * ctypes.sizeof(ctypes.c_void_p) + 16))()
    (ctypes.c_uint * 4).from_address(ctypes.addressof(raw) + 2 * ctypes.sizeof(ctypes.c_void_p))[:] = [12, 34, 640, 480]

    class Meta:
        def __repr__(self):
            return "<GstVideo.VideoCropMeta object at 0x7f00 (GstVideoCropMeta at 0x%x)>" % ctypes.addressof(raw)

    class Buffer:
        def get_meta(self, _name):
            return Meta()
    assert w.crop_meta(Buffer()) == (12, 34, 640, 480)


def test_portal_stream_by_the_window_centre():
    w = program()
    streams = [(41, {"position": (0, 0), "size": (1920, 1080)}), (42, {"position": (1920, 0), "size": (2560, 1440)})]
    # KDE's portal often gives no connector names: the window's centre picks the stream.
    assert w.pick_stream(streams, "DP-2", (2000, 100, 800, 600)) == (42, (1920, 0), (2560, 1440))
    assert w.pick_stream(streams, "DP-2", (1800, 100, 100, 100)) == (41, (0, 0), (1920, 1080))
    # Without a rect, or with streams that give no position: mapping_id, then the first.
    named = [(41, {"mapping_id": "DP-1"}), (42, {"mapping_id": "DP-2"})]
    assert w.pick_stream(named, "DP-2", (5000, 5000, 10, 10))[0] == 42
    assert w.pick_stream(named, "HDMI-1")[0] == 41
    assert w.pick_stream([], "DP-1") is None


def test_portal_environ_that_cannot_be_read_counts_as_kde():
    w = program()
    w.subprocess = types.SimpleNamespace(run=lambda *a, **k: types.SimpleNamespace(stdout="4242\n"),
                                         DEVNULL=subprocess.DEVNULL, TimeoutExpired=subprocess.TimeoutExpired)

    def denied(path, *args, **kwargs):
        raise PermissionError(path)
    w.open = denied
    assert w.portal_serves_kde()


# ---------- GNOME extension and resizing ----------

def fake_gsettings(w, values):
    calls = []

    def run(args, **kwargs):
        calls.append(args)
        if args[:2] == ["gsettings", "get"]:
            return types.SimpleNamespace(stdout=values.get(args[3], "") + "\n", returncode=0)
        if args[:2] == ["gsettings", "set"]:
            values[args[3]] = args[4].replace('"', "'")
        return types.SimpleNamespace(stdout="", returncode=0)
    w.subprocess = types.SimpleNamespace(run=run, DEVNULL=subprocess.DEVNULL, TimeoutExpired=subprocess.TimeoutExpired)
    return calls


def test_gnome_extension_leaves_the_user_extensions_switch_alone():
    w = program()
    os.environ["XDG_DATA_HOME"] = tempfile.mkdtemp()
    values = {"disable-user-extensions": "true", "enabled-extensions": "['other@example.com']"}
    calls = fake_gsettings(w, values)
    # Switched off: nothing written or changed, and the reason given.
    assert w.gnome_install_extension() == "extensions_disabled"
    assert not os.path.exists(w.gnome_extension_dir()) and not any(c[:2] == ["gsettings", "set"] for c in calls)
    w.gnome_windows_call = lambda *a: (_ for _ in ()).throw(RuntimeError("not loaded"))
    assert w.gnome_extension_state(None, None) == "disabled"
    # Switched on: written and added to the list; the switch is never set.
    values["disable-user-extensions"] = "false"
    assert w.gnome_install_extension() is None
    assert values["enabled-extensions"] == "['other@example.com', '%s']" % w.GNOME_EXTENSION
    assert not any(c[:3] == ["gsettings", "set", "org.gnome.shell"] and c[3] == "disable-user-extensions" for c in calls)
    assert w.gnome_extension_state(None, None) == "installed"
    # Uninstalled: off the list, files gone.
    assert w.gnome_uninstall_extension()
    assert values["enabled-extensions"] == "['other@example.com']" and not os.path.exists(w.gnome_extension_dir())


def test_revoke_takes_back_grants_entry_and_extension():
    w = program()
    os.environ["XDG_DATA_HOME"] = tempfile.mkdtemp()
    fake_gsettings(w, {"enabled-extensions": "@as []"})
    os.makedirs(os.path.dirname(w.desktop_file_path()))
    open(w.desktop_file_path(), "w").write("[Desktop Entry]\n")
    os.makedirs(w.gnome_extension_dir())
    deleted = []
    bus = types.SimpleNamespace(call_sync=lambda *a: deleted.append(a[3:5]))
    Gio = types.SimpleNamespace(bus_get_sync=lambda _t: bus, BusType=types.SimpleNamespace(SESSION=1))
    GLib = types.SimpleNamespace(Variant=lambda _s, v: v)
    done = w.revoke(Gio, GLib)
    assert done == {"kde_grant": True, "desktop_entry": True, "extension": True}
    assert deleted == [("DeletePermission", ("kde-authorized", "remote-desktop", w.APP_ID))]
    assert not os.path.exists(w.desktop_file_path()) and not os.path.exists(w.gnome_extension_dir())
    # Nothing left to take back: still fine.
    assert w.revoke(None, None) == {"kde_grant": False, "desktop_entry": False, "extension": False}


def test_resize_waits_for_the_size_to_settle():
    w = program()
    w.time = types.SimpleNamespace(sleep=lambda _s: None, monotonic=time.monotonic)
    readings = iter([(800, 600), (1000, 700), (1024, 768), (1024, 768), (1, 1)])
    assert w.settled_size(lambda: next(readings)) == (1024, 768)
    assert w.settled_size(lambda: None, tries=3) is None
    readings = iter([None, (640, 480), (640, 480)])
    calls = []
    w.gnome_windows_call = lambda Gio, GLib, method, args=None, rtype="(s)": calls.append(method) or "800 600"
    w.gnome_window = lambda Gio, GLib, i: (lambda r: {"width": r[0], "height": r[1]} if r else None)(next(readings))
    GLib = types.SimpleNamespace(Variant=lambda _s, v: v)
    assert w.gnome_resize(None, GLib, "5", 640, 480) == [640, 480] and calls == ["Resize"]


def test_newest_wayland_socket_is_taken():
    w = program()
    run = tempfile.mkdtemp()
    for i, name in enumerate(["wayland-0", "wayland-1", "wayland-1.lock"]):
        path = os.path.join(run, name)
        open(path, "w").close()
        os.utime(path, (1000 + i * 100, 1000 + i * 100))
    os.utime(os.path.join(run, "wayland-0"), (5000, 5000))
    os.environ.pop("WAYLAND_DISPLAY", None)
    os.environ.pop("NIRI_SOCKET", None)
    os.environ["XDG_RUNTIME_DIR"] = run
    w.session_env()
    assert os.environ["WAYLAND_DISPLAY"] == "wayland-0"


# ---------- the shell's session discovery ----------

def session_shell(tmp, sessions, systemd_env, processes, logind=True):
    """Runs `miffan env` against stub loginctl/systemctl/ps and a fake /proc."""
    root = Path(tmp)
    bin_dir = root / "bin"
    bin_dir.mkdir(exist_ok=True)
    proc = root / "proc"
    for tool in ["sh", "cat", "sed", "tr", "grep", "head", "printf", "id"]:
        found = shutil.which(tool)
        if found and not (bin_dir / tool).exists():
            (bin_dir / tool).symlink_to(found)
    uid = os.getuid()
    lines = "\n".join("%s %s %s" % (sid, state, cls) for sid, (state, cls) in sessions.items())
    (bin_dir / "loginctl").write_text("""#!/bin/sh
sessions='%s'
%s
case "$1" in
  list-sessions) exit 0 ;;
  show-user)
    [ -n "$sessions" ] || { echo "User %d is not logged in" >&2; exit 1; }
    printf '%%s\\n' "$sessions" | sed -n 's/^\\([^ ]*\\) .*/\\1/p' | tr '\\n' ' ' ;;
  show-session)
    line=$(printf '%%s\\n' "$sessions" | grep "^$2 ") || exit 1
    set -- $line
    echo "State=$2"; [ "$3" = "-" ] || echo "Class=$3" ;;
esac
""" % (lines, "" if logind else "exit 1", uid))
    (bin_dir / "systemctl").write_text("#!/bin/sh\n[ \"$2\" = show-environment ] && printf '%%s' '%s'\n" % systemd_env)
    (bin_dir / "ps").write_text("#!/bin/sh\necho '%s'\n" % " ".join(str(p) for p in processes))
    for tool in ("loginctl", "systemctl", "ps"):
        (bin_dir / tool).chmod(0o755)
    for pid, (sid, env) in processes.items():
        (proc / str(pid)).mkdir(parents=True, exist_ok=True)
        (proc / str(pid) / "sessionid").write_text(str(sid))
        (proc / str(pid) / "environ").write_bytes(b"\0".join(e.encode() for e in env) + b"\0")
    done = subprocess.run([SH, str(HELPER), "env"], capture_output=True, text=True, timeout=30,
                          env={"HOME": str(root), "PATH": str(bin_dir), "MIFFAN_PROC_ROOT": str(proc)})
    return done.stdout


def test_shell_session_env_needs_a_logged_in_session():
    systemd = "WAYLAND_DISPLAY=wayland-1\nXDG_CURRENT_DESKTOP=KDE\nHOME=/x\n"
    with tempfile.TemporaryDirectory() as tmp:
        # The user manager's environment counts while the user is logged in.
        out = session_shell(tmp, {"3": ("active", "user")}, systemd, {})
        assert "export WAYLAND_DISPLAY='wayland-1'" in out and "XDG_CURRENT_DESKTOP='KDE'" in out and "HOME" not in out
    with tempfile.TemporaryDirectory() as tmp:
        # Logged out, the manager kept the variables: not a session. A Space's Xvfb (no login session)
        # is not taken either.
        out = session_shell(tmp, {}, systemd, {501: (4294967295, ["DISPLAY=:99"])})
        assert out == ""
    with tempfile.TemporaryDirectory() as tmp:
        # systemd 256+'s class=manager session for the user manager is not someone logged in, nor a
        # greeter; a closing session is not either.
        sessions = {"7": ("active", "manager"), "8": ("online", "greeter"), "9": ("closing", "user")}
        assert session_shell(tmp, sessions, systemd, {}) == ""
        # ... but a process of a real session still gives its display.
        sessions["10"] = ("online", "user-early")
        out = session_shell(tmp, sessions, "", {600: (10, ["DISPLAY=:0", "XAUTHORITY=/a"]), 601: (7, ["DISPLAY=:1"])})
        assert "export DISPLAY=':0'" in out and "XAUTHORITY='/a'" in out
    with tempfile.TemporaryDirectory() as tmp:
        # A process in the manager's session (a service's Xvfb) is not taken.
        out = session_shell(tmp, {"7": ("active", "manager")}, "", {700: (7, ["DISPLAY=:99"])})
        assert out == ""
    with tempfile.TemporaryDirectory() as tmp:
        # Older logind without classes: the state decides.
        out = session_shell(tmp, {"4": ("active", "-")}, systemd, {})
        assert "WAYLAND_DISPLAY='wayland-1'" in out
    with tempfile.TemporaryDirectory() as tmp:
        # No logind to ask (containers): every session counts, as before.
        out = session_shell(tmp, {}, systemd, {}, logind=False)
        assert "WAYLAND_DISPLAY='wayland-1'" in out


def main():
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    for name, fn in tests:
        # Tests set NIRI_SOCKET, XDG_DATA_HOME and the like; none sees another's.
        saved = dict(os.environ)
        try:
            fn()
        finally:
            os.environ.clear()
            os.environ.update(saved)
        print("PASS:", name)


if __name__ == "__main__":
    sys.exit(main())
