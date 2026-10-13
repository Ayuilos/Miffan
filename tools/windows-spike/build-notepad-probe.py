#!/usr/bin/env python3
"""Build an isolated p6a variant for this exact, disposable Notepad experiment.

Does not change the normal probe. --input-test becomes an absolute click + VK test,
guarded by a nonce-based Session 1 foreground lease and cursor hit-test. It does not send Win
shortcuts (they address the shared shell). Coordinates require this run's 96 DPI screen.
"""
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[2];probe=root/'tools/p6a-probe';tmp=Path('/private/tmp/windows-spike-notepad-probe');tmp.mkdir(exist_ok=True)
s=(probe/'probe.c').read_text()
old='''            stats.input_forward=LiSendMouseMoveEvent(1,0);
            pause_ms(20);
            stats.input_reverse=LiSendMouseMoveEvent(-1,0);'''
new=r"""            if (system("python3 tools/windows-spike/input-lease-check.py") != 0) {result=1;error="Own Notepad foreground lease refused";break;}
            stats.input_forward=LiSendMousePositionEvent(600,500,2560,1440);
            pause_ms(150);
            if (system("python3 tools/windows-spike/input-lease-check.py --cursor-check --inspect") != 0) {result=1;error="Mouse position did not map to own Notepad";break;}
            LiSendMouseButtonEvent(BUTTON_ACTION_PRESS,BUTTON_LEFT);
            LiSendMouseButtonEvent(BUTTON_ACTION_RELEASE,BUTTON_LEFT);
            if(spike_key(0x56,MODIFIER_CTRL,"paste-at-point") ||
               spike_key(0x23,MODIFIER_CTRL,"ctrl-end") ||
               spike_key(0x56,MODIFIER_CTRL,"paste-at-end") ||
               spike_key(0x25,0,"left") ||
               spike_key(0x56,MODIFIER_CTRL,"paste-after-left") ||
               spike_key(0x27,0,"right") ||
               spike_key(0x74,0,"f5") ||
               spike_key(0x12,0,"alt") ||
               spike_key(0x1b,0,"escape")) {result=1;error="Own Notepad key delivery/lease failed";break;}
            stats.input_reverse=0;"""
helper=r"""static int spike_key(short key,char modifiers,const char *name) {
    if (system("python3 tools/windows-spike/input-lease-check.py") != 0) return -1;
    int down=LiSendKeyboardEvent(key,KEY_ACTION_DOWN,modifiers);
    int up=LiSendKeyboardEvent(key,KEY_ACTION_UP,modifiers);
    printf("notepad_key=%s vk=0x%x modifiers=%d down=%d up=%d\n",name,key,modifiers,down,up);
    pause_ms(150);
    return down || up;
}
"""

if s.count(old)!=1:raise SystemExit('Probe source changed; review variant before rebuilding')
(tmp/'probe.c').write_text(s.replace(old,new).replace('static int run_stream(',helper+'static int run_stream(',1))
includes=['-I'+str(probe),'-I'+str(probe/'build/src/common-c/src'),'-I'+str(probe/'build/src/libgamestream'),'-I/opt/homebrew/opt/openssl@3/include']
subprocess.run(['clang','-O2','-std=c11','-Wall','-Wextra','-Wno-unused-parameter','-Werror',*includes,'-c',str(tmp/'probe.c'),'-o',str(tmp/'probe.o')],check=True)
objects=[p for p in (probe/'build/obj').rglob('*.o') if p.name!='probe.o']
subprocess.run(['clang','-L/opt/homebrew/opt/openssl@3/lib','-Wl,-rpath,/opt/homebrew/opt/openssl@3/lib',str(tmp/'probe.o'),*[str(p) for p in objects],'-lssl','-lcrypto','-lcurl','-lexpat','-lpthread','-o',str(tmp/'probe')],check=True)
print(tmp/'probe')
