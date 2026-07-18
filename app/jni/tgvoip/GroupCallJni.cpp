/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 18/07/2026
 * Group Call & Live Stream JNI Bridge
 */

#include <jni.h>
#include <jni_utils.h>
#include <string>
#include <memory>
#include <vector>
#include <map>
#include <mutex>
#include <android/log.h>

#ifndef DISABLE_TGCALLS
#include <tgcalls/group/GroupInstanceCustomImpl.h>
#include <tgcalls/group/GroupInstanceImpl.h>
#include <tgcalls/StaticThreads.h>
#include <tgcalls/VideoCaptureInterface.h>
#include <platform/android/AndroidContext.h>
#include <sdk/android/native_api/video/wrapper.h>
#include <sdk/android/native_api/base/init.h>
#include <modules/utility/include/jvm_android.h>
#include <rtc_base/ssl_adapter.h>
#endif

#define LOG_TAG "GroupCallJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Java class path
#define GROUP_CALL_CONTROLLER_CLASS "org/thunderdog/challegram/voip/GroupCallController"

#ifndef DISABLE_TGCALLS

// ─── JNI helper wrapper ──────────────────────────────────────────────────────

struct JniGroupWrapper {
  jobject javaObj;
  jclass  javaClass;
  JavaVM* jvm;

  JniGroupWrapper(JNIEnv* env, jobject obj) {
    env->GetJavaVM(&jvm);
    javaObj   = env->NewGlobalRef(obj);
    javaClass = (jclass) env->NewGlobalRef(env->GetObjectClass(obj));
  }

  ~JniGroupWrapper() {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
      jvm->AttachCurrentThread(&env, nullptr);
      attached = true;
    }
    if (env) {
      env->DeleteGlobalRef(javaObj);
      env->DeleteGlobalRef(javaClass);
    }
    if (attached) jvm->DetachCurrentThread();
  }

  void runOnJava(std::function<void(JNIEnv*, jobject, jclass)> fn) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
      jvm->AttachCurrentThread(&env, nullptr);
      attached = true;
    }
    if (env) {
      fn(env, javaObj, javaClass);
    }
    if (attached) jvm->DetachCurrentThread();
  }

  void callVoid(const char* method) {
    runOnJava([&](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, method, "()V");
      if (mid) env->CallVoidMethod(obj, mid);
    });
  }

  void callVoidBool(const char* method, bool val) {
    runOnJava([&](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, method, "(Z)V");
      if (mid) env->CallVoidMethod(obj, mid, (jboolean)val);
    });
  }

  void callVoidInt(const char* method, int val) {
    runOnJava([&](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, method, "(I)V");
      if (mid) env->CallVoidMethod(obj, mid, (jint)val);
    });
  }

  void callVoidString(const char* method, const std::string& val) {
    runOnJava([&](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, method, "(Ljava/lang/String;)V");
      if (mid) {
        jstring jstr = env->NewStringUTF(val.c_str());
        env->CallVoidMethod(obj, mid, jstr);
        env->DeleteLocalRef(jstr);
      }
    });
  }
};

// ─── Native GroupCall instance container ────────────────────────────────────

struct GroupCallNative {
  std::unique_ptr<tgcalls::GroupInstanceCustomImpl> impl;
  std::shared_ptr<tgcalls::Threads>                threads;
  std::shared_ptr<JniGroupWrapper>                 wrapper;

  // Broadcast part callbacks (live stream)
  std::function<void(int64_t)>                     onCurrentTimeResult;
  std::function<void(tgcalls::BroadcastPart&&)>    onAudioPartResult;
  std::function<void(tgcalls::BroadcastPart&&)>    onVideoPartResult;
};

static jlong toJLong(GroupCallNative* ptr) {
  return reinterpret_cast<jlong>(ptr);
}

static GroupCallNative* fromJLong(jlong ptr) {
  return reinterpret_cast<GroupCallNative*>(ptr);
}

