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
#include <cstdint>

namespace {

constexpr const char* kTag = "MeridianAAudio";

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

AAudioStream* toStream(jlong handle) {
  return reinterpret_cast<AAudioStream*>(static_cast<uintptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeOpen(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint androidUsage,
    jint deviceId,
    jint initialBursts) {
  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t res = AAudio_createStreamBuilder(&builder);
  if (res != AAUDIO_OK || builder == nullptr) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "AAudio_createStreamBuilder failed: %d", res);
    return 0;
  }

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setChannelCount(builder, 2);
  if (sampleRate > 0) {
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
  }
  // SHARED + LOW_LATENCY enables AAudio MMAP / Fast Mixer on Pixel while coexisting with media.
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  if (deviceId > 0) {
    AAudioStreamBuilder_setDeviceId(builder, deviceId);
  }

  if (android_get_device_api_level() >= 28) {
    if (auto setUsage = resolveSetUsage()) {
      setUsage(builder, mapAudioUsage(androidUsage));
    }
    if (auto setContentType = resolveSetContentType()) {
      setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
    }
  }

  AAudioStream* stream = nullptr;
  res = AAudioStreamBuilder_openStream(builder, &stream);
  AAudioStreamBuilder_delete(builder);

  if (res != AAUDIO_OK || stream == nullptr) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "AAudioStreamBuilder_openStream failed: %d", res);
    return 0;
  }

  // Only keep AAudio when Android actually grants the low-latency path; otherwise let Kotlin fall
  // back to AudioTrack with its slow-buffer heuristics.
  if (AAudioStream_getPerformanceMode(stream) != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "AAudio did not grant LOW_LATENCY; falling back");
    AAudioStream_close(stream);
    return 0;
  }

  const int32_t burst = std::max(1, AAudioStream_getFramesPerBurst(stream));
  const int32_t capacity = std::max(burst, AAudioStream_getBufferCapacityInFrames(stream));
  const int32_t requested = std::clamp(burst * std::max(1, static_cast<int32_t>(initialBursts)),
                                       burst,
                                       capacity);
  AAudioStream_setBufferSizeInFrames(stream, requested);

  bool mmapUsed = false;
  if (auto isMMap = resolveIsMMapUsed()) {
    mmapUsed = isMMap(stream);
  }
  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "Opened AAudio stream: rate=%d burst=%d buf=%d/%d mmap=%d device=%d",
      AAudioStream_getSampleRate(stream),
      burst,
      AAudioStream_getBufferSizeInFrames(stream),
      capacity,
      mmapUsed ? 1 : 0,
      AAudioStream_getDeviceId(stream));

  return static_cast<jlong>(reinterpret_cast<uintptr_t>(stream));
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStart(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return JNI_FALSE;

  aaudio_stream_state_t state = AAudioStream_getState(stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    return JNI_TRUE;
  }
  if (state == AAUDIO_STREAM_STATE_DISCONNECTED || state == AAUDIO_STREAM_STATE_CLOSED) {
    return JNI_FALSE;
  }

  aaudio_result_t res = AAudioStream_requestStart(stream);
  if (res != AAUDIO_OK) {
    return JNI_FALSE;
  }
  aaudio_stream_state_t nextState = state;
  AAudioStream_waitForStateChange(stream, state, &nextState, 50'000'000LL);
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
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr || samples == nullptr || numFrames <= 0) {
    return AAUDIO_ERROR_INVALID_ARGUMENT;
  }
  const jsize len = env->GetArrayLength(samples);
  if (len < numFrames * 2) {
    return AAUDIO_ERROR_INVALID_ARGUMENT;
  }
  jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
  if (ptr == nullptr) {
    return AAUDIO_ERROR_NO_MEMORY;
  }
  const aaudio_result_t written = AAudioStream_write(stream, ptr, numFrames, timeoutNanos);
  env->ReleaseFloatArrayElements(samples, ptr, JNI_ABORT);
  return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativePauseAndFlush(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return;
  aaudio_stream_state_t state = AAudioStream_getState(stream);
  if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
    if (AAudioStream_requestPause(stream) == AAUDIO_OK) {
      aaudio_stream_state_t nextState = state;
      AAudioStream_waitForStateChange(stream, state, &nextState, 50'000'000LL);
      AAudioStream_requestFlush(stream);
    }
  }
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeStop(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return;
  AAudioStream_requestStop(stream);
}

JNIEXPORT void JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeClose(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return;
  AAudioStream_close(stream);
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetSampleRate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getSampleRate(stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetFramesPerBurst(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getFramesPerBurst(stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getBufferSizeInFrames(stream) : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetBufferCapacityInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getBufferCapacityInFrames(stream) : -1;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeSetBufferSizeInFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint requestedFrames) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr || requestedFrames <= 0) return -1;
  return AAudioStream_setBufferSizeInFrames(stream, requestedFrames);
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetXRunCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getXRunCount(stream) : 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeGetDeviceId(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  return stream != nullptr ? AAudioStream_getDeviceId(stream) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsPlaying(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return JNI_FALSE;
  aaudio_stream_state_t state = AAudioStream_getState(stream);
  return (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_accessibility_utils_output_NativeAAudioOutput_nativeIsMMapUsed(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle) {
  AAudioStream* stream = toStream(handle);
  if (stream == nullptr) return JNI_FALSE;
  if (auto isMMap = resolveIsMMapUsed()) {
    return isMMap(stream) ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_FALSE;
}

}  // extern "C"
