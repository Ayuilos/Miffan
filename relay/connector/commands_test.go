package main

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// runCLI runs realMain with the given stdin and returns the exit code and the decoded stdout line.
func runCLI(t *testing.T, stdin string, args ...string) (int, map[string]any) {
	t.Helper()
	oldOut, oldIn := os.Stdout, os.Stdin
	outR, outW, _ := os.Pipe()
	inR, inW, _ := os.Pipe()
	os.Stdout, os.Stdin = outW, inR
	go func() { _, _ = io.WriteString(inW, stdin); inW.Close() }()
	code := realMain(args)
	outW.Close()
	os.Stdout, os.Stdin = oldOut, oldIn
	raw, _ := io.ReadAll(outR)
	line := strings.TrimSpace(string(raw))
	if strings.Count(line, "\n") != 0 || line == "" {
		t.Fatalf("%v: stdout must be exactly one line, got %q", args, line)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(line), &m); err != nil {
		t.Fatalf("%v: stdout is not JSON: %q", args, line)
	}
	return code, m
}

func cliHome(t *testing.T) string {
	dir := filepath.Join(t.TempDir(), "connector")
	t.Setenv("MIFFAN_CONNECTOR_HOME", dir)
	return dir
}

func mustFail(t *testing.T, code int, m map[string]any, reason string) {
	t.Helper()
	if code == 0 || m["success"] != false || m["reason"] != reason {
		t.Fatalf("expected failure %q, got code %d: %v", reason, code, m)
	}
}

func TestInitCreatesIdentityOnceAndConfig(t *testing.T) {
	dir := cliHome(t)
	m := runOK(t, "", "init", "--relay", "https://Relay.Example.com/", "--token", "abc", "--target", "127.0.0.1:2222")
	hostID := m["hostId"].(string)
	if !idPattern.MatchString(hostID) || m["relay"] != "https://relay.example.com" {
		t.Fatalf("init output: %v", m)
	}
	seed, _ := os.ReadFile(filepath.Join(dir, "host.key"))
	if len(seed) != 32 {
		t.Fatalf("host.key has %d bytes", len(seed))
	}
	if runtime.GOOS != "windows" {
		for _, f := range []string{"host.key", "config.json"} {
			info, err := os.Stat(filepath.Join(dir, f))
			if err != nil || info.Mode().Perm() != 0o600 {
				t.Fatalf("%s mode = %v, %v", f, info.Mode(), err)
			}
		}
	}
	cfg, _ := loadConfig(dataPaths{dir})
	if cfg.Target != "127.0.0.1:2222" || cfg.AccessToken != "abc" || len(cfg.Devices) != 0 {
		t.Fatalf("config = %+v", cfg)
	}
	// Re-running init keeps the key and the token, and updates the relay.
	m2 := runOK(t, "", "init", "--relay", "https://other.example.com", "--target", "127.0.0.1:22")
	if m2["hostId"] != hostID {
		t.Fatal("init must never replace an existing host key")
	}
	cfg, _ = loadConfig(dataPaths{dir})
	if cfg.Relay != "https://other.example.com" || cfg.AccessToken != "abc" {
		t.Fatalf("config after re-init = %+v", cfg)
	}
	// An explicit empty token clears it.
	runOK(t, "", "init", "--relay", "https://other.example.com", "--token", "", "--target", "127.0.0.1:22")
	if cfg, _ = loadConfig(dataPaths{dir}); cfg.AccessToken != "" {
		t.Fatal("--token '' should clear the access code")
	}
}

func runOK(t *testing.T, stdin string, args ...string) map[string]any {
	t.Helper()
	code, m := runCLI(t, stdin, args...)
	if code != 0 || m["success"] != true {
		t.Fatalf("%v: expected success, got code %d: %v", args, code, m)
	}
	return m
}

func TestInitStdinForms(t *testing.T) {
	dir := cliHome(t)
	runOK(t, `{"relay":"https://r.example.com","accessToken":"tok"}`, "init", "--stdin", "--target", "127.0.0.1:22")
	cfg, _ := loadConfig(dataPaths{dir})
	if cfg.Relay != "https://r.example.com" || cfg.AccessToken != "tok" {
		t.Fatalf("config = %+v", cfg)
	}
	runOK(t, "line-token\nignored", "init", "--relay", "https://r.example.com", "--token-stdin", "--target", "127.0.0.1:22")
	if cfg, _ = loadConfig(dataPaths{dir}); cfg.AccessToken != "line-token" {
		t.Fatalf("token = %q", cfg.AccessToken)
	}
	// accessToken absent from the JSON keeps what is there.
	runOK(t, `{"relay":"https://r.example.com"}`, "init", "--stdin", "--target", "127.0.0.1:22")
	if cfg, _ = loadConfig(dataPaths{dir}); cfg.AccessToken != "line-token" {
		t.Fatal("missing accessToken must keep the stored one")
	}
	code, m := runCLI(t, `not json`, "init", "--stdin")
	mustFail(t, code, m, "bad_input")
}

