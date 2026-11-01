package main

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

const (
	maxBinaryFrame = 64 << 10 // PROTOCOL §5.3
	maxTextFrame   = 4 << 10
	coalesceBelow  = 16 << 10
	coalesceWait   = 10 * time.Millisecond
)

// pace is the {"kbps","burstKb"} object of an open message: kbps in kilobits per second, burstKb in KiB.
type pace struct {
	Kbps    int `json:"kbps"`
	BurstKb int `json:"burstKb"`
}

// bucket is a token bucket over bytes. It starts full. take may overdraw so a frame larger than the
// burst can still go out; the debt is then paid off before the next frame, so the average rate holds.
// A zero rate means unlimited.
type bucket struct {
	mu     sync.Mutex
	rate   float64 // bytes per second
	burst  float64
	tokens float64
	last   time.Time
	now    func() time.Time
	sleep  func(ctx context.Context, d time.Duration) error
}

func newBucket(p pace) *bucket {
	b := &bucket{now: time.Now, sleep: sleepCtx}
	b.rate = float64(p.Kbps) * 1000 / 8
	b.burst = float64(p.BurstKb) * 1024
	if b.rate > 0 && b.burst < 4096 {
		b.burst = 4096
	}
	b.tokens = b.burst
	b.last = b.now()
	return b
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// frameLimit is the biggest frame worth reading in one go: never above 64 KiB, and for slow links no
// bigger than the burst, so one frame cannot blow through the burst allowance.
func (b *bucket) frameLimit() int {
	if b.rate <= 0 || b.burst >= maxBinaryFrame {
		return maxBinaryFrame
	}
	return int(b.burst)
}

// take accounts for n bytes about to be sent and waits as long as the rate requires.
func (b *bucket) take(ctx context.Context, n int) error {
	if b.rate <= 0 {
		return nil
	}
	b.mu.Lock()
	now := b.now()
	b.tokens += now.Sub(b.last).Seconds() * b.rate
	if b.tokens > b.burst {
		b.tokens = b.burst
	}
	b.last = now
	b.tokens -= float64(n)
	var wait time.Duration
	if b.tokens < 0 {
		wait = time.Duration(-b.tokens / b.rate * float64(time.Second))
	}
	b.mu.Unlock()
	if wait <= 0 {
		return nil
	}
	return b.sleep(ctx, wait)
}

type deadlineReader interface {
	io.Reader
	SetReadDeadline(time.Time) error
}

// readFrame reads one outgoing frame from r into buf (len(buf) is the frame limit). The first Read
// blocks for as long as there is nothing to send. Below 16 KiB it then keeps reading for up to 10 ms
// from the first byte, so a burst leaves as one frame while a lone keystroke still goes out within
// 10 ms. A pending error is returned together with the bytes read before it.
func readFrame(r deadlineReader, buf []byte) (int, error) {
	n, err := r.Read(buf)
	if err != nil || n >= coalesceBelow || n >= len(buf) {
		return n, err
	}
	deadline := time.Now().Add(coalesceWait)
	for n < len(buf) {
		if err := r.SetReadDeadline(deadline); err != nil {
			break
		}
		k, rerr := r.Read(buf[n:])
		n += k
		if rerr != nil {
			var ne net.Error
			if errors.As(rerr, &ne) && ne.Timeout() {
				break
			}
			_ = r.SetReadDeadline(time.Time{})
			return n, rerr
		}
		if !time.Now().Before(deadline) {
			break
		}
	}
	_ = r.SetReadDeadline(time.Time{})
	return n, nil
}
