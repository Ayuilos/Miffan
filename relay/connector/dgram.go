package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	dgramHeaderSize = 17
	dgramMaxPacket  = 2048
	dgramMaxPayload = dgramMaxPacket - dgramHeaderSize
	dgramTokenSize  = 16

	dgramFlowVideo byte = 0x01
	dgramFlowCtrl  byte = 0x02
	dgramFlowAudio byte = 0x03
	dgramFlowAck   byte = 0xFE
	dgramFlowProbe byte = 0xFF

	dgramKeepaliveInterval = 15 * time.Second
	dgramProbeTimeout      = 2500 * time.Millisecond
	dgramProbeRetry        = 250 * time.Millisecond
	dgramSocketBufBytes    = 1 << 20
)

type udpEndpoint struct {
	Port  int    `json:"port"`
	Host  string `json:"host"`
	Token string `json:"token"`
}

type dgramFlowSocket struct {
	flow   byte
	target *net.UDPAddr
	sock   *net.UDPConn
}

var defaultSunshineUDPTargets = map[byte]string{
	dgramFlowVideo: "127.0.0.1:47998",
	dgramFlowCtrl:  "127.0.0.1:47999",
	dgramFlowAudio: "127.0.0.1:48000",
}

func tuneUDPSocket(conn *net.UDPConn) {
	if conn == nil {
		return
	}
	disableUDPConnReset(conn)
	_ = conn.SetReadBuffer(dgramSocketBufBytes)
	_ = conn.SetWriteBuffer(dgramSocketBufBytes)
}

func resolveSunshineFlowTargets(overrides map[byte]string) (map[byte]*net.UDPAddr, error) {
	out := make(map[byte]*net.UDPAddr, 3)
	for _, flow := range []byte{dgramFlowVideo, dgramFlowCtrl, dgramFlowAudio} {
		raw := defaultSunshineUDPTargets[flow]
		if overrides != nil {
			if custom, ok := overrides[flow]; ok && custom != "" {
				raw = custom
			}
		}
		addr, err := net.ResolveUDPAddr("udp4", raw)
		if err != nil {
			return nil, err
		}
		if addr.IP == nil || !addr.IP.IsLoopback() {
			return nil, errors.New("UDP flow target must be loopback")
		}
		out[flow] = addr
	}
	return out, nil
}

func openFlowSockets(targets map[byte]*net.UDPAddr) (map[byte]*dgramFlowSocket, error) {
	flows := make(map[byte]*dgramFlowSocket, len(targets))
	cleanup := func() {
		for _, f := range flows {
			_ = f.sock.Close()
		}
	}
	for flow, target := range targets {
		sock, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
		if err != nil {
			cleanup()
			return nil, err
		}
		tuneUDPSocket(sock)
		flows[flow] = &dgramFlowSocket{
			flow:   flow,
			target: target,
			sock:   sock,
		}
	}
	return flows, nil
}

// probeRelayUDP verifies reachability to the relay's UDP port using a zero-amplification 0xFF/0xFE
// echo and binds this connector's UDP address/port on the relay before streaming starts.
func probeRelayUDP(ctx context.Context, relayConn *net.UDPConn, token [dgramTokenSize]byte, timeout time.Duration) error {
	var nonce [8]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return err
	}
	var req [dgramHeaderSize + len(nonce)]byte
	copy(req[:dgramTokenSize], token[:])
	req[dgramTokenSize] = dgramFlowProbe
	copy(req[dgramHeaderSize:], nonce[:])

	var buf [dgramMaxPacket]byte
	deadline := time.Now().Add(timeout)
	defer func() { _ = relayConn.SetReadDeadline(time.Time{}) }()

	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if _, err := relayConn.Write(req[:]); err != nil && !isTransientUDPReadErr(err) {
			return err
		}
		waitUntil := time.Now().Add(dgramProbeRetry)
		if waitUntil.After(deadline) {
			waitUntil = deadline
		}
		for time.Now().Before(waitUntil) {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			_ = relayConn.SetReadDeadline(waitUntil)
			n, err := relayConn.Read(buf[:])
			if err != nil {
				var ne net.Error
				if errors.As(err, &ne) && ne.Timeout() {
					break
				}
				if isTransientUDPReadErr(err) {
					if sleepCtx(ctx, 25*time.Millisecond) != nil {
						return ctx.Err()
					}
					break
				}
				return err
			}
			if n == len(req) &&
				bytes.Equal(buf[:dgramTokenSize], token[:]) &&
				buf[dgramTokenSize] == dgramFlowAck &&
				bytes.Equal(buf[dgramHeaderSize:n], nonce[:]) {
				return nil
			}
		}
		if !time.Now().Before(deadline) {
			return errors.New("UDP probe timed out")
		}
	}
}

