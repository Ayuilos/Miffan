// R0 spike tool for the Miffan relay (protocol v0).
//
//	relay-spike serve -relay wss://… -host <id> [-target 127.0.0.1:22]   computer side
//	relay-spike dial  -relay wss://… -host <id> [-ch control]             ssh ProxyCommand
//	relay-spike bench -relay wss://… -host <id> [-ch control]             measurements (needs a serve with -target bench)
//
// The dev token comes from RELAY_DEV_TOKEN. -connect-ip dials that IP but keeps the TLS name,
// for Android shells without a usable resolver.
package main

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type opts struct {
	relay, host, ch, target, connectIP, hint string
	coalesce                                 time.Duration
	token                                    string
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: relay-spike serve|dial|bench [flags]")
		os.Exit(2)
	}
	fs := flag.NewFlagSet(os.Args[1], flag.ExitOnError)
	o := opts{token: os.Getenv("RELAY_DEV_TOKEN")}
	fs.StringVar(&o.relay, "relay", "wss://relay-dev.miffan.ayuilos.me", "relay base URL")
	fs.StringVar(&o.host, "host", "", "host id")
	fs.StringVar(&o.ch, "ch", "control", "channel (control|media)")
	fs.StringVar(&o.target, "target", "127.0.0.1:22", "serve: TCP target, or \"bench\"")
	fs.StringVar(&o.connectIP, "connect-ip", "", "dial this IP instead of resolving the relay host")
	fs.StringVar(&o.hint, "hint", "", "Durable Object location hint used when the host object is first created")
	fs.DurationVar(&o.coalesce, "coalesce", 0, "wait up to this long to fill a frame before sending")
	rounds := fs.Int("rounds", 50, "bench: echo rounds")
	downMB := fs.Int("down", 20, "bench: download MB")
	upMB := fs.Int("up", 10, "bench: upload MB")
	idleFor := fs.Duration("for", 3*time.Minute, "idle: how long to hold the session")
	dataEvery := fs.Duration("data-every", 0, "idle: send one echo this often (0 = never)")
	_ = fs.Parse(os.Args[2:])
	if o.host == "" || o.token == "" {
		log.Fatal("-host and RELAY_DEV_TOKEN are required")
	}
	switch os.Args[1] {
	case "serve":
		serve(o)
	case "dial":
		dial(o)
	case "bench":
		bench(o, *rounds, *downMB, *upMB)
	case "idle":
		idle(o, *idleFor, *dataEvery)
	case "wecho":
		workerEcho(o, *rounds)
	default:
		log.Fatalf("unknown command %q", os.Args[1])
	}
}

func dialer(o opts) *websocket.Dialer {
	d := &websocket.Dialer{HandshakeTimeout: 15 * time.Second, ReadBufferSize: 64 << 10, WriteBufferSize: 64 << 10, Proxy: http.ProxyFromEnvironment}
	if o.connectIP != "" {
		u, _ := url.Parse(o.relay)
		d.TLSClientConfig = &tls.Config{ServerName: u.Hostname()}
		d.NetDialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			_, port, _ := net.SplitHostPort(addr)
			return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(o.connectIP, port))
		}
		d.Proxy = nil
	}
	return d
}

func open(o opts, path string) (*websocket.Conn, error) {
	h := http.Header{}
	h.Set("Authorization", "Bearer "+o.token)
	if o.hint != "" {
		sep := "?"
		if strings.Contains(path, "?") {
			sep = "&"
		}
		path += sep + "hint=" + url.QueryEscape(o.hint)
	}
	c, res, err := dialer(o).Dial(strings.TrimRight(o.relay, "/")+path, h)
	if err != nil && res != nil {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return nil, fmt.Errorf("%s: HTTP %d %s", path, res.StatusCode, strings.TrimSpace(string(body)))
	}
	return c, err
}

// ---------- computer side ----------

