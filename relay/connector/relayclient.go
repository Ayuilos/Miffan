package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// netEnv is everything the relay client takes from the network stack, so tests can substitute it.
type netEnv struct {
	// dial opens a TCP connection; addr is always host:port.
	dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// tlsConfig is the base TLS config (root CAs); ServerName is set per connection.
	tlsConfig *tls.Config
	// proxy returns a proxy for the relay URL, or nil.
	proxy func(*http.Request) (*url.URL, error)
}

func defaultNetEnv() netEnv {
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return netEnv{dial: d.DialContext, proxy: http.ProxyFromEnvironment}
}

// relayClient talks to one relay. The edge argument everywhere is a candidate from edge selection:
// "" is the default DNS answer, otherwise an IP (or IP:port) that is dialed in place of the relay's
// host. TLS always uses the relay's host name for SNI and certificate checks (PROTOCOL §8), so an edge
// changes only the route.
type relayClient struct {
	base  *url.URL
	token string
	env   netEnv
	// warm, when set, supplies an already connected TLS connection for WebSocket dials (warm.go).
	warm *tlsWarmer
	// warmed is what the last WebSocket dial got from warm (a relayClient serves one session).
	warmed warmOutcome
}

func newRelayClient(rawURL, token string, env netEnv) (*relayClient, error) {
	clean, err := validateRelayURL(rawURL)
	if err != nil {
		return nil, err
	}
	u, _ := url.Parse(clean)
	return &relayClient{base: u, token: token, env: env}, nil
}

func (c *relayClient) hostname() string { return c.base.Hostname() }

func (c *relayClient) port() string {
	if p := c.base.Port(); p != "" {
		return p
	}
	if c.base.Scheme == "http" {
		return "80"
	}
	return "443"
}

// hostHeader is the Host header value the signature covers: lower case, with the port only when the
// URL names one.
func (c *relayClient) hostHeader() string { return strings.ToLower(c.base.Host) }

func (c *relayClient) proxyURL() *url.URL {
	if c.env.proxy == nil {
		return nil
	}
	req, _ := http.NewRequest("GET", c.base.String(), nil)
	p, err := c.env.proxy(req)
	if err != nil {
		return nil
	}
	return p
}

// dialAddr maps an edge candidate to the address to connect to.
func (c *relayClient) dialAddr(edge string) string {
	if edge == "" || edge == edgeProxy {
		return net.JoinHostPort(c.hostname(), c.port())
	}
	if addr, err := netip.ParseAddr(edge); err == nil {
		return net.JoinHostPort(addr.String(), c.port())
	}
	return edge // IP:port, already validated by parseEdges
}

func (c *relayClient) tlsConfig() *tls.Config {
	var cfg *tls.Config
	if c.env.tlsConfig != nil {
		cfg = c.env.tlsConfig.Clone()
	} else {
		cfg = &tls.Config{}
	}
	cfg.ServerName = c.hostname()
	cfg.MinVersion = tls.VersionTLS12
	return cfg
}

func (c *relayClient) wsDialer(edge string) *websocket.Dialer {
	d := &websocket.Dialer{
		HandshakeTimeout: 10 * time.Second,
		ReadBufferSize:   64 << 10,
		WriteBufferSize:  64 << 10,
		TLSClientConfig:  c.tlsConfig(),
	}
	if edge == edgeProxy {
		d.Proxy = c.env.proxy
		d.NetDialContext = c.env.dial
		return d
	}
	target := c.dialAddr(edge)
	d.NetDialContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
		return c.env.dial(ctx, network, target)
	}
	if c.warm != nil && c.base.Scheme == "https" {
		// gorilla skips its own TLS handshake when this is set; dialTLS does the same checks.
		d.NetDialTLSContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
			tc, outcome := c.warm.take(edge)
			if c.warmed.kind == "" { // the first edge tried is the one the spare was made for
				c.warmed = outcome
			}
			if tc != nil {
				return tc, nil
			}
			return c.dialTLS(ctx, edge)
		}
	}
	return d
}

func (c *relayClient) wsURL(path string) string {
	scheme := "wss"
	if c.base.Scheme == "http" {
		scheme = "ws"
	}
	return scheme + "://" + c.base.Host + path
}

// relayError is a refusal the relay sent before the WebSocket upgrade. Reason is the protocol's reason
// code, or "platform_rejected" when the body is not the relay's JSON (PROTOCOL §3.2: a Cloudflare error
// page, for example, must not be mistaken for an offline computer).
type relayError struct {
	Status     int
	Reason     string
	RetryAfter time.Duration
}

func (e *relayError) Error() string {
	return fmt.Sprintf("relay refused: HTTP %d %s", e.Status, e.Reason)
}