func (c *connector) sessionDatagram(
	ctx context.Context,
	client *relayClient,
	ws relayConn,
	wmu *sync.Mutex,
	closeWith func(int, string),
	short, deviceID string,
	start time.Time,
	p pace,
	udpInfo *udpEndpoint,
) {
	if udpInfo == nil || udpInfo.Port <= 0 || udpInfo.Port > 65535 {
		closeWith(closeError, "internal")
		return
	}
	tokenRaw, err := b64u.DecodeString(udpInfo.Token)
	if err != nil || len(tokenRaw) != dgramTokenSize {
		closeWith(closeError, "internal")
		return
	}
	var hostToken [dgramTokenSize]byte
	copy(hostToken[:], tokenRaw)

	targets, err := resolveSunshineFlowTargets(c.env.sunshineUDPTargets)
	if err != nil {
		closeWith(closeError, "internal")
		return
	}
	flows, err := openFlowSockets(targets)
	if err != nil {
		c.log.printf("session %s: cannot open local UDP sockets: %v", short, err)
		closeWith(closeError, "internal")
		return
	}
	defer func() {
		for _, f := range flows {
			_ = f.sock.Close()
		}
	}()

	host := strings.TrimSpace(udpInfo.Host)
	if host == "" {
		host = client.hostname()
	}
	relayAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(udpInfo.Port)))
	if err != nil {
		c.log.printf("session %s: cannot resolve relay UDP address %s:%d: %v", short, host, udpInfo.Port, err)
		closeWith(closeError, "internal")
		return
	}
	relayConn, err := net.DialUDP("udp", nil, relayAddr)
	if err != nil {
		c.log.printf("session %s: cannot open relay UDP socket: %v", short, err)
		closeWith(closeError, "internal")
		return
	}
	defer relayConn.Close()
	tuneUDPSocket(relayConn)

	probeTimeout := dgramProbeTimeout
	if c.timing.handshake > 0 && c.timing.handshake < probeTimeout {
		probeTimeout = c.timing.handshake
	}
	if err := probeRelayUDP(ctx, relayConn, hostToken, probeTimeout); err != nil {
		c.log.printf("session %s: relay UDP unreachable (%s:%d): %v", short, host, udpInfo.Port, err)
		closeWith(closeError, "udp_unreachable")
		return
	}

	okMsg, _ := jsonMarshalOk()
	wmu.Lock()
	_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
	err = ws.WriteMessage(websocket.TextMessage, okMsg)
	wmu.Unlock()
	if err != nil {
		return
	}

	c.log.printf("session %s ch=media-dgram device=%s up in %s", short, deviceID[:6], time.Since(start).Round(time.Millisecond))
	up, down := c.forwardDatagram(ctx, ws, wmu, relayConn, hostToken, flows, p)
	c.log.printf("session %s closed after %s: %d B to relay, %d B from relay", short, time.Since(start).Round(time.Second), up, down)
}

func jsonMarshalOk() ([]byte, error) {
	return []byte(`{"t":"ok"}`), nil
}

