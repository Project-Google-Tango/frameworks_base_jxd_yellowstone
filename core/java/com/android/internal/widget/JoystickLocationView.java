package com.android.internal.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Paint.FontMetricsInt;
import android.hardware.input.InputManager;
import android.hardware.input.InputManager.InputDeviceListener;
import android.os.SystemProperties;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.MotionEvent.PointerCoords;
import android.view.KeyEvent;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

public class JoystickLocationView extends View {
    private static final String TAG = "JoystickLocation";

    private final Paint mRectPaint;
    private final Paint mPointPaint;
    private final Paint mTextPaint;
    private final Paint mTextBackgroundPaint;

    private float mX;
    private float mY;
    private float mRx;
    private float mRy;
    private float mLt;
    private float mRt;
    private float mHatX;
    private float mHatY;
    private final int scale = 32767;

    private Map keyMap;
    private Map keyNameMap;
    private StringBuilder btnString;

    private final FontMetricsInt mTextMetrics = new FontMetricsInt();

    private int mTextBgHeight;

    public JoystickLocationView(Context c) {
        super(c);
        setFocusableInTouchMode(true);

        mX = 0;
        mY = 0;
        mRx = 0;
        mRy = 0;
        mLt = 0;
        mRt = 0;
        mHatX = 0;
        mHatY = 0;

        keyMap = new HashMap();

        keyNameMap = new HashMap();
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_A, "A");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_B, "B");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_X, "X");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_Y, "Y");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_L1, "LB");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_R1, "RB");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_START, "START");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_THUMBL, "LS");
        keyNameMap.put(KeyEvent.KEYCODE_BUTTON_THUMBR, "RS");

        btnString = new StringBuilder();

        mRectPaint = new Paint();
        mRectPaint.setARGB(32, 0, 255, 255);
        mRectPaint.setStrokeWidth(10);
        mRectPaint.setStyle(Paint.Style.STROKE);

        mPointPaint = new Paint();
        mPointPaint.setARGB(128, 0, 0, 255);
        mPointPaint.setStrokeWidth(10);
        mPointPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mTextPaint = new Paint();
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(10 *
                getResources().getDisplayMetrics().density);
        mTextPaint.setARGB(255, 0, 0, 0);

        mTextBackgroundPaint = new Paint();
        mTextBackgroundPaint.setAntiAlias(false);
        mTextBackgroundPaint.setARGB(128, 255, 255, 255);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mTextPaint.getFontMetricsInt(mTextMetrics);
        mTextBgHeight = -mTextMetrics.ascent + mTextMetrics.descent + 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();

        final int rectSize = w/4;
        final int ptSize = w/100;
        final int rectInterval = 5 * ptSize;

        final int leftbase = w/2 - rectInterval - rectSize;
        final int bottombase = 7*h/8;
        final int topbase = bottombase - rectSize;
        final int Rleftbase = w/2 + rectInterval;
        final int Textbase = h - mTextMetrics.descent;
        final int Textinterval = w/12;

        final int LtLbase = leftbase - rectInterval - ptSize;
        final int RtRbase = Rleftbase + rectSize + rectInterval + ptSize;

        int LJSleft = leftbase + (int)((mX + 1.0f)/2.0f * (float)rectSize) - ptSize/2;;
        int LJSTop = topbase + (int)((mY + 1.0f)/2.0f * (float)rectSize) - ptSize/2;
        int RJSleft = Rleftbase + (int)((mRx + 1.0f)/2.0f * (float)rectSize) - ptSize/2;
        int RJSTop = topbase + (int)((mRy + 1.0f)/2.0f * (float)rectSize) - ptSize/2;

        int LtTop = bottombase - (int)((mLt/1.0f) * (float)rectSize) - ptSize/2;
        int RtTop = bottombase - (int)((mRt/1.0f) * (float)rectSize) - ptSize/2;

        canvas.drawRect(0, h - mTextBgHeight, w, h, mTextBackgroundPaint);
        canvas.drawText("X=" + (int)(mX*scale), 0, Textbase, mTextPaint);
        canvas.drawText("Y=" + (int)(mY*scale), Textinterval, Textbase, mTextPaint);
        canvas.drawText("Rx=" + (int)(mRx*scale), 2*Textinterval, Textbase, mTextPaint);
        canvas.drawText("Ry=" + (int)(mRy*scale), 3*Textinterval, Textbase, mTextPaint);
        canvas.drawText("LT=" + (int)(mLt*scale), 4*Textinterval, Textbase, mTextPaint);
        canvas.drawText("RT=" + (int)(mRt*scale), 5*Textinterval, Textbase, mTextPaint);
        canvas.drawText("HATX=" + (int)mHatX, 6*Textinterval, Textbase, mTextPaint);
        canvas.drawText("HATY=" + (int)mHatY, 7*Textinterval, Textbase, mTextPaint);
        canvas.drawText(btnString.toString(), RtRbase, Textbase, mTextPaint);

        canvas.drawRect(leftbase, topbase, leftbase + rectSize, topbase + rectSize, mRectPaint);
        canvas.drawRect(Rleftbase, topbase, Rleftbase + rectSize, topbase + rectSize, mRectPaint);
        canvas.drawRect(LtLbase, topbase, LtLbase + ptSize, topbase + rectSize, mRectPaint);
        canvas.drawRect(RtRbase - ptSize, topbase, RtRbase, topbase + rectSize, mRectPaint);

        canvas.drawLine(leftbase, topbase + rectSize/2, leftbase + rectSize, topbase + rectSize/2, mRectPaint);
        canvas.drawLine(Rleftbase, topbase + rectSize/2, Rleftbase + rectSize, topbase + rectSize/2, mRectPaint);
        canvas.drawLine(leftbase + rectSize/2, topbase, leftbase + rectSize/2, topbase + rectSize, mRectPaint);
        canvas.drawLine(Rleftbase + rectSize/2, topbase, Rleftbase + rectSize/2, topbase + rectSize, mRectPaint);

        canvas.drawRect(LJSleft, LJSTop, LJSleft + ptSize, LJSTop + ptSize, mPointPaint);
        canvas.drawRect(RJSleft, RJSTop, RJSleft + ptSize, RJSTop + ptSize, mPointPaint);
        canvas.drawRect(LtLbase, LtTop, LtLbase + ptSize, LtTop + ptSize, mPointPaint);
        canvas.drawRect(RtRbase - ptSize, RtTop, RtRbase, RtTop + ptSize, mPointPaint);
    }

    public void addJoystickEvent(MotionEvent ev) {
        mX = ev.getX(ev.getActionIndex());
        mY = ev.getY(ev.getActionIndex());
        mRx = ev.getAxisValue(MotionEvent.AXIS_Z);
        mRy = ev.getAxisValue(MotionEvent.AXIS_RZ);
        mLt = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        mRt = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        mHatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        mHatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        invalidate();
    }

    public void addKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            keyMap.remove(event.getKeyCode());
        } else {
            String name = (String)keyNameMap.get(event.getKeyCode());
            if (name != null)
                keyMap.put(event.getKeyCode(), 1);
            else
                return;
        }

        btnString.setLength(0);
        Iterator iter = keyMap.entrySet().iterator();

        while(iter.hasNext()) {
            Map.Entry mEntry = (Map.Entry) iter.next();
            String name = (String)keyNameMap.get(mEntry.getKey());
            btnString.append(name + " ");
        }
        invalidate();
    }


}
