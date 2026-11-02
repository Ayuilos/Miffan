package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// fakeRelay implements just enough of PROTOCOL v1 for the connector: /v1/info, /v1/host (challenge,
// auth, hello, ping/pong, open) and the session data endpoint.
type fakeRelay struct {
	t      *testing.T
	srv    *httptest.Server
	token  string
	trace  string // body of /cdn-cgi/trace, "" → 404
	upg    websocket.Upgrader
	hostID string

	mu       sync.Mutex
	control  *websocket.Conn
	cwmu     sync.Mutex
	pending  map[string]chan *websocket.Conn
	pings    int
	registry []registration
}

type registration struct {
	hostID, hint, host string
	authorization      string
}

func newFakeRelay(t *testing.T, token string) *fakeRelay {
	f := &fakeRelay{t: t, token: token, pending: map[string]chan *websocket.Conn{}, upg: websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/info", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"v": 1, "impl": "fake", "access": "open", "edges": []string{}})
	})
	mux.HandleFunc("/cdn-cgi/trace", func(w http.ResponseWriter, r *http.Request) {
		if f.trace == "" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(f.trace))
	})
	mux.HandleFunc("/v1/host", f.handleHost)
	mux.HandleFunc("/v1/host/", f.handleSession)
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeRelay) url() string { return f.srv.URL }

func (f *fakeRelay) refuse(w http.ResponseWriter, status int, body string) {
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

func (f *fakeRelay) handleHost(w http.ResponseWriter, r *http.Request) {
	if f.token != "" && r.Header.Get("Authorization") != "Bearer "+f.token {
		f.refuse(w, 401, `{"reason":"unauthorized"}`)
		return
	}
	pub, err := b64u.DecodeString(r.URL.Query().Get("pub"))
	if err != nil || len(pub) != ed25519.PublicKeySize {
		f.refuse(w, 400, `{"reason":"bad_request"}`)
		return
	}
	ws, err := f.upg.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	nonceRaw := make([]byte, 32)
	_, _ = rand.Read(nonceRaw)
	nonce := b64u.EncodeToString(nonceRaw)
	_ = ws.WriteJSON(map[string]string{"t": "challenge", "nonce": nonce})
	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	var auth struct{ T, Sig string }
	if err := ws.ReadJSON(&auth); err != nil {
		ws.Close()
		return
	}
	sig, _ := b64u.DecodeString(auth.Sig)
	if !ed25519.Verify(pub, []byte("miffan-relay/v1 host\n"+strings.ToLower(r.Host)+"\n"+nonce), sig) {
		_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(4401, "unauthorized"), time.Now().Add(time.Second))
		ws.Close()
		return
	}
	_ = ws.SetReadDeadline(time.Time{})
	hostID := idOf(pub)
	f.mu.Lock()
	old := f.control
	f.control = ws
	f.hostID = hostID
	f.registry = append(f.registry, registration{hostID, r.URL.Query().Get("hint"), strings.ToLower(r.Host), r.Header.Get("Authorization")})
	f.mu.Unlock()
	if old != nil {
		_ = old.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(4000, "replaced"), time.Now().Add(time.Second))
		old.Close()
	}
	f.cwmu.Lock()
	_ = ws.WriteJSON(map[string]any{"t": "hello", "hostId": hostID, "usage": map[string]any{"day": "2026-11-01", "budget": "ok"}})
	f.cwmu.Unlock()
	for {
		kind, data, err := ws.ReadMessage()
		if err != nil {
			f.mu.Lock()
			if f.control == ws {
				f.control = nil
			}
			f.mu.Unlock()
			return
		}
		if kind == websocket.TextMessage && string(data) == "ping" {
			f.mu.Lock()
			f.pings++
			f.mu.Unlock()
			f.cwmu.Lock()
			_ = ws.WriteMessage(websocket.TextMessage, []byte("pong"))
			f.cwmu.Unlock()
		}
	}
}