func parseRelayError(res *http.Response) *relayError {
	body, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
	e := &relayError{Status: res.StatusCode, Reason: "platform_rejected"}
	var parsed struct {
		Reason     string  `json:"reason"`
		RetryAfter float64 `json:"retryAfter"`
	}
	if json.Unmarshal(body, &parsed) == nil && parsed.Reason != "" && len(parsed.Reason) <= 64 {
		e.Reason = parsed.Reason
		if parsed.RetryAfter > 0 && parsed.RetryAfter < 7*24*3600 {
			e.RetryAfter = time.Duration(parsed.RetryAfter * float64(time.Second))
		}
	}
	return e
}

// dialWS opens a WebSocket on one edge. A relayError means the relay answered; any other error is a
// connection failure that may be worth retrying on another edge.
func (c *relayClient) dialWS(ctx context.Context, edge, path string, withToken bool) (*websocket.Conn, error) {
	h := http.Header{}
	if withToken && c.token != "" {
		h.Set("Authorization", "Bearer "+c.token)
	}
	conn, res, err := c.wsDialer(edge).DialContext(ctx, c.wsURL(path), h)
	if err != nil {
		if res != nil {
			defer res.Body.Close()
			return nil, parseRelayError(res)
		}
		return nil, err
	}
	return conn, nil
}

// dialWSAny tries the edges in order and stops at the first connection or the first relay answer.
func (c *relayClient) dialWSAny(ctx context.Context, edges []string, path string, withToken bool) (*websocket.Conn, string, error) {
	var lastErr error
	for _, edge := range edges {
		if ctx.Err() != nil {
			return nil, "", ctx.Err()
		}
		conn, err := c.dialWS(ctx, edge, path, withToken)
		if err == nil {
			return conn, edge, nil
		}
		var re *relayError
		if errors.As(err, &re) {
			return nil, edge, err
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("no relay address to try")
	}
	return nil, "", lastErr
}

func (c *relayClient) httpClient(edge string) *http.Client {
	tr := &http.Transport{
		TLSClientConfig:       c.tlsConfig(),
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second,
		DisableKeepAlives:     true,
	}
	if edge == edgeProxy {
		tr.Proxy = c.env.proxy
		tr.DialContext = c.env.dial
	} else {
		target := c.dialAddr(edge)
		tr.DialContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
			return c.env.dial(ctx, network, target)
		}
	}
	return &http.Client{Transport: tr, Timeout: 15 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func (c *relayClient) get(ctx context.Context, edge, path string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", c.base.String()+path, nil)
	if err != nil {
		return nil, err
	}
	res, err := c.httpClient(edge).Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	return io.ReadAll(io.LimitReader(res.Body, limit))
}

type relayInfo struct {
	Access string   `json:"access"`
	Edges  []string `json:"edges"`
}

// info fetches /v1/info. Failures are not fatal: the connector then just uses the default address.
func (c *relayClient) info(ctx context.Context) (*relayInfo, error) {
	raw, err := c.get(ctx, "", "/v1/info", 64<<10)
	if err != nil {
		return nil, err
	}
	var info relayInfo
	if err := json.Unmarshal(raw, &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// colo returns the Cloudflare data center code reported by /cdn-cgi/trace on the chosen edge, or "".
func (c *relayClient) colo(ctx context.Context, edge string) string {
	raw, err := c.get(ctx, edge, "/cdn-cgi/trace", 8<<10)
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "colo="); ok && len(v) >= 3 && len(v) <= 4 {
			return strings.ToUpper(v)
		}
	}
	return ""
}

const (
	edgeProxy      = "proxy"
	edgeTTL        = time.Hour
	edgeProbeLimit = 16
	edgeProbeTime  = 5 * time.Second
)

// parseEdges keeps the usable candidates: bare IPs, or IP:port (tests, unusual deployments).
func parseEdges(in []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, e := range in {
		e = strings.TrimSpace(e)
		if e == "" || seen[e] {
			continue
		}
		if addr, err := netip.ParseAddr(e); err == nil {
			e = addr.String()
		} else if ap, err := netip.ParseAddrPort(e); err == nil {
			e = ap.String()
		} else {
			continue
		}
		if seen[e] {
			continue
		}
		seen[e] = true
		out = append(out, e)
		if len(out) == edgeProbeLimit {
			break
		}
	}
	return out
}

// probe measures TCP connect plus TLS handshake to the relay host through one candidate.
func (c *relayClient) probe(ctx context.Context, edge string) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, edgeProbeTime)
	defer cancel()
	start := time.Now()
	conn, err := c.env.dial(ctx, "tcp", c.dialAddr(edge))
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if c.base.Scheme == "https" {
		tc := tls.Client(conn, c.tlsConfig())
		if err := tc.HandshakeContext(ctx); err != nil {
			return 0, err
		}
	}
	return time.Since(start), nil
}

