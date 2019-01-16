/*
 * Copyright (c) 2012-2014, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia;

import android.util.Log;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.content.ServiceConnection;
import com.nvidia.NvWFDSvc.INvWFDRemoteService;
import com.nvidia.NvWFDSvc.INvWFDServiceListener;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;
import java.util.List;
import android.os.ConditionVariable;
import android.os.UserHandle;
import android.os.ServiceManager;

/**
 * @hide
 *
 */
public class NvWFDManager {
    private static final String TAG = "NvWFDManager";
    private boolean DBG = false;

    private static final int SINKFOUND = 1;
    private static final int CONNECTDONE = 2;
    private static final int DISCONNECT = 3;
    private static final int HANDLEERROR = 4;
    private static final int SETUPCOMPLETE = 5;
    private static final int CANCELCONNECTDONE = 6;
    private static final int NVWFDSERVICEDEATH = 7;

    private static final int POLLTIME = 5000;
    private static final int POLLTRIALS = 5;

    /**
     * Connection established with the NVWFDService
     */
    private NvWFDSvcConnection connWFDSvc;

    /**
     * Callback class given by the NVWFDService
     */
    private INvWFDRemoteService mNvWFDSvc = null;

    /**
    *To know if NvWFDService has died
    */
    private DeathRecipient mDeathRecipient = null;

    private Context mContext;
    private NvwfdListener mListener;
    private ConditionVariable mCondVar;
    private int mConnectionId = -1;
    private IBinder mBinder = null;

    public NvWFDManager(Context context) {
        mContext = context;
        mCondVar = null;
    }

