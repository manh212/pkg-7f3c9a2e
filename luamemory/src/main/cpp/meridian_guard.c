/*
 * Native runtime integrity, direct on-disk APK Signature Scheme v2/v3 verification,
 * HMAC-SHA256 lease signing coupled to integrity state, fail-closed anti-hook/anti-debugger
 * checks with monotonic TTL caching, and dynamic JNI_OnLoad registration.
 */
#include <ctype.h>
#include <fcntl.h>
#include <jni.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MERIDIAN_SEAL_OK ((jint) 0x5A3C9E17)
#define MERIDIAN_ENV_OK  ((jint) 0x3E7B1D49)
#define ENV_CACHE_TTL_NS ((int64_t) 30000000000LL)

static _Atomic int g_seal_state = 0;
static _Atomic int g_env_state = 0;
static _Atomic int64_t g_env_last_ns = 0;

/* XOR-masked SHA-256 digest of the official release signing certificate. */
static const uint8_t MASKED_CERT_SHA256[32] = {
  0xd8u, 0x1au, 0xb1u, 0x91u, 0xb3u, 0xe9u, 0x30u, 0x5du, 0x3du, 0xb3u, 0xd2u, 0x2eu, 0xfau, 0xddu, 0xa5u, 0x3cu, 0x18u, 0xeeu, 0x23u, 0xd6u, 0x9du, 0x3eu, 0x11u, 0x4du, 0x14u, 0xe9u, 0xfeu, 0x9bu, 0x93u, 0x98u, 0xfeu, 0xd2u
};

/* XOR-masked official applicationId ("io.github.aaron_gh.meridian"). */
static const uint8_t MASKED_PACKAGE_NAME[27] = {
  0x3au, 0x0bu, 0x5bu, 0xe1u, 0xfeu, 0xdcu, 0xd1u, 0xbfu, 0xb9u, 0xc2u, 0x9cu, 0x6fu, 0x6du, 0x5fu, 0x2fu, 0x0du, 0x04u, 0x1cu, 0xabu, 0xfbu, 0xc2u, 0xcau, 0xa0u, 0xbeu, 0x82u, 0x9du, 0x63u
};

/* XOR-masked 32-byte key for HMAC-SHA256 lease verification. */
static const uint8_t MASKED_HMAC_KEY[32] = {
  0x57u, 0x01u, 0x11u, 0x2du, 0xfdu, 0x9cu, 0x24u, 0x3bu, 0x72u, 0x24u, 0xedu, 0x4au, 0x80u, 0xbeu, 0xc7u, 0x9fu, 0xe6u, 0x64u, 0xe6u, 0x35u, 0xedu, 0xb2u, 0xe9u, 0x25u, 0xadu, 0x19u, 0xf5u, 0x01u, 0xdeu, 0x90u, 0xb7u, 0x02u
};

/* XOR-masked JNI registration strings (camouflaged method names). */
static const uint8_t MASKED_CLS_LUAMEM[52] = { 0x5eu, 0x25u, 0x3au, 0x4bu, 0x16u, 0x11u, 0xe4u, 0xffu, 0xc9u, 0xd7u, 0x90u, 0xadu, 0xb7u, 0x82u, 0x81u, 0x6fu, 0x64u, 0x7eu, 0x08u, 0x55u, 0x22u, 0x2du, 0x3eu, 0x1bu, 0x06u, 0xebu, 0xedu, 0xf5u, 0xc5u, 0xdfu, 0xb7u, 0xa9u, 0xf2u, 0x86u, 0x82u, 0x65u, 0x7cu, 0x7bu, 0x46u, 0x57u, 0x37u, 0x2bu, 0x70u, 0x20u, 0x0cu, 0xe7u, 0xdeu, 0xc5u, 0xc0u, 0xd5u, 0xb5u, 0xadu };
static const uint8_t MASKED_M_SEAL[15] = { 0x2fu, 0x35u, 0x13u, 0x13u, 0xfbu, 0xc5u, 0xf0u, 0xaeu, 0xbcu, 0x8fu, 0x94u, 0x42u, 0x4au, 0x57u, 0x27u };
static const uint8_t MASKED_SIG_SEAL[23] = { 0x01u, 0x0cu, 0x3du, 0x0fu, 0xf3u, 0xfdu, 0x9cu, 0xa6u, 0x80u, 0x96u, 0x68u, 0x09u, 0x6eu, 0x20u, 0x19u, 0xebu, 0xf7u, 0xd7u, 0xfcu, 0x85u, 0xb7u, 0x25u, 0x6au };
static const uint8_t MASKED_M_MAC[16] = { 0x19u, 0xe3u, 0xf9u, 0xf1u, 0xd5u, 0xcbu, 0xe9u, 0xabu, 0xa0u, 0xb6u, 0xa1u, 0x99u, 0x9cu, 0x63u, 0x62u, 0x68u };
static const uint8_t MASKED_SIG_MAC[42] = { 0x73u, 0x20u, 0x17u, 0xefu, 0xe9u, 0xd1u, 0xeeu, 0xbeu, 0x82u, 0x9au, 0x62u, 0x39u, 0x74u, 0x4cu, 0x3bu, 0x33u, 0x05u, 0x1bu, 0xb6u, 0xd4u, 0xe5u, 0x8au, 0x98u, 0xcbu, 0xbfu, 0x6eu, 0x74u, 0x50u, 0x56u, 0x67u, 0x35u, 0x0bu, 0x15u, 0xebu, 0xb2u, 0xfdu, 0xcbu, 0xa2u, 0x88u, 0x9cu, 0x64u, 0x2fu };
static const uint8_t MASKED_M_ENV[15] = { 0xedu, 0xf1u, 0xe9u, 0xc3u, 0xc1u, 0xa1u, 0x81u, 0xb1u, 0x84u, 0x94u, 0x56u, 0x66u, 0x7eu, 0x58u, 0x5cu };
static const uint8_t MASKED_SIG_ENV[3] = { 0x37u, 0x0fu, 0x64u };

