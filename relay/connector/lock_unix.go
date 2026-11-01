//go:build !windows

package main

import (
	"errors"
	"os"
	"syscall"
)

var errLocked = errors.New("locked by another process")

// tryLock takes an exclusive advisory lock on path without blocking. The lock lives as long as the
// returned file stays open (and is released by the kernel when the process dies).
func tryLock(path string) (release func(), err error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.EAGAIN) {
			return nil, errLocked
		}
		return nil, err
	}
	return func() { _ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN); f.Close() }, nil
}
