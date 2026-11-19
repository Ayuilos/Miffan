package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"net"
	"sync"
	"testing"
	"time"
)

type fakeUDPRelay struct {
	sock       *net.UDPConn
	port       int
	hostToken  [dgramTokenSize]byte
	clientTok  [dgramTokenSize]byte
	mu         sync.Mutex
	hostAddr   *net.UDPAddr
	clientAddr *net.UDPAddr
}

func newFakeUDPRelay(t *testing.T) *fakeUDPRelay {
	t.Helper()
	sock, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	r := &fakeUDPRelay{
		sock: sock,
		port: sock.LocalAddr().(*net.UDPAddr).Port,
	}
	_, _ = rand.Read(r.hostToken[:])
	_, _ = rand.Read(r.clientTok[:])
	go r.serve()
	t.Cleanup(func() { _ = sock.Close() })
	return r
}

func (r *fakeUDPRelay) serve() {
	buf := make([]byte, dgramMaxPacket)
	for {
		n, addr, err := r.sock.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < dgramHeaderSize {
			continue
		}
		tok := buf[:dgramTokenSize]
		flow := buf[dgramTokenSize]
		isHost := bytes.Equal(tok, r.hostToken[:])
		isClient := bytes.Equal(tok, r.clientTok[:])
		if !isHost && !isClient {
			continue
		}
		r.mu.Lock()
		if isHost {
			r.hostAddr = addr
		} else {
			r.clientAddr = addr
		}
		peerAddr := r.clientAddr
		peerTok := r.clientTok
		if isClient {
			peerAddr = r.hostAddr
			peerTok = r.hostToken
		}
		r.mu.Unlock()

		if flow == dgramFlowProbe && n <= 33 {
			out := make([]byte, n)
			copy(out, buf[:n])
			out[dgramTokenSize] = dgramFlowAck
			_, _ = r.sock.WriteToUDP(out, addr)
			continue
		}
		if (flow == dgramFlowVideo || flow == dgramFlowCtrl || flow == dgramFlowAudio) && peerAddr != nil {
			out := make([]byte, n)
			copy(out, buf[:n])
			copy(out[:dgramTokenSize], peerTok[:])
			_, _ = r.sock.WriteToUDP(out, peerAddr)
		}
	}
}

func udpEchoTarget(t *testing.T) (*net.UDPConn, string, chan []byte) {
	t.Helper()
	sock, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	received := make(chan []byte, 64)
	go func() {
		buf := make([]byte, dgramMaxPacket)
		for {
			n, from, err := sock.ReadFromUDP(buf)
			if err != nil {
				return
			}
			cp := append([]byte(nil), buf[:n]...)
			select {
			case received <- cp:
			default:
			}
			reply := append([]byte("sunshine:"), cp...)
			_, _ = sock.WriteToUDP(reply, from)
		}
	}()
	t.Cleanup(func() { _ = sock.Close() })
	return sock, sock.LocalAddr().String(), received
}

