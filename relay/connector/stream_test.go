package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

func testSelfSignedCert(t *testing.T, dnsNames ...string) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: dnsNames[0]},
		DNSNames:     dnsNames,
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv}, pool
}

func TestStreamConnFramingLimitAndDeadline(t *testing.T) {
	c2sR, c2sW := io.Pipe()
	s2cR, s2cW := io.Pipe()
	_, cancel := context.WithCancel(context.Background())
	sc := newStreamConn(cancel, c2sW, s2cR)
	defer sc.Close()

	// Write close reason > 123 UTF-8 bytes and verify valid UTF-8 truncation <= 125 payload.
	longReason := strings.Repeat("中", 50) // 150 bytes
	closeBytes := encodeStreamCloseBytes(4401, longReason)
	if len(closeBytes)-5 > 125 {
		t.Fatalf("close frame too long: %d", len(closeBytes)-5)
	}

	// Simulate server sending prelude + text + binary + close.
	go func() {
		_, _ = s2cW.Write(streamPreludeBytes[:])
		_, _ = s2cW.Write(encodeStreamFrameBytes(streamTypeText, []byte(`{"t":"hello"}`)))
		_, _ = s2cW.Write(encodeStreamFrameBytes(streamTypeBinary, []byte{1, 2, 3, 4}))
		_, _ = s2cW.Write(closeBytes)
		_ = s2cW.Close()
	}()

	kind, data, err := sc.ReadMessage()
	if err != nil || kind != websocket.TextMessage || string(data) != `{"t":"hello"}` {
		t.Fatalf("text read = (%d, %q, %v)", kind, data, err)
	}
	kind, data, err = sc.ReadMessage()
	if err != nil || kind != websocket.BinaryMessage || !bytes.Equal(data, []byte{1, 2, 3, 4}) {
		t.Fatalf("binary read = (%d, %x, %v)", kind, data, err)
	}
	_, _, err = sc.ReadMessage()
	if code, _, ok := asRelayClose(err); !ok || code != 4401 {
		t.Fatalf("expected relayCloseError 4401, got %v", err)
	}
	_ = c2sR.Close()

	// Test SetReadLimit -> 4413 frame_too_large and SetReadDeadline immediate wakeup.
	c2sR2, c2sW2 := io.Pipe()
	s2cR2, s2cW2 := io.Pipe()
	_, cancel2 := context.WithCancel(context.Background())
	sc2 := newStreamConn(cancel2, c2sW2, s2cR2)
	defer sc2.Close()
	defer c2sR2.Close()
	sc2.SetReadLimit(8)

	_ = sc2.SetReadDeadline(time.Now().Add(-time.Millisecond))
	if _, _, err := sc2.ReadMessage(); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("expected ErrDeadlineExceeded, got %v", err)
	}
	_ = sc2.SetReadDeadline(time.Time{})

	var hdr [5]byte
	hdr[0] = streamTypeBinary
	binary.BigEndian.PutUint32(hdr[1:5], 9)
	go func() {
		_, _ = s2cW2.Write(hdr[:])
	}()
	_, _, err = sc2.ReadMessage()
	if code, text, ok := asRelayClose(err); !ok || code != 4413 || text != "frame_too_large" {
		t.Fatalf("expected 4413 frame_too_large, got %v", err)
	}
}

