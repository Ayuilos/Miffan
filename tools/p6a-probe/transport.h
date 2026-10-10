#pragma once
#include <signal.h>
#include <stdbool.h>
#include <openssl/x509.h>
#include "http.h"

extern volatile sig_atomic_t probe_interrupted;
extern bool probe_pairing;
extern const char *probe_client_name;
extern unsigned short probe_https_port;

int probe_http_init(const char *keydir);
int probe_http_request(const char *url, PHTTP_DATA data);
void probe_http_cleanup(void);
int probe_pair_certificate(const char *pem);
int probe_save_pin(const char *keydir);
int probe_write_identity(const char *keydir, X509 *cert, EVP_PKEY *key);
int probe_prepare_keydir(const char *keydir, bool pairing);
