package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type device struct {
	ID      string `json:"id"`
	Pub     string `json:"pub"`
	Name    string `json:"name"`
	AddedAt string `json:"addedAt"`
	// Relay is the relay this phone reaches the computer through. Each phone keeps its own, so phones
	// on different relays all reach the computer at once.
	Relay string `json:"relay,omitempty"`
}

// relayEntry is one relay the connector stays connected to.
type relayEntry struct {
	URL         string `json:"url"`
	AccessToken string `json:"accessToken,omitempty"`
	// AddedAt is refreshed by every `init`; a relay no phone uses yet stays connected for relayGrace after it,
	// so the phone that is being set up can pair and test it.
	AddedAt string `json:"addedAt,omitempty"`
}

// relayGrace is how long a relay that no paired phone uses is kept after `init` named it.
const relayGrace = 10 * time.Minute

type config struct {
	Relays  []relayEntry `json:"relays"`
	Target  string       `json:"target"`
	Devices []device     `json:"devices"`

	// Before 1.1 the connector served a single relay. loadConfig moves these two into Relays, and
	// binds every phone that has no relay yet to it.
	LegacyRelay       string `json:"relay,omitempty"`
	LegacyAccessToken string `json:"accessToken,omitempty"`
}

// state is what one relay's control loop reports to `status`.
type state struct {
	Connected       bool            `json:"connected"`
	LastError       *string         `json:"lastError"`
	LastConnectedAt *string         `json:"lastConnectedAt"`
	Edge            string          `json:"edge"`
	Registered      bool            `json:"registered"`
	Usage           json.RawMessage `json:"usage,omitempty"`
}

// stateFile is state.json: one state per relay URL. A 1.0 state.json (one flat state) reads as empty.
type stateFile struct {
	Relays    map[string]state `json:"relays"`
	UpdatedAt string           `json:"updatedAt"`
}

type dataPaths struct{ dir string }

func (p dataPaths) hostKey() string    { return filepath.Join(p.dir, "host.key") }
func (p dataPaths) config() string     { return filepath.Join(p.dir, "config.json") }
func (p dataPaths) state() string      { return filepath.Join(p.dir, "state.json") }
func (p dataPaths) runLock() string    { return filepath.Join(p.dir, "run.lock") }
func (p dataPaths) configLock() string { return filepath.Join(p.dir, "config.lock") }

// defaultDataDir is ~/.miffan/connector, or %LOCALAPPDATA%\Miffan\connector on Windows.
// MIFFAN_CONNECTOR_HOME overrides it (tests and development only).
func defaultDataDir() (string, error) {
	if v := os.Getenv("MIFFAN_CONNECTOR_HOME"); v != "" {
		return v, nil
	}
	if runtime.GOOS == "windows" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			return "", errors.New("LOCALAPPDATA is not set")
		}
		return filepath.Join(base, "Miffan", "connector"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".miffan", "connector"), nil
}

func ensureDataDir(p dataPaths) error {
	_, statErr := os.Stat(p.dir)
	if err := os.MkdirAll(p.dir, 0o700); err != nil {
		return err
	}
	if runtime.GOOS == "windows" && statErr == nil {
		return nil // the ACL was set when the directory was created; icacls is slow
	}
	return restrictToCurrentUser(p.dir, true)
}

// writeFileAtomic writes a temporary file in the same directory and renames it over path.
func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	name := tmp.Name()
	cleanup := func() { _ = os.Remove(name) }
	if err := tmp.Chmod(perm); err != nil && runtime.GOOS != "windows" {
		tmp.Close()
		cleanup()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return err
	}
	if err := restrictToCurrentUser(name, false); err != nil {
		cleanup()
		return err
	}
	// On Windows a reader that has the target open (status, a virus scanner) blocks the replacement for a moment.
	var err2 error
	for attempt := 0; attempt < 10; attempt++ {
		if err2 = os.Rename(name, path); err2 == nil || runtime.GOOS != "windows" {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if err2 != nil {
		cleanup()
		return err2
	}
	return nil
}

func loadConfig(p dataPaths) (*config, error) {
	raw, err := os.ReadFile(p.config())
	if err != nil {
		return nil, err
	}
	var c config
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, fmt.Errorf("config.json is not valid JSON: %w", err)
	}
	c.migrate()
	return &c, nil
}

