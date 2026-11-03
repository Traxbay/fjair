/* Minimal in-memory stand-in for Apple's dns_sd.h. UxPlay builds its TXT records through this API
 * (they are served in /info); the actual Bonjour announcement is done by Android NSD (Advertiser.kt). */
#ifndef SHIM_DNS_SD_H
#define SHIM_DNS_SD_H
#include <stdint.h>
#define DNSSD_STDCALL
typedef struct _DNSServiceRef_t *DNSServiceRef;
typedef union _TXTRecordRef_t { char PrivateData[16]; char *ForceNaturalAlignment; } TXTRecordRef;
typedef uint32_t DNSServiceFlags;
typedef int32_t DNSServiceErrorType;
typedef void (*DNSServiceRegisterReply)(DNSServiceRef, DNSServiceFlags, DNSServiceErrorType,
                                        const char *, const char *, const char *, void *);
DNSServiceErrorType DNSServiceRegister(DNSServiceRef *, DNSServiceFlags, uint32_t, const char *, const char *,
                                       const char *, const char *, uint16_t, uint16_t, const void *,
                                       DNSServiceRegisterReply, void *);
void DNSServiceRefDeallocate(DNSServiceRef);
int DNSServiceRefSockFD(DNSServiceRef);
DNSServiceErrorType DNSServiceProcessResult(DNSServiceRef);
void TXTRecordCreate(TXTRecordRef *, uint16_t, void *);
DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *, const char *, uint8_t, const void *);
uint16_t TXTRecordGetLength(const TXTRecordRef *);
const void *TXTRecordGetBytesPtr(const TXTRecordRef *);
void TXTRecordDeallocate(TXTRecordRef *);
#endif