// ─── JNI: nativeCreate ──────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeCreate(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted,
    jboolean isLiveStream,
    jboolean noiseSuppressionEnabled) {

  auto* native  = new GroupCallNative();
  native->threads  = tgcalls::StaticThreads::getThreads();
  native->wrapper  = std::make_shared<JniGroupWrapper>(env, thiz);

  auto wrapper = native->wrapper;

  tgcalls::GroupInstanceDescriptor desc;
  desc.threads = native->threads;

  // Config
  desc.config.need_log = true;

  // Network state callback → Java onNetworkStateChanged(boolean connected, boolean isBroadcast)
  desc.networkStateUpdated = [wrapper](tgcalls::GroupNetworkState state) {
    wrapper->runOnJava([state](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, "onNetworkStateChanged", "(ZZ)V");
      if (mid) {
        bool isBroadcast = (state.connectionMode ==
            tgcalls::GroupConnectionMode::GroupConnectionModeBroadcast);
        env->CallVoidMethod(obj, mid,
            (jboolean)state.isConnected,
            (jboolean)isBroadcast);
      }
    });
  };

  // Audio levels callback → Java onAudioLevelsUpdated(int[] ssrcs, float[] levels, boolean[] voices)
  desc.audioLevelsUpdated = [wrapper](tgcalls::GroupLevelsUpdate const& update) {
    wrapper->runOnJava([&update](JNIEnv* env, jobject obj, jclass cls) {
      int count = (int)update.updates.size();
      jintArray  ssrcs  = env->NewIntArray(count);
      jfloatArray levels = env->NewFloatArray(count);
      jbooleanArray voices = env->NewBooleanArray(count);

      std::vector<jint>  ssrcArr(count);
      std::vector<jfloat> lvlArr(count);
      std::vector<jboolean> voiceArr(count);

      for (int i = 0; i < count; i++) {
        ssrcArr[i]   = (jint)update.updates[i].ssrc;
        lvlArr[i]    = (jfloat)update.updates[i].value.level;
        voiceArr[i]  = (jboolean)update.updates[i].value.voice;
      }
      env->SetIntArrayRegion(ssrcs, 0, count, ssrcArr.data());
      env->SetFloatArrayRegion(levels, 0, count, lvlArr.data());
      env->SetBooleanArrayRegion(voices, 0, count, voiceArr.data());

      jmethodID mid = env->GetMethodID(cls, "onAudioLevelsUpdated", "([I[F[Z)V");
      if (mid) env->CallVoidMethod(obj, mid, ssrcs, levels, voices);

      env->DeleteLocalRef(ssrcs);
      env->DeleteLocalRef(levels);
      env->DeleteLocalRef(voices);
    });
  };

  // Live stream: request current time → Java requestCurrentTime(long requestId)
  desc.requestCurrentTime = [wrapper, native](std::function<void(int64_t)> completion) {
    native->onCurrentTimeResult = std::move(completion);
    wrapper->runOnJava([](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, "requestCurrentTime", "()V");
      if (mid) env->CallVoidMethod(obj, mid);
    });
    // return dummy task
    class DummyTask : public tgcalls::BroadcastPartTask {
    public: void cancel() override {}
    };
    return std::make_shared<DummyTask>();
  };

  // Live stream: request audio broadcast part → Java requestAudioBroadcastPart(long timestamp, long duration)
  desc.requestAudioBroadcastPart = [wrapper, native](
      int64_t timestamp,
      int64_t duration,
      std::function<void(tgcalls::BroadcastPart&&)> completion) {
    native->onAudioPartResult = std::move(completion);
    wrapper->runOnJava([timestamp, duration](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, "requestAudioBroadcastPart", "(JJ)V");
      if (mid) env->CallVoidMethod(obj, mid, (jlong)timestamp, (jlong)duration);
    });
    class DummyTask : public tgcalls::BroadcastPartTask {
    public: void cancel() override {}
    };
    return std::make_shared<DummyTask>();
  };

  // Live stream: request video broadcast part → Java requestVideoBroadcastPart(long timestamp, long duration, int channelId, int quality)
  desc.requestVideoBroadcastPart = [wrapper, native](
      int64_t timestamp,
      int64_t duration,
      int32_t channelId,
      tgcalls::VideoChannelDescription::Quality quality,
      std::function<void(tgcalls::BroadcastPart&&)> completion) {
    native->onVideoPartResult = std::move(completion);
    wrapper->runOnJava([timestamp, duration, channelId, quality](JNIEnv* env, jobject obj, jclass cls) {
      jmethodID mid = env->GetMethodID(cls, "requestVideoBroadcastPart", "(JJII)V");
      if (mid) env->CallVoidMethod(obj, mid,
          (jlong)timestamp,
          (jlong)duration,
          (jint)channelId,
          (jint)static_cast<int>(quality));
    });
    class DummyTask : public tgcalls::BroadcastPartTask {
    public: void cancel() override {}
    };
    return std::make_shared<DummyTask>();
  };

  // Media channel description request → Java requestMediaChannelDescriptions(int[] ssrcs)
  desc.requestMediaChannelDescriptions = [wrapper](
      std::vector<uint32_t> const& ssrcs,
      std::function<void(std::vector<tgcalls::MediaChannelDescription>&&)> completion) {
    wrapper->runOnJava([&ssrcs](JNIEnv* env, jobject obj, jclass cls) {
      int count = (int)ssrcs.size();
      jintArray arr = env->NewIntArray(count);
      std::vector<jint> tmp(ssrcs.begin(), ssrcs.end());
      env->SetIntArrayRegion(arr, 0, count, tmp.data());
      jmethodID mid = env->GetMethodID(cls, "requestMediaChannelDescriptions", "([I)V");
      if (mid) env->CallVoidMethod(obj, mid, arr);
      env->DeleteLocalRef(arr);
    });
    class DummyTask : public tgcalls::RequestMediaChannelDescriptionTask {
    public: void cancel() override {}
    };
    return std::make_shared<DummyTask>();
  };

  desc.useDummyChannel = true;
  desc.disableIncomingChannels = false;
  desc.initialEnableNoiseSuppression = (bool)noiseSuppressionEnabled;
  desc.videoContentType = tgcalls::VideoContentType::None;
  desc.outgoingAudioBitrateKbit = 32;

  native->impl = std::make_unique<tgcalls::GroupInstanceCustomImpl>(std::move(desc));

  // Set initial mute state
  native->impl->setIsMuted((bool)isMuted);

  // Set connection mode: broadcast for live stream, RTC for voice chat
  if ((bool)isLiveStream) {
    native->impl->setConnectionMode(
        tgcalls::GroupConnectionMode::GroupConnectionModeBroadcast,
        false,
        true);
  } else {
    native->impl->setConnectionMode(
        tgcalls::GroupConnectionMode::GroupConnectionModeRtc,
        false,
        false);
  }

  LOGI("GroupCallNative created: ptr=%p isLiveStream=%d", native, (bool)isLiveStream);
  return toJLong(native);
}

