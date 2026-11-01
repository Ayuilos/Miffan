package main

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base32"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
)

// Protocol constants (relay/PROTOCOL.md §2, §4, §5.2).
const (
	hostSigPrefix    = "miffan-relay/v1 host\n"
	sessionSigPrefix = "miffan-relay/v1 session\n"
)

var (
	idPattern  = regexp.MustCompile(`^[a-z2-7]{26}$`)
	sidPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

	b64u = base64.RawURLEncoding
	b32  = base32.StdEncoding.WithPadding(base32.NoPadding)
)

// idOf is base32(SHA-256(data)) truncated to 26 lowercase characters: the hostId of a host public key
// and the deviceId of a device SPKI.
func idOf(data []byte) string {
	sum := sha256.Sum256(data)
	return strings.ToLower(b32.EncodeToString(sum[:]))[:26]
}

type identity struct {
	priv   ed25519.PrivateKey
	hostID string
}

func newIdentity(seed []byte) (*identity, error) {
	if len(seed) != ed25519.SeedSize {
		return nil, errors.New("host.key must hold a 32-byte seed")
	}
	priv := ed25519.NewKeyFromSeed(seed)
	return &identity{priv: priv, hostID: idOf(priv.Public().(ed25519.PublicKey))}, nil
}

func (i *identity) pub() []byte { return i.priv.Public().(ed25519.PublicKey) }

// signHost signs the control-connection challenge: host is the lower-case Host header value.
func (i *identity) signHost(host, nonce string) string {
	return b64u.EncodeToString(ed25519.Sign(i.priv, []byte(hostSigPrefix+host+"\n"+nonce)))
}

// loadIdentity reads host.key. A missing file is reported as os.ErrNotExist.
func loadIdentity(paths dataPaths) (*identity, error) {
	seed, err := os.ReadFile(paths.hostKey())
	if err != nil {
		return nil, err
	}
	return newIdentity(seed)
}

// ensureIdentity creates host.key when it does not exist and never replaces an existing key.
func ensureIdentity(paths dataPaths) (*identity, bool, error) {
	if id, err := loadIdentity(paths); err == nil {
		return id, false, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, false, err
	}
	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		return nil, false, err
	}
	if err := writeFileAtomic(paths.hostKey(), seed, 0o600); err != nil {
		return nil, false, err
	}
	id, err := newIdentity(seed)
	return id, true, err
}

// parseDevicePub decodes a b64u SubjectPublicKeyInfo and requires an ECDSA P-256 key.
func parseDevicePub(encoded string) ([]byte, *ecdsa.PublicKey, error) {
	der, err := b64u.DecodeString(encoded)
	if err != nil || len(der) == 0 || len(der) > 256 {
		return nil, nil, errors.New("device key is not base64url SPKI")
	}
	key, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, nil, fmt.Errorf("device key is not SPKI: %w", err)
	}
	pub, ok := key.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		return nil, nil, errors.New("device key is not ECDSA P-256")
	}
	// Re-encoding must reproduce the input, so the deviceId is that of the canonical SPKI.
	canonical, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil || string(canonical) != string(der) {
		return nil, nil, errors.New("device key is not canonical SPKI")
	}
	return der, pub, nil
}

// sessionMessage is what an App signs: PROTOCOL §5.2 step 2.
func sessionMessage(hostID, sid, ch, nonce string) []byte {
	return []byte(sessionSigPrefix + hostID + "\n" + sid + "\n" + ch + "\n" + nonce)
}

// verifySession checks an ECDSA P-256 / SHA-256 DER signature over sessionMessage.
func verifySession(pub *ecdsa.PublicKey, hostID, sid, ch, nonce, sigB64 string) bool {
	sig, err := b64u.DecodeString(sigB64)
	if err != nil || len(sig) == 0 || len(sig) > 80 {
		return false
	}
	digest := sha256.Sum256(sessionMessage(hostID, sid, ch, nonce))
	return ecdsa.VerifyASN1(pub, digest[:], sig)
}