func (c *connector) forwardDatagram(
	ctx context.Context,
	ws relayConn,
	wmu *sync.Mutex,
	relayConn *net.UDPConn,
	hostToken [dgramTokenSize]byte,
	flows map[byte]*dgramFlowSocket,
	p pace,
) (int64, int64) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	var toRelay, fromRelay atomic.Int64
	var once sync.Once
	var closeCode = closeNormal
	var closeText = "eof"
	finish := func(code int, text string) {
		once.Do(func() {
			closeCode, closeText = code, text
			cancel()
			_ = relayConn.Close()
			for _, f := range flows {
				_ = f.sock.Close()
			}
			_ = ws.SetReadDeadline(time.Now())
		})
	}

	var wg sync.WaitGroup

	// Unblock blocking socket reads if the parent context is cancelled.
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		finish(closeNormal, "eof")
	}()

	// Control stream reader: handles pong and relay/app closure notifications.
	wg.Add(1)
	go func() {
		defer wg.Done()
		ws.SetReadLimit(maxTextFrame)
		for {
			_ = ws.SetReadDeadline(time.Now().Add(c.timing.readIdle))
			kind, data, err := ws.ReadMessage()
			if err != nil {
				finish(closeNormal, "peer_closed")
				return
			}
			if kind == websocket.TextMessage && string(data) == "pong" {
				continue
			}
			finish(closeNormal, "peer_closed")
			return
		}
	}()

	// Control stream ping keepalive (PROTOCOL §5.4).
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

	// UDP NAT keepalive (PROTOCOL §5.5): empty 0xFF every 15s.
	wg.Add(1)
	go func() {
		defer wg.Done()
		var ka [dgramHeaderSize]byte
		copy(ka[:dgramTokenSize], hostToken[:])
		ka[dgramTokenSize] = dgramFlowProbe
		t := time.NewTicker(dgramKeepaliveInterval)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if _, err := relayConn.Write(ka[:]); err != nil && errors.Is(err, net.ErrClosed) {
					return
				}
			}
		}
	}()

	// relay UDP -> local Sunshine UDP ports.
	wg.Add(1)
	go func() {
		defer wg.Done()
		buf := make([]byte, dgramMaxPacket)
		for ctx.Err() == nil {
			n, err := relayConn.Read(buf)
			if err != nil {
				if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
					return
				}
				if isTransientUDPReadErr(err) {
					if sleepCtx(ctx, 10*time.Millisecond) != nil {
						return
					}
					continue
				}
				finish(closeNormal, "peer_closed")
				return
			}
			if n < dgramHeaderSize || !bytes.Equal(buf[:dgramTokenSize], hostToken[:]) {
				continue
			}
			flow := buf[dgramTokenSize]
			if flow == dgramFlowAck {
				continue
			}
			f, ok := flows[flow]
			if !ok {
				continue
			}
			payload := buf[dgramHeaderSize:n]
			if len(payload) == 0 {
				continue
			}
			if _, err := f.sock.WriteToUDP(payload, f.target); err == nil {
				fromRelay.Add(int64(len(payload)))
			}
		}
	}()

	// local Sunshine UDP -> relay UDP (video 0x01 uses non-blocking bucket; 0x02/0x03 never block).
	bkt := newBucket(p)
	for _, f := range flows {
		f := f
		wg.Add(1)
		go func() {
			defer wg.Done()
			pkt := make([]byte, dgramMaxPacket+1)
			copy(pkt[:dgramTokenSize], hostToken[:])
			pkt[dgramTokenSize] = f.flow
			for ctx.Err() == nil {
				n, src, err := f.sock.ReadFromUDP(pkt[dgramHeaderSize:])
				if err != nil {
					if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
						return
					}
					if isTransientUDPReadErr(err) {
						if sleepCtx(ctx, 10*time.Millisecond) != nil {
							return
						}
						continue
					}
					var ne net.Error
					if errors.As(err, &ne) && ne.Timeout() {
						continue
					}
					continue
				}
				if n <= 0 || n > dgramMaxPayload {
					continue
				}
				if src == nil || !src.IP.IsLoopback() || src.Port != f.target.Port {
					continue
				}
				total := dgramHeaderSize + n
				if f.flow == dgramFlowVideo && !bkt.tryTake(total) {
					continue
				}
				if _, err := relayConn.Write(pkt[:total]); err == nil {
					toRelay.Add(int64(n))
				}
			}
		}()
	}

	wg.Wait()
	_ = ws.WriteClose(closeCode, closeText, time.Now().Add(time.Second))
	_ = ws.Close()
	return toRelay.Load(), fromRelay.Load()
}