func (f *fakeRelay) handleSession(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/") // v1 host {id} session {sid}
	if len(parts) != 5 || parts[3] != "session" {
		http.NotFound(w, r)
		return
	}
	f.mu.Lock()
	ch := f.pending[parts[4]]
	f.mu.Unlock()
	if ch == nil {
		f.refuse(w, 404, `{"reason":"bad_request"}`)
		return
	}
	ws, err := f.upg.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ch <- ws
}

func (f *fakeRelay) waitRegistration(n int) registration {
	f.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		f.mu.Lock()
		if len(f.registry) >= n {
			r := f.registry[n-1]
			f.mu.Unlock()
			return r
		}
		f.mu.Unlock()
		time.Sleep(10 * time.Millisecond)
	}
	f.t.Fatalf("connector did not register %d time(s)", n)
	return registration{}
}

func (f *fakeRelay) pingCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.pings
}

// open asks the connector for a session and returns the data connection it opens (the "App side").
func (f *fakeRelay) open(chn string, p pace) (string, *websocket.Conn) {
	f.t.Helper()
	sidRaw := make([]byte, 16)
	_, _ = rand.Read(sidRaw)
	sid := hex.EncodeToString(sidRaw)
	return sid, f.openSID(sid, chn, p)
}

func (f *fakeRelay) openSID(sid, chn string, p pace) *websocket.Conn {
	f.t.Helper()
	ch := make(chan *websocket.Conn, 1)
	f.mu.Lock()
	f.pending[sid] = ch
	control := f.control
	f.mu.Unlock()
	if control == nil {
		f.t.Fatal("no control connection")
	}
	f.cwmu.Lock()
	err := control.WriteJSON(map[string]any{"t": "open", "sid": sid, "ch": chn, "pace": p})
	f.cwmu.Unlock()
	if err != nil {
		f.t.Fatal(err)
	}
	select {
	case ws := <-ch:
		return ws
	case <-time.After(10 * time.Second):
		f.t.Fatal("connector did not open the data connection")
		return nil
	}
}

func (f *fakeRelay) killControl(code int) {
	f.mu.Lock()
	c := f.control
	f.mu.Unlock()
	f.cwmu.Lock()
	_ = c.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, "test"), time.Now().Add(time.Second))
	f.cwmu.Unlock()
}

// echoServer is the "sshd": it echoes bytes and reports connection ends.
type echoServer struct {
	ln     net.Listener
	closed chan struct{} // one value per accepted connection that ended
	mu     sync.Mutex
	conns  []net.Conn
}

func newEchoServer(t *testing.T) *echoServer {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	e := &echoServer{ln: ln, closed: make(chan struct{}, 16)}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			e.mu.Lock()
			e.conns = append(e.conns, c)
			e.mu.Unlock()
			go func() {
				_, _ = io.Copy(c, c)
				c.Close()
				e.closed <- struct{}{}
			}()
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return e
}

func (e *echoServer) addr() string { return e.ln.Addr().String() }

func (e *echoServer) dropAll() {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, c := range e.conns {
		c.Close()
	}
}

type harness struct {
	t     *testing.T
	relay *fakeRelay
	echo  *echoServer
	paths dataPaths
	id    *identity
	app   *testApp
	conn  *connector
	stop  func()
	done  chan struct{}
}

func testTiming() timing {
	return timing{ping: 150 * time.Millisecond, readIdle: 5 * time.Second, handshake: 3 * time.Second, authWait: time.Second,
		backoffMin: 20 * time.Millisecond, backoffMax: 100 * time.Millisecond, backoffReset: time.Hour,
		longRetry: 150 * time.Millisecond, registerRetry: 150 * time.Millisecond, reconcile: 30 * time.Millisecond}
}

