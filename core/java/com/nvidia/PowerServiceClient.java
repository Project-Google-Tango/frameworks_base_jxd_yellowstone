/*
 * Copyright (c) 2013-2014 NVIDIA Corporation.  All rights reserved.
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
 * Class structure based upon Camera in Camera.java:
 * Copyright (C) 2009 The Android Open Source Project
 */

package com.nvidia;

public class PowerServiceClient {

    private int mNativePowerServiceClient;

    // The following must match power.h
    public static final int POWER_HINT_VSYNC = 0x00000001;
    public static final int POWER_HINT_INTERACTION = 0x00000002;
    public static final int POWER_HINT_VIDEO_ENCODE = 0x00000003;
    public static final int POWER_HINT_VIDEO_DECODE = 0x00000004;
    public static final int POWER_HINT_APP_PROFILE = 0x00000005;
    public static final int POWER_HINT_APP_LAUNCH = 0x00000006;
    public static final int POWER_HINT_SHIELD_STREAMING = 0x00000007;
    public static final int POWER_HINT_HIGH_RES_VIDEO = 0x00000008;
    public static final int POWER_HINT_MIRACAST = 0x00000009;

    public static final int POWER_HINT_CAMERA = 0x0000000B;
    public static final int POWER_HINT_MULTITHREAD_BOOST = 0x0000000C;
    public static final int POWER_HINT_COUNT = 0x0000000D;

    public static final int CAMERA_HINT_STILL_PREVIEW_POWER = 0x00000000;
    public static final int CAMERA_HINT_VIDEO_PREVIEW_POWER = 0x00000001;
    public static final int CAMERA_HINT_VIDEO_RECORD_POWER = 0x00000002;
    public static final int CAMERA_HINT_PERF = 0x00000003;
    public static final int CAMERA_HINT_FPS = 0x00000004;
    public static final int CAMERA_HINT_RESET = 0x00000005;
    public static final int CAMERA_HINT_COUNT = 0x00000006;
    public static final int CAMERA_HINT_HIGH_FPS_VIDEO_RECORD_POWER = 0x00000007;

    public PowerServiceClient() {
        mNativePowerServiceClient = 0;
        init();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            if (mNativePowerServiceClient != 0) {
                release();
            }
        }
    }

    private native void init();
    private native void release();

    private native static void nativeClassInit();
    static { nativeClassInit(); }

    public native void sendPowerHint(int hint, int []data);
}