// migrate turns a 1.0 single-relay config into the relay list.
func (c *config) migrate() {
	if c.LegacyRelay == "" {
		c.LegacyAccessToken = ""
		return
	}
	if c.findRelay(c.LegacyRelay) == nil {
		c.Relays = append([]relayEntry{{URL: c.LegacyRelay, AccessToken: c.LegacyAccessToken}}, c.Relays...)
	}
	for i := range c.Devices {
		if c.Devices[i].Relay == "" {
			c.Devices[i].Relay = c.LegacyRelay
		}
	}
	c.LegacyRelay, c.LegacyAccessToken = "", ""
}

func (c *config) findRelay(url string) *relayEntry {
	for i := range c.Relays {
		if c.Relays[i].URL == url {
			return &c.Relays[i]
		}
	}
	return nil
}

// upsertRelay adds url, or refreshes it, as the most recently named relay (the last one).
func (c *config) upsertRelay(url string, token *string, now time.Time) {
	entry := relayEntry{URL: url}
	kept := c.Relays[:0]
	for _, r := range c.Relays {
		if r.URL == url {
			entry = r
			continue
		}
		kept = append(kept, r)
	}
	if token != nil {
		entry.AccessToken = *token
	}
	entry.AddedAt = now.UTC().Format(time.RFC3339)
	c.Relays = append(kept, entry)
}

// inUse reports whether a paired phone reaches the computer through url, or `init` named it within relayGrace.
func (c *config) inUse(r relayEntry, now time.Time) bool {
	for _, d := range c.Devices {
		if d.Relay == r.URL {
			return true
		}
	}
	added, err := time.Parse(time.RFC3339, r.AddedAt)
	return err == nil && now.Sub(added) < relayGrace
}

// activeRelays are the relays the connector stays connected to.
func (c *config) activeRelays(now time.Time) []relayEntry {
	var out []relayEntry
	for _, r := range c.Relays {
		if c.inUse(r, now) {
			out = append(out, r)
		}
	}
	return out
}

// prune drops the relays that no phone uses any more.
func (c *config) prune(now time.Time) {
	c.Relays = c.activeRelays(now)
}

func relayURLs(rs []relayEntry) []string {
	out := make([]string, 0, len(rs))
	for _, r := range rs {
		out = append(out, r.URL)
	}
	return out
}

func saveConfig(p dataPaths, c *config) error {
	if c.Devices == nil {
		c.Devices = []device{}
	}
	if c.Relays == nil {
		c.Relays = []relayEntry{}
	}
	raw, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(p.config(), append(raw, '\n'), 0o600)
}

func (c *config) findDevice(id string) *device {
	for i := range c.Devices {
		if c.Devices[i].ID == id {
			return &c.Devices[i]
		}
	}
	return nil
}

func loadState(p dataPaths) stateFile {
	var s stateFile
	if raw, err := os.ReadFile(p.state()); err == nil {
		_ = json.Unmarshal(raw, &s)
	}
	if s.Relays == nil {
		s.Relays = map[string]state{}
	}
	return s
}

func saveState(p dataPaths, s *stateFile) error {
	s.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	raw, err := json.Marshal(s)
	if err != nil {
		return err
	}
	return writeFileAtomic(p.state(), append(raw, '\n'), 0o600)
}

// validateRelayURL accepts https URLs. Plain http is only for loopback relays (development, tests).
// The result has no path, query or fragment, and no trailing slash.
func validateRelayURL(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" || u.Hostname() == "" {
		return "", errors.New("relay must be an https URL")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", errors.New("relay must be a bare origin without credentials, path or query")
	}
	switch u.Scheme {
	case "https":
	case "http":
		if !isLoopbackHost(u.Hostname()) {
			return "", errors.New("plain http is only allowed for loopback relays")
		}
	default:
		return "", errors.New("relay must be an https URL")
	}
	return u.Scheme + "://" + strings.ToLower(u.Host), nil
}

func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	addr, err := netip.ParseAddr(host)
	return err == nil && addr.IsLoopback()
}

// validateTarget accepts only loopback HOST:PORT: the connector forwards to the local sshd and nowhere else.
func validateTarget(target string) (string, error) {
	host, port, err := net.SplitHostPort(target)
	if err != nil || host == "" {
		return "", errors.New("target must be HOST:PORT")
	}
	if !isLoopbackHost(host) {
		return "", errors.New("target must be a loopback address")
	}
	n, err := net.LookupPort("tcp", port)
	if err != nil || n < 1 || n > 65535 {
		return "", errors.New("target port is invalid")
	}
	return net.JoinHostPort(host, fmt.Sprint(n)), nil
}

func validateName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 64 {
		return "", errors.New("name must be 1 to 64 characters")
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f || (r >= 0x80 && r < 0xa0) {
			return "", errors.New("name must not contain control characters")
		}
	}
	return name, nil
}