func startHarness(t *testing.T, token string, configure func(*harness)) *harness {
	t.Helper()
	h := &harness{t: t, relay: newFakeRelay(t, token), echo: newEchoServer(t), app: newTestApp(t)}
	h.paths = dataPaths{t.TempDir() + "/connector"}
	if err := ensureDataDir(h.paths); err != nil {
		t.Fatal(err)
	}
	var err error
	if h.id, _, err = ensureIdentity(h.paths); err != nil {
		t.Fatal(err)
	}
	phone := h.app.entry()
	phone.Relay = h.relay.url()
	cfg := &config{Relays: []relayEntry{{URL: h.relay.url(), AccessToken: token}}, Target: h.echo.addr(), Devices: []device{phone}}
	if err := saveConfig(h.paths, cfg); err != nil {
		t.Fatal(err)
	}
	if configure != nil {
		configure(h)
	}
	env := netEnv{dial: (&net.Dialer{Timeout: 3 * time.Second}).DialContext}
	h.conn = newConnector(h.paths, h.id, "test", env, newLogger(testWriter{t}))
	h.conn.timing = testTiming()
	ctx, cancel := contextWithCancel()
	h.done = make(chan struct{})
	go func() { h.conn.run(ctx); close(h.done) }()
	h.stop = func() {
		cancel()
		select {
		case <-h.done:
		case <-time.After(10 * time.Second):
			t.Error("connector did not stop")
		}
	}
	t.Cleanup(h.stop)
	return h
}

// state is the harness relay's entry in state.json.
func (h *harness) state() state { return loadState(h.paths).Relays[h.relay.url()] }

type testWriter struct{ t *testing.T }

func (w testWriter) Write(p []byte) (int, error) {
	if !strings.Contains(w.t.Name(), "NoLog") {
		w.t.Log(strings.TrimRight(string(p), "\n"))
	}
	return len(p), nil
}

// handshake plays the App's side up to the ok. It returns the connector's reply message type.
func (h *harness) handshake(ws *websocket.Conn, sid, ch string, mutate func(*authMessage, *string)) (string, string) {
	h.t.Helper()
	_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var challenge struct {
		T     string
		V     int
		Nonce string
	}
	if err := ws.ReadJSON(&challenge); err != nil || challenge.T != "challenge" || challenge.V != 1 {
		h.t.Fatalf("challenge: %+v %v", challenge, err)
	}
	if raw, _ := b64u.DecodeString(challenge.Nonce); len(raw) != 32 {
		h.t.Fatalf("nonce must be 32 random bytes, got %d", len(raw))
	}
	msg := h.app.auth(h.id.hostID, sid, ch, challenge.Nonce)
	if mutate != nil {
		mutate(&msg, &challenge.Nonce)
	}
	if err := ws.WriteJSON(msg); err != nil {
		h.t.Fatal(err)
	}
	var reply struct{ T, Reason string }
	if err := ws.ReadJSON(&reply); err != nil {
		return "closed:" + err.Error(), ""
	}
	return reply.T, reply.Reason
}

func expectClose(t *testing.T, ws *websocket.Conn, wantCode int) {
	t.Helper()
	_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		_, _, err := ws.ReadMessage()
		if err == nil {
			continue
		}
		var ce *websocket.CloseError
		if !asCloseError(err, &ce) {
			t.Fatalf("expected a close frame, got %v", err)
		}
		if ce.Code != wantCode {
			t.Fatalf("close code %d (%s), want %d", ce.Code, ce.Text, wantCode)
		}
		return
	}
}

