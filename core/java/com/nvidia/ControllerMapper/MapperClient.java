/*
 * Copyright (c) 2013, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia.ControllerMapper;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.AndroidRuntimeException;
import android.util.FloatMath;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.*;
import android.view.GestureDetector.OnGestureListener;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Class for map KeyEvent to touch event
 *
 * @hide
 */
public class MapperClient {
    private static final String TAG = "MapperClient";

    private static final String CONTROLLER_MAPPER_ENABLE = "controller_mapper_enable";

    public static final String[] BLACKLIST = { "com.nvidia.grid",
            "com.nvidia.roth.dashboard",
            "com.nvidia.gridapp",
            "com.nvidia.tegrazone3" };

    private boolean mIsInBlacklist = false;

    private ViewRootImpl mViewRoot;
    private Context mContext;
    private String mAppName;
    private int mAppId;
    private boolean enabled;
    private Filters mFilters;
    private InputEventComposer mInputEventComposer = new InputEventComposer();

    private boolean mDebug;

    private ServiceClient mServiceClient = new ServiceClient();
    private HotKeyState mHotKeyState = new HotKeyState();

    private int mCursorType = Mappings.Cursor.CURSOR_TYPE_NOCURSOR;

    /* [0] for width, [1] for height */
    private int[] mViewSize = new int[2];

    public static boolean isBuildEnabled(Context ctx) {
        return ctx.getResources().getBoolean(
                            com.android.internal.R.bool.config_controllerMapper);
    }

    public MapperClient(Context context, ViewRootImpl root) {
        mContext = context;
        mViewRoot = root;
        mAppName = context.getApplicationInfo().packageName;
        checkBlackList();
        if (mIsInBlacklist)
            return;
        mServiceClient.connect();
        getViewSize();
        mServiceClient.setContext(mContext);
    }

    private void getViewSize() {
        int w, h;
        View view = mViewRoot.getView().findViewById(Window.ID_ANDROID_CONTENT);
        if (view == null)
            view = mViewRoot.getView();
        w = view.getWidth();
        h = view.getHeight();
        if (mViewRoot.getAppScale() > 0.0001f) {
            w = (int)((float)w * mViewRoot.getAppScale());
            h = (int)((float)h * mViewRoot.getAppScale());
        }

        Rect displayFrame = new Rect();
        mViewRoot.getView().getWindowVisibleDisplayFrame(displayFrame);

        // content view don't include window decor such as action bar, so we use if it's too small
        // because displayFrame reports wrong for some fullscreen games, we cannot use it directly
        if (w < displayFrame.width())
            w = displayFrame.width();
        if (h < displayFrame.height())
            h = displayFrame.height();

        // use displayFrame which don't include statusbar
        if (mAppName.equals("com.android.launcher")) {
            w = displayFrame.width();
            h = displayFrame.height();
        }

        mViewSize[0] = w;
        mViewSize[1] = h;
    }

    private void checkBlackList() {
        for (String app : BLACKLIST) {
            if (app.equals(mAppName)) {
                mIsInBlacklist = true;
                break;
            }
        }
    }

    public boolean isEnabled() {
        ContentResolver contentResolver = mContext.getContentResolver();
        try {
            return Settings.System.getInt(contentResolver, CONTROLLER_MAPPER_ENABLE) > 0;
        } catch (SettingNotFoundException exception) {
            // Setting was not found, default value is true
            return true;
        }
    }

    public boolean filter(InputEvent event) {
        if (mIsInBlacklist)
            return false;

        if (event instanceof KeyEvent) {
            if (mHotKeyState.onKeyEvent((KeyEvent) event)) {
                return true;
            }
        }
        if (mFilters == null)
            return false;
        if (!mFilters.isEnabled() && !mFilters.isTempCursorExist())
            return false;

        return mFilters.handleInputEvent(event);
    }

    public void die() {
        if (mIsInBlacklist)
            return;
        mServiceClient.disconnect();
    }

    private class InputEventComposer {

