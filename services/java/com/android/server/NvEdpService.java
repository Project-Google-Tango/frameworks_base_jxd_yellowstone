/*
 * Copyright (c) 2013-2014, NVIDIA CORPORATION.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property
 * and proprietary rights in and to this software, related documentation
 * and any modifications thereto.  Any use, reproduction, disclosure or
 * distribution of this software and related documentation without an express
 * license agreement from NVIDIA Corporation is strictly prohibited.
 */

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.INvEdpManager;
import android.os.NvEdpManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Slog;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;
import android.widget.Toast;

class NvEdpService extends INvEdpManager.Stub {
    private static final String TAG = "NvEdpService";
    private static final boolean DEBUG = NvEdpManager.DEBUG;
    private static final boolean DEBUG_POLICY = false;
    private static final String POWER_SUPPLY_PATH = "/sys/class/power_supply/";

    // policy xml tags and attributes:
    private static final String TAG_POLICY_ASSETS = "edp_policy";
    private static final String ATTR_VERSION = "version";
    private static final String TAG_HARDWARE = "hardware";
    private static final String ATTR_HARDWARE_NAME = "name";
    private static final String TAG_DEVICE = "device";
    private static final String ATTR_DEVICE_NAME = "name";
    private static final String TAG_BELOW = "below";
    private static final String TAG_ABOVE = "above";
    private static final String TAG_CHARGING = "charging";
    private static final String ATTR_LIMIT = "limit";
    private static final String ATTR_LEVEL = "level";

    private static final String POLICY_FILE_VERSION = "1.0";

    private static final int ACTION_ABOVE = 0;
    private static final int ACTION_BELOW = 1;
    private static final int ACTION_CHARGING = 2;
    private static final int LEVEL_CONNECTED = 101; // One above the 100% battery level range

    class Policy {
        public String device;
        public int action;
        public int limit;
    };

    class ERT {
        public ArrayList<Policy> policy;
        public ERT() {
            policy = new ArrayList<Policy>();
        }
    };

    private Context mContext;
    private AudioManager mAudioManager;
    private PowerManager mPowerManager;
    private TreeMap<Integer,ERT> mERT;
    private TreeMap<String,Integer> mState;
    private TreeMap<String,String> mEdpLimitedText;
    private int mBatteryLevel = 100;
    private boolean mPowerConnected = true;
    private BroadcastReceiver mBroadcastReceiver;
    private boolean mHardwareSupported = false;
    private Toast mToast;
    private Handler mToastHandler;