// ─── JNI: nativeDestroy ─────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong ptr) {
  auto* native = fromJLong(ptr);
  if (!native) return;
  if (native->impl) {
    native->impl->stop([native]() {
      delete native;
    });
  } else {
    delete native;
  }
  LOGI("GroupCallNative destroyed");
}

// ─── JNI: nativeEmitJoinPayload ─────────────────────────────────────────────
// Triggers → Java onJoinPayloadReady(String payload)

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeEmitJoinPayload(
    JNIEnv* env, jobject thiz, jlong ptr) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  auto wrapper = native->wrapper;
  native->impl->emitJoinPayload([wrapper](tgcalls::GroupJoinPayload const& payload) {
    wrapper->callVoidString("onJoinPayloadReady", payload.json);
  });
}

// ─── JNI: nativeSetJoinResponsePayload ──────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetJoinResponsePayload(
    JNIEnv* env, jobject thiz, jlong ptr, jstring jPayload) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  const char* payloadStr = env->GetStringUTFChars(jPayload, nullptr);
  native->impl->setJoinResponsePayload(std::string(payloadStr));
  env->ReleaseStringUTFChars(jPayload, payloadStr);
}

// ─── JNI: nativeSetIsMuted ──────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetIsMuted(
    JNIEnv* env, jobject thiz, jlong ptr, jboolean isMuted) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  native->impl->setIsMuted((bool)isMuted);
}

// ─── JNI: nativeSetNoiseSuppressionEnabled ──────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetNoiseSuppressionEnabled(
    JNIEnv* env, jobject thiz, jlong ptr, jboolean enabled) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  native->impl->setIsNoiseSuppressionEnabled((bool)enabled);
}

// ─── JNI: nativeSetVolume ───────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetVolume(
    JNIEnv* env, jobject thiz, jlong ptr, jint ssrc, jdouble volume) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  native->impl->setVolume((uint32_t)ssrc, (double)volume);
}

// ─── JNI: nativeRemoveSsrcs ─────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeRemoveSsrcs(
    JNIEnv* env, jobject thiz, jlong ptr, jintArray jSsrcs) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  jsize count = env->GetArrayLength(jSsrcs);
  jint* elements = env->GetIntArrayElements(jSsrcs, nullptr);
  std::vector<uint32_t> ssrcs(elements, elements + count);
  env->ReleaseIntArrayElements(jSsrcs, elements, JNI_ABORT);
  native->impl->removeSsrcs(ssrcs);
}

// ─── JNI: nativeSetConnectionMode ───────────────────────────────────────────
// mode: 0=None, 1=RTC, 2=Broadcast

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetConnectionMode(
    JNIEnv* env, jobject thiz, jlong ptr, jint mode, jboolean keepBroadcast, jboolean isUnified) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  tgcalls::GroupConnectionMode cmode;
  switch ((int)mode) {
    case 1:  cmode = tgcalls::GroupConnectionMode::GroupConnectionModeRtc;       break;
    case 2:  cmode = tgcalls::GroupConnectionMode::GroupConnectionModeBroadcast; break;
    default: cmode = tgcalls::GroupConnectionMode::GroupConnectionModeNone;      break;
  }
  native->impl->setConnectionMode(cmode, (bool)keepBroadcast, (bool)isUnified);
}

