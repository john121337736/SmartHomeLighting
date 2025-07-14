package com.example.smarthomelighting.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smarthomelighting.MainActivity
import com.example.smarthomelighting.R
import com.example.smarthomelighting.SmartHomeLightingApplication
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 前台服务，确保应用在后台时能继续接收MQTT消息
 */
class MqttBackgroundService : Service() {
    private val tag = "MqttBackgroundService"
    
    // 通知ID和通道
    private val notificationId = 1001
    private val channelId = "mqtt_service_channel"
    
    // 定时任务线程池
    private lateinit var scheduler: ScheduledExecutorService
    
    // 记录上次重连检查时间
    private var lastReconnectCheckTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "MQTT后台服务已创建")
        
        // 创建通知通道
        createNotificationChannel()
        
        // 启动前台服务
        try {
            val notification = createNotification("应用正在后台运行")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                Log.d(tag, "使用Android 14方式启动前台服务")
            } else {
                startForeground(notificationId, notification)
                Log.d(tag, "使用传统方式启动前台服务")
            }
        } catch (e: Exception) {
            Log.e(tag, "启动前台服务失败: ${e.message}")
            // 尝试降级为普通服务，避免应用崩溃
            try {
                val notification = createNotification("应用正在后台运行")
                startForeground(notificationId, notification)
                Log.d(tag, "降级启动前台服务")
            } catch (e2: Exception) {
                Log.e(tag, "降级启动前台服务也失败: ${e2.message}")
            }
        }
        
        // 创建定时任务线程池
        scheduler = Executors.newSingleThreadScheduledExecutor()
        
        // 定时检查MQTT连接状态并请求数据
        scheduler.scheduleAtFixedRate({
            // 请求最新数据
            try {
                // 使用SmartHomeLightingApplication的实例来获取MqttClientManager
                val mqttManager = SmartHomeLightingApplication.instance.getMqttClientManager()
                if (mqttManager.isConnected()) {
                    mqttManager.publish("request", "{\"action\":\"getData\"}", 0, false)
                    Log.d(tag, "后台服务定时请求数据")
                } else {
                    Log.d(tag, "MQTT未连接，无法请求数据")
                    // 检查是否长时间未重连（可能是从锁屏恢复）
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastReconnectCheckTime > 60000) { // 如果超过1分钟没有检查重连
                        Log.d(tag, "后台服务检测到可能需要重连，尝试强制重连")
                        mqttManager.forceReconnect()
                        lastReconnectCheckTime = currentTime
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "请求数据失败: ${e.message}")
            }
        }, 30, 30, TimeUnit.SECONDS) // 每30秒执行一次
        
        // 添加更频繁的连接检查，专门用于锁屏后恢复
        scheduler.scheduleAtFixedRate({
            try {
                val currentTime = SystemClock.elapsedRealtime()
                // 记录检查时间
                lastReconnectCheckTime = currentTime
                
                val mqttManager = SmartHomeLightingApplication.instance.getMqttClientManager()
                if (!mqttManager.isConnected()) {
                    Log.d(tag, "快速检查发现MQTT未连接，尝试重连")
                    mqttManager.forceReconnect()
                }
            } catch (e: Exception) {
                Log.e(tag, "连接检查失败: ${e.message}")
            }
        }, 5, 120, TimeUnit.SECONDS) // 5秒后开始，每2分钟执行一次
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "MQTT后台服务已启动")
        
        // 更新通知
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, createNotification("应用正在后台运行"))
        
        // 如果服务被系统杀死，自动重启
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MQTT服务通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持MQTT连接的通知"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String): Notification {
        // 创建打开应用的Intent
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        // 构建通知
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("智能照明")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 关闭定时任务
        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            scheduler.shutdown()
        }
        
        Log.d(tag, "MQTT后台服务已销毁")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    companion object {
        /**
         * 启动服务的便捷方法
         */
        fun beginService(context: Context) {
            val intent = Intent(context, MqttBackgroundService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
                Log.d("MqttBackgroundService", "前台服务启动请求已发送")
            } else {
                context.startService(intent)
                Log.d("MqttBackgroundService", "普通服务启动请求已发送")
            }
        }
        
        /**
         * 停止服务的便捷方法
         */
        fun endService(context: Context) {
            val intent = Intent(context, MqttBackgroundService::class.java)
            context.stopService(intent)
            Log.d("MqttBackgroundService", "服务停止请求已发送")
        }
    }
} 