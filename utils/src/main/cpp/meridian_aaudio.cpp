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

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char* kTag = "MeridianAAudio";
constexpr uint32_t kRingCapacityFrames = 16384;
constexpr uint32_t kRingMask = kRingCapacityFrames - 1;

std::mutex gStatusMutex;
std::string gLastOpenStatus = "unattempted";

void setLastOpenStatus(const std::string& status) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  gLastOpenStatus = status;
}

// API 28+ AAudio setters resolved dynamically so this library compiles cleanly with minSdk 26.
using SetUsageFn = void (*)(AAudioStreamBuilder*, aaudio_usage_t);
using SetContentTypeFn = void (*)(AAudioStreamBuilder*, aaudio_content_type_t);
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

IsMMapUsedFn resolveIsMMapUsed() {
  static auto fn = reinterpret_cast<IsMMapUsedFn>(dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
  return fn;
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

const char* formatName(aaudio_format_t fmt) {
  switch (fmt) {
    case AAUDIO_FORMAT_PCM_FLOAT:
      return "F32";
    case AAUDIO_FORMAT_PCM_I16:
      return "I16";
    default:
      return "OTHER";
  }
}

inline int16_t floatToPcm16(float sample) {
  if (sample >= 1.0f) return 32767;
  if (sample <= -1.0f) return -32768;
  return static_cast<int16_t>(sample * 32767.0f);
}

struct MeridianStream {
  AAudioStream* stream = nullptr;
  aaudio_format_t format = AAUDIO_FORMAT_PCM_FLOAT;
  int32_t sampleRate = 48000;
  int32_t framesPerBurst = 256;
  bool useCallback = false;

  std::vector<float> ring;
  std::atomic<uint64_t> writeFrame{0};
  std::atomic<uint64_t> readFrame{0};
  std::atomic<int32_t> targetRingFrames{512};
  std::atomic<int32_t> callbackUnderruns{0};
  std::atomic<bool> flushRequested{false};
  std::atomic<bool> stopRequested{false};

  std::vector<int16_t> i16Scratch;
};

MeridianStream* toWrapper(jlong handle) {
  return reinterpret_cast<MeridianStream*>(static_cast<uintptr_t>(handle));
}

aaudio_data_callback_result_t meridianDataCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {
  auto* ms = static_cast<MeridianStream*>(userData);
  if (ms == nullptr || audioData == nullptr || numFrames <= 0) {
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  uint64_t w = ms->writeFrame.load(std::memory_order_acquire);
  uint64_t r = ms->readFrame.load(std::memory_order_relaxed);
  if (ms->flushRequested.exchange(false, std::memory_order_acq_rel)) {
    r = w;
    ms->readFrame.store(r, std::memory_order_release);
  }

  const uint64_t avail = (w >= r) ? (w - r) : 0;
  const int32_t toCopy = static_cast<int32_t>(std::min<uint64_t>(avail, numFrames));

  if (ms->format == AAUDIO_FORMAT_PCM_I16) {
    auto* out = static_cast<int16_t*>(audioData);
    for (int32_t i = 0; i < toCopy; ++i) {
      const uint32_t idx = static_cast<uint32_t>((r + i) & kRingMask) * 2U;
      out[i * 2] = floatToPcm16(ms->ring[idx]);
      out[i * 2 + 1] = floatToPcm16(ms->ring[idx + 1]);
    }
    if (toCopy < numFrames) {
      std::memset(out + (toCopy * 2), 0, static_cast<size_t>(numFrames - toCopy) * 2 * sizeof(int16_t));
      if (toCopy > 0) {
        ms->callbackUnderruns.fetch_add(1, std::memory_order_relaxed);
      }
    }
  } else {
    auto* out = static_cast<float*>(audioData);
    for (int32_t i = 0; i < toCopy; ++i) {
      const uint32_t idx = static_cast<uint32_t>((r + i) & kRingMask) * 2U;
      out[i * 2] = ms->ring[idx];
      out[i * 2 + 1] = ms->ring[idx + 1];
    }
    if (toCopy < numFrames) {
      std::memset(out + (toCopy * 2), 0, static_cast<size_t>(numFrames - toCopy) * 2 * sizeof(float));
      if (toCopy > 0) {
        ms->callbackUnderruns.fetch_add(1, std::memory_order_relaxed);
      }
    }
  }

  ms->readFrame.store(r + static_cast<uint64_t>(toCopy), std::memory_order_release);
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

MeridianStream* tryOpenStream(
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts,
    aaudio_format_t requestedFormat,
    bool useCallback,
    bool setContentTypeSpeech,
    std::string& outStatus) {
  auto* wrapper = new MeridianStream();
  wrapper->useCallback = useCallback;
  if (useCallback) {
    wrapper->ring.assign(kRingCapacityFrames * 2U, 0.0f);
  }

  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t res = AAudio_createStreamBuilder(&builder);
  if (res != AAUDIO_OK || builder == nullptr) {
    std::ostringstream oss;
    oss << "builder_err=" << res << "(" << AAudio_convertResultToText(res) << ")";
    outStatus = oss.str();
    delete wrapper;
    return nullptr;
  }

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  if (requestedFormat != AAUDIO_FORMAT_UNSPECIFIED) {
    AAudioStreamBuilder_setFormat(builder, requestedFormat);
  }
  AAudioStreamBuilder_setChannelCount(builder, 2);
  if (sampleRate > 0) {
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
  }
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  if (deviceId > 0) {
    AAudioStreamBuilder_setDeviceId(builder, deviceId);
  }
  if (useCallback) {
    AAudioStreamBuilder_setDataCallback(builder, meridianDataCallback, wrapper);
  }

  if (android_get_device_api_level() >= 28) {
    if (auto setUsage = resolveSetUsage()) {
      setUsage(builder, mapAudioUsage(androidUsage));
    }
    if (setContentTypeSpeech) {
      if (auto setContentType = resolveSetContentType()) {
        setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
      }
    }
  }

  AAudioStream* stream = nullptr;
  res = AAudioStreamBuilder_openStream(builder, &stream);
  AAudioStreamBuilder_delete(builder);

  if (res != AAUDIO_OK || stream == nullptr) {
    std::ostringstream oss;
    oss << "open_err=" << res << "(" << AAudio_convertResultToText(res)
        << ",cb=" << (useCallback ? 1 : 0)
        << ",reqFmt=" << requestedFormat
        << ",rate=" << sampleRate << ",usage=" << androidUsage
        << ",speech=" << (setContentTypeSpeech ? 1 : 0) << ")";
    outStatus = oss.str();
    delete wrapper;
    return nullptr;
  }

  const int32_t perfMode = AAudioStream_getPerformanceMode(stream);
  const int32_t actualRate = AAudioStream_getSampleRate(stream);
  const int32_t burst = std::max(1, AAudioStream_getFramesPerBurst(stream));
  const aaudio_format_t actualFmt = AAudioStream_getFormat(stream);

  if (actualFmt != AAUDIO_FORMAT_PCM_FLOAT && actualFmt != AAUDIO_FORMAT_PCM_I16) {
    std::ostringstream oss;
    oss << "unsupported_fmt=" << actualFmt << "(cb=" << (useCallback ? 1 : 0) << ")";
    outStatus = oss.str();
    AAudioStream_close(stream);
    delete wrapper;
    return nullptr;
  }

  if (perfMode != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
    std::ostringstream oss;
    oss << "perf_mode=" << perfMode << "(want=" << AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
        << ",cb=" << (useCallback ? 1 : 0)
        << ",fmt=" << formatName(actualFmt)
        << ",rate=" << actualRate << ",burst=" << burst
        << ",usage=" << androidUsage << ",speech=" << (setContentTypeSpeech ? 1 : 0) << ")";
    outStatus = oss.str();
    AAudioStream_close(stream);
    delete wrapper;
    return nullptr;
  }

  const int32_t capacity = std::max(burst, AAudioStream_getBufferCapacityInFrames(stream));
  const int32_t requested = std::clamp(burst * std::max(1, static_cast<int32_t>(initialBursts)),
                                       burst,
                                       capacity);
  AAudioStream_setBufferSizeInFrames(stream, requested);

  wrapper->stream = stream;
  wrapper->format = actualFmt;
  wrapper->sampleRate = actualRate;
  wrapper->framesPerBurst = burst;
  wrapper->targetRingFrames.store(
      std::clamp(requested, burst, static_cast<int32_t>(kRingCapacityFrames / 2)),
      std::memory_order_relaxed);

  bool mmapUsed = false;
  if (auto isMMap = resolveIsMMapUsed()) {
    mmapUsed = isMMap(stream);
  }
  const int32_t actualBuf = AAudioStream_getBufferSizeInFrames(stream);
  std::ostringstream oss;
  oss << "ok(cb=" << (useCallback ? 1 : 0)
      << ",mmap=" << (mmapUsed ? 1 : 0)
      << ",fmt=" << formatName(actualFmt)
      << ",perf=" << perfMode
      << ",rate=" << actualRate << ",burst=" << burst
      << ",buf=" << actualBuf << "/" << capacity
      << ",usage=" << androidUsage << ",speech=" << (setContentTypeSpeech ? 1 : 0) << ")";
  outStatus = oss.str();
  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "Opened AAudio stream: %s device=%d",
      outStatus.c_str(),
      AAudioStream_getDeviceId(stream));
  return wrapper;
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeOpen(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts) {
  std::string a1;
  MeridianStream* ms = tryOpenStream(
      sampleRate, androidUsage, deviceId, initialBursts, AAUDIO_FORMAT_UNSPECIFIED, true, true, a1);
  if (ms != nullptr) {
    setLastOpenStatus(a1);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ms));
  }

  std::string a2;
  ms = tryOpenStream(
      0, androidUsage, deviceId, initialBursts, AAUDIO_FORMAT_PCM_I16, true, false, a2);
  if (ms != nullptr) {
    setLastOpenStatus("retry_cb_i16: " + a2 + " [a1=" + a1 + "]");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ms));
  }

  std::string a3;
  ms = tryOpenStream(
      sampleRate, androidUsage, deviceId, initialBursts, AAUDIO_FORMAT_PCM_FLOAT, false, true, a3);
  if (ms != nullptr) {
    setLastOpenStatus("retry_blocking: " + a3 + " [a1=" + a1 + "]");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ms));
  }

  setLastOpenStatus("fallback: a1=" + a1 + "; a2=" + a2 + "; a3=" + a3);
  return 0;
}