static const uint8_t MASKED_PATH_MAPS[15] = { 0x1cu, 0x34u, 0x27u, 0x09u, 0x14u, 0xa7u, 0xeau, 0xcfu, 0xd7u, 0xaau, 0xf2u, 0x83u, 0x9eu, 0x60u, 0x52u };
static const uint8_t MASKED_PATH_STATUS[17] = { 0x68u, 0x24u, 0x13u, 0x01u, 0x18u, 0xa7u, 0xe6u, 0xc7u, 0xc3u, 0xdau, 0xe6u, 0xa5u, 0x97u, 0x91u, 0x89u, 0x7fu, 0x64u };
static const uint8_t MASKED_PATH_CMDLINE[18] = { 0x76u, 0x1cu, 0x0du, 0xfdu, 0xc6u, 0x97u, 0xb8u, 0xbbu, 0x9du, 0x62u, 0x38u, 0x49u, 0x50u, 0x34u, 0x0fu, 0x1fu, 0xe7u, 0xf9u };
static const uint8_t MASKED_TRACER_PID[10] = { 0x7fu, 0x30u, 0x38u, 0x13u, 0xe2u, 0xecu, 0xe5u, 0xa5u, 0x87u, 0xc0u };
static const uint8_t MASKED_SIG_MAGIC[16] = { 0x2cu, 0xdau, 0xecu, 0xe4u, 0xb2u, 0x97u, 0x7cu, 0x18u, 0x17u, 0x1eu, 0xe0u, 0xcfu, 0xa2u, 0xc6u, 0x37u, 0x12u };
static const uint8_t MASKED_EXT_APK[4] = { 0x33u, 0x49u, 0x43u, 0x55u };
static const uint8_t MASKED_BASE_APK[8] = { 0x11u, 0x1bu, 0xf2u, 0xedu, 0xa1u, 0xf7u, 0xedu, 0xcfu };

/* XOR-masked active instrumentation library markers for /proc/self/maps. */
static const uint8_t MASKED_HOOK_FRIDA[5] = { 0x07u, 0x1au, 0x06u, 0x12u, 0x1cu };
static const uint8_t MASKED_HOOK_XPOSED[6] = { 0x1fu, 0x02u, 0x12u, 0xfbu, 0xf6u, 0xfau };
static const uint8_t MASKED_HOOK_LSPOSED[7] = { 0x1du, 0x0du, 0xfbu, 0xf7u, 0xd6u, 0xd7u, 0xdbu };
static const uint8_t MASKED_HOOK_SUBSTRATE[9] = { 0x0au, 0xffu, 0xf9u, 0xdfu, 0xc9u, 0xbcu, 0xbeu, 0x84u, 0x64u };
static const uint8_t MASKED_HOOK_ZYGISK[6] = { 0xf1u, 0xe1u, 0xc2u, 0xdbu, 0xccu, 0xa7u };
static const uint8_t MASKED_HOOK_GADGET[6] = { 0xf6u, 0xc3u, 0xd7u, 0xa3u, 0xb0u, 0x92u };
static const uint8_t MASKED_HOOK_EDXPOSED[8] = { 0xc6u, 0xd2u, 0xb1u, 0xacu, 0x80u, 0x71u, 0x70u, 0x4cu };
static const uint8_t MASKED_HOOK_RIRU[4] = { 0xc7u, 0xa5u, 0x91u, 0x8fu };

