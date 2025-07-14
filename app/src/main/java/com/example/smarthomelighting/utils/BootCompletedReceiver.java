package com.example.smarthomelighting.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.smarthomelighting.services.MqttBackgroundService;

/**
 * 开机自启动广播接收器
 * 用于在设备重启后自动启动MQTT服务
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "设备启动完成，准备启动MQTT服务");
            
            // 延迟30秒后启动服务，确保网络已经准备好
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    // 启动MQTT后台服务 - 使用Intent方式启动服务，而不是直接调用Kotlin的伴生对象方法
                    Intent serviceIntent = new Intent(context, MqttBackgroundService.class);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.d(TAG, "设备启动后，MQTT服务已启动");
                } catch (Exception e) {
                    Log.e(TAG, "启动MQTT服务失败: " + e.getMessage());
                }
            }, 30000); // 延迟30秒
        }
    }
} 