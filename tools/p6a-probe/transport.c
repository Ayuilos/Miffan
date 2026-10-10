// SPDX-License-Identifier: GPL-3.0-or-later
#include "transport.h"
#include "errors.h"
#include <arpa/inet.h>
#include <curl/curl.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <openssl/crypto.h>
#include <openssl/pem.h>
#include <openssl/ssl.h>

#define RESPONSE_LIMIT (4U * 1024U * 1024U)
volatile sig_atomic_t probe_interrupted;
bool probe_pairing;
const char *probe_client_name = "miffan-p6a";
unsigned short probe_https_port = 47984;
static SSL_CTX *tls;
static unsigned char expected_pin[32];
static bool have_pin;
static char error_text[256];

static int failure(const char *text) {
    snprintf(error_text, sizeof(error_text), "%s", text);
    gs_error = error_text;
    return GS_FAILED;
}
static int path_for(char *out, const char *dir, const char *name) {
    return snprintf(out, PATH_MAX, "%s/%s", dir, name) < PATH_MAX ? 0 : failure("Key directory path is too long");
}
static int private_file(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != getuid() || (st.st_mode & 077) != 0)
        return failure("Identity/pin files must be owned regular files with permissions 0600 (no symlinks)");
    return GS_OK;
}
int probe_prepare_keydir(const char *dir, bool pairing) {
    struct stat st;
    if (lstat(dir, &st) != 0) {
        if (!pairing || errno != ENOENT || mkdir(dir, 0700) != 0)
            return failure("Cannot open key directory; run pair with a new private directory first");
        if (lstat(dir, &st) != 0) return failure("Cannot stat key directory");
    }
    if (!S_ISDIR(st.st_mode) || st.st_uid != getuid() || (st.st_mode & 077) != 0)
        return failure("Key directory must be owned and have permissions 0700 (no symlinks)");
    char cert[PATH_MAX], key[PATH_MAX];
    if (path_for(cert, dir, CERTIFICATE_FILE_NAME) || path_for(key, dir, KEY_FILE_NAME)) return GS_FAILED;
    struct stat a, b;
    bool has_cert = lstat(cert, &a) == 0, has_key = lstat(key, &b) == 0;
    if (has_cert != has_key || (!pairing && !has_cert))
        return failure("Incomplete/missing client identity; use pair with a fresh key directory");
    if (has_cert && (private_file(cert) || private_file(key))) return GS_FAILED;
    char id[PATH_MAX];
    if (path_for(id, dir, "uniqueid.dat")) return GS_FAILED;
    if (lstat(id, &st) == 0 && private_file(id)) return GS_FAILED;
    return GS_OK;
}
static FILE *create_private(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (fd < 0) return NULL;
    FILE *f = fdopen(fd, "w");
    if (!f) close(fd);
    return f;
}
int probe_write_identity(const char *dir, X509 *cert, EVP_PKEY *key) {
    char c[PATH_MAX], k[PATH_MAX];
    if (path_for(c, dir, CERTIFICATE_FILE_NAME) || path_for(k, dir, KEY_FILE_NAME)) return GS_FAILED;
    FILE *kf = create_private(k);
    if (!kf) return failure("Cannot create private client key");
    int ok = PEM_write_PrivateKey(kf, key, NULL, NULL, 0, NULL, NULL);
    if (fclose(kf) != 0) ok = 0;
    if (!ok) { unlink(k); return failure("Cannot write private client key"); }
    FILE *cf = create_private(c);
    if (!cf) { unlink(k); return failure("Cannot create client certificate"); }
    ok = PEM_write_X509(cf, cert);
    if (fclose(cf) != 0) ok = 0;
    if (!ok) { unlink(c); unlink(k); return failure("Cannot write client certificate"); }
    return GS_OK;
}
static int cert_digest(X509 *cert, unsigned char out[32]) {
    unsigned int size = 0;
    return cert && X509_digest(cert, EVP_sha256(), out, &size) == 1 && size == 32 ? GS_OK : failure("Cannot hash host certificate DER");
}
static int load_pin(const char *dir) {
    char path[PATH_MAX], text[67] = {0};
    if (path_for(path, dir, "host-cert.sha256")) return GS_FAILED;
    struct stat st;
    if (lstat(path, &st) != 0) {
        if (probe_pairing && errno == ENOENT) return GS_OK;
        return failure("Missing host-cert.sha256; complete pair before stream");
    }
    if (private_file(path)) return GS_FAILED;
    FILE *f = fopen(path, "r");
    if (!f) return failure("Cannot read host certificate pin");
    size_t n = fread(text, 1, sizeof(text)-1, f);
    fclose(f);
    if ((n != 64 && !(n == 65 && text[64] == '\n')) || strspn(text, "0123456789abcdefABCDEF") < 64)
        return failure("Invalid host-cert.sha256 (expected 64 hex digits)");
    for (int i = 0; i < 32; ++i) {
        unsigned int byte;
        if (sscanf(text + i*2, "%2x", &byte) != 1) return failure("Invalid certificate pin");
        expected_pin[i] = (unsigned char)byte;
    }
    have_pin = true;
    return GS_OK;
}
int probe_pair_certificate(const char *pem) {
    BIO *b = BIO_new_mem_buf(pem, -1);
    X509 *cert = b ? PEM_read_bio_X509(b, NULL, NULL, NULL) : NULL;
    unsigned char pin[32];
    int ret = cert_digest(cert, pin);
    X509_free(cert);
    BIO_free(b);
    if (ret != GS_OK) return ret;
    if (have_pin && CRYPTO_memcmp(expected_pin, pin, 32) != 0)
        return failure("Host certificate SHA-256 mismatch; existing pin was preserved");
    memcpy(expected_pin, pin, 32);
    have_pin = true;
    return GS_OK;
}
int probe_save_pin(const char *dir) {
    char path[PATH_MAX], hex[65];
    if (!have_pin || path_for(path, dir, "host-cert.sha256")) return GS_FAILED;
    for (int i = 0; i < 32; ++i) snprintf(hex + i*2, 3, "%02x", expected_pin[i]);
    FILE *f = create_private(path);
    if (!f && errno == EEXIST) {
        unsigned char saved[32];
        memcpy(saved, expected_pin, 32);
        if (load_pin(dir) || CRYPTO_memcmp(saved, expected_pin, 32) != 0)
            return failure("Refusing to overwrite a different host pin");
    } else if (!f) return failure("Cannot create host certificate pin");
    else {
        bool ok = fprintf(f, "%s\n", hex) == 65;
        if (fclose(f) != 0) ok = false;
        if (!ok) { unlink(path); return failure("Cannot save host certificate pin"); }
    }
    printf("host_cert_der_sha256=%s\n", hex);
    return GS_OK;
}
int probe_http_init(const char *dir) {
    have_pin = false;
    if (load_pin(dir)) return GS_FAILED;
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) return failure("curl initialization failed");
    tls = SSL_CTX_new(TLS_client_method());
    if (!tls) return failure("TLS initialization failed");
    SSL_CTX_set_min_proto_version(tls, TLS1_2_VERSION);
    SSL_CTX_set_verify(tls, SSL_VERIFY_NONE, NULL); // Exact DER pin below, before HTTP.
    SSL_CTX_set_options(tls, SSL_OP_IGNORE_UNEXPECTED_EOF);
    char c[PATH_MAX], k[PATH_MAX];
    if (path_for(c, dir, CERTIFICATE_FILE_NAME) || path_for(k, dir, KEY_FILE_NAME)) return GS_FAILED;
    if (SSL_CTX_use_certificate_file(tls, c, SSL_FILETYPE_PEM) != 1 ||
        SSL_CTX_use_PrivateKey_file(tls, k, SSL_FILETYPE_PEM) != 1 || SSL_CTX_check_private_key(tls) != 1)
        return failure("Cannot initialize client TLS identity");
    return GS_OK;
}
void probe_http_cleanup(void) { SSL_CTX_free(tls); tls = NULL; curl_global_cleanup(); }
static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + t.tv_nsec / 1e9;
}
static int wait_fd(int fd, short events, double deadline) {
    while (!probe_interrupted && now() < deadline) {
        struct pollfd p = {fd, events, 0};
        int r = poll(&p, 1, 100);
        if (r > 0) return GS_OK;
        if (r < 0 && errno != EINTR) return failure("Local TLS socket poll failed");
    }
    return failure(probe_interrupted ? "Interrupted" : "Local forwarding/TLS request timed out");
}
static int ssl_wait(SSL *ssl, int result, double deadline) {
    int e = SSL_get_error(ssl, result);
    if (e != SSL_ERROR_WANT_READ && e != SSL_ERROR_WANT_WRITE) return failure("TLS handshake/read/write failed");
    return wait_fd(SSL_get_fd(ssl), e == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT, deadline);
}
static size_t append_body(void *buf, size_t size, size_t count, void *ctx) {
    PHTTP_DATA d = ctx;
    if (size && count > SIZE_MAX / size) return 0;
    size_t n = size * count;
    if (n > RESPONSE_LIMIT - d->size) return 0;
    char *p = realloc(d->memory, d->size + n + 1);
    if (!p) return 0;
    d->memory = p;
    memcpy(p + d->size, buf, n);
    d->size += n;
    p[d->size] = 0;
    return n;
}
static int progress(void *ctx, curl_off_t a, curl_off_t b, curl_off_t c, curl_off_t d) { return probe_interrupted != 0; }
static int plain_request(const char *url, PHTTP_DATA data) {
    CURL *c = curl_easy_init();
    if (!c) return failure("Cannot create HTTP request");
    curl_easy_setopt(c, CURLOPT_URL, url);
    curl_easy_setopt(c, CURLOPT_PROXY, "");
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(c, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 3L);
    curl_easy_setopt(c, CURLOPT_TIMEOUT, probe_pairing ? 180L : 20L);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, append_body);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, data);
    curl_easy_setopt(c, CURLOPT_XFERINFOFUNCTION, progress);
    curl_easy_setopt(c, CURLOPT_NOPROGRESS, 0L);
    CURLcode res = curl_easy_perform(c);
    long status = 0;
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &status);
    curl_easy_cleanup(c);
    if (res != CURLE_OK) return failure(curl_easy_strerror(res));
    return status == 200 ? GS_OK : failure("Local HTTP forward returned a non-200 status");
}
static int parse_response(PHTTP_DATA raw, PHTTP_DATA data) {
    if (!raw->memory) return failure("Empty HTTPS response");
    char *body = strstr(raw->memory, "\r\n\r\n");
    int status = 0;
    if (!body || sscanf(raw->memory, "HTTP/%*s %d", &status) != 1 || status != 200)
        return failure("Malformed/non-200 HTTPS response");
    size_t length = raw->size - (size_t)(body + 4 - raw->memory);
    *body = 0;
    body += 4;
    bool chunked = false;
    long long content_length = -1;
    for (char *h = strstr(raw->memory, "\r\n"); h && h[2]; h = strstr(h + 2, "\r\n")) {
        h += 2;
        if (!strncasecmp(h, "Content-Length:", 15)) content_length = strtoll(h + 15, NULL, 10);
        if (!strncasecmp(h, "Transfer-Encoding: chunked", strlen("Transfer-Encoding: chunked"))) chunked = true;
        h -= 2;
    }
    if (!chunked) {
        if (content_length >= 0 && (unsigned long long)content_length != length) return failure("Truncated HTTPS response");
        return append_body(body, 1, length, data) == length ? GS_OK : failure("HTTPS response too large");
    }
    char *end = body + length;
    while (body < end) {
        char *line = strstr(body, "\r\n"), *parsed;
        if (!line) break;
        unsigned long long n = strtoull(body, &parsed, 16);
        if (parsed == body || (parsed != line && *parsed != ';')) break;
        body = line + 2;
        if (!n) return GS_OK;
        if (n > (unsigned long long)(end - body) || (size_t)(end - body) - (size_t)n < 2 ||
            body[n] != '\r' || body[n+1] != '\n') break;
        if (append_body(body, 1, (size_t)n, data) != n) break;
        body += (size_t)n + 2;
    }
    return failure("Invalid chunked HTTPS response");
}
static int tls_request(unsigned short port, const char *path, PHTTP_DATA data) {
    if (!have_pin) return failure("Refusing HTTPS without an authenticated host certificate pin");
    int fd = socket(AF_INET, SOCK_STREAM, 0), result = GS_FAILED;
    SSL *ssl = NULL;
    HTTP_DATA raw = {0};
    if (fd < 0) return failure("Cannot create local TLS socket");
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
    if (fcntl(fd, F_SETFL, O_NONBLOCK) != 0) { failure("Cannot configure TLS socket"); goto done; }
    struct sockaddr_in a = {.sin_family=AF_INET, .sin_port=htons(port), .sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    if (connect(fd, (struct sockaddr*)&a, sizeof(a)) != 0) {
        if (errno != EINPROGRESS) { failure("Cannot connect to local HTTPS forward (connection refused)"); goto done; }
        if (wait_fd(fd, POLLOUT, now()+3)) goto done;
        int e = 0; socklen_t n = sizeof(e);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &n) != 0 || e != 0) {
            failure("Cannot connect to local HTTPS forward (connection refused)"); goto done;
        }
    }
    ssl = SSL_new(tls);
    if (!ssl || SSL_set_fd(ssl, fd) != 1) { failure("Cannot create TLS connection"); goto done; }
    double deadline = now()+20;
    int r;
    while ((r=SSL_connect(ssl)) != 1) if (ssl_wait(ssl, r, deadline)) goto done;
    X509 *peer = SSL_get1_peer_certificate(ssl);
    unsigned char pin[32];
    int ok = cert_digest(peer, pin);
    X509_free(peer);
    if (ok || CRYPTO_memcmp(pin, expected_pin, 32) != 0) {
        failure("Host certificate DER SHA-256 mismatch; HTTPS request was not sent"); goto done;
    }
    // Authentication precedes all application data, including /launch rikey.
    size_t size = strlen(path)+128;
    char *request = malloc(size);
    if (!request) { failure("Out of memory"); goto done; }
    size_t len = (size_t)snprintf(request, size, "GET %s HTTP/1.0\r\nHost: 127.0.0.1:%u\r\nConnection: close\r\n\r\n", path, port);
    size_t sent = 0;
    while (sent < len && !probe_interrupted) {
        r = SSL_write(ssl, request+sent, (int)(len-sent));
        if (r > 0) sent += (size_t)r;
        else if (ssl_wait(ssl, r, deadline)) break;
    }
    OPENSSL_cleanse(request, size);
    free(request);
    if (sent != len || probe_interrupted) { failure("HTTPS request interrupted/failed"); goto done; }
    char buf[8192];
    while (!probe_interrupted) {
        r = SSL_read(ssl, buf, sizeof(buf));
        if (r > 0) {
            if (append_body(buf, 1, (size_t)r, &raw) != (size_t)r) { failure("HTTPS response too large"); goto done; }
        } else if (SSL_get_error(ssl, r) == SSL_ERROR_ZERO_RETURN) break;
        else if (ssl_wait(ssl, r, deadline)) goto done;
    }
    result = probe_interrupted ? failure("Interrupted") : parse_response(&raw, data);