JNIEXPORT jstring JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetLastOpenStatus(
    JNIEnv* env,
    jclass /*clazz*/) {
  std::lock_guard<std::mutex> lock(gStatusMutex);
  return env->NewStringUTF(gLastOpenStatus.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStart(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return JNI_FALSE;
  ms->stopRequested.store(false, std::memory_order_release);

  aaudio_stream_state_t state = AAudioStream_getState(ms->stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    return JNI_TRUE;
  }
  if (state == AAUDIO_STREAM_STATE_DISCONNECTED || state == AAUDIO_STREAM_STATE_CLOSED) {
    return JNI_FALSE;
  }

  aaudio_result_t res = AAudioStream_requestStart(ms->stream);
  if (res != AAUDIO_OK) {
    return JNI_FALSE;
  }
  aaudio_stream_state_t nextState = state;
  AAudioStream_waitForStateChange(ms->stream, state, &nextState, 50'000'000LL);
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
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr || samples == nullptr || numFrames <= 0) {
    return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  }
  const jsize len = env->GetArrayLength(samples);
  if (numFrames > len / 2) {
    return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  }
  jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
  if (ptr == nullptr) {
    return AAUDIO_ERROR_NO_MEMORY;
  }

  jint result = 0;
  if (ms->useCallback) {
    const auto deadline =
        std::chrono::steady_clock::now() + std::chrono::nanoseconds(std::max<jlong>(0, timeoutNanos));
    int32_t written = 0;
    while (written < numFrames) {
      if (ms->stopRequested.load(std::memory_order_acquire)) {
        break;
      }
      const aaudio_stream_state_t state = AAudioStream_getState(ms->stream);
      if (state == AAUDIO_STREAM_STATE_DISCONNECTED || state == AAUDIO_STREAM_STATE_CLOSED) {
        result = AAUDIO_ERROR_DISCONNECTED;
        break;
      }
      const uint64_t w = ms->writeFrame.load(std::memory_order_relaxed);
      const uint64_t r = ms->readFrame.load(std::memory_order_acquire);
      const int32_t queued = static_cast<int32_t>((w >= r) ? (w - r) : 0);
      const int32_t limit = std::max(ms->targetRingFrames.load(std::memory_order_relaxed),
                                     ms->framesPerBurst);
      const int32_t freeFrames = std::min<int32_t>(
          static_cast<int32_t>(kRingCapacityFrames) - queued - 1,
          limit - queued);
      if (freeFrames > 0) {
        const int32_t chunk = std::min(numFrames - written, freeFrames);
        for (int32_t i = 0; i < chunk; ++i) {
          const uint32_t dst = static_cast<uint32_t>((w + i) & kRingMask) * 2U;
          const int32_t src = (written + i) * 2;
          ms->ring[dst] = ptr[src];
          ms->ring[dst + 1] = ptr[src + 1];
        }
        ms->writeFrame.store(w + static_cast<uint64_t>(chunk), std::memory_order_release);
        written += chunk;
      } else {
        if (std::chrono::steady_clock::now() >= deadline) {
          break;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(800));
      }
    }
    if (result == 0) {
      result = written;
    }
  } else if (ms->format == AAUDIO_FORMAT_PCM_I16) {
    const size_t sampleCount = static_cast<size_t>(numFrames) * 2U;
    if (ms->i16Scratch.size() < sampleCount) {
      ms->i16Scratch.resize(sampleCount);
    }
    for (size_t i = 0; i < sampleCount; ++i) {
      ms->i16Scratch[i] = floatToPcm16(ptr[i]);
    }
    result = static_cast<jint>(
        AAudioStream_write(ms->stream, ms->i16Scratch.data(), numFrames, timeoutNanos));
  } else {
    result = static_cast<jint>(AAudioStream_write(ms->stream, ptr, numFrames, timeoutNanos));
  }

  env->ReleaseFloatArrayElements(samples, ptr, JNI_ABORT);
  return result;
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativePauseAndFlush(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return;
  if (ms->useCallback) {
    ms->flushRequested.store(true, std::memory_order_release);
  }
  aaudio_stream_state_t state = AAudioStream_getState(ms->stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    if (AAudioStream_requestPause(ms->stream) == AAUDIO_OK) {
      aaudio_stream_state_t nextState = state;
      AAudioStream_waitForStateChange(ms->stream, state, &nextState, 50'000'000LL);
      AAudioStream_requestFlush(ms->stream);
    }
  }
  if (ms->useCallback) {
    const uint64_t w = ms->writeFrame.load(std::memory_order_acquire);
    ms->readFrame.store(w, std::memory_order_release);
    ms->flushRequested.store(false, std::memory_order_release);
  }
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStop(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return;
  ms->stopRequested.store(true, std::memory_order_release);
  AAudioStream_requestStop(ms->stream);
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeClose(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr) return;
  ms->stopRequested.store(true, std::memory_order_release);
  if (ms->stream != nullptr) {
    AAudioStream_close(ms->stream);
    ms->stream = nullptr;
  }
  delete ms;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetSampleRate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  return (ms != nullptr && ms->stream != nullptr) ? AAudioStream_getSampleRate(ms->stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetFramesPerBurst(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  return (ms != nullptr && ms->stream != nullptr) ? AAudioStream_getFramesPerBurst(ms->stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  return (ms != nullptr && ms->stream != nullptr) ? AAudioStream_getBufferSizeInFrames(ms->stream)
                                                  : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferCapacityInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  return (ms != nullptr && ms->stream != nullptr)
             ? AAudioStream_getBufferCapacityInFrames(ms->stream)
             : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeSetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint requestedFrames) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr || requestedFrames <= 0) return -1;
  const int32_t actual = AAudioStream_setBufferSizeInFrames(ms->stream, requestedFrames);
  if (ms->useCallback && actual > 0) {
    ms->targetRingFrames.store(
        std::clamp(actual, ms->framesPerBurst, static_cast<int32_t>(kRingCapacityFrames / 2)),
        std::memory_order_relaxed);
  }
  return actual;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetXRunCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return 0;
  return AAudioStream_getXRunCount(ms->stream) +
         ms->callbackUnderruns.load(std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetDeviceId(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  return (ms != nullptr && ms->stream != nullptr) ? AAudioStream_getDeviceId(ms->stream) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsPlaying(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return JNI_FALSE;
  aaudio_stream_state_t state = AAudioStream_getState(ms->stream);
  return (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsMMapUsed(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  MeridianStream* ms = toWrapper(handle);
  if (ms == nullptr || ms->stream == nullptr) return JNI_FALSE;
  if (auto isMMap = resolveIsMMapUsed()) {
    return isMMap(ms->stream) ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_FALSE;
}

}  // extern "C"