__attribute__((noinline))
static void unmask_str(const uint8_t *src, size_t len, unsigned base, unsigned step, char *dst) {
  volatile unsigned vb = base;
  volatile unsigned vs = step;
  unsigned b = vb;
  unsigned s = vs;
  for (size_t i = 0; i < len; ++i) {
    dst[i] = (char) (src[i] ^ ((b + (unsigned) i * s) & 0xFFu));
  }
  dst[len] = '\0';
}

static int64_t monotonic_now_ns(void) {
  struct timespec ts;
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
  return (int64_t) ts.tv_sec * 1000000000LL + (int64_t) ts.tv_nsec;
}

static int contains_icase(const char *haystack, const char *needle) {
  size_t nlen = strlen(needle);
  if (nlen == 0) return 0;
  for (const char *p = haystack; *p != '\0'; ++p) {
    size_t i = 0;
    while (i < nlen && p[i] != '\0' &&
           tolower((unsigned char) p[i]) == tolower((unsigned char) needle[i])) {
      ++i;
    }
    if (i == nlen) return 1;
  }
  return 0;
}

/* Compact FIPS 180-4 SHA-256 implementation (zero external dependencies). */
static const uint32_t SHA256_K[64] = {
  0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
  0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
  0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
  0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
  0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
  0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
  0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
  0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
  0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
  0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
  0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
  0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
  0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
  0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
  0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
  0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

static inline uint32_t rotr32(uint32_t x, uint32_t n) {
  return (x >> n) | (x << (32u - n));
}

static void sha256_transform(uint32_t state[8], const uint8_t block[64]) {
  uint32_t w[64];
  for (int i = 0; i < 16; ++i) {
    w[i] = ((uint32_t) block[i * 4] << 24)
         | ((uint32_t) block[i * 4 + 1] << 16)
         | ((uint32_t) block[i * 4 + 2] << 8)
         | ((uint32_t) block[i * 4 + 3]);
  }
  for (int i = 16; i < 64; ++i) {
    uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
    uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
  uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
  for (int i = 0; i < 64; ++i) {
    uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
    uint32_t ch = (e & f) ^ ((~e) & g);
    uint32_t temp1 = h + S1 + ch + SHA256_K[i] + w[i];
    uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
    uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
    uint32_t temp2 = S0 + maj;
    h = g; g = f; f = e; e = d + temp1;
    d = c; c = b; b = a; a = temp1 + temp2;
  }
  state[0] += a; state[1] += b; state[2] += c; state[3] += d;
  state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

static void sha256_compute(const uint8_t *data, size_t len, uint8_t out[32]) {
  uint32_t state[8] = {
    0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
    0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
  };
  uint8_t buf[64];
  size_t offset = 0;
  while (len - offset >= 64) {
    sha256_transform(state, data + offset);
    offset += 64;
  }
  size_t rem = len - offset;
  if (rem > 0) memcpy(buf, data + offset, rem);
  buf[rem++] = 0x80u;
  if (rem > 56) {
    memset(buf + rem, 0, 64 - rem);
    sha256_transform(state, buf);
    rem = 0;
  }
  memset(buf + rem, 0, 56 - rem);
  uint64_t bit_len = (uint64_t) len * 8u;
  for (int i = 0; i < 8; ++i) {
    buf[56 + i] = (uint8_t) (bit_len >> (56 - i * 8));
  }
  sha256_transform(state, buf);
  for (int i = 0; i < 8; ++i) {
    out[i * 4]     = (uint8_t) (state[i] >> 24);
    out[i * 4 + 1] = (uint8_t) (state[i] >> 16);
    out[i * 4 + 2] = (uint8_t) (state[i] >> 8);
    out[i * 4 + 3] = (uint8_t) (state[i]);
  }
}

/* RFC 2104 / RFC 4231 HMAC-SHA256 for 32-byte key. */
static void hmac_sha256(const uint8_t key[32], const uint8_t *msg, size_t msg_len, uint8_t out[32]) {
  uint8_t ipad[64], opad[64];
  memset(ipad, 0x36, 64);
  memset(opad, 0x5c, 64);
  for (int i = 0; i < 32; ++i) {
    ipad[i] ^= key[i];
    opad[i] ^= key[i];
  }
  uint8_t *inner_buf = (uint8_t *) malloc(64 + msg_len);
  if (inner_buf == NULL) {
    memset(out, 0, 32);
    return;
  }
  memcpy(inner_buf, ipad, 64);
  if (msg_len > 0) memcpy(inner_buf + 64, msg, msg_len);
  uint8_t inner_hash[32];
  sha256_compute(inner_buf, 64 + msg_len, inner_hash);
  free(inner_buf);

  uint8_t outer_buf[64 + 32];
  memcpy(outer_buf, opad, 64);
  memcpy(outer_buf + 64, inner_hash, 32);
  sha256_compute(outer_buf, 96, out);
}

static int cert_matches_expected(const uint8_t *cert, size_t cert_len) {
  if (cert == NULL || cert_len == 0) return 0;
  uint8_t actual_sha256[32];
  sha256_compute(cert, cert_len, actual_sha256);
  uint8_t diff = 0;
  for (size_t i = 0; i < 32; ++i) {
    uint8_t expected = (uint8_t) (MASKED_CERT_SHA256[i] ^ ((0xA7u + (unsigned) i * 29u) & 0xFFu));
    diff |= (uint8_t) (actual_sha256[i] ^ expected);
  }
  return diff == 0;
}

static inline uint32_t read_le32(const uint8_t *p) {
  return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

static inline uint64_t read_le64(const uint8_t *p) {
  return (uint64_t) read_le32(p) | ((uint64_t) read_le32(p + 4) << 32);
}

/*
 * Parse the first X.509 certificate from an APK Signature Scheme v2/v3 signer block
 * and verify its SHA-256 digest directly in C.
 */
static int verify_v2_v3_scheme_value(const uint8_t *val, size_t val_len) {
  if (val_len < 16) return 0;
  uint32_t signers_len = read_le32(val);
  if (4u + signers_len > val_len || signers_len < 12) return 0;
  size_t pos = 4;
  uint32_t signer_len = read_le32(val + pos);
  pos += 4;
  if (pos + signer_len > 4u + signers_len || signer_len < 8) return 0;
  uint32_t signed_data_len = read_le32(val + pos);
  pos += 4;
  if (pos + signed_data_len > val_len || signed_data_len < 8) return 0;
  size_t sd_end = pos + signed_data_len;
  uint32_t digests_len = read_le32(val + pos);
  pos += 4;
  if (pos + digests_len + 4 > sd_end) return 0;
  pos += digests_len;
  uint32_t certs_len = read_le32(val + pos);
  pos += 4;
  if (pos + certs_len > sd_end || certs_len < 4) return 0;
  uint32_t cert_len = read_le32(val + pos);
  pos += 4;
  if (cert_len == 0 || pos + cert_len > sd_end) return 0;
  return cert_matches_expected(val + pos, (size_t) cert_len);
}

/*
 * Directly open the APK on disk and verify its APK Signing Block v2/v3 certificate,
 * including leading/trailing block size consistency.
 */
static int verify_apk_file_signature(const char *apk_path) {
  int fd = open(apk_path, O_RDONLY);
  if (fd < 0) return 0;
  off_t file_size = lseek(fd, 0, SEEK_END);
  if (file_size < 64) {
    close(fd);
    return 0;
  }
  size_t tail_len = file_size > 65558 ? 65558 : (size_t) file_size;
  uint8_t *tail = (uint8_t *) malloc(tail_len);
  if (tail == NULL) {
    close(fd);
    return 0;
  }
  if (pread(fd, tail, tail_len, file_size - (off_t) tail_len) != (ssize_t) tail_len) {
    free(tail);
    close(fd);
    return 0;
  }
  int64_t cd_offset = -1;
  for (size_t i = tail_len - 22; ; --i) {
    if (read_le32(tail + i) == 0x06054b50u) {
      uint16_t comment_len = (uint16_t) tail[i + 20] | ((uint16_t) tail[i + 21] << 8);
      if (i + 22u + comment_len == tail_len) {
        cd_offset = (int64_t) read_le32(tail + i + 16);
        break;
      }
    }
    if (i == 0) break;
  }
  free(tail);
  if (cd_offset < 32 || cd_offset > file_size) {
    close(fd);
    return 0;
  }

  uint8_t footer[24];
  if (pread(fd, footer, 24, (off_t) (cd_offset - 24)) != 24) {
    close(fd);
    return 0;
  }
  char magic[sizeof(MASKED_SIG_MAGIC) + 1];
  unmask_str(MASKED_SIG_MAGIC, sizeof(MASKED_SIG_MAGIC), 0x6Du, 29u, magic);
  if (memcmp(footer + 8, magic, sizeof(MASKED_SIG_MAGIC)) != 0) {
    close(fd);
    return 0;
  }
  uint64_t block_size = read_le64(footer);
  if (block_size < 24 || block_size > 8u * 1024u * 1024u || (int64_t) (block_size + 8) > cd_offset) {
    close(fd);
    return 0;
  }
  int64_t block_start = cd_offset - (int64_t) block_size - 8;
  uint8_t header_size_bytes[8];
  if (pread(fd, header_size_bytes, 8, (off_t) block_start) != 8 ||
      read_le64(header_size_bytes) != block_size) {
    close(fd);
    return 0;
  }
  size_t pairs_len = (size_t) (block_size - 24);
  uint8_t *pairs = (uint8_t *) malloc(pairs_len);
  if (pairs == NULL) {
    close(fd);
    return 0;
  }
  if (pread(fd, pairs, pairs_len, (off_t) (block_start + 8)) != (ssize_t) pairs_len) {
    free(pairs);
    close(fd);
    return 0;
  }
  close(fd);

  int verified = 0;
  size_t off = 0;
  while (off + 12 <= pairs_len) {
    uint64_t entry_len = read_le64(pairs + off);
    off += 8;
    if (entry_len < 4 || entry_len > pairs_len - off) break;
    uint32_t id = read_le32(pairs + off);
    const uint8_t *val = pairs + off + 4;
    size_t val_len = (size_t) (entry_len - 4);
    if (id == 0x7109871au || id == 0xf05368c0u || id == 0x1b93ad61u) {
      if (verify_v2_v3_scheme_value(val, val_len)) {
        verified = 1;
        break;
      }
    }
    off += (size_t) entry_len;
  }
  free(pairs);
  return verified;
}

static int check_tracer_pid(void) {
  char path[sizeof(MASKED_PATH_STATUS) + 1];
  char tracer_key[sizeof(MASKED_TRACER_PID) + 1];
  unmask_str(MASKED_PATH_STATUS, sizeof(MASKED_PATH_STATUS), 0x47u, 13u, path);
  unmask_str(MASKED_TRACER_PID, sizeof(MASKED_TRACER_PID), 0x2Bu, 23u, tracer_key);

  FILE *fp = fopen(path, "r");
  if (fp == NULL) return 0;
  char line[256];
  int found = 0;
  int ok = 0;
  while (fgets(line, sizeof(line), fp) != NULL) {
    if (strncmp(line, tracer_key, sizeof(MASKED_TRACER_PID)) == 0) {
      long pid = strtol(line + sizeof(MASKED_TRACER_PID), NULL, 10);
      found = 1;
      ok = (pid == 0);
      break;
    }
  }
  fclose(fp);
  return found && ok;
}

static int check_process_cmdline(const char *expected_pkg) {
  char path[sizeof(MASKED_PATH_CMDLINE) + 1];
  unmask_str(MASKED_PATH_CMDLINE, sizeof(MASKED_PATH_CMDLINE), 0x59u, 19u, path);
  int fd = open(path, O_RDONLY);
  if (fd < 0) return 0;
  char buf[128];
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return 0;
  buf[n] = '\0';
  size_t elen = strlen(expected_pkg);
  if ((size_t) n < elen) return 0;
  if (memcmp(buf, expected_pkg, elen) != 0) return 0;
  char tail = buf[elen];
  return tail == '\0' || tail == ':';
}

/*
 * Fail-closed single-pass /proc/self/maps scan:
 *  1) detects active Frida/Xposed/LSPosed/Substrate/Zygisk/Gadget/EdXposed/Riru injection,
 *  2) requires mapped APK path belonging to our official packageName,
 *  3) extracts the real on-disk base.apk path (bypassing PackageManager).
 */
static int inspect_process_maps(const char *expected_pkg, char *apk_path_out, size_t apk_path_cap) {
  if (apk_path_out != NULL && apk_path_cap > 0) apk_path_out[0] = '\0';
  char maps_path[sizeof(MASKED_PATH_MAPS) + 1];
  unmask_str(MASKED_PATH_MAPS, sizeof(MASKED_PATH_MAPS), 0x33u, 17u, maps_path);

  FILE *fp = fopen(maps_path, "r");
  if (fp == NULL) return 0;

  char ext_apk[sizeof(MASKED_EXT_APK) + 1];
  char base_apk[sizeof(MASKED_BASE_APK) + 1];
  unmask_str(MASKED_EXT_APK, sizeof(MASKED_EXT_APK), 0x1Du, 11u, ext_apk);
  unmask_str(MASKED_BASE_APK, sizeof(MASKED_BASE_APK), 0x73u, 7u, base_apk);

  char h_frida[sizeof(MASKED_HOOK_FRIDA) + 1];
  char h_xposed[sizeof(MASKED_HOOK_XPOSED) + 1];
  char h_lsposed[sizeof(MASKED_HOOK_LSPOSED) + 1];
  char h_substrate[sizeof(MASKED_HOOK_SUBSTRATE) + 1];
  char h_zygisk[sizeof(MASKED_HOOK_ZYGISK) + 1];
  char h_gadget[sizeof(MASKED_HOOK_GADGET) + 1];
  char h_edxposed[sizeof(MASKED_HOOK_EDXPOSED) + 1];
  char h_riru[sizeof(MASKED_HOOK_RIRU) + 1];
  unmask_str(MASKED_HOOK_FRIDA, sizeof(MASKED_HOOK_FRIDA), 0x61u, 7u, h_frida);
  unmask_str(MASKED_HOOK_XPOSED, sizeof(MASKED_HOOK_XPOSED), 0x67u, 11u, h_xposed);
  unmask_str(MASKED_HOOK_LSPOSED, sizeof(MASKED_HOOK_LSPOSED), 0x71u, 13u, h_lsposed);
  unmask_str(MASKED_HOOK_SUBSTRATE, sizeof(MASKED_HOOK_SUBSTRATE), 0x79u, 17u, h_substrate);
  unmask_str(MASKED_HOOK_ZYGISK, sizeof(MASKED_HOOK_ZYGISK), 0x8Bu, 13u, h_zygisk);
  unmask_str(MASKED_HOOK_GADGET, sizeof(MASKED_HOOK_GADGET), 0x91u, 17u, h_gadget);
  unmask_str(MASKED_HOOK_EDXPOSED, sizeof(MASKED_HOOK_EDXPOSED), 0xA3u, 19u, h_edxposed);
  unmask_str(MASKED_HOOK_RIRU, sizeof(MASKED_HOOK_RIRU), 0xB5u, 23u, h_riru);

  char line[512];
  int matched_pkg = 0;
  int hook_detected = 0;
  while (fgets(line, sizeof(line), fp) != NULL) {
    if (contains_icase(line, h_frida) ||
        contains_icase(line, h_xposed) ||
        contains_icase(line, h_lsposed) ||
        contains_icase(line, h_substrate) ||
        contains_icase(line, h_zygisk) ||
        contains_icase(line, h_gadget) ||
        contains_icase(line, h_edxposed) ||
        contains_icase(line, h_riru)) {
      hook_detected = 1;
      break;
    }
    char *path = strchr(line, '/');
    if (path != NULL && strstr(path, ext_apk) != NULL) {
      if (strstr(path, expected_pkg) != NULL) {
        matched_pkg = 1;
        if (apk_path_out != NULL && apk_path_out[0] == '\0' && strstr(path, base_apk) != NULL) {
          size_t plen = strcspn(path, "\r\n");
          if (plen < apk_path_cap) {
            memcpy(apk_path_out, path, plen);
            apk_path_out[plen] = '\0';
          }
        }
      }
    }
  }
  fclose(fp);
  if (hook_detected) return 0;
  return matched_pkg;
}

static jint check_env_internal(void) {
  int prev = atomic_load_explicit(&g_env_state, memory_order_acquire);
  if (prev < 0) return 0;
  int64_t now = monotonic_now_ns();
  int64_t last = atomic_load_explicit(&g_env_last_ns, memory_order_relaxed);
  if (prev == 1 && now > 0 && last > 0 && (now - last) < ENV_CACHE_TTL_NS) {
    return MERIDIAN_ENV_OK;
  }

  char expected_pkg[sizeof(MASKED_PACKAGE_NAME) + 1];
  unmask_str(MASKED_PACKAGE_NAME, sizeof(MASKED_PACKAGE_NAME), 0x53u, 17u, expected_pkg);
  int ok = check_tracer_pid()
        && check_process_cmdline(expected_pkg)
        && inspect_process_maps(expected_pkg, NULL, 0);
  if (!ok) {
    atomic_store_explicit(&g_env_state, -1, memory_order_release);
    return 0;
  }
  atomic_store_explicit(&g_env_last_ns, now, memory_order_relaxed);
  atomic_store_explicit(&g_env_state, 1, memory_order_release);
  return MERIDIAN_ENV_OK;
}

static int ensure_native_seal_ok(void) {
  int state = atomic_load_explicit(&g_seal_state, memory_order_acquire);
  if (state == 1) return 1;
  if (state < 0) return 0;

  char expected_pkg[sizeof(MASKED_PACKAGE_NAME) + 1];
  unmask_str(MASKED_PACKAGE_NAME, sizeof(MASKED_PACKAGE_NAME), 0x53u, 17u, expected_pkg);
  char apk_path[512];
  int ok = check_tracer_pid()
        && check_process_cmdline(expected_pkg)
        && inspect_process_maps(expected_pkg, apk_path, sizeof(apk_path))
        && apk_path[0] != '\0'
        && verify_apk_file_signature(apk_path);
  atomic_store_explicit(&g_seal_state, ok ? 1 : -1, memory_order_release);
  if (!ok) {
    atomic_store_explicit(&g_env_state, -1, memory_order_release);
  }
  return ok;
}

int meridian_guard_is_ok(void) {
  int env_s = atomic_load_explicit(&g_env_state, memory_order_acquire);
  int seal_s = atomic_load_explicit(&g_seal_state, memory_order_acquire);
  if (env_s < 0 || seal_s < 0) return 0;
  return ensure_native_seal_ok() && (check_env_internal() == MERIDIAN_ENV_OK);
}

static jint verify_seal_bytes(const char *pkg, size_t pkg_len,
                              const uint8_t *cert, size_t cert_len) {
  if (pkg == NULL || cert == NULL || cert_len == 0) return 0;
  if (pkg_len != sizeof(MASKED_PACKAGE_NAME)) return 0;
  if (atomic_load_explicit(&g_seal_state, memory_order_acquire) < 0) return 0;
  if (!check_tracer_pid()) {
    atomic_store_explicit(&g_seal_state, -1, memory_order_release);
    return 0;
  }

  char expected_pkg[sizeof(MASKED_PACKAGE_NAME) + 1];
  uint8_t pkg_diff = 0;
  for (size_t i = 0; i < sizeof(MASKED_PACKAGE_NAME); ++i) {
    uint8_t expected = (uint8_t) (MASKED_PACKAGE_NAME[i] ^ ((0x53u + (unsigned) i * 17u) & 0xFFu));
    expected_pkg[i] = (char) expected;
    pkg_diff |= (uint8_t) (((uint8_t) pkg[i]) ^ expected);
  }
  expected_pkg[sizeof(MASKED_PACKAGE_NAME)] = '\0';
  if (pkg_diff != 0 || !check_process_cmdline(expected_pkg) || !cert_matches_expected(cert, cert_len)) {
    atomic_store_explicit(&g_seal_state, -1, memory_order_release);
    return 0;
  }

  char apk_path[512];
  if (!inspect_process_maps(expected_pkg, apk_path, sizeof(apk_path)) ||
      apk_path[0] == '\0' ||
      !verify_apk_file_signature(apk_path)) {
    atomic_store_explicit(&g_seal_state, -1, memory_order_release);
    return 0;
  }

  atomic_store_explicit(&g_seal_state, 1, memory_order_release);
  atomic_store_explicit(&g_env_last_ns, monotonic_now_ns(), memory_order_relaxed);
  atomic_store_explicit(&g_env_state, 1, memory_order_release);
  return MERIDIAN_SEAL_OK;
}

static jint JNICALL jni_verify_seal(
    JNIEnv *env, jclass clazz, jstring package_name, jbyteArray cert_bytes) {
  (void) clazz;
  if (package_name == NULL || cert_bytes == NULL) return 0;
  const char *pkg = (*env)->GetStringUTFChars(env, package_name, NULL);
  if (pkg == NULL) return 0;
  jsize cert_len = (*env)->GetArrayLength(env, cert_bytes);
  jbyte *cert = cert_len > 0 ? (*env)->GetByteArrayElements(env, cert_bytes, NULL) : NULL;
  jint result = 0;
  if (cert != NULL && cert_len > 0) {
    result = verify_seal_bytes(pkg, strlen(pkg), (const uint8_t *) cert, (size_t) cert_len);
  }
  if (cert != NULL) (*env)->ReleaseByteArrayElements(env, cert_bytes, cert, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, package_name, pkg);
  return result;
}

static jstring JNICALL jni_lease_mac(
    JNIEnv *env, jclass clazz, jstring device_id,
    jlong server_ms, jlong elapsed_ms, jlong highest_wall, jint boot_count) {
  (void) clazz;
  const char *dev = device_id != NULL ? (*env)->GetStringUTFChars(env, device_id, NULL) : NULL;
  char payload[256];
  int n = snprintf(payload, sizeof(payload), "%s|%lld|%lld|%lld|%d",
      dev != NULL ? dev : "",
      (long long) server_ms, (long long) elapsed_ms, (long long) highest_wall, (int) boot_count);
  if (dev != NULL) (*env)->ReleaseStringUTFChars(env, device_id, dev);
  if (n <= 0 || (size_t) n >= sizeof(payload)) return (*env)->NewStringUTF(env, "");

  int intact = ensure_native_seal_ok() && (check_env_internal() == MERIDIAN_ENV_OK);
  uint8_t poison = intact ? 0x00u : 0xA5u;

  uint8_t key[32];
  for (size_t i = 0; i < 32; ++i) {
    key[i] = (uint8_t) ((MASKED_HMAC_KEY[i] ^ ((0x6Bu + (unsigned) i * 37u) & 0xFFu)) ^ poison);
  }
  uint8_t mac[32];
  hmac_sha256(key, (const uint8_t *) payload, (size_t) n, mac);
  memset(key, 0, sizeof(key));

  static const char HEX[16] = "0123456789abcdef";
  char hex_out[65];
  for (int i = 0; i < 32; ++i) {
    hex_out[i * 2] = HEX[(mac[i] >> 4) & 0xFu];
    hex_out[i * 2 + 1] = HEX[mac[i] & 0xFu];
  }
  hex_out[64] = '\0';
  return (*env)->NewStringUTF(env, hex_out);
}

static jint JNICALL jni_env_status(JNIEnv *env, jclass clazz) {
  (void) env;
  (void) clazz;
  return check_env_internal();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void) reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
    return JNI_ERR;
  }
  char cls_name[sizeof(MASKED_CLS_LUAMEM) + 1];
  char m_seal[sizeof(MASKED_M_SEAL) + 1];
  char sig_seal[sizeof(MASKED_SIG_SEAL) + 1];
  char m_mac[sizeof(MASKED_M_MAC) + 1];
  char sig_mac[sizeof(MASKED_SIG_MAC) + 1];
  char m_env[sizeof(MASKED_M_ENV) + 1];
  char sig_env[sizeof(MASKED_SIG_ENV) + 1];

  unmask_str(MASKED_CLS_LUAMEM, sizeof(MASKED_CLS_LUAMEM), 0x3Du, 13u, cls_name);
  unmask_str(MASKED_M_SEAL, sizeof(MASKED_M_SEAL), 0x41u, 19u, m_seal);
  unmask_str(MASKED_SIG_SEAL, sizeof(MASKED_SIG_SEAL), 0x29u, 23u, sig_seal);
  unmask_str(MASKED_M_MAC, sizeof(MASKED_M_MAC), 0x77u, 11u, m_mac);
  unmask_str(MASKED_SIG_MAC, sizeof(MASKED_SIG_MAC), 0x5Bu, 17u, sig_mac);
  unmask_str(MASKED_M_ENV, sizeof(MASKED_M_ENV), 0x83u, 13u, m_env);
  unmask_str(MASKED_SIG_ENV, sizeof(MASKED_SIG_ENV), 0x1Fu, 7u, sig_env);

  jclass clazz = (*env)->FindClass(env, cls_name);
  if (clazz == NULL) {
    (*env)->ExceptionClear(env);
    return JNI_VERSION_1_6;
  }
  JNINativeMethod methods[3] = {
    { m_seal, sig_seal, (void *) jni_verify_seal },
    { m_mac,  sig_mac,  (void *) jni_lease_mac },
    { m_env,  sig_env,  (void *) jni_env_status }
  };
  if ((*env)->RegisterNatives(env, clazz, methods, 3) != JNI_OK) {
    (*env)->ExceptionClear(env);
  }
  return JNI_VERSION_1_6;
}
