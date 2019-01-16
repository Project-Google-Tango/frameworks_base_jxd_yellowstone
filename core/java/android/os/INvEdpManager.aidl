/*
 * Copyright (c) 2013-2014, NVIDIA CORPORATION.  All rights reserved.
 */

package android.os;

/** @hide */

interface INvEdpManager {
    int checkDeviceLimit(String device, int value, int maxValue);
    int getDeviceLimit(String device, int defaultValue);
    boolean isHardwareSupported();
    void showToast(String device);
}