func TestEndToEndForwardAndClosePropagation(t *testing.T) {
	h := startHarness(t, "", nil)
	reg := h.relay.waitRegistration(1)
	if reg.hostID != h.id.hostID {
		t.Fatalf("registered as %s, want %s", reg.hostID, h.id.hostID)
	}
	if !strings.HasPrefix(reg.host, "127.0.0.1:") {
		t.Fatalf("signed host = %q", reg.host)
	}
	waitFor(t, "connected state", func() bool {
		st := h.state()
		return st.Connected && st.Registered && st.LastConnectedAt != nil && st.LastError == nil
	})

	sid, ws := h.relay.open("control", pace{Kbps: 1000, BurstKb: 2048})
	defer ws.Close()
	if reply, _ := h.handshake(ws, sid, "control", nil); reply != "ok" {
		t.Fatalf("handshake reply = %q", reply)
	}
	// Bytes travel both ways as binary frames.
	for _, msg := range []string{"SSH-2.0-test\r\n", "x", strings.Repeat("y", 1000)} {
		if err := ws.WriteMessage(websocket.BinaryMessage, []byte(msg)); err != nil {
			t.Fatal(err)
		}
		var got []byte
		_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
		for len(got) < len(msg) {
			kind, data, err := ws.ReadMessage()
			if err != nil {
				t.Fatal(err)
			}
			if kind == websocket.BinaryMessage {
				got = append(got, data...)
			}
		}
		if string(got) != msg {
			t.Fatalf("echo mismatch: %q", got)
		}
	}

	// App closes → the local connection ends.
	_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(1000, "peer_closed"), time.Now().Add(time.Second))
	select {
	case <-h.echo.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("closing the relay side did not close the local connection")
	}

	// Local side closes → the relay side gets a close.
	sid2, ws2 := h.relay.open("media", pace{Kbps: 4000, BurstKb: 4096})
	defer ws2.Close()
	if reply, _ := h.handshake(ws2, sid2, "media", nil); reply != "ok" {
		t.Fatalf("second handshake reply = %q", reply)
	}
	_ = ws2.WriteMessage(websocket.BinaryMessage, []byte("ping-pong"))
	_ = ws2.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, _, err := ws2.ReadMessage(); err != nil {
		t.Fatal(err)
	}
	h.echo.dropAll()
	expectClose(t, ws2, 1000)
}

func TestEndToEndLargeTransferFrameSizeAndOrder(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	sid, ws := h.relay.open("control", pace{Kbps: 100000, BurstKb: 8192})
	defer ws.Close()
	if reply, _ := h.handshake(ws, sid, "control", nil); reply != "ok" {
		t.Fatalf("reply %q", reply)
	}
	payload := make([]byte, 1<<20)
	_, _ = rand.Read(payload)
	go func() {
		for off := 0; off < len(payload); off += 60 << 10 {
			end := off + 60<<10
			if end > len(payload) {
				end = len(payload)
			}
			_ = ws.WriteMessage(websocket.BinaryMessage, payload[off:end])
		}
	}()
	var got bytes.Buffer
	frames, maxFrame := 0, 0
	_ = ws.SetReadDeadline(time.Now().Add(20 * time.Second))
	for got.Len() < len(payload) {
		kind, data, err := ws.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		if kind != websocket.BinaryMessage {
			continue
		}
		frames++
		if len(data) > maxFrame {
			maxFrame = len(data)
		}
		got.Write(data)
	}
	if !bytes.Equal(got.Bytes(), payload) {
		t.Fatal("payload corrupted in transit")
	}
	if maxFrame > maxBinaryFrame {
		t.Fatalf("a frame of %d bytes exceeds 64 KiB", maxFrame)
	}
	t.Logf("%d bytes in %d frames, largest %d", got.Len(), frames, maxFrame)
}

