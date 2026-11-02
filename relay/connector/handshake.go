package main

import (
	"sync"
	"time"
)

const (
	deviceFailLimit    = 5
	deviceFailWindow   = time.Minute
	deviceBlockFor     = 5 * time.Minute
	globalFailLimit    = 20
	globalFailWindow   = time.Minute
	globalBlockFor     = time.Minute
	maxTrackedDevices  = 1024
	reasonUnknown      = "unknown_device"
	reasonBadSignature = "bad_signature"
	reasonBusy         = "busy"
)

// failureLimiter implements PROTOCOL §5.2 step 5. It only ever refuses requests that could not succeed
// anyway: a paired device with a correct signature always gets in, whatever has failed before, so that
// someone who knows the hostId (or a device's public key) cannot lock the owner out by failing on purpose.
// Twenty failures from anyone within a minute refuse unknown and unparsable requests for a minute (which
// bounds the work and log lines garbage can cost); five bad signatures under one paired device's name
// within a minute refuse further bad attempts for that device for five minutes.
type failureLimiter struct {
	mu           sync.Mutex
	now          func() time.Time
	devices      map[string]*deviceFails
	global       []time.Time
	globalBlocks time.Time
}

type deviceFails struct {
	fails        []time.Time
	blockedUntil time.Time
}

func newFailureLimiter() *failureLimiter {
	return &failureLimiter{now: time.Now, devices: map[string]*deviceFails{}}
}

func recent(times []time.Time, now time.Time, window time.Duration) []time.Time {
	kept := times[:0]
	for _, t := range times {
		if now.Sub(t) < window {
			kept = append(kept, t)
		}
	}
	return kept
}

// allow reports whether a new handshake may proceed. deviceID is empty when the claimed device is unknown.
func (l *failureLimiter) allow(deviceID string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if now.Before(l.globalBlocks) {
		return false
	}
	if deviceID != "" {
		if d := l.devices[deviceID]; d != nil && now.Before(d.blockedUntil) {
			return false
		}
	}
	return true
}

// fail records a failed handshake. deviceID may be empty when the message did not even name a device.
func (l *failureLimiter) fail(deviceID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.global = append(recent(l.global, now, globalFailWindow), now)
	if len(l.global) >= globalFailLimit {
		l.globalBlocks = now.Add(globalBlockFor)
		l.global = l.global[:0]
	}
	if deviceID == "" {
		return
	}
	d := l.devices[deviceID]
	if d == nil {
		if len(l.devices) >= maxTrackedDevices {
			l.prune(now)
		}
		d = &deviceFails{}
		l.devices[deviceID] = d
	}
	d.fails = append(recent(d.fails, now, deviceFailWindow), now)
	if len(d.fails) >= deviceFailLimit {
		d.blockedUntil = now.Add(deviceBlockFor)
		d.fails = d.fails[:0]
	}
}

// success forgets the failures of a device that authenticated.
func (l *failureLimiter) success(deviceID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if d := l.devices[deviceID]; d != nil && !l.now().Before(d.blockedUntil) {
		delete(l.devices, deviceID)
	}
}

func (l *failureLimiter) prune(now time.Time) {
	for id, d := range l.devices {
		if now.After(d.blockedUntil) && len(recent(d.fails, now, deviceFailWindow)) == 0 {
			delete(l.devices, id)
		}
	}
	if len(l.devices) >= maxTrackedDevices {
		// Everything left is blocked or fresh; the global limit is what protects us now.
		for id := range l.devices {
			delete(l.devices, id)
			break
		}
	}
}

type authMessage struct {
	T      string `json:"t"`
	Device string `json:"device"`
	Sig    string `json:"sig"`
}

// verifyAuth decides a handshake. On success it returns the deviceId; otherwise the reason to send in
// the denied message. Failures are recorded here.
//
// A paired device's signature is always checked, and a correct one always passes: neither the global
// lock nor the device's own failures can keep it out. The locks only answer "busy" in place of a refusal
// that was coming anyway.
func verifyAuth(cfg *config, limiter *failureLimiter, hostID, sid, ch, nonce string, msg authMessage) (string, string) {
	refuse := func(deviceID, reason string) (string, string) {
		if !limiter.allow(deviceID) {
			return deviceID, reasonBusy
		}
		limiter.fail(deviceID)
		return deviceID, reason
	}
	if msg.T != "auth" {
		return refuse("", reasonBadSignature)
	}
	der, pub, err := parseDevicePub(msg.Device)
	if err != nil {
		return refuse("", reasonUnknown)
	}
	deviceID := idOf(der)
	entry := cfg.findDevice(deviceID)
	if entry == nil || !samePub(entry, der) {
		// Unknown devices are counted globally only: per-device entries for keys anyone can mint would
		// just fill the table.
		_, reason := refuse("", reasonUnknown)
		return deviceID, reason
	}
	if !verifySession(pub, hostID, sid, ch, nonce, msg.Sig) {
		return refuse(deviceID, reasonBadSignature)
	}
	limiter.success(deviceID)
	return deviceID, ""
}

func samePub(entry *device, der []byte) bool {
	stored, err := b64u.DecodeString(entry.Pub)
	return err == nil && string(stored) == string(der)
}
