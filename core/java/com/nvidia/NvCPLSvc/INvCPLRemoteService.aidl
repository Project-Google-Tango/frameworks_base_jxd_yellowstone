/*
 * Copyright (c) 2012-2014, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia.NvCPLSvc;
import com.nvidia.NvCPLSvc.NvSaverAppInfo;
import android.content.Intent;
import com.nvidia.NvCPLSvc.NvAppProfile;

/**
 * {@hide}
 */

interface INvCPLRemoteService {

    // access for different profile settings - string, int, and bool
    String getAppProfileSettingString(String pkgName, int settingId);

    int getAppProfileSettingInt(String pkgName, int settingId);

    int getAppProfileSettingBoolean(String pkgName, int settingId);

    /** @deprecated Always returns null. Use {@link #getAppProfileSettingInt()} instead. */
    byte[] getAppProfileSetting3DVStruct(String pkgName);

    void handleIntent(in Intent intent);

    //set NvSaver single app to white/black list
    boolean setNvSaverAppInfo(String pkgName, int list);

    //set NvSaver all app to white/black list
    boolean setNvSaverAppInfoAll(in List<NvSaverAppInfo> appList);

    //get NvSaver white/black app list, use flag to control which list to get
    List<NvSaverAppInfo> getNvSaverAppInfo(int list);

    boolean setAppProfileSetting(String packageName, int typeId, int settingId, String value);

    int getActiveProfileType(String packageName);

    int[] getProfileTypes(String packageName);

    boolean setActiveProfileType(String packageName, int typeId);

    NvAppProfile[] getAppProfiles(in String[] packageNames);

    //set whitelisted persist.sys.<propName> properties
    int setSysProperty(String propName, String value);
}
