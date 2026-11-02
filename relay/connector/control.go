package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net/url"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type timing struct {
	ping          time.Duration // text "ping" interval on every WebSocket
	readIdle      time.Duration // no frame at all (not even a pong) for this long: the connection is dead
	handshake     time.Duration // open received → data connection up and challenge sent
	authWait      time.Duration // challenge sent → the app's auth must have arrived
	backoffMin    time.Duration
	backoffMax    time.Duration
	backoffReset  time.Duration // a connection that lived this long resets the backoff
	longRetry     time.Duration // after 4401 / platform refusals
	registerRetry time.Duration // after too_many_registrations without a retryAfter
	reconcile     time.Duration // how often config.json is checked for relays to add or drop
}

func defaultTiming() timing {
	return timing{
		ping: 30 * time.Second, readIdle: 100 * time.Second, handshake: 10 * time.Second, authWait: 5 * time.Second,
		backoffMin: time.Second, backoffMax: 60 * time.Second, backoffReset: 30 * time.Second,
		longRetry: 5 * time.Minute, registerRetry: time.Hour, reconcile: 2 * time.Second,
	}
}

const maxConcurrentSessions = 16

type connector struct {
	paths   dataPaths
	id      *identity
	log     *logger
	env     netEnv
	version string
	timing  timing
	limiter *failureLimiter
	jitter  func(time.Duration) time.Duration

	stateMu sync.Mutex
	st      stateFile

	sessions chan struct{}
	wg       sync.WaitGroup
}

// link is the control connection to one relay. The connector keeps one per relay that a paired
// phone uses, so every phone reaches the computer through its own relay.
type link struct {
	c     *connector
	url   string
	token string

	selMu    sync.Mutex
	selURL   string
	selector *edgeSelector
	warm     *tlsWarmer // spare TLS connection for session data connections, while the control link is up
}

func (l *link) setWarmer(w *tlsWarmer) {
	l.selMu.Lock()
	l.warm = w
	l.selMu.Unlock()
}

func (l *link) warmer() *tlsWarmer {
	l.selMu.Lock()
	defer l.selMu.Unlock()
	return l.warm
}

func newConnector(paths dataPaths, id *identity, version string, env netEnv, log *logger) *connector {
	return &connector{
		paths: paths, id: id, log: log, env: env, version: version,
		timing:   defaultTiming(),
		limiter:  newFailureLimiter(),
		jitter:   func(d time.Duration) time.Duration { return d + time.Duration((rand.Float64()*0.4-0.2)*float64(d)) },
		st:       stateFile{Relays: map[string]state{}},
		sessions: make(chan struct{}, maxConcurrentSessions),
	}
}

func (c *connector) save() {
	if err := saveState(c.paths, &c.st); err != nil {
		c.log.printf("cannot write state.json: %v", err)
	}
}

// update changes the state of the relay at url.
func (c *connector) update(url string, fn func(*state)) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	if c.st.Relays == nil {
		c.st.Relays = map[string]state{}
	}
	st := c.st.Relays[url]
	fn(&st)
	c.st.Relays[url] = st
	c.save()
}

// forget drops the state of relays that are no longer in config.json.
func (c *connector) forget(keep map[string]bool) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	changed := false
	for url := range c.st.Relays {
		if !keep[url] {
			delete(c.st.Relays, url)
			changed = true
		}
	}
	if changed {
		c.save()
	}
}

func (c *connector) snapshot(url string) state {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	return c.st.Relays[url]
}

func (l *link) setError(reason string) {
	l.c.update(l.url, func(s *state) { s.Connected = false; s.LastError = &reason })
}

// selectorFor returns the edge selector for the relay URL, replacing it when the URL changed.
func (l *link) selectorFor(client *relayClient) *edgeSelector {
	l.selMu.Lock()
	defer l.selMu.Unlock()
	if l.selector == nil || l.selURL != client.base.String() {
		l.selector = newEdgeSelector(client)
		l.selURL = client.base.String()
	}
	return l.selector
}

func (l *link) cachedEdges() []string {
	l.selMu.Lock()
	defer l.selMu.Unlock()
	if l.selector == nil {
		return []string{""}
	}
	l.selector.mu.Lock()
	defer l.selector.mu.Unlock()
	if len(l.selector.ranked) == 0 {
		return []string{""}
	}
	return append([]string(nil), l.selector.ranked...)
}

// outcome describes how one control connection attempt ended.
type outcome struct {
	err         error
	reason      string        // short code for state.json lastError
	wait        time.Duration // minimum wait before the next attempt (0: ordinary backoff)
	established bool
	lived       time.Duration
	network     bool // connection-level failure or drop: re-measure the edges next time
}

