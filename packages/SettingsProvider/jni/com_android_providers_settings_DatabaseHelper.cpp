/*
 * Copyright (C) 2013 NVIDIA CORPORATION.  All rights reserved.
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
 */

#define LOG_TAG "DatabaseHelper-JNI"

#include <JNIHelp.h>

#include <diskusage/dirsize.h>
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>

namespace android {

static jboolean isMaxBrightnessIn1stBoot(JNIEnv *env, jclass clazz)
{
#ifdef ENABLE_MAX_BRIGHTNESS_IN_1ST_BOOT
    //LOGD("max brightness in boot return JNI_TRUE");
    return JNI_TRUE;
#else
    //LOGD("max brightness in boot Return JNI_false");
    return JNI_FALSE;
#endif
}

static const JNINativeMethod g_methods[] = {
    { "isMaxBrightnessIn1stBoot", "()Z",
        (void*)isMaxBrightnessIn1stBoot },
};

int register_com_android_databasehelper(JNIEnv *env) {
    if (jniRegisterNativeMethods(
            env, "com/android/providers/settings/DatabaseHelper", g_methods, NELEM(g_methods)) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

} // namespace android

int JNI_OnLoad(JavaVM *jvm, void* reserved) {
    JNIEnv *env;

    if (!jvm || jvm->GetEnv((void**)&env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    return android::register_com_android_databasehelper(env);
}
