package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

const streamContentType = "application/x-miffan-relay-v1"

const (
	streamTypePrelude byte = 0x00
	streamTypeText    byte = 0x01
	streamTypeBinary  byte = 0x02
	streamTypeClose   byte = 0x08
)

var streamPreludeBytes = [5]byte{streamTypePrelude, 0, 0, 0, 0}

// relayConn unifies WebSocket connections and HTTP bidirectional stream connections for control and sessions.
type relayConn interface {
	ReadMessage() (messageType int, p []byte, err error)
	WriteMessage(messageType int, data []byte) error
	SetReadLimit(limit int64)
	SetReadDeadline(t time.Time) error
	SetWriteDeadline(t time.Time) error
	WriteClose(code int, reason string, deadline time.Time) error
	Close() error
}

// relayCloseError represents a 0x08 close frame received over an HTTP bidirectional stream.
type relayCloseError struct {
	Code int
	Text string
}

func (e *relayCloseError) Error() string {
	return fmt.Sprintf("relay closed: %d %s", e.Code, e.Text)
}

func asRelayClose(err error) (int, string, bool) {
	var ce *websocket.CloseError
	if errors.As(err, &ce) {
		return ce.Code, ce.Text, true
	}
	var rce *relayCloseError
	if errors.As(err, &rce) {
		return rce.Code, rce.Text, true
	}
	return 0, "", false
}

// wsConn adapts *websocket.Conn to relayConn.
type wsConn struct{ *websocket.Conn }

func (w wsConn) WriteClose(code int, reason string, deadline time.Time) error {
	return w.Conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), deadline)
}

type streamReadItem struct {
	kind int
	data []byte
	err  error
}

type streamBodyReader struct {
	r io.Reader
	c io.Closer
}

func (b *streamBodyReader) Read(p []byte) (int, error) { return b.r.Read(p) }
func (b *streamBodyReader) Close() error               { return b.c.Close() }

// streamConn implements relayConn over a bidirectional HTTP stream (POST application/x-miffan-relay-v1).
type streamConn struct {
	cancel context.CancelFunc
	pw     *io.PipeWriter
	body   io.ReadCloser

	wmu           sync.Mutex
	writeDeadline atomic.Int64 // unix nanos, 0 if unset
	closed        atomic.Bool

	readLimit atomic.Int64

	rmu          sync.Mutex
	readDeadline time.Time
	rdWake       chan struct{}
	frames       chan streamReadItem
}

func newStreamConn(cancel context.CancelFunc, pw *io.PipeWriter, body io.ReadCloser) *streamConn {
	sc := &streamConn{
		cancel: cancel,
		pw:     pw,
		body:   body,
		rdWake: make(chan struct{}, 1),
		frames: make(chan streamReadItem, 8),
	}
	go sc.readLoop()
	return sc
}

func (s *streamConn) SetReadLimit(limit int64) {
	s.readLimit.Store(limit)
}

func (s *streamConn) SetReadDeadline(t time.Time) error {
	s.rmu.Lock()
	s.readDeadline = t
	s.rmu.Unlock()
	select {
	case s.rdWake <- struct{}{}:
	default:
	}
	return nil
}

func (s *streamConn) SetWriteDeadline(t time.Time) error {
	if t.IsZero() {
		s.writeDeadline.Store(0)
	} else {
		s.writeDeadline.Store(t.UnixNano())
	}
	return nil
}

func (s *streamConn) readLoop() {
	defer close(s.frames)
	br := bufio.NewReaderSize(s.body, 64<<10)
	var hdr [5]byte
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			s.pushFrame(streamReadItem{err: err})
			return
		}
		typ := hdr[0]
		n := binary.BigEndian.Uint32(hdr[1:5])

		switch typ {
		case streamTypePrelude:
			if n != 0 {
				s.pushFrame(streamReadItem{err: errors.New("invalid stream prelude")})
				return
			}
			continue
		case streamTypeText:
			if n > maxTextFrame || (s.readLimit.Load() > 0 && int64(n) > s.readLimit.Load()) {
				s.pushFrame(streamReadItem{err: &relayCloseError{Code: 4413, Text: "frame_too_large"}})
				return
			}
		case streamTypeBinary:
			limit := s.readLimit.Load()
			if limit <= 0 || limit > maxBinaryFrame {
				limit = maxBinaryFrame
			}
			if int64(n) > limit {
				s.pushFrame(streamReadItem{err: &relayCloseError{Code: 4413, Text: "frame_too_large"}})
				return
			}
		case streamTypeClose:
			if n > 125 || n == 1 {
				s.pushFrame(streamReadItem{err: errors.New("invalid close frame length")})
				return
			}
		default:
			s.pushFrame(streamReadItem{err: fmt.Errorf("unknown stream frame type 0x%02x", typ)})
			return
		}

		var payload []byte
		if n > 0 {
			payload = make([]byte, n)
			if _, err := io.ReadFull(br, payload); err != nil {
				s.pushFrame(streamReadItem{err: err})
				return
			}
		}

		switch typ {
		case streamTypeText:
			if !utf8.Valid(payload) {
				s.pushFrame(streamReadItem{err: errors.New("invalid utf-8 text frame")})
				return
			}
			if !s.pushFrame(streamReadItem{kind: websocket.TextMessage, data: payload}) {
				return
			}
		case streamTypeBinary:
			if !s.pushFrame(streamReadItem{kind: websocket.BinaryMessage, data: payload}) {
				return
			}
		case streamTypeClose:
			code := 1000
			reason := ""
			if len(payload) >= 2 {
				code = int(binary.BigEndian.Uint16(payload[:2]))
				if !utf8.Valid(payload[2:]) {
					s.pushFrame(streamReadItem{err: errors.New("invalid utf-8 close reason")})
					return
				}
				reason = string(payload[2:])
			}
			s.pushFrame(streamReadItem{err: &relayCloseError{Code: code, Text: reason}})
			return
		}
	}
}

