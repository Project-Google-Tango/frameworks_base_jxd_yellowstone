/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2013-2014, NVIDIA CORPORATION.  All rights reserved.
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

package com.android.internal.policy.impl;

import com.android.internal.app.AlertController;
import com.android.internal.app.AlertController.AlertParams;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.R;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerPolicy.WindowManagerFuncs;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.ListView;
import android.widget.TextView;
import android.app.ActivityManager;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.List;

import android.net.wifi.WifiManager;
import android.app.Activity;
import com.nvidia.NvWFDManager;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActions implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener  {

    private static final String TAG = "GlobalActions";
    private boolean DBG = false;

    private static final boolean SHOW_SILENT_TOGGLE = true;

    private final Context mContext;
    private final WindowManagerFuncs mWindowManagerFuncs;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private PowerManager mPowerManager;

    private ArrayList<Action> mItems;
    private GlobalActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleAction.State mAirplaneState = ToggleAction.State.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private boolean mPC2THORConnected = false;
    private AlertDialog mConnProgressDlg = null;
    private static final int MAX_REMEMBERED_SINKS = 3;
    private String mConnectingSink = null;
    private int mMiracastIndex = -1;
    private boolean mIsOwner = true;

    private int mMiracastEnabIcon;
    private int mMiracastDisIcon;
    private int mGlobalActionMiracastMsg;
    private int mMiracastEnbStatus;
    private int mMiracastDisStatus;

    /**
     * @param context everything needs a context :(
     */
    public GlobalActions(Context context, WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        // get notified of phone state changes
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasTelephony = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);
        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned) {
        // putting it here to avoid using persistent property
        if(Integer.parseInt(SystemProperties.get("nvwfd.debug", "0")) == 1) {
            DBG = true;
        } else {
            DBG = false;
        }

        //mIsOwner = (ActivityManager.getCurrentUser() == UserHandle.USER_OWNER);
        if (mIsOwner) {
            if ((Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) &&
                (mNvWFDSvc == null)) {
                mNvWFDSvc = new NvWFDManager(mContext);
                mMiracastRememberedSinkList = new ArrayList<String>();
                mNvwfdPwrListener = new NvwfdPwrListener();
            }
        }
        //if (DBG) Log.d(TAG,"current user is:" + (mIsOwner?"owner":"not owner"));
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            handleShow();
        }
    }

    public boolean isDialogShowing() {
        return (mDialog != null) ? mDialog.isShowing() : false;
    }

    public void dismissDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("GlobalActions");
        mDialog.getWindow().setAttributes(attrs);
        mDialog.show();
        mDialog.getWindow().getDecorView().setSystemUiVisibility(View.STATUS_BAR_DISABLE_EXPAND);
    }

    private BroadcastReceiver mPC2ThorStreamingStatusReceiver = new PC2ThorStreamingStatusReceiver();
    private void setPC2ThorStreamingStatus(Intent intent) {
        if (intent != null) {
            mPC2THORConnected = intent.getBooleanExtra("status", false);
            if (DBG) Log.d(TAG, "mPC2THORConnected: " + mPC2THORConnected);
        }
    }

    private class PC2ThorStreamingStatusReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            setPC2ThorStreamingStatus(intent);
        }
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private GlobalActionsDialog createDialog() {
        // Simple toggle style if there's no vibrator, otherwise use a tri-state
        if (!mHasVibrator) {
            mSilentModeAction = new SilentModeToggleAction();
        } else {
            mSilentModeAction = new SilentModeTriStateAction(mContext, mAudioManager, mHandler);
        }
        mAirplaneModeOn = new ToggleAction(
                R.drawable.ic_lock_airplane_mode,
                R.drawable.ic_lock_airplane_mode_off,
                R.string.global_actions_toggle_airplane_mode,
                R.string.global_actions_airplane_mode_on_status,
                R.string.global_actions_airplane_mode_off_status) {

            void onToggle(boolean on) {
                if (mHasTelephony && Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {
                    mIsWaitingForEcmExit = true;
                    // Launch ECM exit dialog
                    Intent ecmDialogIntent =
                            new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                    ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(ecmDialogIntent);
                } else {
                    changeAirplaneModeSystemSetting(on);
                }
            }

            @Override
            protected void changeStateFromPress(boolean buttonOn) {
                if (!mHasTelephony) return;

                // In ECM mode airplane state cannot be changed
                if (!(Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE)))) {
                    mState = buttonOn ? State.TurningOn : State.TurningOff;
                    mAirplaneState = mState;
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        onAirplaneModeChanged();
        if (Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) {
            initializeMiracastModeRes();

            mMiracastModeOn = new ToggleAction(
                    mMiracastEnabIcon,
                    mMiracastDisIcon,
                    mGlobalActionMiracastMsg,
                    mMiracastEnbStatus,
                    mMiracastDisStatus) {

                void onToggle(boolean on) {
                    boolean isWifiEnabled = ((WifiManager)mContext.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
                    boolean airplaneModeOn = false;
                    /*boolean airplaneModeOn = Settings.Global.getInt(
                            mContext.getContentResolver(),
                            Settings.Global.AIRPLANE_MODE_ON,
                            0) == 1;*/
                    if (!isWifiEnabled) {
                        launchWiFiSettings();
                        return;
                    } else if (airplaneModeOn || mPC2THORConnected) {
                        return;
                    }

                    if (on) {
                        if (mMiracastRememberedSinkList != null && mMiracastRememberedSinkList.size() == 1) {
                            // start connection to single remembered sink
                            connectToMiracastSink(mMiracastRememberedSinkList.get(0));
                        } else if (mMiracastRememberedSinkList != null && mMiracastRememberedSinkList.size() > 1) {
                            // more than one remembered sink, show the sink list
                            isConnectionInitiated = true; // this is to not lose connection
                            mHandler.sendEmptyMessage(MESSAGE_MIRACAST_REMSINKSHOW);
                        } else {
                            launchMiracastSettings();

                        }
                    } else {
                        boolean closeConnection = false;
                        if (mNvWFDSvc != null && !isWfdStarted) {
                            mNvWFDSvc.start();
                            mNvWFDSvc.setListener(mNvwfdPwrListener);
                            isWfdStarted = true;
                            closeConnection = true;
                        }

                        mMiracastModeOn.updateMessage(null);
                        if (mNvWFDSvc.isConnected())
                            if (mNvWFDSvc.isConnectionOngoing()) {
                                mNvWFDSvc.cancelConnect();
                            } else {
                                mNvWFDSvc.disconnect();
                            }

                        if(closeConnection) {
                            mNvWFDSvc.close();
                            isWfdStarted = false;
                        }
                    }
                }

                @Override
                protected void changeStateFromPress(boolean buttonOn) {
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return false;
                }

                public boolean isEnabled() {
                    return (!mPC2THORConnected);
                }
            };
            onMiracastModeChanged();
        }
        mItems = new ArrayList<Action>();

        // first: power off
        mItems.add(
            new SinglePressAction(
                    com.android.internal.R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off) {

                public void onPress() {
                    // shutdown by making sure radio and power are handled accordingly.
                    mWindowManagerFuncs.shutdown(true);
                }

                public boolean onLongPress() {
                    mWindowManagerFuncs.rebootSafeMode(true);
                    return true;
                }

                public boolean showDuringKeyguard() {
                    return true;
                }

                public boolean showBeforeProvisioning() {
                    return true;
                }
            });

        if (SystemProperties.getBoolean("fw.sleep_in_power_menu", false)) {
            mItems.add(
                new SinglePressAction(
                        com.android.internal.R.drawable.ic_lock_sleep,
                        R.string.global_action_sleep) {

                    public void onPress() {
                        // shutdown by making sure radio and power are handled accordingly.
                        mPowerManager.goToSleep(SystemClock.uptimeMillis());
                    }

                    public boolean showDuringKeyguard() {
                        return true;
                    }

                    public boolean showBeforeProvisioning() {
                        return true;
                    }
                });
        }

        // next: airplane mode
        mItems.add(mAirplaneModeOn);

        // next: bug report, if enabled
        if (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0 && isCurrentUserOwner()) {
            mItems.add(
                new SinglePressAction(com.android.internal.R.drawable.stat_sys_adb,
                        R.string.global_action_bug_report) {

                    public void onPress() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                        builder.setTitle(com.android.internal.R.string.bugreport_title);
                        builder.setMessage(com.android.internal.R.string.bugreport_message);
                        builder.setNegativeButton(com.android.internal.R.string.cancel, null);
                        builder.setPositiveButton(com.android.internal.R.string.report,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        // Add a little delay before executing, to give the
                                        // dialog a chance to go away before it takes a
                                        // screenshot.
                                        mHandler.postDelayed(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    ActivityManagerNative.getDefault()
                                                            .requestBugReport();
                                                } catch (RemoteException e) {
                                                }
                                            }
                                        }, 500);
                                    }
                                });
                        AlertDialog dialog = builder.create();
                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                        dialog.show();
                    }

                    public boolean onLongPress() {
                        return false;
                    }

                    public boolean showDuringKeyguard() {
                        return true;
                    }

                    public boolean showBeforeProvisioning() {
                        return false;
                    }
                });
        }

        // last: silent mode
        if (mShowSilentToggle) {
            mItems.add(mSilentModeAction);
        }

        if (mIsOwner) {
            if (Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) {
                mItems.add(mMiracastModeOn);
                mMiracastIndex = mItems.size() - 1;
            }
        }

        // one more thing: optionally add a list of users to switch to
        if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
            addUsersToMenu(mItems);
        }

        mAdapter = new MyAdapter();

        AlertParams params = new AlertParams(mContext);
        params.mAdapter = mAdapter;
        params.mOnClickListener = this;
        params.mForceInverseBackground = true;

        GlobalActionsDialog dialog = new GlobalActionsDialog(mContext, params);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.

        dialog.getListView().setItemsCanFocus(true);
        dialog.getListView().setLongClickable(true);
        dialog.getListView().setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        return mAdapter.getItem(position).onLongPress();
                    }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        dialog.setOnDismissListener(this);

        return dialog;
    }

    private UserInfo getCurrentUser() {
        try {
            return ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean isCurrentUserOwner() {
        UserInfo currentUser = getCurrentUser();
        return currentUser == null || currentUser.isPrimary();
    }

    private void addUsersToMenu(ArrayList<Action> items) {
        List<UserInfo> users = ((UserManager) mContext.getSystemService(Context.USER_SERVICE))
                .getUsers();
        if (users.size() > 1) {
            UserInfo currentUser = getCurrentUser();
            for (final UserInfo user : users) {
                boolean isCurrentUser = currentUser == null
                        ? user.id == 0 : (currentUser.id == user.id);
                Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                        : null;
                SinglePressAction switchToUser = new SinglePressAction(
                        com.android.internal.R.drawable.ic_menu_cc, icon,
                        (user.name != null ? user.name : "Primary")
                        + (isCurrentUser ? " \u2714" : "")) {
                    public void onPress() {
                        try {
                            ActivityManagerNative.getDefault().switchUser(user.id);
                        } catch (RemoteException re) {
                            Log.e(TAG, "Couldn't switch user " + re);
                        }
                    }

                    public boolean showDuringKeyguard() {
                        return true;
                    }

                    public boolean showBeforeProvisioning() {
                        return false;
                    }
                };
                items.add(switchToUser);
            }
        }
    }

    private void prepareDialog() {
        refreshSilentMode();
        mAirplaneModeOn.updateState(mAirplaneState);
        if (mIsOwner) {
            if (Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) {
                mMiracastModeOn.updateState(mMiracastState);
            }
        }
        mAdapter.notifyDataSetChanged();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        if (mShowSilentToggle) {
            IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mRingerModeReceiver, filter);
        }
    }

    private void refreshSilentMode() {
        if (!mHasVibrator) {
            final boolean silentModeOn =
                    mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
            ((ToggleAction)mSilentModeAction).updateState(
                    silentModeOn ? ToggleAction.State.On : ToggleAction.State.Off);
        }
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        if (mShowSilentToggle) {
            try {
                mContext.unregisterReceiver(mRingerModeReceiver);
            } catch (IllegalArgumentException ie) {
                // ignore this
                Log.w(TAG, ie);
            }
        }
        if (Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) {
            if (mPC2ThorStreamingStatusReceiver != null) {
                mContext.unregisterReceiver(mPC2ThorStreamingStatusReceiver);
            }

            if (mNvWFDSvc != null && isWfdStarted && isConnectionInitiated == false) {
                mNvWFDSvc.close();
                isWfdStarted = false;
            }
        }
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        if (!(mAdapter.getItem(which) instanceof SilentModeTriStateAction)) {
            dialog.dismiss();
        }
        mAdapter.getItem(which).onPress();
    }

    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link GlobalActions#mKeyguardShowing} and whether the device is provisioned
     * via {@link GlobalActions#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        @Override
        public boolean isEnabled(int position) {
        if (Integer.parseInt(android.os.SystemProperties.get("miracast.powermenu", "0")) == 1) {
            if (position == mMiracastIndex) {
                return true;
            }
        }
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            return action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        public boolean onLongPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         *    is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         *   device is provisioned.
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        private final int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        protected SinglePressAction(int iconResId, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = null;
        }

        public boolean isEnabled() {
            return true;
        }

        abstract public void onPress();

        public boolean onLongPress() {
            return false;
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            v.findViewById(R.id.status).setVisibility(View.GONE);
            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setScaleType(ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getResources().getDrawable(mIconResId));
            }
            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
    }

    private static abstract class MiracastSinglePressAction implements Action {
        private String mSink = null;
        private String mSsid = null;
        private int mIconResId = 0;

        protected MiracastSinglePressAction(String sink, String ssid) {
            mSink = sink;
            mSsid = ssid;
        }

        protected MiracastSinglePressAction(int iconResId) {
            mIconResId = iconResId;
        }

        public boolean isEnabled() {
            return true;
        }

        abstract public void onPress();

        public boolean onLongPress() {
            return false;
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.miracast_remembered_sinks, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            if(mIconResId != 0 && icon != null) {
                icon.setImageDrawable(context.getResources().getDrawable(mIconResId));
            }

            if (messageView != null) {
                messageView.setText(mSink);
                messageView.setEnabled(enabled);
            }

            if (statusView != null) {
                statusView.setText(mSsid);
                statusView.setVisibility(View.VISIBLE);
                statusView.setEnabled(enabled);
            }
            v.setEnabled(enabled);

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    private static abstract class ToggleAction implements Action {

        enum State {
            Off(false),
            TurningOn(true),
            TurningOff(true),
            On(false);

            private final boolean inTransition;

            State(boolean intermediate) {
                inTransition = intermediate;
            }

            public boolean inTransition() {
                return inTransition;
            }
        }

        protected State mState = State.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId The icon for when this action is on.
         * @param disabledIconResid The icon for when this action is off.
         * @param essage The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        public void setResources(int enabledIconResId,
            int disabledIconResid,
            int message,
            int enabledStatusMessageResId,
            int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the
         * View.
         */
        void willCreate() {

        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(R
                            .layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(mMessageResId);
                messageView.setEnabled(enabled);
            }

            boolean on = ((mState == State.On) || (mState == State.TurningOn));
            if (icon != null) {
                icon.setImageDrawable(context.getResources().getDrawable(
                        (on ? mEnabledIconResId : mDisabledIconResid)));
                icon.setEnabled(enabled);
            }

            if (statusView != null) {
                statusView.setText(on ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
                if (mMessage != null) statusView.setText(mMessage);
                statusView.setVisibility(View.VISIBLE);
                statusView.setEnabled(enabled);
            }
            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == State.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean onLongPress() {
            return false;
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate
         * states until some notification is received (e.g airplane mode is 'turning off' until
         * we know the wireless connections are back online
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? State.On : State.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(State state) {
            mState = state;
        }

        protected String mMessage = null;
        public final void updateMessage(String msg) {
            mMessage = msg;
        }
    }

    private class SilentModeToggleAction extends ToggleAction {
        public SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode,
                    R.string.global_action_silent_mode_on_status,
                    R.string.global_action_silent_mode_off_status);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private static class SilentModeTriStateAction implements Action, View.OnClickListener {

        private final int[] ITEM_IDS = { R.id.option1, R.id.option2, R.id.option3 };

        private final AudioManager mAudioManager;
        private final Handler mHandler;
        private final Context mContext;

        SilentModeTriStateAction(Context context, AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
            mContext = context;
        }

        private int ringerModeToIndex(int ringerMode) {
            // They just happen to coincide
            return ringerMode;
        }

        private int indexToRingerMode(int index) {
            // They just happen to coincide
            return index;
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int selectedIndex = ringerModeToIndex(mAudioManager.getRingerMode());
            for (int i = 0; i < 3; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean onLongPress() {
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            mAudioManager.setRingerMode(indexToRingerMode(index));
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            } else if (TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra("PHONE_IN_ECM_STATE", false)) &&
                        mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (!mHasTelephony) return;
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleAction.State.On : ToggleAction.State.Off;
            mAirplaneModeOn.updateState(mAirplaneState);
            mAdapter.notifyDataSetChanged();
        }
    };

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            }
        }
    };

    private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_SHOW = 2;
    private static final int MESSAGE_MIRACAST_REMSINKSHOW = 257;
    private static final int MESSAGE_MIRACAST_CONNPROGDLGSHOW = 258;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_DISMISS:
                if (mDialog != null) {
                    mDialog.dismiss();
                }
                break;
            case MESSAGE_REFRESH:
                refreshSilentMode();
                mAdapter.notifyDataSetChanged();
                break;
            case MESSAGE_SHOW:
                handleShow();
                break;
            case MESSAGE_MIRACAST_REMSINKSHOW:
                showRememberedSinks();
                break;
            case MESSAGE_MIRACAST_CONNPROGDLGSHOW:
                launchConnectionProgressDlg((String)msg.obj);
                break;
            }
        }
    };

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony) return;

        boolean airplaneModeOn = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephony) {
            mAirplaneState = on ? ToggleAction.State.On : ToggleAction.State.Off;
        }
    }

    private static final class GlobalActionsDialog extends Dialog implements DialogInterface {
        private final Context mContext;
        private final int mWindowTouchSlop;
        private final AlertController mAlert;

        private EnableAccessibilityController mEnableAccessibilityController;

        private boolean mIntercepted;
        private boolean mCancelOnUp;

        public GlobalActionsDialog(Context context, AlertParams params) {
            super(context, getDialogTheme(context));
            mContext = context;
            mAlert = new AlertController(mContext, this, getWindow());
            mWindowTouchSlop = ViewConfiguration.get(context).getScaledWindowTouchSlop();
            params.apply(mAlert);
        }

        private static int getDialogTheme(Context context) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(com.android.internal.R.attr.alertDialogTheme,
                    outValue, true);
            return outValue.resourceId;
        }

        @Override
        protected void onStart() {
            // If global accessibility gesture can be performed, we will take care
            // of dismissing the dialog on touch outside. This is because the dialog
            // is dismissed on the first down while the global gesture is a long press
            // with two fingers anywhere on the screen.
            if (EnableAccessibilityController.canEnableAccessibilityViaGesture(mContext)) {
                mEnableAccessibilityController = new EnableAccessibilityController(mContext);
                super.setCanceledOnTouchOutside(false);
            } else {
                mEnableAccessibilityController = null;
                super.setCanceledOnTouchOutside(true);
            }
            super.onStart();
        }

        @Override
        protected void onStop() {
            if (mEnableAccessibilityController != null) {
                mEnableAccessibilityController.onDestroy();
            }
            super.onStop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (mEnableAccessibilityController != null) {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    View decor = getWindow().getDecorView();
                    final int eventX = (int) event.getX();
                    final int eventY = (int) event.getY();
                    if (eventX < -mWindowTouchSlop
                            || eventY < -mWindowTouchSlop
                            || eventX >= decor.getWidth() + mWindowTouchSlop
                            || eventY >= decor.getHeight() + mWindowTouchSlop) {
                        mCancelOnUp = true;
                    }
                }
                try {
                    if (!mIntercepted) {
                        mIntercepted = mEnableAccessibilityController.onInterceptTouchEvent(event);
                        if (mIntercepted) {
                            final long now = SystemClock.uptimeMillis();
                            event = MotionEvent.obtain(now, now,
                                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                            mCancelOnUp = true;
                        }
                    } else {
                        return mEnableAccessibilityController.onTouchEvent(event);
                    }
                } finally {
                    if (action == MotionEvent.ACTION_UP) {
                        if (mCancelOnUp) {
                            cancel();
                        }
                        mCancelOnUp = false;
                        mIntercepted = false;
                    }
                }
            }
            return super.dispatchTouchEvent(event);
        }

        public ListView getListView() {
            return mAlert.getListView();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mAlert.installContent();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (mAlert.onKeyDown(keyCode, event)) {
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (mAlert.onKeyUp(keyCode, event)) {
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
    }

    private NvWFDManager mNvWFDSvc = null;
    private List<String> mMiracastRememberedSinkList = null;
    private boolean isWfdStarted = false;
    private boolean isConnectionInitiated = false;
    private boolean isConnectionInitiatedSinkFound = false;
    private NvwfdPwrListener mNvwfdPwrListener;
    private ToggleAction mMiracastModeOn;
    private ToggleAction.State mMiracastState = ToggleAction.State.Off;

    private void onMiracastModeChanged() {
        boolean isWifiEnabled = ((WifiManager)mContext.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
        boolean airplaneModeOn = false;
        /*boolean airplaneModeOn = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;*/
        if (isWifiEnabled && !mPC2THORConnected && !airplaneModeOn && mNvWFDSvc != null && isWfdStarted) {
            boolean miracastModeOn = mNvWFDSvc.isConnected();
            if (miracastModeOn) {
                if(mNvWFDSvc.isConnectionOngoing()) {
                    String message = mContext.getResources().getString(R.string.global_actions_miracast_mode_cancel_connection_status);
                    mMiracastModeOn.updateMessage(message);
                }else {
                    mMiracastModeOn.updateMessage(null);
                }
            } else {
                mMiracastRememberedSinkList = mNvWFDSvc.getRememberedSinkList();
                if (mMiracastRememberedSinkList != null && mMiracastRememberedSinkList.size() == 1) {
                    // Only one remembered sink
                    String message = mContext.getResources().getString(R.string.global_actions_miracast_mode_off_status_single_paired);
                    mMiracastModeOn.updateMessage(message + mMiracastRememberedSinkList.get(0));
                } else {
                    // more than one remembered sink
                    mMiracastModeOn.updateMessage(null);
                }
            }
            mMiracastState = miracastModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
            mMiracastModeOn.updateState(mMiracastState);
        } else {
            mMiracastState = ToggleAction.State.Off;
            isWfdStarted = false;
            isConnectionInitiated = false;
            isConnectionInitiatedSinkFound = false;
            mMiracastModeOn.updateState(mMiracastState);
        }
    }

    private void initializeMiracastModeRes() {
        boolean airplaneModeOn = false;
        /*boolean airplaneModeOn = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;*/
        boolean isWifiEnabled = ((WifiManager)mContext.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
        // Check PC2THOR streaming status
        IntentFilter filter = new IntentFilter();
        if (filter != null) {
            filter.addAction("com.nvidia.grid.PC2ThorStreamingStatus.CONNECTED");
            setPC2ThorStreamingStatus(mContext.registerReceiver(mPC2ThorStreamingStatusReceiver, filter));
        }

        //Dismiss ongoing progress dialog
        dismissConnProgressDlg(false);

        if (mIsOwner) {
            int enabledIconResId = R.drawable.ic_miracast_unavailable;
            int disabledIconResId = R.drawable.ic_miracast_unavailable;
            int message = R.string.global_actions_toggle_miracast_mode;
            int enabledStatusMessageResId = 0;
            int disabledStatusMessageResId = 0;
            if (airplaneModeOn) {
                enabledStatusMessageResId = R.string.global_actions_miracast_mode_airplane_on_status;
                disabledStatusMessageResId = R.string.global_actions_miracast_mode_airplane_on_status;
            } else if (!isWifiEnabled) {
                enabledStatusMessageResId = R.string.global_actions_miracast_mode_wifi_off_status;
                disabledStatusMessageResId = R.string.global_actions_miracast_mode_wifi_off_status;
            } else if (mPC2THORConnected) {
                enabledStatusMessageResId = R.string.global_actions_miracast_mode_pc2thor_on_status;
                disabledStatusMessageResId = R.string.global_actions_miracast_mode_pc2thor_on_status;
            } else {
                // we are ready to connect
                enabledStatusMessageResId = R.string.global_actions_miracast_mode_on_status;
                disabledStatusMessageResId = R.string.global_actions_miracast_mode_off_status_no_paired;
                if (mNvWFDSvc != null && !isWfdStarted) {
                    mNvWFDSvc.start();
                    mNvWFDSvc.setListener(mNvwfdPwrListener);
                    isWfdStarted = true;
                }
                if (mNvWFDSvc.isConnected()) {
                    enabledIconResId = R.drawable.ic_miracast_connected;
                    disabledIconResId = R.drawable.ic_miracast_connected;
                } else {
                    enabledIconResId = R.drawable.ic_miracast_ready_to_connect;
                    disabledIconResId = R.drawable.ic_miracast_ready_to_connect;
                }

                int remSinkSize = 0;
                mMiracastRememberedSinkList = mNvWFDSvc.getRememberedSinkList();
                if (mMiracastRememberedSinkList != null) {
                    remSinkSize = mMiracastRememberedSinkList.size();
                    if (remSinkSize == 1) {
                        disabledStatusMessageResId = R.string.global_actions_miracast_mode_off_status_single_paired;
                    } else if (remSinkSize > 1) {
                        disabledStatusMessageResId = R.string.global_actions_miracast_mode_off_status_many_paired;
                    } else {
                        disabledStatusMessageResId = R.string.global_actions_miracast_mode_off_status_no_paired;
                    }
                }
            }

            mMiracastEnabIcon = enabledIconResId;
            mMiracastDisIcon = disabledIconResId;
            mGlobalActionMiracastMsg = message;
            mMiracastEnbStatus = enabledStatusMessageResId;
            mMiracastDisStatus = disabledStatusMessageResId;
        }
    }

    private void showRememberedSinks() {
        isConnectionInitiated = false;
        isConnectionInitiatedSinkFound = false;
        int maxSinksToShow = MAX_REMEMBERED_SINKS;

        mItems = new ArrayList<Action>();
        if (mItems != null) {
            for (final String sink : mMiracastRememberedSinkList) {
                String ssid = mNvWFDSvc.getSinkSSID(sink);
                mItems.add(
                    new MiracastSinglePressAction(sink, ssid) {
                          public void onPress() {
                              connectToMiracastSink(sink.toString());
                          }
                          public boolean showDuringKeyguard() {
                              return true;
                          }
                          public boolean showBeforeProvisioning() {
                              return true;
                          }

                });

                if (--maxSinksToShow == 0)
                    break;
            }

            mItems.add(
                new MiracastSinglePressAction(com.android.internal.R.drawable.ic_sysbar_quicksettings) {
                    public void onPress() {
                        launchMiracastSettings();
                    }
                    public boolean showDuringKeyguard() {
                        return true;
                    }
                    public boolean showBeforeProvisioning() {
                        return true;
                    }

            });
        }

        mAdapter = new MyAdapter();

        final AlertDialog.Builder ab = new AlertDialog.Builder(mContext);

        ab.setAdapter(mAdapter, this)
                .setInverseBackgroundForced(true);

        final AlertDialog dialog = ab.create();
        dialog.getListView().setItemsCanFocus(true);
        dialog.getListView().setLongClickable(false);
        dialog.getListView().setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        return mAdapter.getItem(position).onLongPress();
                    }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        mAdapter.notifyDataSetChanged();
        dialog.show();

        //reset list
        mMiracastRememberedSinkList = null;

    }

    private void launchConnectionProgressDlg(String sink) {
        String message = String.format(mContext.getResources().
                        getString(R.string.global_actions_miracast_sink_search_status, sink));

        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle(sink);
        builder.setMessage(message);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mConnProgressDlg = dialog;
        dialog.show();
    }

    private void dismissConnProgressDlg(boolean updateError) {
        if (mConnProgressDlg != null) {
            if (updateError) {
                if (!isConnectionInitiatedSinkFound) {
                    mConnProgressDlg.setMessage(mContext.getResources().getString(R.string.global_actions_miracast_sink_not_found_status));
                } else {
                    mConnProgressDlg.setMessage(mContext.getResources().getString(R.string.global_actions_miracast_connection_failed_status));
                }
                mHandler.postDelayed(new Runnable() {
                        public void run() {
                            mConnProgressDlg.dismiss();
                            mConnProgressDlg = null;
                        }
                    }, 3000); // show connection failed message for 3 sec
            } else {
                mConnProgressDlg.dismiss();
                mConnProgressDlg = null;
            }
        }
        isConnectionInitiatedSinkFound = false;
    }

    private void launchMiracastSettings() {
        Intent settingIntent = new Intent("com.nvidia.settings.MIRACAST_SETTINGS");
        if (settingIntent != null) {
            settingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(settingIntent, UserHandle.CURRENT);
        }
    }

    private void launchWiFiSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private class NvwfdPwrListener implements NvWFDManager.NvwfdListener {
        public void onSetupComplete(int success) {
            if (DBG) Log.d(TAG, "onSetupComplete success : " + success);
            boolean isWifiEnabled = ((WifiManager)mContext.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
            boolean airplaneModeOn = false;
            /*boolean airplaneModeOn = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;*/

            if (isWifiEnabled && !mPC2THORConnected && !airplaneModeOn ) {
                // start discovery only if we can connect to one of the remembered sink
                if (mNvWFDSvc != null && isWfdStarted && !mNvWFDSvc.isConnected() && mNvWFDSvc.getRememberedSinkList().size() > 0) {
                    mNvWFDSvc.discoverSinks();
                }
            }
        }

        public void onSinkFound(List<String> sinkList) {
            if (mNvWFDSvc != null && isConnectionInitiated) {
                for (String sink : sinkList) {
                    String nickName = mNvWFDSvc.getSinkNickname(sink);
                    if (nickName == null) {
                        nickName = sink;
                    }
                    if (nickName.equalsIgnoreCase(mConnectingSink) && mNvWFDSvc.getSinkAvailabilityStatus(sink)) {
                        isConnectionInitiatedSinkFound = true;
                        // sink discovered, update the status on progress dialog
                        if (mConnProgressDlg != null) {
                            String message = String.format(mContext.getResources().
                                          getString(R.string.global_actions_miracast_connection_progress_status, mConnectingSink));
                            mConnProgressDlg.setMessage(message);
                        }
                    }
                }
            }
        }

        public void onConnectionDone(int value) {
            isConnectionInitiated = false;
            initializeMiracastModeRes();
            mMiracastModeOn.setResources(mMiracastEnabIcon, mMiracastDisIcon,
                      mGlobalActionMiracastMsg, mMiracastEnbStatus, mMiracastDisStatus);
            onMiracastModeChanged();
            mAdapter.notifyDataSetChanged();
            if (mNvWFDSvc != null && isWfdStarted) {
                mNvWFDSvc.close();
                isWfdStarted = false;
            }
            dismissConnProgressDlg((value == 0) ? true : false);
        }

        public void onConnectionDisconnect() {
            if (mNvWFDSvc != null && isWfdStarted) {
                mNvWFDSvc.close();
                isWfdStarted = false;
            }
        }

        public void onError(String error) {
            dismissConnProgressDlg(true);
        }

        public void onCancelConnectionDone(int success) {
            isConnectionInitiated = false;
            isConnectionInitiatedSinkFound = false;
            if (mNvWFDSvc != null && isWfdStarted) {
                mNvWFDSvc.close();
                isWfdStarted = false;
            }
        }
    }

    private void connectToMiracastSink(String sink) {
        if (DBG) Log.d(TAG, "Connecting to " + sink);
        if (mNvWFDSvc != null && isWfdStarted) {
            isConnectionInitiated = true;
            isConnectionInitiatedSinkFound = false;
            mConnectingSink = sink;
            mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_MIRACAST_CONNPROGDLGSHOW, (Object)sink));
            mNvWFDSvc.connect(sink, null);
        }
    }
}