    public void start() {
        if (Integer.parseInt(android.os.SystemProperties.get("nvwfd.debug", "0")) == 1) {
            DBG = true;
        } else {
            DBG = false;
        }
        try {
            if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
                connWFDSvc = new NvWFDSvcConnection();
                Intent intentWFDSvc = new Intent();
                intentWFDSvc.setClassName("com.nvidia.NvWFDSvc", "com.nvidia.NvWFDSvc.NvwfdService");
                if (mContext != null) {
                    mContext.bindService(intentWFDSvc, connWFDSvc, Context.BIND_AUTO_CREATE);
                    mCondVar = new ConditionVariable();
                    mCondVar.block(500);
                }
            } else {
                if (mContext != null) {
                    mBinder = ServiceManager.getService("com.nvidia.NvWFDSvc.NvwfdService");
                    Message msg = new Message();
                    if (mBinder != null) {
                        mNvWFDSvc = INvWFDRemoteService.Stub.asInterface(mBinder);
                        try {
                            mConnectionId = mNvWFDSvc.createConnection(mServiceListener);
                            if(mConnectionId < 0) {
                                 throw new Exception("couldn't connect to service !!");
                            }
                        } catch (Exception ex) {
                            msg.what = SETUPCOMPLETE;
                            msg.arg1 = -1;
                            msgHandler.sendMessage(msg);
                            Log.e(TAG, "Exception when trying to create a connection from start()");
                        }
                        if (mDeathRecipient == null) {
                            mDeathRecipient = new DeathRecipient();
                        }
                        mBinder.linkToDeath(mDeathRecipient, 0);
                    } else {
                        msg.what = NVWFDSERVICEDEATH;
                        msgHandler.sendMessage(msg);
                        Log.e(TAG, "****Could not get the NVWFDService Binder****");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NVWFDService: Failed to establish binding. Error=" + e.getMessage());
        }
    }

    public void startAsync() {
        if (Integer.parseInt(android.os.SystemProperties.get("nvwfd.debug", "0")) == 1) {
            DBG = true;
        } else {
            DBG = false;
        }
        try {
            if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
                connWFDSvc = new NvWFDSvcConnection();
                Intent intentWFDSvc = new Intent();
                intentWFDSvc.setClassName("com.nvidia.NvWFDSvc", "com.nvidia.NvWFDSvc.NvwfdService");
                if (mContext != null) {
                    mContext.bindService(intentWFDSvc, connWFDSvc, Context.BIND_AUTO_CREATE);
                }
            } else {
                start();
            }
        } catch (Exception e) {
            Log.e(TAG, "NVWFDService: Failed to establish binding. Error=" + e.getMessage());
        }
    }

    public void close() {
       mListener = null;

       if (mNvWFDSvc != null) {
           try {
                mNvWFDSvc.closeConnection(mConnectionId);
           } catch (Exception e) {
                if (DBG) Log.d(TAG, e.getMessage());
           }
           mNvWFDSvc = null;
           mConnectionId = -1;
       }

        if (UserHandle.myUserId() == UserHandle.USER_OWNER) {
           try {
               if (connWFDSvc != null) {
                   mContext.unbindService(connWFDSvc);
               }
           } catch (Exception e) {
               // connWFDSvc may have been created, but not bound.
               // Guard against that.
               if (DBG) Log.d(TAG, e.getMessage());
           }

           if (mCondVar != null) {
               mCondVar.close();
               mCondVar = null;
            }
        } else {
            if (mBinder != null && mDeathRecipient != null) {
                mBinder.unlinkToDeath(mDeathRecipient, 0);
                mBinder = null;
                mDeathRecipient = null;
            }
            if (msgHandler != null) {
                msgHandler.removeCallbacksAndMessages(null);
            }
        }
    }

    public boolean isConnected() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.isConnected();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
                return false;
            }
        }
        return false;
    }

    public boolean isConnectionOngoing() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.isConnectionOngoing();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        }
        return false;
    }

    public boolean connect(String sinkSSID, String mAuthentication) {
        if (DBG) Log.d(TAG, "Connect to" + sinkSSID);
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.connect(sinkSSID, mAuthentication);
            } catch (Exception e) {
                Log.e(TAG, "Exception in onService Connected");
                return false;
            }
        }
        return false;
    }

    public void cancelConnect() {
        if (DBG) Log.d(TAG, "cancelConnect");
        if (mNvWFDSvc != null) {
            try {
                mNvWFDSvc.cancelConnect();
            } catch (Exception e) {
                Log.e(TAG, "Exception in onService cancelConnect");
            }
        }
    }

    public void disconnect() {
        if (mNvWFDSvc != null) {
            try {
                mNvWFDSvc.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Exception in onService disConnected");
            }
        }
    }

    public List<String> getRememberedSinkList() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getRememberedSinkList();
            } catch (Exception e) {
                Log.e(TAG, "Exception in getRememberedSinkList");
                return null;
            }
        }
        return null;
    }

    public String getConnectedSinkId() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getConnectedSinkId();
            } catch (Exception e) {
                Log.e(TAG, "Exception in getConnectedSinkId");
                return null;
            }
        }
        return null;
    }

    public boolean isPbcModeSupported(String sinkSSID) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getPbcModeSupport(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "getPbcModeSupport :" + e.toString());
                return false;
            }
        }
        return false;
    }

    public boolean isPinModeSupported(String sinkSSID) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getPinModeSupport(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "getPinModeSupport :" + e.toString());
                return false;
            }
        }
        return false;
    }

    public void discoverSinks() {
        if (mNvWFDSvc != null) {
            try {
                mNvWFDSvc.discoverSinks();
            } catch (Exception e) {
                Log.e(TAG, "Exception in discoverSinks");
            }
        }
    }

    public void stopSinkDiscovery() {
        if (mNvWFDSvc != null) {
            try {
                mNvWFDSvc.stopSinkDiscovery();
            } catch (Exception e) {
                Log.e(TAG, "Exception in stopSinkDiscovery");
            }
        }
    }

    public boolean getSinkAvailabilityStatus(String sinkSSID) {
        if (DBG) Log.d(TAG, "getSinkAvailabilityStatus" + sinkSSID);
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkAvailabilityStatus(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "Exception in getSinkAvailabilityStatus");
                return false;
            }
        }
        return false;
    }

    public String getSinkNickname(String sinkSSID) {
        if (DBG) Log.d(TAG, "getSinkNickname" + sinkSSID);
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkNickname(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "Exception in getSinkNickname");
            }
        }
        return null;
    }

    public String getSinkSSID(String sinkSSID) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkSSID(sinkSSID);
            } catch (Exception e) {
                if (DBG) Log.e(TAG, "Exception in getSinkSSID");
            }
        }
        return null;
    }

    public String getSinkGroupNetwork(String sinkSSID) {
        if (DBG) Log.d(TAG, "getSinkGroupNetwork" + sinkSSID);
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkGroupNetwork(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "Exception in getSinkGroupNetwork");
            }
        }
        return null;
    }

    public List<String> getSinkNetworkGroupList() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkNetworkGroupList();
            } catch (Exception e) {
                Log.e(TAG, "Exception in getSinkNetworkGroupList");
            }
        }
        return null;
    }

    public boolean removeNetworkGroupSink(String networkGroupName) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.removeNetworkGroupSink(networkGroupName);
            } catch (Exception e) {
                Log.e(TAG, "Exception in removeNetworkGroupSink");
            }
        }
        return false;
    }

    public boolean modifySink(String sinkSSID, boolean modify, String nickName) {
        if (DBG) Log.d(TAG, "modifySink" + sinkSSID + "modify:" + modify);
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.modifySink(sinkSSID, modify, nickName);
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "Exception in modifySink");
                return false;
            }
        }
        return false;
    }

    public boolean getSinkBusyStatus(String sinkSSID) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getSinkBusyStatus(sinkSSID);
            } catch (Exception e) {
                Log.e(TAG, "getSinkBusyStatus :" + e.toString());
            }
        }
        return false;
    }

    public boolean setGameModeOption(boolean enable) {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.setGameModeOption(enable);
            } catch (Exception e) {
                Log.e(TAG, "setGameModeOption :" + e.toString());
            }
        }
        return false;
    }

    public boolean getGameModeOption() {
        if (mNvWFDSvc != null) {
            try {
                return mNvWFDSvc.getGameModeOption();
            } catch (Exception e) {
                Log.e(TAG, "getGamemodeOption :" + e.toString());
            }
        }
        return false;
    }

    private INvWFDServiceListener.Stub mServiceListener = new INvWFDServiceListener.Stub() {
        public void onSinksFound(List sinks) {
            Message msg = new Message();
            msg.what = SINKFOUND;
            msg.obj = (Object)sinks;
            msgHandler.sendMessage(msg);
        }
        public void onConnectDone(boolean status) {
            int value = 0;
            if (status == true) {
                if (DBG) Log.d(TAG, "Connection succeeded");
                value = 1;
                msgHandler.obtainMessage(CONNECTDONE, value, 0).sendToTarget();
            } else {
                Log.e(TAG, "Connection failed!!!");
                value = 0;
                msgHandler.obtainMessage(CONNECTDONE, value, 0).sendToTarget();
            }
        }
        public void onDisconnectDone(boolean status) {
            msgHandler.sendEmptyMessage(DISCONNECT);
        }
        public void onNotifyError(String text) {
            Message msg = new Message();
            msg.what = HANDLEERROR;
            msg.obj = (Object)text;
            msgHandler.sendMessage(msg);
        }
        public void onDiscovery(int value) {
            if (DBG) Log.d(TAG, "Discovery status call back arrived value:" + value);
        }
        public void onCancelConnect(int status) {
            if (DBG) Log.d(TAG, "onCancelConnect status:" + ((status==0) ? "failed" : "success"));
            msgHandler.obtainMessage(CANCELCONNECTDONE, status, 0).sendToTarget();
        }
        public void onSetupComplete(int status) {
            if (DBG) Log.d(TAG, "onSetupComplete :" + ((status==0) ? "success" : "failed")
                + " mConnectionId :" + mConnectionId);
            if (mConnectionId >= 0) {
                msgHandler.obtainMessage(SETUPCOMPLETE, status, 0).sendToTarget();
            }
        }
    };

    public interface NvwfdListener {
        public void onSetupComplete(int success);
        public void onSinkFound(List<String> name);
        public void onConnectionDone(int value);
        public void onConnectionDisconnect();
        public void onCancelConnectionDone(int success);
        public void onError(String error);
    };

    public void setListener(NvwfdListener listener) {
        mListener = listener;
    }

    private Handler msgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SETUPCOMPLETE: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast SETUPCOMPLETE");
                    if (mListener != null) {
                        mListener.onSetupComplete((int)msg.arg1);
                    }
                    break;
                }
                case SINKFOUND: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast SINKSFOUND");
                    List<String> sinks = (List<String>)msg.obj;
                    if (mListener != null) {
                        mListener.onSinkFound(sinks);
                    }
                    break;
                }
                case CONNECTDONE: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast CONNECTDONE");
                    int status = (int)msg.arg1;
                    if (mListener != null) {
                        mListener.onConnectionDone(status);
                    }
                    break;
                }
                case DISCONNECT: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast DISCONNECT");
                    if (mListener != null) {
                        mListener.onConnectionDisconnect();
                    }
                    break;
                }
                case CANCELCONNECTDONE: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast CANCELCONNECTDONE");
                    int status = (int)msg.arg1;
                    if (mListener != null) {
                        mListener.onCancelConnectionDone(status);
                    }
                    break;
                }
                case HANDLEERROR: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast HANDLEERROR");
                    String error = (String)msg.obj;
                    if (mListener != null) {
                        mListener.onError(error);
                    }
                    break;
                }
                case NVWFDSERVICEDEATH: {
                    if (DBG) Log.d(TAG, "handleMessage - Miracast NVWFDSERVICEDEATH");
                    try {
                        if (DBG) Log.d(TAG,"No of polling trials = " + msg.arg1);
                        mBinder = ServiceManager.getService("com.nvidia.NvWFDSvc.NvwfdService");
                        if (mBinder == null && (msg.arg1 < POLLTRIALS)) {
                            Message msgSvcDeath = new Message();
                            msgSvcDeath.what = NVWFDSERVICEDEATH;
                            msgSvcDeath.arg1 = msg.arg1 + 1;
                            msgHandler.sendMessageDelayed(msgSvcDeath,POLLTIME);
                        } else if (mBinder != null){
                            if (DBG) Log.d(TAG,"start() to createconnection after service death");
                            start();
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "****Exception in getting NvWFDServiceBinder in msgHandler****");
                    }
                    break;
                }
            }
        }
    };

    /**
     * Sets up callback function into NvwfdService
     */
    class NvWFDSvcConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) Log.d(TAG, "onServiceConnected+ ------------------ ");

            if (service == null) {
                Log.e(TAG, "NVWFDService: Invalid Binder given");
                return;
            }
            // Get service object to interact with the service.
            mNvWFDSvc = INvWFDRemoteService.Stub.asInterface(service);
            if (DBG) Log.d(TAG, "mNvWFDSvc" + mNvWFDSvc);
            try {
                mConnectionId = mNvWFDSvc.createConnection(mServiceListener);
                if (mCondVar != null) {
                    mCondVar.open();
                }
                if(mConnectionId < 0) {
                     throw new Exception("couldn't connect to service !!");
                }
            } catch (Exception e) {
                Message msg = new Message();
                msg.what = SETUPCOMPLETE;
                msg.arg1 = -1;
                msgHandler.sendMessage(msg);
                Log.e(TAG, "Exception in onServiceConnected");
            }
            if (DBG) Log.d(TAG, "onServiceConnected-    ");
        }
        public void onServiceDisconnected(ComponentName name) {
            mNvWFDSvc = null;
            Message msg = new Message();
            msg.what = SETUPCOMPLETE;
            msg.arg1 = -1;
            msgHandler.sendMessage(msg);
            Log.e(TAG, "Couldn't bind to service !!!");
        }
    }

    private class DeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Log.e(TAG, "****NvWFDService Died****");
            NvWFDManager.this.mNvWFDSvc = null;
            NvWFDManager.this.mDeathRecipient = null;
            NvWFDManager.this.mBinder = null;
            Message msg = new Message();
            msg.what = NVWFDSERVICEDEATH;
            msgHandler.sendMessage(msg);
        }
    }
}
