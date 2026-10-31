package com.hearingstudio.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;

/** UI controller. All audio capture lives in HearingService, not this Activity. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS=42;
    private static final int[] FREQUENCIES={250,500,1000,2000,4000};
    private static final int[][] PRESETS={
        {45,65,35,50}, // Conversation
        {60,60,75,45}, // Transport
        {25,40,80,30}, // Street
        {0,0,20,0}     // Neutral
    };
    private final int[][] eq=new int[2][5];
    private final float[] volumes={0.25f,0.25f};
    private final int[] scene={45,65,35,50};
    private final SeekBar[] sceneBars=new SeekBar[4];
    private Spinner scenePicker, sourcePicker;
    private Button startButton, stopButton;
    private TextView statusLabel;
    private int inputMode=0, preset=0;
    private boolean initialized=false, isRestoring=false;
    private SharedPreferences preferences;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshTask=new Runnable(){
        @Override public void run(){
            refresh();
            handler.postDelayed(this,650);
        }
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        preferences=getSharedPreferences("audio_settings",MODE_PRIVATE);
        loadSettings();
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247,248,252));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20),px(24),px(20),px(45));
        scroll.addView(root);
        setContentView(scroll);
        text(root,"HEARING STUDIO 0.5",24,true,0xff183047);
        text(root,"POCO C71 · AirPods Pro 3 · фоновая обработка",14,false,0xff526273);
        gap(root,14);
        text(root,"Фоновая работа включена",19,true,0xff244f73);
        text(root,"Нажми «Запустить», затем сверни приложение или выключи экран. Звук продолжит обрабатываться через службу Android. Активный микрофон отображается в постоянном уведомлении; там же можно нажать «Остановить».",14,false,0xff264d6d);
        text(root,"Не является слуховым аппаратом. Начни с низкой громкости. При появлении свиста, задержки или дискомфорта немедленно останови обработку. Не применяй на дороге.",13,false,0xff9b3a2f);
        gap(root,14);

        text(root,"Источник звука",18,true,0xff183047);
        sourcePicker=new Spinner(this);
        ArrayAdapter<String> sources=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,new String[]{
                "Микрофон телефона → AirPods (стерео)",
                "Микрофон AirPods / Bluetooth (гарнитура)"
            });
        sourcePicker.setAdapter(sources);
        sourcePicker.setSelection(inputMode);
        root.addView(sourcePicker);
        sourcePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){
                if(!HearingService.active) {
                    inputMode=pos;
                    sync();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        statusLabel=text(root,"Остановлено",14,true,0xff264d6d);
        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(controls);
        startButton=button(controls,"ЗАПУСТИТЬ",view->startProcessing());
        stopButton=button(controls,"СТОП",view->stopProcessing());
        gap(root,14);

        text(root,"Улучшение звуковой сцены",19,true,0xff183047);
        text(root,"Без нейросетевого распознавания источников: адаптивное приглушение постоянного шума, частотное выделение речи и компрессия. Тихая речь или сигналы также могут приглушаться.",13,false,0xff526273);
        scenePicker=new Spinner(this);
        ArrayAdapter<String> modes=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,new String[]{
                "Разговор","Транспорт","Улица","Нейтральный","Моя настройка"
            });
        scenePicker.setAdapter(modes);
        scenePicker.setSelection(preset);
        root.addView(scenePicker);
        sceneSlider(root,0,"Подавление фонового шума");
        sceneSlider(root,1,"Разборчивость речи");
        sceneSlider(root,2,"Ветер и низкий гул");
        sceneSlider(root,3,"Стабилизация громкости");
        scenePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){
                if(!initialized)return;
                preset=pos;
                if(pos<PRESETS.length) {
                    isRestoring=true;
                    for(int i=0;i<4;i++)sceneBars[i].setProgress(PRESETS[pos][i]);
                    isRestoring=false;
                }
                sync();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        gap(root,12);
        text(root,"Громкость отдельно для каждого уха",19,true,0xff183047);
        volume(root,0,"Левое ухо");
        volume(root,1,"Правое ухо");
        gap(root,10);
        text(root,"Эквалайзер — от −6 до +6 дБ",19,true,0xff183047);
        text(root,"В режиме Bluetooth-гарнитуры Android может воспроизводить только моно. Тогда раздельная коррекция левого и правого уха не гарантируется.",13,false,0xff526273);
        for(int ear=0;ear<2;ear++){
            gap(root,8);
            text(root,ear==0?"ЛЕВОЕ УХО":"ПРАВОЕ УХО",16,true,0xff244f73);
            for(int b=0;b<5;b++)band(root,ear,b);
        }
        gap(root,14);
        button(root,"Сбросить настройки",view->{
            stopProcessing();
            preferences.edit().clear().apply();
            recreate();
        });
        gap(root,12);
        text(root,"Звук остаётся на телефоне, не записывается и не передаётся на сервер. Режим прозрачности AirPods не изменяется. Система может остановить службу при нехватке ресурсов или принудительной остановке приложения.",13,false,0xff526273);
        initialized=true;
        sync();
        refresh();
    }
    private int px(int v){
        return Math.round(v*getResources().getDisplayMetrics().density);
    }
    private TextView text(LinearLayout parent,String value,int size,boolean bold,int color){
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);
        t.setPadding(0,px(5),0,px(7));
        parent.addView(t);
        return t;
    }
    private void gap(LinearLayout parent,int size){
        parent.addView(new View(this),new LinearLayout.LayoutParams(1,px(size)));
    }
    private Button button(LinearLayout parent,String value,View.OnClickListener onClick){
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);
        if(parent.getOrientation()==LinearLayout.HORIZONTAL)
            parent.addView(b,new LinearLayout.LayoutParams(0,px(54),1f));
        else parent.addView(b,new LinearLayout.LayoutParams(-1,px(54)));
        b.setOnClickListener(onClick);
        return b;
    }
    private void sceneSlider(LinearLayout parent,int index,String name){
        TextView label=text(parent,name+" — "+scene[index]+"%",14,false,0xff183047);
        SeekBar bar=new SeekBar(this);
        sceneBars[index]=bar;bar.setMax(100);bar.setProgress(scene[index]);
        parent.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                scene[index]=value;
                label.setText(name+" — "+value+"%");
                if(fromUser&&!isRestoring&&scenePicker!=null
                    &&scenePicker.getSelectedItemPosition()!=4)
                    scenePicker.setSelection(4);
                sync();
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void volume(LinearLayout parent,int ear,String name){
        TextView label=text(parent,name+" "+Math.round(volumes[ear]*100)+"%",
            15,false,0xff183047);
        SeekBar b=new SeekBar(this);
        b.setMax(100);b.setProgress(Math.round(volumes[ear]*100));parent.addView(b);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){
                volumes[ear]=value/100f;
                label.setText(name+" "+value+"%");
                sync();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
    }
    private void band(LinearLayout parent,int ear,int index){
        String title=FREQUENCIES[index]+" Гц";
        TextView label=text(parent,title+" "+eq[ear][index]+" дБ",14,false,0xff183047);
        SeekBar b=new SeekBar(this);
        b.setMax(12);b.setProgress(eq[ear][index]+6);parent.addView(b);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){
                int db=value-6;eq[ear][index]=db;
                label.setText(title+" "+(db>0?"+":"")+db+" дБ");
                sync();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
    }
    private void loadSettings(){
        inputMode=preferences.getInt("source",0);
        preset=preferences.getInt("preset",0);
        if(inputMode<0||inputMode>1)inputMode=0;
        if(preset<0||preset>4)preset=0;
        for(int i=0;i<4;i++)scene[i]=preferences.getInt("scene_"+i,scene[i]);
        for(int ear=0;ear<2;ear++){
            volumes[ear]=preferences.getFloat("volume_"+ear,0.25f);
            for(int band=0;band<5;band++)
                eq[ear][band]=preferences.getInt("eq_"+ear+"_"+band,0);
        }
    }
    private void sync(){
        if(!initialized)return;
        HearingService.updateProfile(new HearingService.Profile(inputMode,volumes,eq,scene));
        SharedPreferences.Editor e=preferences.edit().putInt("source",inputMode)
            .putInt("preset",preset);
        for(int i=0;i<4;i++)e.putInt("scene_"+i,scene[i]);
        for(int ear=0;ear<2;ear++){
            e.putFloat("volume_"+ear,volumes[ear]);
            for(int band=0;band<5;band++)
                e.putInt("eq_"+ear+"_"+band,eq[ear][band]);
        }
        e.apply();
    }
    private void startProcessing(){
        if(HearingService.active)return;
        ArrayList<String> missing=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if(inputMode==1&&Build.VERSION.SDK_INT>=31
            &&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(Build.VERSION.SDK_INT>=33
            &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
            &&!preferences.getBoolean("asked_notification",false)){
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            preferences.edit().putBoolean("asked_notification",true).apply();
        }
        if(!missing.isEmpty()){
            requestPermissions(missing.toArray(new String[0]),REQUEST_PERMISSIONS);
            return;
        }
        sync();
        try {
            Intent service=new Intent(this,HearingService.class).setAction(HearingService.ACTION_START);
            startForegroundService(service);
            statusLabel.setText("Запуск звуковой службы…");
        } catch(Exception e){
            statusLabel.setText("Ошибка запуска: "+e.getMessage());
        }
        refresh();
    }
    private void stopProcessing(){
        if(!HearingService.active)return;
        startService(new Intent(this,HearingService.class).setAction(HearingService.ACTION_STOP));
        refresh();
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] grants){
        super.onRequestPermissionsResult(request,perms,grants);
        if(request==REQUEST_PERMISSIONS){
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
                &&(inputMode==0 || Build.VERSION.SDK_INT<31
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        ==PackageManager.PERMISSION_GRANTED))
                startProcessing();
            else statusLabel.setText("Для работы необходим доступ к микрофону и Bluetooth (для гарнитуры).");
        }
    }
    private void refresh(){
        if(statusLabel==null)return;
        statusLabel.setText(HearingService.status);
        boolean running=HearingService.active;
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        sourcePicker.setEnabled(!running);
    }
    @Override protected void onResume(){
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }
    @Override protected void onPause(){
        // Intentionally DO NOT stop audio when app is minimized or display is off.
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
