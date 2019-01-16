/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (c) 2013, NVIDIA CORPORATION. All rights reserved.
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

package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.FloatMath;

/**
 * Detects scaling transformation gestures using the supplied {@link MotionEvent}s.
 * The {@link OnScaleGestureListener} callback will notify users when a particular
 * gesture event has occurred.
 *
 * This class should only be used with {@link MotionEvent}s reported via touch.
 *
 * To use this class:
 * <ul>
 *  <li>Create an instance of the {@code ScaleGestureDetector} for your
 *      {@link View}
 *  <li>In the {@link View#onTouchEvent(MotionEvent)} method ensure you call
 *          {@link #onTouchEvent(MotionEvent)}. The methods defined in your
 *          callback will be executed when the events occur.
 * </ul>
 */
public class ScaleGestureDetector {
    private static final String TAG = "ScaleGestureDetector";

    /**
     * The listener for receiving notifications when gestures occur.
     * If you want to listen for all the different gestures then implement
     * this interface. If you only want to listen for a subset it might
     * be easier to extend {@link SimpleOnScaleGestureListener}.
     *
     * An application will receive events in the following order:
     * <ul>
     *  <li>One {@link OnScaleGestureListener#onScaleBegin(ScaleGestureDetector)}
     *  <li>Zero or more {@link OnScaleGestureListener#onScale(ScaleGestureDetector)}
     *  <li>One {@link OnScaleGestureListener#onScaleEnd(ScaleGestureDetector)}
     * </ul>
     */
    public interface OnScaleGestureListener {
        /**
         * Responds to scaling events for a gesture in progress.
         * Reported by pointer motion.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return Whether or not the detector should consider this event
         *          as handled. If an event was not handled, the detector
         *          will continue to accumulate movement until an event is
         *          handled. This can be useful if an application, for example,
         *          only wants to update scaling factors if the change is
         *          greater than 0.01.
         */
        public boolean onScale(ScaleGestureDetector detector);

        /**
         * Responds to the beginning of a scaling gesture. Reported by
         * new pointers going down.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return Whether or not the detector should continue recognizing
         *          this gesture. For example, if a gesture is beginning
         *          with a focal point outside of a region where it makes
         *          sense, onScaleBegin() may return false to ignore the
         *          rest of the gesture.
         */
        public boolean onScaleBegin(ScaleGestureDetector detector);

        /**
         * Responds to the end of a scale gesture. Reported by existing
         * pointers going up.
         *
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} will return focal point
         * of the pointers remaining on the screen.
         *
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         */
        public void onScaleEnd(ScaleGestureDetector detector);
    }

    /**
     * A convenience class to extend when you only want to listen for a subset
     * of scaling-related events. This implements all methods in
     * {@link OnScaleGestureListener} but does nothing.
     * {@link OnScaleGestureListener#onScale(ScaleGestureDetector)} returns
     * {@code false} so that a subclass can retrieve the accumulated scale
     * factor in an overridden onScaleEnd.
     * {@link OnScaleGestureListener#onScaleBegin(ScaleGestureDetector)} returns
     * {@code true}.
     */
    public static class SimpleOnScaleGestureListener implements OnScaleGestureListener {

        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        public void onScaleEnd(ScaleGestureDetector detector) {
            // Intentionally empty
        }
    }

    private class ScaleDataKalmanFilter {

        private class KalmanFilteredValue {
            // This class keeps track of a one dimensional value given in pixels, applying Kalman
            // filtering to it. We do filtering individually for each dimension (span / focus) to
            // simplify it, even though there could be some correlation in the errors in different
            // dimensions depending on the properties of the touch hardware / lower-level software.

            float mResult;
            float mEstimate;
            float mP;
            float mPrevMeasurement;
            float mDelta;

            // Delta is smoothed by taking a weighted average of it and previous delta.
            private static final float DELTA_SMOOTHING_WEIGHT = 5.0f;
            private static final float DELTA_SMOOTHING_DIV = 1.0f / (DELTA_SMOOTHING_WEIGHT + 1.0f);

            // Kalman filter constant related to the standard deviation of process noise
            private static final float Q = 0.1f;
            // Kalman filter constant related to the standard deviation of measurement noise
            private static final float R = 5.0f;

            // How much less to care about measurement error if the delta is big.
            // Adjusts R downwards.
            private static final float SMALL_RELATIVE_ERROR_DISREGARD = 12.0f * 160.0f;

