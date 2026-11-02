package main

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"time"
)

// A session's data connection is a new WebSocket to the relay: TCP connect, TLS handshake, HTTP
// upgrade - three round trips before the app can be answered. tlsWarmer keeps one connection per relay
// with TCP and TLS already done and no HTTP request sent, so a session needs only the upgrade. An idle
// TLS connection reaches no Worker, so it costs the relay nothing. It is replaced every warmMaxAge, and
// one that the edge has closed meanwhile is noticed before use; either way the session falls back to
// dialing as before.

const (
	warmMaxAge   = 60 * time.Second
	warmRetry    = 10 * time.Second
	warmProbeFor = time.Millisecond
)

type tlsWarmer struct {
	client *relayClient
	edge   string
	maxAge time.Duration
	now    func() time.Time

	mu   sync.Mutex
	conn *tls.Conn
	born time.Time
	kick chan struct{}
}

func newTLSWarmer(client *relayClient, edge string) *tlsWarmer {
	return &tlsWarmer{client: client, edge: edge, maxAge: warmMaxAge, now: time.Now, kick: make(chan struct{}, 1)}
}

// run keeps a spare connection ready until ctx ends, then closes it.
func (w *tlsWarmer) run(ctx context.Context) {
	defer w.put(nil)
	for {
		wait := w.maxAge
		w.mu.Lock()
		fresh := w.conn != nil && w.now().Sub(w.born) < w.maxAge
		if fresh {
			wait = w.maxAge - w.now().Sub(w.born)
		}
		w.mu.Unlock()
		if !fresh {
			dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			c, err := w.client.dialTLS(dctx, w.edge)
			cancel()
			if ctx.Err() != nil {
				if c != nil {
					c.Close()
				}
				return
			}
			if err != nil {
				wait = warmRetry
			} else {
				w.put(c)
				wait = w.maxAge
			}
		}
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-w.kick:
		case <-t.C:
		}
		t.Stop()
	}
}

// put replaces the spare, closing the old one.
func (w *tlsWarmer) put(c *tls.Conn) {
	w.mu.Lock()
	old := w.conn
	w.conn, w.born = c, w.now()
	w.mu.Unlock()
	if old != nil {
		old.Close()
	}
}

// take hands out the spare for a connection to edge, or nil when there is none that is usable.
func (w *tlsWarmer) take(edge string) *tls.Conn {
	if w == nil {
		return nil
	}
	w.mu.Lock()
	c := w.conn
	if c == nil || edge != w.edge || w.now().Sub(w.born) >= w.maxAge {
		w.mu.Unlock()
		return nil
	}
	w.conn = nil
	w.mu.Unlock()
	select {
	case w.kick <- struct{}{}: // make the next one
	default:
	}
	if !stillOpen(c) {
		c.Close()
		return nil
	}
	return c
}

// stillOpen reports whether an idle TLS connection has not been closed by the other end. Waiting a
// moment for data is safe on a TLS connection: a post-handshake message (a session ticket) is consumed
// by crypto/tls itself, and a read that times out leaves the connection usable.
func stillOpen(c *tls.Conn) bool {
	if err := c.SetReadDeadline(time.Now().Add(warmProbeFor)); err != nil {
		return false
	}
	var b [1]byte
	n, err := c.Read(b[:])
	_ = c.SetReadDeadline(time.Time{})
	var ne net.Error
	return n == 0 && errors.As(err, &ne) && ne.Timeout()
}

// dialTLS connects to the relay through edge and completes the TLS handshake, verifying the relay's
// host name exactly as the WebSocket dialer would (PROTOCOL §8).
func (c *relayClient) dialTLS(ctx context.Context, edge string) (*tls.Conn, error) {
	raw, err := c.env.dial(ctx, "tcp", c.dialAddr(edge))
	if err != nil {
		return nil, err
	}
	cfg := c.tlsConfig()
	cfg.NextProtos = []string{"http/1.1"} // the upgrade is an HTTP/1.1 request
	tc := tls.Client(raw, cfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		raw.Close()
		return nil, err
	}
	return tc, nil
}
