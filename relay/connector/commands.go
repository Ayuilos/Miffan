package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"
)

func newFlagSet(name string) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	return fs
}

// withConfigLock serialises read-modify-write cycles of config.json between concurrent commands.
func withConfigLock(paths dataPaths, fn func() int) int {
	if err := ensureDataDir(paths); err != nil {
		return fail("io_error", err.Error())
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		release, err := tryLock(paths.configLock())
		if err == nil {
			defer release()
			return fn()
		}
		if !errors.Is(err, errLocked) || time.Now().After(deadline) {
			return fail("io_error", "cannot lock config.json")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func readStdinLimited() ([]byte, error) {
	raw, err := io.ReadAll(io.LimitReader(os.Stdin, 64<<10+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > 64<<10 {
		return nil, errors.New("stdin is too large")
	}
	return raw, nil
}

func cmdInit(paths dataPaths, args []string) int {
	fs := newFlagSet("init")
	relay := fs.String("relay", "", "relay base URL")
	token := fs.String("token", "", "access code of the relay")
	tokenStdin := fs.Bool("token-stdin", false, "read the access code from the first line of stdin")
	useStdin := fs.Bool("stdin", false, `read {"relay","accessToken"} from stdin`)
	target := fs.String("target", "", "forward sessions to this loopback HOST:PORT (default: the local sshd port)")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 {
		return fail("usage", "usage: init --relay URL [--token T | --token-stdin | --stdin] [--target HOST:PORT]")
	}
	tokenSet := false
	tokenValue := *token
	relayValue := *relay
	flagSeen := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { flagSeen[f.Name] = true })
	tokenSet = flagSeen["token"]

	if *useStdin || *tokenStdin {
		raw, err := readStdinLimited()
		if err != nil {
			return fail("bad_input", err.Error())
		}
		if *tokenStdin {
			line, _ := bufio.NewReader(strings.NewReader(string(raw))).ReadString('\n')
			tokenValue, tokenSet = strings.TrimRight(line, "\r\n"), true
		} else {
			var in struct {
				Relay       string  `json:"relay"`
				AccessToken *string `json:"accessToken"`
			}
			if err := json.Unmarshal(raw, &in); err != nil {
				return fail("bad_input", "stdin is not valid JSON")
			}
			if in.Relay != "" {
				relayValue = in.Relay
			}
			if in.AccessToken != nil {
				tokenValue, tokenSet = *in.AccessToken, true
			}
		}
	}
	clean, err := validateRelayURL(relayValue)
	if err != nil {
		return fail("bad_relay", err.Error())
	}
	if tokenSet && (len(tokenValue) > 512 || strings.ContainsAny(tokenValue, "\r\n\x00")) {
		return fail("bad_token", "access code is invalid")
	}
	var targetValue string
	if *target != "" {
		if targetValue, err = validateTarget(*target); err != nil {
			return fail("bad_target", err.Error())
		}
	} else {
		targetValue = net.JoinHostPort("127.0.0.1", fmt.Sprint(detectSSHPort()))
	}

	return withConfigLock(paths, func() int {
		id, _, err := ensureIdentity(paths)
		if err != nil {
			return fail("io_error", err.Error())
		}
		cfg, err := loadConfig(paths)
		if err != nil {
			if !errors.Is(err, os.ErrNotExist) {
				return fail("bad_config", err.Error())
			}
			cfg = &config{}
		}
		cfg.Relay = clean
		cfg.Target = targetValue
		if tokenSet {
			cfg.AccessToken = tokenValue
		}
		if err := saveConfig(paths, cfg); err != nil {
			return fail("io_error", err.Error())
		}
		return emit(map[string]any{"success": true, "hostId": id.hostID, "relay": cfg.Relay, "target": cfg.Target})
	})
}

func cmdPair(paths dataPaths, args []string) int {
	fs := newFlagSet("pair")
	dev := fs.String("device", "", "device public key, base64url SubjectPublicKeyInfo")
	name := fs.String("name", "", "display name")
	useStdin := fs.Bool("stdin", false, `read {"device","name"} from stdin`)
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 {
		return fail("usage", "usage: pair --device <b64u SPKI> --name NAME")
	}
	deviceValue, nameValue := *dev, *name
	if *useStdin {
		raw, err := readStdinLimited()
		if err != nil {
			return fail("bad_input", err.Error())
		}
		var in struct{ Device, Name string }
		if err := json.Unmarshal(raw, &in); err != nil {
			return fail("bad_input", "stdin is not valid JSON")
		}
		deviceValue, nameValue = in.Device, in.Name
	}
	der, _, err := parseDevicePub(deviceValue)
	if err != nil {
		return fail("bad_device", err.Error())
	}
	nameValue, err = validateName(nameValue)
	if err != nil {
		return fail("bad_name", err.Error())
	}
	deviceID := idOf(der)

	return withConfigLock(paths, func() int {
		id, err := loadIdentity(paths)
		if err != nil {
			return fail("not_initialized", "run `miffan-connector init` first")
		}
		cfg, err := loadConfig(paths)
		if err != nil {
			return fail("not_initialized", "run `miffan-connector init` first")
		}
		if existing := cfg.findDevice(deviceID); existing != nil {
			existing.Name = nameValue
		} else {
			cfg.Devices = append(cfg.Devices, device{
				ID: deviceID, Pub: b64u.EncodeToString(der), Name: nameValue,
				AddedAt: time.Now().UTC().Format(time.RFC3339),
			})
		}
		if err := saveConfig(paths, cfg); err != nil {
			return fail("io_error", err.Error())
		}
		return emit(map[string]any{"success": true, "hostId": id.hostID, "relay": cfg.Relay, "deviceId": deviceID})
	})
}

type deviceInfo struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	AddedAt string `json:"addedAt"`
}

func deviceList(cfg *config) []deviceInfo {
	out := make([]deviceInfo, 0, len(cfg.Devices))
	for _, d := range cfg.Devices {
		out = append(out, deviceInfo{d.ID, d.Name, d.AddedAt})
	}
	return out
}

func cmdUnpair(paths dataPaths, args []string) int {
	fs := newFlagSet("unpair")
	deviceID := fs.String("device-id", "", "deviceId to remove")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 {
		return fail("usage", "usage: unpair --device-id ID")
	}
	if !idPattern.MatchString(*deviceID) {
		return fail("bad_device_id", "device id must be 26 characters of a-z2-7")
	}
	return withConfigLock(paths, func() int {
		cfg, err := loadConfig(paths)
		if err != nil {
			return fail("not_initialized", "run `miffan-connector init` first")
		}
		kept := cfg.Devices[:0]
		removed := false
		for _, d := range cfg.Devices {
			if d.ID == *deviceID {
				removed = true
				continue
			}
			kept = append(kept, d)
		}
		cfg.Devices = kept
		if removed {
			if err := saveConfig(paths, cfg); err != nil {
				return fail("io_error", err.Error())
			}
		}
		return emit(map[string]any{"success": true, "removed": removed, "devices": deviceList(cfg)})
	})
}

func cmdStatus(paths dataPaths) int {
	id, err := loadIdentity(paths)
	if err != nil {
		return fail("not_initialized", "run `miffan-connector init` first")
	}
	cfg, err := loadConfig(paths)
	if err != nil {
		return fail("not_initialized", "run `miffan-connector init` first")
	}
	running := false
	if release, err := tryLock(paths.runLock()); err == nil {
		release()
	} else if errors.Is(err, errLocked) {
		running = true
	}
	st := loadState(paths)
	edge := any(nil)
	if running && st.Edge != "" {
		edge = st.Edge
	}
	var usage any
	if running && len(st.Usage) > 0 {
		usage = st.Usage
	}
	return emit(map[string]any{
		"success":         true,
		"version":         version,
		"hostId":          id.hostID,
		"relay":           cfg.Relay,
		"target":          cfg.Target,
		"running":         running,
		"connected":       running && st.Connected,
		"lastError":       st.LastError,
		"lastConnectedAt": st.LastConnectedAt,
		"edge":            edge,
		"usage":           usage,
		"devices":         deviceList(cfg),
	})
}
