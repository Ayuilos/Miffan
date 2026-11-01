package main

import (
	"bufio"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const defaultSSHPort = 22

// detectSSHPort finds the port the local sshd listens on: `sshd -T` when it can be asked (it usually
// needs root), else the Port lines of sshd_config and the files it includes, else 22.
func detectSSHPort() int {
	if runtime.GOOS != "windows" {
		if p := portFromSshdT(); p > 0 {
			return p
		}
		if p := portFromConfig("/etc/ssh/sshd_config", "/etc/ssh", 0); p > 0 {
			return p
		}
		return defaultSSHPort
	}
	programData := os.Getenv("ProgramData")
	if programData == "" {
		programData = `C:\ProgramData`
	}
	dir := filepath.Join(programData, "ssh")
	if p := portFromConfig(filepath.Join(dir, "sshd_config"), dir, 0); p > 0 {
		return p
	}
	return defaultSSHPort
}

func portFromSshdT() int {
	for _, bin := range []string{"/usr/sbin/sshd", "/usr/local/sbin/sshd", "/opt/homebrew/sbin/sshd", "sshd"} {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		out, err := exec.CommandContext(ctx, bin, "-T").Output()
		cancel()
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(out), "\n") {
			f := strings.Fields(line)
			if len(f) == 2 && strings.EqualFold(f[0], "port") {
				if n, err := strconv.Atoi(f[1]); err == nil && n > 0 && n <= 65535 {
					return n
				}
			}
		}
	}
	return 0
}

// portFromConfig returns the first Port of an sshd_config, following Include lines (relative paths are
// relative to base) and stopping at the first Match block, where global settings end.
func portFromConfig(path, base string, depth int) int {
	if depth > 3 {
		return 0
	}
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// sshd_config allows "Keyword value" and "Keyword=value".
		key, value := line, ""
		if i := strings.IndexAny(line, " \t="); i > 0 {
			key, value = line[:i], strings.TrimSpace(strings.TrimLeft(line[i:], " \t="))
		}
		key = strings.ToLower(key)
		fields := strings.Fields(value)
		switch key {
		case "match":
			return 0
		case "port":
			if len(fields) > 0 {
				if n, err := strconv.Atoi(fields[0]); err == nil && n > 0 && n <= 65535 {
					return n
				}
			}
		case "include":
			for _, pattern := range fields {
				if !filepath.IsAbs(pattern) {
					pattern = filepath.Join(base, pattern)
				}
				matches, _ := filepath.Glob(pattern)
				for _, m := range matches {
					if p := portFromConfig(m, base, depth+1); p > 0 {
						return p
					}
				}
			}
		}
	}
	return 0
}