done:
    free(raw.memory);
    SSL_free(ssl);
    close(fd);
    return result;
}
int probe_http_request(const char *url, PHTTP_DATA data) {
    free(data->memory); data->memory = NULL; data->size = 0;
    const char *prefix = !strncmp(url, "https://127.0.0.1:", 18) ? "https://127.0.0.1:" : "http://127.0.0.1:";
    size_t prefixlen = strlen(prefix);
    if (strncmp(url, prefix, prefixlen)) return failure("HTTP transport accepts only 127.0.0.1 SSH forwards");
    char *end;
    long port = strtol(url+prefixlen, &end, 10);
    if (port < 1 || port > 65535 || *end != '/' || strchr(end, '\r') || strchr(end, '\n'))
        return failure("Invalid local HTTP URL");
    bool allowed = false;
    const char *routes[] = {"/serverinfo?", "/applist?", "/launch?", "/resume?", "/pair?"};
    for (size_t i=0; i<sizeof(routes)/sizeof(routes[0]); ++i)
        if (!strncmp(end, routes[i], strlen(routes[i]))) allowed = true;
    if (!allowed) return failure("Disallowed HTTP operation (cancel/unpair are never permitted)");
    if (probe_interrupted) return failure("Interrupted");
    return prefix[4] == 's' ? tls_request((unsigned short)port, end, data) : plain_request(url, data);
}
