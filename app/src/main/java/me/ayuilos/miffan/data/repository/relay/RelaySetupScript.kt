package me.ayuilos.miffan.data.repository.relay

import android.content.res.AssetManager
import java.nio.charset.StandardCharsets
import java.util.Base64
import me.rerere.workspace.relay.RelayEndpoint

/**
 * Generates a standalone POSIX shell command that authorizes Miffan's SSH public key,
 * installs/configures `miffan-connector` (or reuses an existing `miffan-connector`), pairs this
 * phone's device key, starts the connector, and prints a `miffan-pair://v1?...` code containing
 * `hostId`, user, name, relay, and local SSH host key fingerprints for out-of-band verification.
 */
object RelaySetupScript {
    private val keyPattern = Regex(
        """\A(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521))[ \t]+([A-Za-z0-9+/]+={0,2})(?:[ \t]+[^\r\n\u0000]*)?\z"""
    )
    private val deviceKeyPattern = Regex("""\A[A-Za-z0-9_-]{80,200}\z""")
    private val sha256Pattern = Regex("""\A[0-9a-f]{64}\z""")

    internal data class ConnectorPins(
        val version: String,
        val releaseUrl: String,
        val downloadBaseUrls: List<String>,
        val shaDarwinArm64: String,
        val shaDarwinAmd64: String,
        val shaLinuxAmd64: String,
        val shaLinuxArm64: String,
    )

    internal fun parsePins(helperSh: String): ConnectorPins {
        fun readOptionalVar(name: String): String? =
            Regex("""(?m)^${Regex.escape(name)}=("[^"\r\n]*"|[^\s#\r\n]+)""")
                .find(helperSh)
                ?.groupValues?.get(1)
                ?.removeSurrounding("\"")
        fun readVar(name: String): String = readOptionalVar(name) ?: error("Missing $name in miffan.sh")
        val version = readVar("MIFFAN_CONNECTOR_VERSION")
        require(version.matches(Regex("""[0-9A-Za-z._-]+"""))) { "Invalid connector version" }
        fun normalizeUrl(raw: String): String =
            raw.replace("\${MIFFAN_CONNECTOR_VERSION}", version)
                .replace("\$MIFFAN_CONNECTOR_VERSION", version)
                .trimEnd('/')
                .also { url ->
                    require(url.startsWith("https://") && url.none { it.isWhitespace() || it == '\'' || it == '\\' || it == '"' || it == '$' }) {
                        "Invalid connector download URL"
                    }
                }
        val releaseUrl = normalizeUrl(readVar("MIFFAN_CONNECTOR_RELEASE_URL"))
        val mirrorUrls = readOptionalVar("MIFFAN_CONNECTOR_MIRRORS")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split(Regex("""\s+"""))
            ?.map(::normalizeUrl)
            .orEmpty()
        val downloadBaseUrls = (mirrorUrls + releaseUrl).distinct()
        fun readSha(name: String): String = readVar(name).lowercase().also {
            require(sha256Pattern.matches(it)) { "Invalid $name in miffan.sh" }
        }
        return ConnectorPins(
            version = version,
            releaseUrl = releaseUrl,
            downloadBaseUrls = downloadBaseUrls,
            shaDarwinArm64 = readSha("MIFFAN_CONNECTOR_SHA256_DARWIN_ARM64"),
            shaDarwinAmd64 = readSha("MIFFAN_CONNECTOR_SHA256_DARWIN_AMD64"),
            shaLinuxAmd64 = readSha("MIFFAN_CONNECTOR_SHA256_LINUX_AMD64"),
            shaLinuxArm64 = readSha("MIFFAN_CONNECTOR_SHA256_LINUX_ARM64"),
        )
    }