            KalmanFilteredValue() {}

            public void reset(float measurement) {
                mResult = measurement;
                mPrevMeasurement = measurement;
                mP = 0.7f;
                mDelta = 0.0f;
            }

            private void timeUpdate() {
                // Estimate based on linear motion derived from previous data.
                mEstimate = mResult + mDelta;
                mP += Q;
            }

            private float measureUpdate(float measurement) {
                mDelta = (measurement - mPrevMeasurement + mDelta * DELTA_SMOOTHING_WEIGHT) *
                          DELTA_SMOOTHING_DIV;
                mPrevMeasurement = measurement;

                float inchDelta = Math.abs(mDelta) / mDisplayMetrics.xdpi;
                // Visible effect of the measurement error is negligible if the delta is big.
                // Thus we lower the estimate weight in this case to avoid overshooting due to
                // estimate error.
                float k = mP / (mP + R / (inchDelta * SMALL_RELATIVE_ERROR_DISREGARD + 1));
                mResult = mEstimate + k * (measurement - mEstimate);
                mP *= 1 - k;
                return mResult;
            }

            public float update(float measurement) {
                timeUpdate();
                return measureUpdate(measurement);
            }
        }

        private ScaleData mResult;
        private KalmanFilteredValue mSpanX;
        private KalmanFilteredValue mSpanY;
        private KalmanFilteredValue mFocusX;
        private KalmanFilteredValue mFocusY;

        ScaleDataKalmanFilter() {
            mResult = new ScaleData();
            mSpanX = new KalmanFilteredValue();
            mSpanY = new KalmanFilteredValue();
            mFocusX = new KalmanFilteredValue();
            mFocusY = new KalmanFilteredValue();
        }

        public ScaleData startFiltering(ScaleData measured) {
            mResult.set(measured);
            mSpanX.reset(measured.getSpanX());
            mSpanY.reset(measured.getSpanY());
            mFocusX.reset(measured.getFocusX());
            mFocusY.reset(measured.getFocusY());
            return mResult;
        }

        // Should be called with data from constant time intervals.
        public ScaleData update(ScaleData measured) {
            mResult.setSpan(mSpanX.update(measured.getSpanX()),
                            mSpanY.update(measured.getSpanY()));
            mResult.setFocus(mFocusX.update(measured.getFocusX()),
                             mFocusY.update(measured.getFocusY()));
            return mResult;
        }

    }

    private class ScaleData {
        private float mFocusX;
        private float mFocusY;
        private float mSpanX;
        private float mSpanY;
        private boolean mUseSpanY;

        ScaleData() {
        }

        public void set(ScaleData other) {
            setSpan(other);
            setFocus(other);
        }

        public void interpolateWith(ScaleData other, float otherWeight) {
            mSpanX += (other.mSpanX - mSpanX) * otherWeight;
            mSpanY += (other.mSpanY - mSpanY) * otherWeight;
            mFocusX += (other.mFocusX - mFocusX) * otherWeight;
            mFocusY += (other.mFocusY - mFocusY) * otherWeight;
        }

        public void setSpan(ScaleData other) {
            setSpan(other.mSpanX, other.mSpanY);
            mUseSpanY = other.mUseSpanY;
        }

        public void setSpan(float spanX, float spanY) {
            mSpanX = spanX;
            mSpanY = spanY;
        }

        public void setFocus(ScaleData other) {
            setFocus(other.mFocusX, other.mFocusY);
        }

        public void setFocus(float focusX, float focusY) {
            mFocusX = focusX;
            mFocusY = focusY;
        }

        public void setFromEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            final boolean pointerUp = (action == MotionEvent.ACTION_POINTER_UP);
            final int skipIndex = pointerUp ? event.getActionIndex() : -1;

            final int count = event.getPointerCount();
            final int div = pointerUp ? count - 1 : count;

            // Determine focal point
            if (mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS) {
                // In double tap mode, the focal pt is always where the double tap
                // gesture started
                setFocus(mDoubleTapEvent.getX(), mDoubleTapEvent.getY());
            } else {
                float sumX = 0, sumY = 0;
                for (int i = 0; i < count; i++) {
                    if (skipIndex == i) continue;
                    sumX += event.getX(i);
                    sumY += event.getY(i);
                }
                setFocus(sumX / div, sumY / div);
            }
            mUseSpanY = mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS;

