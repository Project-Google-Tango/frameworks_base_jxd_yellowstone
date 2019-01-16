/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2013 NVIDIA CORPORATION.  All rights reserved.
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

/**
 * Test testing WebView memory trimming.
 */

package com.android.webviewtests;

import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.ComponentCallbacks2;
import junit.framework.Assert;

public class OnTrimTest extends ActivityInstrumentationTestCase2<WebViewStubActivity> {
    protected class TestWebViewClient extends WebViewClient {
        private boolean mIsPageFinished;
        @Override
        public synchronized void onPageFinished(WebView webView, String url) {
            mIsPageFinished = true;
            notify();
        }
        public synchronized void waitForOnPageFinished() throws RuntimeException {
            while (!mIsPageFinished) {
                try {
                    wait(5000);
                } catch (Exception e) {
                    continue;
                }
                if (!mIsPageFinished) {
                    throw new RuntimeException("Timed out waiting for onPageFinished()");
                }
            }
            mIsPageFinished = false;
        }
    }

    protected TestWebViewClient mWebViewClient = new TestWebViewClient();

    public OnTrimTest() {
        super("com.android.webviewtests", WebViewStubActivity.class);
    }

    public void testOnTrim() throws Throwable {
        final WebViewStubActivity activity = getActivity();

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                WebView webView = activity.getWebView();
                webView.setWebViewClient(mWebViewClient);
                webView.loadData("<!DOCTYPE html><title>Test onTrimMemory</title><body>test onTrimMemory</body></html>", "text/html", null);
            }
        });

        mWebViewClient.waitForOnPageFinished();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.getApplication().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                // Should not segfault nor ASSERT.
                activity.getApplication().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
            }
        });

        // The test is a success if it did not crash.
        assertTrue(true);
    }
}
