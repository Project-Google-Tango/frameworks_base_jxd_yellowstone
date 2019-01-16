/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wm;

import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.JoystickEventListener;

import com.android.server.UiThread;

import java.util.ArrayList;

public class JoystickEventDispatcher extends InputEventReceiver {
    ArrayList<JoystickEventListener> mListeners = new ArrayList<JoystickEventListener>();
    JoystickEventListener[] mListenersArray = new JoystickEventListener[0];

    public JoystickEventDispatcher(InputChannel inputChannel) {
        super(inputChannel, UiThread.getHandler().getLooper());
    }

    @Override
    public void onInputEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent
                    && (event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                final MotionEvent motionEvent = (MotionEvent)event;
                JoystickEventListener[] listeners;
                synchronized (mListeners) {
                    if (mListenersArray == null) {
                        mListenersArray = new JoystickEventListener[mListeners.size()];
                        mListeners.toArray(mListenersArray);
                    }
                    listeners = mListenersArray;
                }
                for (int i = 0; i < listeners.length; ++i) {
                    listeners[i].onJoystickEvent(motionEvent);
                }
            } else if (event instanceof KeyEvent
                    && ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)) {
                final KeyEvent keyEvent = (KeyEvent)event;
                JoystickEventListener[] listeners;
                synchronized (mListeners) {
                    if (mListenersArray == null) {
                        mListenersArray = new JoystickEventListener[mListeners.size()];
                        mListeners.toArray(mListenersArray);
                    }
                    listeners = mListenersArray;
                }
                for (int i = 0; i < listeners.length; ++i) {
                    listeners[i].onGamepadEvent(keyEvent);
                }
            }
        } finally {
            finishInputEvent(event, false);
        }
    }

    /**
     * Add the specified listener to the list.
     * @param listener The listener to add.
     */
    public void registerInputEventListener(JoystickEventListener listener) {
        synchronized (mListeners) {
            if (mListeners.contains(listener)) {
                throw new IllegalStateException("registerInputEventListener: trying to register" +
                        listener + " twice.");
            }
            mListeners.add(listener);
            mListenersArray = null;
        }
    }

    /**
     * Remove the specified listener from the list.
     * @param listener The listener to remove.
     */
    public void unregisterInputEventListener(JoystickEventListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                throw new IllegalStateException("registerInputEventListener: " + listener +
                        " not registered.");
            }
            mListeners.remove(listener);
            mListenersArray = null;
        }
    }
}
