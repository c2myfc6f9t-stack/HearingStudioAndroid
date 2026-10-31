package com.hearingstudio.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Build;
import android.media.AudioAttributes;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Experimental phone-microphone monitor. NOT a certified hearing aid.
 * Audio is processed locally and is not saved or uploaded.
 */
public final class MainActivity extends Activity {
    private static final int PERMISSION_MIC = 42;
    private static final int RATE = 48000;
    private static final int FRAME = 240; // 5 ms PCM frames at 48 kHz
    private static final int[] FREQS = {250,500,1000,2000,4000};
    private final int[][] gains = new int[2][5];
    private final float[] levels = {0.25f,0.25f};
    // noise, speech EQ, wind/rumble cut, dynamic compression.
    private final int[] sceneControls = {45,65,35,50};
    private final SeekBar[] sceneBars = new SeekBar[4];
    private final TextView[] sceneLabels = new TextView[4];
    private Spinner scenePicker;
    private volatile SceneProcessor.Settings sceneSettings = new SceneProcessor.Settings(45,65,35,50);
    private static final int[][] PRESETS = {
        {45,65,35,50}, // Conversation
        {60,60,75,45}, // Transport
        {25,40,80,30}, // Street
        {0,0,20,0}     // Neutral
    };
    private volatile Bank bank = new Bank(gains);
    private volatile boolean running = false;
    private volatile int inputMode = 0; // 0: phone microphone + media output, 1: Bluetooth headset hands-free
    private Thread thread;
    private TextView status;
    private AudioManager audioManager;
    private Button startButton, stopButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247,248,252));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20),px(28),px(20),px(48));
        scroll.addView(root);
        setContentView(scroll);
        text(root,"HEARING STUDIO",25,true,0xff183047);
        text(root,"Тестовая версия для POCO C71 • Android",14,false,0xff526273);
        gap(root,16);
        text(root,"Выбор источника звука",18,true,0xff244f73);
        Spinner chooser=new Spinner(this);
        ArrayAdapter<String> choices=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Микрофон телефона → AirPods (медиа)","Микрофон AirPods / Bluetooth (гарнитура)"});
        chooser.setAdapter(choices);
        chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View v,int position,long id){
                if(running)stop();
                inputMode=position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        root.addView(chooser);
        text(root,"В режиме AirPods Android переключает Bluetooth на разговорный профиль: звук может быть моно, более низкого качества. Если микрофон не доступен системе, приложение сообщит об этом.",13,false,0xff526273);
        text(root,"Экспериментальная программа, не слуховой аппарат. Bluetooth может давать заметную задержку и акустическую обратную связь. Начните с низкой системной громкости. Не используйте для безопасности или на дороге.",14,false,0xff9b3a2f);
        gap(root,12);
        status=text(root,"Остановлено",16,true,0xff264d6d);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);
        startButton=button(buttons,"ЗАПУСТИТЬ",v->start());
        stopButton=button(buttons,"СТОП",v->stop());
        stopButton.setEnabled(false);
        gap(root,16);
        text(root,"Улучшение звуковой сцены",20,true,0xff183047);
        text(root,"Фильтрация работает на телефоне для обоих источников. Это не ИИ-разделение голосов: постоянные шумы приглушаются адаптивно, а речевые частоты подчёркиваются эквалайзером.",13,false,0xff526273);
        scenePicker=new Spinner(this);
        ArrayAdapter<String> modes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Разговор", "Транспорт", "Улица", "Нейтральный", "Моя настройка"});
        scenePicker.setAdapter(modes);
        root.addView(scenePicker);
        sceneSlider(root,0,"Подавление фонового шума");
        sceneSlider(root,1,"Разборчивость речи");
        sceneSlider(root,2,"Убрать ветер и низкий гул");
        sceneSlider(root,3,"Стабилизация громкости");
        scenePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View v,int position,long id){
                if(position>=0 && position<PRESETS.length){
                    for(int i=0;i<4;i++)sceneBars[i].setProgress(PRESETS[position][i]);
                    updateScene();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        text(root,"Сильное шумоподавление может приглушать тихую речь и важные звуки. Начни с профиля «Разговор». Задержка Bluetooth этим не устраняется.",13,false,0xff9b3a2f);
        gap(root,14);
        text(root,"Индивидуальная громкость",19,true,0xff183047);
        volume(root,0,"Левое ухо");
        volume(root,1,"Правое ухо");
        gap(root,10);
        text(root,"Эквалайзер: −6 … +6 дБ",19,true,0xff183047);
        text(root,"Ручная коррекция, не аудиограмма. В режиме Bluetooth-гарнитуры Android может передавать моно; тогда независимая коррекция ушей не поддерживается.",13,false,0xff526273);
        for(int ear=0;ear<2;ear++) {
            gap(root,8);
            text(root,ear==0?"ЛЕВОЕ УХО":"ПРАВОЕ УХО",16,true,0xff244f73);
            for(int b=0;b<5;b++) band(root,ear,b);
        }
        gap(root,14);
        button(root,"Сбросить настройки",v->{
            stop();
            for(int e=0;e<2;e++) for(int b=0;b<5;b++) gains[e][b]=0;
            levels[0]=levels[1]=0.25f;
            for(int i=0;i<4;i++)sceneControls[i]=PRESETS[0][i];
            updateScene();
            bank=new Bank(gains);
            recreate();
        });
        gap(root,12);
        text(root,"Обрабатывается выбранный микрофон: телефона или гарнитуры. Встроенный режим прозрачности AirPods не изменяется. Звук не сохраняется и никуда не отправляется.",13,false,0xff526273);
    }
    private int px(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout root,String s,int size,boolean bold,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);t.setPadding(0,px(5),0,px(7));root.addView(t);return t;
    }
    private void gap(LinearLayout root,int size){
        View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,px(size)));
    }
    private Button button(LinearLayout root,String label,View.OnClickListener click){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);
        if(root.getOrientation()==LinearLayout.HORIZONTAL)root.addView(b,new LinearLayout.LayoutParams(0,px(56),1f));
        else root.addView(b,new LinearLayout.LayoutParams(-1,px(56)));
        b.setOnClickListener(click);return b;
    }
    private void updateScene(){
        sceneSettings=new SceneProcessor.Settings(sceneControls[0],sceneControls[1],
            sceneControls[2],sceneControls[3]);
    }
    private void sceneSlider(LinearLayout root,int index,String name) {
        TextView label=text(root,name+" — "+sceneControls[index]+"%",15,false,0xff183047);
        sceneLabels[index]=label;
        SeekBar bar=new SeekBar(this);
        sceneBars[index]=bar;
        bar.setMax(100);
        bar.setProgress(sceneControls[index]);
        root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar sb,int p,boolean fromUser){
                sceneControls[index]=p;
                label.setText(name+" — "+p+"%");
                updateScene();
                if(fromUser && scenePicker!=null && scenePicker.getSelectedItemPosition()!=4)
                    scenePicker.setSelection(4);
            }
            @Override public void onStartTrackingTouch(SeekBar sb){}
            @Override public void onStopTrackingTouch(SeekBar sb){}
        });
    }
    private void volume(LinearLayout root,int ear,String name){
        TextView label=text(root,name+" 25%",15,false,0xff183047);
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(25);root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar sb,int p,boolean fromUser){levels[ear]=p/100f;label.setText(name+" "+p+"%");}
            public void onStartTrackingTouch(SeekBar sb){}
            public void onStopTrackingTouch(SeekBar sb){}
        });
    }
    private void band(LinearLayout root,int ear,int idx){
        String name=FREQS[idx]+" Гц";
        TextView label=text(root,name+" 0 дБ",14,false,0xff183047);
        SeekBar bar=new SeekBar(this);bar.setMax(12);bar.setProgress(6);root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar sb,int p,boolean fromUser){
                int db=p-6;gains[ear][idx]=db;
                label.setText(name+" "+(db>0?"+":"")+db+" дБ");
                bank=new Bank(gains);
            }
            public void onStartTrackingTouch(SeekBar sb){}
            public void onStopTrackingTouch(SeekBar sb){}
        });
    }
    private synchronized void start(){
        if(running)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},PERMISSION_MIC);return;
        }
        if(inputMode==1 && Build.VERSION.SDK_INT>=31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},PERMISSION_MIC);return;
        }
        running=true;startButton.setEnabled(false);stopButton.setEnabled(true);
        status.setText("Подключение аудио...");
        thread=new Thread(this::loop,"phone-mic-monitor");thread.start();
    }
    private void stop(){
        running=false;
        if(startButton!=null)startButton.setEnabled(true);
        if(stopButton!=null)stopButton.setEnabled(false);
        if(status!=null)status.setText("Остановлено");
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] result){
        super.onRequestPermissionsResult(code,perms,result);
        if(code==PERMISSION_MIC){
            if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)start();
            else status.setText("Нет разрешения на микрофон");
        }
    }
    @Override protected void onStop(){stop();super.onStop();}
    private void status(String s){runOnUiThread(()->{if(status!=null)status.setText(s);});}
    private void loop(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord rec=null; AudioTrack out=null;
        boolean bluetooth=inputMode==1;
        SceneProcessor processor=new SceneProcessor(RATE);
        try{
            if(bluetooth){
                if(Build.VERSION.SDK_INT<31)throw new IllegalStateException("Нужен Android 12+ для выбора микрофона AirPods.");
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                AudioDeviceInfo selected=null;
                for(AudioDeviceInfo device:audioManager.getAvailableCommunicationDevices()){
                    int t=device.getType();
                    if(t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||t==AudioDeviceInfo.TYPE_BLE_HEADSET){
                        selected=device;
                        break;
                    }
                }
                if(selected==null)throw new IllegalStateException("Android не показывает Bluetooth-микрофон. Переподключите AirPods.");
                if(!audioManager.setCommunicationDevice(selected))
                    throw new IllegalStateException("Android отказал в переключении Bluetooth-гарнитуры.");
                Thread.sleep(650); // permit hands-free route to initialize, not a playback buffer
            }

            int outChannels=bluetooth?1:2;
            int outputMask=bluetooth?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
            int rmin=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            int pmin=AudioTrack.getMinBufferSize(RATE,outputMask,AudioFormat.ENCODING_PCM_16BIT);
            if(rmin<=0||pmin<=0)throw new IllegalStateException("PCM 48 кГц недоступен");
            rec=new AudioRecord(bluetooth?MediaRecorder.AudioSource.VOICE_COMMUNICATION:MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(rmin,FRAME*4));
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(outputMask).build();
            AudioAttributes attributes=new AudioAttributes.Builder()
                .setUsage(bluetooth?AudioAttributes.USAGE_VOICE_COMMUNICATION:AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            out=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(pmin,FRAME*outChannels*4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED||out.getState()!=AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Не удалось открыть аудио");
            if(!bluetooth){
                for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                    if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){rec.setPreferredDevice(d);break;}
            }
            rec.startRecording();out.play();
            AudioDeviceInfo routedInput=rec.getRoutedDevice();
            AudioDeviceInfo routedOutput=out.getRoutedDevice();
            String inName=routedInput==null?"маршрут не определён":routedInput.getProductName().toString();
            String outName=routedOutput==null?"маршрут не определён":routedOutput.getProductName().toString();
            status("Вход: "+inName+" | Выход: "+outName+(bluetooth?" (гарнитура, моно)":""));
            short[] input=new short[FRAME], output=new short[FRAME*outChannels];
            while(running){
                int n=rec.read(input,0,FRAME);
                if(n<0)throw new IllegalStateException("Ошибка микрофона: "+n);
                if(n==0)continue;
                Bank f=bank;
                processor.setSettings(sceneSettings);
                float vl=levels[0],vr=levels[1];
                for(int i=0;i<n;i++){
                    double processed=processor.process(input[i]/32768.0);
                    if(bluetooth)output[i]=pcm((f.apply(0,processed)*vl+f.apply(1,processed)*vr)*0.5);
                    else {
                        output[i*2]=pcm(f.apply(0,processed)*vl);
                        output[i*2+1]=pcm(f.apply(1,processed)*vr);
                    }
                }
                int pos=0;
                while(running&&pos<n*outChannels){
                    int w=out.write(output,pos,n*outChannels-pos);
                    if(w<0)throw new IllegalStateException("Ошибка воспроизведения: "+w);
                    if(w==0)break;
                    pos+=w;
                }
            }
        }catch(Exception ex){status("Ошибка: "+ex.getMessage());}
        finally{
            running=false;
            if(bluetooth && Build.VERSION.SDK_INT>=31){
                audioManager.clearCommunicationDevice();
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
            if(rec!=null){try{rec.stop();}catch(Exception ignored){}rec.release();}
            if(out!=null){try{out.stop();}catch(Exception ignored){}out.release();}
            runOnUiThread(()->{startButton.setEnabled(true);stopButton.setEnabled(false);});
        }
    }
    private short pcm(double x){
        if(!Double.isFinite(x))return 0;
        double magnitude=Math.abs(x);
        // Unity below -7.5 dBFS, then smooth limiting to avoid hard-clipped peaks.
        if(magnitude>0.42)magnitude=0.42+0.08*(1.0-Math.exp(-(magnitude-0.42)/0.08));
        return (short)Math.round(Math.copySign(Math.min(0.5,magnitude),x)*32767.0);
    }
    private static final class Bank{
        final Biquad[][] eq=new Biquad[2][5];
        Bank(int[][] g){
            for(int e=0;e<2;e++)for(int b=0;b<5;b++)eq[e][b]=new Biquad(FREQS[b],g[e][b]);
        }
        double apply(int ear,double x){for(Biquad b:eq[ear])x=b.run(x);return x;}
    }
    private static final class Biquad{
        final double b0,b1,b2,a1,a2;double z1,z2;
        Biquad(int hz,int db){
            double A=Math.pow(10,db/40.0),w=2*Math.PI*hz/RATE,alpha=Math.sin(w)/2.0;
            double norm=1+alpha/A;
            b0=(1+alpha*A)/norm;b1=(-2*Math.cos(w))/norm;b2=(1-alpha*A)/norm;
            a1=(-2*Math.cos(w))/norm;a2=(1-alpha/A)/norm;
        }
        double run(double x){
            double y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;
        }
    }
}
