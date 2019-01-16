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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;

import android.graphics.Bitmap;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;

/**
 * This class is used by both service and client to pass data.
 *
 */
public class Mappings implements Serializable {
    private static final long serialVersionUID = 0L;

    /**
     * Message sent to service to request mapping data. Service should reply
     * with MSG_SEND_MAPPINGS. Bundle: set MSG_KEY_APP_NAME
     */
    public static final int MSG_GET_MAPPINGS = 0;
    /**
     * Used for service to send mapping data. Bundle: set MSG_KEY_MAPPINGS.
     */
    public static final int MSG_SEND_MAPPINGS = 1;
    /**
     * Used by client to ask service to show mapping overlay Bundle: set
     * MSG_KEY_APP_NAME
     */
    public static final int MSG_SHOW_MAPPINGS = 2;

    /**
     * Show cursor. Reply = MSG_SEND_CURSOR_POS
     */
    public static final int MSG_SHOW_CURSOR = 3;
    /**
     * Hide Cursor
     */
    public static final int MSG_HIDE_CURSOR = 4;
    /**
     * Move cursor. Reply = MSG_SEND_CURSOR_POS
     */
    public static final int MSG_MOVE_CURSOR = 5;
    /**
     * Service send cursor coordinates to client. arg1:x arg2:y
     */
    public static final int MSG_SEND_CURSOR_POS = 6;

    /**
     * Used to request mapping reload.
     */
    public static final int MSG_RELOAD_MAPPINGS = 7;

    /**
     * Used to save global cursor type.
     */
    public static final int MSG_SET_CURSOR_TYPE = 8;

    /**
     * Message bundle keys
     */
    public static final String MSG_KEY_APP_NAME = "appName";
    public static final String MSG_KEY_CURR_PAGE = "currpage";
    public static final String MSG_KEY_MAPPNIGS = "mappings";
    /* It's bind to int array. [0] = width, [1] = height */
    public static final String MSG_KEY_VIEW_SIZE = "viewsize";
    public static final String MSG_KEY_CURSOR_TYPE = "cursortype";

    protected int mCurrPage;
    protected boolean mDebug;

    public static class Mapping implements Serializable {
        protected static final long serialVersionUID = 0L;

        public static final int TYPE_STICK = 0;
        public static final int TYPE_BUTTON = 1;
        public static final int TYPE_EVENT_RECORD = 2;

        /**
         * Cursor has a stick to move cursor and a button to do click, optional
         * another stick to drag
         */
        public static final int TYPE_CURSOR = 3;
        public static final int TYPE_SENSOR = 4;
        public static final int TYPE_LOOK_STICK = 5;

        public static final int KEYCODE_LEFT_STICK = KeyEvent.KEYCODE_ASSIST + 1;
        public static final int KEYCODE_RIGHT_STICK = KeyEvent.KEYCODE_ASSIST + 2;
        public static final int KEYCODE_LEFT_STICK_UPPERSIDE = KeyEvent.KEYCODE_ASSIST + 3;
        public static final int KEYCODE_LEFT_STICK_DOWNSIDE = KeyEvent.KEYCODE_ASSIST + 4;
        public static final int KEYCODE_LEFT_STICK_LEFTSIDE = KeyEvent.KEYCODE_ASSIST + 5;
        public static final int KEYCODE_LEFT_STICK_RIGHTSIDE = KeyEvent.KEYCODE_ASSIST + 6;
        public static final int KEYCODE_RIGHT_STICK_UPPERSIDE = KeyEvent.KEYCODE_ASSIST + 7;
        public static final int KEYCODE_RIGHT_STICK_DOWNSIDE = KeyEvent.KEYCODE_ASSIST + 8;
        public static final int KEYCODE_RIGHT_STICK_LEFTSIDE = KeyEvent.KEYCODE_ASSIST + 9;
        public static final int KEYCODE_RIGHT_STICK_RIGHTSIDE = KeyEvent.KEYCODE_ASSIST + 10;
        public static final int KEYCODE_LEFT_TRIGGER = KeyEvent.KEYCODE_ASSIST + 11;
        public static final int KEYCODE_RIGHT_TRIGGER = KeyEvent.KEYCODE_ASSIST + 12;

        protected int mType;
        protected int mX, mY, mKey;
        protected int mToPage = 0;
        protected int mPage;

        public Mapping(int x, int y) {
            mX = x;
            mY = y;
        }

        public Mapping(int x, int y, int key) {
            this(x, y);
            mKey = key;
        }

