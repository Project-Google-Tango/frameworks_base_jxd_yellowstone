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

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.content.Context;
import android.content.res.Configuration;
import android.view.MotionEvent;


/**
 * 
 */
public class MapperEventPool {
    private float mX;
    private float mY;
    private boolean mIsEnabled = false;
    private Context mContext;

    private final static float MIN_STICK_MOVE_OFFSET = 0.1f;

    public MapperEventPool (Context context) {
        mContext = context;
    }
/*
    public void updateValues(MotionEvent event) {
        Configuration config = mContext.getResources().getConfiguration();
        if (Configuration.ORIENTATION_LANDSCAPE == config.orientation) {
            mX = event.getAxisValue(MotionEvent.AXIS_X);
            mY = event.getAxisValue(MotionEvent.AXIS_Y);
        } else if (Configuration.ORIENTATION_PORTRAIT == config.orientation) {
            mX = event.getAxisValue(MotionEvent.AXIS_Y);
            mY = event.getAxisValue(MotionEvent.AXIS_X);
        } else {
            mX = event.getAxisValue(MotionEvent.AXIS_X);
            mY = event.getAxisValue(MotionEvent.AXIS_Y);
        }
    }
*/

    public void updateValues(float x, float y) {
        mX = x;
        mY = y;
    }

    public void setMappingEnabled(boolean enabled) {
        mIsEnabled = enabled;
    }

    public boolean isMappingEnabled() {
        return mIsEnabled;
    }

    public boolean isStickValid() {
        if (Math.abs(mX) < MIN_STICK_MOVE_OFFSET && Math.abs(mY) < MIN_STICK_MOVE_OFFSET) {
            return false;
        } else {
            return true;
        }
    }

    public void mapEventValues(float[] values, int type) {
        if (Sensor.TYPE_ACCELEROMETER == type) {
            float x = 0;
            float y = 0;
            if (isStickValid()) {
                x = -mX;
                y = mY;
            }
            float z = 0;
            float squareSum = (float)Math.pow(x, 2) + (float)Math.pow(y, 2);
            if (squareSum < 1.0f) {
                z = (float)Math.sqrt(1 - squareSum);
            }
            values[0] = x * SensorManager.STANDARD_GRAVITY;
            values[1] = y * SensorManager.STANDARD_GRAVITY;
            values[2] = z * SensorManager.STANDARD_GRAVITY;
        } else if (Sensor.TYPE_ORIENTATION == type) {
            float x = 0;
            float y = 0;
            float z = 0;
            if (isStickValid()) {
                z = mX;
                y = -mY;
            }
            values[0] = x;
            values[1] = y * 90.0f;
            values[2] = z * 90.0f;
        }
    }

}
