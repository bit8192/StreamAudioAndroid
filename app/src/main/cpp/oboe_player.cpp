#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <algorithm>
#include <memory>

namespace {
    constexpr const char *TAG = "OboePlayerJNI";

    oboe::AudioFormat formatFromBits(int bits) {
        switch (bits) {
            case 16:
                return oboe::AudioFormat::I16;
            case 24:
                return oboe::AudioFormat::I24;
            case 32:
                return oboe::AudioFormat::I32;
            default:
                return oboe::AudioFormat::Unspecified;
        }
    }

    struct OboePlayer {
        std::shared_ptr<oboe::AudioStream> stream;
        int32_t bytesPerFrame = 0;
    };

    bool openStream(OboePlayer *player, int32_t sampleRate, int32_t channelCount, int32_t bits,
                    int32_t preferredBufferFrames) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setSampleRate(sampleRate);
        builder.setChannelCount(channelCount);
        builder.setUsage(oboe::Usage::Media);
        builder.setContentType(oboe::ContentType::Music);
        builder.setFormat(formatFromBits(bits));

        oboe::Result result = builder.openStream(player->stream);
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(player->stream);
        }
        if (result != oboe::Result::OK || !player->stream) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "openStream failed: %s",
                                oboe::convertToText(result));
            return false;
        }

        if (preferredBufferFrames > 0) {
            int32_t capacity = player->stream->getBufferCapacityInFrames();
            int32_t target = std::min(preferredBufferFrames, capacity);
            player->stream->setBufferSizeInFrames(target);
        }
        player->bytesPerFrame = player->stream->getBytesPerFrame();
        return player->bytesPerFrame > 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeCreate(
    JNIEnv *env,
    jobject /* thiz */,
    jint sampleRate,
    jint channelCount,
    jint bits,
    jint preferredBufferFrames
) {
    if (sampleRate <= 0 || channelCount <= 0) {
        return 0;
    }
    auto *player = new OboePlayer();
    if (!openStream(player, sampleRate, channelCount, bits, preferredBufferFrames)) {
        delete player;
        return 0;
    }
    return reinterpret_cast<jlong>(player);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeStart(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream) {
        return JNI_FALSE;
    }
    oboe::Result result = player->stream->requestStart();
    return result == oboe::Result::OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeWrite(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray data,
    jint offset,
    jint size
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream || player->bytesPerFrame <= 0 || !data || size <= 0) {
        return -1;
    }
    if (offset < 0) {
        return -1;
    }
    const jsize dataLen = env->GetArrayLength(data);
    if (offset + size > dataLen) {
        return -1;
    }

    jbyte *raw = env->GetByteArrayElements(data, nullptr);
    if (!raw) {
        return -1;
    }

    int32_t frames = size / player->bytesPerFrame;
    int32_t bytesToWrite = frames * player->bytesPerFrame;
    auto *buffer = reinterpret_cast<uint8_t *>(raw + offset);
    oboe::ResultWithValue<int32_t> result =
        player->stream->write(buffer, frames, 1'000'000'000L);

    env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
    if (!result) {
        return -1;
    }
    return result.value() * player->bytesPerFrame;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeGetTimestamp(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jlongArray outTimestamp
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream || !outTimestamp || env->GetArrayLength(outTimestamp) < 2) {
        return JNI_FALSE;
    }
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    oboe::Result result = player->stream->getTimestamp(CLOCK_MONOTONIC, &framePosition, &timeNanos);
    if (result != oboe::Result::OK) {
        return JNI_FALSE;
    }
    jlong values[2] = {framePosition, timeNanos};
    env->SetLongArrayRegion(outTimestamp, 0, 2, values);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeGetStreamInfo(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jintArray outInfo
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream || !outInfo || env->GetArrayLength(outInfo) < 7) {
        return JNI_FALSE;
    }
    const jint values[7] = {
        player->stream->getSampleRate(),
        player->stream->getChannelCount(),
        player->stream->getBufferSizeInFrames(),
        player->stream->getBufferCapacityInFrames(),
        player->stream->getFramesPerBurst(),
        static_cast<jint>(player->stream->getSharingMode()),
        static_cast<jint>(player->stream->getPerformanceMode()),
    };
    env->SetIntArrayRegion(outInfo, 0, 7, values);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeSetBufferSizeInFrames(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jint frames
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream || frames < 0) {
        return -1;
    }
    if (frames == 0) {
        return player->stream->getBufferSizeInFrames();
    }
    int32_t capacity = player->stream->getBufferCapacityInFrames();
    int32_t target = std::min(static_cast<int32_t>(frames), capacity);
    auto result = player->stream->setBufferSizeInFrames(target);
    if (!result) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "setBufferSizeInFrames failed: %s",
                            oboe::convertToText(result.error()));
        return -1;
    }
    return result.value();
}

extern "C" JNIEXPORT void JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeStop(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player || !player->stream) {
        return;
    }
    player->stream->requestStop();
}

extern "C" JNIEXPORT void JNICALL
Java_cn_bincker_stream_sound_entity_OboeAudioPlayer_nativeRelease(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle
) {
    auto *player = reinterpret_cast<OboePlayer *>(handle);
    if (!player) {
        return;
    }
    if (player->stream) {
        player->stream->close();
        player->stream.reset();
    }
    delete player;
}
