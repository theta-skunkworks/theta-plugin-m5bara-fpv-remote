/**
 * Copyright 2018 Ricoh Company, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.theta360.m5barafpvremote;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.util.Log;
import android.os.Bundle;
import java.io.IOException;
import java.util.List;

import android.view.KeyEvent;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginapplication.task.TakePictureTask;
import com.theta360.pluginapplication.task.TakePictureTask.Callback;
import com.theta360.pluginapplication.task.GetLiveViewTask;
import com.theta360.pluginapplication.task.MjisTimeOutTask;
import com.theta360.pluginapplication.view.MJpegInputStream;
import com.theta360.pluginapplication.oled.Oled;


public class MainActivity extends PluginActivity {
    private static final String TAG = "ExtendedPreview";

    //シリアル通信関連
    private UsbSerialPort port ;
    private boolean mFinished;  //スレッド
    boolean readFlag = false;

    //USBデバイスへのパーミッション付与関連
    PendingIntent mPermissionIntent;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    //Button Resorce
    private boolean onKeyDownModeButton = false;
    private boolean onKeyLongPressWlan = false;
    private boolean onKeyLongPressFn = false;

    //Preview Resorce
    private int previewFormatNo;
    GetLiveViewTask mGetLiveViewTask;
    private byte[]		latestLvFrame;

    //Preview Timeout Resorce
    private static final long FRAME_READ_TIMEOUT_MSEC  = 1000;
    MjisTimeOutTask mTimeOutTask;
    MJpegInputStream mjis;

    //WebServer Resorce
    private Context context;
    private WebServer webServer;

    //OLED Dislay Resorce
    Oled oledDisplay = null;

    private static final int DISP_MODE_BIN = 0;
    private static final int DISP_MODE_EDGE = 1;
    private static final int DISP_MODE_MOTION = 2;
    int dispMode = DISP_MODE_BIN;


    private TakePictureTask.Callback mTakePictureTaskCallback = new Callback() {
        @Override
        public void onTakePicture(String fileUrl) {
            startPreview(mGetLiveViewTaskCallback, previewFormatNo);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Set enable to close by pluginlibrary, If you set false, please call close() after finishing your end processing.
        setAutoClose(true);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        //init OLED
        oledDisplay = new Oled(getApplicationContext());
        oledDisplay.brightness(100);
        oledDisplay.clear(oledDisplay.black);
        oledDisplay.draw();

        // Set a callback when a button operation event is acquired.
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int keyCode, KeyEvent event) {
                switch (keyCode) {
                    case KeyReceiver.KEYCODE_CAMERA :
                        stopPreview();
                        new TakePictureTask(mTakePictureTaskCallback).execute();
                        break;
                    case KeyReceiver.KEYCODE_MEDIA_RECORD :
                        // Disable onKeyUp of startup operation.
                        onKeyDownModeButton = true;
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent event) {

                switch (keyCode) {
                    case KeyReceiver.KEYCODE_WLAN_ON_OFF :
                        if (onKeyLongPressWlan) {
                            onKeyLongPressWlan=false;
                        } else {

                            dispMode++;
                            if ( dispMode > DISP_MODE_MOTION ) {
                                dispMode= DISP_MODE_BIN;
                            }

                        }

                        break;
                    case KeyReceiver.KEYCODE_MEDIA_RECORD :
                        if (onKeyDownModeButton) {
                            if (mGetLiveViewTask!=null) {
                                stopPreview();
                            } else {
                                startPreview(mGetLiveViewTaskCallback, previewFormatNo);
                            }
                            onKeyDownModeButton = false;
                        }
                        break;
                    case KeyEvent.KEYCODE_FUNCTION :
                        if (onKeyLongPressFn) {
                            onKeyLongPressFn=false;
                        } else {

                            //NOP : KEYCODE_FUNCTION

                        }

                        break;
                    default:
                        break;
                }

            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {
                switch (keyCode) {
                    case KeyReceiver.KEYCODE_WLAN_ON_OFF:
                        onKeyLongPressWlan=true;

                        //NOP : KEYCODE_WLAN_ON_OFF

                        break;
                    case KeyEvent.KEYCODE_FUNCTION :
                        onKeyLongPressFn=true;

                        //NOP : KEYCODE_FUNCTION

                        break;
                    default:
                        break;
                }

            }
        });

        this.context = getApplicationContext();
        this.webServer = new WebServer(this.context, mWebServerCallback);
        try {
            this.webServer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isApConnected()) {

        }
        //WlanをAPモードから開始する
        notificationWlanAp();

        //Start LivePreview
        previewFormatNo = GetLiveViewTask.FORMAT_NO_640_30FPS;
        startPreview(mGetLiveViewTaskCallback, previewFormatNo);


        //USBシリアル関連
        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> usb = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (usb.isEmpty()) {
            int usb_num = usb.size();
            Log.d(TAG,"usb num =" + usb_num  );
            Log.d(TAG,"usb device is not connect."  );
        } else {
            // デバッグのため認識したデバイス数をしらべておく
            int usb_num = usb.size();
            Log.d(TAG,"usb num =" + usb_num  );

            // Open a connection to the first available driver.
            UsbSerialDriver driver = usb.get(0);

            //USBデバイスへのパーミッション付与用（機器を刺したときスルーしてもアプリ起動時にチャンスを与えるだけ。なくても良い。）
            mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            manager.requestPermission( driver.getDevice() , mPermissionIntent);
            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
            if (connection == null) {
                // You probably need to call UsbManager.requestPermission(driver.getDevice(), ..)
                // パーミッションを与えた後でも、USB機器が接続されたままの電源Off->On だとnullになる... 刺しなおせばOK
                Log.d(TAG,"M:Can't open usb device.\n");

                port = null;
            } else {
                port = driver.getPorts().get(0);

                try {
                    port.open(connection);
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    port.setDTR(true); // for arduino(ATmega32U4)
                    port.setRTS(true); // for arduino(ATmega32U4)

                    port.purgeHwBuffers(true,true);//念のため
                    Log.d(TAG,"CD  - Carrier Detect     =" + String.valueOf( port.getCD() ) );
                    Log.d(TAG,"CTS - Clear To Send      =" + String.valueOf( port.getCTS() ) );
                    Log.d(TAG,"DSR - Data Set Ready     =" + String.valueOf( port.getDSR() ) );
                    Log.d(TAG,"DTR - Data Terminal Ready=" + String.valueOf( port.getDTR() ) );
                    Log.d(TAG,"RI  - Ring Indicator     =" + String.valueOf( port.getRI() ) );
                    Log.d(TAG,"RTS - Request To Send    =" + String.valueOf( port.getRTS() ) );

                } catch (IOException e) {
                    // Deal with error.
                    e.printStackTrace();
                    Log.d(TAG, "M:IOException");
                    //return;
                }

            }

        }

        //Start thread
        mFinished = false;
        mainThread();

    }

    @Override
    protected void onPause() {
        // Do end processing
        //close();

        //Stop LivePreview
        stopPreview();

        //Stop OLED thread
        mFinished = true;

        //シリアル通信の後片付け ポート開けてない場合にはCloseしないこと
        if (port != null) {
            try {
                port.close();
                Log.d(TAG, "M:onDestroy() port.close()");
            } catch (IOException e) {
                Log.d(TAG, "M:onDestroy() IOException");
            }
        } else {
            Log.d(TAG, "M:port=null\n");
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (this.webServer != null) {
            this.webServer.stop();
        }
    }

    private void startPreview(GetLiveViewTask.Callback callback, int formatNo){
        if (mGetLiveViewTask!=null) {
            stopPreview();

            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mGetLiveViewTask = new GetLiveViewTask(callback, formatNo);
        mGetLiveViewTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void stopPreview(){
        //At the intended stop, timeout monitoring also stops.
        if (mTimeOutTask!=null) {
            mTimeOutTask.cancel(false);
            mTimeOutTask=null;
        }

        if (mGetLiveViewTask!=null) {
            mGetLiveViewTask.cancel(false);
            mGetLiveViewTask = null;
        }
    }


    /**
     * GetLiveViewTask Callback.
     */
    private GetLiveViewTask.Callback mGetLiveViewTaskCallback = new GetLiveViewTask.Callback() {

        @Override
        public void onGetResorce(MJpegInputStream inMjis) {
            mjis = inMjis;
        }

        @Override
        public void onLivePreviewFrame(byte[] previewByteArray) {
            latestLvFrame = previewByteArray;

            //Update timeout monitor
            if (mTimeOutTask!=null) {
                mTimeOutTask.cancel(false);
                mTimeOutTask=null;
            }
            mTimeOutTask = new MjisTimeOutTask(mMjisTimeOutTaskCallback, FRAME_READ_TIMEOUT_MSEC);
            mTimeOutTask.execute();
        }

        @Override
        public void onCancelled(Boolean inTimeoutOccurred) {
            mGetLiveViewTask = null;
            latestLvFrame = null;

            if (inTimeoutOccurred) {
                startPreview(mGetLiveViewTaskCallback, previewFormatNo);
            }
        }

    };


    /**
     * MjisTimeOutTask Callback.
     */
    private MjisTimeOutTask.Callback mMjisTimeOutTaskCallback = new MjisTimeOutTask.Callback() {
        @Override
        public void onTimeoutExec(){
            if (mjis!=null) {
                try {
                    // Force an IOException to `mjis.readMJpegFrame()' in GetLiveViewTask()
                    mjis.close();
                } catch (IOException e) {
                    Log.d(TAG, "[timeout] mjis.close() IOException");
                    e.printStackTrace();
                }
                mjis=null;
            }
        }
    };

    /**
     * WebServer Callback.
     */
    private WebServer.Callback mWebServerCallback = new WebServer.Callback() {
        @Override
        public void execStartPreview(int format) {
            previewFormatNo = format;
            startPreview(mGetLiveViewTaskCallback, format);
        }

        @Override
        public void execStopPreview() {
            stopPreview();
        }

        @Override
        public boolean execGetPreviewStat() {
            if (mGetLiveViewTask==null) {
                return false;
            } else {
                return true;
            }
        }

        @Override
        public byte[] getLatestFrame() {
            return latestLvFrame;
        }

        @Override
        public void execCtrlBala(int inCmdNo){
            Log.d(TAG, "execCtrlBala() : inCmdNo=" + String.valueOf(inCmdNo) );
            moveCommandNo = inCmdNo;
            sendReq = true;
        }

    };

    //==============================================================
    // Main Thread
    //==============================================================
    public void mainThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int outFps=0;
                long startTime = System.currentTimeMillis();

                while (mFinished == false) {

                    if (port!=null) {
                        readUsbSerial();
                        writeUsbSerial();
                    }
                    outFps++;

                    try {
                        Thread.sleep(33);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    long curTime = System.currentTimeMillis();
                    long diffTime = curTime - startTime;
                    if (diffTime >= 1000 ) {
                        Log.d(TAG, "[Thread]" + String.valueOf(outFps) + "[fps]" );
                        startTime = curTime;
                        outFps =0;
                    }

                }
            }
        }).start();
    }

    private void readUsbSerial(){
        // ProMicro(ATmega32U4)は 受信していないときに read()をすると戻ってきません。
        if ( readFlag ) {
            readFlag = false;

            byte buff[] = new byte[256];
            int num=0;
            try {
                num= port.read(buff, buff.length);
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "T:read() IOException");
            }

            if ( num > 0 ) {
                String rcvStr = new String(buff, 0, num);
                rcvStr = rcvStr.trim();
                Log.d(TAG, "len=" + rcvStr.length() + ", RcvDat=[" + rcvStr + "]" );
            }
        }
    }

    boolean sendReq = false;
    int moveCommandNo = 0;

    private static final String BALA_STOP = "stop\n";
    private static final String BALA_FORWARD = "move 128 1000\n";
    private static final String BALA_BACK     = "move -128 1000\n";

    private static final String BALA_TURN_R   = "turn 128 200\n";   //未使用
    private static final String BALA_TURN_L   = "turn -128 200\n";  //未使用

    private static final String BALA_TURN_RF   = "turn0 128 200\n";
    private static final String BALA_TURN_RB   = "turn0 -128 200\n";
    private static final String BALA_TURN_LF   = "turn1 128 200\n";
    private static final String BALA_TURN_LB   = "turn1 -128 200\n";

    private static final String BALA_ROTATE_R = "rotate 128 800\n";
    private static final String BALA_ROTATE_L = "rotate -128 800\n";

    private void writeUsbSerial(){
        if (sendReq == true) {
            sendReq = false;

            byte[] sendBytes;
            int sendTimeout = 0;

            switch (moveCommandNo) {
                case 1 :
                    Log.d(TAG, BALA_FORWARD);
                    sendBytes = BALA_FORWARD.getBytes();
                    sendTimeout = BALA_FORWARD.length();
                    break;
                case 2 :
                    Log.d(TAG, BALA_BACK);
                    sendBytes = BALA_BACK.getBytes();
                    sendTimeout = BALA_BACK.length();
                    break;
                case 3 :
                    Log.d(TAG, BALA_TURN_RF);
                    sendBytes = BALA_TURN_RF.getBytes();
                    sendTimeout = BALA_TURN_RF.length();
                    break;
                case 4 :
                    Log.d(TAG, BALA_TURN_LF);
                    sendBytes = BALA_TURN_LF.getBytes();
                    sendTimeout = BALA_TURN_LF.length();
                    break;
                case 5 :
                    Log.d(TAG, BALA_ROTATE_R);
                    sendBytes = BALA_ROTATE_R.getBytes();
                    sendTimeout = BALA_ROTATE_R.length();
                    break;
                case 6 :
                    Log.d(TAG, BALA_ROTATE_L);
                    sendBytes = BALA_ROTATE_L.getBytes();
                    sendTimeout = BALA_ROTATE_L.length();
                    break;
                case 7 :
                    Log.d(TAG, BALA_TURN_RB);
                    sendBytes = BALA_TURN_RB.getBytes();
                    sendTimeout = BALA_TURN_RB.length();
                    break;
                case 8 :
                    Log.d(TAG, BALA_TURN_LB);
                    sendBytes = BALA_TURN_LB.getBytes();
                    sendTimeout = BALA_TURN_LB.length();
                    break;
                default:
                    Log.d(TAG, BALA_STOP);
                    sendBytes = BALA_STOP.getBytes();
                    sendTimeout = BALA_STOP.length();
                    break;
            }

            try {
                port.write( sendBytes, sendTimeout );
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "T:read() IOException");
            }

            readFlag = true;
        }
    }

}