func TestConnectorHTTP3EndToEndWhenTCPBlocked(t *testing.T) {
	cert, pool := testSelfSignedCert(t, "relay.test")
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer udpConn.Close()
	udpAddr := udpConn.LocalAddr().(*net.UDPAddr)

	// Start a local echo TCP server for session forwarding target.
	targetLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer targetLn.Close()
	go func() {
		for {
			c, err := targetLn.Accept()
			if err != nil {
				return
			}
			go func(nc net.Conn) {
				defer nc.Close()
				_, _ = io.Copy(nc, nc)
			}(c)
		}
	}()

	// Paired ECDSA P-256 device key for end-to-end session handshake.
	devPriv, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	devPub, _ := x509.MarshalPKIXPublicKey(&devPriv.PublicKey)

	paths := dataPaths{dir: t.TempDir()}
	seed := make([]byte, 32)
	_, _ = rand.Read(seed)
	id, err := newIdentity(seed)
	if err != nil {
		t.Fatal(err)
	}

	var pairOnce = make(chan string, 1)
	var dataDone = make(chan []byte, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"v":            1,
			"impl":         "bun",
			"access":       "open",
			"capabilities": map[string]any{"dgram": "none", "stream": true},
			"edges":        []string{udpAddr.String()},
		})
	})
	mux.HandleFunc("/v1/host", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != streamContentType {
			http.Error(w, `{"reason":"bad_request"}`, 400)
			return
		}
		w.Header().Set("Content-Type", streamContentType)
		w.WriteHeader(200)
		fl, _ := w.(http.Flusher)
		writeFrame := func(typ byte, body []byte) {
			_, _ = w.Write(encodeStreamFrameBytes(typ, body))
			if fl != nil {
				fl.Flush()
			}
		}
		_, _ = w.Write(streamPreludeBytes[:])
		if fl != nil {
			fl.Flush()
		}
		writeFrame(streamTypeText, []byte(`{"t":"challenge","nonce":"abc123"}`))

		_, cancel := context.WithCancel(r.Context())
		defer cancel()
		br := newStreamTestServerReader(r.Body)
		typ, payload, err := br.next()
		if err != nil || typ != streamTypeText {
			return
		}
		var auth struct{ T, Sig string }
		if json.Unmarshal(payload, &auth) != nil || auth.T != "auth" {
			return
		}
		writeFrame(streamTypeText, []byte(`{"t":"hello","hostId":"`+id.hostID+`","capabilities":{"dgram":"none","stream":true}}`))
		sid := "0123456789abcdef0123456789abcdef"
		writeFrame(streamTypeText, []byte(`{"t":"open","sid":"`+sid+`","ch":"control","pace":{"kbps":1000,"burstKb":2048}}`))
		pairOnce <- sid
		// Wait until context ends.
		for {
			_, _, err := br.next()
			if err != nil {
				return
			}
		}
	})
	mux.HandleFunc("/v1/host/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", streamContentType)
		w.WriteHeader(200)
		fl, _ := w.(http.Flusher)
		writeFrame := func(typ byte, body []byte) {
			_, _ = w.Write(encodeStreamFrameBytes(typ, body))
			if fl != nil {
				fl.Flush()
			}
		}
		_, _ = w.Write(streamPreludeBytes[:])
		if fl != nil {
			fl.Flush()
		}
		br := newStreamTestServerReader(r.Body)
		// 1. read connector challenge
		_, chRaw, err := br.next()
		if err != nil {
			return
		}
		var ch struct{ Nonce string }
		_ = json.Unmarshal(chRaw, &ch)
		msg := "miffan-relay/v1 session\n" + id.hostID + "\n0123456789abcdef0123456789abcdef\ncontrol\n" + ch.Nonce
		h := sha256.Sum256([]byte(msg))
		sig, _ := ecdsa.SignASN1(rand.Reader, devPriv, h[:])
		authJSON, _ := json.Marshal(map[string]string{
			"t":      "auth",
			"device": b64u.EncodeToString(devPub),
			"sig":    b64u.EncodeToString(sig),
		})
		writeFrame(streamTypeText, authJSON)
		_, okRaw, err := br.next()
		if err != nil || !strings.Contains(string(okRaw), `"ok"`) {
			return
		}
		writeFrame(streamTypeBinary, []byte("ping-h3-echo"))
		for {
			typ, payload, err := br.next()
			if err != nil {
				return
			}
			if typ == streamTypeBinary {
				dataDone <- payload
				writeFrame(streamTypeClose, encodeStreamCloseBytes(1000, "done")[5:])
				return
			}
		}
	})

	h3Srv := &http3.Server{
		Handler:   mux,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{http3.NextProtoH3}},
	}
	go func() { _ = h3Srv.Serve(udpConn) }()
	defer h3Srv.Close()

	relayURL := "https://relay.test:" + itoa(udpAddr.Port)
	nowStr := time.Now().UTC().Format(time.RFC3339)
	cfg := config{
		Target: targetLn.Addr().String(),
		Relays: []relayEntry{{URL: relayURL, AddedAt: nowStr}},
		Devices: []device{{
			ID:      idOf(devPub),
			Pub:     b64u.EncodeToString(devPub),
			Name:    "phone",
			AddedAt: nowStr,
			Relay:   relayURL,
		}},
	}
	if err := saveConfig(paths, &cfg); err != nil {
		t.Fatal(err)
	}

	env := netEnv{
		// Simulate ISP blocking TCP to relay while permitting loopback target TCP.
		dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if addr == targetLn.Addr().String() {
				return (&net.Dialer{}).DialContext(ctx, network, addr)
			}
			return nil, errors.New("tcp blocked")
		},
		tlsConfig: &tls.Config{RootCAs: pool},
		quic:      true,
		quicDial: func(ctx context.Context, _ string, tc *tls.Config, qc *quic.Config) (*quic.Conn, error) {
			return quic.DialAddrEarly(ctx, udpAddr.String(), tc, qc)
		},
	}

	c := newConnector(paths, id, "test", env, newLogger(io.Discard))
	c.timing.reconcile = 50 * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() {
		c.run(ctx)
		close(done)
	}()

	select {
	case got := <-dataDone:
		if string(got) != "ping-h3-echo" {
			t.Fatalf("echoed bytes = %q", got)
		}
	case <-ctx.Done():
		t.Fatal("timed out waiting for HTTP/3 session echo while TCP blocked")
	}
	cancel()
	<-done
}

