#include <stdlib.h>
#include <string.h>
#include "dns_sd.h"

typedef struct { uint16_t len; uint8_t buf[1300]; } txt_t;

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *r, DNSServiceFlags f, uint32_t i, const char *n, const char *t,
                                       const char *d, const char *h, uint16_t p, uint16_t tl, const void *tx,
                                       DNSServiceRegisterReply cb, void *c) {
    if (r) *r = NULL;
    return 0;
}
void DNSServiceRefDeallocate(DNSServiceRef r) {}
int DNSServiceRefSockFD(DNSServiceRef r) { return -1; }
DNSServiceErrorType DNSServiceProcessResult(DNSServiceRef r) { return 0; }

void TXTRecordCreate(TXTRecordRef *r, uint16_t l, void *b) {
    r->ForceNaturalAlignment = calloc(1, sizeof(txt_t));
}
DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *r, const char *k, uint8_t vl, const void *v) {
    txt_t *t = (txt_t *) r->ForceNaturalAlignment;
    size_t kl = strlen(k), n = kl + 1 + vl;
    if (!t || kl == 0 || n > 255 || t->len + 1 + n > sizeof(t->buf)) return -1;
    t->buf[t->len++] = (uint8_t) n;
    memcpy(t->buf + t->len, k, kl); t->len += kl;
    t->buf[t->len++] = '=';
    memcpy(t->buf + t->len, v, vl); t->len += vl;
    return 0;
}
uint16_t TXTRecordGetLength(const TXTRecordRef *r) { return ((txt_t *) r->ForceNaturalAlignment)->len; }
const void *TXTRecordGetBytesPtr(const TXTRecordRef *r) { return ((txt_t *) r->ForceNaturalAlignment)->buf; }
void TXTRecordDeallocate(TXTRecordRef *r) { free(r->ForceNaturalAlignment); r->ForceNaturalAlignment = NULL; }