        public int getToPage() {
            return mToPage;
        }

        public void setToPage(int to) {
            mToPage = to;
        }

        public int getPage() {
            return mPage;
        }

        public void setPage(int page) {
            mPage = page;
        }

        public int getType() {
            return mType;
        }

        public int getX() {
            return mX;
        }

        public void setX(int x) {
            mX = x;
        }

        public int getY() {
            return mY;
        }

        public void setY(int y) {
            mY = y;
        }

        public int getKey() {
            return mKey;
        }

        public void setKey(int Key) {
            mKey = Key;
        }

        protected static int scaleAxis(int value, float scaleFactor) {
            return (int) (((float) value) * scaleFactor);
        }

        public void scale(float scaleX, float scaleY) {
            mX = scaleAxis(mX, scaleX);
            mY = scaleAxis(mY, scaleY);
        }
    }

    public static class Stick extends Mapping {
        private static final long serialVersionUID = 1L;

        protected int mRadius;

        public Stick(int x, int y, int radius) {
            super(x, y);
            mType = TYPE_STICK;
            mKey = KeyEvent.KEYCODE_BUTTON_THUMBL;
            mRadius = radius;
        }

        public int getRadius() {
            return mRadius;
        }

        public void setRadius(int mRadius) {
            this.mRadius = mRadius;
        }

        @Override
        public void scale(float scaleX, float scaleY) {
            super.scale(scaleX, scaleY);
            mRadius = scaleAxis(mRadius, scaleX); // we assume resolution is
                                                  // always 16:9
        }
    }

    public static class LookStick extends Stick {
        private static final long serialVersionUID = 1L;

        // look around like mc4
        public static final int LOOK_TYPE_SWIPE = 0;
        // when finger up, the scene back to center of screen
        public static final int LOOK_TYPE_BACK_CENTER = 1;

        protected int mLookType;
        protected float mSpeed;

        public LookStick(int x, int y, int radius, int lookType) {
            super(x, y, radius);
            mType = TYPE_LOOK_STICK;
            mLookType = lookType;
        }

        public int getLookType() {
            return mLookType;
        }

        public float getSpeed() {
            return mSpeed;
        }

        public void setSpeed(float speed) {
            mSpeed = speed;
        }

    }

    public static class Button extends Mapping {
        private static final long serialVersionUID = 1L;

        public Button(int x, int y) {
            super(x, y);
            mType = TYPE_BUTTON;
        }

        public Button(int x, int y, int key) {
            super(x, y, key);
            mType = TYPE_BUTTON;
        }
    }

    public static class EventRecord extends Mapping {
        private static final long serialVersionUID = 1L;

        public static final int TYPE_MOTION_EVENT = 0;
        public static final int TYPE_KEY_EVENT = 1;

        public interface Data {
            int getType();

            void scale(float scaleX, float scaleY);
        }

        public static class MotionData implements Data, Serializable {
            private static final long serialVersionUID = 1L;

            public static class Finger implements Serializable {
                private static final long serialVersionUID = 1L;

                public int id;
                public float x, y, pressure, size;
            }

            public static class PointerCoordinates implements Serializable {
                private static final long serialVersionUID = 1L;

                public long time;
                public Finger[] fingers;
            }

            public long downtime;
            public int action, pointer_cnt, history_size;
            public PointerCoordinates[] pc;

            @Override
            public int getType() {
                return TYPE_MOTION_EVENT;
            }

            @Override
            public void scale(float scaleX, float scaleY) {
                if (pc == null)
                    return;
                for (PointerCoordinates onePC : pc) {
                    for (Finger finger : onePC.fingers) {
                        finger.x = scaleAxis((int) finger.x, scaleX);
                        finger.y = scaleAxis((int) finger.y, scaleY);
                        finger.size = scaleAxis((int) finger.size, scaleX);
                    }
                }
            }
        }

        public class KeyData implements Data, Serializable {
            private static final long serialVersionUID = 1L;

            public int action;
            public int keyCode;

            @Override
            public int getType() {
                return TYPE_KEY_EVENT;
            }

            @Override
            public void scale(float scaleX, float scaleY) {

            }
        }

        private ArrayList<Data> mRecord = new ArrayList<Data>(100);

        public EventRecord() {
            super(-1, -1);
            mType = TYPE_EVENT_RECORD;
        }