// run keeps one link per relay in use until ctx ends, following config.json: a relay that a phone
// starts using is connected within timing.reconcile, and one that no phone uses any more is dropped.
func (c *connector) run(ctx context.Context) {
	defer c.wg.Wait()
	type running struct {
		token  string
		cancel context.CancelFunc
		done   chan struct{}
	}
	links := map[string]*running{}
	stop := func(url string) {
		r := links[url]
		r.cancel()
		<-r.done
		delete(links, url)
	}
	defer func() {
		for url := range links {
			stop(url)
		}
	}()
	ticker := time.NewTicker(c.timing.reconcile)
	defer ticker.Stop()
	lastErr := ""
	for {
		cfg, err := loadConfig(c.paths)
		if err != nil {
			if err.Error() != lastErr {
				c.log.printf("cannot read config.json: %v", err)
				lastErr = err.Error()
			}
		} else {
			lastErr = ""
			want := map[string]string{}
			for _, r := range cfg.activeRelays(time.Now()) {
				want[r.URL] = r.AccessToken
			}
			for url, r := range links {
				if token, ok := want[url]; !ok || token != r.token {
					c.log.printf("leaving relay %s", url)
					stop(url)
				}
			}
			known := map[string]bool{}
			for _, r := range cfg.Relays {
				known[r.URL] = true
			}
			c.forget(known)
			for url, token := range want {
				if links[url] != nil {
					continue
				}
				lctx, cancel := context.WithCancel(ctx)
				r := &running{token: token, cancel: cancel, done: make(chan struct{})}
				links[url] = r
				l := &link{c: c, url: url, token: token}
				go func() { defer close(r.done); l.run(lctx) }()
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// run keeps this relay's control connection up until ctx ends.
func (l *link) run(ctx context.Context) {
	c := l.c
	l.c.update(l.url, func(s *state) { s.Connected = false })
	backoff := c.timing.backoffMin
	force := false
	for ctx.Err() == nil {
		out := l.controlOnce(ctx, force)
		if ctx.Err() != nil {
			break
		}
		force = out.network
		if out.reason != "" {
			l.setError(out.reason)
		} else {
			c.update(l.url, func(s *state) { s.Connected = false })
		}
		c.log.printf("control connection to %s ended (%s): %v", l.url, out.reason, out.err)
		if out.established && out.lived >= c.timing.backoffReset {
			backoff = c.timing.backoffMin
		}
		wait := c.jitter(backoff)
		if out.wait > wait {
			wait = out.wait
		}
		backoff *= 2
		if backoff > c.timing.backoffMax {
			backoff = c.timing.backoffMax
		}
		if err := sleepCtx(ctx, wait); err != nil {
			break
		}
	}
	c.update(l.url, func(s *state) { s.Connected = false })
}

func (l *link) controlOnce(ctx context.Context, forceProbe bool) (out outcome) {
	c := l.c
	cfg, err := loadConfig(c.paths)
	if err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	if _, err := validateTarget(cfg.Target); err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	client, err := newRelayClient(l.url, l.token, c.env)
	if err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	sel := l.selectorFor(client)
	edges := sel.get(ctx, forceProbe)
	if ctx.Err() != nil {
		return outcome{err: ctx.Err()}
	}

	path := "/v1/host?pub=" + url.QueryEscape(b64u.EncodeToString(c.id.pub()))
	if !c.snapshot(l.url).Registered {
		if hint := hintForColo(client.colo(ctx, edges[0])); hint != "" {
			path += "&hint=" + hint
		}
	}
	dialCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	ws, edge, err := client.dialWSAny(dialCtx, edges, path, true)
	cancel()
	if err != nil {
		var re *relayError
		if errors.As(err, &re) {
			return relayRefusal(re, c.timing)
		}
		return outcome{err: err, reason: "network", network: true}
	}
	defer ws.Close()
	started := time.Now()
	defer func() { out.lived = time.Since(started) }()

	ws.SetReadLimit(64 << 10)
	var wmu sync.Mutex
	write := func(kind int, data []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
		return ws.WriteMessage(kind, data)
	}

	// challenge → auth → hello, within 10 seconds.
	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	var ch struct{ T, Nonce string }
	if err := readJSON(ws, &ch); err != nil || ch.T != "challenge" || ch.Nonce == "" || len(ch.Nonce) > 256 {
		return handshakeFailure(err, "protocol", c.timing)
	}
	sig := c.id.signHost(client.hostHeader(), ch.Nonce)
	authMsg, _ := json.Marshal(map[string]string{"t": "auth", "sig": sig})
	if err := write(websocket.TextMessage, authMsg); err != nil {
		return outcome{err: err, reason: "network", network: true}
	}
	var hello struct {
		T     string
		Usage json.RawMessage
	}
	if err := readJSON(ws, &hello); err != nil || hello.T != "hello" {
		return handshakeFailure(err, "protocol", c.timing)
	}

	c.update(l.url, func(s *state) {
		now := time.Now().UTC().Format(time.RFC3339)
		s.Connected = true
		s.LastError = nil
		s.LastConnectedAt = &now
		s.Edge = describeEdge(edge)
		s.Registered = true
		if len(hello.Usage) > 0 && len(hello.Usage) <= 4096 {
			s.Usage = hello.Usage
		}
	})
	c.log.printf("connected to relay %s as %s via %s", l.url, c.id.hostID, describeEdge(edge))

	// Keep a TLS connection ready for the next session's data connection (not through a proxy).
	if client.base.Scheme == "https" && edge != edgeProxy {
		w := newTLSWarmer(client, edge)
		wctx, wcancel := context.WithCancel(ctx)
		go w.run(wctx)
		l.setWarmer(w)
		defer func() {
			l.setWarmer(nil)
			wcancel()
		}()
	}

	// Keepalive: text ping only (PROTOCOL §5.4).
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		t := time.NewTicker(c.timing.ping)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ctx.Done():
				ws.Close()
				return
			case <-t.C:
				if write(websocket.TextMessage, []byte("ping")) != nil {
					ws.Close()
					return
				}
			}
		}
	}()

	for {
		_ = ws.SetReadDeadline(time.Now().Add(c.timing.readIdle))
		kind, data, err := ws.ReadMessage()
		if err != nil {
			return classifyClose(err, true, c.timing)
		}
		if kind != websocket.TextMessage || string(data) == "pong" {
			continue
		}
		var m struct {
			T     string          `json:"t"`
			Sid   string          `json:"sid"`
			Ch    string          `json:"ch"`
			Pace  pace            `json:"pace"`
			Usage json.RawMessage `json:"usage"`
		}
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		switch m.T {
		case "open":
			l.startSession(ctx, m.Sid, m.Ch, m.Pace)
		case "usage":
			if len(m.Usage) > 0 && len(m.Usage) <= 4096 {
				c.update(l.url, func(s *state) { s.Usage = m.Usage })
			}
		}
	}
}

func readJSON(ws *websocket.Conn, v any) error {
	kind, data, err := ws.ReadMessage()
	if err != nil {
		return err
	}
	if kind != websocket.TextMessage {
		return errors.New("expected a text frame")
	}
	return json.Unmarshal(data, v)
}

func handshakeFailure(err error, fallback string, t timing) outcome {
	if err == nil {
		return outcome{err: errors.New("unexpected handshake message"), reason: fallback}
	}
	return classifyClose(err, false, t)
}

// classifyClose turns a read error into an outcome.
func classifyClose(err error, established bool, t timing) outcome {
	var ce *websocket.CloseError
	if errors.As(err, &ce) {
		switch ce.Code {
		case 4000:
			return outcome{err: err, reason: "replaced", established: established}
		case 4401:
			return outcome{err: err, reason: "unauthorized", wait: t.longRetry, established: established}
		}
		return outcome{err: err, reason: "closed", established: established}
	}
	if errors.Is(err, os.ErrDeadlineExceeded) {
		return outcome{err: err, reason: "timeout", established: established, network: true}
	}
	return outcome{err: err, reason: "network", established: established, network: true}
}

// relayRefusal maps an HTTP refusal of the control upgrade to an outcome.
func relayRefusal(re *relayError, t timing) outcome {
	out := outcome{err: re, reason: re.Reason}
	switch {
	case re.Status == 401 || re.Reason == "unauthorized" || re.Reason == "platform_rejected":
		out.wait = t.longRetry
	case re.Reason == "too_many_registrations":
		out.wait = t.registerRetry
	}
	if re.RetryAfter > out.wait {
		out.wait = re.RetryAfter
	}
	if out.wait > 2*time.Hour {
		out.wait = 2 * time.Hour
	}
	return out
}

func (l *link) startSession(ctx context.Context, sid, ch string, p pace) {
	c := l.c
	if !sidPattern.MatchString(sid) || (ch != "control" && ch != "media") {
		c.log.printf("ignoring malformed open message")
		return
	}
	select {
	case c.sessions <- struct{}{}:
	default:
		c.log.printf("too many sessions in flight; ignoring open %s", sid[:8])
		return
	}
	edges := l.cachedEdges()
	warm := l.warmer()
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		defer func() { <-c.sessions }()
		c.session(ctx, l, sid, ch, p, edges, warm)
	}()
}

var errNotInitialized = fmt.Errorf("not initialized")