func TestEndToEndRefusals(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	stranger := newTestApp(t)

	// Wrong signature.
	sid, ws := h.relay.open("control", pace{})
	reply, reason := h.handshake(ws, sid, "control", func(m *authMessage, _ *string) { m.Sig = h.app.sign(h.id.hostID, sid, "media", "other") })
	if reply != "denied" || reason != "bad_signature" {
		t.Fatalf("wrong signature: %s/%s", reply, reason)
	}
	expectClose(t, ws, 4403)
	ws.Close()

	// Unknown device.
	sid, ws = h.relay.open("control", pace{})
	reply, reason = h.handshake(ws, sid, "control", func(m *authMessage, nonce *string) { *m = stranger.auth(h.id.hostID, sid, "control", *nonce) })
	if reply != "denied" || reason != "unknown_device" {
		t.Fatalf("unknown device: %s/%s", reply, reason)
	}
	expectClose(t, ws, 4403)
	ws.Close()

	// Binary data before ok.
	sid, ws = h.relay.open("control", pace{})
	_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var ch map[string]any
	_ = ws.ReadJSON(&ch)
	_ = ws.WriteMessage(websocket.BinaryMessage, []byte("SSH-2.0-eager"))
	expectClose(t, ws, 4403)
	ws.Close()
	_ = sid

	// Nothing reached the local target.
	select {
	case <-h.echo.closed:
		t.Fatal("a refused session must never connect to the local target")
	case <-time.After(200 * time.Millisecond):
	}
	h.echo.mu.Lock()
	n := len(h.echo.conns)
	h.echo.mu.Unlock()
	if n != 0 {
		t.Fatalf("%d connections reached the target", n)
	}
}

func TestEndToEndSilentAppIsCutAfterAuthWait(t *testing.T) {
	h := startHarness(t, "", nil) // authWait: 1 s
	h.relay.waitRegistration(1)
	_, ws := h.relay.open("control", pace{})
	_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var ch map[string]any
	if err := ws.ReadJSON(&ch); err != nil || ch["t"] != "challenge" {
		t.Fatalf("challenge: %v %v", ch, err)
	}
	start := time.Now()
	_, _, err := ws.ReadMessage() // never answer
	if err == nil {
		t.Fatal("expected the connector to give up")
	}
	if d := time.Since(start); d < 800*time.Millisecond || d > 3*time.Second {
		t.Fatalf("gave up after %v, want about the auth wait", d)
	}
	ws.Close()
	if defaultTiming().authWait != 5*time.Second {
		t.Fatalf("default auth wait is %v", defaultTiming().authWait)
	}
}

func TestEndToEndPairUnpairTakeEffectWhileRunning(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	newcomer := newTestApp(t)

	try := func(app *testApp) (string, string) {
		sid, ws := h.relay.open("control", pace{})
		defer ws.Close()
		_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
		var ch struct{ Nonce string }
		_ = ws.ReadJSON(&ch)
		_ = ws.WriteJSON(app.auth(h.id.hostID, sid, "control", ch.Nonce))
		var reply struct{ T, Reason string }
		if err := ws.ReadJSON(&reply); err != nil {
			t.Fatal(err)
		}
		return reply.T, reply.Reason
	}
	if r, why := try(newcomer); r != "denied" || why != "unknown_device" {
		t.Fatalf("before pair: %s/%s", r, why)
	}
	cfg, _ := loadConfig(h.paths)
	entry := newcomer.entry()
	entry.Relay = h.relay.url()
	cfg.Devices = append(cfg.Devices, entry)
	if err := saveConfig(h.paths, cfg); err != nil {
		t.Fatal(err)
	}
	if r, _ := try(newcomer); r != "ok" {
		t.Fatalf("after pair: %s", r)
	}
	cfg, _ = loadConfig(h.paths)
	cfg.Devices = cfg.Devices[:1]
	if err := saveConfig(h.paths, cfg); err != nil {
		t.Fatal(err)
	}
	if r, why := try(newcomer); r != "denied" || why != "unknown_device" {
		t.Fatalf("after unpair: %s/%s", r, why)
	}
}

func TestEndToEndRejectsMalformedOpen(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	h.relay.mu.Lock()
	control := h.relay.control
	h.relay.mu.Unlock()
	for _, m := range []map[string]any{
		{"t": "open", "sid": "../../admin", "ch": "control"},
		{"t": "open", "sid": strings.Repeat("g", 32), "ch": "control"},
		{"t": "open", "sid": "00112233445566778899aabbccddeeff", "ch": "weird"},
		{"t": "open", "sid": "00112233445566778899aabbccddeeff", "ch": "control", "target": "10.0.0.1:22"},
		{"t": "mystery"},
	} {
		h.relay.cwmu.Lock()
		_ = control.WriteJSON(m)
		h.relay.cwmu.Unlock()
	}
	time.Sleep(300 * time.Millisecond)
	// The connector is still up and serves a proper session afterwards.
	sid, ws := h.relay.open("control", pace{})
	defer ws.Close()
	if reply, _ := h.handshake(ws, sid, "control", nil); reply != "ok" {
		t.Fatalf("reply %q", reply)
	}
}

