package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	closeDenied = 4403
	closeNormal = 1000
	closeTooBig = 1009
	closeError  = 1011
)

// session serves one `open`: data connection → end-to-end handshake → forward to the configured target.
func (c *connector) session(ctx context.Context, l *link, sid, ch string, p pace, edges []string) {
	short := sid[:8]
	start := time.Now()
	deadline := start.Add(c.timing.handshake)

	client, err := newRelayClient(l.url, l.token, c.env)
	if err != nil {
		c.log.printf("session %s: bad relay in config", short)
		return
	}
	dialCtx, cancel := context.WithDeadline(ctx, deadline)
	// The session data connection is authorised by the sid alone (PROTOCOL §3), so no access code is sent.
	ws, _, err := client.dialWSAny(dialCtx, edges, "/v1/host/"+c.id.hostID+"/session/"+sid, false)
	cancel()
	if err != nil {
		c.log.printf("session %s: cannot open data connection: %v", short, err)
		return
	}
	defer ws.Close()

	var wmu sync.Mutex
	writeText := func(v any) error {
		raw, _ := json.Marshal(v)
		wmu.Lock()
		defer wmu.Unlock()
		_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
		return ws.WriteMessage(websocket.TextMessage, raw)
	}
	closeWith := func(code int, reason string) {
		_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), time.Now().Add(time.Second))
	}

	// 1. challenge
	nonceRaw := make([]byte, 32)
	if _, err := rand.Read(nonceRaw); err != nil {
		closeWith(closeError, "internal")
		return
	}
	nonce := b64u.EncodeToString(nonceRaw)
	ws.SetReadLimit(maxTextFrame)
	if err := writeText(map[string]any{"t": "challenge", "v": 1, "nonce": nonce}); err != nil {
		return
	}
	// The app answers as soon as the challenge arrives (one round trip and a signature). A session that
	// stays silent is cut early: until it ends it holds one of the relay's per-computer session slots.
	_ = ws.SetReadDeadline(time.Now().Add(c.timing.authWait))

	// 2. auth (relay answers pings itself, but tolerate a stray text "ping"; binary before ok is refused)
	var msg authMessage
	for {
		kind, data, err := ws.ReadMessage()
		if err != nil {
			c.log.printf("session %s: handshake did not complete", short)
			return
		}
		if kind == websocket.BinaryMessage {
			c.limiter.fail("")
			closeWith(closeDenied, "denied")
			return
		}
		if string(data) == "ping" || string(data) == "pong" {
			continue
		}
		if json.Unmarshal(data, &msg) != nil {
			c.limiter.fail("")
			_ = writeText(map[string]string{"t": "denied", "reason": reasonBadSignature})
			closeWith(closeDenied, "denied")
			return
		}
		break
	}

	// 3. verify against the allowlist as it is now (pair/unpair take effect on the next handshake)
	cfg, err := loadConfig(c.paths)
	if err != nil {
		closeWith(closeError, "internal")
		return
	}
	deviceID, reason := verifyAuth(cfg, c.limiter, c.id.hostID, sid, ch, nonce, msg)
	if reason != "" {
		c.log.printf("session %s: device refused (%s)", short, reason)
		_ = writeText(map[string]string{"t": "denied", "reason": reason})
		closeWith(closeDenied, "denied")
		return
	}
	target, err := validateTarget(cfg.Target)
	if err != nil {
		closeWith(closeError, "internal")
		return
	}
	if err := writeText(map[string]string{"t": "ok"}); err != nil {
		return
	}

	// 4. the only place a connection to the local machine is made, and only to the configured target
	local, err := net.DialTimeout("tcp", target, 5*time.Second)
	if err != nil {
		c.log.printf("session %s: cannot reach the local target", short)
		closeWith(closeError, "target_unavailable")
		return
	}
	defer local.Close()
	c.log.printf("session %s ch=%s device=%s up in %s", short, ch, deviceID[:6], time.Since(start).Round(time.Millisecond))

	up, down := c.forward(ctx, ws, local, &wmu, p)
	c.log.printf("session %s closed after %s: %d B to relay, %d B from relay", short, time.Since(start).Round(time.Second), up, down)
}

// forward copies in both directions until either side ends and returns the byte counts
// (toRelay, fromRelay).
func (c *connector) forward(ctx context.Context, ws *websocket.Conn, local net.Conn, wmu *sync.Mutex, p pace) (int64, int64) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var toRelay, fromRelay int64
	var once sync.Once
	var closeCode = closeNormal
	var closeText = "eof"
	finish := func(code int, text string) {
		once.Do(func() {
			closeCode, closeText = code, text
			cancel()
			_ = local.Close()
			// Unblock the reader goroutine that is waiting on the WebSocket.
			_ = ws.SetReadDeadline(time.Now())
		})
	}
	var wg sync.WaitGroup

	// relay → local
	wg.Add(1)
	go func() {
		defer wg.Done()
		ws.SetReadLimit(maxBinaryFrame)
		for {
			_ = ws.SetReadDeadline(time.Now().Add(c.timing.readIdle))
			kind, r, err := ws.NextReader()
			if err != nil {
				finish(closeNormal, "peer_closed")
				return
			}
			if kind == websocket.TextMessage {
				// Only pong/ping can arrive here; anything bigger than a text frame may be is a protocol error.
				if n, _ := io.Copy(io.Discard, io.LimitReader(r, maxTextFrame+1)); n > maxTextFrame {
					finish(closeTooBig, "frame_too_large")
					return
				}
				continue
			}
			n, err := io.Copy(local, r)
			fromRelay += n
			if err != nil {
				finish(closeNormal, "peer_closed")
				return
			}
		}
	}()

	// keepalive: text ping every 30 s, nothing else
	wg.Add(1)
	go func() {
		defer wg.Done()
		t := time.NewTicker(c.timing.ping)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				wmu.Lock()
				_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
				err := ws.WriteMessage(websocket.TextMessage, []byte("ping"))
				wmu.Unlock()
				if err != nil {
					finish(closeNormal, "peer_closed")
					return
				}
			}
		}
	}()

	// local → relay, paced and coalesced
	bkt := newBucket(p)
	buf := make([]byte, bkt.frameLimit())
	fr := newFramer()
	for ctx.Err() == nil {
		n, err := fr.read(local, buf)
		if n > 0 {
			if werr := bkt.take(ctx, n); werr != nil {
				break
			}
			wmu.Lock()
			_ = ws.SetWriteDeadline(time.Now().Add(60 * time.Second))
			werr := ws.WriteMessage(websocket.BinaryMessage, buf[:n])
			wmu.Unlock()
			if werr != nil {
				finish(closeNormal, "peer_closed")
				break
			}
			toRelay += int64(n)
		}
		if err != nil {
			if errors.Is(err, io.EOF) || !errors.Is(err, net.ErrClosed) {
				finish(closeNormal, "eof")
			}
			break
		}
	}
	finish(closeNormal, "eof")
	_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(closeCode, closeText), time.Now().Add(time.Second))
	_ = ws.Close()
	wg.Wait()
	return toRelay, fromRelay
}
