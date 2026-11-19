//go:build windows

package main

import (
	"errors"
	"net"
	"syscall"
	"unsafe"
)

const (
	sioUDPConnReset = 0x9800000C
	wsaEConnReset   = syscall.Errno(10054)
)

// disableUDPConnReset stops Winsock from surfacing ICMP Port Unreachable errors on subsequent
// ReadFromUDP calls after an unconnected WriteToUDP targets a port that is not listening yet.
func disableUDPConnReset(conn *net.UDPConn) {
	if conn == nil {
		return
	}
	raw, err := conn.SyscallConn()
	if err != nil {
		return
	}
	var bytesReturned uint32
	var flag uint32
	_ = raw.Control(func(fd uintptr) {
		_ = syscall.WSAIoctl(
			syscall.Handle(fd),
			sioUDPConnReset,
			(*byte)(unsafe.Pointer(&flag)),
			4,
			nil,
			0,
			&bytesReturned,
			nil,
			0,
		)
	})
}

func isTransientUDPReadErr(err error) bool {
	return errors.Is(err, syscall.ECONNREFUSED) || errors.Is(err, wsaEConnReset)
}
