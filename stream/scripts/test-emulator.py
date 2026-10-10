#!/usr/bin/env python3
"""Only emulator-5560. Pair PIN travels on stdin into private test cache, never argv/logs."""
import argparse, getpass, os, pathlib, shlex, subprocess, socket, ssl, tempfile, threading
root = pathlib.Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('mode', choices=['pair', 'pin', 'stream', 'lifecycle', 'strict'])
p.add_argument('--codec', choices=['hevc', 'h264'], default='hevc')
p.add_argument('--candidate', action='store_true')
p.add_argument('--software', action='store_true', help='Explicit emulator-only software decoder exception')
p.add_argument('--timeout', type=int, default=4000, help='Good candidate startup deadline in milliseconds')
p.add_argument('--fallback', action='store_true', help='Allow HEVC first and verify H.264 fallback (use --codec h264)')
p.add_argument('--width', type=int, default=1920)
p.add_argument('--height', type=int, default=1080)
p.add_argument('--fps', type=int, default=60)
p.add_argument('--bitrate', type=int, default=15000, help='Requested bitrate in Kbps')
a = p.parse_args()
sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or pathlib.Path.home() / 'Library/Android/sdk')
adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5560']
subprocess.run(adb + ['install', '-r', str(root / 'stream/build/outputs/apk/androidTest/debug/stream-debug-androidTest.apk')], check=True)
if a.mode == 'pair':
    pin = getpass.getpass('Pair PIN (hidden): ')
    assert len(pin) == 4 and pin.isascii() and pin.isdigit()
    command = ['run-as', 'me.rerere.stream.test', 'sh', '-c', 'umask 077; mkdir -p cache; cat > cache/stream-pin']
    subprocess.run(adb + ['shell', shlex.join(command)], input=pin, text=True, check=True)
# A Mac-only TLS fixture proves a bad pin causes zero HTTP bytes after handshake.
# It never listens on Android and uses only a freshly generated task certificate.
fixture = None; server = None; received = []; errors = []; server_thread = None
if a.mode == 'pin':
    fixture = tempfile.TemporaryDirectory(prefix='p6b-tls-')
    cert = pathlib.Path(fixture.name) / 'cert.pem'; key = pathlib.Path(fixture.name) / 'key.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=P6b Test',
        '-days', '1', '-keyout', str(key), '-out', str(cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    key.chmod(0o600)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
    server = socket.socket(); server.bind(('127.0.0.1', 0)); server.listen(1); server.settimeout(30)
    def serve():
        try:
            with server.accept()[0] as sock:
                with tls.wrap_socket(sock, server_side=True) as secure:
                    received.append(secure.recv(8192))
        except Exception as e: errors.append(type(e).__name__)
    server_thread = threading.Thread(target=serve, daemon=True); server_thread.start()
method = {'pair': 'pairNewIdentity', 'pin': 'certificatePinRejects', 'stream': 'streamAcceptance', 'lifecycle': 'cancellationReleasesProcessSlot', 'strict': 'strictEncryptionRejectsPlainRtsp'}[a.mode]
cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', 'me.rerere.stream.StreamInstrumentedTest#' + method,
    '-e', 'codec', a.codec, '-e', 'candidate', str(a.candidate).lower(), '-e', 'software', str(a.software).lower(),
    '-e', 'timeout', str(a.timeout), '-e', 'fallback', str(a.fallback).lower(),
    '-e', 'width', str(a.width), '-e', 'height', str(a.height), '-e', 'fps', str(a.fps), '-e', 'bitrate', str(a.bitrate),
    'me.rerere.stream.test/androidx.test.runner.AndroidJUnitRunner']
if server: cmd[3:3] = ['-e', 'pinServerPort', str(server.getsockname()[1])]
result = subprocess.run(adb + ['shell', shlex.join(cmd)], capture_output=True, text=True)
output = result.stdout + result.stderr
if a.mode == 'pair': output = output.replace(pin, '<redacted>')
out = root / 'stream/build/test-output'; out.mkdir(parents=True, exist_ok=True)
(out / (a.mode + '-' + a.codec + '.txt')).write_text(output)
print(output)
if server:
    server_thread.join(5); server.close(); fixture.cleanup()
    if received != [b''] or errors: raise SystemExit(f'TLS pin fixture failed: bytes={list(map(len, received))} errors={errors}')
    print('TLS fixture: handshake completed; HTTP bytes received = 0')
if result.returncode or 'OK (' not in output or 'FAILURES' in output: raise SystemExit(1)
if a.mode == 'stream':
    for suffix in ['txt', 'png']:
        subprocess.run(adb + ['pull', '/sdcard/Android/data/me.rerere.stream.test/files/p6b-' + a.codec.upper() + '.' + suffix,
            str(out / ('stats-' + a.codec + '.' + suffix))], check=True)
