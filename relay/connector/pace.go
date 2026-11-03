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
	coalesceBelow  = 16 << 10 // a frame this big leaves at once

	// Adaptive coalescing (PROTOCOL §5.3). The relay's platform bills every frame, so data that trickles
	// in should leave in few frames, while a keystroke's echo should leave at once.
	quietGap     = 2 * time.Millisecond  // after a quiet spell: send once no more data comes for this long
	quietMax     = 10 * time.Millisecond // ...and in any case this long after the first byte
	streamGap    = 25 * time.Millisecond // data within this long of the previous frame means a stream
	streamWindow = 40 * time.Millisecond // a stream's frame collects for this long (or 16 KiB)
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

// framer cuts the bytes read from the local connection into WebSocket frames.
type framer struct {
	now                                       func() time.Time
	quietGap, quietMax, streamGap, streamWait time.Duration
	lastSent                                  time.Time
}

func newFramer() *framer {
	return &framer{now: time.Now, quietGap: quietGap, quietMax: quietMax, streamGap: streamGap, streamWait: streamWindow}
}

// read reads one outgoing frame from r into buf (len(buf) is the frame limit). The first Read blocks for
// as long as there is nothing to send. What happens next depends on how recently the previous frame
// left:
//   - after a quiet spell (an echo, a command's reply), more data is collected only while it keeps
//     coming within 2 ms, and for at most 10 ms, so a lone keystroke's echo goes out within about 2 ms;
//   - within 25 ms of the previous frame (output that trickles in), the frame collects for 40 ms, which
//     cuts a stream's frame rate to about 25 per second.
//
// Either way a frame leaves as soon as it holds 16 KiB. A pending error is returned together with the
// bytes read before it.
func (f *framer) read(r deadlineReader, buf []byte) (int, error) {
	n, err := r.Read(buf)
	if n > 0 {
		defer func() { f.lastSent = f.now() }()
	}
	if err != nil || n >= coalesceBelow || n >= len(buf) {
		return n, err
	}
	first := f.now()
	stream := !f.lastSent.IsZero() && first.Sub(f.lastSent) < f.streamGap
	limit := len(buf)
	if limit > coalesceBelow {
		limit = coalesceBelow
	}
	end := first.Add(f.quietMax)
	if stream {
		end = first.Add(f.streamWait)
	}
	for n < limit {
		deadline := end
		if !stream {
			if quiet := f.now().Add(f.quietGap); quiet.Before(deadline) {
				deadline = quiet
			}
		}
		if !f.now().Before(deadline) {
			break
		}
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
	}
	_ = r.SetReadDeadline(time.Time{})
	return n, nil
}
