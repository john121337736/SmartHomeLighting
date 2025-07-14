package com.example.smarthomelighting

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.example.smarthomelighting.utils.MqttClientManager
import org.json.JSONObject
import java.util.Calendar

class SmartHomeLightingApplication : Application(), Application.ActivityLifecycleCallbacks {
    
    companion object {
        private const val TAG = "SmartHomeApp"
        lateinit var instance: SmartHomeLightingApplication
            private set
        
        // 时间发布相关常量
        private const val TIME_PUBLISH_INTERVAL = 1000L // 1秒发送一次
    }
    
    private lateinit var mqttClientManager: MqttClientManager
    
    // 存储当前连接状态，供其他组件访问
    val connectionStatus = MutableLiveData<String>().apply { value = "未连接" }
    
    // 应用前台/后台状态跟踪
    private var isInBackground = true
    private var activeActivities = 0
    
    // 添加时间发布相关变量
    private val timePublishHandler = Handler(Looper.getMainLooper())
    private val timePublishRunnable = object : Runnable {
        override fun run() {
            if (::mqttClientManager.isInitialized) {
                publishCurrentTime()
                timePublishHandler.postDelayed(this, TIME_PUBLISH_INTERVAL)
            }
        }
    }
    
    // 添加定期检查相关变量
    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            if (::mqttClientManager.isInitialized) {
                if (!mqttClientManager.isConnected()) {
                    Log.d(TAG, "检测到MQTT连接断开，尝试重新连接")
                    mqttClientManager.connect()
                }
            }
            connectionCheckHandler.postDelayed(this, 30000) // 每30秒检查一次
        }
    }
    
    // 添加网络回调
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "网络可用")
            // 网络变化时，延迟3秒后检查连接并尝试重连
            Handler(Looper.getMainLooper()).postDelayed({
                if (::mqttClientManager.isInitialized) {
                    if (!mqttClientManager.isConnected()) {
                        Log.d(TAG, "网络变化后检测到连接断开，尝试重新连接")
                        mqttClientManager.forceReconnect()
                    }
                }
            }, 3000)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d(TAG, "网络断开")
        }
    }
    
    // 添加锁屏监听器
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "屏幕点亮")
                    // 延迟2秒后检查连接状态，给系统一些时间恢复网络
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (::mqttClientManager.isInitialized) {
                            if (!mqttClientManager.isConnected()) {
                                Log.d(TAG, "屏幕点亮后检测到连接断开，尝试重新连接")
                                mqttClientManager.forceReconnect()
                            }
                        }
                    }, 2000)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "用户解锁设备")
                    // 用户解锁设备后，强制重新连接MQTT
                    if (::mqttClientManager.isInitialized) {
                        Log.d(TAG, "用户解锁后尝试重新连接MQTT")
                        mqttClientManager.forceReconnect()
                    }
                }
            }
        }
    }
    
    // 提供获取MqttClientManager的方法
    fun getMqttClientManager(): MqttClientManager {
        if (::mqttClientManager.isInitialized) {
            return mqttClientManager
        } else {
            throw IllegalStateException("MqttClientManager尚未初始化")
        }
    }
    
    // 请求最新数据
    fun requestLatestData() {
        if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
            try {
                mqttClientManager.publish("request", "{\"action\":\"getData\"}", 0, false)
                Log.d(TAG, "已请求最新数据")
            } catch (e: Exception) {
                Log.e(TAG, "请求数据失败: ${e.message}")
            }
        } else {
            Log.d(TAG, "MQTT未连接，无法请求数据")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 注册Activity生命周期回调
        registerActivityLifecycleCallbacks(this)
        
        // 初始化MQTT客户端
        try {
            initMqttClient()
        } catch (e: Exception) {
            Log.e(TAG, "MQTT初始化失败: ${e.message}")
        }
        
        // 启动时间发布服务
        startTimePublishing()
        
        // 启动前台服务保持MQTT连接
        try {
            com.example.smarthomelighting.services.MqttBackgroundService.beginService(applicationContext)
            Log.d(TAG, "已启动MQTT后台服务")
        } catch (e: Exception) {
            Log.e(TAG, "启动MQTT后台服务失败: ${e.message}")
        }
        
        // 启动定期检查
        connectionCheckHandler.post(connectionCheckRunnable)
        
        // 注册锁屏和网络变化监听器
        registerScreenAndNetworkReceiver()
    }
    
    private fun registerScreenAndNetworkReceiver() {
        try {
            // 注册屏幕状态广播接收器
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, screenFilter)
            Log.d(TAG, "已注册锁屏监听器")
            
            // 注册网络状态监听器（使用新的API替代已弃用的CONNECTIVITY_ACTION）
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0及以上使用NetworkCallback
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                Log.d(TAG, "已注册网络状态监听器(NetworkCallback)")
            } else {
                // 低版本Android继续使用广播接收器
                @Suppress("DEPRECATION")
                val networkFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        Log.d(TAG, "网络状态变化(旧API)")
                        // 网络状态变化时，延迟3秒后检查连接并尝试重连
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (::mqttClientManager.isInitialized) {
                                if (!mqttClientManager.isConnected()) {
                                    Log.d(TAG, "网络变化后检测到连接断开，尝试重新连接")
                                    mqttClientManager.forceReconnect()
                                }
                            }
                        }, 3000)
                    }
                }, networkFilter)
                Log.d(TAG, "已注册网络状态监听器(BroadcastReceiver)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册监听器失败: ${e.message}")
        }
    }
    
    private fun initMqttClient() {
        try {
            // 连接状态更新
            connectionStatus.postValue("正在连接...")
            
            // 创建MQTT客户端
            mqttClientManager = MqttClientManager(
                applicationContext,
                "k6dffa53.ala.cn-hangzhou.emqxsl.cn:8883", // 服务器地址
                "android_${System.currentTimeMillis()}", // 唯一客户端ID
                "wan", // 用户名
                "121337736", // 密码
                true // 使用SSL
            )
            
            // 设置MQTT状态回调
            mqttClientManager.setCallback(object : MqttClientManager.MqttStatusCallback {
                override fun onConnected() {
                    connectionStatus.postValue("已连接")
                    Log.d(TAG, "MQTT连接成功")
                    
                    // 延迟1秒后订阅，确保连接完全建立
                    Handler(Looper.getMainLooper()).postDelayed({
                        // 订阅需要的主题
                        try {
                            mqttClientManager.subscribe("alarm", 1)
                            mqttClientManager.subscribe("sensor/data", 1)
                            mqttClientManager.subscribe("time", 1)
                            mqttClientManager.subscribe("control", 1)
                            Log.d(TAG, "所有主题订阅成功")
                        } catch(e: Exception) {
                            Log.e(TAG, "订阅主题失败: ${e.message}")
                            // 再次尝试订阅
                            retrySubscriptions()
                        }
                    }, 1000)
                }
                
                override fun onConnectionFailed(error: String) {
                    connectionStatus.postValue("未连接")
                    Log.e(TAG, "MQTT连接失败: $error")
                    
                    // 延迟5秒后重试连接
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "尝试重新连接MQTT...")
                        mqttClientManager.connect()
                    }, 5000)
                }
                
                override fun onMessageReceived(topic: String, message: String) {
                    // 在应用层面处理消息，此处仅记录日志
                    Log.d(TAG, "应用收到消息: 主题=$topic, 内容=$message")
                }
            })
            
            // 设置单例实例
            MqttClientManager.setInstance(mqttClientManager)
            
            // 连接MQTT服务器
            try {
                mqttClientManager.connect()
                Log.d(TAG, "MQTT客户端开始连接...")
            } catch (e: Exception) {
                connectionStatus.postValue("未连接")
                Log.e(TAG, "MQTT连接失败: ${e.message}")
                
                // 延迟5秒后重试连接
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "尝试重新连接MQTT...")
                    mqttClientManager.connect()
                }, 5000)
            }
        } catch (e: Exception) {
            connectionStatus.postValue("未连接")
            Log.e(TAG, "MQTT客户端初始化失败: ${e.message}")
            throw e
        }
    }
    
    // 添加重试订阅方法
    private fun retrySubscriptions() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
                try {
                    mqttClientManager.subscribe("alarm", 1)
                    mqttClientManager.subscribe("sensor/data", 1)
                    mqttClientManager.subscribe("time", 1)
                    mqttClientManager.subscribe("control", 1)
                    Log.d(TAG, "重试订阅主题成功")
                } catch(e: Exception) {
                    Log.e(TAG, "重试订阅主题失败: ${e.message}")
                    // 再次尝试
                    retrySubscriptions()
                }
            } else {
                Log.d(TAG, "MQTT未连接，无法重试订阅")
                // 如果还未连接，等待连接建立后会自动订阅
            }
        }, 3000) // 3秒后重试
    }
    
    // 开始发布时间
    private fun startTimePublishing() {
        if (::mqttClientManager.isInitialized) {
            timePublishHandler.post(timePublishRunnable)
            Log.d(TAG, "开始发布时间服务")
        } else {
            Log.e(TAG, "无法启动时间发布服务：MQTT客户端未初始化")
            // 延迟尝试启动时间服务
            timePublishHandler.postDelayed({
                if (::mqttClientManager.isInitialized) {
                    timePublishHandler.post(timePublishRunnable)
                    Log.d(TAG, "延迟启动时间发布服务成功")
                }
            }, 5000) // 5秒后重试
        }
    }
    
    // 发布当前时间到MQTT
    private fun publishCurrentTime() {
        if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
            try {
                // 获取当前时间
                val calendar = Calendar.getInstance()
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                val second = calendar.get(Calendar.SECOND)
                
                // 格式化时间
                val timeString = String.format("%02d:%02d:%02d", hour, minute, second)
                
                // 创建JSON对象
                val jsonObject = JSONObject()
                jsonObject.put("current_time", timeString)
                
                // 发送到time主题
                mqttClientManager.publish("time", jsonObject.toString(), 0, false)
                Log.d(TAG, "应用级服务发送时间: $timeString")
            } catch (e: Exception) {
                Log.e(TAG, "发送时间失败: ${e.message}")
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        
        // 注销监听器
        try {
            // 注销屏幕状态监听器
            unregisterReceiver(screenReceiver)
            Log.d(TAG, "已注销锁屏监听器")
            
            // 注销网络状态监听器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(networkCallback)
                Log.d(TAG, "已注销网络状态监听器(NetworkCallback)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注销监听器失败: ${e.message}")
        }
        
        // 停止时间发布
        timePublishHandler.removeCallbacks(timePublishRunnable)
        Log.d(TAG, "停止时间发布服务")
        
        // 断开MQTT连接
        if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
            try {
                mqttClientManager.disconnect()
                Log.d(TAG, "MQTT客户端已断开连接")
            } catch (e: Exception) {
                Log.e(TAG, "断开MQTT连接失败: ${e.message}")
            }
        }
        
        // 停止定期检查
        connectionCheckHandler.removeCallbacks(connectionCheckRunnable)
        Log.d(TAG, "停止定期检查")
        
        // 注销Activity生命周期回调
        unregisterActivityLifecycleCallbacks(this)
    }
    
    // 应用从后台切回前台时检查连接
    private fun checkConnectionOnForeground() {
        if (::mqttClientManager.isInitialized) {
            Log.d(TAG, "应用切回前台，检查MQTT连接状态")
            mqttClientManager.checkConnectionAndReconnect()
        }
    }
    
    // Activity生命周期回调实现
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    
    override fun onActivityStarted(activity: Activity) {
        activeActivities++
        if (isInBackground) {
            isInBackground = false
            Log.d(TAG, "应用切回前台")
            checkConnectionOnForeground()
        }
    }
    
    override fun onActivityResumed(activity: Activity) {}
    
    override fun onActivityPaused(activity: Activity) {}
    
    override fun onActivityStopped(activity: Activity) {
        activeActivities--
        if (activeActivities == 0) {
            isInBackground = true
            Log.d(TAG, "应用切入后台")
        }
    }
    
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    
    override fun onActivityDestroyed(activity: Activity) {}
} 