    fun render(
        helperSh: String,
        sshPublicKey: String,
        phone: RelayPhoneIdentity,
        endpoint: RelayEndpoint,
    ): String {
        require(sshPublicKey.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Public key must be one line" }
        val keyMatch = requireNotNull(keyPattern.matchEntire(sshPublicKey.trim())) { "Unsupported SSH public key format" }
        val normalizedSshKey = "${keyMatch.groupValues[1]} ${keyMatch.groupValues[2]}"
        val devicePub = phone.devicePublicKeyB64u.trim()
        require(deviceKeyPattern.matches(devicePub)) { "Invalid relay device public key" }
        val pins = parsePins(helperSh)
        val relayB64 = b64(endpoint.baseUrl)
        val tokenB64 = endpoint.accessToken?.takeIf { it.isNotEmpty() }?.let(::b64).orEmpty()
        val nameB64 = b64(phone.deviceName)

        // Zero single quotes and zero backslashes inside the body so `sh -c '...'` works verbatim
        // in sh, dash, bash, zsh, and fish.
        val body = """
set -u
umask 077
PUB="$normalizedSshKey"
DEV="$devicePub"
RELAY_B64="$relayB64"
TOK_B64="$tokenB64"
NAME_B64="$nameB64"
DL_URLS="${pins.downloadBaseUrls.joinToString(" ")}"
SHA_DA="${pins.shaDarwinArm64}"
SHA_DX="${pins.shaDarwinAmd64}"
SHA_LA="${pins.shaLinuxAmd64}"
SHA_LR="${pins.shaLinuxArm64}"
TMP=""
cleanup_tmp() { [ -z "${'$'}{TMP:-}" ] || rm -f "${'$'}TMP"; }
trap cleanup_tmp EXIT
fail() { echo "Miffan error: ${'$'}1" >&2; exit 1; }
b64d() {
  if printf "YQ==" | base64 -d >/dev/null 2>&1; then
    printf "%s" "${'$'}1" | base64 -d
  else
    printf "%s" "${'$'}1" | base64 -D
  fi
}
b64e() {
  out=""
  for p in ${'$'}(printf "%s" "${'$'}1" | base64); do out="${'$'}out${'$'}p"; done
  printf "%s" "${'$'}out"
}
RELAY=${'$'}(b64d "${'$'}RELAY_B64") || fail "cannot decode relay"
DNAME=${'$'}(b64d "${'$'}NAME_B64") || fail "cannot decode device name"
mkdir -p "${'$'}HOME/.ssh" && chmod 700 "${'$'}HOME/.ssh" || fail "cannot prepare ssh dir"
touch "${'$'}HOME/.ssh/authorized_keys" && chmod 600 "${'$'}HOME/.ssh/authorized_keys" || fail "cannot prepare authorized_keys"
if ! grep -qxF "${'$'}PUB" "${'$'}HOME/.ssh/authorized_keys"; then
  if [ -s "${'$'}HOME/.ssh/authorized_keys" ]; then echo "" >>"${'$'}HOME/.ssh/authorized_keys"; fi
  echo "${'$'}PUB" >>"${'$'}HOME/.ssh/authorized_keys" || fail "cannot authorize SSH key"
fi
BIN=""
if [ -x "${'$'}HOME/.miffan/bin/miffan-connector" ]; then
  BIN="${'$'}HOME/.miffan/bin/miffan-connector"
elif command -v miffan-connector >/dev/null 2>&1; then
  BIN=${'$'}(command -v miffan-connector)
else
  case "${'$'}(uname -s)-${'$'}(uname -m)" in
    Darwin-arm64) PLAT=darwin-arm64; EXP=${'$'}SHA_DA ;;
    Darwin-x86_64) PLAT=darwin-amd64; EXP=${'$'}SHA_DX ;;
    Linux-x86_64) PLAT=linux-amd64; EXP=${'$'}SHA_LA ;;
    Linux-aarch64|Linux-arm64) PLAT=linux-arm64; EXP=${'$'}SHA_LR ;;
    *) fail "unsupported platform ${'$'}(uname -s)-${'$'}(uname -m)" ;;
  esac
  mkdir -p "${'$'}HOME/.miffan/bin" || fail "cannot create ~/.miffan/bin"
  TMP="${'$'}HOME/.miffan/bin/.miffan-connector.${'$'}${'$'}"
  DL_OK=0
  DL_BAD=0
  for U in ${'$'}DL_URLS; do
    rm -f "${'$'}TMP"
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL --connect-timeout 15 --max-time 90 --speed-time 20 --speed-limit 1024 -o "${'$'}TMP" "${'$'}U/miffan-connector-${'$'}PLAT" 2>/dev/null || continue
    elif command -v wget >/dev/null 2>&1; then
      wget -q -T 30 -O "${'$'}TMP" "${'$'}U/miffan-connector-${'$'}PLAT" 2>/dev/null || continue
    else
      fail "curl or wget is required"
    fi
    if command -v sha256sum >/dev/null 2>&1; then
      set -- ${'$'}(sha256sum "${'$'}TMP")
      ACT=${'$'}{1:-}
    elif command -v shasum >/dev/null 2>&1; then
      set -- ${'$'}(shasum -a 256 "${'$'}TMP")
      ACT=${'$'}{1:-}
    elif command -v openssl >/dev/null 2>&1; then
      set -- ${'$'}(openssl dgst -sha256 "${'$'}TMP")
      ACT=""
      for w in "${'$'}@"; do ACT=${'$'}w; done
    else
      fail "sha256 tool is required"
    fi
    if [ "${'$'}ACT" = "${'$'}EXP" ]; then
      DL_OK=1
      break
    fi
    DL_BAD=1
  done
  if [ "${'$'}DL_OK" -ne 1 ]; then
    if [ "${'$'}DL_BAD" -eq 1 ]; then
      fail "connector checksum mismatch"
    fi
    fail "download failed"
  fi
  chmod 755 "${'$'}TMP" && mv -f "${'$'}TMP" "${'$'}HOME/.miffan/bin/miffan-connector" || fail "cannot install connector"
  TMP=""
  BIN="${'$'}HOME/.miffan/bin/miffan-connector"
fi
mkdir -p "${'$'}HOME/.miffan/connector" && chmod 700 "${'$'}HOME/.miffan/connector" || fail "cannot prepare connector dir"
if [ -n "${'$'}TOK_B64" ]; then
  b64d "${'$'}TOK_B64" | "${'$'}BIN" init --relay "${'$'}RELAY" --token-stdin >/dev/null || fail "connector init failed"
else
  "${'$'}BIN" init --relay "${'$'}RELAY" >/dev/null || fail "connector init failed"
fi
PAIR_OUT=${'$'}("${'$'}BIN" pair --device "${'$'}DEV" --name "${'$'}DNAME" --relay "${'$'}RELAY") || fail "connector pair failed"
hid=${'$'}{PAIR_OUT#*hostId}
hid=${'$'}{hid#*:}
while case "${'$'}hid" in ""|[a-z2-7]*) false ;; *) true ;; esac; do hid=${'$'}{hid#?}; done
hid=${'$'}{hid%%[!a-z2-7]*}
[ "${'$'}{#hid}" -eq 26 ] || fail "connector returned invalid hostId"
ST=${'$'}("${'$'}BIN" status 2>/dev/null || true)
if ! printf "%s" "${'$'}ST" | grep -Eq "running.: *true"; then
  if [ "${'$'}BIN" = "${'$'}HOME/.miffan/bin/miffan-connector" ] && [ "${'$'}(uname -s)" = Darwin ]; then
    mkdir -p "${'$'}HOME/Library/LaunchAgents"
    PLIST="${'$'}HOME/Library/LaunchAgents/me.ayuilos.miffan.connector.plist"
    cat <<EOF >"${'$'}PLIST.tmp"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
<key>Label</key><string>me.ayuilos.miffan.connector</string>
<key>ProgramArguments</key><array><string>${'$'}BIN</string><string>run</string></array>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>10</integer>
<key>ProcessType</key><string>Background</string>
<key>StandardOutPath</key><string>/dev/null</string>
<key>StandardErrorPath</key><string>${'$'}HOME/.miffan/connector/connector.log</string>
</dict>
</plist>
EOF
    mv -f "${'$'}PLIST.tmp" "${'$'}PLIST"
    launchctl bootout "gui/${'$'}(id -u)/me.ayuilos.miffan.connector" >/dev/null 2>&1 || true
    launchctl bootstrap "gui/${'$'}(id -u)" "${'$'}PLIST" >/dev/null 2>&1 || nohup "${'$'}BIN" run >>"${'$'}HOME/.miffan/connector/connector.log" 2>&1 </dev/null &
  elif [ "${'$'}BIN" = "${'$'}HOME/.miffan/bin/miffan-connector" ] && command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    mkdir -p "${'$'}HOME/.config/systemd/user"
    UNIT="${'$'}HOME/.config/systemd/user/miffan-connector.service"
    cat <<EOF >"${'$'}UNIT.tmp"
[Unit]
Description=Miffan relay connector
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=%h/.miffan/bin/miffan-connector run
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
    mv -f "${'$'}UNIT.tmp" "${'$'}UNIT"
    systemctl --user daemon-reload >/dev/null 2>&1 && systemctl --user enable --now miffan-connector.service >/dev/null 2>&1 || nohup "${'$'}BIN" run >>"${'$'}HOME/.miffan/connector/connector.log" 2>&1 </dev/null &
    if command -v loginctl >/dev/null 2>&1; then loginctl enable-linger "${'$'}(id -un)" >/dev/null 2>&1 || true; fi
  else
    nohup "${'$'}BIN" run >>"${'$'}HOME/.miffan/connector/connector.log" 2>&1 </dev/null &
  fi
fi
FPS=""
if command -v ssh-keygen >/dev/null 2>&1; then
  for f in /etc/ssh/ssh_host_ed25519_key.pub /etc/ssh/ssh_host_ecdsa_key.pub /etc/ssh/ssh_host_rsa_key.pub; do
    if [ -r "${'$'}f" ]; then
      set -- ${'$'}(ssh-keygen -lf "${'$'}f" -E sha256 2>/dev/null || true)
      case "${'$'}{2:-}" in SHA256:*) FPS="${'$'}{FPS:+${'$'}FPS,}${'$'}2" ;; esac
    fi
  done
fi
USR=${'$'}(id -un)
CNAME=""
if command -v scutil >/dev/null 2>&1; then CNAME=${'$'}(scutil --get ComputerName 2>/dev/null || true); fi
if [ -z "${'$'}CNAME" ] && command -v hostnamectl >/dev/null 2>&1; then CNAME=${'$'}(hostnamectl --pretty 2>/dev/null || hostnamectl --static 2>/dev/null || true); fi
if [ -z "${'$'}CNAME" ]; then CNAME=${'$'}(hostname 2>/dev/null || echo computer); fi
echo ""
echo "Miffan pairing code:"
echo "miffan-pair://v1?host=${'$'}hid&user64=${'$'}(b64e "${'$'}USR")&name64=${'$'}(b64e "${'$'}CNAME")&relay64=${'$'}RELAY_B64${'$'}{FPS:+&fp=${'$'}FPS}"
        """.trim()

        require('\'' !in body && '\\' !in body) { "Script body must not contain single quotes or backslashes" }
        return "sh -c '$body'"
    }

    /** Read on an IO dispatcher when called from UI. */
    fun fromAssets(
        assets: AssetManager,
        sshPublicKey: String,
        phone: RelayPhoneIdentity,
        endpoint: RelayEndpoint,
    ): String {
        val helperSh = assets.open("remote/miffan.sh").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return render(helperSh, sshPublicKey, phone, endpoint)
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
