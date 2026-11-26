/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "MeridianAAudio";
constexpr int32_t kChannelCount = 2;

// NDK constants defined explicitly so this compiles cleanly with minSdk 26 headers.
constexpr aaudio_format_t kFormatPcmI32 = 4;        // AAUDIO_FORMAT_PCM_I32 (API 31+)
constexpr int32_t kSessionIdNone = -1;              // AAUDIO_SESSION_ID_NONE (API 28+)

std::mutex gStatusMutex;
std::string gLastOpenStatus = "unattempted";
std::string gLastProbeSummary = "not_probed";

void setLastOpenStatus(const std::string& status) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  gLastOpenStatus = status;
}

void setLastProbeSummary(const std::string& summary) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  gLastProbeSummary = summary;
}

// API 28+ AAudio setters resolved dynamically so this library compiles cleanly with minSdk 26.
using SetUsageFn = void (*)(AAudioStreamBuilder*, aaudio_usage_t);
using SetContentTypeFn = void (*)(AAudioStreamBuilder*, aaudio_content_type_t);
using SetSessionIdFn = void (*)(AAudioStreamBuilder*, int32_t);
using IsMMapUsedFn = bool (*)(AAudioStream*);

SetUsageFn resolveSetUsage() {
  static auto fn = reinterpret_cast<SetUsageFn>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"));
  return fn;
}

SetContentTypeFn resolveSetContentType() {
  static auto fn =
      reinterpret_cast<SetContentTypeFn>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setContentType"));
  return fn;
}

SetSessionIdFn resolveSetSessionId() {
  static auto fn =
      reinterpret_cast<SetSessionIdFn>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setSessionId"));
  return fn;
}

