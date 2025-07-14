package com.example.smarthomelighting.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.smarthomelighting.R
import com.example.smarthomelighting.SmartHomeLightingApplication
import com.example.smarthomelighting.databinding.FragmentHomeBinding
import com.example.smarthomelighting.utils.MqttClientManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.Date

class HomeFragment : Fragment(), MqttClientManager.MqttStatusCallback {

    private val TAG = "HomeFragment"
    private lateinit var homeViewModel: HomeViewModel
    
    // UI组件
    private lateinit var temperatureTextView: TextView
    private lateinit var humidityTextView: TextView
    private lateinit var distanceTextView: TextView
    private lateinit var lightIntensityTextView: TextView
    private var mqttStatusTextView: TextView? = null
    private var deviceStatusTextView: TextView? = null
    private lateinit var modeTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private var humanStatusTextView: TextView? = null
    private var humanStatusIcon: ImageView? = null
    
    // MQTT客户端
    private lateinit var mqttClientManager: MqttClientManager
    
    // ESP8266连接检查相关
    private val ESP_TIMEOUT = 5000 // 5秒超时，修改为5秒
    private val handler = Handler(Looper.getMainLooper())
    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            checkESP8266Connection()
            handler.postDelayed(this, 1000) // 每秒检查一次
        }
    }
    
    // 数据缓存，避免频繁更新UI导致闪烁
    companion object {
        private var cachedTemperature: String = "0.0 °C"
        private var cachedHumidity: String = "0.0 %"
        private var cachedDistance: String = "0 cm"
        private var cachedLightIntensity: String = "0 lux"
        private var cachedCurrentTime: String = "00:00:00"
        private var cachedMode: String = "自动模式"
        
        // 标记是否曾经接收到数据
        private var hasReceivedData: Boolean = false
        // 静态变量，保存最后一次alarm主题数据接收时间
        private var lastDataReceiveTime: Long = 0
    }
    
    // ViewBinding变量
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 获取绑定
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        
        // 获取ViewModel
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)
        
        // 获取MQTT客户端管理器
        try {
            mqttClientManager = SmartHomeLightingApplication.instance.getMqttClientManager()
            mqttClientManager.setCallback(this)
        } catch (e: Exception) {
            Log.e(TAG, "获取MQTT客户端失败: ${e.message}")
        }
        
        // 初始化视图
        initViews()
        
        // 设置观察者
        setupObservers()
        
        // 设置MQTT订阅
        setupMqttSubscriptions()
        
        // 如果从未接收到数据，则初始化时间戳
        if (!hasReceivedData) {
            lastDataReceiveTime = System.currentTimeMillis()
        } else {
            // 如果之前已接收过数据，立即显示缓存数据
            displayCachedData()
        }
        
        // 立即执行一次ESP8266连接状态检查
        checkESP8266Connection()
        
        // 请求最新数据
        try {
            SmartHomeLightingApplication.instance.requestLatestData()
        } catch (e: Exception) {
            Log.e(TAG, "请求数据失败: ${e.message}")
        }
        
        // 设置设置按钮点击事件
        setupSettingsButton()
        
        return root
    }
    
    private fun displayCachedData() {
        // 立即显示缓存的传感器数据
        temperatureTextView.text = "$cachedTemperature °C"
        humidityTextView.text = "$cachedHumidity %"
        distanceTextView.text = "$cachedDistance cm" 
        lightIntensityTextView.text = "$cachedLightIntensity lux"
        modeTextView.text = cachedMode
        currentTimeTextView.text = cachedCurrentTime
        
        // 同时更新ViewModel，确保观察者能正确工作
        homeViewModel.setTemperature(cachedTemperature)
        homeViewModel.setHumidity(cachedHumidity)
        homeViewModel.setDistance(cachedDistance)
        homeViewModel.setLightIntensity(cachedLightIntensity)
        homeViewModel.setMode(cachedMode)
    }
    
    private fun initViews() {
        // 获取视图引用
        temperatureTextView = binding.root.findViewById(R.id.temperature_value)
        humidityTextView = binding.root.findViewById(R.id.humidity_value)
        distanceTextView = binding.root.findViewById(R.id.distance_value)
        lightIntensityTextView = binding.root.findViewById(R.id.light_value)
        mqttStatusTextView = binding.root.findViewById(R.id.mqtt_status_value)
        deviceStatusTextView = binding.root.findViewById(R.id.device_status_value)
        modeTextView = binding.root.findViewById(R.id.mode_value)
        currentTimeTextView = binding.root.findViewById(R.id.current_time_value)
        humanStatusTextView = binding.root.findViewById(R.id.human_status_value)
        humanStatusIcon = binding.root.findViewById(R.id.human_status_icon)
        
        // 初始化连接状态根据当前状态
        updateMqttStatusUI()
    }
    
    private fun updateMqttStatusUI() {
        // 判断MQTT连接状态
        if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
            mqttStatusTextView?.text = "已连接"
            context?.let { ctx ->
                mqttStatusTextView?.setTextColor(ContextCompat.getColor(ctx, R.color.teal_200))
            }
        } else {
            mqttStatusTextView?.text = "未连接"
            context?.let { ctx ->
                mqttStatusTextView?.setTextColor(ContextCompat.getColor(ctx, R.color.status_disconnected))
            }
        }
    }
    
    private fun setupObservers() {
        // 观察温度数据变化
        homeViewModel.temperature.observe(viewLifecycleOwner, {
            temperatureTextView.text = "$it °C"
            cachedTemperature = it  // 缓存数据
        })
        
        // 观察湿度数据变化
        homeViewModel.humidity.observe(viewLifecycleOwner, {
            humidityTextView.text = "$it %"
            cachedHumidity = it  // 缓存数据
        })
        
        // 观察距离数据变化
        homeViewModel.distance.observe(viewLifecycleOwner, {
            distanceTextView.text = "$it cm"
            cachedDistance = it  // 缓存数据
        })
        
        // 观察光强数据变化
        homeViewModel.lightIntensity.observe(viewLifecycleOwner, {
            lightIntensityTextView.text = "$it lux"
            cachedLightIntensity = it  // 缓存数据
        })
        
        // 观察连接状态变化
        homeViewModel.connectionStatus.observe(viewLifecycleOwner, {
            updateConnectionStatusUI(it)
        })
        
        // 观察模式变化
        homeViewModel.mode.observe(viewLifecycleOwner, {
            modeTextView.text = it
            cachedMode = it  // 缓存数据
        })
        
        // 观察人员状态变化
        homeViewModel.humanPresent.observe(viewLifecycleOwner, { isPresent ->
            updateHumanStatusUI(isPresent)
        })
    }
    
    private fun updateConnectionStatusUI(status: String) {
        updateMqttStatusUI()
    }
    
    private fun setupMqttSubscriptions() {
        try {
            // 获取MQTT客户端实例
            mqttClientManager = SmartHomeLightingApplication.instance.getMqttClientManager()
            
            // 设置回调
            mqttClientManager.setCallback(this)
            
            // 更新UI状态
            val isConnected = mqttClientManager.isConnected()
            if (isConnected) {
                homeViewModel.updateConnectionStatus("已连接")
            } else {
                homeViewModel.updateConnectionStatus("正在连接...")
            }
            
            // 更新UI显示
            updateMqttStatusUI()
        } catch (e: Exception) {
            Log.e(TAG, "设置MQTT订阅失败: ${e.message}")
            homeViewModel.updateConnectionStatus("连接失败")
        }
    }
    
    // MQTT状态回调方法
    override fun onConnected() {
        activity?.runOnUiThread {
            homeViewModel.updateConnectionStatus("已连接")
            // 订阅相关主题
            try {
                mqttClientManager.subscribe("alarm", 1)
                mqttClientManager.subscribe("sensor/data", 1)
                mqttClientManager.subscribe("time", 1)  // 订阅时间主题
                mqttClientManager.subscribe("control", 1)  // 订阅控制主题
                Log.d(TAG, "成功订阅主题: alarm, sensor/data, time, control")
            } catch (e: Exception) {
                Log.e(TAG, "订阅主题失败: ${e.message}")
            }
        }
    }
    
    override fun onConnectionFailed(error: String) {
        activity?.runOnUiThread {
            // 不再显示详细错误信息，只显示"未连接"
            homeViewModel.updateConnectionStatus("未连接")
            Log.e(TAG, "MQTT连接失败: $error") // 仅记录日志，不显示给用户
        }
    }
    
    private fun checkESP8266Connection() {
        // 检查是否超过超时时间未收到alarm主题数据
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastDataReceiveTime
        
        if (hasReceivedData && elapsedTime > ESP_TIMEOUT) {
            // 如果超过5秒未收到alarm数据，认为设备已断开
            deviceStatusTextView?.text = "已断开"
            context?.let { ctx ->
                deviceStatusTextView?.setTextColor(ContextCompat.getColor(ctx, R.color.status_disconnected))
            }
            Log.d(TAG, "设备状态：已断开 (${elapsedTime/1000}秒未收到alarm数据)")
        } else if (hasReceivedData) {
            // 如果在5秒内收到过alarm数据，认为设备在线
            deviceStatusTextView?.text = "已连接"
            context?.let { ctx ->
                deviceStatusTextView?.setTextColor(ContextCompat.getColor(ctx, R.color.teal_200))
            }
            // 添加日志以便于调试
            Log.d(TAG, "设备状态：已连接 (${elapsedTime/1000}秒前收到alarm数据)")
        } else {
            // 从未收到过alarm数据，显示等待中状态
            deviceStatusTextView?.text = "等待连接"
            context?.let { ctx ->
                deviceStatusTextView?.setTextColor(ContextCompat.getColor(ctx, R.color.status_waiting))
            }
            Log.d(TAG, "设备状态：等待连接 (从未收到alarm数据)")
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 开始检查ESP8266连接状态
        handler.post(connectionCheckRunnable)
        
        // 确保重新设置MQTT回调
        if (::mqttClientManager.isInitialized) {
            mqttClientManager.setCallback(this)
        }
        
        // 如果有缓存数据，立即显示
        if (hasReceivedData) {
            displayCachedData()
        }
        
        // 更新MQTT状态显示
        updateMqttStatusUI()
    }
    
    override fun onPause() {
        super.onPause()
        
        // 不再停止ESP8266连接检查，允许后台刷新
        // handler.removeCallbacks(connectionCheckRunnable)
        
        // 取消设置回调器但保持MQTT连接
        if (::mqttClientManager.isInitialized) {
            // 不再移除回调，保持接收消息
            // mqttClientManager.setCallback(null)
        }
        
        Log.d(TAG, "暂停Fragment但保持后台连接和数据更新")
    }
    
    override fun onStop() {
        super.onStop()
        
        // 页面停止时仍然保持MQTT连接
        // 但降低检查频率以节省资源
        handler.removeCallbacks(connectionCheckRunnable)
        handler.postDelayed(connectionCheckRunnable, 5000) // 改为5秒检查一次
        
        Log.d(TAG, "停止Fragment但保持后台连接，降低刷新频率")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 只在Fragment真正销毁时停止所有操作
        handler.removeCallbacks(connectionCheckRunnable)
        
        // 不断开MQTT连接，只解除回调
        if (::mqttClientManager.isInitialized) {
            mqttClientManager.setCallback(null)
            Log.d(TAG, "Fragment销毁，移除MQTT回调但保持连接")
        }
    }
    
    // 添加在Activity级别保持连接的方法
    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        
        try {
            // 获取MQTT客户端
            mqttClientManager = SmartHomeLightingApplication.instance.getMqttClientManager()
            
            // 立即设置回调
            mqttClientManager.setCallback(this)
            
            // 确保连接已建立
            if (!mqttClientManager.isConnected()) {
                mqttClientManager.connect()
                Log.d(TAG, "Fragment附加到Activity，确保MQTT连接")
            }
            
            // 启动连接状态检查
            handler.post(connectionCheckRunnable)
            
            Log.d(TAG, "Fragment附加到Activity，启动后台服务")
        } catch (e: Exception) {
            Log.e(TAG, "附加Fragment时获取MQTT客户端失败: ${e.message}")
        }
    }
    
    override fun onMessageReceived(topic: String, message: String) {
        activity?.runOnUiThread {
            Log.d(TAG, "收到数据: $topic -> $message")
            
            when (topic) {
                "time" -> {
                    try {
                        val data = JSONObject(message)
                        if (data.has("current_time")) {
                            val time = data.getString("current_time")
                            cachedCurrentTime = time
                            currentTimeTextView.text = time
                            
                            // time主题消息不算作ESP8266在线证据
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析时间数据失败: ${e.message}")
                    }
                }
                "sensor/data" -> {
                    try {
                        val data = JSONObject(message)
                        
                        if (data.has("temperature")) {
                            val temp = data.getString("temperature")
                            homeViewModel.setTemperature(temp)
                            cachedTemperature = temp
                        }
                        
                        if (data.has("humidity")) {
                            val humid = data.getString("humidity")
                            homeViewModel.setHumidity(humid)
                            cachedHumidity = humid
                        }
                        
                        if (data.has("distance")) {
                            val dist = data.getString("distance")
                            homeViewModel.setDistance(dist)
                            cachedDistance = dist
                        }
                        
                        if (data.has("light")) {
                            val light = data.getString("light")
                            homeViewModel.setLightIntensity(light)
                            cachedLightIntensity = light
                        }
                        
                        // sensor/data主题消息不算作ESP8266在线证据
                    } catch (e: Exception) {
                        Log.e(TAG, "解析数据失败: ${e.message}")
                    }
                }
                "alarm" -> {
                    try {
                        // 尝试解析为JSON
                        val jsonData = JSONObject(message)
                        
                        // 更新最后一次alarm数据接收时间
                        lastDataReceiveTime = System.currentTimeMillis()
                        hasReceivedData = true
                        Log.d(TAG, "收到alarm数据，更新设备连接状态时间戳")
                        
                        // 处理温度(temp)
                        if (jsonData.has("temp")) {
                            val temp = jsonData.getString("temp")
                            homeViewModel.setTemperature(temp)
                            cachedTemperature = temp
                        }
                        
                        // 处理湿度(humi)
                        if (jsonData.has("humi")) {
                            val humi = jsonData.getString("humi")
                            homeViewModel.setHumidity(humi)
                            cachedHumidity = humi
                        }
                        
                        // 处理人体存在(human)
                        if (jsonData.has("human")) {
                            val human = jsonData.getInt("human")
                            homeViewModel.setHumanPresent(human == 1)
                        }
                        
                        // 处理距离(dist)
                        if (jsonData.has("dist")) {
                            val dist = jsonData.getString("dist")
                            homeViewModel.setDistance(dist)
                            cachedDistance = dist
                        }
                        
                        // 处理光强(lux)
                        if (jsonData.has("lux")) {
                            val lux = jsonData.getString("lux")
                            homeViewModel.setLightIntensity(lux)
                            cachedLightIntensity = lux
                        }
                        
                        // 处理模式(mode)
                        if (jsonData.has("mode")) {
                            val modeValue = jsonData.getInt("mode")
                            val modeText = if (modeValue == 1) "自动模式" else "手动模式"
                            homeViewModel.setMode(modeText)
                            cachedMode = modeText
                        }
                    } catch (e: Exception) {
                        // 如果不是JSON，记录错误但不显示通知
                        Log.e(TAG, "解析警报数据失败: ${e.message}")
                    }
                }
                "control" -> {
                    try {
                        // 处理控制指令消息
                        val jsonData = JSONObject(message)
                        Log.d(TAG, "收到控制指令: $message")
                        
                        // control主题消息不算作ESP8266在线证据
                        
                        // 示例：如果包含level1字段，说明是灯光控制指令
                        if (jsonData.has("level1")) {
                            val level1 = jsonData.getInt("level1")
                            Log.d(TAG, "收到灯光控制指令: level1=$level1")
                            // 可以在这里根据指令执行操作
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "解析控制指令失败: ${e.message}")
                    }
                }
            }
        }
    }
    
    // 更新人员状态UI
    private fun updateHumanStatusUI(isPresent: Boolean) {
        if (isPresent) {
            humanStatusTextView?.text = "有人"
            context?.let { ctx ->
                humanStatusIcon?.setColorFilter(
                    ContextCompat.getColor(ctx, R.color.tech_amber),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        } else {
            humanStatusTextView?.text = "无人"
            context?.let { ctx ->
                humanStatusIcon?.setColorFilter(
                    ContextCompat.getColor(ctx, R.color.status_disconnected),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        }
    }

    /**
     * 设置设置按钮点击事件
     */
    private fun setupSettingsButton() {
        binding.settingsButton?.setOnClickListener {
            // 导航到设置页面
            findNavController().navigate(R.id.navigation_settings)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}