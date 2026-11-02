package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"testing"
	"time"
)

// Fixed vectors for other implementations (also listed in docs/remote-screen/RELAY_R1C_NOTES.md).
const (
	vectorHostPubHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
	vectorHostID     = "kzdvvj2umnduyauf35o36k6kw4"
	vectorSPKIHex    = "3059301306072a8648ce3d020106082a8648ce3d03010703420004b47cbd7bb4106d67a11c5cfcbc6631b0c84f3145674751846de5fb1d5aff7597be0c853ea088ea2b9d0710415a29c5c4941b98e30e9886f61c8388f7fb91c0c7"
	vectorDeviceID   = "do6p7c6wsq3x7aus7ovinattgo"
)

func TestHostIDVector(t *testing.T) {
	seed := make([]byte, 32)
	for i := range seed {
		seed[i] = byte(i)
	}
	id, err := newIdentity(seed)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(id.pub()); got != vectorHostPubHex {
		t.Fatalf("hostPub = %s", got)
	}
	if id.hostID != vectorHostID {
		t.Fatalf("hostId = %s, want %s", id.hostID, vectorHostID)
	}
	if !idPattern.MatchString(id.hostID) {
		t.Fatal("hostId does not match the protocol pattern")
	}
	der, _ := hex.DecodeString(vectorSPKIHex)
	if got := idOf(der); got != vectorDeviceID {
		t.Fatalf("deviceId = %s, want %s", got, vectorDeviceID)
	}
	if _, _, err := parseDevicePub(b64u.EncodeToString(der)); err != nil {
		t.Fatalf("vector SPKI rejected: %v", err)
	}
}

func TestHostSignatureContent(t *testing.T) {
	seed := make([]byte, 32)
	id, _ := newIdentity(seed)
	sig := id.signHost("relay.example.com", "bm9uY2U")
	raw, _ := b64u.DecodeString(sig)
	msg := []byte("miffan-relay/v1 host\nrelay.example.com\nbm9uY2U")
	if !ed25519Verify(id.pub(), msg, raw) {
		t.Fatal("host signature does not cover the PROTOCOL §4 content")
	}
	if ed25519Verify(id.pub(), []byte("miffan-relay/v1 host\nother.example.com\nbm9uY2U"), raw) {
		t.Fatal("signature must be bound to the host name")
	}
}

// testApp plays the phone: a P-256 device key.
type testApp struct {
	key *ecdsa.PrivateKey
	der []byte
}

func newTestApp(t testing.TB) *testApp {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return &testApp{k, der}
}

func (a *testApp) id() string  { return idOf(a.der) }
func (a *testApp) pub() string { return b64u.EncodeToString(a.der) }

func (a *testApp) sign(hostID, sid, ch, nonce string) string {
	digest := sha256.Sum256(sessionMessage(hostID, sid, ch, nonce))
	sig, err := ecdsa.SignASN1(rand.Reader, a.key, digest[:])
	if err != nil {
		panic(err)
	}
	return b64u.EncodeToString(sig)
}

func (a *testApp) auth(hostID, sid, ch, nonce string) authMessage {
	return authMessage{T: "auth", Device: a.pub(), Sig: a.sign(hostID, sid, ch, nonce)}
}

func (a *testApp) entry() device {
	return device{ID: a.id(), Pub: a.pub(), Name: "test phone", AddedAt: "2026-11-01T00:00:00Z"}
}

const (
	testHost  = "kzdvvj2umnduyauf35o36k6kw4"
	testSID   = "00112233445566778899aabbccddeeff"
	testNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
)