func TestInitValidation(t *testing.T) {
	dir := cliHome(t)
	cases := []struct {
		args   []string
		reason string
	}{
		{[]string{"init"}, "bad_relay"},
		{[]string{"init", "--relay", "ftp://x.example.com"}, "bad_relay"},
		{[]string{"init", "--relay", "http://relay.example.com"}, "bad_relay"},
		{[]string{"init", "--relay", "https://user:pw@relay.example.com"}, "bad_relay"},
		{[]string{"init", "--relay", "https://relay.example.com/path?x=1"}, "bad_relay"},
		{[]string{"init", "--relay", "https://relay.example.com", "--target", "192.168.1.5:22"}, "bad_target"},
		{[]string{"init", "--relay", "https://relay.example.com", "--target", "example.com:22"}, "bad_target"},
		{[]string{"init", "--relay", "https://relay.example.com", "--target", "127.0.0.1:99999"}, "bad_target"},
		{[]string{"init", "--relay", "https://relay.example.com", "--target", "127.0.0.1"}, "bad_target"},
		{[]string{"init", "--relay", "https://relay.example.com", "--bogus"}, "usage"},
	}
	for _, c := range cases {
		code, m := runCLI(t, "", c.args...)
		mustFail(t, code, m, c.reason)
	}
	if _, err := os.Stat(filepath.Join(dir, "host.key")); err == nil {
		t.Fatal("a rejected init must not create the identity")
	}
	// Loopback http and IPv6 loopback are fine (development).
	runOK(t, "", "init", "--relay", "http://127.0.0.1:8787", "--target", "[::1]:22")
}

func TestPairUnpairStatus(t *testing.T) {
	dir := cliHome(t)
	code, m := runCLI(t, "", "pair", "--device", vectorSPKIB64(), "--name", "phone")
	mustFail(t, code, m, "not_initialized")
	init := runOK(t, "", "init", "--relay", "https://relay.example.com", "--target", "127.0.0.1:22")

	paired := runOK(t, "", "pair", "--device", vectorSPKIB64(), "--name", "PGEM10")
	if paired["deviceId"] != vectorDeviceID || paired["hostId"] != init["hostId"] || paired["relay"] != "https://relay.example.com" {
		t.Fatalf("pair output: %v", paired)
	}
	// Pairing again does not duplicate; the name is updated.
	runOK(t, "", "pair", "--device", vectorSPKIB64(), "--name", "Pixel")
	cfg, _ := loadConfig(dataPaths{dir})
	if len(cfg.Devices) != 1 || cfg.Devices[0].Name != "Pixel" || cfg.Devices[0].ID != vectorDeviceID || cfg.Devices[0].AddedAt == "" {
		t.Fatalf("devices = %+v", cfg.Devices)
	}
	// stdin form
	app := newTestApp(t)
	body, _ := json.Marshal(map[string]string{"device": app.pub(), "name": "第二台手机"})
	runOK(t, string(body), "pair", "--stdin")

	for _, bad := range [][]string{
		{"pair", "--device", "!!!", "--name", "x"},
		{"pair", "--device", vectorSPKIB64() + "AA", "--name", "x"},
		{"pair", "--device", b64u.EncodeToString([]byte("not a key")), "--name", "x"},
		{"pair", "--device", "MCowBQYDK2VwAyEAA6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg", "--name", "x"}, // Ed25519, not P-256
	} {
		code, m := runCLI(t, "", bad...)
		mustFail(t, code, m, "bad_device")
	}
	code, m = runCLI(t, "", "pair", "--device", vectorSPKIB64(), "--name", "")
	mustFail(t, code, m, "bad_name")
	code, m = runCLI(t, "", "pair", "--device", vectorSPKIB64(), "--name", "bad\x07name")
	mustFail(t, code, m, "bad_name")
	code, m = runCLI(t, "", "pair", "--device", vectorSPKIB64(), "--name", strings.Repeat("x", 65))
	mustFail(t, code, m, "bad_name")

	st := runOK(t, "", "status")
	if st["hostId"] != init["hostId"] || st["running"] != false || st["connected"] != false || st["lastError"] != nil || st["lastConnectedAt"] != nil || st["edge"] != nil {
		t.Fatalf("status = %v", st)
	}
	if devs := st["devices"].([]any); len(devs) != 2 {
		t.Fatalf("status devices = %v", devs)
	}
	for _, key := range []string{"version", "hostId", "relay", "running", "connected", "lastError", "lastConnectedAt", "edge", "devices"} {
		if _, ok := st[key]; !ok {
			t.Errorf("status lacks %q", key)
		}
	}

	un := runOK(t, "", "unpair", "--device-id", vectorDeviceID)
	if un["removed"] != true || len(un["devices"].([]any)) != 1 {
		t.Fatalf("unpair output: %v", un)
	}
	again := runOK(t, "", "unpair", "--device-id", vectorDeviceID)
	if again["removed"] != false {
		t.Fatalf("second unpair: %v", again)
	}
	code, m = runCLI(t, "", "unpair", "--device-id", "../../etc")
	mustFail(t, code, m, "bad_device_id")
}

