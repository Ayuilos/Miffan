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


def test_kde_window_ids_must_be_uuids():
    w = program()
    assert w.UUID.match("708d5d37-b7d0-4508-a139-8c42f605b068")
    assert not w.UUID.match('708d5d37"); workspace.activeWindow = null; ("')


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
    assert meta["uuid"] == w.GNOME_EXTENSION and "51" in meta["shell-version"]
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


def main():
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    for name, fn in tests:
        fn()
        print("PASS:", name)


if __name__ == "__main__":
    sys.exit(main())