// ─── JNI: nativeProvideCurrentTime ──────────────────────────────────────────
// Called from Java when TDLib returns current broadcast time

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideCurrentTime(
    JNIEnv* env, jobject thiz, jlong ptr, jlong timeMillis) {
  auto* native = fromJLong(ptr);
  if (!native) return;
  if (native->onCurrentTimeResult) {
    native->onCurrentTimeResult((int64_t)timeMillis);
    native->onCurrentTimeResult = nullptr;
  }
}

// ─── JNI: nativeProvideAudioPart ────────────────────────────────────────────
// Called from Java when TDLib returns an audio broadcast chunk
// status: 0=Success, 1=NotReady, 2=ResyncNeeded

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideAudioPart(
    JNIEnv* env, jobject thiz, jlong ptr,
    jlong timestampMillis, jdouble responseTimestamp,
    jint status, jbyteArray jData) {

  auto* native = fromJLong(ptr);
  if (!native) return;

  tgcalls::BroadcastPart part;
  part.timestampMilliseconds = (int64_t)timestampMillis;
  part.responseTimestamp     = (double)responseTimestamp;
  part.status = static_cast<tgcalls::BroadcastPart::Status>((int)status);

  if (jData != nullptr) {
    jsize len = env->GetArrayLength(jData);
    jbyte* bytes = env->GetByteArrayElements(jData, nullptr);
    part.data.assign(bytes, bytes + len);
    env->ReleaseByteArrayElements(jData, bytes, JNI_ABORT);
  }

  if (native->onAudioPartResult) {
    native->onAudioPartResult(std::move(part));
    native->onAudioPartResult = nullptr;
  }
}

// ─── JNI: nativeProvideVideoPart ────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideVideoPart(
    JNIEnv* env, jobject thiz, jlong ptr,
    jlong timestampMillis, jdouble responseTimestamp,
    jint status, jbyteArray jData) {

  auto* native = fromJLong(ptr);
  if (!native) return;

  tgcalls::BroadcastPart part;
  part.timestampMilliseconds = (int64_t)timestampMillis;
  part.responseTimestamp     = (double)responseTimestamp;
  part.status = static_cast<tgcalls::BroadcastPart::Status>((int)status);

  if (jData != nullptr) {
    jsize len = env->GetArrayLength(jData);
    jbyte* bytes = env->GetByteArrayElements(jData, nullptr);
    part.data.assign(bytes, bytes + len);
    env->ReleaseByteArrayElements(jData, bytes, JNI_ABORT);
  }

  if (native->onVideoPartResult) {
    native->onVideoPartResult(std::move(part));
    native->onVideoPartResult = nullptr;
  }
}

// ─── JNI: nativeGetStats ────────────────────────────────────────────────────
// Triggers → Java onStats(String json)

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeGetStats(
    JNIEnv* env, jobject thiz, jlong ptr) {
  auto* native = fromJLong(ptr);
  if (!native || !native->impl) return;
  auto wrapper = native->wrapper;
  native->impl->getStats([wrapper](tgcalls::GroupInstanceStats stats) {
    std::string json = "{\"incomingVideo\":[";
    bool first = true;
    for (auto& kv : stats.incomingVideoStats) {
      if (!first) json += ",";
      json += "{\"endpointId\":\"" + kv.first + "\","
              "\"receivingQuality\":" + std::to_string(kv.second.receivingQuality) + ","
              "\"availableQuality\":" + std::to_string(kv.second.availableQuality) + "}";
      first = false;
    }
    json += "]}";
    wrapper->callVoidString("onStats", json);
  });
}

#else // DISABLE_TGCALLS

extern "C" JNIEXPORT jlong JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeCreate(
    JNIEnv*, jobject, jboolean, jboolean, jboolean) { return 0; }

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeDestroy(
    JNIEnv*, jobject, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeEmitJoinPayload(
    JNIEnv*, jobject, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetJoinResponsePayload(
    JNIEnv*, jobject, jlong, jstring) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetIsMuted(
    JNIEnv*, jobject, jlong, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetNoiseSuppressionEnabled(
    JNIEnv*, jobject, jlong, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetVolume(
    JNIEnv*, jobject, jlong, jint, jdouble) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeRemoveSsrcs(
    JNIEnv*, jobject, jlong, jintArray) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeSetConnectionMode(
    JNIEnv*, jobject, jlong, jint, jboolean, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideCurrentTime(
    JNIEnv*, jobject, jlong, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideAudioPart(
    JNIEnv*, jobject, jlong, jlong, jdouble, jint, jbyteArray) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeProvideVideoPart(
    JNIEnv*, jobject, jlong, jlong, jdouble, jint, jbyteArray) {}

extern "C" JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_voip_GroupCallController_nativeGetStats(
    JNIEnv*, jobject, jlong) {}

#endif // DISABLE_TGCALLS