func vectorSPKIB64() string {
	return "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtHy9e7QQbWehHFz8vGYxsMhPMUVnR1GEbeX7HVr_dZe-DIU-oIjqK50HEEFaKcXElBuY4w6YhvYcg4j3-5HAxw"
}

func TestVectorSPKIEncoding(t *testing.T) {
	der, _ := b64u.DecodeString(vectorSPKIB64())
	if idOf(der) != vectorDeviceID {
		t.Fatalf("the base64url form of the vector SPKI hashes to %s", idOf(der))
	}
}

func TestStatusReportsRunningFromLock(t *testing.T) {
	dir := cliHome(t)
	runOK(t, "", "init", "--relay", "https://relay.example.com", "--target", "127.0.0.1:22")
	p := dataPaths{dir}
	_ = saveState(p, &state{Connected: true, Edge: "104.17.5.5"})
	st := runOK(t, "", "status")
	if st["running"] != false || st["connected"] != false || st["edge"] != nil {
		t.Fatalf("a stale state.json without a running connector must not look connected: %v", st)
	}
	release, err := tryLock(p.runLock())
	if err != nil {
		t.Fatal(err)
	}
	st = runOK(t, "", "status")
	if st["running"] != true || st["connected"] != true || st["edge"] != "104.17.5.5" {
		t.Fatalf("status with the lock held: %v", st)
	}
	// A second `run` refuses to start.
	code, m := runCLI(t, "", "run")
	mustFail(t, code, m, "already_running")
	release()
}

func TestStatusAndRunBeforeInit(t *testing.T) {
	cliHome(t)
	code, m := runCLI(t, "", "status")
	mustFail(t, code, m, "not_initialized")
	code, m = runCLI(t, "", "run")
	mustFail(t, code, m, "not_initialized")
}

func TestVersionAndUsage(t *testing.T) {
	cliHome(t)
	m := runOK(t, "", "version")
	if m["version"] == "" {
		t.Fatal("version is empty")
	}
	code, m := runCLI(t, "", "frobnicate")
	mustFail(t, code, m, "usage")
}

func TestSshdConfigPort(t *testing.T) {
	dir := t.TempDir()
	write := func(name, body string) string {
		p := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		_ = os.WriteFile(p, []byte(body), 0o644)
		return p
	}
	main := write("sshd_config", "# comment\nInclude sshd_config.d/*.conf\nPasswordAuthentication no\n")
	write("sshd_config.d/10-port.conf", "Port 2200\n")
	if got := portFromConfig(main, dir, 0); got != 2200 {
		t.Fatalf("included port = %d", got)
	}
	if got := portFromConfig(write("a", "  port   2022  \nPort 2023\n"), dir, 0); got != 2022 {
		t.Fatalf("first Port wins, got %d", got)
	}
	if got := portFromConfig(write("b", "Port=2024\n"), dir, 0); got != 2024 {
		t.Fatalf("Port=N form: %d", got)
	}
	if got := portFromConfig(write("c", "Match User x\nPort 9999\n"), dir, 0); got != 0 {
		t.Fatalf("a Port inside a Match block is not the listen port: %d", got)
	}
	if got := portFromConfig(write("d", "# Port 2222\nPort notanumber\n"), dir, 0); got != 0 {
		t.Fatalf("garbage: %d", got)
	}
	write("loop.conf", "Include loop.conf\n")
	if got := portFromConfig(filepath.Join(dir, "loop.conf"), dir, 0); got != 0 {
		t.Fatalf("include loop: %d", got)
	}
}

func TestLogLimiter(t *testing.T) {
	var sb strings.Builder
	l := newLogger(&sb)
	now := l.now()
	l.now = func() time.Time { return now }
	for i := 0; i < 1000; i++ {
		l.printf("line %d", i)
	}
	if n := strings.Count(sb.String(), "\n"); n > 25 {
		t.Fatalf("a flood of 1000 lines wrote %d", n)
	}
	now = now.Add(time.Minute)
	l.printf("after")
	if !strings.Contains(sb.String(), "log lines suppressed") || !strings.Contains(sb.String(), "after") {
		t.Fatalf("no summary of suppressed lines:\n%s", sb.String())
	}
}
