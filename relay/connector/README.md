# miffan-connector

Computer-side agent of the Miffan relay ([PROTOCOL.md](../PROTOCOL.md) v1). It keeps one outbound
WebSocket to the relay and, for each session the relay announces, checks the phone's signature
and forwards the session to the local sshd. Design: [RELAY_PLAN.md](../../docs/remote-screen/RELAY_PLAN.md)
§8; implementation notes: [RELAY_R1C_NOTES.md](../../docs/remote-screen/RELAY_R1C_NOTES.md).

```sh
miffan-connector init --relay https://relay.example.com [--token CODE | --token-stdin] [--target 127.0.0.1:22]
miffan-connector pair --device <b64u SPKI> --name "My phone"      # or: pair --stdin  ({"device","name"})
miffan-connector unpair --device-id <deviceId>
miffan-connector status
miffan-connector run        # foreground, started by launchd / systemd --user / a scheduled task
```

Every command except `run` prints one JSON line; failures are `{"success":false,"reason":…,"detail":…}`
with a non-zero exit. Logs go to stderr.

## Build and test

Go 1.22 or newer.

```sh
cd relay/connector
go vet ./... && go test ./...
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=1.0.0" -o miffan-connector .
```

The `relay-connector-v*` tag workflow (`.github/workflows/relay-connector.yml`) builds the five release
binaries and `SHA256SUMS`.