func (s *streamConn) pushFrame(item streamReadItem) bool {
	s.frames <- item
	return true
}

func (s *streamConn) ReadMessage() (int, []byte, error) {
	for {
		s.rmu.Lock()
		dl := s.readDeadline
		s.rmu.Unlock()

		if dl.IsZero() {
			select {
			case item, ok := <-s.frames:
				if !ok {
					return 0, nil, io.EOF
				}
				return item.kind, item.data, item.err
			case <-s.rdWake:
				continue
			}
		}

		wait := time.Until(dl)
		if wait <= 0 {
			// Consume an already-buffered frame if present? No: Go net.Conn returns timeout immediately when past deadline.
			return 0, nil, os.ErrDeadlineExceeded
		}
		timer := time.NewTimer(wait)
		select {
		case item, ok := <-s.frames:
			timer.Stop()
			if !ok {
				return 0, nil, io.EOF
			}
			return item.kind, item.data, item.err
		case <-timer.C:
			return 0, nil, os.ErrDeadlineExceeded
		case <-s.rdWake:
			timer.Stop()
		}
	}
}

func encodeStreamFrameBytes(typ byte, payload []byte) []byte {
	out := make([]byte, 5+len(payload))
	out[0] = typ
	binary.BigEndian.PutUint32(out[1:5], uint32(len(payload)))
	copy(out[5:], payload)
	return out
}

func encodeStreamCloseBytes(code int, reason string) []byte {
	rb := []byte(reason)
	if len(rb) > 123 {
		n := 123
		for n > 0 && !utf8.Valid(rb[:n]) {
			n--
		}
		rb = rb[:n]
	}
	body := make([]byte, 2+len(rb))
	binary.BigEndian.PutUint16(body[:2], uint16(code))
	copy(body[2:], rb)
	return encodeStreamFrameBytes(streamTypeClose, body)
}

func (s *streamConn) writeRaw(raw []byte, deadlineNano int64) error {
	if deadlineNano > 0 {
		wait := time.Until(time.Unix(0, deadlineNano))
		if wait <= 0 {
			return os.ErrDeadlineExceeded
		}
		t := time.AfterFunc(wait, func() {
			_ = s.pw.CloseWithError(os.ErrDeadlineExceeded)
		})
		defer t.Stop()
	}
	_, err := s.pw.Write(raw)
	return err
}

func (s *streamConn) WriteMessage(messageType int, data []byte) error {
	var typ byte
	switch messageType {
	case websocket.TextMessage:
		typ = streamTypeText
	case websocket.BinaryMessage:
		typ = streamTypeBinary
	default:
		return fmt.Errorf("unsupported message type %d", messageType)
	}
	raw := encodeStreamFrameBytes(typ, data)
	s.wmu.Lock()
	defer s.wmu.Unlock()
	return s.writeRaw(raw, s.writeDeadline.Load())
}

func (s *streamConn) WriteClose(code int, reason string, deadline time.Time) error {
	raw := encodeStreamCloseBytes(code, reason)
	var dlNano int64
	if !deadline.IsZero() {
		dlNano = deadline.UnixNano()
	}
	s.wmu.Lock()
	defer s.wmu.Unlock()
	err := s.writeRaw(raw, dlNano)
	_ = s.pw.Close()
	return err
}

func (s *streamConn) Close() error {
	if s.closed.Swap(true) {
		return nil
	}
	_ = s.pw.Close()
	_ = s.body.Close()
	s.cancel()
	return nil
}

// h3Pool manages pooled HTTP/3 (QUIC over UDP) transports keyed by target socket address (dialAddr(edge)).
type h3Pool struct {
	mu  sync.Mutex
	trs map[string]*http3.Transport
}

func newH3Pool() *h3Pool {
	return &h3Pool{trs: map[string]*http3.Transport{}}
}

func (p *h3Pool) transportFor(c *relayClient, edge string) *http3.Transport {
	target := c.dialAddr(edge)
	p.mu.Lock()
	defer p.mu.Unlock()
	if tr, ok := p.trs[target]; ok {
		return tr
	}
	tlsCfg := c.tlsConfig()
	tlsCfg.NextProtos = []string{http3.NextProtoH3}
	qCfg := &quic.Config{
		HandshakeIdleTimeout: 3 * time.Second,
		KeepAlivePeriod:      20 * time.Second,
		MaxIdleTimeout:       90 * time.Second,
	}
	dialFn := c.env.quicDial
	tr := &http3.Transport{
		TLSClientConfig: tlsCfg,
		QUICConfig:      qCfg,
		Dial: func(ctx context.Context, _ string, tc *tls.Config, qc *quic.Config) (*quic.Conn, error) {
			if dialFn != nil {
				return dialFn(ctx, target, tc, qc)
			}
			return quic.DialAddrEarly(ctx, target, tc, qc)
		},
	}
	p.trs[target] = tr
	return tr
}

func (p *h3Pool) closeAll() {
	p.mu.Lock()
	trs := p.trs
	p.trs = map[string]*http3.Transport{}
	p.mu.Unlock()
	for _, tr := range trs {
		_ = tr.Close()
	}
}
