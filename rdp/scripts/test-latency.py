#!/usr/bin/env python3
"""Measure App SSH -> installed helper -> RDP using only the miffanrdp test account."""
import argparse
from datetime import datetime
import json
import os
import re
import statistics
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--scenarios', default='off,on', help='Round 2: baseline,event,ack,bitmap,network, in order')
parser.add_argument('--measure-seconds', type=int, default=30)
parser.add_argument('--skip-install', action='store_true', help='Rerun already installed matching APKs after a cold-start ANR')
parser.add_argument('--ssh-fingerprint', help='Already verified fingerprint, if not stored in App')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5560']
if not args.skip_install:
    for directory in ('app/build/outputs/apk/debug', 'app/build/outputs/apk/androidTest/debug'):
        artifacts = root / directory
        metadata = json.loads((artifacts / 'output-metadata.json').read_text())
        entry = next(item for item in metadata['elements'] if not item['filters'])
        subprocess.run(adb + ['install', '-r', str(artifacts / entry['outputFile'])], check=True)
out = root / 'rdp/build/test-output/ssh-latency' / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
out.mkdir(parents=True, exist_ok=True)
print('Measurement artifacts: ' + str(out), flush=True)
command = ['am', 'instrument', '-w', '-r', '-e', 'class',
           'me.ayuilos.miffan.data.repository.RdpLatencyInstrumentedTest',
           '-e', 'measure.seconds', str(args.measure_seconds), '-e', 'measure.scenarios', args.scenarios]
if args.ssh_fingerprint:
    command += ['-e', 'ssh.fingerprint', args.ssh_fingerprint]
command += ['me.ayuilos.miffan.app.debug.test/androidx.test.runner.AndroidJUnitRunner']
with (out / 'perf.txt').open('w') as perf_output:
    logcat = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', 'RemoteScreenPerf:I', '*:S'],
                              stdout=perf_output, stderr=subprocess.DEVNULL)
    try:
        result = subprocess.run(adb + ['shell', shlex.join(command)], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
    finally:
        logcat.terminate()
        try:
            logcat.wait(timeout=5)
        except subprocess.TimeoutExpired:
            logcat.kill()
            logcat.wait()
(out / 'instrumentation.txt').write_text(result.stdout)
print(result.stdout)
if result.returncode or 'FAILURES' in result.stdout or 'INSTRUMENTATION_FAILED' in result.stdout or 'OK (' not in result.stdout:
    raise SystemExit(1)
# Only pull after a successful fresh run, so a startup crash cannot expose stale reports.
for name in (f'gnome-{mode}.{ext}' for mode in args.scenarios.split(',') for ext in ('txt', 'png')):
    data = subprocess.run(adb + ['exec-out', 'run-as', 'me.ayuilos.miffan.app.debug', 'cat',
                                'files/rdp-latency/' + name], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if data.returncode:
        raise SystemExit('Passed instrumentation but missing artifact: ' + name)
    (out / name).write_bytes(data.stdout)
for name in (f'gnome-{mode}.txt' for mode in args.scenarios.split(',')):
    if 'frameDataMeanMs=' not in (out / name).read_text():
        raise SystemExit('Installed APK is stale: actual ACK queue statistics are missing')

# Derive rates from monotonic elapsed time, not from nominal one-second sleeps.
summary = {}
for mode in args.scenarios.split(','):
    rows = [dict(re.findall(r"(\w+)=([^, )]+)", line))
            for line in (out / f'gnome-{mode}.txt').read_text().splitlines()]
    first, last = rows[0], rows[-1]
    elapsed = (float(last['elapsedMs']) - float(first['elapsedMs'])) / 1000
    result = {'samples': len(rows), 'elapsedSeconds': elapsed}
    for key, label in (('frames', 'sinkFps'), ('decodedFrames', 'decodeFps'), ('bytesReceived', 'receiveBytesPerSecond')):
        result[label] = (float(last[key]) - float(first[key])) / elapsed
    result['zeroReceiveIntervals'] = sum(a['bytesReceived'] == b['bytesReceived'] for a, b in zip(rows, rows[1:]))
    for key in ('decode', 'yuvToRgb', 'sink', 'surfaceToAck', 'ackQueue', 'frameData', 'surfaceWork',
                'loopWait', 'compose', 'inputWait', 'outputWait', 'outputAccess', 'outputRelease', 'inputQueue'):
        means = [float(row[key + 'MeanMs']) for row in rows if float(row[key + 'MeanMs']) > 0]
        result[key + 'WindowMeanMedianMs'] = statistics.median(means) if means else 0
        result[key + 'MaxMs'] = max(float(row[key + 'MaxMs']) for row in rows)
    for key in ('outputWaitTimeouts', 'directBitmapFrames', 'ackBeforeSinkFrames'):
        result[key] = [int(first[key]), int(last[key])]
    summary[mode] = result
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