func TestEndToEndRegistrationHintOnlyOnce(t *testing.T) {
	h := startHarness(t, "", func(h *harness) { h.relay.trace = "fl=1\ncolo=LAX\nhttp=http/2\n" })
	first := h.relay.waitRegistration(1)
	if first.hint != "wnam" {
		t.Fatalf("first registration hint = %q, want wnam (LAX)", first.hint)
	}
	// The relay replaces the control connection: the connector reconnects, without a hint now.
	h.relay.killControl(4000)
	second := h.relay.waitRegistration(2)
	if second.hint != "" {
		t.Fatalf("second registration carried hint %q", second.hint)
	}
	if second.hostID != first.hostID {
		t.Fatal("host id changed")
	}
}

func TestEndToEndNoHintWhenColoUnknown(t *testing.T) {
	h := startHarness(t, "", nil) // /cdn-cgi/trace → 404
	if reg := h.relay.waitRegistration(1); reg.hint != "" {
		t.Fatalf("hint = %q", reg.hint)
	}
}

func TestEndToEndSendsTextPingOnly(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	waitFor(t, "ping on the control connection", func() bool { return h.relay.pingCount() >= 2 })
}

func TestEndToEndAccessToken(t *testing.T) {
	h := startHarness(t, "s3cret", nil)
	reg := h.relay.waitRegistration(1)
	if reg.authorization != "Bearer s3cret" {
		t.Fatalf("authorization = %q", reg.authorization)
	}
	// The token reaches only the relay's host endpoint; the data connection is authorised by the sid.
	sid, ws := h.relay.open("control", pace{})
	defer ws.Close()
	if reply, _ := h.handshake(ws, sid, "control", nil); reply != "ok" {
		t.Fatalf("reply %q", reply)
	}
}

func TestEndToEndWrongTokenAndPlatformRefusal(t *testing.T) {
	h := startHarness(t, "right", func(h *harness) {
		cfg, _ := loadConfig(h.paths)
		cfg.Relays[0].AccessToken = "wrong"
		_ = saveConfig(h.paths, cfg)
	})
	waitFor(t, "unauthorized in state.json", func() bool {
		st := h.state()
		return !st.Connected && st.LastError != nil && *st.LastError == "unauthorized"
	})

	// A non-JSON error page (Cloudflare 1027 and friends) is a platform refusal, not an offline computer.
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(429)
		_, _ = w.Write([]byte("<html>error code: 1027</html>"))
	}))
	defer ts.Close()
	h.stop()
	cfg, _ := loadConfig(h.paths)
	cfg.Relays = []relayEntry{{URL: ts.URL}}
	cfg.Devices[0].Relay = ts.URL
	_ = saveConfig(h.paths, cfg)
	c2 := newConnector(h.paths, h.id, "test", netEnv{dial: (&net.Dialer{}).DialContext}, newLogger(io.Discard))
	c2.timing = testTiming()
	ctx, cancel := contextWithCancel()
	done := make(chan struct{})
	go func() { c2.run(ctx); close(done) }()
	waitFor(t, "platform_rejected in state.json", func() bool {
		st := loadState(h.paths).Relays[ts.URL]
		return st.LastError != nil && *st.LastError == "platform_rejected"
	})
	cancel()
	<-done
}

func TestEndToEndReconnectsAfterRelayDrop(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	h.relay.killControl(1012)
	h.relay.waitRegistration(2)
	waitFor(t, "connected again", func() bool { return h.state().Connected })
	sid, ws := h.relay.open("control", pace{})
	defer ws.Close()
	if reply, _ := h.handshake(ws, sid, "control", nil); reply != "ok" {
		t.Fatalf("reply %q", reply)
	}
}

