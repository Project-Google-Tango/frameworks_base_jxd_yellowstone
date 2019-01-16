/*
 * Copyright (c) 2012-2013, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia.NvCPLSvc;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

//structure to communicate between settings and NvSaver
public class NvSaverAppInfo implements Parcelable {

    public static final int NVSAVER_LIST_NONE = 0x1;
    public static final int NVSAVER_LIST_WHITELIST = 0x2;
    public static final int NVSAVER_LIST_BLACKLIST = 0x3;

    public static final int NVSAVER_ACTIVITY_HIGH = 0x1;
    public static final int NVSAVER_ACTIVITY_MIDIUM = 0x2;
    public static final int NVSAVER_ACTIVITY_LOW = 0x3;

    private String appLabel;    //app label
    private Drawable appIcon;   //app icon
    private int appActivity;    //app activity
    private float powerSaver;    //power Saver
    public int uid;
    public int appList;
    public int wakeupTimes;
    public int wowWakeupTimes;
    public String pkgName;
    public long wakeupStatsTime;
    public long totalWakeupStatsTime;

    public NvSaverAppInfo(){};

    public NvSaverAppInfo(Parcel pl){
        uid = pl.readInt();
        appList = pl.readInt();
        wakeupTimes = pl.readInt();
        wowWakeupTimes = pl.readInt();
        pkgName = pl.readString();
        wakeupStatsTime = pl.readLong();
        totalWakeupStatsTime = pl.readLong();
        appLabel = null;
        appIcon = null;
        appActivity = 0;
        powerSaver = 0;
    }

    public NvSaverAppInfo(int u, int a, int w, int wow, String pkg, long t1, long t2) {
        uid = u;
        appList = a;
        wakeupTimes = w;
        wowWakeupTimes = wow;
        pkgName = pkg;
        wakeupStatsTime = t1;
        totalWakeupStatsTime = t2;
        appLabel = null;
        appIcon = null;
        appActivity = 0;
        powerSaver = 0;
    }

    public String getAppLabel() {
        return appLabel;
    }
    public void setAppLabel(String appLabel) {
        this.appLabel = appLabel;
    }
    public Drawable getAppIcon() {
        return appIcon;
    }
    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }
    public int getAppActivity() {
        return appActivity;
    }
    public void setAppActivity(int activity){
        this.appActivity = activity;
    }
    public String getPkgName(){
        return pkgName ;
    }
    public void setPkgName(String pkgName){
        this.pkgName=pkgName ;
    }
    public int getUid() {
        return uid;
    }
    public void setUid(int uid) {
        this.uid = uid;
    }
    public int getWakeupTimes() {
        return wakeupTimes;
    }
    public void setWakeupTimes(int wakeupTimes) {
        this.wakeupTimes = wakeupTimes;
    }
    public int getWowWakeupTimes() {
        return wowWakeupTimes;
    }
    public void setWowWakeupTimes(int wowWakeupTimes) {
        this.wowWakeupTimes = wowWakeupTimes;
    }
    public long getTotalWakeupStatsTime() {
        return totalWakeupStatsTime;
    }
    public void setTotalWakeupStatsTime(long totalWakeupStatsTime) {
        this.totalWakeupStatsTime = totalWakeupStatsTime;
    }
    public long getWakeupStatsTime() {
        return wakeupStatsTime;
    }
    public void setWakeupStatsTime(long wakeupStatsTime) {
        this.wakeupStatsTime = wakeupStatsTime;
    }
    public int getAppList() {
        return appList;
    }
    public void setAppList(int appList) {
        this.appList = appList;
    }
    public float getPowerSaver() {
        return powerSaver;
    }
    public void setPowerSaver(float powerSaver) {
        this.powerSaver = powerSaver;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeInt(appList);
        dest.writeInt(wakeupTimes);
        dest.writeInt(wowWakeupTimes);
        dest.writeString(pkgName);
        dest.writeLong(wakeupStatsTime);
        dest.writeLong(totalWakeupStatsTime);
    }

    public static final Parcelable.Creator<NvSaverAppInfo> CREATOR = new Parcelable.Creator<NvSaverAppInfo>() {

        @Override
        public NvSaverAppInfo createFromParcel(Parcel source) {
            return new NvSaverAppInfo(source);
    }

        @Override
        public NvSaverAppInfo[] newArray(int size) {
            return new NvSaverAppInfo[size];
        }
    };
}