            // Determine average deviation from focal point
            float devSumX = 0, devSumY = 0;
            for (int i = 0; i < count; i++) {
                if (skipIndex == i) continue;

                devSumX += Math.abs(event.getX(i) - getFocusX()) + mTouchMinMajor * 0.7f;
                devSumY += Math.abs(event.getY(i) - getFocusY()) + mTouchMinMajor * 0.7f;
            }
            // Span is the average distance between touch points through the focal point;
            // i.e. the diameter of the circle with a radius of the average deviation from
            // the focal point.
            setSpan(devSumX / div * 2, devSumY / div * 2);
        }

        public void setFromEventHistory(MotionEvent event, int pos) {
            final int count = event.getPointerCount();

            // Determine focal point
            if (mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS) {
                // In double tap mode, the focal pt is always where the double tap
                // gesture started
                setFocus(mDoubleTapEvent.getX(), mDoubleTapEvent.getY());
            } else {
                float sumX = 0, sumY = 0;
                for (int i = 0; i < count; i++) {
                    sumX += event.getHistoricalX(i, pos);
                    sumY += event.getHistoricalY(i, pos);
                }
                setFocus(sumX / count, sumY / count);
            }
            mUseSpanY = mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS;

            // Determine average deviation from focal point
            float devSumX = 0, devSumY = 0;
            for (int i = 0; i < count; i++) {
                devSumX += Math.abs(event.getHistoricalX(i, pos) - getFocusX()) +
                           mTouchMinMajor * 0.7f;
                devSumY += Math.abs(event.getHistoricalY(i, pos) - getFocusY()) +
                           mTouchMinMajor * 0.7f;
            }
            // Span is the average distance between touch points through the focal point;
            // i.e. the diameter of the circle with a radius of the average deviation from
            // the focal point.
            setSpan(devSumX / count * 2, devSumY / count * 2);
        }

        public float getFocusX() {
            return mFocusX;
        }

        public float getFocusY() {
            return mFocusY;
        }

        public float getSpanX() {
            return mSpanX;
        }

        public float getSpanY() {
            return mSpanY;
        }

        public boolean useSpanY() {
            return mUseSpanY;
        }