func TestVerifyAuth(t *testing.T) {
	app := newTestApp(t)
	other := newTestApp(t)
	cfg := &config{Devices: []device{app.entry()}}
	check := func(name string, msg authMessage, sid, ch, nonce, wantReason string) {
		t.Helper()
		l := newFailureLimiter()
		_, reason := verifyAuth(cfg, l, testHost, sid, ch, nonce, msg)
		if reason != wantReason {
			t.Errorf("%s: reason = %q, want %q", name, reason, wantReason)
		}
	}
	good := app.auth(testHost, testSID, "control", testNonce)
	check("valid", good, testSID, "control", testNonce, "")
	check("wrong sid", app.auth(testHost, "ffeeddccbbaa99887766554433221100", "control", testNonce), testSID, "control", testNonce, reasonBadSignature)
	check("wrong channel", app.auth(testHost, testSID, "media", testNonce), testSID, "control", testNonce, reasonBadSignature)
	check("wrong nonce", app.auth(testHost, testSID, "control", "b3RoZXI"), testSID, "control", testNonce, reasonBadSignature)
	check("wrong host", app.auth("aaaaaaaaaaaaaaaaaaaaaaaaaa", testSID, "control", testNonce), testSID, "control", testNonce, reasonBadSignature)
	check("replayed on other session", good, "ffeeddccbbaa99887766554433221100", "control", testNonce, reasonBadSignature)
	check("unknown device", other.auth(testHost, testSID, "control", testNonce), testSID, "control", testNonce, reasonUnknown)
	check("garbage signature", authMessage{T: "auth", Device: app.pub(), Sig: "AAAA"}, testSID, "control", testNonce, reasonBadSignature)
	check("empty signature", authMessage{T: "auth", Device: app.pub()}, testSID, "control", testNonce, reasonBadSignature)
	check("garbage device", authMessage{T: "auth", Device: "!!!", Sig: good.Sig}, testSID, "control", testNonce, reasonUnknown)
	check("wrong message type", authMessage{T: "hello", Device: app.pub(), Sig: good.Sig}, testSID, "control", testNonce, reasonBadSignature)
	// A signature by another key under the allowed device's name must not pass.
	forged := authMessage{T: "auth", Device: app.pub(), Sig: other.sign(testHost, testSID, "control", testNonce)}
	check("signature by another key", forged, testSID, "control", testNonce, reasonBadSignature)
}

func TestVerifyAuthRejectsNonP256(t *testing.T) {
	// Ed25519 SPKI: a valid public key, but not ECDSA P-256.
	der, _ := hex.DecodeString("302a300506032b6570032100" + vectorHostPubHex)
	if _, _, err := parseDevicePub(b64u.EncodeToString(der)); err == nil {
		t.Fatal("an Ed25519 key must not be accepted as a device key")
	}
}

func TestDeviceBackoff(t *testing.T) {
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	l := newFailureLimiter()
	l.now = func() time.Time { return now }
	app, bystander := newTestApp(t), newTestApp(t)
	cfg := &config{Devices: []device{app.entry(), bystander.entry()}}
	bad := authMessage{T: "auth", Device: app.pub(), Sig: "AAAA"}
	for i := 0; i < 5; i++ {
		if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, bad); r != reasonBadSignature {
			t.Fatalf("attempt %d: %q", i, r)
		}
		now = now.Add(5 * time.Second)
	}
	// Further bad signatures under this device's name are refused as busy for five minutes...
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, bad); r != reasonBusy {
		t.Fatalf("after five failures: %q, want busy", r)
	}
	// ...but the device itself, signing correctly, is never locked out: its public key is no secret,
	// so anyone could otherwise keep it out by failing under its name.
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, app.auth(testHost, testSID, "control", testNonce)); r != "" {
		t.Fatalf("a correct signature must pass: %q", r)
	}
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, bystander.auth(testHost, testSID, "control", testNonce)); r != "" {
		t.Fatalf("bystander refused: %q", r)
	}
	now = now.Add(4*time.Minute + 30*time.Second)
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, bad); r != reasonBusy {
		t.Fatalf("still inside the five minutes: %q", r)
	}
	now = now.Add(time.Minute)
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, bad); r != reasonBadSignature {
		t.Fatalf("after five minutes: %q", r)
	}
}