        public int injectSingleEvent(Filter src, SingleEvent singleEvent) {
            boolean isNew = false;

            if (singleEvent.id < 0
                    || singleEvent.action == MotionEvent.ACTION_DOWN) {
                singleEvent.id = allocId();
                isNew = true;
            }

            Finger finger;
            if (isNew)
                finger = new Finger();
            else
                finger = getFingerById(singleEvent.id);
            finger.from = src;
            finger.id = singleEvent.id;
            finger.type = TYPE_SINGLE_EVENT;
            finger.x = singleEvent.x;
            finger.y = singleEvent.y;
            if (isNew)
                mFingers.add(finger);

            int pointerCount = mFingers.size();

            MotionEvent.PointerProperties[] pp = MotionEvent.PointerProperties
                    .createArray(pointerCount);
            MotionEvent.PointerCoords[] pc = MotionEvent.PointerCoords
                    .createArray(pointerCount);
            for (int i = 0; i < mFingers.size(); i++) {
                pp[i].id = mFingers.get(i).id;
                pc[i].x = mFingers.get(i).x;
                pc[i].y = mFingers.get(i).y;
                pc[i].pressure = 1.0f;
                pc[i].size = 1.0f;
            }
            if (pointerCount > 1) {
                if (singleEvent.action == MotionEvent.ACTION_DOWN)
                    singleEvent.action = MotionEvent.ACTION_POINTER_DOWN
                            | (mFingers.indexOf(finger)) << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                else if (singleEvent.action == MotionEvent.ACTION_UP)
                    singleEvent.action = MotionEvent.ACTION_POINTER_UP
                            | (mFingers.indexOf(finger)) << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            }

            if (singleEvent.action == MotionEvent.ACTION_UP
                    || (singleEvent.action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
                mFingers.remove(finger);
            }

            MotionEvent ev = MotionEvent.obtain(singleEvent.downTime,
                    singleEvent.eventTime, singleEvent.action, pointerCount,
                    pp, pc, 0, 0, 1.0f, 1.0f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0);
            mViewRoot.injectEnqueueInputEvent(ev);

            return singleEvent.id;
        }

        public RecordDataState startRecordData(Filter src,
                Mappings.EventRecord.MotionData firstMotionData) {
            RecordDataState state = new RecordDataState();
            state.filter = src;
            state.timeOffset = SystemClock.uptimeMillis()
                    - firstMotionData.pc[firstMotionData.history_size - 1].time;
            return state;
        }

        private MotionEvent.PointerCoords[] getPCFromData(
                Mappings.EventRecord.MotionData data, int pos,
                RecordDataState state, MotionEvent.PointerProperties[] pp) {
            MotionEvent.PointerCoords[] pc = MotionEvent.PointerCoords
                    .createArray(pp.length);
            Mappings.EventRecord.MotionData.PointerCoordinates pcData = data.pc[pos];
            for (int p = 0; p < data.pointer_cnt; p++) {
                int id = state.idMap.indexOfKey(pcData.fingers[p].id);
                if (id < 0) {
                    id = allocId();
                    state.idMap.put(pcData.fingers[p].id, id);
                } else {
                    id = state.idMap.get(pcData.fingers[p].id);
                }
                pp[p].id = id;
                pp[p].toolType = MotionEvent.TOOL_TYPE_FINGER;
                pc[p].x = pcData.fingers[p].x;
                pc[p].y = pcData.fingers[p].y;
                pc[p].pressure = pcData.fingers[p].pressure;
                pc[p].size = pcData.fingers[p].size;
            }
            int index = data.pointer_cnt;
            for (Finger finger : mFingers) {
                pp[index].id = finger.id;
                pp[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
                pc[index].x = finger.x;
                pc[index].y = finger.y;
                pc[index].pressure = 1.0f;
                pc[index].size = 1.0f;
                index++;
            }
            return pc;
        }

        // maintain idmap, but can be cleared
        public SparseIntArray injectRecordData(RecordDataState state, Mappings.EventRecord.MotionData motionData) {

            // remove Finger record, we'll create new one
            removeFingerByFilter(state.filter);

            int pointerCount = mFingers.size() + motionData.pointer_cnt;
            int action = motionData.action;
            if (pointerCount > 1) {
                if (action == MotionEvent.ACTION_DOWN)
                    action = MotionEvent.ACTION_POINTER_DOWN; // index is zero
                else if (action == MotionEvent.ACTION_UP)
                    action = MotionEvent.ACTION_POINTER_UP; // index is zero
            }

           MotionEvent.PointerProperties[] pp = MotionEvent.PointerProperties
                    .createArray(pointerCount);
            MotionEvent.PointerCoords[] pc = getPCFromData(motionData, 0,
                    state, pp);
            MotionEvent motionEvent = MotionEvent.obtain(motionData.downtime
                    + state.timeOffset, motionData.pc[0].time
                    + state.timeOffset, action, pointerCount, pp,
                    pc, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN,
                    0);
            for (int h = 1; h < motionData.history_size; h++) {
                long time = motionData.pc[h].time + state.timeOffset;
                pc = getPCFromData(motionData, h, state, pp);
                motionEvent.addBatch(time, pc, 0);
            }

            mViewRoot.injectEnqueueInputEvent(motionEvent);

            // some fingers are up
            if (motionData.pointer_cnt != state.idMap.size()) {
                for (int i = 0; i < state.idMap.size(); i++) {
                    int key = state.idMap.keyAt(i);
                    int value = state.idMap.valueAt(i);
                    boolean got = false;
                    for (Mappings.EventRecord.MotionData.Finger fg : motionData.pc[0].fingers) {
                        if (fg.id == key) {
                            got = true;
                            break;
                        }
                    }
                    // no one use the value id.
                    if (!got) {
                        removeFingerById(value);
                    }
                }
            }
            //add fingers in motionData to mFingers
            int skipIndex = -1;
            Mappings.EventRecord.MotionData.Finger[] fingersData = motionData.pc[motionData.history_size - 1].fingers;
            if (motionData.action == MotionEvent.ACTION_UP) {
                state.idMap.clear();
                return state.idMap;
            }
            if ((motionData.action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
                skipIndex = (motionData.action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            }
            for (int i = 0; i < fingersData.length; i++) {
                if (i == skipIndex)
                    continue;
                Finger finger = new Finger();
                finger.from = state.filter;
                finger.id = state.idMap.get(fingersData[i].id);
                mFingers.add(finger);
            }
            return state.idMap;
        }

        private static final int TYPE_SINGLE_EVENT = 1;
        private static final int TYPE_RECORD_DATA = 2;

        private ArrayList<Finger> mFingers = new ArrayList<Finger>();

        private Finger getFingerById(int id) {
            for (Finger finger : mFingers) {
                if (finger.id == id)
                    return finger;
            }
            return null;
        }

        private void removeFingerById(int id) {
            Finger finger = getFingerById(id);
            mFingers.remove(finger);
        }

        private void removeFingerByFilter(Filter filter) {
            for (Iterator<Finger> it = mFingers.iterator(); it.hasNext();) {
                Finger finger = it.next();
                if (finger.from == filter)
                    it.remove();
            }
        }

        private static final int ID_SIM_START = 0;

        private int allocId() {
            for (int i = ID_SIM_START;; i++) {
                boolean taken = false;
                for (Finger finger : mFingers) {
                    if (finger.id == i) {
                        taken = true;
                        break;
                    }
                }
                if (!taken)
                    return i;
            }
        }

        class Finger {
            public int id;
            public float x, y;
            public int type;
            public Filter from;
        }

        class SingleEvent {
            public int action;
            public long eventTime;
            public long downTime;
            public float x, y;
            public int id;
        }

        class RecordDataState {
            Filter filter;
            public SparseIntArray idMap = new SparseIntArray();
            public long timeOffset;
        }

    }

    class ServiceClient {
        private Messenger mMessenger;
        private Messenger mMsgRead;
        private int[] mCursorPos = new int[2];
        private Object mCursorLock = new Object();

        private ServiceConnection mConnection = new ServiceConnection() {
            private Handler mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case Mappings.MSG_SEND_MAPPINGS:
                        Mappings mappings;
                        mappings = (Mappings) msg.getData().getSerializable(
                                Mappings.MSG_KEY_MAPPNIGS);
                        mDebug = mappings.getDebug();
                        mCursorType  = msg.getData().getInt(Mappings.MSG_KEY_CURSOR_TYPE);
                        mFilters = new Filters(mappings);
                        mFilters.setCurrActivePage(mappings.getCurrPage());
                        break;
                    case Mappings.MSG_SEND_CURSOR_POS:
                        mCursorPos[0] = msg.arg1;
                        mCursorPos[1] = msg.arg2;
                        break;
                    case Mappings.MSG_RELOAD_MAPPINGS:
                        getMappings();
                        break;
                    default:
                        Log.e(TAG, "Unknown message %d " + msg.what
                                + " received");
                    }
                }
            };

            @Override
            public void onServiceConnected(ComponentName arg0, IBinder binder) {
                synchronized(ServiceClient.this) {
                    mMessenger = new Messenger(binder);
                    mMsgRead = new Messenger(mHandler);
                }
                getMappings();
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                synchronized(ServiceClient.this) {
                    mMessenger = null;
                    mMsgRead = null;
                }
            }

        };

        private synchronized void sendMessageWithAppName(int msgId) {
            if (mMessenger == null)
                return;

            Bundle bundle = new Bundle();
            Message msg = Message.obtain(null, msgId);
            msg.replyTo = mMsgRead;
            bundle.putString(Mappings.MSG_KEY_APP_NAME, mAppName);
            bundle.putIntArray(Mappings.MSG_KEY_VIEW_SIZE, mViewSize);
            if (mFilters == null) {
                bundle.putInt(Mappings.MSG_KEY_CURR_PAGE, 0);
            } else {
                bundle.putInt(Mappings.MSG_KEY_CURR_PAGE,
                        mFilters.getCurrActivePage());
            }
            msg.setData(bundle);
            try {
                mMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        private synchronized void sendMessage(int msgId, int arg1, int arg2) {
            if (mMessenger == null)
                return;

            Message msg = Message.obtain(null, msgId);
            msg.replyTo = mMsgRead;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            try {
                mMessenger.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        public void saveCursorType(int type) {
            mCursorType = type;
            sendMessage(Mappings.MSG_SET_CURSOR_TYPE, mCursorType, 0);
        }

        public void getMappings() {
            sendMessageWithAppName(Mappings.MSG_GET_MAPPINGS);
        }

        public void showOverlay() {
            sendMessageWithAppName(Mappings.MSG_SHOW_MAPPINGS);
        }

        class CursorHideTimer {
            private final static long CURSOR_HIDE_DELAY_MS = 3000;
            private Timer mTimer;
            void startTimer() {
                if (mTimer != null) {
                    mTimer.cancel();
                }
                mTimer = new Timer();
                mTimer.schedule(new TimerTask () {
                    @Override
                    public void run() {
                        synchronized (mCursorLock) {
                            if (mCursorVisible) {
                                sendMessage(Mappings.MSG_HIDE_CURSOR, 0, 0);
                                mCursorVisible = false;
                            }
                        }
                    }
                }, CURSOR_HIDE_DELAY_MS);
            }
            void stopTimer() {
                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer = null;
                }
            }
        }

        private CursorHideTimer mCursorHideTimer = new CursorHideTimer();

        private boolean mCursorVisible = false;

        public void showCursor() {
            synchronized (mCursorLock) {
                if (!mCursorVisible) {
                    sendMessage(Mappings.MSG_SHOW_CURSOR, 0, 0);
                    mCursorVisible = true;
                    mCursorHideTimer.startTimer();
                }
            }
        }

        public void hideCursor() {
            synchronized (mCursorLock) {
                if (mCursorVisible) {
                    mCursorHideTimer.stopTimer();
                    sendMessage(Mappings.MSG_HIDE_CURSOR, 0, 0);
                    mCursorVisible = false;
                }
            }
        }

        public void moveCursor(int x, int y) {
            synchronized (mCursorLock) {
                if (!mCursorVisible) {
                    sendMessage(Mappings.MSG_SHOW_CURSOR, 0, 0);
                    mCursorVisible = true;
                }
            }
            sendMessage(Mappings.MSG_MOVE_CURSOR, x, y);
            mCursorHideTimer.startTimer();
        }

        public int getCursorX() {
            return mCursorPos[0];
        }

        public int getCursorY() {
            return mCursorPos[1];
        }

        boolean connect() {
            boolean rt = false;
            try {
                rt = mContext.bindService(new Intent(
                        "com.nvidia.ControllerMapper.START_SERVICE"), mConnection,
                        Context.BIND_AUTO_CREATE);
            } catch (SecurityException e) {
                e.printStackTrace();
            }

            return rt;
        }

        synchronized void disconnect() {
            hideCursor();
            stopGenKeyThread();
            mMessenger = null;
            mMsgRead = null;
            mContext.unbindService(mConnection);
        }

        public void setContext(Context context) {
            mDispWidth = context.getResources().getDisplayMetrics().widthPixels;
            mDispHeight = context.getResources().getDisplayMetrics().heightPixels;
        }

        public void updateCursor(MotionEvent ev, int xAxis, int yAxis, int type) {
            if (type == Mappings.Cursor.CURSOR_TYPE_ACCELERATED) {
                startGenKeyThread();
                mStickX = ev.getAxisValue(xAxis);
                mStickY = ev.getAxisValue(yAxis);
            } else {
                stopGenKeyThread();
                float x = ev.getAxisValue(xAxis);
                float y = ev.getAxisValue(yAxis);
                float r = FloatMath.sqrt(x * x + y * y);
                float x1, y1, r1;
                if (Math.abs(x) > Math.abs(y)) {
                    x1 = 1;
                    y1 = y / x;
                } else {
                    x1 = x / y;
                    y1 = 1;
                }
                r1 = FloatMath.sqrt(x1 * x1 + y1 * y1);
                float ratio = r1 / STICK_VALUE_VALID;
                x *= ratio;
                y *= ratio;

                moveCursor((int) (mDispWidth / 2.0f * x), (int) (mDispHeight / 2.0f * y));
            }
        }

        public void startGenKeyThread() {
            if (mGenKeyThread != null) {
                stopGenKeyThread();
            }
            mGenKeyThread = new GenKeyThread();
            mGenKeyThread.setContext(mContext);
            mGenKeyThread.updatePointerSpeed();
            mGenKeyThread.start();
        }

        public void stopGenKeyThread() {
            if (mGenKeyThread != null) {
                mGenKeyThread.markStop();
                mGenKeyThread.interrupt();
                mGenKeyThread = null;
            }
        }

        final private static float STICK_VALUE_ZERO = 0.01f;
        final private static float STICK_VALUE_VALID = 0.8f;

        private float mStickX = 0.0f;
        private float mStickY = 0.0f;

        private int mCursorX;
        private int mCursorY;

        private int mDispWidth;
        private int mDispHeight;

        GenKeyThread mGenKeyThread;

        class GenKeyThread extends Thread {
            private Context mContext;
            private boolean mStopMarked = false;
            private int mPointerSpeed = 0;
            private float mPointerScale = 1.0f;
            private float POINTER_SPEED_EXPONENT = 1.0f / 4;

            public void markStop() {
                mStopMarked = true;
            }

            public void setContext(Context context) {
                mContext = context;
            }

            public void updatePointerSpeed() {
                ContentResolver contentResolver = mContext.getContentResolver();
                try {
                    mPointerSpeed = Settings.System.getInt(contentResolver, Settings.System.POINTER_SPEED);
                } catch (SettingNotFoundException snfe) {
                }

                mPointerScale = (float)Math.pow(2, mPointerSpeed * POINTER_SPEED_EXPONENT);
            }

            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        //interrupted
                        return;
                    }

                    if (mStopMarked) {
                        return;
                    }

                    if (Math.abs(mStickX) < STICK_VALUE_ZERO && Math.abs(mStickY) < STICK_VALUE_ZERO) {
                        //back to zero point, reset all, do nothing
                        return;
                    }
                    mCursorX += mDispWidth / 100.0f * mStickX * mPointerScale;
                    mCursorY += mDispHeight / 100.0f * mStickY * mPointerScale;

                    if (mCursorX < -mDispWidth / 2) {
                        mCursorX = -mDispWidth / 2;
                    }
                    if (mCursorX > mDispWidth / 2) {
                        mCursorX = mDispWidth / 2;
                    }
                    if (mCursorY < -mDispHeight / 2) {
                        mCursorY = -mDispHeight / 2;
                    }
                    if (mCursorY > mDispHeight / 2) {
                        mCursorY = mDispHeight / 2;
                    }

                    moveCursor((int)mCursorX, (int)mCursorY);
                }
            }
        }
    }