func TestEndToEndTargetComesOnlyFromConfig(t *testing.T) {
	// A config pointing off the machine is refused outright: the connector would not even connect.
	h := startHarness(t, "", func(h *harness) {
		cfg, _ := loadConfig(h.paths)
		cfg.Target = "192.0.2.1:22"
		_ = saveConfig(h.paths, cfg)
	})
	waitFor(t, "bad_config", func() bool {
		st := h.state()
		return st.LastError != nil && *st.LastError == "bad_config"
	})
	h.relay.mu.Lock()
	n := len(h.relay.registry)
	h.relay.mu.Unlock()
	if n != 0 {
		t.Fatal("connector registered with a non-loopback target configured")
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// One computer, two phones on two relays: both reach it at the same time, a third relay is joined as
// soon as a phone starts using it, and a relay no phone uses any more is left.
func TestEndToEndServesEveryRelayInUse(t *testing.T) {
	h := startHarness(t, "", nil)
	h.relay.waitRegistration(1)
	second := newFakeRelay(t, "code")
	other := newTestApp(t)

	cfg, _ := loadConfig(h.paths)
	cfg.upsertRelay(second.url(), func() *string { s := "code"; return &s }(), time.Now())
	entry := other.entry()
	entry.Relay = second.url()
	cfg.Devices = append(cfg.Devices, entry)
	if err := saveConfig(h.paths, cfg); err != nil {
		t.Fatal(err)
	}
	reg := second.waitRegistration(1)
	if reg.hostID != h.id.hostID || reg.authorization != "Bearer code" {
		t.Fatalf("second relay registration = %+v", reg)
	}
	waitFor(t, "both relays connected", func() bool {
		st := loadState(h.paths)
		return st.Relays[h.relay.url()].Connected && st.Relays[second.url()].Connected
	})

	// Sessions run on both relays at once, each forwarding to the computer.
	sidA, wsA := h.relay.open("control", pace{})
	defer wsA.Close()
	if reply, _ := h.handshake(wsA, sidA, "control", nil); reply != "ok" {
		t.Fatalf("first relay: %q", reply)
	}
	sidB, wsB := second.open("control", pace{})
	defer wsB.Close()
	_ = wsB.SetReadDeadline(time.Now().Add(5 * time.Second))
	var ch struct{ Nonce string }
	if err := wsB.ReadJSON(&ch); err != nil {
		t.Fatal(err)
	}
	_ = wsB.WriteJSON(other.auth(h.id.hostID, sidB, "control", ch.Nonce))
	var reply struct{ T string }
	if err := wsB.ReadJSON(&reply); err != nil || reply.T != "ok" {
		t.Fatalf("second relay: %v %v", reply, err)
	}
	for _, ws := range []*websocket.Conn{wsA, wsB} {
		if err := ws.WriteMessage(websocket.BinaryMessage, []byte("hello")); err != nil {
			t.Fatal(err)
		}
		_, got, err := ws.ReadMessage()
		if err != nil || string(got) != "hello" {
			t.Fatalf("echo through relay: %q %v", got, err)
		}
	}

	// The second phone is forgotten: the connector leaves its relay and drops its state.
	cfg, _ = loadConfig(h.paths)
	cfg.Devices = cfg.Devices[:1]
	cfg.Relays[1].AddedAt = ""
	cfg.prune(time.Now())
	if err := saveConfig(h.paths, cfg); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "second relay left", func() bool {
		second.mu.Lock()
		defer second.mu.Unlock()
		return second.control == nil
	})
	waitFor(t, "second relay state dropped", func() bool {
		_, ok := loadState(h.paths).Relays[second.url()]
		return !ok
	})
	if !h.state().Connected {
		t.Fatal("the first relay must stay connected")
	}
}
