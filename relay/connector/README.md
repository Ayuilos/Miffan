# miffan-connector

Computer-side agent of the Miffan relay ([PROTOCOL.md](../PROTOCOL.md) v1). It keeps one outbound
WebSocket to each relay that a paired phone uses and, for each session a relay announces, checks the
phone's signature and forwards the session to the local sshd. Design: [RELAY_PLAN.md](../../docs/remote-screen/RELAY_PLAN.md)
§8; implementation notes: [RELAY_R1C_NOTES.md](../../docs/remote-screen/RELAY_R1C_NOTES.md).

```sh
miffan-connector init --relay https://relay.example.com [--token CODE | --token-stdin] [--target 127.0.0.1:22]
miffan-connector pair --device <b64u SPKI> --name "My phone" [--relay URL]   # or: pair --stdin ({"device","name","relay"})
miffan-connector unpair --device-id <deviceId>
miffan-connector status
miffan-connector run        # foreground, started by launchd / systemd --user / a scheduled task
```

Every command except `run` prints one JSON line; failures are `{"success":false,"reason":…,"detail":…}`
with a non-zero exit. Logs go to stderr.

### Several relays

Since 1.1 one computer can be reached through several relays at once, for example one phone on the
official relay and another on a self-hosted one:

- `init --relay URL` **adds** the relay (or refreshes its access code) instead of replacing the previous one.
- `pair` binds the phone to one relay: `--relay`, or the relay `init` named last. Pairing a phone again with
  another relay moves it there.
- `run` keeps a control connection to every relay a paired phone uses, plus one that `init` named within the
  last 10 minutes (so the phone being set up can pair and test it). It rereads `config.json` every 2 seconds,
  so `init`, `pair` and `unpair` take effect without a restart.
- `pair` and `unpair` drop relays that no phone uses any more.
- `status` lists every relay under `relays` (`url`, `active`, `connected`, `lastError`, `edge`, `usage`); the
  1.0 top-level fields describe the relay `init` named last.
- A 1.0 `config.json` (`relay`, `accessToken`) is migrated on read; its phones stay on that relay.

## Build and test

Go 1.22 or newer.

```sh
cd relay/connector
go vet ./... && go test ./...
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=1.0.0" -o miffan-connector .
```

The `relay-connector-v*` tag workflow (`.github/workflows/relay-connector.yml`) builds the five release
binaries and `SHA256SUMS`.
