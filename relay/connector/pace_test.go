package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"io"
	"net"
	"testing"
	"time"
)

func ed25519Verify(pub ed25519.PublicKey, msg, sig []byte) bool { return ed25519.Verify(pub, msg, sig) }

// fakeClock advances only when the bucket sleeps.
type fakeClock struct{ t time.Time }

func (f *fakeClock) now() time.Time { return f.t }
func (f *fakeClock) sleep(_ context.Context, d time.Duration) error {
	f.t = f.t.Add(d)
	return nil
}

func clockedBucket(p pace) (*bucket, *fakeClock) {
	clk := &fakeClock{t: time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)}
	b := newBucket(p)
	b.now, b.sleep, b.last = clk.now, clk.sleep, clk.t
	return b, clk
}

func TestBucketRateAndBurst(t *testing.T) {
	// 1000 kbit/s = 125,000 B/s, burst 2048 KiB.
	b, clk := clockedBucket(pace{Kbps: 1000, BurstKb: 2048})
	start := clk.t
	ctx := context.Background()
	// The burst goes out immediately.
	sent := 0
	for sent < 2048*1024 {
		if err := b.take(ctx, 64<<10); err != nil {
			t.Fatal(err)
		}
		sent += 64 << 10
	}
	if !clk.t.Equal(start) {
		t.Fatalf("the burst should not wait, waited %s", clk.t.Sub(start))
	}
	// 10 s more of data at the sustained rate takes about 10 s.
	const extra = 1_250_000
	for sent2 := 0; sent2 < extra; sent2 += 64 << 10 {
		_ = b.take(ctx, 64<<10)
	}
	waited := clk.t.Sub(start).Seconds()
	if waited < 9.5 || waited > 11 {
		t.Fatalf("1.25 MB after the burst took %.2fs, want about 10s", waited)
	}
}

func TestBucketStaysUnderRelayLimit(t *testing.T) {
	// The relay closes a session that moves more than 1.5 × (kbps×10/8×1000 + burstKb×1024) bytes in 10 s.
	p := pace{Kbps: 1000, BurstKb: 2048}
	b, clk := clockedBucket(p)
	start := clk.t
	ctx := context.Background()
	var windowBytes int
	for clk.t.Sub(start) < 10*time.Second {
		_ = b.take(ctx, 64<<10)
		if clk.t.Sub(start) < 10*time.Second {
			windowBytes += 64 << 10
		}
	}
	limit := float64(p.Kbps*10/8*1000+p.BurstKb*1024) * 1.5
	if float64(windowBytes) > limit {
		t.Fatalf("%d bytes in 10s exceeds the relay limit %.0f", windowBytes, limit)
	}
}

func TestBucketUnlimitedAndTinyBurst(t *testing.T) {
	b, clk := clockedBucket(pace{})
	start := clk.t
	_ = b.take(context.Background(), 1<<30)
	if !clk.t.Equal(start) || b.frameLimit() != maxBinaryFrame {
		t.Fatal("kbps 0 means unlimited")
	}
	small, _ := clockedBucket(pace{Kbps: 100, BurstKb: 1})
	if small.frameLimit() > 4096 || small.frameLimit() <= 0 {
		t.Fatalf("frame limit for a tiny burst = %d", small.frameLimit())
	}
	big, _ := clockedBucket(pace{Kbps: 100, BurstKb: 4096})
	if big.frameLimit() != maxBinaryFrame {
		t.Fatalf("frame limit must be capped at 64 KiB, got %d", big.frameLimit())
	}
}

func TestBucketCancel(t *testing.T) {
	b := newBucket(pace{Kbps: 8, BurstKb: 1}) // 1000 B/s
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_ = b.take(context.Background(), 4096) // drain
	if err := b.take(ctx, 1<<20); err == nil {
		t.Fatal("a cancelled context must end the wait")
	}
}

func TestReadFrameCoalescesSmallWrites(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	go func() {
		for i := 0; i < 5; i++ {
			_, _ = b.Write(bytes.Repeat([]byte{byte('a' + i)}, 100))
			time.Sleep(time.Millisecond)
		}
	}()
	buf := make([]byte, maxBinaryFrame)
	start := time.Now()
	n, err := readFrame(a, buf)
	if err != nil {
		t.Fatal(err)
	}
	if n != 500 {
		t.Fatalf("frame has %d bytes, want the five small writes joined (500)", n)
	}
	if d := time.Since(start); d > 200*time.Millisecond {
		t.Fatalf("coalescing took %s, must stay near 10 ms", d)
	}
}

func TestReadFrameSendsLoneKeystrokeWithin10ms(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	go func() {
		time.Sleep(50 * time.Millisecond)
		_, _ = b.Write([]byte("x"))
	}()
	buf := make([]byte, maxBinaryFrame)
	start := time.Now()
	n, err := readFrame(a, buf)
	if err != nil || n != 1 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	// The wait for the keystroke itself is not coalescing; after it arrived the frame must leave within ~10 ms.
	if d := time.Since(start); d < 50*time.Millisecond || d > 150*time.Millisecond {
		t.Fatalf("frame left after %s", d)
	}
}

func TestReadFrameBigDataIsNotDelayedAndRespectsLimit(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	payload := bytes.Repeat([]byte{7}, 200<<10)
	go func() { _, _ = b.Write(payload); b.Close() }()
	buf := make([]byte, maxBinaryFrame)
	total := 0
	for {
		n, err := readFrame(a, buf)
		if n > maxBinaryFrame {
			t.Fatalf("frame of %d bytes exceeds 64 KiB", n)
		}
		total += n
		if err != nil {
			if err != io.EOF {
				t.Fatal(err)
			}
			break
		}
	}
	if total != len(payload) {
		t.Fatalf("got %d of %d bytes", total, len(payload))
	}
}

func TestReadFrameReturnsDataBeforeError(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	go func() { _, _ = b.Write([]byte("tail")); b.Close() }()
	buf := make([]byte, maxBinaryFrame)
	n, err := readFrame(a, buf)
	if string(buf[:n]) != "tail" {
		t.Fatalf("lost the last bytes: %q", buf[:n])
	}
	if err != nil && err != io.EOF {
		t.Fatal(err)
	}
}
