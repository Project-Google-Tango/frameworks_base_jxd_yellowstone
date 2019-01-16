/*
 * Copyright (c) 2012 - 2014, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia;

import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.ServiceManager;
import com.nvidia.NvCPLSvc.INvCPLRemoteService;
import com.nvidia.NvConstants;

public class NvAppProfiles {
    private static final String TAG = "NvAppProfiles";

    /**
     * Unique name used for NvCPLSvc to whitelist this class
     */
    static final String NV_APP_PROFILES_NAME = "Frameworks_NvAppProfiles";
    static final boolean DEBUG = false;
    private final Context mContext;
    private INvCPLRemoteService mNvCPLSvc = null;

    /**
     * Callback class given by the NvCPLService
     */

    public NvAppProfiles(Context context) {
        mContext = context;
    }

    public int getApplicationProfile(String packageName, int settingId) {
        int result = -1;
        getNvCPLService();

        if (mNvCPLSvc == null) {
            if (DEBUG) {
                Log.d(TAG, "NvCPLSvc is null");
            }

        } else {
            try {
                result = mNvCPLSvc.getAppProfileSettingInt(packageName, settingId);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        result = mNvCPLSvc.getAppProfileSettingInt(packageName, settingId);
                    } else {
                        if (DEBUG) {
                            Log.w(TAG, "App Profile: NvCPLSvc is null, trying to start service");
                        }
                        startNvCPLService();
                    }
                } catch (Exception ex) {
                    if (DEBUG) {
                        Log.w(TAG, "App Profile: Exception again. Going to start service. "+ex.getMessage());
                    }
                    startNvCPLService();
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to retrieve profile. Error="+e.getMessage());
            }
        }

        return result;
    }

    public String getApplicationProfileString(String packageName, int settingId) {
        String result = null;
        getNvCPLService();

        if (mNvCPLSvc == null) {
            if (DEBUG) {
                Log.d(TAG, "NvCPLSvc is null");
            }

        } else {
            try {
                result = mNvCPLSvc.getAppProfileSettingString(packageName, settingId);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        result = mNvCPLSvc.getAppProfileSettingString(packageName, settingId);
                    } else {
                        if (DEBUG) {
                            Log.w(TAG, "App Profile: NvCPLSvc is null, trying to start service");
                        }
                        startNvCPLService();
                    }
                } catch (Exception ex) {
                    if (DEBUG) {
                        Log.w(TAG, "App Profile: Exception again. Going to start service. "+ex.getMessage());
                    }
                    startNvCPLService();
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to retrieve profile. Error="+e.getMessage());
            }
        }

        return result;
    }

    public void setPowerMode(boolean bEnable) {
        if (DEBUG) { Log.w(TAG, "setPowerMode"); }
        getNvCPLService();

        Intent intent = new Intent();
        intent.setClassName(NvConstants.NvCPLSvc, NvConstants.NvCPLService);
        if (bEnable) {
            intent.putExtra(NvConstants.NvPowerMode, "0");
        }
        else {
            intent.putExtra(NvConstants.NvPowerMode, "-1");
        }
        intent.putExtra(NvConstants.NvStateId, 1);
        intent.putExtra(NvConstants.NvOrigin, 1);

        if (mNvCPLSvc == null) {
            if (DEBUG) {
                Log.d(TAG, "NvCPLSvc is null");
            }

        } else {
            try {
                if (DEBUG) {
                    Log.w(TAG, "calling NvCPLSvc to set power mode");
                }
                mNvCPLSvc.handleIntent(intent);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        mNvCPLSvc.handleIntent(intent);
                    } else {
                        if (DEBUG) {
                            Log.w(TAG, "App Profile: NvCPLSvc is null, trying to start service");
                        }
                        startNvCPLService();
                    }
                } catch (Exception ex) {
                    if (DEBUG) {
                        Log.w(TAG, "App Profile: Failed to set device power mode again. "
                                + "Going to start service. " + ex.getMessage());
                    }
                    startNvCPLService();
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to set device power mode. Error="+e.getMessage());
            }
        }
    }

    private void getNvCPLService() {
        if (mNvCPLSvc == null) {
            try {
                mNvCPLSvc = INvCPLRemoteService.Stub.asInterface(ServiceManager.getService("nvcpl"));
            } catch (Exception ex) {
                Log.e(TAG, "Failed to bind to service. " + ex.getMessage());
            }
        }
    }

    private void startNvCPLService() {
        Intent intent = new Intent("com.nvidia.NvCPLSvc.START_SERVICE");
        intent.setClassName("com.nvidia.NvCPLSvc", "com.nvidia.NvCPLSvc.NvCPLBootReceiver");
        mContext.sendBroadcast(intent);
    }
}