        public Data addInputEvent(InputEvent ev) {
            if (ev instanceof MotionEvent) {
                MotionData data = new MotionData();
                MotionEvent event = (MotionEvent) ev;
                data.downtime = event.getDownTime();
                data.action = event.getAction();
                data.pointer_cnt = event.getPointerCount();
                data.history_size = event.getHistorySize() + 1;
                data.pc = new MotionData.PointerCoordinates[data.history_size];

                for (int h = 0; h < event.getHistorySize(); h++) {
                    data.pc[h] = new MotionData.PointerCoordinates();
                    data.pc[h].time = event.getHistoricalEventTime(h);
                    MotionData.Finger[] fingers = new MotionData.Finger[data.pointer_cnt];
                    for (int p = 0; p < data.pointer_cnt; p++) {
                        fingers[p] = new MotionData.Finger();
                        fingers[p].id = event.getPointerId(p);
                        fingers[p].x = event.getHistoricalX(p, h);
                        fingers[p].y = event.getHistoricalY(p, h);
                        fingers[p].pressure = event.getHistoricalPressure(p, h);
                        fingers[p].size = event.getHistoricalSize(p, h);
                    }
                    data.pc[h].fingers = fingers;
                }

                MotionData.PointerCoordinates pc = new MotionData.PointerCoordinates();
                pc.time = event.getEventTime();
                MotionData.Finger[] fingers = new MotionData.Finger[data.pointer_cnt];
                for (int p = 0; p < data.pointer_cnt; p++) {
                    fingers[p] = new MotionData.Finger();
                    fingers[p].id = event.getPointerId(p);
                    fingers[p].x = event.getX(p);
                    fingers[p].y = event.getY(p);
                    fingers[p].pressure = event.getPressure(p);
                    fingers[p].size = event.getSize(p);
                    if (mX < 0)
                        mX = (int) fingers[p].x;
                    if (mY < 0)
                        mY = (int) fingers[p].y;
                }
                pc.fingers = fingers;
                data.pc[data.history_size - 1] = pc;
                mRecord.add(data);

                return data;
            } else if (ev instanceof KeyEvent) {
                KeyData data = new KeyData();
                KeyEvent event = (KeyEvent) ev;
                data.action = event.getAction();
                data.keyCode = event.getKeyCode();
                mRecord.add(data);
                return data;
            } else
                throw new IllegalArgumentException("invalid event " + ev);
        }

        // ds read order MUST be exactly same as writeMotionData
        private MotionData readMotionData(DataInputStream ds)
                throws IOException {
            MotionData data = new MotionData();
            data.downtime = ds.readLong();
            data.action = ds.readInt();
            data.pointer_cnt = ds.readInt();
            data.history_size = ds.readInt();
            data.pc = new MotionData.PointerCoordinates[data.history_size];

            for (int h = 0; h < data.history_size; h++) {
                data.pc[h] = new MotionData.PointerCoordinates();
                data.pc[h].time = ds.readLong();
                MotionData.Finger[] fingers = new MotionData.Finger[data.pointer_cnt];
                for (int p = 0; p < data.pointer_cnt; p++) {
                    fingers[p] = new MotionData.Finger();
                    fingers[p].id = ds.readInt();
                    fingers[p].x = ds.readFloat();
                    fingers[p].y = ds.readFloat();
                    fingers[p].pressure = ds.readFloat();
                    fingers[p].size = ds.readFloat();
                    if (p != data.pointer_cnt - 1)
                        continue;
                    if (mX < 0)
                        mX = (int) fingers[p].x;
                    if (mY < 0)
                        mY = (int) fingers[p].y;
                }
                data.pc[h].fingers = fingers;
            }

            return data;
        }

        // ds put order MUST be exactly same as readMotionData
        private void writeMotionData(MotionData data, DataOutputStream ds)
                throws IOException {
            ds.writeLong(data.downtime);
            ds.writeInt(data.action);
            ds.writeInt(data.pointer_cnt);
            ds.writeInt(data.history_size);
            for (int h = 0; h < data.history_size; h++) {
                ds.writeLong(data.pc[h].time);
                for (int p = 0; p < data.pointer_cnt; p++) {
                    MotionData.Finger finger = data.pc[h].fingers[p];
                    ds.writeInt(finger.id);
                    ds.writeFloat(finger.x);
                    ds.writeFloat(finger.y);
                    ds.writeFloat(finger.pressure);
                    ds.writeFloat(finger.size);
                }
            }
        }

        // ds get order MUST be exactly same as writeKeyData
        private KeyData readKeyData(DataInputStream ds) throws IOException {
            KeyData data = new KeyData();
            data.action = ds.readInt();
            data.keyCode = ds.readInt();
            return data;
        }

