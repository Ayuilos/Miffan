package main

import (
	"bytes"
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
	"github.com/quic-go/quic-go"
)

// netEnv is everything the relay client takes from the network stack, so tests can substitute it.
type netEnv struct {
	// dial opens a TCP connection; addr is always host:port.
	dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// tlsConfig is the base TLS config (root CAs); ServerName is set per connection.
	tlsConfig *tls.Config
	// proxy returns a proxy for the relay URL, or nil.
	proxy func(*http.Request) (*url.URL, error)
	// quic enables HTTP/3 (QUIC over UDP) alongside WebSocket when talking to https relays.
	quic bool
	// quicDial overrides QUIC dialing in tests; nil uses quic.DialAddrEarly.
	quicDial func(ctx context.Context, addr string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error)
	// sunshineUDPTargets overrides the 127.0.0.1 Sunshine UDP flow targets (0x01/0x02/0x03) in tests.
	sunshineUDPTargets map[byte]string
}

func defaultNetEnv() netEnv {
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return netEnv{dial: d.DialContext, proxy: http.ProxyFromEnvironment, quic: true}
}

// relayClient talks to one relay. The edge argument everywhere is a candidate from edge selection:
// "" is the default DNS answer, otherwise an IP (or IP:port) that is dialed in place of the relay's
// host. TLS always uses the relay's host name for SNI and certificate checks (PROTOCOL §8), so an edge
// changes only the route.
type relayClient struct {
	base  *url.URL
	token string
	env   netEnv
	h3    *h3Pool
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

func (c *relayClient) h3Enabled() bool {
	return c.env.quic && c.base.Scheme == "https" && c.proxyURL() == nil
}

func (c *relayClient) pool() *h3Pool {
	if c.h3 == nil {
		c.h3 = newH3Pool()
	}
	return c.h3
}

func (c *relayClient) dnsEdgeCandidates(ctx context.Context) []string {
	host := c.hostname()
	if _, err := netip.ParseAddr(host); err == nil {
		return nil
	}
	rCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	addrs, err := (&net.Resolver{}).LookupIPAddr(rCtx, host)
	if err != nil {
		return nil
	}
	var v4, v6 []string
	seen := map[string]bool{}
	for _, a := range addrs {
		s := a.IP.String()
		if seen[s] {
			continue
		}
		seen[s] = true
		if a.IP.To4() != nil {
			v4 = append(v4, s)
		} else {
			v6 = append(v6, s)
		}
	}
	var out []string
	for i := 0; len(out) < 4 && (i < len(v4) || i < len(v6)); i++ {
		if i < len(v4) {
			out = append(out, v4[i])
		}
		if i < len(v6) && len(out) < 4 {
			out = append(out, v6[i])
		}
	}
	return out
}

func (c *relayClient) dialStream(streamCtx, dialCtx context.Context, edge, path string, withToken bool) (*streamConn, error) {
	reqCtx, cancelReq := context.WithCancel(streamCtx)
	pr, pw := io.Pipe()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, c.base.String()+path, &streamBodyReader{
		r: io.MultiReader(bytes.NewReader(streamPreludeBytes[:]), pr),
		c: pr,
	})
	if err != nil {
		_ = pw.Close()
		_ = pr.Close()
		cancelReq()
		return nil, err
	}
	req.Header.Set("Content-Type", streamContentType)
	if withToken && c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	stopWatch := context.AfterFunc(dialCtx, cancelReq)
	h3c := &http.Client{
		Transport:     c.pool().transportFor(c, edge),
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	res, err := h3c.Do(req)
	notTimedOut := stopWatch()
	if err != nil {
		_ = pw.Close()
		_ = pr.Close()
		cancelReq()
		if !notTimedOut && dialCtx.Err() != nil {
			return nil, dialCtx.Err()
		}
		return nil, err
	}
	if !notTimedOut && dialCtx.Err() != nil {
		_ = res.Body.Close()
		_ = pw.Close()
		_ = pr.Close()
		cancelReq()
		return nil, dialCtx.Err()
	}
	if res.StatusCode != http.StatusOK {
		re := parseRelayError(res)
		_ = res.Body.Close()
		_ = pw.Close()
		_ = pr.Close()
		cancelReq()
		return nil, re
	}
	return newStreamConn(cancelReq, pw, res.Body), nil
}

func (c *relayClient) dialStreamAny(streamCtx, dialCtx context.Context, edges []string, path string, withToken bool) (relayConn, string, error) {
	var lastErr error
	for _, edge := range edges {
		if dialCtx.Err() != nil {
			return nil, "", dialCtx.Err()
		}
		sc, err := c.dialStream(streamCtx, dialCtx, edge, path, withToken)
		if err == nil {
			return sc, edge, nil
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

// dialWSAny tries the edges in order and stops at the first connection or the first relay answer.
func (c *relayClient) dialWSAny(ctx context.Context, edges []string, path string, withToken bool) (relayConn, string, error) {
	var lastErr error
	for _, edge := range edges {
		if ctx.Err() != nil {
			return nil, "", ctx.Err()
		}
		conn, err := c.dialWS(ctx, edge, path, withToken)
		if err == nil {
			return wsConn{conn}, edge, nil
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

func (c *relayClient) getOnce(ctx context.Context, client *http.Client, path string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", c.base.String()+path, nil)
	if err != nil {
		return nil, err
	}
	res, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	return io.ReadAll(io.LimitReader(res.Body, limit))
}

func (c *relayClient) get(ctx context.Context, edge, path string, limit int64) ([]byte, error) {
	if !c.h3Enabled() || edge == edgeProxy {
		return c.getOnce(ctx, c.httpClient(edge), path, limit)
	}
	h3Edges := []string{edge}
	if edge == "" {
		if dnsEdges := c.dnsEdgeCandidates(ctx); len(dnsEdges) > 0 {
			h3Edges = append(dnsEdges, "")
		}
	}
	type res struct {
		b   []byte
		err error
	}
	raceCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	total := 1 + len(h3Edges)
	ch := make(chan res, total)
	go func() {
		b, err := c.getOnce(raceCtx, c.httpClient(edge), path, limit)
		ch <- res{b, err}
	}()
	for _, he := range h3Edges {
		he := he
		go func() {
			hc := &http.Client{
				Transport:     c.pool().transportFor(c, he),
				Timeout:       10 * time.Second,
				CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
			}
			b, err := c.getOnce(raceCtx, hc, path, limit)
			ch <- res{b, err}
		}()
	}
	var lastErr error
	for i := 0; i < total; i++ {
		r := <-ch
		if r.err == nil {
			cancel()
			return r.b, nil
		}
		lastErr = r.err
	}
	return nil, lastErr
}

type relayInfo struct {
	Access       string   `json:"access"`
	Edges        []string `json:"edges"`
	Capabilities struct {
		Dgram   string `json:"dgram"`
		UDPPort int    `json:"udpPort"`
		UDPHost string `json:"udpHost"`
		Stream  bool   `json:"stream"`
	} `json:"capabilities"`
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

func (c *relayClient) probeTCP(ctx context.Context, edge string) (time.Duration, error) {
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

func (c *relayClient) probeH3(ctx context.Context, edge string) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	start := time.Now()
	hc := &http.Client{
		Transport:     c.pool().transportFor(c, edge),
		Timeout:       3 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	if _, err := c.getOnce(ctx, hc, "/v1/info", 64<<10); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// probe measures TCP/TLS (and H3 when QUIC is enabled) latency to the relay host through one candidate.
func (c *relayClient) probe(ctx context.Context, edge string) (time.Duration, error) {
	if !c.h3Enabled() || edge == edgeProxy {
		return c.probeTCP(ctx, edge)
	}
	type res struct {
		d   time.Duration
		err error
	}
	ch := make(chan res, 2)
	go func() {
		d, err := c.probeTCP(ctx, edge)
		ch <- res{d, err}
	}()
	go func() {
		d, err := c.probeH3(ctx, edge)
		ch <- res{d, err}
	}()
	r1 := <-ch
	if r1.err == nil {
		return r1.d, nil
	}
	r2 := <-ch
	return r2.d, r2.err
}

type edgeRoute struct {
	edge   string
	stream bool
	d      time.Duration
}

func (c *relayClient) rankRoutes(ctx context.Context, candidates []string) ([]edgeRoute, []string) {
	all := append([]string{""}, candidates...)
	type probeRes struct {
		route edgeRoute
		ok    bool
	}
	results := make([]probeRes, len(all)*2)
	var wg sync.WaitGroup
	for i, e := range all {
		wg.Add(2)
		go func(idx int, edge string) {
			defer wg.Done()
			d, err := c.probeH3(ctx, edge)
			results[idx] = probeRes{edgeRoute{edge: edge, stream: true, d: d}, err == nil}
		}(i*2, e)
		go func(idx int, edge string) {
			defer wg.Done()
			d, err := c.probeTCP(ctx, edge)
			results[idx] = probeRes{edgeRoute{edge: edge, stream: false, d: d}, err == nil}
		}(i*2+1, e)
	}
	wg.Wait()

	var routes []edgeRoute
	for _, r := range results {
		if r.ok {
			routes = append(routes, r.route)
		}
	}
	sort.SliceStable(routes, func(a, b int) bool {
		// Prefer stream (H3 over UDP) first, fastest RTT within each transport.
		if routes[a].stream != routes[b].stream {
			return routes[a].stream
		}
		return routes[a].d < routes[b].d
	})

	var edges []string
	seen := map[string]bool{}
	for _, rt := range routes {
		if !seen[rt.edge] {
			seen[rt.edge] = true
			edges = append(edges, rt.edge)
		}
	}
	if !seen[""] {
		edges = append(edges, "")
	}
	return routes, edges
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
	mu            sync.Mutex
	client        *relayClient
	now           func() time.Time
	ranked        []string
	routes        []edgeRoute
	chosen        time.Time
	hasInfo       bool
	streamCapable bool
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
		s.routes = nil
		s.chosen = s.now()
		return append([]string(nil), s.ranked...)
	}
	var candidates []string
	infoCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	if info, err := s.client.info(infoCtx); err == nil {
		s.hasInfo = true
		s.streamCapable = info.Capabilities.Stream
		candidates = parseEdges(info.Edges)
	}
	cancel()
	if s.client.h3Enabled() {
		if len(candidates) == 0 {
			candidates = s.client.dnsEdgeCandidates(ctx)
		}
		s.routes, s.ranked = s.client.rankRoutes(ctx, candidates)
	} else {
		s.routes = nil
		s.ranked = s.client.rankEdges(ctx, candidates)
	}
	s.chosen = s.now()
	return append([]string(nil), s.ranked...)
}

func (s *edgeSelector) snapshot() (edges []string, routes []edgeRoute, hasInfo, streamCapable bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.ranked) > 0 {
		edges = append([]string(nil), s.ranked...)
	} else {
		edges = []string{""}
	}
	if len(s.routes) > 0 {
		routes = append([]edgeRoute(nil), s.routes...)
	}
	return edges, routes, s.hasInfo, s.streamCapable
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
