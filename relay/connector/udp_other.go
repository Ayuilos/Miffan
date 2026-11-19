//go:build !windows

package main

import (
	"errors"
	"net"
	"syscall"
)

func disableUDPConnReset(_ *net.UDPConn) {}

func isTransientUDPReadErr(err error) bool {
	return errors.Is(err, syscall.ECONNREFUSED)
}