func TestMediaDatagramForwardingAllFlowsAndPace(t *testing.T) {
	udpRelay := newFakeUDPRelay(t)
	_, vidAddr, _ := udpEchoTarget(t)
	_, ctrlAddr, _ := udpEchoTarget(t)
	_, audAddr, _ := udpEchoTarget(t)

	h := startHarness(t, "", func(h *harness) {
		h.env.sunshineUDPTargets = map[byte]string{
			dgramFlowVideo: vidAddr,
			dgramFlowCtrl:  ctrlAddr,
			dgramFlowAudio: audAddr,
		}
	})
	h.relay.waitRegistration(1)
	h.relay.mu.Lock()
	authDgram := h.relay.authDgram
	h.relay.mu.Unlock()
	if !authDgram {
		t.Fatalf("expected connector host auth to declare dgram: true")
	}

	sidRaw := make([]byte, 16)
	_, _ = rand.Read(sidRaw)
	sid := hex.EncodeToString(sidRaw)

	// Limit rate to 200 kbps (~25 KB/s) with 4 KiB burst so oversized video floods drop cleanly while
	// control/audio keep working without blocking.
	ws := h.relay.openSIDExtra(sid, "media-dgram", pace{Kbps: 200, BurstKb: 4}, map[string]any{
		"udp": map[string]any{
			"port":  udpRelay.port,
			"host":  "127.0.0.1",
			"token": b64u.EncodeToString(udpRelay.hostToken[:]),
		},
	})
	defer ws.Close()

	reply, reason := h.handshake(ws, sid, "media-dgram", nil)
	if reply != "ok" {
		t.Fatalf("handshake reply = %q (%q), want ok", reply, reason)
	}

	appSock, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: udpRelay.port})
	if err != nil {
		t.Fatal(err)
	}
	defer appSock.Close()

	// App probes 0xFF -> 0xFE so fakeRelay binds appAddr.
	probe := append(append([]byte{}, udpRelay.clientTok[:]...), dgramFlowProbe, 0xAA, 0xBB)
	if _, err := appSock.Write(probe); err != nil {
		t.Fatal(err)
	}
	_ = appSock.SetReadDeadline(time.Now().Add(2 * time.Second))
	ack := make([]byte, 64)
	n, err := appSock.Read(ack)
	if err != nil || n != len(probe) || ack[dgramTokenSize] != dgramFlowAck {
		t.Fatalf("app probe failed: n=%d err=%v", n, err)
	}

	// Send one packet per flow (0x01 video, 0x02 ctrl, 0x03 audio) and expect echo from each target.
	for _, tc := range []struct {
		flow byte
		body string
	}{
		{dgramFlowVideo, "vid-pkt"},
		{dgramFlowCtrl, "ctrl-pkt"},
		{dgramFlowAudio, "aud-pkt"},
	} {
		pkt := append(append([]byte{}, udpRelay.clientTok[:]...), tc.flow)
		pkt = append(pkt, tc.body...)
		if _, err := appSock.Write(pkt); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, dgramMaxPacket)
		_ = appSock.SetReadDeadline(time.Now().Add(2 * time.Second))
		rn, err := appSock.Read(buf)
		if err != nil {
			t.Fatalf("flow 0x%02x read failed: %v", tc.flow, err)
		}
		if !bytes.Equal(buf[:dgramTokenSize], udpRelay.clientTok[:]) || buf[dgramTokenSize] != tc.flow {
			t.Fatalf("unexpected header for flow 0x%02x", tc.flow)
		}
		want := "sunshine:" + tc.body
		if string(buf[dgramHeaderSize:rn]) != want {
			t.Fatalf("flow 0x%02x got %q, want %q", tc.flow, buf[dgramHeaderSize:rn], want)
		}
	}

	// Burst excess 0x01 packets back to App; verify bucket drops some while 0x02 remains unblocked.
	bigBody := bytes.Repeat([]byte("V"), 1100)
	for i := 0; i < 20; i++ {
		pkt := append(append([]byte{}, udpRelay.clientTok[:]...), dgramFlowVideo)
		pkt = append(pkt, bigBody...)
		_, _ = appSock.Write(pkt)
	}
	// Drain briefly and count how many video replies came back (4 KiB burst + ~25 KB/s should drop many).
	vidReplies := 0
	drainUntil := time.Now().Add(150 * time.Millisecond)
	tmp := make([]byte, dgramMaxPacket)
	for time.Now().Before(drainUntil) {
		_ = appSock.SetReadDeadline(drainUntil)
		n, err := appSock.Read(tmp)
		if err != nil {
			break
		}
		if n > dgramHeaderSize && tmp[dgramTokenSize] == dgramFlowVideo {
			vidReplies++
		}
	}
	if vidReplies >= 20 {
		t.Fatalf("expected video bucket to drop excess packets from 20x1100B burst, got %d replies", vidReplies)
	}

	// Audio & control still pass immediately even right after video bucket saturated.
	ctrlPkt := append(append([]byte{}, udpRelay.clientTok[:]...), dgramFlowCtrl)
	ctrlPkt = append(ctrlPkt, "still-alive"...)
	_, _ = appSock.Write(ctrlPkt)
	_ = appSock.SetReadDeadline(time.Now().Add(2 * time.Second))
	gotCtrl := make([]byte, dgramMaxPacket)
	cn, err := appSock.Read(gotCtrl)
	for err == nil && gotCtrl[dgramTokenSize] == dgramFlowVideo {
		cn, err = appSock.Read(gotCtrl)
	}
	if err != nil || gotCtrl[dgramTokenSize] != dgramFlowCtrl || string(gotCtrl[dgramHeaderSize:cn]) != "sunshine:still-alive" {
		t.Fatalf("control blocked after video burst: err=%v flow=0x%02x body=%q", err, gotCtrl[dgramTokenSize], gotCtrl[dgramHeaderSize:max(dgramHeaderSize, cn)])
	}
}

func TestMediaDatagramUnreachableUDPClosesWithReason(t *testing.T) {
	// Open and immediately close a localhost UDP port so probes get ICMP unreachable / timeout.
	deadSock, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	deadPort := deadSock.LocalAddr().(*net.UDPAddr).Port
	_ = deadSock.Close()

	_, vidAddr, _ := udpEchoTarget(t)
	_, ctrlAddr, _ := udpEchoTarget(t)
	_, audAddr, _ := udpEchoTarget(t)

	h := startHarness(t, "", func(h *harness) {
		h.env.sunshineUDPTargets = map[byte]string{
			dgramFlowVideo: vidAddr,
			dgramFlowCtrl:  ctrlAddr,
			dgramFlowAudio: audAddr,
		}
	})
	h.relay.waitRegistration(1)

	sidRaw := make([]byte, 16)
	_, _ = rand.Read(sidRaw)
	sid := hex.EncodeToString(sidRaw)
	var tok [dgramTokenSize]byte
	_, _ = rand.Read(tok[:])

	ws := h.relay.openSIDExtra(sid, "media-dgram", pace{}, map[string]any{
		"udp": map[string]any{
			"port":  deadPort,
			"host":  "127.0.0.1",
			"token": b64u.EncodeToString(tok[:]),
		},
	})
	defer ws.Close()

	reply, _ := h.handshake(ws, sid, "media-dgram", nil)
	if reply == "ok" {
		t.Fatalf("expected handshake to close instead of ok when UDP port is unreachable")
	}
}
