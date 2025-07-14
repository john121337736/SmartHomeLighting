package com.example.smarthomelighting.utils

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import com.example.smarthomelighting.ui.home.HomeViewModel

/**
 * 全局MQTT服务类，确保应用在后台时也能保持MQTT连接
 * 负责管理MQTT连接状态，并将数据分发给各个ViewModel
 */
class GlobalMqttService private constructor(private val appContext: Context) : MqttClientManager.MqttStatusCallback {
    
    private val TAG = "GlobalMqttService"
    private lateinit var mqttClientManager: MqttClientManager
    private val handler = Handler(Looper.getMainLooper())
    
    // 连接状态检查
    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            checkMqttConnection()
            // 每10秒检查一次连接状态
            handler.postDelayed(this, 10000) 
        }
    }
    
    // 存储当前连接状态
    val connectionStatus = MutableLiveData<String>().apply { value = "未连接" }
    
    // 存储传感器数据，用于在应用恢复时恢复显示
    val lastSensorData = HashMap<String, String>()
    val lastMode = MutableLiveData<String>().apply { value = "手动模式" }
    
    // 保存HomeViewModel实例的引用
    private var homeViewModel: HomeViewModel? = null
    
    init {
        // 初始化MQTT管理器
        mqttClientManager = MqttClientManager.getInstance(appContext)
        mqttClientManager.setCallback(this)
        
        // 开始连接状态检查
        handler.post(connectionCheckRunnable)
        
        // 尝试连接MQTT服务器
        if (!mqttClientManager.isConnected()) {
            mqttClientManager.connect()
        }
        
        Log.d(TAG, "GlobalMqttService已初始化")
    }
    
    /**
     * 设置HomeViewModel实例
     */
    fun setHomeViewModel(viewModel: HomeViewModel) {
        this.homeViewModel = viewModel
        
        // 恢复之前保存的数据
        if (lastSensorData.containsKey("temperature")) {
            viewModel.setTemperature(lastSensorData["temperature"] ?: "0")
        }
        if (lastSensorData.containsKey("humidity")) {
            viewModel.setHumidity(lastSensorData["humidity"] ?: "0")
        }
        if (lastSensorData.containsKey("distance")) {
            viewModel.setDistance(lastSensorData["distance"] ?: "0")
        }
        if (lastSensorData.containsKey("lightIntensity")) {
            viewModel.setLightIntensity(lastSensorData["lightIntensity"] ?: "0")
        }
        
        // 恢复模式
        viewModel.setMode(lastMode.value ?: "手动模式")
        
        // 更新连接状态
        viewModel.updateConnectionStatus(connectionStatus.value ?: "未连接")
        
        Log.d(TAG, "HomeViewModel已设置，初始数据已恢复")
    }
    
    /**
     * 检查并确保MQTT连接
     */
    private fun checkMqttConnection() {
        if (!mqttClientManager.isConnected()) {
            Log.d(TAG, "检测到MQTT未连接，尝试重新连接")
            connectionStatus.postValue("正在连接...")
            mqttClientManager.connect()
        } else {
            connectionStatus.postValue("已连接")
        }
    }
    
    /**
     * 订阅必要的主题
     */
    private fun subscribeTopics() {
        mqttClientManager.subscribe("alarm", 1)
        mqttClientManager.subscribe("sensor/data", 1)
        mqttClientManager.subscribe("time", 1)
        mqttClientManager.subscribe("control", 1)
        Log.d(TAG, "已订阅所有必要主题")
    }
    
    // MQTT状态回调实现
    override fun onConnected() {
        connectionStatus.postValue("已连接")
        Log.d(TAG, "MQTT连接成功")
        
        // 连接成功后订阅主题
        subscribeTopics()
        
        // 请求最新数据
        try {
            mqttClientManager.publish("request", "{\"action\":\"getData\"}", 0, false)
            Log.d(TAG, "已请求最新数据")
        } catch (e: Exception) {
            Log.e(TAG, "请求数据失败: ${e.message}")
        }
        
        // 通知HomeViewModel连接状态变化
        homeViewModel?.updateConnectionStatus("已连接")
    }
    
    override fun onConnectionFailed(error: String) {
        connectionStatus.postValue("未连接")
        Log.e(TAG, "MQTT连接失败: $error")
        
        // 通知HomeViewModel连接状态变化
        homeViewModel?.updateConnectionStatus("未连接")
        
        // 安排重新连接
        handler.postDelayed({
            if (!mqttClientManager.isConnected()) {
                mqttClientManager.connect()
                Log.d(TAG, "尝试重新连接MQTT")
            }
        }, 5000) // 5秒后重试
    }
    
    override fun onMessageReceived(topic: String, message: String) {
        Log.d(TAG, "收到消息: 主题=$topic, 内容=$message")
        
        try {
            // 根据不同主题处理消息
            when (topic) {
                "alarm" -> handleAlarmMessage(message)
                "sensor/data" -> handleSensorDataMessage(message)
                "control" -> handleControlMessage(message)
                "time" -> handleTimeMessage(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息时出错: ${e.message}")
        }
    }
    
    private fun handleAlarmMessage(message: String) {
        try {
            val jsonData = JSONObject(message)
            
            // 处理温度(temp)
            if (jsonData.has("temp")) {
                val temp = jsonData.getString("temp")
                homeViewModel?.setTemperature(temp)
                lastSensorData["temperature"] = temp
            }
            
            // 处理湿度(humi)
            if (jsonData.has("humi")) {
                val humi = jsonData.getString("humi")
                homeViewModel?.setHumidity(humi)
                lastSensorData["humidity"] = humi
            }
            
            // 处理距离(dist)
            if (jsonData.has("dist")) {
                val dist = jsonData.getString("dist")
                homeViewModel?.setDistance(dist)
                lastSensorData["distance"] = dist
            }
            
            // 处理光强(lux)
            if (jsonData.has("lux")) {
                val lux = jsonData.getString("lux")
                homeViewModel?.setLightIntensity(lux)
                lastSensorData["lightIntensity"] = lux
            }
            
            // 处理模式(mode)
            if (jsonData.has("mode")) {
                val modeValue = jsonData.getInt("mode")
                val modeText = if (modeValue == 1) "自动模式" else "手动模式"
                homeViewModel?.setMode(modeText)
                lastMode.postValue(modeText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析alarm消息失败: ${e.message}")
        }
    }
    
    private fun handleSensorDataMessage(message: String) {
        try {
            val jsonData = JSONObject(message)
            
            // 处理温度
            if (jsonData.has("temperature")) {
                val temp = jsonData.getString("temperature")
                homeViewModel?.setTemperature(temp)
                lastSensorData["temperature"] = temp
            }
            
            // 处理湿度
            if (jsonData.has("humidity")) {
                val humid = jsonData.getString("humidity")
                homeViewModel?.setHumidity(humid)
                lastSensorData["humidity"] = humid
            }
            
            // 处理距离
            if (jsonData.has("distance")) {
                val dist = jsonData.getString("distance")
                homeViewModel?.setDistance(dist)
                lastSensorData["distance"] = dist
            }
            
            // 处理光强
            if (jsonData.has("light")) {
                val light = jsonData.getString("light")
                homeViewModel?.setLightIntensity(light)
                lastSensorData["lightIntensity"] = light
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析sensor/data消息失败: ${e.message}")
        }
    }
    
    private fun handleControlMessage(message: String) {
        try {
            val jsonData = JSONObject(message)
            
            // 处理模式变化
            if (jsonData.has("mode")) {
                val modeValue = jsonData.getInt("mode")
                val modeText = if (modeValue == 1) "自动模式" else "手动模式"
                homeViewModel?.setMode(modeText)
                lastMode.postValue(modeText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析control消息失败: ${e.message}")
        }
    }
    
    private fun handleTimeMessage(message: String) {
        // 时间消息处理 (如果需要)
    }
    
    /**
     * 发布MQTT消息
     */
    fun publishMessage(topic: String, message: String, qos: Int = 0, retained: Boolean = false) {
        try {
            if (!mqttClientManager.isConnected()) {
                mqttClientManager.connect()
            }
            mqttClientManager.publish(topic, message, qos, retained)
            Log.d(TAG, "消息已发送: 主题=$topic, 内容=$message")
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败: ${e.message}")
        }
    }
    
    /**
     * 请求最新数据
     */
    fun requestLatestData() {
        if (mqttClientManager.isConnected()) {
            try {
                mqttClientManager.publish("request", "{\"action\":\"getData\"}", 0, false)
                Log.d(TAG, "已请求最新数据")
            } catch (e: Exception) {
                Log.e(TAG, "请求数据失败: ${e.message}")
            }
        } else {
            Log.d(TAG, "MQTT未连接，无法请求数据")
            // 尝试重新连接
            mqttClientManager.connect()
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: GlobalMqttService? = null
        
        fun getInstance(context: Context): GlobalMqttService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlobalMqttService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
} 