IsMMapUsedFn resolveIsMMapUsed() {
  static auto fn = reinterpret_cast<IsMMapUsedFn>(dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
  return fn;
}

std::string getSystemProperty(const char* name) {
#if defined(__ANDROID__)
  char value[PROP_VALUE_MAX] = {0};
  const int len = __system_property_get(name, value);
  return len > 0 ? std::string(value, static_cast<size_t>(len)) : std::string();
#else
  (void)name;
  return std::string();
#endif
}

// Mirrors Google Oboe's SamsungExynosDeviceQuirks detection: Samsung Exynos EXCLUSIVE MMAP streams
// need at least 2 bursts bottom margin and 1 burst top margin to avoid DSP pointer glitches.
bool isSamsungExynos() {
  static const bool detected = []() {
    std::string mfr = getSystemProperty("ro.product.manufacturer");
    std::transform(mfr.begin(), mfr.end(), mfr.begin(), [](unsigned char c) {
      return static_cast<char>(std::tolower(c));
    });
    if (mfr != "samsung") return false;
    std::string arch = getSystemProperty("ro.arch");
    std::transform(arch.begin(), arch.end(), arch.begin(), [](unsigned char c) {
      return static_cast<char>(std::tolower(c));
    });
    return arch.rfind("exynos", 0) == 0;
  }();
  return detected;
}

int32_t clampBufferFramesForDevice(
    int32_t requested,
    int32_t burst,
    int32_t capacity,
    bool mmapUsed,
    aaudio_sharing_mode_t sharing) {
  int32_t minFrames = burst;
  int32_t maxFrames = capacity;
  if (isSamsungExynos() && mmapUsed && sharing == AAUDIO_SHARING_MODE_EXCLUSIVE) {
    minFrames = std::min(capacity, burst * 2);
    maxFrames = std::max(minFrames, capacity - burst);
  }
  return std::clamp(requested, minFrames, maxFrames);
}

// Maps android.media.AudioAttributes.USAGE_* constants to AAudio usage values.
aaudio_usage_t mapAudioUsage(jint androidUsage) {
  switch (androidUsage) {
    case 1:  // USAGE_MEDIA
      return AAUDIO_USAGE_MEDIA;
    case 2:  // USAGE_VOICE_COMMUNICATION
      return AAUDIO_USAGE_VOICE_COMMUNICATION;
    case 4:  // USAGE_ALARM
      return AAUDIO_USAGE_ALARM;
    case 5:  // USAGE_NOTIFICATION
      return AAUDIO_USAGE_NOTIFICATION;
    case 11:  // USAGE_ASSISTANCE_ACCESSIBILITY
      return AAUDIO_USAGE_ASSISTANCE_ACCESSIBILITY;
    case 12:  // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
      return AAUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
    case 13:  // USAGE_ASSISTANCE_SONIFICATION
      return AAUDIO_USAGE_ASSISTANCE_SONIFICATION;
    case 16:  // USAGE_ASSISTANT
      return AAUDIO_USAGE_ASSISTANT;
    default:
      return AAUDIO_USAGE_ASSISTANCE_ACCESSIBILITY;
  }
}

uint64_t nextPowerOfTwo(uint64_t value) {
  uint64_t p = 1;
  while (p < value && p < (uint64_t{1} << 30)) {
    p <<= 1;
  }
  return p;
}

inline int16_t floatToInt16(float sample) {
  const float clamped = std::clamp(sample, -1.0f, 1.0f);
  const long scaled = std::lrintf(clamped * 32767.0f);
  return static_cast<int16_t>(std::clamp(scaled, -32768L, 32767L));
}

inline int32_t floatToInt32(float sample) {
  const double clamped = std::clamp(static_cast<double>(sample), -1.0, 1.0);
  const long long scaled = std::llround(clamped * 2147483647.0);
  return static_cast<int32_t>(std::clamp(scaled, -2147483648LL, 2147483647LL));
}

struct StreamProfile {
  int32_t id;
  const char* label;
  bool useCallback;
  aaudio_sharing_mode_t sharing;
  aaudio_format_t format;
  bool setContentTypeSpeech;
  bool requestExplicitSampleRate;
  int32_t minApiLevel;
};

// Candidate AAudio profiles probed on first launch and after app/OS updates:
// - p1..p4: blocking mode (preserves Google/Pixel MMAP path)
// - p5..p8: callback mode with explicit sample rate (f32 / i16, excl / shared)
// - p9..p10: callback mode with PCM_I32 (API 31+, for modern 32-bit MMAP mixPorts)
// - p11..p12: callback mode with Unspecified sample rate (avoids HAL SRC fallback)
constexpr StreamProfile kProfiles[] = {
    {1, "p1", false, AAUDIO_SHARING_MODE_SHARED, AAUDIO_FORMAT_PCM_FLOAT, true, true, 26},
    {2, "p2", false, AAUDIO_SHARING_MODE_SHARED, AAUDIO_FORMAT_PCM_FLOAT, false, false, 26},
    {3, "p3", false, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_FORMAT_PCM_FLOAT, false, true, 26},
    {4, "p4", false, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_FORMAT_PCM_I16, false, true, 26},
    {5, "p5", true, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_FORMAT_PCM_FLOAT, false, true, 26},
    {6, "p6", true, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_FORMAT_PCM_I16, false, true, 26},
    {7, "p7", true, AAUDIO_SHARING_MODE_SHARED, AAUDIO_FORMAT_PCM_FLOAT, false, true, 26},
    {8, "p8", true, AAUDIO_SHARING_MODE_SHARED, AAUDIO_FORMAT_PCM_I16, false, true, 26},
    {9, "p9", true, AAUDIO_SHARING_MODE_EXCLUSIVE, kFormatPcmI32, false, true, 31},
    {10, "p10", true, AAUDIO_SHARING_MODE_SHARED, kFormatPcmI32, false, true, 31},
    {11, "p11", true, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_FORMAT_PCM_FLOAT, false, false, 26},
    {12, "p12", true, AAUDIO_SHARING_MODE_SHARED, AAUDIO_FORMAT_PCM_FLOAT, false, false, 26},
};

const StreamProfile* findProfile(int32_t profileId) {
  for (const auto& p : kProfiles) {
    if (p.id == profileId) return &p;
  }
  return nullptr;
}

struct StreamHandle {
  AAudioStream* stream = nullptr;
  int32_t profileId = 0;
  bool useCallback = false;
  aaudio_format_t format = AAUDIO_FORMAT_PCM_FLOAT;
  int32_t framesPerBurst = 0;
  bool mmapUsed = false;
  aaudio_sharing_mode_t actualSharing = AAUDIO_SHARING_MODE_SHARED;
  int32_t initialBufferFrames = 0;
  int32_t bufferCapacityFrames = 0;

  // Scratch buffers for blocking non-float writes.
  std::vector<int16_t> i16Scratch;
  std::vector<int32_t> i32Scratch;

  // Lock-free SPSC ring buffer (only allocated when useCallback == true).
  // Stores interleaved stereo float samples: ring.size() == ringCapacityFrames * kChannelCount.
  std::vector<float> ring;
  uint64_t ringCapacityFrames = 0;
  uint64_t ringMask = 0;
  std::atomic<uint64_t> readPos{0};
  std::atomic<uint64_t> writePos{0};
  std::atomic<int32_t> maxQueuedFrames{0};
  std::atomic<bool> hasWrittenSinceStart{false};
  std::atomic<bool> stopping{false};
  std::atomic<bool> disconnected{false};
  std::atomic<int32_t> callbackUnderruns{0};
  std::atomic<bool> writerWaiting{false};
  std::mutex writeMutex;
  std::condition_variable writeCv;
};

StreamHandle* toHandle(jlong handle) {
  return reinterpret_cast<StreamHandle*>(static_cast<uintptr_t>(handle));
}

void zeroOutput(void* audioData, aaudio_format_t format, int32_t numFrames) {
  if (audioData == nullptr || numFrames <= 0) return;
  const size_t sampleCount = static_cast<size_t>(numFrames) * kChannelCount;
  if (format == AAUDIO_FORMAT_PCM_I16) {
    std::memset(audioData, 0, sampleCount * sizeof(int16_t));
  } else if (format == kFormatPcmI32) {
    std::memset(audioData, 0, sampleCount * sizeof(int32_t));
  } else {
    std::memset(audioData, 0, sampleCount * sizeof(float));
  }
}

aaudio_data_callback_result_t onDataCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {
  auto* h = static_cast<StreamHandle*>(userData);
  if (h == nullptr || audioData == nullptr || numFrames <= 0) {
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }
  if (h->stopping.load(std::memory_order_acquire) ||
      h->disconnected.load(std::memory_order_acquire)) {
    zeroOutput(audioData, h->format, numFrames);
    return AAUDIO_CALLBACK_RESULT_STOP;
  }

  const uint64_t r = h->readPos.load(std::memory_order_relaxed);
  const uint64_t w = h->writePos.load(std::memory_order_acquire);
  const uint64_t avail = (w >= r) ? (w - r) : 0;
  const uint64_t toCopy = std::min<uint64_t>(avail, static_cast<uint64_t>(numFrames));

  if (toCopy > 0) {
    const uint64_t startIdx = r & h->ringMask;
    const uint64_t firstFrames = std::min<uint64_t>(toCopy, h->ringCapacityFrames - startIdx);
    const uint64_t secondFrames = toCopy - firstFrames;
    const size_t count1 = static_cast<size_t>(firstFrames) * kChannelCount;
    const size_t count2 = static_cast<size_t>(secondFrames) * kChannelCount;
    const float* src1 = h->ring.data() + startIdx * kChannelCount;
    const float* src2 = h->ring.data();

    if (h->format == AAUDIO_FORMAT_PCM_I16) {
      auto* out = static_cast<int16_t*>(audioData);
      for (size_t i = 0; i < count1; ++i) out[i] = floatToInt16(src1[i]);
      if (count2 > 0) {
        int16_t* out2 = out + count1;
        for (size_t i = 0; i < count2; ++i) out2[i] = floatToInt16(src2[i]);
      }
    } else if (h->format == kFormatPcmI32) {
      auto* out = static_cast<int32_t*>(audioData);
      for (size_t i = 0; i < count1; ++i) out[i] = floatToInt32(src1[i]);
      if (count2 > 0) {
        int32_t* out2 = out + count1;
        for (size_t i = 0; i < count2; ++i) out2[i] = floatToInt32(src2[i]);
      }
    } else {
      auto* out = static_cast<float*>(audioData);
      std::memcpy(out, src1, count1 * sizeof(float));
      if (count2 > 0) {
        std::memcpy(out + count1, src2, count2 * sizeof(float));
      }
    }
    h->readPos.store(r + toCopy, std::memory_order_release);
  }

  if (toCopy < static_cast<uint64_t>(numFrames)) {
    const int32_t remaining = numFrames - static_cast<int32_t>(toCopy);
    const size_t offsetSamples = static_cast<size_t>(toCopy) * kChannelCount;
    if (h->format == AAUDIO_FORMAT_PCM_I16) {
      auto* out = static_cast<int16_t*>(audioData) + offsetSamples;
      std::memset(out, 0, static_cast<size_t>(remaining) * kChannelCount * sizeof(int16_t));
    } else if (h->format == kFormatPcmI32) {
      auto* out = static_cast<int32_t*>(audioData) + offsetSamples;
      std::memset(out, 0, static_cast<size_t>(remaining) * kChannelCount * sizeof(int32_t));
    } else {
      auto* out = static_cast<float*>(audioData) + offsetSamples;
      std::memset(out, 0, static_cast<size_t>(remaining) * kChannelCount * sizeof(float));
    }
    if (h->hasWrittenSinceStart.load(std::memory_order_relaxed)) {
      h->callbackUnderruns.fetch_add(1, std::memory_order_relaxed);
    }
  }

  if (h->writerWaiting.load(std::memory_order_acquire)) {
    h->writeCv.notify_one();
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void onErrorCallback(
    AAudioStream* /*stream*/,
    void* userData,
    aaudio_result_t error) {
  auto* h = static_cast<StreamHandle*>(userData);
  if (h != nullptr) {
    h->disconnected.store(true, std::memory_order_release);
    h->writeCv.notify_all();
  }
  __android_log_print(
      ANDROID_LOG_WARN,
      kTag,
      "AAudio stream error callback: %d (%s)",
      error,
      AAudio_convertResultToText(error));
}

int32_t writeCallbackRing(
    StreamHandle* h,
    const float* samples,
    int32_t numFrames,
    int64_t timeoutNanos) {
  if (h->disconnected.load(std::memory_order_acquire)) {
    return AAUDIO_ERROR_DISCONNECTED;
  }
  if (h->stopping.load(std::memory_order_acquire)) {
    return AAUDIO_ERROR_INVALID_STATE;
  }

  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::nanoseconds(std::max<int64_t>(0, timeoutNanos));
  int32_t written = 0;

  while (written < numFrames) {
    if (h->disconnected.load(std::memory_order_acquire)) {
      return AAUDIO_ERROR_DISCONNECTED;
    }
    if (h->stopping.load(std::memory_order_acquire)) {
      return AAUDIO_ERROR_INVALID_STATE;
    }

    const uint64_t w = h->writePos.load(std::memory_order_relaxed);
    const uint64_t r = h->readPos.load(std::memory_order_acquire);
    const uint64_t used = (w >= r) ? (w - r) : 0;
    const uint64_t limit = static_cast<uint64_t>(std::max<int32_t>(1, h->maxQueuedFrames.load(std::memory_order_relaxed)));
    const uint64_t freeFrames = (limit > used) ? (limit - used) : 0;

    if (freeFrames > 0) {
      const uint64_t chunk = std::min<uint64_t>(freeFrames, static_cast<uint64_t>(numFrames - written));
      const uint64_t startIdx = w & h->ringMask;
      const uint64_t firstFrames = std::min<uint64_t>(chunk, h->ringCapacityFrames - startIdx);
      const uint64_t secondFrames = chunk - firstFrames;

      const float* src = samples + static_cast<size_t>(written) * kChannelCount;
      const size_t count1 = static_cast<size_t>(firstFrames) * kChannelCount;
      std::memcpy(h->ring.data() + startIdx * kChannelCount, src, count1 * sizeof(float));
      if (secondFrames > 0) {
        const size_t count2 = static_cast<size_t>(secondFrames) * kChannelCount;
        std::memcpy(h->ring.data(), src + count1, count2 * sizeof(float));
      }

      h->writePos.store(w + chunk, std::memory_order_release);
      h->hasWrittenSinceStart.store(true, std::memory_order_relaxed);
      written += static_cast<int32_t>(chunk);
      continue;
    }

    const auto now = std::chrono::steady_clock::now();
    if (now >= deadline) {
      break;
    }
    const auto remainingTime = deadline - now;
    const auto slice = std::min(remainingTime, std::chrono::duration_cast<std::chrono::steady_clock::duration>(std::chrono::milliseconds(2)));

    std::unique_lock<std::mutex> lock(h->writeMutex);
    h->writerWaiting.store(true, std::memory_order_release);
    // Re-check after setting writerWaiting to avoid missed notification.
    const uint64_t rNow = h->readPos.load(std::memory_order_acquire);
    const uint64_t usedNow = (w >= rNow) ? (w - rNow) : 0;
    if (usedNow >= limit &&
        !h->stopping.load(std::memory_order_acquire) &&
        !h->disconnected.load(std::memory_order_acquire)) {
      h->writeCv.wait_for(lock, slice);
    }
    h->writerWaiting.store(false, std::memory_order_relaxed);
  }

  return written;
}

const char* formatName(aaudio_format_t format) {
  switch (format) {
    case AAUDIO_FORMAT_PCM_I16:
      return "i16";
    case AAUDIO_FORMAT_PCM_FLOAT:
      return "f32";
    case kFormatPcmI32:
      return "i32";
    default:
      return "other";
  }
}

const char* sharingName(aaudio_sharing_mode_t mode) {
  return mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "excl" : "shared";
}

// Scores an opened low-latency AAudio stream: higher is better.
// True MMAP always beats legacy FastMixer; within the same tier, smaller burst and buffer win;
// ties prefer SHARED (better coexistence with system sounds), blocking (no extra ring copy),
// PCM_FLOAT (no quantization), and speech content type.
int64_t scoreHandle(const StreamHandle* h, const StreamProfile& profile) {
  int64_t score = 0;
  if (h->mmapUsed) {
    score += 1'000'000;
  }
  score -= static_cast<int64_t>(h->framesPerBurst) * 100;
  score -= static_cast<int64_t>(h->initialBufferFrames) * 10;
  if (h->actualSharing == AAUDIO_SHARING_MODE_SHARED) {
    score += 50;
  }
  if (!h->useCallback) {
    score += 20;
  }
  if (h->format == AAUDIO_FORMAT_PCM_FLOAT) {
    score += 10;
  } else if (h->format == kFormatPcmI32) {
    score += 5;
  }
  if (profile.setContentTypeSpeech) {
    score += 5;
  }
  return score;
}

void destroyHandle(StreamHandle* h) {
  if (h == nullptr) return;
  if (h->useCallback) {
    h->stopping.store(true, std::memory_order_release);
    h->writeCv.notify_all();
  }
  if (h->stream != nullptr) {
    // AAudioStream_close synchronously stops and joins the callback thread before returning.
    AAudioStream_close(h->stream);
    h->stream = nullptr;
  }
  delete h;
}

StreamHandle* tryOpenProfile(
    const StreamProfile& profile,
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts,
    bool verifyStart,
    std::string& outStatus) {
  if (android_get_device_api_level() < profile.minApiLevel) {
    outStatus = "api_unsupported";
    return nullptr;
  }

  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t res = AAudio_createStreamBuilder(&builder);
  if (res != AAUDIO_OK || builder == nullptr) {
    std::ostringstream oss;
    oss << "builder_err=" << res << "(" << AAudio_convertResultToText(res) << ")";
    outStatus = oss.str();
    __android_log_print(ANDROID_LOG_WARN, kTag, "AAudio_createStreamBuilder failed: %d", res);
    return nullptr;
  }

  auto handle = std::make_unique<StreamHandle>();
  handle->profileId = profile.id;
  handle->useCallback = profile.useCallback;
  handle->format = profile.format;

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setFormat(builder, profile.format);
  AAudioStreamBuilder_setChannelCount(builder, kChannelCount);
  if (profile.requestExplicitSampleRate && sampleRate > 0) {
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
  }
  AAudioStreamBuilder_setSharingMode(builder, profile.sharing);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  if (deviceId > 0) {
    AAudioStreamBuilder_setDeviceId(builder, deviceId);
  }
  if (profile.useCallback) {
    AAudioStreamBuilder_setDataCallback(builder, onDataCallback, handle.get());
    AAudioStreamBuilder_setErrorCallback(builder, onErrorCallback, handle.get());
  }

  if (android_get_device_api_level() >= 28) {
    if (auto setUsage = resolveSetUsage()) {
      setUsage(builder, mapAudioUsage(androidUsage));
    }
    if (profile.setContentTypeSpeech) {
      if (auto setContentType = resolveSetContentType()) {
        setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
      }
    }
    // Explicitly opt out of system audio effects session so effects never force a slow path.
    if (auto setSessionId = resolveSetSessionId()) {
      setSessionId(builder, kSessionIdNone);
    }
  }

  AAudioStream* stream = nullptr;
  res = AAudioStreamBuilder_openStream(builder, &stream);
  AAudioStreamBuilder_delete(builder);

  if (res != AAUDIO_OK || stream == nullptr) {
    std::ostringstream oss;
    oss << "open_err=" << res << "(" << AAudio_convertResultToText(res)
        << ",cb=" << (profile.useCallback ? 1 : 0)
        << ",share=" << sharingName(profile.sharing)
        << ",fmt=" << formatName(profile.format) << ")";
    outStatus = oss.str();
    __android_log_print(ANDROID_LOG_WARN, kTag, "AAudioStreamBuilder_openStream failed (%s): %s",
                        profile.label, outStatus.c_str());
    return nullptr;
  }

  const int32_t perfMode = AAudioStream_getPerformanceMode(stream);
  const int32_t actualRate = AAudioStream_getSampleRate(stream);
  const int32_t burst = std::max(1, AAudioStream_getFramesPerBurst(stream));
  const aaudio_sharing_mode_t actualSharing = AAudioStream_getSharingMode(stream);
  const aaudio_format_t actualFormat = AAudioStream_getFormat(stream);

  if (perfMode != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
    std::ostringstream oss;
    oss << "perf=" << perfMode << "(cb=" << (profile.useCallback ? 1 : 0)
        << ",share=" << sharingName(actualSharing)
        << ",fmt=" << formatName(actualFormat)
        << ",burst=" << burst << ")";
    outStatus = oss.str();
    __android_log_print(ANDROID_LOG_INFO, kTag, "AAudio %s did not grant LOW_LATENCY: %s",
                        profile.label, outStatus.c_str());
    AAudioStream_close(stream);
    return nullptr;
  }

  if (sampleRate > 0 && actualRate != sampleRate) {
    std::ostringstream oss;
    oss << "rate=" << actualRate << "!=" << sampleRate;
    outStatus = oss.str();
    __android_log_print(ANDROID_LOG_INFO, kTag, "AAudio %s sample rate mismatch: %s",
                        profile.label, outStatus.c_str());
    AAudioStream_close(stream);
    return nullptr;
  }

  bool mmapUsed = false;
  if (auto isMMap = resolveIsMMapUsed()) {
    mmapUsed = isMMap(stream);
  }
  const int32_t capacity = std::max(burst, AAudioStream_getBufferCapacityInFrames(stream));
  const int32_t requested = clampBufferFramesForDevice(
      burst * std::max(1, static_cast<int32_t>(initialBursts)),
      burst,
      capacity,
      mmapUsed,
      actualSharing);
  AAudioStream_setBufferSizeInFrames(stream, requested);
  const int32_t actualBuf = std::max(burst, AAudioStream_getBufferSizeInFrames(stream));

  handle->stream = stream;
  handle->format = actualFormat;
  handle->framesPerBurst = burst;
  handle->mmapUsed = mmapUsed;
  handle->actualSharing = actualSharing;
  handle->initialBufferFrames = actualBuf;
  handle->bufferCapacityFrames = capacity;

  if (profile.useCallback) {
    const uint64_t minCap = std::max<uint64_t>(4096, static_cast<uint64_t>(capacity) * 2);
    handle->ringCapacityFrames = nextPowerOfTwo(minCap);
    handle->ringMask = handle->ringCapacityFrames - 1;
    handle->ring.assign(static_cast<size_t>(handle->ringCapacityFrames) * kChannelCount, 0.0f);
    const int32_t maxRingQueue = static_cast<int32_t>(
        std::min<uint64_t>(static_cast<uint64_t>(INT32_MAX), handle->ringCapacityFrames - burst));
    handle->maxQueuedFrames.store(std::clamp(actualBuf, burst, std::max(burst, maxRingQueue)),
                                  std::memory_order_relaxed);
  }

  if (verifyStart) {
    aaudio_stream_state_t state = AAudioStream_getState(stream);
    aaudio_result_t startRes = AAudioStream_requestStart(stream);
    aaudio_stream_state_t nextState = state;
    if (startRes == AAUDIO_OK) {
      AAudioStream_waitForStateChange(stream, state, &nextState, 50'000'000LL);
    }
    if (startRes != AAUDIO_OK ||
        (nextState != AAUDIO_STREAM_STATE_STARTED && nextState != AAUDIO_STREAM_STATE_STARTING) ||
        handle->disconnected.load(std::memory_order_acquire)) {
      std::ostringstream oss;
      oss << "start_err=" << startRes << "(state=" << nextState << ")";
      outStatus = oss.str();
      destroyHandle(handle.release());
      return nullptr;
    }
  }

  std::ostringstream oss;
  oss << profile.label << ":ok(cb=" << (profile.useCallback ? 1 : 0)
      << ",mmap=" << (mmapUsed ? 1 : 0) << ",perf=" << perfMode
      << ",share=" << sharingName(actualSharing)
      << ",fmt=" << formatName(actualFormat)
      << ",rate=" << actualRate << ",burst=" << burst
      << ",buf=" << actualBuf << "/" << capacity
      << ",usage=" << androidUsage << ",speech=" << (profile.setContentTypeSpeech ? 1 : 0) << ")";
  outStatus = oss.str();
  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "Opened AAudio stream: %s device=%d",
      outStatus.c_str(),
      AAudioStream_getDeviceId(stream));
  return handle.release();
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeProbeBestProfile(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts) {
  int32_t bestProfileId = 0;
  int64_t bestScore = INT64_MIN;
  std::string bestDesc;
  std::ostringstream report;

  bool first = true;
  for (const auto& profile : kProfiles) {
    std::string status;
    StreamHandle* h = tryOpenProfile(
        profile, sampleRate, androidUsage, deviceId, initialBursts, /*verifyStart=*/true, status);
    if (!first) report << "; ";
    first = false;
    if (h != nullptr) {
      const int64_t score = scoreHandle(h, profile);
      report << profile.label << "=OK(mmap=" << (h->mmapUsed ? 1 : 0)
             << ",cb=" << (h->useCallback ? 1 : 0)
             << ",share=" << sharingName(h->actualSharing)
             << ",fmt=" << formatName(h->format)
             << ",burst=" << h->framesPerBurst
             << ",buf=" << h->initialBufferFrames << ")";
      if (score > bestScore) {
        bestScore = score;
        bestProfileId = profile.id;
        bestDesc = status;
      }
      destroyHandle(h);
    } else {
      report << profile.label << "=" << status;
    }
  }

  std::ostringstream full;
  if (bestProfileId > 0) {
    full << "best=" << bestDesc << " | probe=[" << report.str() << "]";
  } else {
    full << "best=AudioTrack | probe=[" << report.str() << "]";
  }
  setLastProbeSummary(full.str());
  __android_log_print(ANDROID_LOG_INFO, kTag, "Audio path probe finished: %s", full.str().c_str());
  return bestProfileId;
}

JNIEXPORT jlong JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeOpen(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts,
    jint preferredProfileId) {
  // Fast path: open the previously calibrated winning profile immediately.
  if (const StreamProfile* preferred = findProfile(preferredProfileId)) {
    std::string status;
    StreamHandle* h = tryOpenProfile(
        *preferred, sampleRate, androidUsage, deviceId, initialBursts, /*verifyStart=*/false, status);
    if (h != nullptr) {
      setLastOpenStatus("cached(" + status + ")");
      return static_cast<jlong>(reinterpret_cast<uintptr_t>(h));
    }
  }

  // Fallback path: iterate through all profiles in order until one grants LOW_LATENCY.
  std::ostringstream failedSummary;
  failedSummary << "fallback:";
  for (const auto& profile : kProfiles) {
    if (profile.id == preferredProfileId) continue;
    std::string status;
    StreamHandle* h = tryOpenProfile(
        profile, sampleRate, androidUsage, deviceId, initialBursts, /*verifyStart=*/false, status);
    if (h != nullptr) {
      setLastOpenStatus(status);
      return static_cast<jlong>(reinterpret_cast<uintptr_t>(h));
    }
    failedSummary << " " << profile.label << "=" << status << ";";
  }

  setLastOpenStatus(failedSummary.str());
  return 0;
}

JNIEXPORT jstring JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetLastOpenStatus(
    JNIEnv* env,
    jclass /*clazz*/) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  return env->NewStringUTF(gLastOpenStatus.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetLastProbeSummary(
    JNIEnv* env,
    jclass /*clazz*/) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  return env->NewStringUTF(gLastProbeSummary.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStart(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return JNI_FALSE;
  if (h->useCallback && h->disconnected.load(std::memory_order_acquire)) {
    return JNI_FALSE;
  }

  aaudio_stream_state_t state = AAudioStream_getState(h->stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    return JNI_TRUE;
  }
  if (state == AAUDIO_STREAM_STATE_DISCONNECTED || state == AAUDIO_STREAM_STATE_CLOSED) {
    return JNI_FALSE;
  }

  if (h->useCallback) {
    h->stopping.store(false, std::memory_order_release);
    h->hasWrittenSinceStart.store(false, std::memory_order_relaxed);
  }

  aaudio_result_t res = AAudioStream_requestStart(h->stream);
  if (res != AAUDIO_OK) {
    return JNI_FALSE;
  }
  aaudio_stream_state_t nextState = state;
  AAudioStream_waitForStateChange(h->stream, state, &nextState, 50'000'000LL);
  return (nextState == AAUDIO_STREAM_STATE_STARTED || nextState == AAUDIO_STREAM_STATE_STARTING)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeWrite(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jfloatArray samples,
    jint numFrames,
    jlong timeoutNanos) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr || samples == nullptr || numFrames <= 0) {
    return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  }
  const jsize len = env->GetArrayLength(samples);
  if (numFrames > len / kChannelCount) {
    return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  }
  jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
  if (ptr == nullptr) {
    return AAUDIO_ERROR_NO_MEMORY;
  }
  aaudio_result_t written = 0;
  if (h->useCallback) {
    written = writeCallbackRing(h, ptr, numFrames, timeoutNanos);
  } else if (h->format == AAUDIO_FORMAT_PCM_I16) {
    const size_t sampleCount = static_cast<size_t>(numFrames) * kChannelCount;
    if (h->i16Scratch.size() < sampleCount) {
      h->i16Scratch.resize(sampleCount);
    }
    for (size_t i = 0; i < sampleCount; ++i) {
      h->i16Scratch[i] = floatToInt16(ptr[i]);
    }
    written = AAudioStream_write(h->stream, h->i16Scratch.data(), numFrames, timeoutNanos);
  } else if (h->format == kFormatPcmI32) {
    const size_t sampleCount = static_cast<size_t>(numFrames) * kChannelCount;
    if (h->i32Scratch.size() < sampleCount) {
      h->i32Scratch.resize(sampleCount);
    }
    for (size_t i = 0; i < sampleCount; ++i) {
      h->i32Scratch[i] = floatToInt32(ptr[i]);
    }
    written = AAudioStream_write(h->stream, h->i32Scratch.data(), numFrames, timeoutNanos);
  } else {
    written = AAudioStream_write(h->stream, ptr, numFrames, timeoutNanos);
  }
  env->ReleaseFloatArrayElements(samples, ptr, JNI_ABORT);
  return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativePauseAndFlush(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return;
  if (h->useCallback) {
    h->stopping.store(true, std::memory_order_release);
    h->writeCv.notify_all();
  }
  aaudio_stream_state_t state = AAudioStream_getState(h->stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    if (AAudioStream_requestPause(h->stream) == AAUDIO_OK) {
      aaudio_stream_state_t nextState = state;
      AAudioStream_waitForStateChange(h->stream, state, &nextState, 50'000'000LL);
      AAudioStream_requestFlush(h->stream);
    }
  }
  if (h->useCallback) {
    h->readPos.store(0, std::memory_order_relaxed);
    h->writePos.store(0, std::memory_order_relaxed);
    h->hasWrittenSinceStart.store(false, std::memory_order_relaxed);
    h->stopping.store(false, std::memory_order_release);
  }
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStop(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return;
  if (h->useCallback) {
    h->stopping.store(true, std::memory_order_release);
    h->writeCv.notify_all();
  }
  AAudioStream_requestStop(h->stream);
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeClose(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  destroyHandle(toHandle(handle));
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetSampleRate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  return (h != nullptr && h->stream != nullptr) ? AAudioStream_getSampleRate(h->stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetFramesPerBurst(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  return (h != nullptr && h->stream != nullptr) ? AAudioStream_getFramesPerBurst(h->stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  return (h != nullptr && h->stream != nullptr) ? AAudioStream_getBufferSizeInFrames(h->stream) : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferCapacityInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  return (h != nullptr && h->stream != nullptr) ? AAudioStream_getBufferCapacityInFrames(h->stream) : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeSetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint requestedFrames) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr || requestedFrames <= 0) return -1;
  const int32_t burst = std::max(1, h->framesPerBurst);
  const int32_t capacity = std::max(burst, h->bufferCapacityFrames);
  const int32_t safeFrames =
      clampBufferFramesForDevice(requestedFrames, burst, capacity, h->mmapUsed, h->actualSharing);
  const int32_t actual = AAudioStream_setBufferSizeInFrames(h->stream, safeFrames);
  if (h->useCallback && actual > 0 && h->ringCapacityFrames > static_cast<uint64_t>(burst)) {
    const int32_t maxRingQueue = static_cast<int32_t>(
        std::min<uint64_t>(static_cast<uint64_t>(INT32_MAX), h->ringCapacityFrames - burst));
    h->maxQueuedFrames.store(
        std::clamp(actual, burst, std::max(burst, maxRingQueue)),
        std::memory_order_relaxed);
  }
  return actual;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetXRunCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return 0;
  int32_t xruns = AAudioStream_getXRunCount(h->stream);
  if (h->useCallback) {
    xruns += h->callbackUnderruns.load(std::memory_order_relaxed);
  }
  return xruns;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetDeviceId(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  return (h != nullptr && h->stream != nullptr) ? AAudioStream_getDeviceId(h->stream) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsPlaying(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return JNI_FALSE;
  if (h->useCallback && h->disconnected.load(std::memory_order_acquire)) {
    return JNI_FALSE;
  }
  aaudio_stream_state_t state = AAudioStream_getState(h->stream);
  return (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsMMapUsed(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  StreamHandle* h = toHandle(handle);
  if (h == nullptr || h->stream == nullptr) return JNI_FALSE;
  if (auto isMMap = resolveIsMMapUsed()) {
    return isMMap(h->stream) ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_FALSE;
}

}  // extern "C"