        public float getSpan() {
            return mUseSpanY ? mSpanY : FloatMath.sqrt(mSpanX * mSpanX + mSpanY * mSpanY);
        }
    }

    private static class SpanData {
        private float mSpanX;
        private float mSpanY;
        private boolean mUseSpanY;

        public SpanData() {
        }

        public void set(ScaleData from) {
            mSpanX = from.getSpanX();
            mSpanY = from.getSpanY();
            mUseSpanY = from.useSpanY();
        }

        public float getSpanX() {
            return mSpanX;
        }

        public float getSpanY() {
            return mSpanY;
        }

        public float getSpan() {
            return mUseSpanY ? mSpanY : FloatMath.sqrt(mSpanX * mSpanX + mSpanY * mSpanY);
        }
    }

    private final Context mContext;
    private final OnScaleGestureListener mListener;
    private DisplayMetrics mDisplayMetrics;

    private ScaleData mCurrScaleData;
    private SpanData mPrevSpanData;
    private ScaleData mOnTouchScaleData; // Only for use in onTouchEvent, here for efficiency

    private ScaleData mHistoricalScaleData;
    private long mHistoricalScaleDataTime = 0;

    private ScaleDataKalmanFilter mKalmanFilter;

    private boolean mQuickScaleEnabled;

    private float mInitialSpan;
    private long mCurrTime;
    private long mPrevTime;
    private boolean mInProgress;
    private int mSpanSlop;
    private int mMinSpan;

    private int mTouchMinMajor;
    private MotionEvent mDoubleTapEvent;
    private int mDoubleTapMode = DOUBLE_TAP_MODE_NONE;
    private final Handler mHandler;

    private static final int DOUBLE_TAP_MODE_NONE = 0;
    private static final int DOUBLE_TAP_MODE_IN_PROGRESS = 1;
    private static final float SCALE_FACTOR = .5f;

    private final long TIME_STEP = 8; // milliseconds
    private final long MAX_EVENT_INTERVAL = 1000; // milliseconds

    /**
     * Consistency verifier for debugging purposes.
     */
    private final InputEventConsistencyVerifier mInputEventConsistencyVerifier =
            InputEventConsistencyVerifier.isInstrumentationEnabled() ?
                    new InputEventConsistencyVerifier(this, 0) : null;
    private GestureDetector mGestureDetector;

    private boolean mEventBeforeOrAboveStartingGestureEvent;

    /**
     * Creates a ScaleGestureDetector with the supplied listener.
     * You may only use this constructor from a {@link android.os.Looper Looper} thread.
     *
     * @param context the application's context
     * @param listener the listener invoked for all the callbacks, this must
     * not be null.
     *
     * @throws NullPointerException if {@code listener} is null.
     */
    public ScaleGestureDetector(Context context, OnScaleGestureListener listener) {
        this(context, listener, null);
    }

    /**
     * Creates a ScaleGestureDetector with the supplied listener.
     * @see android.os.Handler#Handler()
     *
     * @param context the application's context
     * @param listener the listener invoked for all the callbacks, this must
     * not be null.
     * @param handler the handler to use for running deferred listener events.
     *
     * @throws NullPointerException if {@code listener} is null.
     */
    public ScaleGestureDetector(Context context, OnScaleGestureListener listener,
                                Handler handler) {
        mContext = context;
        mListener = listener;
        mSpanSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;

        final Resources res = context.getResources();
        mTouchMinMajor = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_minScalingTouchMajor);
        mMinSpan = res.getDimensionPixelSize(com.android.internal.R.dimen.config_minScalingSpan);
        mHandler = handler;
        // Quick scale is enabled by default after JB_MR2
        if (context.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setQuickScaleEnabled(true);
        }

        mDisplayMetrics = res.getDisplayMetrics();

        mCurrScaleData = new ScaleData();
        mPrevSpanData = new SpanData();
        mOnTouchScaleData = new ScaleData();
        mHistoricalScaleData = new ScaleData();
        mKalmanFilter = new ScaleDataKalmanFilter();
    }

    private ScaleData restartProcessingScaleDataFrom(ScaleData measurement, long time) {
        mHistoricalScaleData.set(measurement);
        mHistoricalScaleDataTime = time;
        return mKalmanFilter.startFiltering(measurement);
    }

    private ScaleData processScaleData(ScaleData measurement, long time) {
        // Linearly interpolate measurement values to get the value
        // at the correct point in time.

        ScaleData filtered = null;

        // Prevent getting stuck in the loop in case of an incomplete event stream
        // or not entering the loop at all if timer has wrapped around.
        if (Math.abs(time - mHistoricalScaleDataTime) > MAX_EVENT_INTERVAL) {
            return restartProcessingScaleDataFrom(measurement, time);
        }

        while (mHistoricalScaleDataTime + TIME_STEP <= time) {
            long timeDelta = time - mHistoricalScaleDataTime;
            float measurementWeight = (float)TIME_STEP / (float)timeDelta;
            mHistoricalScaleData.interpolateWith(measurement, measurementWeight);
            filtered = mKalmanFilter.update(mHistoricalScaleData);
            mHistoricalScaleDataTime += TIME_STEP;
        }
        return filtered;
    }

    /**
     * Accepts MotionEvents and dispatches events to a {@link OnScaleGestureListener}
     * when appropriate.
     *
     * <p>Applications should pass a complete and consistent event stream to this method.
     * A complete and consistent event stream involves all MotionEvents from the initial
     * ACTION_DOWN to the final ACTION_UP or ACTION_CANCEL.</p>
     *
     * @param event The event to process
     * @return true if the event was processed and the detector wants to receive the
     *         rest of the MotionEvents in this event stream.
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onTouchEvent(event, 0);
        }

        mCurrTime = event.getEventTime();

        final int action = event.getActionMasked();

        // Forward the event to check for double tap gesture
        if (mQuickScaleEnabled) {
            mGestureDetector.onTouchEvent(event);
        }

        final boolean streamComplete = action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_CANCEL;

        if (action == MotionEvent.ACTION_DOWN || streamComplete) {
            // Reset any scale in progress with the listener.
            // If it's an ACTION_DOWN we're beginning a new event stream.
            // This means the app probably didn't give us all the events. Shame on it.
            if (mInProgress) {
                mListener.onScaleEnd(this);
                mInProgress = false;
                mInitialSpan = 0;
                mDoubleTapMode = DOUBLE_TAP_MODE_NONE;
            } else if (mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS && streamComplete) {
                mInProgress = false;
                mInitialSpan = 0;
                mDoubleTapMode = DOUBLE_TAP_MODE_NONE;
            }

            if (streamComplete) {
                return true;
            }
        }

        final boolean configChanged = action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_UP ||
                action == MotionEvent.ACTION_POINTER_DOWN;

        if (configChanged) {
            mOnTouchScaleData.setFromEvent(event);
            restartProcessingScaleDataFrom(mOnTouchScaleData, event.getEventTime());
        } else if (action == MotionEvent.ACTION_MOVE) {
            ScaleData finalFilteredData = null;
            for (int i = 0; i < event.getHistorySize(); ++i) {
                // Only use history if the data points are far enough from each other
                // and don't use them if they are abnormally far from the main timestamp.
                // This could still be improved further to always use all available data.
                if (event.getHistoricalEventTime(i) < event.getEventTime() - TIME_STEP
                    && event.getHistoricalEventTime(i) > event.getEventTime() - MAX_EVENT_INTERVAL) {
                    mOnTouchScaleData.setFromEventHistory(event, i);
                    ScaleData filteredData = processScaleData(mOnTouchScaleData,
                                                              event.getHistoricalEventTime(i));
                    if (filteredData != null) {
                        finalFilteredData = filteredData;
                    }
                }
            }
            mOnTouchScaleData.setFromEvent(event);
            ScaleData filteredData = processScaleData(mOnTouchScaleData, event.getEventTime());
            if (filteredData != null) {
                finalFilteredData = filteredData;
            }
            if (finalFilteredData != null) {
                mOnTouchScaleData.set(finalFilteredData);
            }
        } else {
            return true;
        }

        final float span = mOnTouchScaleData.getSpan();
        if (mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS) {
            if (event.getY() < mDoubleTapEvent.getY()) {
                mEventBeforeOrAboveStartingGestureEvent = true;
            } else {
                mEventBeforeOrAboveStartingGestureEvent = false;
            }
        }

        // Dispatch begin/end events as needed.
        // If the configuration changes, notify the app to reset its current state by beginning
        // a fresh scale event stream.
        final boolean wasInProgress = mInProgress;
        mCurrScaleData.setFocus(mOnTouchScaleData);
        if (!inDoubleTapMode() && mInProgress && (span < mMinSpan || configChanged)) {
            mListener.onScaleEnd(this);
            mInProgress = false;
            mInitialSpan = span;
            mDoubleTapMode = DOUBLE_TAP_MODE_NONE;
        }
        if (configChanged) {
            mCurrScaleData.setSpan(mOnTouchScaleData);
            mPrevSpanData.set(mOnTouchScaleData);
            mInitialSpan = span;
        }

        final int minSpan = inDoubleTapMode() ? mSpanSlop : mMinSpan;
        if (!mInProgress && span >=  minSpan &&
                (wasInProgress || Math.abs(span - mInitialSpan) > mSpanSlop)) {
            mCurrScaleData.setSpan(mOnTouchScaleData);
            mPrevSpanData.set(mOnTouchScaleData);
            mPrevTime = mCurrTime;
            mInProgress = mListener.onScaleBegin(this);
        }

        // Handle motion; focal point and span/scale factor are changing.
        if (action == MotionEvent.ACTION_MOVE) {
            mCurrScaleData.setSpan(mOnTouchScaleData);

            boolean updatePrev = true;

            if (mInProgress) {
                updatePrev = mListener.onScale(this);
            }

            if (updatePrev) {
                mPrevSpanData.set(mOnTouchScaleData);
                mPrevTime = mCurrTime;
            }
        }

        return true;
    }


    private boolean inDoubleTapMode() {
        return mDoubleTapMode == DOUBLE_TAP_MODE_IN_PROGRESS;
    }

    /**
     * Set whether the associated {@link OnScaleGestureListener} should receive onScale callbacks
     * when the user performs a doubleTap followed by a swipe. Note that this is enabled by default
     * if the app targets API 19 and newer.
     * @param scales true to enable quick scaling, false to disable
     */
    public void setQuickScaleEnabled(boolean scales) {
        mQuickScaleEnabled = scales;
        if (mQuickScaleEnabled && mGestureDetector == null) {
            GestureDetector.SimpleOnGestureListener gestureListener =
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            // Double tap: start watching for a swipe
                            mDoubleTapEvent = e;
                            mDoubleTapMode = DOUBLE_TAP_MODE_IN_PROGRESS;
                            return true;
                        }
                    };
            mGestureDetector = new GestureDetector(mContext, gestureListener, mHandler);
        }
    }

  /**
   * Return whether the quick scale gesture, in which the user performs a double tap followed by a
   * swipe, should perform scaling. {@see #setQuickScaleEnabled(boolean)}.
   */
    public boolean isQuickScaleEnabled() {
        return mQuickScaleEnabled;
    }

    /**
     * Returns {@code true} if a scale gesture is in progress.
     */
    public boolean isInProgress() {
        return mInProgress;
    }

    /**
     * Get the X coordinate of the current gesture's focal point.
     * If a gesture is in progress, the focal point is between
     * each of the pointers forming the gesture.
     *
     * If {@link #isInProgress()} would return false, the result of this
     * function is undefined.
     *
     * @return X coordinate of the focal point in pixels.
     */
    public float getFocusX() {
        return mCurrScaleData.getFocusX();
    }

    /**
     * Get the Y coordinate of the current gesture's focal point.
     * If a gesture is in progress, the focal point is between
     * each of the pointers forming the gesture.
     *
     * If {@link #isInProgress()} would return false, the result of this
     * function is undefined.
     *
     * @return Y coordinate of the focal point in pixels.
     */
    public float getFocusY() {
        return mCurrScaleData.getFocusY();
    }

    /**
     * Return the average distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Distance between pointers in pixels.
     */
    public float getCurrentSpan() {
        return mCurrScaleData.getSpan();
    }

    /**
     * Return the average X distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Distance between pointers in pixels.
     */
    public float getCurrentSpanX() {
        return mCurrScaleData.getSpanX();
    }

    /**
     * Return the average Y distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Distance between pointers in pixels.
     */
    public float getCurrentSpanY() {
        return mCurrScaleData.getSpanY();
    }

    /**
     * Return the previous average distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Previous distance between pointers in pixels.
     */
    public float getPreviousSpan() {
        return mPrevSpanData.getSpan();
    }

    /**
     * Return the previous average X distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Previous distance between pointers in pixels.
     */
    public float getPreviousSpanX() {
        return mPrevSpanData.getSpanX();
    }

    /**
     * Return the previous average Y distance between each of the pointers forming the
     * gesture in progress through the focal point.
     *
     * @return Previous distance between pointers in pixels.
     */
    public float getPreviousSpanY() {
        return mPrevSpanData.getSpanY();
    }

    /**
     * Return the scaling factor from the previous scale event to the current
     * event. This value is defined as
     * ({@link #getCurrentSpan()} / {@link #getPreviousSpan()}).
     *
     * @return The current scaling factor.
     */
    public float getScaleFactor() {
        if (inDoubleTapMode()) {
            // Drag is moving up; the further away from the gesture
            // start, the smaller the span should be, the closer,
            // the larger the span, and therefore the larger the scale
            final boolean scaleUp =
                    (mEventBeforeOrAboveStartingGestureEvent && (mCurrScaleData.getSpan() < mPrevSpanData.getSpan())) ||
                    (!mEventBeforeOrAboveStartingGestureEvent && (mCurrScaleData.getSpan() > mPrevSpanData.getSpan()));
            final float spanDiff = (Math.abs(1 - (mCurrScaleData.getSpan() / mPrevSpanData.getSpan())) * SCALE_FACTOR);
            return mPrevSpanData.getSpan() <= 0 ? 1 : scaleUp ? (1 + spanDiff) : (1 - spanDiff);
        }
        return mPrevSpanData.getSpan() > 0 ? mCurrScaleData.getSpan() / mPrevSpanData.getSpan() : 1;
    }

    /**
     * Return the time difference in milliseconds between the previous
     * accepted scaling event and the current scaling event.
     *
     * @return Time difference since the last scaling event in milliseconds.
     */
    public long getTimeDelta() {
        return mCurrTime - mPrevTime;
    }

    /**
     * Return the event time of the current event being processed.
     *
     * @return Current event time in milliseconds.
     */
    public long getEventTime() {
        return mCurrTime;
    }
}
