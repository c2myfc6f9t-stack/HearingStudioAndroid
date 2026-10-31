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
import android.os.Process;
import android.view.View;
import android.widget.Button;
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
    private static final int FRAME = 1024;
    private static final int[] FREQS = {250,500,1000,2000,4000};
    private final int[][] gains = new int[2][5];
    private final float[] levels = {0.25f,0.25f};
    private volatile Bank bank = new Bank(gains);
    private volatile boolean running = false;
    private Thread thread;
    private TextView status;
    private Button startButton, stopButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        text(root,"Микрофон телефона → AirPods (если выбраны для звука)",15,true,0xff244f73);
        text(root,"Не является слуховым аппаратом. Возможны задержка и акустическая обратная связь. Перед запуском уменьшите громкость в телефоне. Не используйте при движении по дороге.",14,false,0xff9b3a2f);
        gap(root,12);
        status=text(root,"Остановлено",16,true,0xff264d6d);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);
        startButton=button(buttons,"ЗАПУСТИТЬ",v->start());
        stopButton=button(buttons,"СТОП",v->stop());
        stopButton.setEnabled(false);
        gap(root,14);
        text(root,"Индивидуальная громкость",19,true,0xff183047);
        volume(root,0,"Левое ухо");
        volume(root,1,"Правое ухо");
        gap(root,10);
        text(root,"Эквалайзер: −6 … +6 дБ",19,true,0xff183047);
        text(root,"Это ручная коррекция, не настройка по аудиограмме. Оба канала получают один и тот же сигнал с микрофона телефона.",13,false,0xff526273);
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
            bank=new Bank(gains);
            recreate();
        });
        gap(root,12);
        text(root,"Обрабатывается только звук микрофона смартфона. Встроенный режим прозрачности AirPods не изменяется. Данные не покидают устройство.",13,false,0xff526273);
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
        try{
            int rmin=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            int pmin=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if(rmin<=0||pmin<=0)throw new IllegalStateException("PCM 48 кГц недоступен");
            rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(rmin,FRAME*4));
            out=new AudioTrack(AudioManager.STREAM_MUSIC,RATE,AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(pmin,FRAME*8),AudioTrack.MODE_STREAM);
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED||out.getState()!=AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Не удалось открыть аудио");
            AudioManager manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
            for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){rec.setPreferredDevice(d);break;}
            rec.startRecording();out.play();status("Работает. Громкость регулируется в телефоне.");
            short[] input=new short[FRAME], output=new short[FRAME*2];
            float oldx=0,oldy=0;
            while(running){
                int n=rec.read(input,0,FRAME);
                if(n<0)throw new IllegalStateException("Ошибка микрофона: "+n);
                if(n==0)continue;
                Bank f=bank;
                float vl=levels[0],vr=levels[1];
                for(int i=0;i<n;i++){
                    float x=input[i]/32768f;
                    float hp=0.99f*(oldy+x-oldx);oldx=x;oldy=hp;
                    output[i*2]=pcm(f.apply(0,hp)*vl);
                    output[i*2+1]=pcm(f.apply(1,hp)*vr);
                }
                int pos=0;
                while(running&&pos<n*2){
                    int w=out.write(output,pos,n*2-pos);
                    if(w<0)throw new IllegalStateException("Ошибка воспроизведения: "+w);
                    if(w==0)break;
                    pos+=w;
                }
            }
        }catch(Exception ex){status("Ошибка: "+ex.getMessage());}
        finally{
            running=false;
            if(rec!=null){try{rec.stop();}catch(Exception ignored){}rec.release();}
            if(out!=null){try{out.stop();}catch(Exception ignored){}out.release();}
            runOnUiThread(()->{startButton.setEnabled(true);stopButton.setEnabled(false);});
        }
    }
    private short pcm(double x){
        return (short)Math.round(Math.max(-0.5,Math.min(0.5,x))*32767.0);
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
