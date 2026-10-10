#!/usr/bin/env python3
"""Offline checks: fresh disposable identities and loopback fixtures only."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
import re
import signal
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parent
BIN = ROOT / "build/p6a-probe"
OPENSSL = Path(os.environ.get("OPENSSL_DIR", "/opt/homebrew/opt/openssl@3")) / "bin/openssl"


def openssl(*args, data=None):
    return subprocess.run([str(OPENSSL), *map(str, args)], input=data,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def certificate(directory, name):
    key, cert = directory / f"{name}.key", directory / f"{name}.pem"
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", f"/CN={name}", "-keyout", key, "-out", cert)
    der = openssl("x509", "-in", cert, "-outform", "DER")
    return key, cert, der


def signature(der):
    # DER X509 sequence contains tbsCertificate, algorithm, BIT STRING signature.
    def item(pos):
        tag, n = der[pos], der[pos+1]
        start = pos+2
        if n & 128:
            size = n & 127
            n = int.from_bytes(der[start:start+size], "big")
            start += size
        return tag, start, start+n
    _, pos, _ = item(0)
    _, _, pos = item(pos)
    _, _, pos = item(pos)
    tag, start, end = item(pos)
    assert tag == 3 and der[start] == 0
    return der[start+1:end]


class Fixture:
    def __init__(self, key, cert, der):
        self.key, self.cert, self.der = key, cert, der
        self.pin = None
        self.pin_ready = threading.Event()
        self.requests = []
        self.current_game = 0
        self.launch_success = False
        self.bad_challenge = False
        self.stall = False
        self.request_ready = threading.Event()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # Never print request URLs (pairing secrets/launch keys).

            def do_GET(self):
                u = urlsplit(self.path)
                q = {k: v[0] for k, v in parse_qs(u.query).items()}
                fixture.requests.append((self.server.secure, u.path, q))
                fixture.request_ready.set()
                try:
                    if fixture.stall:
                        time.sleep(2)
                    if u.path == "/serverinfo":
                        body = ("<currentgame>%d</currentgame><PairStatus>%d</PairStatus>"
                                "<appversion>7.1.431.-1</appversion><state>SUNSHINE_SERVER_FREE</state>"
                                "<ServerCodecModeSupport>257</ServerCodecModeSupport><gputype>fixture</gputype>"
                                "<GsVersion>1</GsVersion><GfeVersion>3.23.0.74</GfeVersion>"
                                "<HttpsPort>1</HttpsPort>" % (fixture.current_game, self.server.secure))
                    elif u.path == "/applist":
                        body = "<App><ID>123</ID><AppTitle>Desktop</AppTitle></App>"
                    elif u.path in ("/launch", "/resume"):
                        if not fixture.launch_success:
                            self.respond('<root status_code="403" status_message="Offline fixture stops before streaming"/>')
                            return
                        field = "resume" if u.path == "/resume" else "gamesession"
                        body = f"<{field}>1</{field}><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0>"
                    elif u.path == "/pair":
                        body = fixture.pair(q)
                    else:
                        raise AssertionError("Destructive/unexpected route was requested")
                    self.respond('<root status_code="200">'+body+"</root>", u.path == "/applist")
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                    pass

            def respond(self, body, chunked=False):
                body = body.encode()
                self.send_response(200)
                if chunked:
                    self.send_header("Transfer-Encoding", "chunked")
                else:
                    self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if chunked:
                    self.wfile.write(f"{len(body):x}\r\n".encode()+body+b"\r\n0\r\n\r\n")
                else:
                    self.wfile.write(body)

        self.http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.http.secure = False
        self.https = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.https.secure = True
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(str(cert), str(key))
        self.https.socket = ctx.wrap_socket(self.https.socket, server_side=True)
        for server in (self.http, self.https):
            threading.Thread(target=server.serve_forever, daemon=True).start()

    def pair(self, q):
        if q.get("phrase") == "getservercert":
            assert self.pin_ready.wait(5)
            self.aes = hashlib.sha256(bytes.fromhex(q["salt"])+self.pin.encode()).digest()[:16]
            self.client_cert = bytes.fromhex(q["clientcert"]).decode()
            self.server_secret = os.urandom(16)
            self.challenge = os.urandom(16)
            return "<paired>1</paired><plaincert>"+self.cert.read_bytes().hex()+"</plaincert>"
        if "clientchallenge" in q:
            challenge = self.crypt(bytes.fromhex(q["clientchallenge"]), decrypt=True)
            hashed = hashlib.sha256(challenge+signature(self.der)+self.server_secret).digest()
            if self.bad_challenge:
                hashed = bytes([hashed[0]^1])+hashed[1:]
            response = self.crypt(hashed+self.challenge)
            return "<paired>1</paired><challengeresponse>"+response.hex()+"</challengeresponse>"
        if "serverchallengeresp" in q:
            self.client_hash = self.crypt(bytes.fromhex(q["serverchallengeresp"]), decrypt=True)
            signed = openssl("dgst", "-sha256", "-sign", self.key, data=self.server_secret)
            return "<paired>1</paired><pairingsecret>"+(self.server_secret+signed).hex()+"</pairingsecret>"
        if "clientpairingsecret" in q:
            secret = bytes.fromhex(q["clientpairingsecret"])[:16]
            client_der = ssl.PEM_cert_to_DER_cert(self.client_cert)
            assert self.client_hash == hashlib.sha256(self.challenge+signature(client_der)+secret).digest()
        return "<paired>1</paired>"

    def crypt(self, data, decrypt=False):
        args = ["enc", "-aes-128-ecb", "-nopad", "-K", self.aes.hex()]
        if decrypt:
            args.append("-d")
        return openssl(*args, data=data)

    def ports(self):
        return ["--http-port", str(self.http.server_port), "--https-port", str(self.https.server_port)]

    def close(self):
        for server in (self.http, self.https):
            server.shutdown()
            server.server_close()


def run(*args, expected=0):
    r = subprocess.run([str(BIN), *map(str, args)], capture_output=True, text=True, timeout=12)
    assert r.returncode == expected, (r.returncode, r.stdout, r.stderr)
    assert "BEGIN PRIVATE KEY" not in r.stdout+r.stderr and "rikey=" not in r.stdout+r.stderr
    return r


def pair(fixture, keydir, expected=0):
    p = subprocess.Popen([str(BIN), "pair", "--http-host", "127.0.0.1", "--keydir", str(keydir),
                          "--name", "miffan-p6a-offline", *fixture.ports()],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    output = []

    def read():
        for line in p.stdout:
            output.append(line)
            m = re.match(r"PIN=(\d{4})", line)
            if m:
                fixture.pin = m[1]
                fixture.pin_ready.set()

    reader = threading.Thread(target=read)
    reader.start()
    p.wait(timeout=12)
    reader.join(timeout=2)
    err = p.stderr.read()
    assert p.returncode == expected, ("".join(output), err)
    return "".join(output), err


def main():
    for args in [("--help",), ("pair", "--help"), ("stream", "--help")]:
        assert "p6a-probe" in run(*args).stdout
    run("stream", "--keydir", "/nonexistent-p6a-test", "--udp-host", "example.com",
        "--rtsp-tcp", "127.0.0.1:48010", expected=2)
    run("pair", "--keydir", "/nonexistent-p6a-test", "--http-host", "192.0.2.1", expected=2)
    with tempfile.TemporaryDirectory(prefix="p6a-offline-") as temporary:
        tmp = Path(temporary)
        key, cert, der = certificate(tmp, "offline-host")
        fixture = Fixture(key, cert, der)
        keydir = tmp/"identity"
        try:
            out, _ = pair(fixture, keydir)
            assert "pairing_complete=true" in out
            assert (keydir/"host-cert.sha256").read_text().strip() == hashlib.sha256(der).hexdigest()
            assert keydir.stat().st_mode & 0o777 == 0o700
            for name in ("client.pem", "key.pem", "uniqueid.dat", "host-cert.sha256"):
                assert (keydir/name).stat().st_mode & 0o777 == 0o600
            assert not (keydir/"client.p12").exists()
            print("PASS authenticated PIN pairing, exact DER pin, private identity permissions")

            def stream(*args, expected=1, **kw):
                return run("stream", "--http-host", "127.0.0.1", "--keydir", keydir,
                           "--udp-host", "127.0.0.2", "--rtsp-tcp", "127.0.0.1:48010",
                           *fixture.ports(), *args, expected=expected)

            launch_report = tmp/"launch.json"
            stream("--json", launch_report)
            assert any(path=="/launch" for _,path,_ in fixture.requests)
            assert json.loads(launch_report.read_text())["result"] == 1
            first_key = [q["rikey"] for _,p,q in fixture.requests if p=="/launch"][-1]
            fixture.current_game = 123  # Paused application, SERVER_FREE, still must resume.
            stream("--no-strict", "--codec", "h264")
            assert fixture.requests[-1][1] == "/resume"
            second_key = fixture.requests[-1][2]["rikey"]
            assert first_key != second_key and fixture.requests[-1][2]["corever"] == "1"
            print("PASS pinned HTTPS, chunked XML, launch/resume selection, fresh session keys")

            fixture.launch_success = True
            strict_report = tmp/"strict.json"
            stream("--json", strict_report)
            report = json.loads(strict_report.read_text())
            assert report["startup_error"] == -110
            assert any(e["kind"]=="failed" and e["error"]==-110 for e in report["stages"])
            print("PASS native strict rejection of plaintext RTSP scheme, stageFailed JSON")

            # No-strict proves RTSP TCP uses the relay, not numeric UDP destination.
            rtsp = socket.socket()
            rtsp.bind(("127.0.0.1", 0)); rtsp.listen(); rtsp.settimeout(5)
            seen = []

            def rtsp_reply():
                with rtsp.accept()[0] as c:
                    seen.append(c.recv(8192))
                    c.sendall(b"RTSP/1.0 403 Forbidden\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n")

            thread = threading.Thread(target=rtsp_reply)
            thread.start()
            stream("--no-strict", "--rtsp-tcp", f"127.0.0.1:{rtsp.getsockname()[1]}")
            thread.join(timeout=6); rtsp.close()
            assert seen and seen[0].startswith(b"OPTIONS ")
            print("PASS split RTSP loopback TCP endpoint with a different numeric UDP address")

            fixture.launch_success = False
            pinfile = keydir/"host-cert.sha256"
            old = pinfile.read_text()
            pinfile.write_text("00"*32+"\n")
            count = len(fixture.requests)
            r = stream()
            assert "SHA-256 mismatch" in r.stderr and len(fixture.requests)==count
            pinfile.write_text(old)
            print("PASS pin mismatch rejected before sending any HTTPS application request")

            fixture.bad_challenge = True
            _, err = pair(fixture, tmp/"bad-pin", expected=1)
            assert "PIN challenge mismatch" in err
            assert not (tmp/"bad-pin/host-cert.sha256").exists()
            assert not any(path in ("/cancel", "/unpair") for _,path,_ in fixture.requests)
            print("PASS bad pairing challenge rejects without saving pin or unpair/cancel")

            # Closed local HTTPS port: exit well below the nominal 3s connection cap.
            closed = socket.socket(); closed.bind(("127.0.0.1",0))
            port = closed.getsockname()[1]; closed.close()
            start = time.monotonic()
            r = stream("--https-port", str(port))
            assert time.monotonic()-start < 5 and "connection refused" in r.stderr
            print("PASS absent forwarding exits promptly with clear connection-refused error")

            fixture.stall = True
            fixture.request_ready.clear()
            p = subprocess.Popen([str(BIN),"stream","--keydir",str(keydir),"--udp-host","127.0.0.2",
                                  "--rtsp-tcp","127.0.0.1:48010",*fixture.ports()],
                                 stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            assert fixture.request_ready.wait(5)
            p.send_signal(signal.SIGINT)
            p.communicate(timeout=5)
            assert p.returncode == 130
            print("PASS Ctrl+C interrupts HTTP initialization without unsafe signal-handler cleanup")
        finally:
            fixture.close()
    print("All offline checks passed; no remote host, SSH, install, or Internet access.")


if __name__ == "__main__":
    main()
