#!/usr/bin/env python3
"""Build an isolated p6a variant for this exact, disposable Notepad experiment.

Does not change the normal probe. --input-test becomes an absolute click + VK test,
guarded before each event by a fresh Session 1 foreground lease. It does not send Win
shortcuts (they address the shared shell). Coordinates require this run's 96 DPI screen.
"""
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[2];probe=root/'tools/p6a-probe';tmp=Path('/private/tmp/windows-spike-notepad-probe');tmp.mkdir(exist_ok=True)
s=(probe/'probe.c').read_text()
old='''            stats.input_forward=LiSendMouseMoveEvent(1,0);
            pause_ms(20);
            stats.input_reverse=LiSendMouseMoveEvent(-1,0);'''
new='''            if (system("python3 tools/windows-spike/input-lease-check.py") != 0) {result=1;error="Own Notepad foreground lease refused";break;}
            stats.input_forward=LiSendMousePositionEvent(600,500,2560,1440);
            pause_ms(80);
            if (system("python3 tools/windows-spike/input-lease-check.py") != 0) {result=1;error="Own Notepad foreground lease lost";break;}
            LiSendMouseButtonEvent(BUTTON_ACTION_PRESS,BUTTON_LEFT);
            LiSendMouseButtonEvent(BUTTON_ACTION_RELEASE,BUTTON_LEFT);
            const short keys[]={0x23,0x58,0x25,0x27,0x74,0x12};
            for(unsigned int k=0;k<sizeof(keys)/sizeof(keys[0]);++k) {
                if (system("python3 tools/windows-spike/input-lease-check.py") != 0) {result=1;error="Own Notepad foreground lease lost";break;}
                char modifiers=k==0?MODIFIER_CTRL:0;
                int down=LiSendKeyboardEvent(keys[k],KEY_ACTION_DOWN,modifiers);
                int up=LiSendKeyboardEvent(keys[k],KEY_ACTION_UP,modifiers);
                printf("notepad_vk=0x%x down=%d up=%d\\n",keys[k],down,up);
                pause_ms(80);
            }
            stats.input_reverse=result?-1:0;
            if(result)break;'''
if s.count(old)!=1:raise SystemExit('Probe source changed; review variant before rebuilding')
(tmp/'probe.c').write_text(s.replace(old,new))
includes=['-I'+str(probe),'-I'+str(probe/'build/src/common-c/src'),'-I'+str(probe/'build/src/libgamestream'),'-I/opt/homebrew/opt/openssl@3/include']
subprocess.run(['clang','-O2','-std=c11','-Wall','-Wextra','-Wno-unused-parameter','-Werror',*includes,'-c',str(tmp/'probe.c'),'-o',str(tmp/'probe.o')],check=True)
objects=[p for p in (probe/'build/obj').rglob('*.o') if p.name!='probe.o']
subprocess.run(['clang','-L/opt/homebrew/opt/openssl@3/lib','-Wl,-rpath,/opt/homebrew/opt/openssl@3/lib',str(tmp/'probe.o'),*[str(p) for p in objects],'-lssl','-lcrypto','-lcurl','-lexpat','-lpthread','-o',str(tmp/'probe')],check=True)
print(tmp/'probe')