func TestSessionFallsBackOnceFromFailedQUICToWS(t *testing.T) {
	var dataHits atomic.Int32
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	cert, pool := testSelfSignedCert(t, "relay.test")

	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/info":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"v":1,"impl":"bun","access":"open","capabilities":{"dgram":"none","stream":true},"edges":[]}`))
		case "/v1/host/testhostidabcdefghijklmnop/session/0123456789abcdef0123456789abcdef":
			dataHits.Add(1)
			ws, err := upgrader.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			defer ws.Close()
			_, _, _ = ws.ReadMessage() // challenge from connector
			_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(1000, "ok"), time.Now().Add(time.Second))
		}
	}))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	defer srv.Close()

	tcpAddr := srv.Listener.Addr().String()
	_, port, _ := net.SplitHostPort(tcpAddr)

	paths := dataPaths{dir: t.TempDir()}
	seed := make([]byte, 32)
	id, _ := newIdentity(seed)
	id.hostID = "testhostidabcdefghijklmnop"

	var h3Attempts atomic.Int32
	env := netEnv{
		dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, tcpAddr)
		},
		tlsConfig: &tls.Config{RootCAs: pool},
		quic:      true,
		quicDial: func(context.Context, string, *tls.Config, *quic.Config) (*quic.Conn, error) {
			h3Attempts.Add(1)
			return nil, errors.New("udp blocked")
		},
	}

	c := newConnector(paths, id, "test", env, newLogger(io.Discard))
	l := &link{c: c, url: "https://relay.test:" + port}
	c.session(context.Background(), l, "0123456789abcdef0123456789abcdef", "control", pace{Kbps: 1000, BurstKb: 1024}, []string{""}, nil, true, "", true, l.ensureH3Pool())
	l.closeH3Pool()

	if h3Attempts.Load() == 0 {
		t.Fatal("expected H3 attempt first")
	}
	if dataHits.Load() != 1 {
		t.Fatalf("expected exactly 1 WS fallback hit on /session/{sid}, got %d", dataHits.Load())
	}
}

type streamServerReader struct{ r io.Reader }

func newStreamTestServerReader(r io.Reader) *streamServerReader { return &streamServerReader{r: r} }

func (s *streamServerReader) next() (byte, []byte, error) {
	var hdr [5]byte
	for {
		if _, err := io.ReadFull(s.r, hdr[:]); err != nil {
			return 0, nil, err
		}
		typ := hdr[0]
		n := binary.BigEndian.Uint32(hdr[1:5])
		var body []byte
		if n > 0 {
			body = make([]byte, n)
			if _, err := io.ReadFull(s.r, body); err != nil {
				return 0, nil, err
			}
		}
		if typ == streamTypePrelude {
			continue
		}
		return typ, body, nil
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