func TestDeviceFailuresOutsideWindowDoNotAccumulate(t *testing.T) {
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	l := newFailureLimiter()
	l.now = func() time.Time { return now }
	for i := 0; i < 20; i++ {
		l.fail("dev")
		now = now.Add(20 * time.Second) // three per minute at most
	}
	if !l.allow("dev") {
		t.Fatal("slow failures must not trigger the device lockout")
	}
}

func TestGlobalBackoff(t *testing.T) {
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	l := newFailureLimiter()
	l.now = func() time.Time { return now }
	app := newTestApp(t)
	cfg := &config{Devices: []device{app.entry()}}
	// Twenty failures from twenty different unknown devices: none reaches the per-device limit.
	for i := 0; i < 19; i++ {
		stranger := newTestApp(t)
		if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, stranger.auth(testHost, testSID, "control", testNonce)); r != reasonUnknown {
			t.Fatalf("stranger %d: %q", i, r)
		}
		now = now.Add(time.Second)
	}
	stranger := newTestApp(t)
	verifyAuth(cfg, l, testHost, testSID, "control", testNonce, stranger.auth(testHost, testSID, "control", testNonce))
	// The 20th failure closes the door to unknown and unparsable requests for a minute...
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, newTestApp(t).auth(testHost, testSID, "control", testNonce)); r != reasonBusy {
		t.Fatalf("unknown device after 20 failures: %q, want busy", r)
	}
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, authMessage{T: "auth", Device: "!!!"}); r != reasonBusy {
		t.Fatalf("garbage after 20 failures: %q, want busy", r)
	}
	// ...but never to a paired device that signs correctly (PROTOCOL §5.2 step 5).
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, app.auth(testHost, testSID, "control", testNonce)); r != "" {
		t.Fatalf("after 20 failures a valid device must still pass: %q", r)
	}
	// Its bad signatures are refused as busy meanwhile, like everything else that would fail.
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, authMessage{T: "auth", Device: app.pub(), Sig: "AAAA"}); r != reasonBusy {
		t.Fatalf("bad signature during the lock: %q", r)
	}
	now = now.Add(59 * time.Second)
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, newTestApp(t).auth(testHost, testSID, "control", testNonce)); r != reasonBusy {
		t.Fatalf("inside the minute: %q", r)
	}
	now = now.Add(2 * time.Second)
	if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, newTestApp(t).auth(testHost, testSID, "control", testNonce)); r != reasonUnknown {
		t.Fatalf("after the minute: %q", r)
	}
}

// An attacker who knows the hostId keeps failing faster than the lock expires; the owner still gets in.
func TestOwnerIsNeverLockedOut(t *testing.T) {
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	l := newFailureLimiter()
	l.now = func() time.Time { return now }
	app := newTestApp(t)
	cfg := &config{Devices: []device{app.entry()}}
	impostor := authMessage{T: "auth", Device: app.pub(), Sig: "AAAA"}
	for minute := 0; minute < 10; minute++ {
		for i := 0; i < 30; i++ {
			verifyAuth(cfg, l, testHost, testSID, "control", testNonce, newTestApp(t).auth(testHost, testSID, "control", testNonce))
			verifyAuth(cfg, l, testHost, testSID, "control", testNonce, impostor)
			now = now.Add(time.Second)
		}
		if _, r := verifyAuth(cfg, l, testHost, testSID, "control", testNonce, app.auth(testHost, testSID, "control", testNonce)); r != "" {
			t.Fatalf("minute %d: the owner was refused: %q", minute, r)
		}
		now = now.Add(30 * time.Second)
	}
}

func TestSuccessClearsDeviceFailures(t *testing.T) {
	l := newFailureLimiter()
	for i := 0; i < 4; i++ {
		l.fail("dev")
	}
	l.success("dev")
	l.fail("dev")
	if !l.allow("dev") {
		t.Fatal("a success must forget earlier failures")
	}
}