func serve(o opts) {
	backoff := time.Second
	for {
		start := time.Now()
		err := serveOnce(o)
		if time.Since(start) > time.Minute {
			backoff = time.Second
		}
		log.Printf("control socket ended: %v; retry in %s", err, backoff)
		time.Sleep(backoff)
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func serveOnce(o opts) error {
	c, err := open(o, "/v0/host/"+o.host)
	if err != nil {
		return err
	}
	defer c.Close()
	log.Printf("registered %s", o.host)
	var wmu sync.Mutex
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				wmu.Lock()
				_ = c.WriteMessage(websocket.TextMessage, []byte("ping"))
				wmu.Unlock()
			}
		}
	}()
	for {
		kind, data, err := c.ReadMessage()
		if err != nil {
			return err
		}
		if kind != websocket.TextMessage || string(data) == "pong" {
			continue
		}
		var m struct{ T, Sid, Ch string }
		if json.Unmarshal(data, &m) != nil || m.T != "open" {
			continue
		}
		go session(o, m.Sid, m.Ch)
	}
}

func session(o opts, sid, ch string) {
	t0 := time.Now()
	ws, err := open(o, "/v0/host/"+o.host+"/s/"+sid)
	if err != nil {
		log.Printf("session %s: %v", sid[:8], err)
		return
	}
	defer ws.Close()
	var local io.ReadWriteCloser
	if o.target == "bench" {
		a, b := net.Pipe()
		go benchService(b)
		local = a
	} else {
		local, err = net.DialTimeout("tcp", o.target, 5*time.Second)
		if err != nil {
			log.Printf("session %s: %v", sid[:8], err)
			return
		}
	}
	log.Printf("session %s ch=%s up in %s", sid[:8], ch, time.Since(t0).Round(time.Millisecond))
	up, down := pipe(ws, local, o.coalesce)
	log.Printf("session %s closed after %s: to-relay %d B in %d frames, from-relay %d B", sid[:8], time.Since(t0).Round(time.Second), up.bytes, up.frames, down)
}

// benchService runs commands until EOF: 'p' echo 64 bytes, 'd'+u64 send N bytes, 'u'+u64 read N bytes then ack 'k'.
func benchService(c net.Conn) {
	defer c.Close()
	cmd := make([]byte, 1)
	buf := make([]byte, 64<<10)
	for {
		if _, err := io.ReadFull(c, cmd); err != nil {
			return
		}
		switch cmd[0] {
		case 'p':
			if _, err := io.ReadFull(c, buf[:64]); err != nil {
				return
			}
			if _, err := c.Write(buf[:64]); err != nil {
				return
			}
		case 'd':
			for n := readLen(c); n > 0; {
				k := uint64(len(buf))
				if n < k {
					k = n
				}
				if _, err := c.Write(buf[:k]); err != nil {
					return
				}
				n -= k
			}
		case 'u':
			if _, err := io.CopyN(io.Discard, c, int64(readLen(c))); err != nil {
				return
			}
			if _, err := c.Write([]byte{'k'}); err != nil {
				return
			}
		default:
			return
		}
	}
}

func readLen(r io.Reader) uint64 {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0
	}
	return binary.BigEndian.Uint64(b[:])
}

type sent struct{ bytes, frames int64 }

// pipe copies both ways until either side ends. Returns what went to the relay and what came from it.
func pipe(ws *websocket.Conn, local io.ReadWriteCloser, coalesce time.Duration) (sent, int64) {
	var out sent
	var in int64
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 64<<10)
		for {
			n, err := readFrame(local, buf, coalesce)
			if n > 0 {
				if werr := ws.WriteMessage(websocket.BinaryMessage, buf[:n]); werr != nil {
					return
				}
				out.bytes += int64(n)
				out.frames++
			}
			if err != nil {
				_ = ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(1000, "eof"), time.Now().Add(time.Second))
				return
			}
		}
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		for {
			kind, data, err := ws.ReadMessage()
			if err != nil {
				return
			}
			if kind != websocket.BinaryMessage {
				continue
			}
			in += int64(len(data))
			if _, err := local.Write(data); err != nil {
				return
			}
		}
	}()
	<-done
	_ = local.Close()
	_ = ws.Close()
	<-done
	return out, in
}

type deadliner interface{ SetReadDeadline(time.Time) error }

