/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>

#include "bitnet_runtime.h"

namespace {

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message);
}

StellonBitnetRuntime* runtime_from_handle(jlong handle) {
    return reinterpret_cast<StellonBitnetRuntime*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stellon_mobile_sdk_internal_NativeBitnetBridge_nativeCreate(JNIEnv* env, jobject /* thiz */) {
    try {
        return reinterpret_cast<jlong>(new StellonBitnetRuntime());
    } catch (const std::exception& error) {
        throw_illegal_state(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_stellon_mobile_sdk_internal_NativeBitnetBridge_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    delete runtime_from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stellon_mobile_sdk_internal_NativeBitnetBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring model_path
) {
    try {
        auto* runtime = runtime_from_handle(handle);
        if (runtime == nullptr) {
            throw std::runtime_error("native runtime handle is null");
        }
        runtime->load_model(to_string(env, model_path));
    } catch (const std::exception& error) {
        throw_illegal_state(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_stellon_mobile_sdk_internal_NativeBitnetBridge_nativeGenerate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring request_json,
    jobject callback,
    jobject cancellation_token
) {
    try {
        auto* runtime = runtime_from_handle(handle);
        if (runtime == nullptr) {
            throw std::runtime_error("native runtime handle is null");
        }

        jclass callback_class = env->GetObjectClass(callback);
        jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;I)V");
        jclass cancellation_class = env->GetObjectClass(cancellation_token);
        jmethodID is_cancelled = env->GetMethodID(cancellation_class, "isCancelled", "()Z");

        if (on_token == nullptr || is_cancelled == nullptr) {
            throw std::runtime_error("JNI callback method lookup failed");
        }

        runtime->generate(
            to_string(env, request_json),
            [&]() {
                return env->CallBooleanMethod(cancellation_token, is_cancelled) == JNI_TRUE;
            },
            [&](const std::string& token, int index) {
                jstring java_token = env->NewStringUTF(token.c_str());
                env->CallVoidMethod(callback, on_token, java_token, index);
                env->DeleteLocalRef(java_token);
                if (env->ExceptionCheck()) {
                    throw std::runtime_error("Kotlin token callback threw an exception");
                }
            }
        );
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) {
            throw_illegal_state(env, error.what());
        }
    }
}
