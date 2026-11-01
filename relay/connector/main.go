// miffan-connector keeps one computer reachable through Miffan relays (relay/PROTOCOL.md, connector side):
// one control connection per relay that a paired phone uses, so phones on different relays all reach it.
//
//	miffan-connector init   --relay URL [--token T | --token-stdin | --stdin] [--target HOST:PORT]
//	                        adds the relay; the connector stays connected to every relay a paired phone uses
//	miffan-connector pair   --device <b64u SPKI> --name NAME [--relay URL]   (or --stdin with {"device","name","relay"})
//	miffan-connector unpair --device-id ID
//	miffan-connector status
//	miffan-connector run                     foreground; started by the service manager
//	miffan-connector version
//
// Every command except `run` prints exactly one line of JSON on stdout. Failures print
// {"success":false,"reason":…,"detail":…} and exit non-zero. Logs go to stderr only.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

var version = "dev"

func main() { os.Exit(realMain(os.Args[1:])) }

func realMain(args []string) int {
	if len(args) == 0 {
		return fail("usage", "usage: miffan-connector init|pair|unpair|status|run|version")
	}
	cmd, rest := args[0], args[1:]
	if cmd == "version" {
		return emit(map[string]any{"success": true, "version": version})
	}
	dir, err := defaultDataDir()
	if err != nil {
		return fail("no_data_dir", err.Error())
	}
	paths := dataPaths{dir}
	switch cmd {
	case "init":
		return cmdInit(paths, rest)
	case "pair":
		return cmdPair(paths, rest)
	case "unpair":
		return cmdUnpair(paths, rest)
	case "status":
		return cmdStatus(paths)
	case "run":
		return cmdRun(paths)
	default:
		return fail("usage", "unknown command")
	}
}

func emit(v any) int {
	raw, err := json.Marshal(v)
	if err != nil {
		return fail("internal", "cannot encode result")
	}
	fmt.Fprintln(os.Stdout, string(raw))
	return 0
}

func fail(reason, detail string) int {
	raw, _ := json.Marshal(map[string]any{"success": false, "reason": reason, "detail": detail})
	fmt.Fprintln(os.Stdout, string(raw))
	return 1
}

func cmdRun(paths dataPaths) int {
	if err := ensureDataDir(paths); err != nil {
		return fail("io_error", err.Error())
	}
	release, err := tryLock(paths.runLock())
	if err != nil {
		if err == errLocked {
			return fail("already_running", "another miffan-connector is running")
		}
		return fail("io_error", err.Error())
	}
	defer release()
	id, err := loadIdentity(paths)
	if err != nil {
		return fail("not_initialized", "run `miffan-connector init` first")
	}
	if _, err := loadConfig(paths); err != nil {
		return fail("not_initialized", "run `miffan-connector init` first")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log := newLogger(os.Stderr)
	log.printf("miffan-connector %s starting, host %s", version, id.hostID)
	c := newConnector(paths, id, version, defaultNetEnv(), log)
	c.st = loadState(paths)
	c.run(ctx)
	log.printf("stopped")
	return 0
}