// rankEdges probes the default address and every candidate in parallel and returns them fastest first.
// Candidates that failed are dropped; the default address is always kept as the last resort.
func (c *relayClient) rankEdges(ctx context.Context, candidates []string) []string {
	all := append([]string{""}, candidates...)
	type result struct {
		edge string
		d    time.Duration
		ok   bool
	}
	results := make([]result, len(all))
	var wg sync.WaitGroup
	for i, e := range all {
		wg.Add(1)
		go func(i int, e string) {
			defer wg.Done()
			d, err := c.probe(ctx, e)
			results[i] = result{e, d, err == nil}
		}(i, e)
	}
	wg.Wait()
	sort.SliceStable(results, func(a, b int) bool {
		if results[a].ok != results[b].ok {
			return results[a].ok
		}
		return results[a].d < results[b].d
	})
	var out []string
	hasDefault := false
	for _, r := range results {
		if r.ok || r.edge == "" {
			out = append(out, r.edge)
			hasDefault = hasDefault || r.edge == ""
		}
	}
	if !hasDefault {
		out = append(out, "")
	}
	return out
}

// edgeSelector caches the ranked candidate list for up to an hour.
type edgeSelector struct {
	mu      sync.Mutex
	client  *relayClient
	now     func() time.Time
	ranked  []string
	chosen  time.Time
	hasInfo bool
}

func newEdgeSelector(c *relayClient) *edgeSelector {
	return &edgeSelector{client: c, now: time.Now}
}

// get returns the edges to try, fastest first. force re-measures even inside the hour (after a
// connection failure or drop: the network may have changed).
func (s *edgeSelector) get(ctx context.Context, force bool) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !force && s.ranked != nil && s.now().Sub(s.chosen) < edgeTTL {
		return append([]string(nil), s.ranked...)
	}
	if s.client.proxyURL() != nil {
		s.ranked = []string{edgeProxy}
		s.chosen = s.now()
		return append([]string(nil), s.ranked...)
	}
	var candidates []string
	infoCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	if info, err := s.client.info(infoCtx); err == nil {
		candidates = parseEdges(info.Edges)
	}
	cancel()
	s.ranked = s.client.rankEdges(ctx, candidates)
	s.chosen = s.now()
	return append([]string(nil), s.ranked...)
}

// describeEdge is what `status` shows for the chosen edge.
func describeEdge(edge string) string {
	if edge == "" {
		return "default"
	}
	return edge
}

// coloHints maps Cloudflare data center codes to Durable Object location hints. Unknown codes yield no hint.
var coloHints = func() map[string]string {
	m := map[string]string{}
	add := func(hint string, codes string) {
		for _, c := range strings.Fields(codes) {
			m[c] = hint
		}
	}
	add("wnam", "LAX SJC SFO SEA PDX LAS PHX SLC DEN SAN SMF BOI GEG YVR YYC ABQ HNL ANC")
	add("enam", "EWR JFK IAD ATL ORD DFW IAH MIA BOS PHL CLT DTW MSP MCI STL BNA CMH CLE PIT RDU TPA OMA MEM IND BUF YYZ YUL YOW YWG")
	add("sam", "GRU GIG EZE SCL BOG LIM UIO MDE CWB POA BSB FOR CNF")
	add("weur", "LHR MAN LGW EDI DUB AMS BRU CDG MRS LYS FRA DUS HAM MUC STR TXL ZRH GVA MAD BCN LIS MXP FCO LIN")
	add("eeur", "WAW PRG VIE BUD ATH SOF OTP BEG ZAG HEL ARN CPH OSL GOT KBP RIX VNO TLL SKP LJU BTS KIV")
	add("apac", "HKG SIN NRT HND KIX ITM FUK OKA ICN PUS TPE KHH BKK KUL CGK MNL CEB DEL BOM MAA BLR HYD CCU COK HAN SGN DAD PNH RGN KTM CMB DAC ISB KHI LHE")
	add("oc", "SYD MEL BNE PER ADL CBR AKL WLG CHC NOU")
	add("afr", "JNB CPT DUR LOS NBO MBA CAI TUN ALG CMN DAR EBB")
	add("me", "DXB AUH DOH BAH KWI MCT RUH JED TLV AMM BEY IST")
	return m
}()

func hintForColo(colo string) string { return coloHints[strings.ToUpper(strings.TrimSpace(colo))] }