    public NvEdpService(Context context) {
        mContext = context;
        mToastHandler = new Handler();
        mERT = new TreeMap<Integer,ERT>();
        mState = new TreeMap<String,Integer>();
        mEdpLimitedText = new TreeMap<String,String>();
        {
            mEdpLimitedText.put(NvEdpManager.EDP_CLIENT_BACKLIGHT, context.getResources().getString(com.android.internal.R.string.edp_brightness_limited));
            mEdpLimitedText.put(NvEdpManager.EDP_CLIENT_AUDIO_VOLUME, context.getResources().getString(com.android.internal.R.string.edp_volume_limited));
            mEdpLimitedText.put(NvEdpManager.EDP_CLIENT_CAMERA_FLASH, context.getResources().getString(com.android.internal.R.string.edp_camera_flash_limited));
        }
        if (mERT == null || mState == null) {
            Slog.e(TAG, "fatal OOM");
            return;
        }

        if (false == isBatterySupported()) {
            return;
        }

        if (initPolicy() == false) {
            mHardwareSupported = false;
        }

        if (mHardwareSupported != true) {
            mERT.clear();
        }

        if (mERT.size() > 0) {
            if (DEBUG) Slog.d(TAG, "policy supported");
            mContext.registerReceiver(new BootReceiver(), new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        }

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    private boolean isBatterySupported() {
        boolean supported = false;
        FileInputStream is = null;
        byte [] buffer = new byte[2048];
        File power_supply = new File(POWER_SUPPLY_PATH);
        if (buffer != null && power_supply != null && power_supply.exists()) {
            for (String name : power_supply.list()) {
                try {
                    is = new FileInputStream(POWER_SUPPLY_PATH + name + "/type");
                } catch (FileNotFoundException e) {
                }
                if (is != null) {
                    try {
                        int count = is.read(buffer);
                        if (count > 0) {
                            String type = new String(buffer, 0, count).trim();
                            if (DEBUG) Slog.d(TAG, "name: " + name + " type: " + type);
                            if (type.equals("Battery")) {
                                supported = true;
                            }
                        }
                    } catch (IOException e) {
                        Slog.e(TAG, "oops: " + e);
                    } finally {
                        try {
                            is.close();
                        } catch (IOException e) {
                        }
                        is = null;
                    }
                }
                if (supported) {
                    return true;
                }
            }
        }

        return false;
    }

    private final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            mContext.unregisterReceiver(this);
            mBroadcastReceiver = new BatteryReceiver();
            if (mBroadcastReceiver != null) {
                Slog.d(TAG, "starting");
                mContext.registerReceiver(mBroadcastReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
        }
    };

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, scale);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                boolean plugged = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
                int percent = (level * scale) / 100;
                if (DEBUG) Slog.d(TAG, "Battery: " + present + " status: " + status + " plugged: " + plugged +
                    " level: " + level + " scale: " + scale + " (" + percent + "%)");

                if (mPowerConnected != plugged) {
                    if (plugged) { // trigger any policy actions for charging.
                        notifyPolicy(mERT.get(LEVEL_CONNECTED), ACTION_CHARGING);
                    } else { // sweep from 100% down to the current battery level
                        mBatteryLevel = 100;
                        notifyLevelChanges(percent);
                        mBatteryLevel = percent;
                    }
                    mPowerConnected = plugged;
                } else if (present && !plugged) { // only notify changes if battery is present and not charging
                    notifyLevelChanges(percent);
                    mBatteryLevel = percent;
                }
            }
        }
    };

    protected void finalize() {
        if (mContext != null && mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
            mContext = null;
        }
    }

    private void sendIntent() {
        Intent intent = new Intent(NvEdpManager.EDP_INTENT);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            for (String device : mState.keySet()) {
                intent.putExtra(device, mState.get(device).intValue());
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    private void applyLevelChange(String device, int level) {
        int cur_value = -1;
        int max_value = -1;
        float percent = (float)level/100;
        int edp_limit = -1;
        if(device.equals(NvEdpManager.EDP_CLIENT_BACKLIGHT)) {
            max_value = mPowerManager.getMaximumScreenBrightnessSetting();
            cur_value = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, -1);
            edp_limit = Math.round(percent * max_value);
            if(cur_value > edp_limit) {
                if(DEBUG) Slog.d(TAG, "apply "+ device + " cur: "+cur_value+" limit: "+ edp_limit+" max: "+max_value);
                mPowerManager.setBacklightBrightness(edp_limit); // show Toast here?
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, edp_limit);
            }
        }
        else if(device.equals(NvEdpManager.EDP_CLIENT_AUDIO_VOLUME)) {
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = 0; streamType < numStreamTypes; streamType++) {
                max_value = mAudioManager.getStreamMaxVolume(streamType);
                cur_value = mAudioManager.getStreamVolume(streamType);
                edp_limit = Math.round(percent * max_value);
                if(cur_value > edp_limit) {
                    if(DEBUG) Slog.d(TAG, "apply "+ device + " cur: "+cur_value+" limit: "+ edp_limit+" max: "+max_value);
                    mAudioManager.setStreamVolume(streamType, edp_limit, 0); // show Toast here?
                }
            }
        }
    }

    private void notifyPolicy(ERT t, int action) {
        if (t != null) {
            for (Policy p : t.policy) {
                if (p.action == action) {
                    if (DEBUG) Slog.d(TAG, "trigger: " + p.device + " limit: " + p.limit);
                    mState.put(p.device, new Integer(p.limit));
                    sendIntent();
                    applyLevelChange(p.device, p.limit);
                }
            }
        }
    }

    private boolean getPolicyEvents(ERT t, int action) {
        boolean changed = false;
        if (t != null) {
            for (Policy p : t.policy) {
                if (p.action == action) {
                    mState.put(p.device, new Integer(p.limit));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void notifyLevelChanges(int level) {
        boolean changed = false;
        boolean rising = (level > mBatteryLevel);
        Integer k;
        if (rising) {
            k = mERT.higherKey(mBatteryLevel);
            while (k != null && k <= level) {
                if (getPolicyEvents(mERT.get(k), ACTION_ABOVE) == true) {
                    changed = true;
                }
                k = mERT.higherKey(k);
            }
        } else {
            k = mERT.floorKey(mBatteryLevel);
            while (k != null && (k > level || level == 0)) {
                if (getPolicyEvents(mERT.get(k), ACTION_BELOW) == true) {
                   changed = true;
                }
                k = mERT.lowerKey(k);
            }
        }
        if (!changed) {
            return;
        }
        for (String device : mState.keySet()) {
            Integer limit = mState.get(device);
            if (DEBUG) Slog.d(TAG, "trigger: " + device + " limit: " + limit);
            applyLevelChange(device, limit.intValue());
        }
        sendIntent();
    }

    @Override
    public int getDeviceLimit(String device, int defaultValue) {
        Integer limit = mState.get(device);
        if (limit != null)
            return limit.intValue();
        return defaultValue;
    }

    @Override
    public int checkDeviceLimit(String device, int value, int maxValue) {
        if (device.equals(NvEdpManager.EDP_CLIENT_BACKLIGHT) || device.equals(NvEdpManager.EDP_CLIENT_AUDIO_VOLUME)) {
            int percent = getDeviceLimit(device, 100);
            int edp_limit = (maxValue * percent) / 100;

            if (DEBUG)
                Slog.d(TAG, "checkDeviceLimit device:"+device+" value:"+value+" maxValue:"+maxValue+" edp_limit:"+edp_limit +" percent:"+percent);

            if (value > edp_limit) {
                showToast(device);
                return edp_limit;
            }
        }
        return value;
    }

    @Override
    public boolean isHardwareSupported() {
        return mHardwareSupported;
    }

    public void showToast(String device) {
        String text = mEdpLimitedText.get(device);
        final String msg = (text == null) ? (device + " Limited (message undefined).") : text;
        mToastHandler.post(new Runnable() {
            @Override
            public void run() {
                if(mToast == null)
                    mToast = Toast.makeText(mContext, msg, Toast.LENGTH_SHORT);
                else{
                    mToast.setText(msg);
                    mToast.show();
                }
            }
        });
    }

    private boolean initPolicy() {
        XmlResourceParser parser = null;
        boolean ok = false;

        try {
            parser = mContext.getResources().getXml(com.android.internal.R.xml.edp_device_policy);

            XmlUtils.beginDocument(parser, TAG_POLICY_ASSETS);
            String version = parser.getAttributeValue(null, ATTR_VERSION);
            if (DEBUG_POLICY) Slog.d(TAG, "edp policy version " + version);
            if (POLICY_FILE_VERSION.equals(version)) {
                ok = parseEdpPolicy(parser);
            }
        } catch (Resources.NotFoundException e) {
            Slog.w(TAG, "edp policy file not found", e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "XML parser exception reading edp policy", e);
        } catch (IOException e) {
            Slog.w(TAG, "I/O exception reading edp policy", e);
        } finally {
            if (parser != null) {
                 parser.close();
            }
        }
        return ok;
    }

    private boolean parseEdpPolicy(XmlResourceParser parser) throws XmlPullParserException, IOException {
        boolean inDevice = false;
        String device = null;
        final String thisHardware = SystemProperties.get("ro.hardware");

        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_DOCUMENT)
                break;

            String element = parser.getName();
            if (element == null) {
                if (DEBUG_POLICY) Slog.d(TAG, "element is null");
                return false;
            }

            if (inDevice == false && TAG_HARDWARE.equals(element) && type == XmlPullParser.START_TAG) {
                String hardware = parser.getAttributeValue(null, ATTR_HARDWARE_NAME);
                if (hardware == null) {
                    if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: hardware name is null");
                    return false;
                }
                if (DEBUG_POLICY) Slog.d(TAG, "edp policy for hardware: " + hardware);
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(';');
                if (splitter == null) {
                    return false;
                }
                splitter.setString(hardware);
                while (splitter.hasNext()) {
                    String h = splitter.next();
                    if (h != null && h.equals(thisHardware)) {
                        if (DEBUG_POLICY) Slog.d(TAG, "edp policy is supported: " + h);
                        mHardwareSupported = true;
                        break;
                    }
                }
                continue;
            }

            if (type == XmlPullParser.END_TAG && inDevice) {
                if (TAG_DEVICE.equals(element)) {
                    inDevice = false;
                    if (DEBUG_POLICY) Slog.d(TAG, "-device: " + device);
                }
                continue;
            }

            if (inDevice == false && TAG_DEVICE.equals(element)) {
                device = parser.getAttributeValue(null, ATTR_DEVICE_NAME);
                if (device == null) {
                    if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: device name is null");
                    return false;
                }
                inDevice = true;
                if (DEBUG_POLICY) Slog.d(TAG, "+device: " + device);
            } else if (inDevice == true) {
                if (TAG_BELOW.equals(element) || TAG_ABOVE.equals(element) || TAG_CHARGING.equals(element)) {
                    if (DEBUG_POLICY) Slog.d(TAG, "+policy: " + element);

                    String attr = parser.getAttributeValue(null, ATTR_LIMIT);
                    if (attr == null) {
                        if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: " + element + " null limit");
                        return false;
                    }
                    int limit = 0;
                    try {
                        limit = Integer.parseInt(attr);
                    } catch (NumberFormatException e) {
                        if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: " + element + " invalid limit: " + attr);
                        return false;
                    }
                    if (DEBUG_POLICY) Slog.d(TAG, "limit: " + limit);
                    if (TAG_CHARGING.equals(element)) {
                        if (addPolicy(LEVEL_CONNECTED, device, ACTION_CHARGING, limit) == false) {
                            Slog.e(TAG, "addPolicy failed OOM");
                            return false;
                        }
                    } else {
                        int level = parser.getAttributeIntValue(null, ATTR_LEVEL, -1);
                        if (level == -1) {
                            if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: '" + element + "' invalid or null level");
                            return false;
                        }
                        if (DEBUG_POLICY) Slog.d(TAG, "level: " + level);
                        int action = TAG_BELOW.equals(element) ? ACTION_BELOW : ACTION_ABOVE;
                        if (addPolicy(level, device, action, limit) == false) {
                            Slog.e(TAG, "addPolicy failed OOM");
                            return false;
                        }
                    }

                    if (DEBUG_POLICY) Slog.d(TAG, "-policy: " + element);
                } else {
                    if (DEBUG_POLICY) Slog.e(TAG, "edp policy parse error: device '" + device + "' tag '" + element + "' unknown");
                    return false;
                }
            }
        }
        return true;
    }

    boolean addPolicy(int level, String device, int action, int limit) {
        ERT t = mERT.get(level);
        if (t == null) {
            t = new ERT();
            if (t == null || t.policy == null) {
                return false;
            }
            mERT.put(level, t);
        }
        Policy p = new Policy();
        if (p == null) {
            return false;
        }
        p.device = device;
        p.action = action;
        p.limit = limit;
        t.policy.add(p);
        return true;
    }
};