// readFrame reads at least one byte, then keeps filling buf for up to coalesce.
func readFrame(r io.Reader, buf []byte, coalesce time.Duration) (int, error) {
	n, err := r.Read(buf)
	dl, ok := r.(deadliner)
	if err != nil || coalesce <= 0 || !ok || n >= len(buf)/4 {
		return n, err
	}
	_ = dl.SetReadDeadline(time.Now().Add(coalesce))
	defer dl.SetReadDeadline(time.Time{})
	for n < len(buf) {
		k, err := r.Read(buf[n:])
		n += k
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				return n, nil
			}
			return n, err
		}
	}
	return n, nil
}

// ---------- phone side ----------

func connect(o opts) (*websocket.Conn, map[string]any, time.Duration, error) {
	t0 := time.Now()
	ws, err := open(o, "/v0/connect/"+o.host+"?ch="+o.ch)
	if err != nil {
		return nil, nil, 0, err
	}
	_ = ws.SetReadDeadline(time.Now().Add(15 * time.Second))
	kind, data, err := ws.ReadMessage()
	_ = ws.SetReadDeadline(time.Time{})
	if err != nil || kind != websocket.TextMessage {
		ws.Close()
		return nil, nil, 0, fmt.Errorf("waiting for ready: %v", err)
	}
	var ready map[string]any
	_ = json.Unmarshal(data, &ready)
	return ws, ready, time.Since(t0), nil
}

type wsConn struct {
	ws  *websocket.Conn
	buf []byte
}

func (c *wsConn) Read(p []byte) (int, error) {
	for len(c.buf) == 0 {
		kind, data, err := c.ws.ReadMessage()
		if err != nil {
			return 0, io.EOF
		}
		if kind == websocket.BinaryMessage {
			c.buf = data
		}
	}
	n := copy(p, c.buf)
	c.buf = c.buf[n:]
	return n, nil
}

