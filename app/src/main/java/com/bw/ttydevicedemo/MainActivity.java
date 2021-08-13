package com.bw.ttydevicedemo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.odm.OdmUtil;
import com.odm.tty.TtyDevice;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class MainActivity extends Activity implements View.OnClickListener {

    private final static String TAG = "TtyDevice";
    private Button mOpen,mClose,mRead,mWrite,mSet,mCalibration;
    private TextView mTemperature, mResult, mEnvironmentTemperature;
    private EditText mAddress,mCmd,mAlarmTemperature;
    private TtyDevice mTtyDev;

    private boolean mOpened = false;

    private static final String CALIBRATION_COMMAND = "60000000000a";
    private static final byte CALIBRATION_SUCCEED = 0x72;
    private static final byte CALIBRATION_EXCEED = 0x73;
    private static final byte CALIBRATION_FAILED = 0x74;

    private static final String path = "/dev/ttyHSL1";

    private final int DELAY_TIME = 1 * 1 * 1000; // 1000ms为基准，此处为1min
    Runnable delayExecuteRunnable = new Runnable() {
        @Override
        public void run() {
            // execute codes.
            open();
            OdmUtil.delayms(500);
            float temperature = readCurrentTemperature();
            Log.d(TAG, "temperature = " + temperature + ", alarmLine = " + alarmLine);
            if (temperature - alarmLine > 0.0001) {
                alarm();
            } else {
                cancel();
            }
            OdmUtil.delayms(100);
            close();
            mHandler.postDelayed(delayExecuteRunnable, DELAY_TIME);
        }
    };
    private Handler mHandler; // define handler

    private float alarmLine = 70.0f;
    NotificationManager notificationManager;
    Notification notification;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();

    }

    private void init() {

        mOpen = (Button) findViewById(R.id.open);
        mClose = (Button) findViewById(R.id.close);
        mRead = (Button) findViewById(R.id.read);
        mWrite = (Button) findViewById(R.id.write);
        mSet = findViewById(R.id.set);
        mCalibration = findViewById(R.id.calibration);
        mAddress = (EditText) findViewById(R.id.address);
        mCmd = (EditText) findViewById(R.id.cmd);
        mTemperature = (TextView) findViewById(R.id.temperature);
        mEnvironmentTemperature = findViewById(R.id.env_temp_value);
        mAlarmTemperature = findViewById(R.id.alarm_temperature);
        mResult = findViewById(R.id.result);

        mOpen.setOnClickListener(this);
        mClose.setOnClickListener(this);
        mRead.setOnClickListener(this);
        mWrite.setOnClickListener(this);
        mSet.setOnClickListener(this);
        mCalibration.setOnClickListener(this);
        mAddress.setSelection(mAddress.getText().length());

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = null;

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String id = "channel_alarm";
            String description = "alarm";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            channel = new NotificationChannel(id, description, importance);
            channel.setVibrationPattern(new long[]{0,1000,1000,1000});
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            notificationManager.createNotificationChannel(channel);
            notification = new Notification.Builder(getApplicationContext(), id)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();

        } else {
            notification = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build();
        }

        mHandler = new Handler(); // define handler
    }

    @Override
    protected void onStart() {
        super.onStart();
        mHandler.postDelayed(delayExecuteRunnable, DELAY_TIME); // start execute
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(delayExecuteRunnable); //cancel execute
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        switch (id) {
            case R.id.open:
                open();
                break;
            case R.id.close:
                close();
                break;
            case R.id.read:
                open();
                OdmUtil.delayms(500);
                readCurrentTemperature();
                OdmUtil.delayms(100);
                close();
                break;
            case R.id.write:
                open();
                OdmUtil.delayms(300);
                write();
                OdmUtil.delayms(500);
                close();
                break;
            case R.id.set:
                synchronized (this) {
                    if (!"".equals(mAlarmTemperature.getText().toString())) {
                        alarmLine = Float.parseFloat(mAlarmTemperature.getText().toString());
                    }
                }
                break;
            case R.id.calibration:
                open();
                OdmUtil.delayms(300);
                sendCommand(CALIBRATION_COMMAND);
                OdmUtil.delayms(100);
                byte[] readBuf = new byte[32];
                //read(readBuf);
                OdmUtil.delayms(500);
                read(readBuf);
                for (byte b : readBuf) {
                    if (b == CALIBRATION_SUCCEED) {
                        Log.d(TAG, "calibration succeed");
                        mResult.setText(R.string.calibration_succeed);
                    } else if (b == CALIBRATION_EXCEED) {
                        Log.d(TAG, "calibration exceed");
                        mResult.setText(R.string.calibration_failed);
                    } else if (b == CALIBRATION_FAILED) {
                        Log.d(TAG, "calibration failed");
                        mResult.setText(R.string.calibration_failed);
                    } else {
                        Log.d(TAG, "calibration no respond");
                        mResult.setText(R.string.calibration_failed);
                    }
                }
                close();
                break;
            default:
                break;
        }
    }

    private void open() {
        //String ttyDev = mAddress.getText().toString();
        String ttyDev = path;
        mTtyDev = new TtyDevice(ttyDev);
        int fd = mTtyDev.ttyOpen();
        Log.d(TAG, "fd = " + fd);
        int ret = -1;
        if (fd > 0) {
            /**
             * 初始化 tty 设备
             * @param speed 波特率 {@link #TTY_B38400} {@link #TTY_B115200} {@link #TTY_B921600} ...
             * @param dataBits 数据位 {@link #TTY_CS5} {@link #TTY_CS6} {@link #TTY_CS7} {@link #TTY_CS8}
             * @param stopBits 停止位 {@link #TTY_STOPB_1} {@link #TTY_STOPB_2}
             * @param parity 奇偶校验 {@link #TTY_PAR_NONE} {@link #TTY_PAR_EVEN} {@link #TTY_PAR_ODD}
             * @param readBlock 阻塞方式，true 开启，false 关闭
             * @param rtscts 硬件流控，true 开启，false 关闭
             * @return 0 初始化成功，其它值为初始化失败
             */
            ret = mTtyDev.ttyInit(TtyDevice.TTY_B9600,TtyDevice.DEF_DATABITS,TtyDevice.DEF_STOPBITS,
                    TtyDevice.DEF_PARITY,TtyDevice.DEF_READ_BLOCK);
            mOpened = true;
        }
        //Toast.makeText(this,
        //        "ret = " + ret + "; " + getString(R.string.open) + " " + (ret == 0 ? "success":"failed"),
        //        Toast.LENGTH_SHORT).show();
    }

    private void close() {
        int ret = -1;

        if (mOpened) {
            ret = mTtyDev.ttyClose();
            mTtyDev = null;
            mOpened = false;
        }
        //Toast.makeText(this,
        //        "ret = " + ret + "; " + getString(R.string.close) + " " + (ret == 0 ? "success":"failed"),
        //        Toast.LENGTH_SHORT).show();
    }

    private float readCurrentTemperature() {
        String prop = "waiting";
        BufferedReader reader = null;
        float temperature = 0.0f;
        try {
            reader = new BufferedReader(new FileReader(path));
            while((prop = reader.readLine()) != null) {
                if (prop.startsWith("T")) {
                    String str1=prop.substring(0, prop.indexOf(":"));
                    String str2=prop.substring(str1.length()+1, prop.length());
                    Log.d(TAG, "current temperature = " + str2);
                    mTemperature.setText(str2);
                    temperature = Float.parseFloat(str2);
                }
                if (prop.startsWith("A")) {
                    String str1 = prop.substring(0, prop.indexOf(":"));
                    String str2=prop.substring(str1.length()+1, prop.length());
                    Log.d(TAG, "environment temperature = " + str2);
                    mEnvironmentTemperature.setText(str2);
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return temperature;
    }

    private float readEnvironmentTemperature() {
        String prop = "waiting";
        BufferedReader reader = null;
        float temperature = 0.0f;
        try {
            reader = new BufferedReader(new FileReader(path));
            while((prop = reader.readLine()) != null) {
                if (prop.startsWith("A")) {
                    String str1=prop.substring(0, prop.indexOf(":"));
                    String str2=prop.substring(str1.length()+1, prop.length());
                    Log.d(TAG, "environment temperature = " + str2);
                    temperature = Float.parseFloat(str2);
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return temperature;
    }

    private void read() {
        byte[] readBuf = new byte[1024];
        read(readBuf);
    }

    public void read(byte[] readBuf) {
        read(readBuf, 0, readBuf.length);
    }

    public void read(byte[] readBuf, int bufLen) {
        read(readBuf, 0, bufLen);
    }

    public void read(byte[] readBuf, int offset, int bufLen) {
        int ret = -1;
        // for test {
        //sendCommand(TEST_COMMAND);
        //OdmUtil.delayms(100);
        // for test }
        try {
            //Log.d(TAG,"readBuf1 = " + Arrays.toString(readBuf));
            ret = mTtyDev.ttyRead(readBuf, offset, bufLen);
            Log.d(TAG, "ret = " + ret);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this,"Please open", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG,"readBuf2 = " + Arrays.toString(readBuf));
        Log.d(TAG,"OdmUtil.bytesToHexString(readBuf2) = " + OdmUtil.bytesToHexString(readBuf));
        //if (ret > 0) {
        //    mResult.setText(OdmUtil.bytesToHexString(readBuf, ret));
        //}
    }

    private void write() {
        String cmd = mCmd.getText().toString();
        if (!checkCmd(cmd)) {
            Toast.makeText(this,getString(R.string.wrong_cmd), Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG,"cmd = " + cmd);
        write(cmd.getBytes());
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int dataLen) {
        write(data, 0, dataLen);
    }

    public void write(byte[] data, int offset, int dataLen){
        int ret = -1;
        try {
            ret = mTtyDev.ttyWrite(data, offset, dataLen);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this,"Please open", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this,
                "ret = " + ret + "; " + getString(R.string.write) + " " + (ret >= 0 ? "success":"failed"),
                Toast.LENGTH_SHORT).show();
    }

    private boolean checkCmd(String cmd) {
        for (int i = 0; i < cmd.length(); i++ ){
            if ("0123456789abcdef".contains(String.valueOf(cmd.charAt(i)))) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    private void writeFile(String filename,String value)  {
        try  {
            FileWriter writer=new FileWriter(filename);
            writer.write(value);
            writer.close();
        } catch (IOException e)  {
            Log.e(TAG, "IOException caught while writting stream", e);
        }
    }

    private int sendCommand(String hexCmd) {
        return sendCommand(OdmUtil.hexStringToBytes(hexCmd));
    }

    private int sendCommand(byte[] cmd) {
        int writeRet = 0;

        writeRet = mTtyDev.ttyWrite(cmd);
        if (writeRet == cmd.length) {
            Toast.makeText(this, "Send: " + OdmUtil.bytesToHexString(cmd) + "\n", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Send failed!" + "\n", Toast.LENGTH_SHORT).show();
        }

        return writeRet;
    }

    private void alarm() {
        Log.d(TAG, "alarm");
        notificationManager.notify(23, notification);
        setAlarmLedState(true);
    }

    private void cancel() {
        Log.d(TAG, "cancel");
        notificationManager.cancel(23);
        setAlarmLedState(false);
    }

    private void setAlarmLedState(boolean state) {
        Intent intent = new Intent("com.intent.action.TURN_ON_LIGHT");
        intent.putExtra("on", state);
        intent.addFlags(0x01000000);
        sendBroadcast(intent);
    }

}
