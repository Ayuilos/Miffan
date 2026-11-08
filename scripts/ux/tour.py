#!/usr/bin/env python3
"""Screenshot tour of the easy-mode (IM) pages, for UX review.

Drives the installed app with adb + uiautomator (no test APK), taps through the main pages by their
visible text and saves one PNG per page. Text matching uses the Chinese UI by default; pass
--lang en for English.

    python3 -I scripts/ux/tour.py --serial emulator-5560 --package me.ayuilos.miffan.app.debug \
        --out /tmp/tour [--dark] [--font 1.3] [--only chats,partners]

A step whose target is not on screen is skipped and reported, so the tour survives missing data.
The device's dark mode and font scale are restored afterwards.
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

TEXT = {
    "zh": {
        "chats": "消息", "partners": "伙伴", "discover": "发现", "me": "我",
        "search": "搜索伙伴或消息", "history": "查看历史对话", "draw": "画图", "favorites": "收藏",
        "translate": "翻译", "appearance": "外观", "notifications": "通知", "backup": "备份",
        "service": "服务连接", "approvals": "授权记录", "computers": "我的电脑", "memory": "伙伴记得的我",
    },
    "en": {
        "chats": "Chats", "partners": "Partners", "discover": "Discover", "me": "Me",
        "search": "Search partners or messages", "history": "View earlier chats", "draw": "Draw",
        "favorites": "Favorites", "translate": "Translate", "appearance": "Appearance",
        "notifications": "Notifications", "backup": "Backup", "service": "Service connection",
        "approvals": "Permissions", "computers": "My computers", "memory": "What partners remember",
    },
}


class Device:
    def __init__(self, serial: str):
        self.serial = serial

    def adb(self, *args: str, check: bool = True) -> str:
        out = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True)
        if check and out.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {out.stderr.strip()}")
        return out.stdout

    def shell(self, cmd: str) -> str:
        return self.adb("shell", cmd)

    def nodes(self):
        for _ in range(3):
            self.shell("uiautomator dump /sdcard/tour.xml >/dev/null 2>&1 || true")
            xml = self.shell("cat /sdcard/tour.xml")
            if xml.strip().startswith("<?xml"):
                return list(ET.fromstring(xml).iter("node"))
            time.sleep(0.5)
        return []

    def find(self, text: str, contains: bool = True):
        for node in self.nodes():
            label = node.get("text") or node.get("content-desc") or ""
            if (text in label) if contains else (label == text):
                m = re.findall(r"\d+", node.get("bounds", ""))
                if len(m) == 4:
                    x1, y1, x2, y2 = map(int, m)
                    return (x1 + x2) // 2, (y1 + y2) // 2
        return None

    def tap_text(self, text: str, contains: bool = True, settle: float = 1.2) -> bool:
        at = self.find(text, contains)
        if not at:
            return False
        self.shell(f"input tap {at[0]} {at[1]}")
        time.sleep(settle)
        return True

    def back(self, settle: float = 1.0):
        self.shell("input keyevent BACK")
        time.sleep(settle)

    def size(self):
        m = re.search(r"(\d+)x(\d+)", self.shell("wm size"))
        return int(m.group(1)), int(m.group(2))

    def swipe_up(self):
        w, h = self.size()
        self.shell(f"input swipe {w // 2} {int(h * .75)} {w // 2} {int(h * .3)} 300")
        time.sleep(0.8)

    def long_press(self, x: int, y: int):
        self.shell(f"input swipe {x} {y} {x} {y} 700")
        time.sleep(1.0)

    def shot(self, path: Path):
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        path.write_bytes(data)


def tour(dev: Device, t: dict, out: Path, only: set[str] | None, package: str):
    done, skipped = [], []

    def step(name: str, ok: bool = True):
        if only and name.split("-")[0] not in only:
            return
        if ok:
            dev.shot(out / f"{len(done) + 1:02d}-{name}.png")
            done.append(name)
        else:
            skipped.append(name)

    activity = dev.shell(f"cmd package resolve-activity --brief {package}").strip().splitlines()[-1]

    def home_tab(key: str) -> bool:
        if dev.tap_text(t[key], contains=False):
            return True
        # Left the app or stuck on a page: bring the app back (it resumes where it was), then back out to the tabs.
        dev.shell(f"am start -n {activity}")
        time.sleep(2)
        for _ in range(3):
            if dev.find(t["chats"], contains=False):
                break
            dev.back()
        return dev.tap_text(t[key], contains=False)

    dev.shell(f"am force-stop {package}")
    dev.shell(f"am start -W -n {activity}")
    time.sleep(3)

    step("chats")
    step("chats-search", dev.tap_text(t["search"]))
    dev.back()
    # The first chat row: the first node below the search pill with a long text.
    w, h = dev.size()
    dev.long_press(w // 2, int(h * .26))
    step("chats-longpress")
    dev.back()
    dev.shell(f"input tap {w // 2} {int(h * .26)}")
    time.sleep(2)
    step("thread")
    if dev.tap_text(t["history"]):
        step("thread-history")
    dev.swipe_up()
    step("thread-scrolled")
    # Composer "+" menu: bottom-left button.
    dev.shell(f"input tap {int(w * .09)} {int(h * .94)}")
    time.sleep(1)
    step("thread-plus")
    dev.back(); dev.back()

    step("partners", home_tab("partners"))
    dev.shell(f"input tap {w // 2} {int(h * .30)}")
    time.sleep(1.5)
    step("partner-profile")
    dev.back()

    step("discover", home_tab("discover"))
    for key in ("draw", "favorites", "translate"):
        if dev.tap_text(t[key], contains=False):
            step(f"discover-{key}")
            dev.back()
        else:
            skipped.append(f"discover-{key}")

    step("me", home_tab("me"))
    for key in ("appearance", "notifications", "backup", "service", "approvals", "computers", "memory"):
        hit = dev.tap_text(t[key])
        if not hit:
            dev.swipe_up()
            hit = dev.tap_text(t[key])
        if hit:
            step(f"me-{key}")
            dev.back()
            home_tab("me")
        else:
            skipped.append(f"me-{key}")
    home_tab("chats")
    return done, skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default="me.ayuilos.miffan.app.debug")
    ap.add_argument("--out", required=True)
    ap.add_argument("--lang", default="zh", choices=TEXT)
    ap.add_argument("--dark", action="store_true")
    ap.add_argument("--font", type=float, default=None)
    ap.add_argument("--only", default=None, help="comma-separated page groups: chats,thread,partners,discover,me")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    dev = Device(args.serial)
    night_before = dev.shell("cmd uimode night").strip()
    font_before = dev.shell("settings get system font_scale").strip()
    try:
        dev.shell(f"cmd uimode night {'yes' if args.dark else 'no'}")
        if args.font:
            dev.shell(f"settings put system font_scale {args.font}")
        time.sleep(1)
        only = set(args.only.split(",")) if args.only else None
        done, skipped = tour(dev, TEXT[args.lang], out, only, args.package)
    finally:
        mode = "yes" if "yes" in night_before else ("auto" if "auto" in night_before else "no")
        dev.shell(f"cmd uimode night {mode}")
        if font_before and font_before != "null":
            dev.shell(f"settings put system font_scale {font_before}")
        else:
            dev.shell("settings delete system font_scale")
    print(f"saved {len(done)} screenshots to {out}")
    if skipped:
        print("skipped (not found on screen): " + ", ".join(skipped))


if __name__ == "__main__":
    sys.exit(main())