func (c *wsConn) Write(p []byte) (int, error) {
	if err := c.ws.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func dial(o opts) {
	ws, _, _, err := connect(o)
	if err != nil {
		log.Fatal(err)
	}
	c := &wsConn{ws: ws}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(os.Stdout, c); done <- struct{}{} }()
	go func() {
		buf := make([]byte, 64<<10)
		for {
			n, err := os.Stdin.Read(buf)
			if n > 0 {
				if _, werr := c.Write(buf[:n]); werr != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
	ws.Close()
}

func lenCmd(cmd byte, n uint64) []byte {
	b := make([]byte, 9)
	b[0] = cmd
	binary.BigEndian.PutUint64(b[1:], n)
	return b
}

func stats(v []float64) map[string]float64 {
	sort.Float64s(v)
	return map[string]float64{"min": v[0], "p50": v[len(v)/2], "p90": v[len(v)*9/10], "max": v[len(v)-1]}
}

func bench(o opts, rounds, downMB, upMB int) {
	result := map[string]any{}
	pingT0 := time.Now()
	if res, err := http.Get(strings.Replace(strings.TrimRight(o.relay, "/"), "wss://", "https://", 1) + "/v0/ping"); err == nil {
		var p map[string]any
		_ = json.NewDecoder(res.Body).Decode(&p)
		res.Body.Close()
		result["edgeHttpMs"] = time.Since(pingT0).Milliseconds()
		result["edgeColo"] = p["colo"]
	}

	var setups []float64
	for i := 0; i < 5; i++ {
		ws, ready, setup, err := connect(o)
		if err != nil {
			log.Fatal(err)
		}
		setups = append(setups, float64(setup.Milliseconds()))
		result["ready"] = ready
		ws.Close()
	}
	result["sessionSetupMs"] = stats(setups)

	ws, _, _, err := connect(o)
	if err != nil {
		log.Fatal(err)
	}
	defer ws.Close()
	c := &wsConn{ws: ws}
	msg := append([]byte{'p'}, make([]byte, 64)...)
	got := make([]byte, 64)
	var rtts []float64
	for i := 0; i < rounds; i++ {
		t := time.Now()
		_, _ = c.Write(msg)
		if _, err := io.ReadFull(c, got); err != nil {
			log.Fatal(err)
		}
		rtts = append(rtts, float64(time.Since(t).Microseconds())/1000)
		time.Sleep(100 * time.Millisecond)
	}
	result["echoRttMs"] = stats(rtts)

	var shots []float64
	for i := 0; i < 10; i++ {
		t := time.Now()
		_, _ = c.Write(lenCmd('d', 250<<10))
		if _, err := io.CopyN(io.Discard, c, 250<<10); err != nil {
			log.Fatal(err)
		}
		shots = append(shots, float64(time.Since(t).Milliseconds()))
		time.Sleep(500 * time.Millisecond)
	}
	result["screenshot250KBMs"] = stats(shots)

	if downMB > 0 {
		t := time.Now()
		_, _ = c.Write(lenCmd('d', uint64(downMB)<<20))
		if _, err := io.CopyN(io.Discard, c, int64(downMB)<<20); err != nil {
			log.Fatal(err)
		}
		result["downMbps"] = float64(downMB*8) / time.Since(t).Seconds()
	}
	if upMB > 0 {
		t := time.Now()
		_, _ = c.Write(lenCmd('u', uint64(upMB)<<20))
		chunk := make([]byte, 64<<10)
		for sentBytes := 0; sentBytes < upMB<<20; sentBytes += len(chunk) {
			if _, err := c.Write(chunk); err != nil {
				log.Fatal(err)
			}
		}
		ack := make([]byte, 1)
		if _, err := io.ReadFull(c, ack); err != nil {
			log.Fatal(err)
		}
		result["upMbps"] = float64(upMB*8) / time.Since(t).Seconds()
	}
	out, _ := json.MarshalIndent(result, "", "  ")
	fmt.Println(string(out))
}

// workerEcho measures WebSocket round trips to the Worker itself (no Durable Object, no connector).
func workerEcho(o opts, rounds int) {
	ws, err := open(o, "/v0/echo/"+o.host)
	if err != nil {
		log.Fatal(err)
	}
	defer ws.Close()
	msg := make([]byte, 64)
	var rtts []float64
	for i := 0; i < rounds; i++ {
		t := time.Now()
		if err := ws.WriteMessage(websocket.BinaryMessage, msg); err != nil {
			log.Fatal(err)
		}
		if _, _, err := ws.ReadMessage(); err != nil {
			log.Fatal(err)
		}
		rtts = append(rtts, float64(time.Since(t).Microseconds())/1000)
		time.Sleep(100 * time.Millisecond)
	}
	out, _ := json.Marshal(stats(rtts))
	fmt.Println(string(out))
}

// idle holds one session open with text "ping" keepalives every 30 s (answered by the relay without
// waking the object) and optionally one echo through the connector every dataEvery.
func idle(o opts, d, dataEvery time.Duration) {
	ws, _, _, err := connect(o)
	if err != nil {
		log.Fatal(err)
	}
	defer ws.Close()
	msgs := make(chan []byte, 16)
	go func() {
		for {
			kind, data, err := ws.ReadMessage()
			if err != nil {
				close(msgs)
				return
			}
			if kind == websocket.BinaryMessage {
				msgs <- data
			}
		}
	}()
	end := time.Now().Add(d)
	keep := time.NewTicker(30 * time.Second)
	defer keep.Stop()
	var data <-chan time.Time
	if dataEvery > 0 {
		t := time.NewTicker(dataEvery)
		defer t.Stop()
		data = t.C
	}
	echoes := 0
	for time.Now().Before(end) {
		select {
		case <-keep.C:
			if err := ws.WriteMessage(websocket.TextMessage, []byte("ping")); err != nil {
				log.Fatalf("keepalive: %v", err)
			}
		case <-data:
			_ = ws.WriteMessage(websocket.BinaryMessage, append([]byte{'p'}, make([]byte, 64)...))
			got := 0
			for got < 64 {
				m, ok := <-msgs
				if !ok {
					log.Fatal("session closed")
				}
				got += len(m)
			}
			echoes++
		case _, ok := <-msgs:
			if !ok {
				log.Fatal("session closed by relay")
			}
		case <-time.After(time.Until(end)):
		}
	}
	fmt.Printf("{\"heldSeconds\":%d,\"echoes\":%d}\n", int(d.Seconds()), echoes)
}
