package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"math/big"
	"net"
	"net/http"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

func testCert(t testing.TB, name string) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: name},
		DNSNames: []string{name}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, BasicConstraintsValid: true, IsCA: true,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func poolOf(t testing.TB, certs ...tls.Certificate) *x509.CertPool {
	pool := x509.NewCertPool()
	for _, c := range certs {
		leaf, err := x509.ParseCertificate(c.Certificate[0])
		if err != nil {
			t.Fatal(err)
		}
		pool.AddCert(leaf)
	}
	return pool
}

// tlsServer serves handler over TLS on a loopback port; every handshake takes at least delay.
// handshakes counts completed ClientHello arrivals.
func tlsServer(t testing.TB, cert tls.Certificate, delay time.Duration, handler http.Handler, handshakes *atomic.Int32) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: handler, TLSConfig: &tls.Config{
		Certificates: []tls.Certificate{cert},
		GetConfigForClient: func(*tls.ClientHelloInfo) (*tls.Config, error) {
			if handshakes != nil {
				handshakes.Add(1)
			}
			time.Sleep(delay)
			return nil, nil
		},
	}}
	go srv.ServeTLS(ln, "", "")
	t.Cleanup(func() { srv.Close() })
	return ln.Addr().String()
}

func edgeEnv(pool *x509.CertPool, defaultAddr string) netEnv {
	d := &net.Dialer{Timeout: 3 * time.Second}
	return netEnv{
		tlsConfig: &tls.Config{RootCAs: pool},
		dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if h, _, _ := net.SplitHostPort(addr); h == "relay.test" {
				addr = defaultAddr // "DNS" for the relay's name
			}
			return d.DialContext(ctx, network, addr)
		},
	}
}

func TestRankEdgesPicksFastest(t *testing.T) {
	cert := testCert(t, "relay.test")
	evil := testCert(t, "evil.test")
	pool := poolOf(t, cert)
	h := http.NotFoundHandler()
	def := tlsServer(t, cert, 120*time.Millisecond, h, nil)
	fast := tlsServer(t, cert, 10*time.Millisecond, h, nil)
	slow := tlsServer(t, cert, 300*time.Millisecond, h, nil)
	// Fastest of all, but its certificate is not for the relay: a route must never weaken the check.
	impostor := tlsServer(t, evil, 0, h, nil)
	dead := "127.0.0.1:1"

	c, err := newRelayClient("https://relay.test:8443", "", edgeEnv(pool, def))
	if err != nil {
		t.Fatal(err)
	}
	got := c.rankEdges(context.Background(), []string{slow, impostor, dead, fast})
	want := []string{fast, "", slow}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ranking = %q, want %q", got, want)
	}
}

func TestRankEdgesAllFailFallsBackToDefault(t *testing.T) {
	cert := testCert(t, "relay.test")
	c, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), "127.0.0.1:1"))
	got := c.rankEdges(context.Background(), []string{"127.0.0.1:2", "127.0.0.1:3"})
	if !reflect.DeepEqual(got, []string{""}) {
		t.Fatalf("ranking = %q, want only the default address", got)
	}
	// With no candidates at all there is still the default address.
	if got := c.rankEdges(context.Background(), nil); !reflect.DeepEqual(got, []string{""}) {
		t.Fatalf("ranking = %q", got)
	}
}

func TestEdgeSelectorUsesInfoAndCaches(t *testing.T) {
	cert := testCert(t, "relay.test")
	pool := poolOf(t, cert)
	var fastHits, slowHits atomic.Int32
	fast := tlsServer(t, cert, 10*time.Millisecond, http.NotFoundHandler(), &fastHits)
	slow := tlsServer(t, cert, 250*time.Millisecond, http.NotFoundHandler(), &slowHits)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/info", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"v": 1, "access": "open", "edges": []string{slow, fast, "not an ip", slow}})
	})
	def := tlsServer(t, cert, 100*time.Millisecond, mux, nil)

	c, _ := newRelayClient("https://relay.test", "", edgeEnv(pool, def))
	sel := newEdgeSelector(c)
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	sel.now = func() time.Time { return now }

	first := sel.get(context.Background(), false)
	if !reflect.DeepEqual(first, []string{fast, "", slow}) {
		t.Fatalf("first selection = %q", first)
	}
	probes := fastHits.Load()
	now = now.Add(59 * time.Minute)
	if got := sel.get(context.Background(), false); !reflect.DeepEqual(got, first) || fastHits.Load() != probes {
		t.Fatal("a selection younger than an hour must be reused without probing")
	}
	if sel.get(context.Background(), true); fastHits.Load() == probes {
		t.Fatal("force must measure again")
	}
	probes = fastHits.Load()
	now = now.Add(2 * time.Hour)
	if sel.get(context.Background(), false); fastHits.Load() == probes {
		t.Fatal("a selection older than an hour must be measured again")
	}
}

func TestEdgeSelectorWithoutInfoUsesDefault(t *testing.T) {
	cert := testCert(t, "relay.test")
	def := tlsServer(t, cert, 0, http.NotFoundHandler(), nil) // /v1/info → 404
	c, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), def))
	if got := newEdgeSelector(c).get(context.Background(), false); !reflect.DeepEqual(got, []string{""}) {
		t.Fatalf("selection = %q", got)
	}
}

func TestColoHints(t *testing.T) {
	cases := map[string]string{
		"LAX": "wnam", "sjc": "wnam", "SEA": "wnam",
		"EWR": "enam", "IAD": "enam",
		"AMS": "weur", "LHR": "weur", "FRA": "weur",
		"WAW": "eeur", "HEL": "eeur",
		"SIN": "apac", "HKG": "apac", "NRT": "apac",
		"": "", "ZZZ": "",
	}
	for colo, want := range cases {
		if got := hintForColo(colo); got != want {
			t.Errorf("hintForColo(%q) = %q, want %q", colo, got, want)
		}
	}
}

func TestColoFromTrace(t *testing.T) {
	cert := testCert(t, "relay.test")
	mux := http.NewServeMux()
	mux.HandleFunc("/cdn-cgi/trace", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("fl=123\nh=relay.test\nip=1.2.3.4\ncolo=SJC\nhttp=http/2\n"))
	})
	def := tlsServer(t, cert, 0, mux, nil)
	c, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), def))
	if got := c.colo(context.Background(), ""); got != "SJC" {
		t.Fatalf("colo = %q", got)
	}
	notCF := tlsServer(t, cert, 0, http.NotFoundHandler(), nil)
	c2, _ := newRelayClient("https://relay.test", "", edgeEnv(poolOf(t, cert), notCF))
	if got := c2.colo(context.Background(), ""); got != "" {
		t.Fatalf("a relay without /cdn-cgi/trace must yield no colo, got %q", got)
	}
}

func TestParseEdges(t *testing.T) {
	got := parseEdges([]string{"104.17.5.5", " 104.19.8.8 ", "2606:4700::1", "bad", "", "104.17.5.5", "1.2.3.4:8443", "evil.example.com"})
	want := []string{"104.17.5.5", "104.19.8.8", "2606:4700::1", "1.2.3.4:8443"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseEdges = %q", got)
	}
}
