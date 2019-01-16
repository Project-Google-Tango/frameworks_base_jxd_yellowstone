#
# Copyright (C) 2013 NVIDIA CORPORATION.  All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    com_android_providers_settings_DatabaseHelper.cpp

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDES)

LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libcutils \

LOCAL_STATIC_LIBRARIES := \
    libdiskusage

LOCAL_MODULE := libdatabasehelper_jni
LOCAL_MODULE_TAGS := optional

# Make the first brightness Max 255
ifeq ($(BOARD_FIRST_MAX_BRIGHTNESS_FOR_OOBE), true)
LOCAL_CFLAGS += -DENABLE_MAX_BRIGHTNESS_IN_1ST_BOOT=1
endif

include $(BUILD_SHARED_LIBRARY)
