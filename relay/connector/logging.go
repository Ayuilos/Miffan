package main

import (
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

// logger writes to stderr (the service manager collects it) with a token bucket, so a misbehaving relay
// or a flood of bad handshakes cannot fill the disk. Nothing secret is ever passed to it: no frame
// contents, signatures, keys or access codes.
type logger struct {
	mu         sync.Mutex
	out        io.Writer
	now        func() time.Time
	tokens     float64
	last       time.Time
	suppressed int
}

const (
	logBurst  = 20.0
	logPerSec = 0.5
)

func newLogger(out io.Writer) *logger {
	if out == nil {
		out = os.Stderr
	}
	return &logger{out: out, now: time.Now, tokens: logBurst}
}

func (l *logger) printf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if !l.last.IsZero() {
		l.tokens += now.Sub(l.last).Seconds() * logPerSec
		if l.tokens > logBurst {
			l.tokens = logBurst
		}
	}
	l.last = now
	if l.tokens < 1 {
		l.suppressed++
		return
	}
	l.tokens--
	if l.suppressed > 0 {
		fmt.Fprintf(l.out, "%s %d log lines suppressed\n", now.UTC().Format(time.RFC3339), l.suppressed)
		l.suppressed = 0
		if l.tokens >= 1 {
			l.tokens--
		}
	}
	fmt.Fprintf(l.out, "%s %s\n", now.UTC().Format(time.RFC3339), fmt.Sprintf(format, args...))
}