    class HotKeyState {
        private int[] mKeys = { KeyEvent.KEYCODE_BUTTON_START };
        private boolean[] mKeyPressed;

        private static final int MSG_LONG_PRESS = 0;
        Handler mHandler = new Handler() {

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                case MSG_LONG_PRESS:
                    for (int i = 0; i < mKeys.length; i ++)
                        mKeyPressed[i] = false;
                    mServiceClient.showOverlay();
                    break;
                }
                return;
            }

        };

        public HotKeyState() {
            mKeyPressed = new boolean[mKeys.length];
        }

        public boolean onKeyEvent(KeyEvent ev) {
            int presses = 0;
            boolean newDown = false;
            for (int i = 0; i < mKeys.length; i++) {
                if (ev.getKeyCode() == mKeys[i]) {
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        newDown = !mKeyPressed[i];
                        mKeyPressed[i] = true;
                    } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                        mKeyPressed[i] = false;
                        mHandler.removeMessages(MSG_LONG_PRESS);
                    }
                }
                if (mKeyPressed[i])
                    presses++;
            }
            if (presses == mKeys.length && newDown) {
                mHandler.sendEmptyMessageDelayed(MSG_LONG_PRESS,
                        ViewConfiguration.getLongPressTimeout());
            }
            return false;
        }
    }

    interface Filter {
        boolean canHandleKey(int keyCode);

        boolean canHandleMotion(MotionEvent ev);

        boolean translate(KeyEvent ev);

        boolean translate(MotionEvent ev);

        boolean isTouched();

        int getId();

        void setId(int id);

        float getLastX();

        float getLastY();

        boolean needChangePage();

        void changePage();
    }

    class Filters {
        public Filters(Mappings mappingData) {
            mServiceClient.hideCursor();
            MapperEventPool mapperEventPool = null;
            if (mContext.getApplicationContext() != null) {
                mapperEventPool = mContext.getApplicationContext().getMapperEventPool();
                if (mapperEventPool != null)
                    mapperEventPool.setMappingEnabled(false);
            }
            mTempCursorCreated = true;
            ArrayList<Mappings.Mapping> mappings = mappingData.getMappings();
            for (Mappings.Mapping mapping : mappings) {
                switch (mapping.getType()) {
                case Mappings.Mapping.TYPE_BUTTON:
                    mFilters.add(new ButtonFilter((Mappings.Button) mapping));
                    break;
                case Mappings.Mapping.TYPE_STICK:
                    mFilters.add(new StickFilter((Mappings.Stick) mapping));
                    break;
                case Mappings.Mapping.TYPE_EVENT_RECORD:
                    mFilters.add(new EventRecordFilter(
                            (Mappings.EventRecord) mapping));
                    break;
                case Mappings.Mapping.TYPE_CURSOR:
                    mFilters.add(new CursorFilter((Mappings.Cursor) mapping));
                    mTempCursorCreated = false;
                    break;
                case Mappings.Mapping.TYPE_SENSOR:
                    mFilters.add(new SensorFilter((Mappings.Sensor) mapping));
                    if (mapperEventPool != null)
                        mapperEventPool.setMappingEnabled(true);
                    break;
                case Mappings.Mapping.TYPE_LOOK_STICK:
                    mFilters.add(new LookStickFilter((Mappings.LookStick) mapping));
                    break;
                default:
                    Log.e(TAG, "unknow mapping type " + mapping.getType());
                }
                if (mTempCursorCreated) {
                    switch (mapping.getKey()) {
                    case Mappings.Mapping.KEYCODE_RIGHT_STICK:
                    case Mappings.Mapping.KEYCODE_RIGHT_STICK_UPPERSIDE:
                    case Mappings.Mapping.KEYCODE_RIGHT_STICK_DOWNSIDE:
                    case Mappings.Mapping.KEYCODE_RIGHT_STICK_LEFTSIDE:
                    case Mappings.Mapping.KEYCODE_RIGHT_STICK_RIGHTSIDE:
                    case Mappings.Mapping.KEYCODE_LEFT_TRIGGER:
                    case Mappings.Mapping.KEYCODE_RIGHT_TRIGGER:
                    case KeyEvent.KEYCODE_BUTTON_THUMBR:
                        mTempCursorCreated = false;
                        break;
                    }
                }
            }
            if (mTempCursorCreated && mDebug) {
                mFilters.add(new TempCursorFilter(mCursorType));
            }
            mEnabled = mappingData.isEnabled();
        }

        public int getCurrActivePage() {
            return mCurrActivePage;
        }

        public void setCurrActivePage(int currPage) {
            mCurrActivePage = currPage;
        }

        public boolean handleInputEvent(InputEvent ev) {
            KeyEvent keyEvent = null;
            MotionEvent motionEvent = null;
            if (ev instanceof KeyEvent)
                keyEvent = (KeyEvent) ev;
            else if (ev instanceof MotionEvent
                    && ((ev.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0))
                motionEvent = (MotionEvent) ev;

            if (keyEvent != null) {
                for (Filter filter : mFilters) {
                    if (filter.canHandleKey(keyEvent.getKeyCode())) {
                        filter.translate(keyEvent);
                        if (filter.needChangePage())
                            filter.changePage();
                        return true;
                    }
                }
            }
            if (motionEvent != null) {
                boolean motionHandled = false;
                for (Filter filter : mFilters) {
                    if (filter.canHandleMotion(motionEvent)) {
                        boolean handled = filter.translate(motionEvent);

                        if (filter.needChangePage())
                            filter.changePage();

                        if (!handled)
                            continue;
                        else
                            motionHandled = true;
                    }
                }
                return motionHandled;
            }
            return false;
        }

        public boolean isEnabled() {
            return mEnabled;
        }

        public boolean isTempCursorExist() {
            return mTempCursorCreated;
        }

        public boolean useMotionEventForKey(int keyCode) {
            switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case Mappings.Mapping.KEYCODE_LEFT_STICK_UPPERSIDE:
            case Mappings.Mapping.KEYCODE_LEFT_STICK_DOWNSIDE:
            case Mappings.Mapping.KEYCODE_LEFT_STICK_LEFTSIDE:
            case Mappings.Mapping.KEYCODE_LEFT_STICK_RIGHTSIDE:
            case Mappings.Mapping.KEYCODE_RIGHT_STICK_UPPERSIDE:
            case Mappings.Mapping.KEYCODE_RIGHT_STICK_DOWNSIDE:
            case Mappings.Mapping.KEYCODE_RIGHT_STICK_LEFTSIDE:
            case Mappings.Mapping.KEYCODE_RIGHT_STICK_RIGHTSIDE:
            case Mappings.Mapping.KEYCODE_LEFT_TRIGGER:
            case Mappings.Mapping.KEYCODE_RIGHT_TRIGGER:
                return true;
            default:
                return false;
            }
        }

        private ArrayList<Filter> mFilters = new ArrayList<Filter>(10);
        private boolean mEnabled;
        private boolean mTempCursorCreated;
        private int mCurrActivePage;

        class ButtonFilter implements Filter {
            private Mappings.Button mData;
            private float mLastX, mLastY;
            private int mId;
            private boolean mTouched;
            private boolean mUseMotionEvent;

            public ButtonFilter(Mappings.Button data) {
                mData = data;
                mLastX = mData.getX();
                mLastY = mData.getY();
                mUseMotionEvent = useMotionEventForKey(mData.getKey());
            }

            @Override
            public boolean needChangePage() {
                return mData.getToPage() != 0 && mTouched == false;
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            @Override
            public boolean canHandleKey(int key) {
                return (!mUseMotionEvent && mData.getKey() == key && mData.getPage() == getCurrActivePage());
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                if (mData.getPage() != getCurrActivePage())
                    return false;
                return mUseMotionEvent;
            }

            @Override
            public boolean translate(KeyEvent event) {
                InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                singleEvent.downTime = event.getDownTime();
                singleEvent.eventTime = SystemClock.uptimeMillis();
                singleEvent.x = mData.getX();
                singleEvent.y = mData.getY();
                singleEvent.id = getId();

                switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (isTouched()) {
                        singleEvent.action = MotionEvent.ACTION_MOVE;
                    } else {
                        singleEvent.action = MotionEvent.ACTION_DOWN;
                    }
                    mTouched = true;
                    break;
                case KeyEvent.ACTION_UP:
                    singleEvent.action = MotionEvent.ACTION_UP;
                    mTouched = false;
                    break;
                default:
                    return false;
                }

                mId = mInputEventComposer.injectSingleEvent(this, singleEvent);
                return true;
            }

            private final float STICK_BUTTON_TRIGGER_OFFSET = 0.2f;

            @Override
            public boolean translate(MotionEvent ev) {
                ArrayList<InputEvent> toEvents = new ArrayList<InputEvent>(2);
                if (ev.getAction() != MotionEvent.ACTION_MOVE)
                    return false;
                float x = ev.getX();
                float y = ev.getY();
                float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
                float lMoveX = ev.getAxisValue(MotionEvent.AXIS_X);
                float lMoveY = ev.getAxisValue(MotionEvent.AXIS_Y);
                float rMoveX = ev.getAxisValue(MotionEvent.AXIS_Z);
                float rMoveY = ev.getAxisValue(MotionEvent.AXIS_RZ);
                float lTrigger = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
                float rTrigger = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);
                boolean toTouchDown = false;
                boolean toTouchRelease = false;
                switch (mData.getKey()) {
                case Mappings.Mapping.KEYCODE_LEFT_STICK_UPPERSIDE:
                    toTouchDown = Math.abs(lMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveY) > Math.abs(lMoveX)
                            && lMoveY < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_DOWNSIDE:
                    toTouchDown = Math.abs(lMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveY) > Math.abs(lMoveX)
                            && lMoveY > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_LEFTSIDE:
                    toTouchDown = Math.abs(lMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveX) > Math.abs(lMoveY)
                            && lMoveX < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_RIGHTSIDE:
                    toTouchDown = Math.abs(lMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveX) > Math.abs(lMoveY)
                            && lMoveX > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_UPPERSIDE:
                    toTouchDown = Math.abs(rMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveY) > Math.abs(rMoveX)
                            && rMoveY < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_DOWNSIDE:
                    toTouchDown = Math.abs(rMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveY) > Math.abs(rMoveX)
                            && rMoveY > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_LEFTSIDE:
                    toTouchDown = Math.abs(rMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveX) > Math.abs(rMoveY)
                            && rMoveX < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_RIGHTSIDE:
                    toTouchDown = Math.abs(rMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveX) > Math.abs(rMoveY)
                            && rMoveX > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_TRIGGER:
                    toTouchDown = Math.abs(lTrigger) > STICK_BUTTON_TRIGGER_OFFSET;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_TRIGGER:
                    toTouchDown = Math.abs(rTrigger) > STICK_BUTTON_TRIGGER_OFFSET;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    toTouchDown = (hatX == -1);
                    toTouchRelease = (hatX == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    toTouchDown = (hatY == -1);
                    toTouchRelease = (hatY == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    toTouchDown = (hatX == 1);
                    toTouchRelease = (hatX == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    toTouchDown = (hatY == 1);
                    toTouchRelease = (hatY == 0) && isTouched();
                    break;
                }
                InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                singleEvent.downTime = ev.getDownTime();
                singleEvent.eventTime = SystemClock.uptimeMillis();
                singleEvent.id = getId();
                singleEvent.x = mData.getX();
                singleEvent.y = mData.getY();
                if (toTouchDown) {
                    if (isTouched())
                        singleEvent.action = MotionEvent.ACTION_MOVE;
                    else
                        singleEvent.action = MotionEvent.ACTION_DOWN;
                    mTouched = true;
                } else if (toTouchRelease) {
                    singleEvent.action = MotionEvent.ACTION_UP;
                    mTouched = false;
                } else {
                    return false;
                }

                mId = mInputEventComposer.injectSingleEvent(this, singleEvent);
                return true;
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public float getLastX() {
                return mLastX;
            }

            @Override
            public float getLastY() {
                return mLastY;
            }
        }

        class StickFilter implements Filter {
            private Mappings.Stick mData;
            private boolean mTouched;
            private float mLastX, mLastY;
            private int mId;

            private final float OFFSET = 0.05f;
            private int mAxisX;
            private int mAxisY;
            private float mRatio;
            private long mDownTime;

            public StickFilter(Mappings.Stick data) {
                mData = data;
                if (mData.getKey() == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                    mAxisX = MotionEvent.AXIS_X;
                    mAxisY = MotionEvent.AXIS_Y;
                } else {
                    mAxisX = MotionEvent.AXIS_Z;
                    mAxisY = MotionEvent.AXIS_RZ;
                }
                mRatio = mData.getRadius() / (1 - OFFSET);
            }

            @Override
            public boolean needChangePage() {
                return (mData.getToPage() != 0 && mTouched == false);
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            @Override
            public boolean canHandleKey(int key) {
                return false;
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                return (mData.getPage() == getCurrActivePage());
            }

            @Override
            public boolean translate(KeyEvent event) {
                return false;
            }

            @Override
            public boolean translate(MotionEvent ev) {
                float x, y, toX, toY;
                x = ev.getAxisValue(mAxisX);
                y = ev.getAxisValue(mAxisY);
                boolean touched = x > OFFSET || x < -OFFSET || y > OFFSET
                        || y < -OFFSET;
                ArrayList<InputEvent> toEvents = new ArrayList<InputEvent>(3);
                toX = mData.getX() + x * mRatio;
                toY = mData.getY() + y * mRatio;

                if (!isTouched() && !touched)
                    return false;

                if (!isTouched()) {
                    mDownTime = SystemClock.uptimeMillis();
                    InputEventComposer.SingleEvent downEvent = mInputEventComposer.new SingleEvent();
                    downEvent.action = MotionEvent.ACTION_DOWN;
                    downEvent.downTime = mDownTime;
                    downEvent.eventTime = mDownTime;
                    downEvent.id = -1;
                    downEvent.x = mData.getX();
                    downEvent.y = mData.getY();
                    mId = mInputEventComposer
                            .injectSingleEvent(this, downEvent);
                }

                InputEventComposer.SingleEvent moveEvent = mInputEventComposer.new SingleEvent();
                moveEvent.action = MotionEvent.ACTION_MOVE;
                moveEvent.downTime = mDownTime;
                moveEvent.eventTime = SystemClock.uptimeMillis();
                moveEvent.id = getId();
                moveEvent.x = toX;
                moveEvent.y = toY;
                mId = mInputEventComposer.injectSingleEvent(this, moveEvent);
                mLastX = toX;
                mLastY = toY;
                mTouched = true;
                if (!touched) {
                    InputEventComposer.SingleEvent upEvent = mInputEventComposer.new SingleEvent();
                    upEvent.action = MotionEvent.ACTION_UP;
                    upEvent.downTime = mDownTime;
                    upEvent.eventTime = SystemClock.uptimeMillis();
                    upEvent.id = getId();
                    upEvent.x = toX;
                    upEvent.y = toY;
                    mInputEventComposer.injectSingleEvent(this, upEvent);
                    mId = -1;
                    mTouched = false;
                }

                return true;
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public float getLastX() {
                return mLastX;
            }

            @Override
            public float getLastY() {
                return mLastY;
            }

        }

        class EventRecordFilter implements Filter {
            private Mappings.EventRecord mData;

            private boolean mTouched;
            private boolean mUseMotionEvent;

            public EventRecordFilter(Mappings.EventRecord data) {
                mData = data;
                mUseMotionEvent = useMotionEventForKey(mData.getKey());
                Mappings.EventRecord.MotionData motionData = (Mappings.EventRecord.MotionData)data.getRecord().get(1);
            }

            @Override
            public boolean canHandleKey(int keyCode) {
                return (!mUseMotionEvent && mData.getKey() == keyCode)
                        && (mData.getPage() == getCurrActivePage());
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                if (mData.getPage() != getCurrActivePage())
                    return false;
                return mUseMotionEvent;
            }

            @Override
            public boolean needChangePage() {
                return (mData.getToPage() != 0);
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            // MotionData send status
            int mSendIndex = -1;
            InputEventComposer.RecordDataState mState;
            Handler mHandler = new Handler() {

                @Override
                public void handleMessage(Message msg) {
                    sendOneMotionData();
                }

            };

            private void startSend() {
                mState = mInputEventComposer.startRecordData(this,
                        (Mappings.EventRecord.MotionData) mData.getRecord()
                                .get(0));
                mSendIndex = 0;
            }

            private void sendOneMotionData() {
                Mappings.EventRecord.MotionData motionData = (Mappings.EventRecord.MotionData) mData
                        .getRecord().get(mSendIndex);
                mInputEventComposer.injectRecordData(mState, motionData);
                mSendIndex++;
                if (mSendIndex >= mData.getRecord().size())
                    mSendIndex = -1;
                if (mSendIndex < 0)
                    return;
                motionData = (Mappings.EventRecord.MotionData) mData
                        .getRecord().get(mSendIndex);

                long atTime = motionData.pc[motionData.history_size - 1].time
                        + mState.timeOffset;
                if (atTime < SystemClock.uptimeMillis())
                    sendOneMotionData();
                else
                    mHandler.sendEmptyMessageAtTime(0, atTime);

            }

            @Override
            public boolean translate(KeyEvent keyEvent) {
                // TODO Report coordinates of other mappings
                ArrayList<InputEvent> toEvents = new ArrayList<InputEvent>(
                        mData.getRecord().size());
                if (keyEvent.getAction() != KeyEvent.ACTION_DOWN)
                    return false;
                if (mSendIndex >= 0)
                    return false;
                startSend();
                sendOneMotionData();
                return true;
            }

            private final float STICK_BUTTON_TRIGGER_OFFSET = 0.2f;

            @Override
            public boolean translate(MotionEvent ev) {
                ArrayList<InputEvent> toEvents = new ArrayList<InputEvent>(
                        mData.getRecord().size());
                if (ev.getAction() != MotionEvent.ACTION_MOVE)
                    return false;
                float x = ev.getX();
                float y = ev.getY();
                float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
                float lMoveX = ev.getAxisValue(MotionEvent.AXIS_X);
                float lMoveY = ev.getAxisValue(MotionEvent.AXIS_Y);
                float rMoveX = ev.getAxisValue(MotionEvent.AXIS_Z);
                float rMoveY = ev.getAxisValue(MotionEvent.AXIS_RZ);
                float lTrigger = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
                float rTrigger = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);
                boolean toTouchDown = false;
                boolean toTouchRelease = false;
                switch (mData.getKey()) {
                case Mappings.Mapping.KEYCODE_LEFT_STICK_UPPERSIDE:
                    toTouchDown = Math.abs(lMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveY) > Math.abs(lMoveX)
                            && lMoveY < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_DOWNSIDE:
                    toTouchDown = Math.abs(lMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveY) > Math.abs(lMoveX)
                            && lMoveY > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_LEFTSIDE:
                    toTouchDown = Math.abs(lMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveX) > Math.abs(lMoveY)
                            && lMoveX < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_STICK_RIGHTSIDE:
                    toTouchDown = Math.abs(lMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(lMoveX) > Math.abs(lMoveY)
                            && lMoveX > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_UPPERSIDE:
                    toTouchDown = Math.abs(rMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveY) > Math.abs(rMoveX)
                            && rMoveY < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_DOWNSIDE:
                    toTouchDown = Math.abs(rMoveY) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveY) > Math.abs(rMoveX)
                            && rMoveY > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_LEFTSIDE:
                    toTouchDown = Math.abs(rMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveX) > Math.abs(rMoveY)
                            && rMoveX < 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_STICK_RIGHTSIDE:
                    toTouchDown = Math.abs(rMoveX) > STICK_BUTTON_TRIGGER_OFFSET
                            && Math.abs(rMoveX) > Math.abs(rMoveY)
                            && rMoveX > 0;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_LEFT_TRIGGER:
                    toTouchDown = Math.abs(lTrigger) > STICK_BUTTON_TRIGGER_OFFSET;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case Mappings.Mapping.KEYCODE_RIGHT_TRIGGER:
                    toTouchDown = Math.abs(rTrigger) > STICK_BUTTON_TRIGGER_OFFSET;
                    toTouchRelease = !toTouchDown && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    toTouchDown = (hatX == -1);
                    toTouchRelease = (hatX == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    toTouchDown = (hatY == -1);
                    toTouchRelease = (hatY == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    toTouchDown = (hatX == 1);
                    toTouchRelease = (hatX == 0) && isTouched();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    toTouchDown = (hatY == 1);
                    toTouchRelease = (hatY == 0) && isTouched();
                    break;
                }
                if (toTouchDown) {
                     if (!isTouched()) {
                        if (mSendIndex >= 0)
                            return false;
                        startSend();
                        sendOneMotionData();
                        mTouched = true;
                     }
                } else {
                    if (toTouchRelease) {
                        mTouched = false;
                    }
                }
                return true;
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return 0;
            }

            @Override
            public void setId(int id) {

            }

            @Override
            public float getLastX() {
                return 0;
            }

            @Override
            public float getLastY() {
                return 0;
            }

        }

        class CursorFilter implements Filter {
            private Mappings.Cursor mData;
            private boolean mTouched;

            private int mMoveAxisX;
            private int mMoveAxisY;

            private float mLast_X = 2.0f;
            private float mLast_Y = -2.0f;

            private int mId;

            private int mCursorType;

            public CursorFilter(Mappings.Cursor data) {
                mData = data;

                if (mData.getKeyMove() == Mappings.Mapping.KEYCODE_LEFT_STICK) {
                    mMoveAxisX = MotionEvent.AXIS_X;
                    mMoveAxisY = MotionEvent.AXIS_Y;
                } else {
                    mMoveAxisX = MotionEvent.AXIS_Z;
                    mMoveAxisY = MotionEvent.AXIS_RZ;
                }

                mCursorType = mData.getCursorType();
            }

            @Override
            public boolean canHandleKey(int keyCode) {
                return (keyCode == mData.getKeyClick() || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) && mData.getPage() == getCurrActivePage();
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                // TODO add drag support
                return (mData.getPage() == getCurrActivePage());
            }

            @Override
            public boolean translate(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) {
                        switchCursorType();
                    }
                    return true;
                }
                InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                singleEvent.eventTime = SystemClock.uptimeMillis();
                singleEvent.id = getId();
                singleEvent.x = mServiceClient.getCursorX();
                singleEvent.y = mServiceClient.getCursorY();
                singleEvent.downTime = ev.getDownTime();
                switch (ev.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (isTouched())
                        singleEvent.action = MotionEvent.ACTION_MOVE;
                    else
                        singleEvent.action = MotionEvent.ACTION_DOWN;
                    mTouched = true;
                    break;
                case KeyEvent.ACTION_UP:
                    singleEvent.action = MotionEvent.ACTION_UP;
                    mTouched = false;
                    break;
                }
                mInputEventComposer.injectSingleEvent(this, singleEvent);
                return true;
            }

            private void switchCursorType() {
                if (mCursorType == Mappings.Cursor.CURSOR_TYPE_ABSOLUTE) {
                    mCursorType = Mappings.Cursor.CURSOR_TYPE_ACCELERATED;
                } else if (mCursorType == Mappings.Cursor.CURSOR_TYPE_ACCELERATED) {
                    mCursorType = Mappings.Cursor.CURSOR_TYPE_ABSOLUTE;
                }
            }

            private boolean moveCursor(MotionEvent ev) {
                float x = ev.getAxisValue(mMoveAxisX);
                float y = ev.getAxisValue(mMoveAxisY);
                if ((x != mLast_X) || (y != mLast_Y)) {
                    mServiceClient.updateCursor(ev, mMoveAxisX, mMoveAxisY,
                            mCursorType);
                    mLast_X = x;
                    mLast_Y = y;
                    if (!mTouched)
                        return true;

                    InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                    singleEvent.id = getId();
                    singleEvent.action = MotionEvent.ACTION_MOVE;
                    singleEvent.downTime = ev.getDownTime();
                    singleEvent.eventTime = SystemClock.uptimeMillis();
                    singleEvent.x = mServiceClient.getCursorX();
                    singleEvent.y = mServiceClient.getCursorY();
                    mInputEventComposer.injectSingleEvent(this, singleEvent);
                    return true;
                }
                return false;
            }

            @Override
            public boolean translate(MotionEvent ev) {
                return moveCursor(ev);
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public boolean needChangePage() {
                return (mData.getToPage() != 0 && mTouched == false);
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            @Override
            public float getLastX() {
                return (float) mServiceClient.getCursorX();
            }

            @Override
            public float getLastY() {
                return (float) mServiceClient.getCursorY();
            }

        }

        class SensorFilter implements Filter {
            private Mappings.Sensor mData;
            private float mLastX, mLastY;
            private int mId;
            private boolean mTouched;

            public SensorFilter(Mappings.Sensor data) {
                mData = data;
                mLastX = mData.getX();
                mLastY = mData.getY();
            }

            @Override
            public boolean needChangePage() {
                return mData.getToPage() != 0 && mTouched == false;
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            @Override
            public boolean canHandleKey(int key) {
                return false;
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                if (mData.getPage() != getCurrActivePage())
                    return false;
                switch (mData.getKey()) {
                case Mappings.Mapping.KEYCODE_LEFT_STICK:
                case Mappings.Mapping.KEYCODE_RIGHT_STICK:
                    return true;
                default:
                    return false;
                }
            }

            @Override
            public boolean translate(KeyEvent event) {
                return false;
            }

            @Override
            public boolean translate(MotionEvent event) {
                if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                    MapperEventPool mapperEventPool = null;
                    if (mContext.getApplicationContext() != null) {
                        mapperEventPool = mContext.getApplicationContext().getMapperEventPool();
                    }
                    if (null != mapperEventPool) {
                        float x = 0.0f, y = 0.0f;
                        switch (mData.getKey()) {
                            case Mappings.Mapping.KEYCODE_LEFT_STICK:
                                x = event.getAxisValue(MotionEvent.AXIS_X);
                                y = event.getAxisValue(MotionEvent.AXIS_Y);
                                break;
                            case Mappings.Mapping.KEYCODE_RIGHT_STICK:
                                x = event.getAxisValue(MotionEvent.AXIS_Z);
                                y = event.getAxisValue(MotionEvent.AXIS_RZ);
                                break;
                        }
                        Configuration mConfiguration = mContext.getApplicationContext().getResources().getConfiguration();
                        if(mConfiguration.orientation == mConfiguration.ORIENTATION_PORTRAIT){
                            x = y - x;
                            y = x - y;
                            x = x - y;
                        }
                        mapperEventPool.updateValues(x, y);
                    }
                }

                return true;
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public float getLastX() {
                return mLastX;
            }

            @Override
            public float getLastY() {
                return mLastY;
            }
        }

        class LookStickFilter implements Filter {

            public LookStickFilter(Mappings.LookStick data) {
                mData = data;
                if (mData.getKey() == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                    mAxisX = MotionEvent.AXIS_X;
                    mAxisY = MotionEvent.AXIS_Y;
                } else {
                    mAxisX = MotionEvent.AXIS_Z;
                    mAxisY = MotionEvent.AXIS_RZ;
                }
            }

            @Override
            public boolean canHandleKey(int keyCode) {
                return false;
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                return (mData.getPage() == getCurrActivePage());
            }

            @Override
            public boolean translate(KeyEvent ev) {
                return false;
            }

            @Override
            public boolean translate(MotionEvent ev) {
                float x, y;
                x = ev.getAxisValue(mAxisX);
                y = ev.getAxisValue(mAxisY);
                boolean start = x > OFFSET || x < -OFFSET || y > OFFSET
                        || y < -OFFSET;

                if (!mStarted && !start)
                    return false;

                mSpeedX = x * mData.getSpeed();
                mSpeedY = y * mData.getSpeed();

                if (!mStarted)
                    startSending();

                if (!start)
                    stopSending();

                return true;
            }

            @Override
            public boolean isTouched() {
                return mIsTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public float getLastX() {
                return mLastX;
            }

            @Override
            public float getLastY() {
                return mLastY;
            }

            @Override
            public boolean needChangePage() {
                return (mData.getToPage() != 0);
            }

            @Override
            public void changePage() {
                setCurrActivePage(mData.getToPage());
            }

            private final float OFFSET = 0.005f;

            private Mappings.LookStick mData;
            private int mId;
            private boolean mIsTouched;
            private int mAxisX;
            private int mAxisY;
            private float mRatio;

            private float mSpeedX, mSpeedY;
            private float mLastX, mLastY, mCurrX, mCurrY;
            private boolean mReSwipe;
            private boolean mStarted;

            private void setStartForLookSwipe() {
                float speedR = FloatMath.sqrt(mSpeedX * mSpeedX + mSpeedY
                        * mSpeedY);
                mCurrX = -mSpeedX * ((float) mData.getRadius()) / speedR;
                mCurrY = -mSpeedY * ((float) mData.getRadius()) / speedR;
                mCurrX += (float) mData.getX();
                mCurrY += (float) mData.getY();
            }

            /**
             * 
             * @param timeInterval
             * @return true for success, false for failed
             */
            private boolean getNextCoordinates(long timeInterval) {
                if (!mStarted)
                    return false;
                float toX, toY;
                toX = mCurrX + mSpeedX * timeInterval;
                toY = mCurrY + mSpeedY * timeInterval;
                if (mData.getLookType() == Mappings.LookStick.LOOK_TYPE_SWIPE) {
                    if (FloatMath.pow(toX - (float) mData.getX(), 2)
                            + FloatMath.pow(toY - (float) mData.getY(), 2)
                            - FloatMath.pow((float) mData.getRadius(), 2) > 4.0f) {
                        setStartForLookSwipe();
                        mReSwipe = true;
                    } else {
                        mCurrX = toX;
                        mCurrY = toY;
                    }
                } else {
                    if (toX < 0 || toY < 0)
                        return true;
                    if (toX > mViewSize[0] || toY > mViewSize[1])
                        return true;
                    mCurrX = toX;
                    mCurrY = toY;
                }
                return true;
            }

            private long EVENT_SEND_INTERVAL = 10;
            private long mDownTime;
            private long mLastSendTime;
            private Handler mHandler = new Handler() {

                @Override
                public void handleMessage(Message msg) {
                    onSendEvent();
                }

            };

            private InputEventComposer.SingleEvent createEvent(int action) {
                InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                singleEvent.action = action;
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownTime = SystemClock.uptimeMillis() + 1;
                    singleEvent.downTime = mDownTime;
                    singleEvent.eventTime = mDownTime;
                } else {
                    singleEvent.downTime = mDownTime;
                    singleEvent.eventTime = SystemClock.uptimeMillis();
                }
                if (action == MotionEvent.ACTION_UP) {
                    singleEvent.x = mLastX;
                    singleEvent.y = mLastY;
                } else {
                    singleEvent.x = mCurrX;
                    singleEvent.y = mCurrY;
                }
                singleEvent.id = getId();
                mLastX = mCurrX;
                mLastY = mCurrY;
                mLastSendTime = singleEvent.eventTime;
                return singleEvent;
            }

            private void startSending() {
                mStarted = true;
                mReSwipe = false;
                if (mData.getLookType() == Mappings.LookStick.LOOK_TYPE_SWIPE) {
                    setStartForLookSwipe();
                } else {
                    mCurrX = mData.getX();
                    mCurrY = mData.getY();
                }

                mId = mInputEventComposer.injectSingleEvent(this,
                        createEvent(MotionEvent.ACTION_DOWN));
                mHandler.sendEmptyMessageDelayed(0, EVENT_SEND_INTERVAL);
            }

            private void onSendEvent() {
                long interval = SystemClock.uptimeMillis() - mLastSendTime;
                getNextCoordinates(interval);
                if (mReSwipe) {
                    mInputEventComposer.injectSingleEvent(this,
                            createEvent(MotionEvent.ACTION_UP));
                    mId = mInputEventComposer.injectSingleEvent(this,
                            createEvent(MotionEvent.ACTION_DOWN));
                } else {
                    mInputEventComposer.injectSingleEvent(this,
                            createEvent(MotionEvent.ACTION_MOVE));
                }
                mReSwipe = false;
                mHandler.sendEmptyMessageDelayed(0, EVENT_SEND_INTERVAL);
            }

            private void stopSending() {
                mHandler.removeMessages(0);
                mInputEventComposer.injectSingleEvent(this,
                        createEvent(MotionEvent.ACTION_UP));
                mStarted = false;
            }

        }
    }

    class TempCursorFilter implements Filter {

            private int mMoveAxisX;
            private int mMoveAxisY;

            private boolean mTouched;

            private int mId;

            private int mCursorType;

            public TempCursorFilter(int type) {
                mMoveAxisX = MotionEvent.AXIS_Z;
                mMoveAxisY = MotionEvent.AXIS_RZ;

                mCursorType = type;
            }

            public boolean isTempCursorEnabled() {
                return mCursorType >= 0;
            }

            @Override
            public boolean canHandleKey(int keyCode) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                    return true;
                }
                return false;
            }

            @Override
            public boolean canHandleMotion(MotionEvent ev) {
                // TODO add drag support
                return isTempCursorEnabled();
            }

            @Override
            public boolean translate(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) {
                        switchCursorType();
                    }
                    return true;
                }
                return true;
            }

            private long mDownTime;

            private void sendTouchEvent(int action) {
                InputEventComposer.SingleEvent singleEvent = mInputEventComposer.new SingleEvent();
                singleEvent.eventTime = SystemClock.uptimeMillis();
                singleEvent.id = getId();
                singleEvent.x = mServiceClient.getCursorX();
                singleEvent.y = mServiceClient.getCursorY();

                switch (action) {
                    case KeyEvent.ACTION_DOWN:
                        if (isTouched()) {
                            singleEvent.action = MotionEvent.ACTION_MOVE;
                        } else {
                            singleEvent.action = MotionEvent.ACTION_DOWN;
                            mDownTime = SystemClock.uptimeMillis();
                        }
                        mTouched = true;
                        break;
                    case KeyEvent.ACTION_UP:
                        if (!mTouched)
                            return;
                        singleEvent.action = MotionEvent.ACTION_UP;
                        mTouched = false;
                        break;
                }

                singleEvent.downTime = mDownTime;
                mInputEventComposer.injectSingleEvent(this, singleEvent);
            }

            private void switchCursorType() {
                if (mCursorType == Mappings.Cursor.CURSOR_TYPE_ABSOLUTE) {
                    mCursorType = Mappings.Cursor.CURSOR_TYPE_NOCURSOR;
                    if (mTouched)
                        sendTouchEvent(KeyEvent.ACTION_UP);
                } else if (mCursorType == Mappings.Cursor.CURSOR_TYPE_ACCELERATED) {
                    mCursorType = Mappings.Cursor.CURSOR_TYPE_ABSOLUTE;
                } else if (mCursorType == Mappings.Cursor.CURSOR_TYPE_NOCURSOR) {
                    mCursorType = Mappings.Cursor.CURSOR_TYPE_ACCELERATED;
                }
                mServiceClient.saveCursorType(mCursorType);
                mServiceClient.hideCursor();
            }

            private boolean moveCursor(MotionEvent ev) {
                if (isTempCursorEnabled()) {
                    mServiceClient.updateCursor(ev, mMoveAxisX, mMoveAxisY, mCursorType);
                }
                return true;
            }

            private final float STICK_BUTTON_TRIGGER_OFFSET = 0.2f;

            private void checkClick(MotionEvent ev) {
                float val_l = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
                float val_r = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);

                if (val_l > STICK_BUTTON_TRIGGER_OFFSET ||
                    val_r > STICK_BUTTON_TRIGGER_OFFSET)
                    sendTouchEvent(KeyEvent.ACTION_DOWN);
                else
                    sendTouchEvent(KeyEvent.ACTION_UP);
            }

            @Override
            public boolean translate(MotionEvent ev) {
                checkClick(ev);
                return moveCursor(ev);
            }

            @Override
            public boolean isTouched() {
                return mTouched;
            }

            @Override
            public int getId() {
                return mId;
            }

            @Override
            public void setId(int id) {
                mId = id;
            }

            @Override
            public boolean needChangePage() {
                return false;
            }

            @Override
            public void changePage() {
            }

            @Override
            public float getLastX() {
                return (float) mServiceClient.getCursorX();
            }

            @Override
            public float getLastY() {
                return (float) mServiceClient.getCursorY();
            }
        }

}
