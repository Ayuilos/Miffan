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
	handshake     time.Duration // open received → authenticated
	backoffMin    time.Duration
	backoffMax    time.Duration
	backoffReset  time.Duration // a connection that lived this long resets the backoff
	longRetry     time.Duration // after 4401 / platform refusals
	registerRetry time.Duration // after too_many_registrations without a retryAfter
}

func defaultTiming() timing {
	return timing{
		ping: 30 * time.Second, readIdle: 100 * time.Second, handshake: 10 * time.Second,
		backoffMin: time.Second, backoffMax: 60 * time.Second, backoffReset: 30 * time.Second,
		longRetry: 5 * time.Minute, registerRetry: time.Hour,
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
	st      state

	selMu    sync.Mutex
	selURL   string
	selector *edgeSelector

	sessions chan struct{}
	wg       sync.WaitGroup
}

func newConnector(paths dataPaths, id *identity, version string, env netEnv, log *logger) *connector {
	return &connector{
		paths: paths, id: id, log: log, env: env, version: version,
		timing:   defaultTiming(),
		limiter:  newFailureLimiter(),
		jitter:   func(d time.Duration) time.Duration { return d + time.Duration((rand.Float64()*0.4-0.2)*float64(d)) },
		sessions: make(chan struct{}, maxConcurrentSessions),
	}
}

func (c *connector) update(fn func(*state)) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	fn(&c.st)
	if err := saveState(c.paths, &c.st); err != nil {
		c.log.printf("cannot write state.json: %v", err)
	}
}

func (c *connector) snapshot() state {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	return c.st
}

func (c *connector) setError(reason string) {
	c.update(func(s *state) { s.Connected = false; s.LastError = &reason })
}

// selectorFor returns the edge selector for the relay URL, replacing it when the URL changed.
func (c *connector) selectorFor(client *relayClient) *edgeSelector {
	c.selMu.Lock()
	defer c.selMu.Unlock()
	if c.selector == nil || c.selURL != client.base.String() {
		c.selector = newEdgeSelector(client)
		c.selURL = client.base.String()
	}
	return c.selector
}

func (c *connector) cachedEdges() []string {
	c.selMu.Lock()
	defer c.selMu.Unlock()
	if c.selector == nil {
		return []string{""}
	}
	c.selector.mu.Lock()
	defer c.selector.mu.Unlock()
	if len(c.selector.ranked) == 0 {
		return []string{""}
	}
	return append([]string(nil), c.selector.ranked...)
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

// run keeps the control connection up until ctx ends.
func (c *connector) run(ctx context.Context) {
	defer c.wg.Wait()
	c.update(func(s *state) { s.Connected = false })
	backoff := c.timing.backoffMin
	force := false
	for ctx.Err() == nil {
		out := c.controlOnce(ctx, force)
		if ctx.Err() != nil {
			break
		}
		force = out.network
		if out.reason != "" {
			c.setError(out.reason)
		} else {
			c.update(func(s *state) { s.Connected = false })
		}
		c.log.printf("control connection ended (%s): %v", out.reason, out.err)
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
	c.update(func(s *state) { s.Connected = false })
}

func (c *connector) controlOnce(ctx context.Context, forceProbe bool) (out outcome) {
	cfg, err := loadConfig(c.paths)
	if err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	if _, err := validateTarget(cfg.Target); err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	client, err := newRelayClient(cfg.Relay, cfg.AccessToken, c.env)
	if err != nil {
		return outcome{err: err, reason: "bad_config", wait: c.timing.longRetry}
	}
	sel := c.selectorFor(client)
	edges := sel.get(ctx, forceProbe)
	if ctx.Err() != nil {
		return outcome{err: ctx.Err()}
	}

	path := "/v1/host?pub=" + url.QueryEscape(b64u.EncodeToString(c.id.pub()))
	if !c.snapshot().Registered {
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

	c.update(func(s *state) {
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
	c.log.printf("connected to relay as %s via %s", c.id.hostID, describeEdge(edge))

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
			c.startSession(ctx, m.Sid, m.Ch, m.Pace)
		case "usage":
			if len(m.Usage) > 0 && len(m.Usage) <= 4096 {
				c.update(func(s *state) { s.Usage = m.Usage })
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

func (c *connector) startSession(ctx context.Context, sid, ch string, p pace) {
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
	edges := c.cachedEdges()
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		defer func() { <-c.sessions }()
		c.session(ctx, sid, ch, p, edges)
	}()
}

var errNotInitialized = fmt.Errorf("not initialized")
