package main

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// warmRelay is a TLS "relay" with one WebSocket echo endpoint; it counts TLS handshakes.
func warmRelay(t *testing.T) (*relayClient, *atomic.Int32) {
	t.Helper()
	cert := testCert(t, "relay.test")
	var handshakes atomic.Int32
	upg := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		ws, err := upg.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer ws.Close()
		for {
			k, m, err := ws.ReadMessage()
			if err != nil || ws.WriteMessage(k, m) != nil {
				return
			}
		}
	})
	addr := tlsServer(t, cert, 0, mux, &handshakes)
	c, err := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), addr))
	if err != nil {
		t.Fatal(err)
	}
	return c, &handshakes
}

func waitUntil(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func (w *tlsWarmer) ready() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.conn != nil
}

func echoOnce(t *testing.T, c *relayClient) {
	t.Helper()
	ws, err := c.dialWS(context.Background(), "", "/echo", false)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	if err := ws.WriteMessage(websocket.BinaryMessage, []byte("hi")); err != nil {
		t.Fatal(err)
	}
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, m, err := ws.ReadMessage(); err != nil || string(m) != "hi" {
		t.Fatalf("echo: %q %v", m, err)
	}
}

func TestWarmConnectionCarriesTheUpgrade(t *testing.T) {
	c, handshakes := warmRelay(t)
	w := newTLSWarmer(c, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go w.run(ctx)
	waitUntil(t, "the spare connection", w.ready)
	if handshakes.Load() != 1 {
		t.Fatalf("%d handshakes before any session", handshakes.Load())
	}

	c.warm = w
	echoOnce(t, c) // the upgrade goes over the spare: no new handshake for it
	// Taking the spare starts the next one right away.
	waitUntil(t, "a new spare", w.ready)
	if got := handshakes.Load(); got != 2 {
		t.Fatalf("%d handshakes, want 2 (the first spare, used by the session, and its replacement)", got)
	}
	echoOnce(t, c)
	waitUntil(t, "a third spare", w.ready)
	if got := handshakes.Load(); got != 3 {
		t.Fatalf("%d handshakes after two sessions, want 3", got)
	}
}

func TestWarmConnectionOnlyServesItsEdge(t *testing.T) {
	c, _ := warmRelay(t)
	w := newTLSWarmer(c, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go w.run(ctx)
	waitUntil(t, "the spare connection", w.ready)
	if w.take("104.17.5.5") != nil {
		t.Fatal("a spare for one route must not be used for another")
	}
	if !w.ready() {
		t.Fatal("a refused take must leave the spare in place")
	}
	var none *tlsWarmer
	if none.take("") != nil {
		t.Fatal("no warmer, no connection")
	}
}

func TestWarmConnectionIsReplacedWhenOldAndClosedOnStop(t *testing.T) {
	c, handshakes := warmRelay(t)
	w := newTLSWarmer(c, "")
	w.maxAge = 150 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	go w.run(ctx)
	waitUntil(t, "two replacements", func() bool { return handshakes.Load() >= 3 })
	w.mu.Lock()
	age := time.Since(w.born)
	w.mu.Unlock()
	if age > 300*time.Millisecond { // at most one replacement in flight
		t.Fatalf("the spare is %v old", age)
	}
	cancel()
	waitUntil(t, "the spare to be dropped", func() bool { return !w.ready() })
}

func TestWarmConnectionClosedByTheEdgeFallsBackToDialing(t *testing.T) {
	cert := testCert(t, "relay.test")
	var handshakes atomic.Int32
	upg := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{
		// Like an edge that drops connections on which no request arrives.
		ReadHeaderTimeout: 100 * time.Millisecond,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ws, err := upg.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			defer ws.Close()
			k, m, err := ws.ReadMessage()
			if err == nil {
				_ = ws.WriteMessage(k, m)
			}
		}),
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, GetConfigForClient: func(*tls.ClientHelloInfo) (*tls.Config, error) {
			handshakes.Add(1)
			return nil, nil
		}},
	}
	go srv.ServeTLS(ln, "", "")
	t.Cleanup(func() { srv.Close() })
	c, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), ln.Addr().String()))

	w := newTLSWarmer(c, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go w.run(ctx)
	waitUntil(t, "the spare connection", w.ready)
	time.Sleep(300 * time.Millisecond) // the server has closed the idle spare by now
	c.warm = w
	echoOnce(t, c) // the dead spare is noticed and a fresh connection dialed
	if got := handshakes.Load(); got < 2 {
		t.Fatalf("%d handshakes: the session must have dialed its own connection", got)
	}
}

func TestWarmConnectionVerifiesTheRelayCertificate(t *testing.T) {
	evil := testCert(t, "evil.test")
	impostor := tlsServer(t, evil, 0, http.NotFoundHandler(), nil)
	good := testCert(t, "relay.test")
	c, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, good), impostor))
	if _, err := c.dialTLS(context.Background(), ""); err == nil {
		t.Fatal("a spare must be refused when the certificate is not the relay's")
	}
}