        // ds put order MUST be exactly same as readKeyData
        private void writeKeyData(KeyData data, DataOutputStream ds)
                throws IOException {
            ds.writeInt(data.action);
            ds.writeInt(data.keyCode);
        }

        // read order MUST be exactly same as writeToByteArray
        public boolean readFromByteArray(byte[] record) throws IOException {
            DataInputStream ds = new DataInputStream(new ByteArrayInputStream(
                    record));
            mRecord.clear();

            try {
                while (true) {
                    if (ds.available() < 4)
                        break;
                    int type = ds.readInt();
                    if (type == TYPE_MOTION_EVENT) {
                        mRecord.add(readMotionData(ds));
                    } else if (type == TYPE_KEY_EVENT) {
                        mRecord.add(readKeyData(ds));
                    } else {
                        throw new IllegalArgumentException(
                                "invalid content of bytearray");
                    }
                }
            } catch (Exception exp) {
                return false;
            }

            return true;
        }

        // write order MUST be exactly same as readFromByteArray
        public byte[] writeToByteArray() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(100);
            DataOutputStream ds = new DataOutputStream(output);
            for (Data data : mRecord) {
                if (data instanceof MotionData) {
                    ds.writeInt(TYPE_MOTION_EVENT);
                    writeMotionData((MotionData) data, ds);
                } else if (data instanceof KeyData) {
                    ds.writeInt(TYPE_KEY_EVENT);
                    writeKeyData((KeyData) data, ds);
                } else
                    throw new IllegalArgumentException("invalid event");
            }
            ds.flush();
            ds.close();
            return output.toByteArray();
        }

        public ArrayList<Data> getRecord() {
            return mRecord;
        }

        public void setRecord(ArrayList<Data> record) {
            mRecord = record;
        }

        @Override
        public void scale(float scaleX, float scaleY) {
            super.scale(scaleX, scaleY);
            for (Data data : mRecord)
                data.scale(scaleX, scaleY);
        }

    }

    public static class Cursor extends Mapping {
        private static final long serialVersionUID = 1L;

        public static final int CURSOR_TYPE_NOCURSOR = -1;
        public static final int CURSOR_TYPE_ABSOLUTE = 0;
        public static final int CURSOR_TYPE_ACCELERATED = 1;

        private int mKeyMove, mKeyDrag, mKeyClick, mCursorType;

        public Cursor(int x, int y, int keyMove, int keyDrag, int keyClick, int cursorType) {
            super(x, y);
            mType = TYPE_CURSOR;
            mKeyMove = keyMove;
            mKeyDrag = keyDrag;
            mKeyClick = keyClick;
            mCursorType = cursorType;
        }

        public int getKeyMove() {
            return mKeyMove;
        }

        public void setKeyMove(int mKeyMove) {
            this.mKeyMove = mKeyMove;
        }

        public int getKeyDrag() {
            return mKeyDrag;
        }

        public void setKeyDrag(int mKeyDrag) {
            this.mKeyDrag = mKeyDrag;
        }

        public int getKeyClick() {
            return mKeyClick;
        }

        public void setKeyClick(int mKeyClick) {
            this.mKeyClick = mKeyClick;
        }

        public int getCursorType() {
            return mCursorType;
        }

        public void setCursorType(int cursorType) {
            this.mCursorType = cursorType;
        }

    }

    public static class Sensor extends Mapping {
        private static final long serialVersionUID = 1L;

        public Sensor(int x, int y) {
            super(x, y);
            mType = TYPE_SENSOR;
        }

        public Sensor(int x, int y, int key) {
            super(x, y, key);
            mType = TYPE_SENSOR;
        }
    }

    protected ArrayList<Mapping> mMappings = new ArrayList<Mapping>();
    protected boolean mEnabled;

    public ArrayList<Mapping> getMappings() {
        return mMappings;
    }

    public void addMapping(Mapping mapping) {
        mMappings.add(mapping);
    }

    public void removeMapping(Mapping mapping) {
        mMappings.remove(mapping);
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public void setCurrPage(int curr) {
        mCurrPage = curr;
    }

    public int getCurrPage() {
        return mCurrPage;
    }

    public boolean getDebug() {
        return mDebug;
    }

    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    public void scale(float scaleX, float scaleY) {
        for (Mapping mapping : mMappings)
            mapping.scale(scaleX, scaleY);
    }

    public static Bitmap catchScreenShot(int width, int height) {
        return SurfaceControl.screenshot(width, height);

    }
}
