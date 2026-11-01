//go:build windows

package main

import (
	"errors"
	"syscall"
)

var errLocked = errors.New("locked by another process")

// Win32 error codes the standard library does not name.
const (
	errorAccessDenied     syscall.Errno = 5
	errorSharingViolation syscall.Errno = 32
	errorLockViolation    syscall.Errno = 33
)

// tryLock opens path with a share mode of 0, so a second open (from this or another process) fails with
// a sharing violation for as long as the handle lives. The kernel closes the handle when the process dies.
func tryLock(path string) (release func(), err error) {
	name, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	h, err := syscall.CreateFile(name, syscall.GENERIC_READ|syscall.GENERIC_WRITE, 0, nil,
		syscall.OPEN_ALWAYS, syscall.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		if errors.Is(err, errorSharingViolation) || errors.Is(err, errorLockViolation) || errors.Is(err, errorAccessDenied) {
			return nil, errLocked
		}
		return nil, err
	}
	return func() { _ = syscall.CloseHandle(h) }, nil
}
