/*
 * Copyright (c) 2014, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia.NvCPLSvc;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

/**
 * Container class for application profile and it's settings
 *
 * This is just a small, parcelable version intended to be used for the NvCPLSvc api clients.
 */
public class NvAppProfile implements Parcelable {

    /** Profile type id (one of the constants in ProfileTypeId) */
    public final int    typeId;

    /** Android package name which the profile belongs to */
    public final String pkgName;

    /** Version of the android package if the profile is for a specific package version. Null otherwise */
    public final String pkgVersion;

    /** List of (setting-id, value) pairs. The id is one of the constants in NvAppProfileSettingId. */
    public SparseArray<String> settings;

    /**
     * Constructor
     *
     * @param typeId
     *            profile type id (one of the constants in ProfileTypeId)
     * @param pkgName
     *            android package name
     * @param pkgVersion
     *            android package version if the profile is for specific
     *            version, null otherwise
     * @param settings
     *            SparseArray of setting-id, value -pairs
     */
    public NvAppProfile(int typeId, String pkgName, String pkgVersion, SparseArray<String> settings) {
        this.typeId     = typeId;
        this.pkgName    = pkgName;
        this.pkgVersion = pkgVersion;
        this.settings   = settings;
    }

    //
    // Parcelable interface
    //

    public static final Parcelable.Creator<NvAppProfile> CREATOR = new Parcelable.Creator<NvAppProfile>() {
        @Override
        public NvAppProfile createFromParcel(Parcel parcel) {
            return NvAppProfile.createFromParcel(parcel);
        }

        @Override
        public NvAppProfile[] newArray(int size) {
            return new NvAppProfile[size];
        }

    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flag) {
        parcel.writeInt(this.typeId);
        parcel.writeString(encodeNull(this.pkgName));
        parcel.writeString(encodeNull(this.pkgVersion));
        parcel.writeInt(this.settings.size());
        for (int i = 0; i < this.settings.size(); i++) {
            parcel.writeInt(this.settings.keyAt(i));
            parcel.writeString(this.settings.valueAt(i));
        }
    }

    private static NvAppProfile createFromParcel(Parcel parcel) {
        int typeId = parcel.readInt();
        String pkgName = decodeNull(parcel.readString());
        String pkgVersion = decodeNull(parcel.readString());
        int numSettings = parcel.readInt();
        SparseArray<String> settings = new SparseArray<String>();
        for (int i = 0; i < numSettings; i++) {
            int settingId = parcel.readInt();
            String value = parcel.readString();
            settings.append(settingId, value);
        }
        return new NvAppProfile(typeId, pkgName, pkgVersion, settings);
    }

    private static String encodeNull(String str) {
        return str != null ? str : "";
    }

    private static String decodeNull(String str) {
        return !str.equals("") ? str : null;
    }
}
