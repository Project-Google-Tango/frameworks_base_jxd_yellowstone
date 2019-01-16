/*
 * Copyright (c) 2013-2014, NVIDIA CORPORATION.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property
 * and proprietary rights in and to this software, related documentation
 * and any modifications thereto.  Any use, reproduction, disclosure or
 * distribution of this software and related documentation without an express
 * license agreement from NVIDIA Corporation is strictly prohibited.
 */

package android.os;

import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.os.INvEdpManager;
import android.os.RemoteException;
import android.os.AsyncTask;
import android.widget.Toast;
import android.util.Log;

import java.util.List;

/** @hide */
public final class NvEdpManager {
    public static final boolean DEBUG = false;
    private static final String TAG = "NvEdpManager";

    public static final String EDP_SERVICE = "nvedpservice";
    public static final String EDP_INTENT = "android.nvidia.intent.action.edpevent";

    public static final String EDP_CLIENT_BACKLIGHT = "backlight";
    public static final String EDP_CLIENT_AUDIO_VOLUME = "audio_volume";
    public static final String EDP_CLIENT_CAMERA_FLASH = "camera_flash";
    public static final String EDP_CLIENT_CAMERA_DISABLE = "camera_disable";
    public static final String EDP_CLIENT_CAMERA_TORCH = "camera_torch";
    public static final String EDP_CLIENT_CAMERA_PREVIEW = "camera_preview";
    public static final String EDP_CLIENT_VIBRATOR = "vibrator_disable";

    private static INvEdpManager mService;
    private static int mIsEnabled = -1;

    public static synchronized boolean isEnabled() {
        if (mIsEnabled == -1) {
            mService = INvEdpManager.Stub.asInterface(ServiceManager.getService(EDP_SERVICE));
            if (mService != null) {
                try {
                    mIsEnabled = mService.isHardwareSupported() ? 1 : 0;
                } catch (RemoteException e) {
                }
            } else {
                mIsEnabled = 0;
            }
        }
        return (mIsEnabled == 1) ? true : false;
    }

    public static int checkDeviceLimit(String device, int value, int maxValue){
        if (isEnabled() == false)
           return value;

        if (mService != null) {
            try {
                return mService.checkDeviceLimit(device, value, maxValue);
            } catch (RemoteException e) {
                Log.e(TAG, "checkDeviceLimit oops: " + e);
            }
        }
        return value;
    }

    public static int getDeviceLimit(String device, int defaultValue) {
        if (isEnabled() == false)
            return defaultValue;

        if (mService != null) {
            try {
                return mService.getDeviceLimit(device, defaultValue);
            } catch (RemoteException e) {
                Log.e(TAG, "getDeviceLimit oops: " + e);
            }
        }
        return defaultValue;
    }

    public static class NvEdpCamera {
        private static final String TAG = "NvEdpCamera";
        private static final boolean DEBUG = false;
        private Context mContext = null;
        private BroadcastReceiver mBroadcastReceiver = null;
        private boolean mEdpFlashDisabled = false;
        private String mEdpFlashClientParam = null;
        private Camera mCamera = null;

        public static NvEdpCamera getInstance(Camera camera, String packageName) {
            if (NvEdpManager.isEnabled() == false) {
                return null;
            }
            Camera.Parameters params = camera.getParameters();
            if (params == null) {
                return null;
            }
            List<String> modes = params.getSupportedFlashModes();
            if (modes == null) {
                return null;
            }
            if (modes.contains(Camera.Parameters.FLASH_MODE_OFF) == false) {
                return null;
            }

            Context context = initContext(packageName);
            if (context == null) {
                return null;
            }
            return new NvEdpCamera(context, camera);
        }

        private NvEdpCamera(Context context, Camera camera) {
            mContext = context;
            mCamera = camera;
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    edpProcessEvent(intent);
                }
            };
            mEdpFlashDisabled = (NvEdpManager.getDeviceLimit(NvEdpManager.EDP_CLIENT_CAMERA_FLASH, 0) != 0);
            mContext.registerReceiver(mBroadcastReceiver, new IntentFilter(NvEdpManager.EDP_INTENT));
        }

        private static Context initContext(String packageName) {
            Context context;

            if (DEBUG) Log.d(TAG, "initContext: creating context for: " + packageName);
            ActivityThread at = ActivityThread.currentActivityThread();
            if (at == null) {
                if (DEBUG) Log.e(TAG, "EDP init ActivityThread null");
                return null;
            }

            Application app = at.getApplication();
            if (app == null) {
                if (DEBUG) Log.e(TAG, "EDP init Application null");
                return null;
            }

            try {
                context = app.createPackageContext(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                context = null;
                if (DEBUG) Log.e(TAG, "EDP init createPackageContext failed: " + e);
            } catch (SecurityException e) {
                context = null;
                if (DEBUG) Log.e(TAG, "EDP init createPackageContext failed: " + e);
            }

            if (context == null) {
                if (DEBUG) Log.e(TAG, "EDP initContext null");
                return null;
            }
            return context;
        }

        public void release() {
            if (DEBUG) Log.d(TAG, "release");
            if (mContext != null) {
                if (mBroadcastReceiver != null) {
                    mContext.unregisterReceiver(mBroadcastReceiver);
                    mBroadcastReceiver = null;
                }
                mContext = null;
            }
            mCamera = null;
        }

        private void edpProcessEvent(Intent intent) {
            try {
                boolean update = false;
                if (intent.hasExtra(NvEdpManager.EDP_CLIENT_CAMERA_FLASH)) {
                    boolean disable = (intent.getIntExtra(NvEdpManager.EDP_CLIENT_CAMERA_FLASH, 0) == 1);
                    if (mEdpFlashDisabled != disable) {
                        if (DEBUG) Log.d(TAG, "EDP flash disable: " + mEdpFlashDisabled + " -> " + disable);
                        mEdpFlashDisabled = disable;
                        update = true;
                    }
                }
                if (update) {
                    mCamera.setParameters(mCamera.getParameters());
                }
            } catch (RuntimeException e) {
                if (DEBUG) Log.e(TAG, "oops: " + e);
            }
        }

        public void edpParametersOverride(Camera.Parameters params) {
            if (mEdpFlashDisabled) {
                List<String> modes = params.getSupportedFlashModes();
                if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                    if (Camera.Parameters.FLASH_MODE_OFF.equals(params.getFlashMode()) == false) {
                        if (DEBUG) Log.d(TAG, "override flash: " + params.getFlashMode());
                        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                        try{
                            NvEdpManager.mService.showToast(NvEdpManager.EDP_CLIENT_CAMERA_FLASH);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Show toast failed: " + e);
                        }
                    }
                }
            }
        }

        public void edpSetParameters(Camera.Parameters params) {
            List<String> supportedModes = params.getSupportedFlashModes();
            String flashMode = params.getFlashMode();
            if (supportedModes.contains(flashMode)) {
                mEdpFlashClientParam = flashMode;
            }

            // override clients params with EDP limits
            edpParametersOverride(params);
            if (DEBUG) Log.d(TAG, "edpSetParameters" +
                " flash: " + mEdpFlashClientParam + " -> " + params.getFlashMode());
        }

        public void edpGetParameters(Camera.Parameters params) {
            if (mEdpFlashClientParam != null) {
                params.setFlashMode(mEdpFlashClientParam);
            }
        }
    }
};
