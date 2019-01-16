/*
 * Copyright (c) 2013, NVIDIA CORPORATION.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (C) 2009 The Android Open Source Project
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "PowerServiceClient-JNI"
#include <utils/Log.h>
#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include <powerservice/PowerServiceClient.h>
#include <powerservice/IPowerService.h>
using namespace android;

static jfieldID mNativePowerServiceClient;

static PowerServiceClient* getClientPtr(JNIEnv* env, jobject thiz)
{
    return (PowerServiceClient*)env->GetIntField(thiz,
            mNativePowerServiceClient);
}

static void setClientPtr(JNIEnv* env,
        jobject thiz, PowerServiceClient* ptr)
{
    env->SetIntField(thiz, mNativePowerServiceClient, (int)ptr);
}

static void com_nvidia_PowerServiceClient_sendPowerHint(JNIEnv *env,
        jobject thiz, jint hint, jintArray data)
{
    int size;
    void *hintData;
    PowerServiceClient *ptr = getClientPtr(env, thiz);

    if (ptr) {
        size = data ? env->GetArrayLength(data) : 0;
        switch (hint) {
            case POWER_HINT_APP_PROFILE:
                if (size != APP_PROFILE_COUNT) {
                    ALOGW("Invalid data");
                    return;
                }
                break;
            default:
                break;
        }

        hintData = (void*) env->GetIntArrayElements(data, 0);
        ptr->sendPowerHint((int)hint, hintData);
        env->ReleaseIntArrayElements(data, (int*)hintData, 0);
    }
}

static void com_nvidia_PowerServiceClient_init(JNIEnv* env, jobject thiz)
{
    setClientPtr(env, thiz, new PowerServiceClient());
}

static void com_nvidia_PowerServiceClient_release(JNIEnv* env, jobject thiz)
{
    PowerServiceClient* ptr = getClientPtr(env, thiz);
    setClientPtr(env, thiz, NULL);
    if (ptr) {
        delete ptr;
    }
}

static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod PowerServiceClientMethods[] = {
  { "sendPowerHint", "(I[I)V", (void *)com_nvidia_PowerServiceClient_sendPowerHint },
  { "nativeClassInit", "()V", (void*)nativeClassInit},
  { "init", "()V", (void*)com_nvidia_PowerServiceClient_init},
  { "release", "()V", (void*)com_nvidia_PowerServiceClient_release},
};

static void nativeClassInit(JNIEnv* env, jclass clazz)
{
    mNativePowerServiceClient = env->GetFieldID(
            clazz, "mNativePowerServiceClient", "I");
}

int register_com_nvidia_PowerServiceClient(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "com/nvidia/PowerServiceClient", PowerServiceClientMethods, NELEM(PowerServiceClientMethods));
}
