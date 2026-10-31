package com.hearingstudio.demo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.PowerManager;

import java.util.Arrays;

/**
 * User-initiated, ongoing microphone foreground service.
 * This is an experimental audio monitor, NOT a medical hearing aid.
 * Does not store or upload microphone data.
 */
public final class HearingService extends Service {
    public static final String ACTION_START="com.hearingstudio.demo.START";
    public static final String ACTION_STOP="com.hearingstudio.demo.STOP";
    private static final String CHANNEL_ID="hearing_microphone";
    private static final int NOTIFICATION_ID=406;
    private static final int SAMPLE_RATE=48000;
    private static final int FRAME_SIZE=240;
    private static final int[] EQ_FREQ={250,500,1000,2000,4000};

    public static volatile boolean active=false;
    public static volatile String status="Остановлено";
    public static volatile Profile profile=new Profile(null,null,null);
    private volatile boolean capture=false;
    private Thread audioThread;
    private volatile AudioRecord currentRecorder;
    private AudioManager manager;
    private PowerManager.WakeLock wakeLock;

    public static final class Profile {
        public final int inputMode;
        public final float[] levels;
        public final int[][] gain;
        public final SceneProcessor.Settings scene;
        public Profile(int inputMode, float[] levels, int[][] gains, int[] sceneControls) {
            this.inputMode=inputMode;
            this.levels=levels==null?new float[]{0.25f,0.25f}:levels.clone();
            this.gain=new int[2][5];
            if(gains!=null)for(int ear=0;ear<2;ear++)
                System.arraycopy(gains[ear],0,this.gain[ear],0,5);
            int[] sc=sceneControls==null?new int[]{45,65,35,50}:sceneControls;
            this.scene=new SceneProcessor.Settings(sc[0],sc[1],sc[2],sc[3]);
        }
        // Initial profile has inputMode 0.
        private Profile(float[] levels,int[][] gains,int[] controls){
            this(0,levels,gains,controls);
        }
    }
    public static void updateProfile(Profile p) {
        if (p!=null) profile=p;
    }
    @Override public void onCreate() {
        super.onCreate();
        manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID,"Обработка звука",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent) {return null;}
    private Notification notification() {
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,HearingService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent=PendingIntent.getService(this,1,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hearing Studio — микрофон работает")
            .setContentText("Обработка звука продолжается в фоне")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",stopIntent)
            .build();
    }
    @Override public synchronized int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?null:intent.getAction();
        if(ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if(active) return START_NOT_STICKY;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status="Нет доступа к микрофону";stopSelf();return START_NOT_STICKY;
        }
        try {
            // Must be started while MainActivity is visible (while-in-use permission).
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION_ID,notification());
            active=true;capture=true;status="Подключение звука…";
            final int selectedInput=profile.inputMode;
            audioThread=new Thread(()->runAudio(selectedInput),"hearing-audio");
            audioThread.start();
        } catch(Exception error) {
            capture=false;active=false;
            status="Запуск службы: "+error.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY; // Never resume the mic automatically after process death.
    }
    private void stopCapture(){
        capture=false;
        AudioRecord recorder=currentRecorder;
        if(recorder!=null)try{recorder.stop();}catch(Exception ignored){}
        // Preserve audio-routing errors so the user can report them.
        if(!status.startsWith("Ошибка")&&!status.startsWith("Нет доступа")&&
           !status.startsWith("Запуск службы"))
            status="Остановлено";
    }
    @Override public void onDestroy(){
        stopCapture();
        super.onDestroy();
    }
    private static short pcm(double sample) {
        if(!Double.isFinite(sample))return 0;
        double amplitude=Math.abs(sample);
        if(amplitude>0.42)
            amplitude=0.42+0.08*(1-Math.exp(-(amplitude-0.42)/0.08));
        return (short)Math.round(Math.copySign(Math.min(0.5,amplitude),sample)*32767);
    }
    private void runAudio(int inputMode){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord record=null;
        AudioTrack track=null;
        boolean bluetooth=inputMode==1;
        SceneProcessor processor=new SceneProcessor(SAMPLE_RATE);
        Profile previousProfile=null;
        Biquad[][] filters=new Biquad[2][5];
        try {
            PowerManager power=(PowerManager)getSystemService(Context.POWER_SERVICE);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hearingstudio:audio");
            wakeLock.acquire(); // released in finally; allows audio when screen is off
            if(bluetooth) {
                if(Build.VERSION.SDK_INT<31)
                    throw new IllegalStateException("Android 12+ нужен для Bluetooth микрофона");
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                AudioDeviceInfo selected=null;
                for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices()){
                    if(device.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        ||device.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET){
                        selected=device;break;
                    }
                }
                if(selected==null)
                    throw new IllegalStateException("Bluetooth микрофон не найден");
                if(!manager.setCommunicationDevice(selected))
                    throw new IllegalStateException("Не удалось переключить Bluetooth");
                Thread.sleep(650);
            }
            if(!capture)return;
            int channels=bluetooth?1:2;
            int mask=bluetooth?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
            int inputMin=AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            int outputMin=AudioTrack.getMinBufferSize(SAMPLE_RATE,
                mask,AudioFormat.ENCODING_PCM_16BIT);
            if(inputMin<=0||outputMin<=0)
                throw new IllegalStateException("PCM 48 кГц недоступен");
            record=new AudioRecord(bluetooth?
                MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(inputMin,FRAME_SIZE*4));
            currentRecorder=record;
            AudioFormat fmt=new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            AudioAttributes attr=new AudioAttributes.Builder()
                .setUsage(bluetooth?AudioAttributes.USAGE_VOICE_COMMUNICATION:
                    AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            track=new AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(outputMin,FRAME_SIZE*channels*4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED
                ||track.getState()!=AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Не удалось открыть аудиоустройство");
            if(!bluetooth) {
                for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                    if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){
                        record.setPreferredDevice(device);break;
                    }
            }
            if(!capture)return;
            record.startRecording();
            track.play();
            AudioDeviceInfo src=record.getRoutedDevice();
            AudioDeviceInfo dst=track.getRoutedDevice();
            status="Вход: "+(src==null?"?":src.getProductName())
                +" | Выход: "+(dst==null?"?":dst.getProductName());
            short[] input=new short[FRAME_SIZE];
            short[] output=new short[FRAME_SIZE*channels];
            while(capture){
                int n=record.read(input,0,input.length);
                if(!capture)break;
                if(n<0)throw new IllegalStateException("Ошибка чтения: "+n);
                if(n==0)continue;
                Profile p=profile;
                if(p!=previousProfile){
                    // EQ instances are recreated on UI changes, not per frame.
                    for(int ear=0;ear<2;ear++)
                        for(int band=0;band<5;band++)
                            filters[ear][band]=new Biquad(EQ_FREQ[band],p.gain[ear][band]);
                    processor.setSettings(p.scene);
                    previousProfile=p;
                }
                for(int i=0;i<n;i++) {
                    double mono=processor.process(input[i]/32768.0);
                    double left=mono,right=mono;
                    for(int band=0;band<5;band++) {
                        left=filters[0][band].run(left);
                        right=filters[1][band].run(right);
                    }
                    if(bluetooth)
                        output[i]=pcm((left*p.levels[0]+right*p.levels[1])*0.5);
                    else {
                        output[2*i]=pcm(left*p.levels[0]);
                        output[2*i+1]=pcm(right*p.levels[1]);
                    }
                }
                int pos=0,total=n*channels;
                while(capture&&pos<total){
                    int written=track.write(output,pos,total-pos);
                    if(written<0)throw new IllegalStateException("Ошибка воспроизведения: "+written);
                    if(written==0)break;
                    pos+=written;
                }
            }
        } catch(Exception e) {
            if(capture)status="Ошибка: "+e.getMessage();
        } finally {
            capture=false;
            currentRecorder=null;
            if(record!=null) {try{record.stop();}catch(Exception ignored){} record.release();}
            if(track!=null) {try{track.pause();track.flush();}catch(Exception ignored){}track.release();}
            if(bluetooth&&Build.VERSION.SDK_INT>=31){
                try{manager.clearCommunicationDevice();}catch(Exception ignored){}
                manager.setMode(AudioManager.MODE_NORMAL);
            }
            if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();
            wakeLock=null;
            active=false;
            if("Подключение звука…".equals(status) || status.startsWith("Вход:"))
                status="Остановлено";
            stopSelf();
        }
    }
    private static final class Biquad {
        final double b0,b1,b2,a1,a2;
        double s1,s2;
        Biquad(int hz,int db){
            double a=Math.pow(10,db/40.0),w=2*Math.PI*hz/SAMPLE_RATE;
            double alpha=Math.sin(w)/2,den=1+alpha/a;
            b0=(1+alpha*a)/den;b1=-2*Math.cos(w)/den;b2=(1-alpha*a)/den;
            a1=-2*Math.cos(w)/den;a2=(1-alpha/a)/den;
        }
        double run(double input) {
            double y=b0*input+s1;
            s1=b1*input-a1*y+s2;
            s2=b2*input-a2*y;
            return y;
        }
    }
}
