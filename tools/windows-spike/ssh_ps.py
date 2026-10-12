#!/usr/bin/env python3
"""Run a non-secret PowerShell script through Windows OpenSSH's cmd shell.

Scripts are UTF-16LE EncodedCommand; stdin stays available to the script.
Never use this runner for passwords, PINs or pairing/session keys.
"""
import argparse
import base64
from pathlib import Path
import subprocess


def command(script):
    prefix = "$ProgressPreference = 'SilentlyContinue'; [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); $OutputEncoding = [Console]::OutputEncoding; "
    encoded = base64.b64encode((prefix + script).encode("utf-16le")).decode("ascii")
    return "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand " + encoded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("script", type=Path)
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()
    try:
        result = subprocess.run(
            ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
             "-i", str(Path.home() / ".ssh/id_ed25519_new"), "ekk@192.168.31.61",
             command(args.script.read_text())], timeout=args.timeout)
    except subprocess.TimeoutExpired:
        parser.exit(124, "SSH timed out; remote work may continue. Inspect before retrying.\n")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
