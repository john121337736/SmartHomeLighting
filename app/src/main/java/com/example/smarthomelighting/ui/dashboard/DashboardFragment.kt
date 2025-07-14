package com.example.smarthomelighting.ui.dashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.smarthomelighting.R
import com.example.smarthomelighting.utils.MqttClientManager
import android.util.Log
import com.google.android.material.slider.Slider
import kotlin.math.max
import org.json.JSONObject
import com.google.android.material.button.MaterialButton

class DashboardFragment : Fragment(), MqttClientManager.MqttStatusCallback {

    private val TAG = "DashboardFragment"
    
    private lateinit var dashboardViewModel: DashboardViewModel
    
    // 吊灯显示控件
    private var currentStatusValue: TextView? = null
    private var coldLightBulb: View? = null
    private var warmLightBulb: View? = null
    private var redLightBulb: View? = null
    private var blueLightBulb: View? = null
    
    // 冷暖光控制滑块
    private var coldLightSlider: Slider? = null
    private var warmLightSlider: Slider? = null
    private var coldLightLevel: TextView? = null
    private var warmLightLevel: TextView? = null
    
    // 红灯蓝灯控制滑块
    private var redLightSlider: Slider? = null
    private var blueLightSlider: Slider? = null
    private var redLightSliderLevel: TextView? = null
    private var blueLightSliderLevel: TextView? = null
    
    // 灯光状态
    private var activeLightTypes = mutableSetOf<String>()

    // 传感器数据显示控件
    private var redLightValue: TextView? = null
    private var coldLightSensorValue: TextView? = null
    private var blueLightValue: TextView? = null
    private var warmLightSensorValue: TextView? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_dashboard, container, false)
        
        // 获取ViewModel实例
        dashboardViewModel = ViewModelProvider(this).get(DashboardViewModel::class.java)
        
        // 初始化视图
        initViews(root)
        
        // 设置观察者
        setupObservers()
        
        // 设置事件监听
        setupListeners()
        
        // 强制初始化MQTT连接
        initializeMqttConnection()
        
        // 确保模式按钮初始化
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 在执行延迟任务前检查Fragment是否仍然附加到Activity
            if (!isAdded || isDetached || activity == null) {
                Log.d(TAG, "Fragment已不再附加到Activity，取消初始化操作")
                return@postDelayed
            }
            
            setupModeButtons()
            
            // 查询当前模式
            queryCurrentMode()
            
            // 延迟初始化灯光状态，防止与MQTT初始化冲突
        initializeLightState()
        }, 500)  // 延迟500毫秒，确保其他初始化已完成
        
        // 启动数据刷新定时器
        startDataRefreshTimer()
        
        return root
    }
    
    // 查询当前系统模式
    private fun queryCurrentMode() {
        try {
            val request = "{\"action\":\"getMode\"}"
            mqttClientManager.publish("request", request, 1, false)
            Log.d(TAG, "已请求当前模式状态")
        } catch (e: Exception) {
            Log.e(TAG, "请求当前模式状态失败: ${e.message}")
        }
    }
    
    private fun initViews(root: View) {
        // 控制控件
        // colorButtonGroup = root.findViewById(R.id.color_button_group)
        // coldColorBtn = root.findViewById(R.id.cold_color_btn)
        // warmColorBtn = root.findViewById(R.id.warm_color_btn)
        
        // 吊灯显示控件
        currentStatusValue = root.findViewById(R.id.current_status_value)
        coldLightBulb = root.findViewById(R.id.cold_light_bulb)
        warmLightBulb = root.findViewById(R.id.warm_light_bulb)
        redLightBulb = root.findViewById(R.id.red_light_bulb)
        blueLightBulb = root.findViewById(R.id.blue_light_bulb)
        
        // 设置灯泡颜色
        coldLightBulb?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.light_cold_off)
        )
        warmLightBulb?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.light_warm_off)
        )
        redLightBulb?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.light_red_off)
        )
        blueLightBulb?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.light_blue_off)
        )
        
        // 冷暖光控制滑块
        coldLightSlider = root.findViewById(R.id.cold_light_slider)
        warmLightSlider = root.findViewById(R.id.warm_light_slider)
        coldLightLevel = root.findViewById(R.id.cold_light_level)
        warmLightLevel = root.findViewById(R.id.warm_light_level)

        // 红灯蓝灯控制滑块
        redLightSlider = root.findViewById(R.id.red_light_slider)
        blueLightSlider = root.findViewById(R.id.blue_light_slider)
        redLightSliderLevel = root.findViewById(R.id.red_light_slider_level)
        blueLightSliderLevel = root.findViewById(R.id.blue_light_slider_level)

        // 初始化传感器数据显示控件
        redLightValue = root.findViewById(R.id.red_light_value)
        coldLightSensorValue = root.findViewById(R.id.cold_light_sensor_value)
        blueLightValue = root.findViewById(R.id.blue_light_value)
        warmLightSensorValue = root.findViewById(R.id.warm_light_sensor_value)
        
        // 移除测试代码，防止亮度条自动变化
        // testRedBulbLevels()
    }
    
    private fun initializeLightState() {
        val coldBrightness = max(1, dashboardViewModel.coldLightLevel.value ?: 3)
        val warmBrightness = max(1, dashboardViewModel.warmLightLevel.value ?: 3)
        val redBrightness = max(1, dashboardViewModel.redLightBrightness.value ?: 1)
        val blueBrightness = max(1, dashboardViewModel.blueLightBrightness.value ?: 1)
        
        dashboardViewModel.setColdLightLevel(coldBrightness)
        dashboardViewModel.setWarmLightLevel(warmBrightness)
        dashboardViewModel.setRedLightBrightness(redBrightness)
        dashboardViewModel.setBlueLightBrightness(blueBrightness)
        
        // 使用当前模式，如果没有则默认为手动模式
        val currentMode = dashboardViewModel.currentMode.value ?: 0
        updateModeButtonsUI(currentMode)
        
        // 更新状态显示
        updateStatusDisplay()
        updateBulbsDisplay()
    }
    
    private fun setupObservers() {
        dashboardViewModel.lightColor.observe(viewLifecycleOwner) { color ->
            // 直接调用更新按钮选中状态，不再管理监听器
            updateStatusDisplay()
        }

        // 观察冷光亮度级别变化
        dashboardViewModel.coldLightLevel.observe(viewLifecycleOwner) { level ->
            if (coldLightSlider?.value?.toInt() != level) {
                coldLightSlider?.value = level.toFloat()
            }
            coldLightLevel?.text = level.toString()
            updateStatusDisplay()
        }
        
        // 观察暖光亮度级别变化
        dashboardViewModel.warmLightLevel.observe(viewLifecycleOwner) { level ->
            if (warmLightSlider?.value?.toInt() != level) {
                warmLightSlider?.value = level.toFloat()
            }
            warmLightLevel?.text = level.toString()
            updateStatusDisplay()
        }
    }
    
    private fun setupListeners() {
        // --- ButtonGroup 监听器更新 ---
        // colorButtonGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
        //     // 增加判断，仅当按钮确实被用户选中时才处理
        //     if (isChecked && group.isPressed) {
        //         val selectedColor = when (checkedId) {
        //             R.id.cold_color_btn -> "冷光"
        //             R.id.warm_color_btn -> "暖光"
        //             else -> dashboardViewModel.lightColor.value ?: "冷光"
        //         }
        //         // 检查 ViewModel 中的值是否真的改变了
        //         if (dashboardViewModel.lightColor.value != selectedColor) {
        //             dashboardViewModel.setLightColor(selectedColor)
        //             
        //             // 根据选择的颜色模式发送控制命令
        //             if (selectedColor == "冷光") {
        //                 // 冷光模式：把冷光设为当前级别，暖光设为0
        //                 val coldLevel = dashboardViewModel.coldLightLevel.value ?: 3
        //                 publishColdLightLevelCommand(coldLevel)
        //                 publishWarmLightLevelCommand(1) // 最低级别
        //             } else {
        //                 // 暖光模式：把暖光设为当前级别，冷光设为0
        //                 val warmLevel = dashboardViewModel.warmLightLevel.value ?: 3
        //                 publishWarmLightLevelCommand(warmLevel)
        //                 publishColdLightLevelCommand(1) // 最低级别
        //             }
        //         }
        //     }
        // }

        // 冷光亮度滑块监听
        coldLightSlider?.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                // 标记用户正在调整滑块
                isUserAdjustingSlider = true
                
                val level = value.toInt()
                coldLightLevel?.text = level.toString()
                // 直接更新灯泡显示
                dashboardViewModel.setColdLightLevel(level)
                updateBulbsFromControls()
            }
        }
        coldLightSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // 标记用户开始触摸滑块
                isUserAdjustingSlider = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val level = slider.value.toInt()
                dashboardViewModel.setColdLightLevel(level)
                publishColdLightLevelCommand(level)
                
                // 用户停止操作滑块后，设置标记允许延迟刷新
                isUserAdjustingSlider = false
                shouldRefreshUI = true
            }
        })
        
        // 暖光亮度滑块监听
        warmLightSlider?.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                // 标记用户正在调整滑块
                isUserAdjustingSlider = true
                
                val level = value.toInt()
                warmLightLevel?.text = level.toString()
                // 直接更新灯泡显示
                dashboardViewModel.setWarmLightLevel(level)
                updateBulbsFromControls()
            }
        }
        warmLightSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // 标记用户开始触摸滑块
                isUserAdjustingSlider = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val level = slider.value.toInt()
                dashboardViewModel.setWarmLightLevel(level)
                publishWarmLightLevelCommand(level)
                
                // 用户停止操作滑块后，设置标记允许延迟刷新
                isUserAdjustingSlider = false
                shouldRefreshUI = true
            }
        })

        // 红灯亮度滑块监听
        redLightSlider?.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                // 标记用户正在调整滑块
                isUserAdjustingSlider = true
                
                val level = value.toInt()
                redLightSliderLevel?.text = level.toString()
                // 更新ViewModel
                dashboardViewModel.setRedLightBrightness(level)
                // 直接更新UI显示
                updateRedBulbBrightnessImmediate(level)
            }
        }
        redLightSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // 标记用户开始触摸滑块
                isUserAdjustingSlider = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val level = slider.value.toInt()
                dashboardViewModel.setRedLightBrightness(level)
                publishRedLightLevelCommand(level)
                
                // 用户停止操作滑块后，设置标记允许延迟刷新
                isUserAdjustingSlider = false
                shouldRefreshUI = true
            }
        })
        
        // 蓝灯亮度滑块监听
        blueLightSlider?.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                // 标记用户正在调整滑块
                isUserAdjustingSlider = true
                
                val level = value.toInt()
                blueLightSliderLevel?.text = level.toString()
                // 更新ViewModel
                dashboardViewModel.setBlueLightBrightness(level)
                // 更新UI显示
                updateBulbsFromControls()
            }
        }
        blueLightSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // 标记用户开始触摸滑块
                isUserAdjustingSlider = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val level = slider.value.toInt()
                dashboardViewModel.setBlueLightBrightness(level)
                publishBlueLightLevelCommand(level)
                
                // 用户停止操作滑块后，设置标记允许延迟刷新
                isUserAdjustingSlider = false
                shouldRefreshUI = true
            }
        })

        // 设置亮度变化监听
        dashboardViewModel.coldLightLevel.observe(viewLifecycleOwner) { value ->
        updateStatusDisplay()
    }

        dashboardViewModel.warmLightLevel.observe(viewLifecycleOwner) { value ->
            updateStatusDisplay()
        }

        dashboardViewModel.redLightBrightness.observe(viewLifecycleOwner) { value ->
            updateStatusDisplay()
        }

        dashboardViewModel.blueLightBrightness.observe(viewLifecycleOwner) { value ->
        updateStatusDisplay()
        }
    }
    
    private fun updateStatusDisplay() {
        try {
            // 减少UI更新频率，只在必要时更新
            if (!isAdded || view == null) return
            
            // 获取数据但限制更新频率
            val redLevel = dashboardViewModel.redLightBrightness.value ?: 1
            val coldLevel = dashboardViewModel.coldLightLevel.value ?: 1
            val blueLevel = dashboardViewModel.blueLightBrightness.value ?: 1
            val warmLevel = dashboardViewModel.warmLightLevel.value ?: 1
            
            // 更新灯泡显示状态
            updateBulbsDisplay()
            
            // 更新状态文本，但直接使用缓存，避免频繁刷新
            updateStatusTextFromBulbs()
            
            // 传感器数据容器默认隐藏，以避免性能问题
            view?.findViewById<View>(R.id.sensor_data_container)?.visibility = View.GONE
            
        } catch (e: Exception) {
            Log.e(TAG, "更新状态显示时出错: ${e.message}", e)
        }
    }
    
    // 根据控制面板的设置更新灯泡显示
    private fun updateBulbsFromControls() {
        // 获取原始亮度值
        val rawColdBrightness = dashboardViewModel.coldLightLevel.value ?: 0
        val rawWarmBrightness = dashboardViewModel.warmLightLevel.value ?: 0
        val rawRedBrightness = dashboardViewModel.redLightBrightness.value ?: 0
        val rawBlueBrightness = dashboardViewModel.blueLightBrightness.value ?: 0
        
        // 清空当前活跃灯光类型
        activeLightTypes.clear()
        
        // 根据原始亮度判断是否激活对应灯泡
        if (rawColdBrightness > 1) {
            activeLightTypes.add("冷光")
            coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_on))
        } else {
            coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_off))
        }
        
        if (rawWarmBrightness > 1) {
            activeLightTypes.add("暖光")
            warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_on))
        } else {
            warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_off))
        }
        
        if (rawRedBrightness > 1) {
            activeLightTypes.add("红色")
            redLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_red_on))
        } else {
            redLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_red_off))
        }
        
        if (rawBlueBrightness > 1) {
            activeLightTypes.add("蓝色")
            blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_on))
        } else {
            blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_off))
        }
        
        // 更新状态文本
        updateStatusTextFromBulbs()
    }
    
    // 发送冷光亮度级别控制命令
    private fun publishColdLightLevelCommand(level: Int) {
        val command = "{\"level1\":$level}"
        MqttClientManager.getInstance(context).publish("control", command, 0, false)
        Log.d(TAG, "Published cold light command: $command")
    }
    
    // 发送暖光亮度级别控制命令
    private fun publishWarmLightLevelCommand(level: Int) {
        val command = "{\"level3\":$level}"
        MqttClientManager.getInstance(context).publish("control", command, 0, false)
        Log.d(TAG, "Published warm light command: $command")
    }

    // 发送红灯亮度级别控制命令
    private fun publishRedLightLevelCommand(level: Int) {
        val command = "{\"level\":$level}"
        MqttClientManager.getInstance(context).publish("control", command, 0, false)
        Log.d(TAG, "Published red light command: $command")
    }
    
    // 发送蓝灯亮度级别控制命令
    private fun publishBlueLightLevelCommand(level: Int) {
        val command = "{\"level2\":$level}"
        MqttClientManager.getInstance(context).publish("control", command, 0, false)
        Log.d(TAG, "Published blue light command: $command")
    }

    private lateinit var mqttClientManager: MqttClientManager

    private fun subscribeMqttTopics() {
        context?.let {
            mqttClientManager = MqttClientManager.getInstance(it)
            mqttClientManager.setCallback(this)
            
            // 订阅传感器数据主题 - 修复参数错误
            mqttClientManager.subscribe("sensor", 1)
            mqttClientManager.subscribe("alarm", 1)
            mqttClientManager.subscribe("control", 1)
            
            Log.d(TAG, "已订阅主题: sensor, alarm, control")
            
            // 连接后马上请求一次当前数据
            requestSensorData()
            
            // 延迟500ms请求一次警报数据，确保UI更新
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                requestAlarmData()
            }, 500)
        } ?: Log.e(TAG, "Context is null, cannot subscribe.")
    }

    override fun onConnected() {
        Log.d(TAG, "MQTT连接成功")
        activity?.runOnUiThread {
            // 移除binding对象的引用，使用Toast显示连接状态
            android.widget.Toast.makeText(context, "MQTT已连接", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        // 连接成功后订阅主题
        subscribeMqttTopics()
        
        // 请求初始数据
        requestInitialData()
    }

    /**
     * 请求初始化所有数据
     */
    private fun requestInitialData() {
        // 请求传感器数据
        requestSensorData()
        
        // 请求警报数据
        requestAlarmData()
        
        // 请求控制模式数据
        requestControlData()
    }

    private fun requestControlData() {
        val jsonObject = JSONObject()
        try {
            jsonObject.put("command", "getMode")
            // 移除时间戳，不再添加time字段
            
            mqttClientManager.publish("control", jsonObject.toString(), 1, false)
            Log.d(TAG, "请求控制模式数据: $jsonObject")
        } catch (e: Exception) {
            Log.e(TAG, "请求控制模式数据失败", e)
        }
    }

    override fun onConnectionFailed(error: String) {
        Log.e(TAG, "MQTT Connection Failed in DashboardFragment: $error")
    }

    override fun onMessageReceived(topic: String, message: String) {
        Log.d(TAG, "收到MQTT消息 [主题:$topic]: $message")
        
        // 加上try-catch确保消息处理的稳定性
        try {
            // 如果是alarm主题，记录额外日志
            if (topic == "alarm") {
                Log.d(TAG, "收到重要的alarm消息，立即处理并更新UI")
                
                // 尝试从alarm消息中提取模式信息
                if (message.contains("mode")) {
                    try {
                        val jsonStartIndex = message.indexOf("{")
                        val jsonEndIndex = message.lastIndexOf("}") + 1
                        if (jsonStartIndex >= 0 && jsonEndIndex > jsonStartIndex) {
                            val jsonStr = message.substring(jsonStartIndex, jsonEndIndex)
                            val jsonObj = JSONObject(jsonStr)
                            
                            if (jsonObj.has("mode")) {
                                val modeValue = jsonObj.getInt("mode")
                                Log.d(TAG, "从alarm消息解析到模式: $modeValue")
                                
                                // 保存到ViewModel
                                dashboardViewModel.setCurrentMode(modeValue)
                                
                                // 更新UI
                                activity?.runOnUiThread {
                                    updateModeButtonsUI(modeValue)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "从alarm消息解析模式失败: ${e.message}")
                    }
                }
            }
            
            activity?.runOnUiThread {
                when (topic) {
                    "control" -> {
                        Log.d(TAG, "处理control主题消息")
                        handleControlMessage(message)
                    }
                    "alarm" -> {
                        Log.d(TAG, "处理alarm主题消息")
                        handleAlarmMessage(message)
                    }
                    "sensor" -> {
                        Log.d(TAG, "处理sensor主题消息")
                        handleSensorMessage(message)
                    }
                    else -> {
                        Log.d(TAG, "未知主题消息，尝试作为状态更新处理")
                        handleLightStatusMessage(message)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息时出错: ${e.message}", e)
            // 即使出错也尝试更新UI
            activity?.runOnUiThread {
                if (!isUserAdjustingSlider) {
                    updateStatusDisplay()
            } else {
                    // 如果用户正在操作，设置标记以便后续刷新
                    shouldRefreshUI = true
                }
            }
        }
    }

    // 处理灯光控制消息
    private fun handleControlMessage(message: String) {
        try {
            Log.d(TAG, "收到control消息: $message")
            
            // 图中消息格式例如 {"level1":1}红色，需要正确解析
            if (message.contains("{") && message.contains("}")) {
                // 提取JSON部分
                val jsonStartIndex = message.indexOf("{")
                val jsonEndIndex = message.lastIndexOf("}") + 1
                val jsonStr = message.substring(jsonStartIndex, jsonEndIndex)
                
                Log.d(TAG, "JSON部分: $jsonStr")
                
                val json = org.json.JSONObject(jsonStr)
                
                // 处理模式消息
                if (json.has("mode")) {
                    val mode = json.optInt("mode", -1)
                    val modeName = when (mode) {
                        0 -> "手动模式"
                        1 -> "自动模式"
                        else -> "未知模式"
                    }
                    Log.d(TAG, "模式切换: $modeName")
                    updateModeStatus(modeName)
                    
                    // 保存到ViewModel
                    dashboardViewModel.setCurrentMode(mode)
                    
                    activity?.runOnUiThread {
                        updateModeButtonsUI(mode)
                    }
                    return
                }
                
                // 处理灯光模式命令
                if (json.has("lightMode")) {
                    val lightMode = json.getString("lightMode")
                    
                    // 映射灯光模式到模式索引
                    val modeIndex = when (lightMode) {
                        "alarm" -> 2
                        "fast" -> 3
                        "constant" -> 4
                        else -> dashboardViewModel.currentMode.value ?: 0
                    }
                    
                    // 保存到ViewModel
                    dashboardViewModel.setCurrentMode(modeIndex)
                    
                    activity?.runOnUiThread {
                        updateModeButtonsUI(modeIndex)
                    }
                    return
                }
                
                // 获取所有可能的亮度值
                val originalRedLevel = if (json.has("level")) json.getInt("level") else dashboardViewModel.redLightBrightness.value ?: 0
                val originalColdLevel = if (json.has("level1")) json.getInt("level1") else dashboardViewModel.coldLightLevel.value ?: 0
                val originalBlueLevel = if (json.has("level2")) json.getInt("level2") else dashboardViewModel.blueLightBrightness.value ?: 0
                val originalWarmLevel = if (json.has("level3")) json.getInt("level3") else dashboardViewModel.warmLightLevel.value ?: 0
                
                Log.d(TAG, "处理control消息 - 原始亮度值: 红=$originalRedLevel, 冷=$originalColdLevel, 蓝=$originalBlueLevel, 暖=$originalWarmLevel")
                
                // 处理灯光类型
                var lightType = ""
                
                // 确定灯光类型
                if (message.contains("红色")) {
                    lightType = "红色"
                } else if (message.contains("蓝色")) {
                    lightType = "蓝色"
                } else if (message.contains("冷色") || message.contains("冷光")) {
                    lightType = "冷光"
                } else if (message.contains("暖色") || message.contains("暖光")) {
                    lightType = "暖光"
                } else if (json.has("level1") && !json.has("level2") && !json.has("level3")) {
                    // 如果只有level1，默认为冷光
                    lightType = "冷光"
                } else if (json.has("level3") && !json.has("level1") && !json.has("level2")) {
                    // 如果只有level3，默认为暖光
                    lightType = "暖光"
                } else if (json.has("level")) {
                    // 如果有level，默认为红色
                    lightType = "红色"
                } else if (json.has("level2")) {
                    // 如果有level2，默认为蓝色
                    lightType = "蓝色"
                }
                
                Log.d(TAG, "识别到灯光类型: $lightType")
                
                // 保存到ViewModel - 使用原始值
                if (json.has("level")) dashboardViewModel.setRedLightBrightness(originalRedLevel)
                if (json.has("level1")) dashboardViewModel.setColdLightLevel(originalColdLevel)
                if (json.has("level2")) dashboardViewModel.setBlueLightBrightness(originalBlueLevel)
                if (json.has("level3")) dashboardViewModel.setWarmLightLevel(originalWarmLevel)
                
                if (lightType.isNotEmpty()) {
                    dashboardViewModel.setLightColor(lightType)
                }
                
                // 根据亮度判断是否激活灯光 - 用原始值判断
                activeLightTypes.clear()
                
                if (originalRedLevel > 1) activeLightTypes.add("红色")
                if (originalColdLevel > 1) activeLightTypes.add("冷光")
                if (originalBlueLevel > 1) activeLightTypes.add("蓝色") 
                if (originalWarmLevel > 1) activeLightTypes.add("暖光")
                
                // 更新UI显示
                activity?.runOnUiThread {
                    // 更新传感器数值显示 - 使用原始值显示
                    updateSensorValues(originalRedLevel, originalColdLevel, originalBlueLevel, originalWarmLevel)
                    
                    // 直接更新滑块位置，确保用户能看到实际亮度值
                    if (!isUserAdjustingSlider) {
                        // 计算安全值(用于滑块位置)
                        val safeRedLevel = max(1, originalRedLevel)
                        val safeColdLevel = max(1, originalColdLevel)
                        val safeBlueLevel = max(1, originalBlueLevel)
                        val safeWarmLevel = max(1, originalWarmLevel)
                        
                        // 设置滑块位置 - 使用安全值(防止IllegalStateException)
                        setSliderPosition(safeRedLevel, safeColdLevel, safeBlueLevel, safeWarmLevel)
                        
                        // 设置显示的文本 - 使用原始值
                        updateSliderTextValues(originalRedLevel, originalColdLevel, originalBlueLevel, originalWarmLevel)
        } else {
                        // 设置标记，等用户操作结束后再刷新
                        shouldRefreshUI = true
                    }
                    
                    // 更新状态显示
                    updateStatusDisplay()
                    
                    // 强制更新红灯状态
                    if (json.has("level")) {
                        updateRedBulbBrightnessImmediate(originalRedLevel)
                    }
                    
                    // 更新灯泡显示
                    updateBulbsDisplay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理control消息时出错: ${e.message}", e)
        }
    }
    
    // 更新模式状态显示
    private fun updateModeStatus(modeName: String) {
        // 保留当前灯光状态信息，添加模式信息
        val currentText = currentStatusValue?.text.toString()
        val lightColor = dashboardViewModel.lightColor.value ?: "冷光"
        val coldLevel = dashboardViewModel.coldLightLevel.value ?: 3
        val warmLevel = dashboardViewModel.warmLightLevel.value ?: 3
        
        // 根据亮度判断灯光状态
        val isLightOn = when (lightColor) {
            "冷光" -> coldLevel > 1
            "暖光" -> warmLevel > 1
            "红色", "蓝色" -> coldLevel > 1
            else -> coldLevel > 1 || warmLevel > 1
        }
        
        if (isLightOn) {
            // 如果灯是开着的，在当前状态文本后添加模式信息
            currentStatusValue?.text = "$currentText ($modeName)"
        } else {
            // 如果灯是关着的，显示灯光类型和模式
            when (lightColor) {
                "冷光" -> currentStatusValue?.text = "冷光 ($modeName)"
                "暖光" -> currentStatusValue?.text = "暖光 ($modeName)"
                "红色" -> currentStatusValue?.text = "红色 ($modeName)"
                "蓝色" -> currentStatusValue?.text = "蓝色 ($modeName)"
                else -> currentStatusValue?.text = modeName
            }
        }
    }

    // 处理来自alarm主题的消息
    private fun handleAlarmMessage(message: String) {
        try {
            Log.d(TAG, "收到alarm消息: $message")
            
            // 解析消息格式
            if (message.contains("{") && message.contains("}")) {
                // 提取JSON部分
                val jsonStartIndex = message.indexOf("{")
                val jsonEndIndex = message.lastIndexOf("}") + 1
                val jsonStr = message.substring(jsonStartIndex, jsonEndIndex)
                
                Log.d(TAG, "JSON部分: $jsonStr")
                
                val json = org.json.JSONObject(jsonStr)
                
                // 处理模式信息
                if (json.has("mode")) {
                    val modeValue = json.optInt("mode", 0)
                    Log.d(TAG, "从alarm消息中提取到模式值: $modeValue")
                    
                    // 保存到ViewModel
                    dashboardViewModel.setCurrentMode(modeValue)
                    
                    // 更新UI
                    activity?.runOnUiThread {
                        updateModeButtonsUI(modeValue)
                    }
                }
                
                // 获取原始亮度值 - 不应用安全值修正
                val originalRedLevel = if (json.has("level")) json.getInt("level") else dashboardViewModel.redLightBrightness.value ?: 0
                val originalColdLevel = if (json.has("level1")) json.getInt("level1") else dashboardViewModel.coldLightLevel.value ?: 0
                val originalBlueLevel = if (json.has("level2")) json.getInt("level2") else dashboardViewModel.blueLightBrightness.value ?: 0
                val originalWarmLevel = if (json.has("level3")) json.getInt("level3") else dashboardViewModel.warmLightLevel.value ?: 0
                
                // 只有在保存到ViewModel和使用滑块时才应用安全值
                val safeRedLevel = max(1, originalRedLevel)
                val safeColdLevel = max(1, originalColdLevel)
                val safeBlueLevel = max(1, originalBlueLevel)
                val safeWarmLevel = max(1, originalWarmLevel)
                
                Log.d(TAG, "原始传感器数据: 红灯level=$originalRedLevel, 冷光level1=$originalColdLevel, 蓝灯level2=$originalBlueLevel, 暖光level3=$originalWarmLevel")
                Log.d(TAG, "安全值(用于滑块): 红灯=$safeRedLevel, 冷光=$safeColdLevel, 蓝灯=$safeBlueLevel, 暖光=$safeWarmLevel")
                
                // 保存到ViewModel - 使用原始值
                dashboardViewModel.setRedLightBrightness(originalRedLevel)
                dashboardViewModel.setColdLightLevel(originalColdLevel)
                dashboardViewModel.setBlueLightBrightness(originalBlueLevel) 
                dashboardViewModel.setWarmLightLevel(originalWarmLevel)
                
                // 根据亮度判断是否激活灯光 - 用原始值判断
                activeLightTypes.clear()
                
                if (originalRedLevel > 1) activeLightTypes.add("红色")
                if (originalColdLevel > 1) activeLightTypes.add("冷光")
                if (originalBlueLevel > 1) activeLightTypes.add("蓝色") 
                if (originalWarmLevel > 1) activeLightTypes.add("暖光")
                
                // 更新UI显示
                activity?.runOnUiThread {
                    // 更新传感器数值显示 - 使用原始值显示
                    updateSensorValues(originalRedLevel, originalColdLevel, originalBlueLevel, originalWarmLevel)
                    
                    // 无论用户是否在调整滑块，都更新数值文本显示 - 保证alarm消息一定会更新
                    updateSliderTextValues(originalRedLevel, originalColdLevel, originalBlueLevel, originalWarmLevel)
                    
                    // 直接更新滑块位置，确保用户能看到实际亮度值
                    if (!isUserAdjustingSlider) {
                        // 设置滑块位置 - 使用安全值(防止IllegalStateException)
                        setSliderPosition(safeRedLevel, safeColdLevel, safeBlueLevel, safeWarmLevel)
        } else {
                        // 即使用户在调整滑块，也强制设置刷新标志，确保用户停止操作后立即更新
                        shouldRefreshUI = true
                        Log.d(TAG, "用户正在调整滑块，已设置刷新标志，待操作结束后更新UI")
                    }
                    
                    // 更新状态显示
                    updateStatusDisplay()
                    
                    // 强制更新红灯状态
                    if (json.has("level")) {
                        updateRedBulbBrightnessImmediate(originalRedLevel)
                    }
                    
                    // 再次延迟100ms更新一次，确保UI显示最新状态
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "延迟100ms更新UI - 确保alarm数据反映到界面")
                        if (!isUserAdjustingSlider) {
                            updateSliderTextValues(originalRedLevel, originalColdLevel, originalBlueLevel, originalWarmLevel)
                        }
                    }, 100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理alarm消息时出错: ${e.message}", e)
        }
    }
    
    // 新方法：仅更新滑块位置，不更新文本显示
    private fun setSliderPosition(redLevel: Int, coldLevel: Int, blueLevel: Int, warmLevel: Int) {
        try {
            // 更新滑块位置 - 这些值必须在滑块范围内(1-5)
            redLightSlider?.value = redLevel.toFloat()
            coldLightSlider?.value = coldLevel.toFloat()
            blueLightSlider?.value = blueLevel.toFloat()
            warmLightSlider?.value = warmLevel.toFloat()
            
            Log.d(TAG, "已更新所有滑块位置: 红=$redLevel, 冷=$coldLevel, 蓝=$blueLevel, 暖=$warmLevel")
        } catch (e: Exception) {
            Log.e(TAG, "更新滑块位置失败: ${e.message}", e)
        }
    }
    
    // 新方法：仅更新文本显示，不更新滑块位置
    private fun updateSliderTextValues(redLevel: Int, coldLevel: Int, blueLevel: Int, warmLevel: Int) {
        try {
            // 更新显示的数值文本 - 可以显示任何值，包括0
            redLightSliderLevel?.text = redLevel.toString()
            coldLightLevel?.text = coldLevel.toString()
            blueLightSliderLevel?.text = blueLevel.toString()
            warmLightLevel?.text = warmLevel.toString()
            
            Log.d(TAG, "已更新所有亮度显示: 红=$redLevel, 冷=$coldLevel, 蓝=$blueLevel, 暖=$warmLevel")
        } catch (e: Exception) {
            Log.e(TAG, "更新亮度显示失败: ${e.message}", e)
        }
    }

    // 根据level值更新红色灯泡亮度
    private fun updateRedBulbBrightness(level: Int) {
        // 记录日志，帮助调试
        Log.d(TAG, "更新红色灯泡亮度: level = $level")
        
        try {
            val imageView = redLightBulb as ImageView
            
            // 使用不同方法更新灯泡颜色
            if (level <= 1) {
                // 关闭状态 - 使用灰色
                Log.d(TAG, "设置红灯为关闭状态 (灰色)")
                
                // 重置所有颜色设置
                imageView.clearColorFilter()
                
                // 使用多种方式确保颜色更新
                imageView.setColorFilter(Color.DKGRAY, android.graphics.PorterDuff.Mode.SRC_IN)
                imageView.imageTintList = ColorStateList.valueOf(Color.DKGRAY)
                
                // 通过背景色确保视觉效果明显
                if (imageView.background != null) {
                    try {
                        val background = imageView.background.mutate()
                        background.setTint(Color.DKGRAY)
                    } catch (e: Exception) {
                        Log.e(TAG, "设置背景颜色失败: ${e.message}")
                    }
                }
                
                // 设置标签，用于调试
                imageView.tag = "RedBulb:OFF"
        } else {
                // 开启状态 - 使用红色，亮度根据level变化
                val redColor = when (level) {
                    2 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_2)
                    3 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_3)
                    4 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_4)
                    5 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_5)
                    else -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_3)
                }
                
                Log.d(TAG, "设置红灯为开启状态: level=$level, color=$redColor")
                
                // 重置所有颜色设置
                imageView.clearColorFilter()
                
                // 使用多种方式确保颜色更新
                imageView.setColorFilter(redColor, android.graphics.PorterDuff.Mode.SRC_IN)
                imageView.imageTintList = ColorStateList.valueOf(redColor)
                
                // 通过背景色确保视觉效果明显
                if (imageView.background != null) {
                    try {
                        val background = imageView.background.mutate()
                        background.setTint(redColor)
                    } catch (e: Exception) {
                        Log.e(TAG, "设置背景颜色失败: ${e.message}")
                    }
                }
                
                // 设置标签，用于调试
                imageView.tag = "RedBulb:ON(level=$level)"
            }
            
            // 强制重绘视图
            imageView.invalidate()
            
            // 记录用于调试的信息
            Log.d(TAG, "红色灯泡状态更新完成: ${imageView.tag}")
        } catch (e: Exception) {
            Log.e(TAG, "设置红色灯泡颜色失败: ${e.message}", e)
        }
        
        // 更新红灯状态文本显示
        updateRedBulbStatusText(level)
        
        // 设置到ViewModel，确保状态一致
        dashboardViewModel.setRedLightBrightness(level)
    }
    
    // 更新红色灯泡状态文本
    private fun updateRedBulbStatusText(level: Int) {
        val statusText = when (level) {
            0, 1 -> "红灯: 关闭"
            2 -> "红灯: 低亮度"
            3 -> "红灯: 中亮度"
            4 -> "红灯: 高亮度"
            5 -> "红灯: 最高亮度"
            else -> "红灯: 关闭"
        }
        
        // 更新红灯状态文本
        redLightValue?.text = level.toString()
        
        // 设置文本颜色
        redLightValue?.setTextColor(ContextCompat.getColor(requireContext(), 
            if (level >= 2) R.color.light_red else R.color.text_secondary))
    }
    
    // 更新其他灯泡显示
    private fun updateOtherBulbsDisplay(level1: Int, level2: Int, level3: Int) {
        // 先将其他灯泡设为灰色
        coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_off))
        blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_off))
        warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_off))
        
        // 根据传感器level值设置指示器颜色
        if (level1 >= 1) {
            coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_on))
        }
        
        if (level2 >= 1) {
            blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_on))
        }
        
        if (level3 >= 1) {
            warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_on))
        }
        
        // 更新状态文本
        updateSensorStatusText(level1, level2, level3)
    }
    
    // 更新传感器状态文本 (不包括红灯)
    private fun updateSensorStatusText(level1: Int, level2: Int, level3: Int) {
        val sb = StringBuilder()
        
        // 先显示传感器数据标题
        sb.append("传感器数据: ")
        
        // 构建显示文本
        if (level1 <= 0 && level2 <= 0 && level3 <= 0 && activeLightTypes.isEmpty()) {
            sb.append("所有灯光已关闭")
        } else {
            sb.append("已开启: ")
            val activeList = mutableListOf<String>()
            
            if (activeLightTypes.contains("红色")) activeList.add("红色")
            if (level1 >= 1) activeList.add("冷光")
            if (level2 >= 1) activeList.add("蓝色")
            if (level3 >= 1) activeList.add("暖光")
            
            sb.append(activeList.joinToString("，"))
        }
        
        // 设置状态文本
        currentStatusValue?.text = sb.toString()
        
        // 设置文本颜色
        val textColor = when {
            activeLightTypes.contains("红色") -> R.color.light_red
            level1 >= 1 -> R.color.light_cold
            level2 >= 1 -> R.color.light_blue
            level3 >= 1 -> R.color.light_warm
            else -> R.color.text_secondary
        }
        currentStatusValue?.setTextColor(ContextCompat.getColor(requireContext(), textColor))
    }

    // 更新灯泡显示
    private fun updateBulbsDisplay() {
        // 先将所有灯泡设为灰色
        redLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_red_off))
        coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_off))
        blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_off))
        warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_off))
        
        // 设置活跃的灯泡为对应颜色
        for (lightType in activeLightTypes) {
            when (lightType) {
                "红色" -> redLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_red_on))
                "冷光" -> coldLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_cold_on))
                "蓝色" -> blueLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_blue_on))
                "暖光" -> warmLightBulb?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.light_warm_on))
            }
        }
        
        updateStatusTextFromBulbs()
    }
    
    // 根据灯泡状态更新文本
    private fun updateStatusTextFromBulbs() {
        if (activeLightTypes.isEmpty()) {
            currentStatusValue?.text = "所有灯光已关闭"
            currentStatusValue?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            val statusText = activeLightTypes.joinToString(", ")
            currentStatusValue?.text = "已开启: $statusText"
            
            // 根据第一个活跃灯光设置文本颜色
            val textColor = when {
                activeLightTypes.contains("红色") -> R.color.light_red
                activeLightTypes.contains("冷光") -> R.color.light_cold
                activeLightTypes.contains("蓝色") -> R.color.light_blue
                activeLightTypes.contains("暖光") -> R.color.light_warm
                else -> R.color.text_primary
            }
            currentStatusValue?.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        }
    }

    // 更新传感器数值显示
    private fun updateSensorValues(level: Int, level1: Int, level2: Int, level3: Int) {
        // 更新其他传感器数值显示 (红色灯泡的值已在updateRedBulbBrightness中更新)
        coldLightSensorValue?.text = level1.toString()
        blueLightValue?.text = level2.toString()
        warmLightSensorValue?.text = level3.toString()
        
        // 高亮当前活跃的传感器数值
        coldLightSensorValue?.setTextColor(ContextCompat.getColor(requireContext(), 
            if (level1 >= 1) R.color.light_cold else R.color.text_secondary))
        
        blueLightValue?.setTextColor(ContextCompat.getColor(requireContext(), 
            if (level2 >= 1) R.color.light_blue else R.color.text_secondary))
        
        warmLightSensorValue?.setTextColor(ContextCompat.getColor(requireContext(), 
            if (level3 >= 1) R.color.light_warm else R.color.text_secondary))
    }

    // 立即强制更新红灯状态，绕过任何缓存或逻辑判断
    private fun updateRedBulbBrightnessImmediate(level: Int) {
        Log.d(TAG, "强制立即更新红灯状态: level=$level")
        
        try {
            val imageView = redLightBulb as ImageView
            
            if (level <= 1) {
                // 关闭状态 - 使用灰色
                imageView.clearColorFilter()
                imageView.setColorFilter(Color.DKGRAY, android.graphics.PorterDuff.Mode.SRC_IN)
                imageView.imageTintList = ColorStateList.valueOf(Color.DKGRAY)
                imageView.tag = "RedBulb:OFF"
                
                // 从活跃灯光中移除
                activeLightTypes.remove("红色")
                Log.d(TAG, "强制关闭红灯完成")
            } else {
                // 开启状态 - 使用红色
                val redColor = when (level) {
                    2 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_2)
                    3 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_3)
                    4 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_4)
                    5 -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_5)
                    else -> ContextCompat.getColor(requireContext(), R.color.red_bulb_level_3)
                }
                
                imageView.clearColorFilter() 
                imageView.setColorFilter(redColor, android.graphics.PorterDuff.Mode.SRC_IN)
                imageView.imageTintList = ColorStateList.valueOf(redColor)
                imageView.tag = "RedBulb:ON(level=$level)"
                
                // 添加到活跃灯光
                activeLightTypes.add("红色")
                Log.d(TAG, "强制开启红灯完成: 亮度=$level, 颜色=$redColor")
            }
            
            // 强制更新视图
            imageView.invalidate()
            
            // 更新文本显示
            redLightValue?.text = level.toString()
            redLightValue?.setTextColor(ContextCompat.getColor(requireContext(), 
                if (level > 1) R.color.light_red else R.color.text_secondary))
                
            // 确保状态文本更新
            updateStatusTextFromBulbs()
        } catch (e: Exception) {
            Log.e(TAG, "强制更新红灯状态失败: ${e.message}", e)
        }
    }

    // 创建定时器确保UI数据实时刷新
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            try {
                // 检查MQTT连接状态
                if (!::mqttClientManager.isInitialized || !mqttClientManager.isConnected()) {
                    Log.d(TAG, "检测到MQTT未连接，尝试重新连接")
                    if (::mqttClientManager.isInitialized) {
                        mqttClientManager.connect()
                    }
                }
                
                // 获取当前所有灯光原始状态
                val redLevel = dashboardViewModel.redLightBrightness.value ?: 0
                val coldLevel = dashboardViewModel.coldLightLevel.value ?: 0
                val blueLevel = dashboardViewModel.blueLightBrightness.value ?: 0
                val warmLevel = dashboardViewModel.warmLightLevel.value ?: 0
                
                // 计算安全值
                val safeRedLevel = max(1, redLevel)
                val safeColdLevel = max(1, coldLevel)
                val safeBlueLevel = max(1, blueLevel)
                val safeWarmLevel = max(1, warmLevel)
                
                // 检查用户是否正在调整滑块
                if (!isUserAdjustingSlider) {
                    // 减少UI更新频率，防止卡顿
                    if (shouldRefreshUI) {
                        activity?.runOnUiThread {
                            // 重置标志
                            shouldRefreshUI = false
                            
                            // 更新滑块位置 - 使用安全值
                            setSliderPosition(safeRedLevel, safeColdLevel, safeBlueLevel, safeWarmLevel)
                            
                            // 更新显示的文本 - 使用原始值
                            updateSliderTextValues(redLevel, coldLevel, blueLevel, warmLevel)
                            
                            // 更新红灯显示
                            if (redLevel > 1) {
                                updateRedBulbBrightnessImmediate(redLevel)
                            }
                            
                            // 更新其他传感器和状态 - 减少更新频率，优化性能
                            updateStatusDisplay()
                        }
                    }
                }
        } catch (e: Exception) {
                Log.e(TAG, "数据刷新时出错: ${e.message}", e)
            } finally {
                // 增加刷新间隔到5秒，减少频繁刷新导致的卡顿
                refreshHandler.postDelayed(this, 5000)
            }
        }
    }
    
    // 用于防止亮度条频繁跳动的标志
    private var isUserAdjustingSlider = false
    private var shouldRefreshUI = true
    
    // 添加测试函数
    private fun testRedBulbLevels() {
        Thread {
            try {
                // 先测试关闭状态
                Log.d(TAG, "==== 测试红灯关闭状态 ====")
                activity?.runOnUiThread {
                    Log.d(TAG, "测试红色灯泡关闭: level=1")
                    updateRedBulbBrightness(1)
                    redLightValue?.text = "1"
                    redLightValue?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    // 额外日志确认颜色被设置
                    val imageView = redLightBulb as ImageView
                    Log.d(TAG, "设置后的红灯标签: ${imageView.tag}")
                }
                Thread.sleep(2000) // 先停留2秒在关闭状态
                
                // 循环测试所有亮度级别
                for (level in 2..5) {
                    activity?.runOnUiThread {
                        Log.d(TAG, "测试红色灯泡亮度级别: $level")
                        updateRedBulbBrightness(level)
                        redLightValue?.text = level.toString()
                        redLightValue?.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_red))
                    }
                    Thread.sleep(1000) // 每秒变化一次亮度
                }
                
                // 最后再次测试关闭状态
                activity?.runOnUiThread {
                    Log.d(TAG, "测试红色灯泡再次关闭: level=1")
                    updateRedBulbBrightness(1)
                    redLightValue?.text = "1"
                    redLightValue?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
            } catch (e: Exception) {
                Log.e(TAG, "测试红色灯泡亮度变化时出错: ${e.message}", e)
            }
        }.start()
    }

    // 启动数据刷新定时器
    private fun startDataRefreshTimer() {
        // 停止现有的定时器（如果有）
        stopDataRefreshTimer()
        // 启动新的定时器
        refreshHandler.post(refreshRunnable)
        Log.d(TAG, "启动数据实时刷新定时器")
    }
    
    // 停止数据刷新定时器
    private fun stopDataRefreshTimer() {
        refreshHandler.removeCallbacks(refreshRunnable)
        Log.d(TAG, "停止数据实时刷新定时器")
    }

    override fun onResume() {
        super.onResume()
        
        // 在每次界面恢复时更新状态
        updateStatusDisplay()
        updateBulbsDisplay()
        
        // 确保当前模式按钮高亮 - 在查询前先用存储的值
        val currentMode = dashboardViewModel.currentMode.value ?: 0
        updateModeButtonsUI(currentMode)
        
        // 先暂停定时器，避免在检查期间被干扰
        stopDataRefreshTimer()
        
        // 检查当前红灯状态
        checkRedLightStatus()
        
        // 重新订阅主题确保不错过消息
        if (::mqttClientManager.isInitialized && mqttClientManager.isConnected()) {
            mqttClientManager.subscribe("alarm", 0)
            mqttClientManager.subscribe("control", 0)
            
            // 请求一次最新数据
            try {
                mqttClientManager.publish("request", "{\"action\":\"getData\"}", 0, false)
                Log.d(TAG, "恢复时请求最新数据")
                
                // 查询当前模式
                queryCurrentMode()
            } catch (e: Exception) {
                Log.e(TAG, "请求数据时出错: ${e.message}", e)
            }
        }
        
        // 启动数据刷新定时器
        startDataRefreshTimer()
    }
    
    // 检查红灯当前状态并确保UI正确显示
    private fun checkRedLightStatus() {
        val redLevel = dashboardViewModel.redLightBrightness.value ?: 0
        Log.d(TAG, "检查红灯当前状态: level=$redLevel")
        
        // 强制更新红灯显示
        activity?.runOnUiThread {
            // 使用立即更新方法，确保状态正确
            updateRedBulbBrightnessImmediate(redLevel)
            
            // 额外日志
            val imageView = redLightBulb as ImageView
            Log.d(TAG, "红灯状态检查完成，当前状态: ${imageView.tag}, 活动灯类型: $activeLightTypes")
        }
    }

    private fun setupModeButtons() {
        Log.d(TAG, "开始设置模式按钮...")
        
        try {
            // 手动模式按钮
            val manualButton: MaterialButton? = view?.findViewById(R.id.btn_mode_manual)
            if (manualButton == null) {
                Log.e(TAG, "无法找到手动模式按钮")
            } else {
                manualButton.setOnClickListener { view ->
                    Log.d(TAG, "点击了手动模式按钮")
                    // 先更新UI，为用户提供即时反馈
                    updateModeButtonsUI(0)
                    // 显示轻提示
                    android.widget.Toast.makeText(context, "正在切换到手动模式...", android.widget.Toast.LENGTH_SHORT).show()
                    // 发送命令
                    publishControlModeCommand(0)
                }
                Log.d(TAG, "手动模式按钮设置成功")
            }
    
            // 自动模式按钮
            val autoButton: MaterialButton? = view?.findViewById(R.id.btn_mode_auto)
            if (autoButton == null) {
                Log.e(TAG, "无法找到自动模式按钮")
            } else {
                autoButton.setOnClickListener { view ->
                    Log.d(TAG, "点击了自动模式按钮")
                    // 先更新UI
                    updateModeButtonsUI(1)
                    // 显示轻提示
                    android.widget.Toast.makeText(context, "正在切换到自动模式...", android.widget.Toast.LENGTH_SHORT).show()
                    // 发送命令
                    publishControlModeCommand(1)
                }
                Log.d(TAG, "自动模式按钮设置成功")
            }
    
            // 警示模式按钮
            val alarmButton: MaterialButton? = view?.findViewById(R.id.btn_mode_alarm)
            if (alarmButton == null) {
                Log.e(TAG, "无法找到警示模式按钮")
            } else {
                alarmButton.setOnClickListener { view ->
                    Log.d(TAG, "点击了警示模式按钮")
                    // 先更新UI
                    updateModeButtonsUI(2)
                    // 显示轻提示
                    android.widget.Toast.makeText(context, "正在切换到警示模式...", android.widget.Toast.LENGTH_SHORT).show()
                    // 发送命令
                    publishLightModeCommand("alarm")
                }
                Log.d(TAG, "警示模式按钮设置成功")
            }
    
            // 闪烁模式按钮
            val flashButton: MaterialButton? = view?.findViewById(R.id.btn_mode_flash)
            if (flashButton == null) {
                Log.e(TAG, "无法找到闪烁模式按钮")
            } else {
                flashButton.setOnClickListener { view ->
                    Log.d(TAG, "点击了闪烁模式按钮")
                    // 先更新UI
                    updateModeButtonsUI(3)
                    // 显示轻提示
                    android.widget.Toast.makeText(context, "正在切换到闪烁模式...", android.widget.Toast.LENGTH_SHORT).show()
                    // 发送命令
                    publishLightModeCommand("fast")
                }
                Log.d(TAG, "闪烁模式按钮设置成功")
            }
    
            // 常亮模式按钮
            val constantButton: MaterialButton? = view?.findViewById(R.id.btn_mode_constant)
            if (constantButton == null) {
                Log.e(TAG, "无法找到常亮模式按钮")
            } else {
                constantButton.setOnClickListener { view ->
                    Log.d(TAG, "点击了常亮模式按钮")
                    // 先更新UI
                    updateModeButtonsUI(4)
                    // 显示轻提示
                    android.widget.Toast.makeText(context, "正在切换到常亮模式...", android.widget.Toast.LENGTH_SHORT).show()
                    // 发送命令
                    publishLightModeCommand("constant")
                }
                Log.d(TAG, "常亮模式按钮设置成功")
            }
            
            Log.d(TAG, "所有模式按钮设置完成")
        } catch (e: Exception) {
            Log.e(TAG, "设置模式按钮时出错: ${e.message}", e)
            android.widget.Toast.makeText(context, "模式按钮设置失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeButtonsUI(activeMode: Int) {
        try {
            Log.d(TAG, "更新模式按钮UI: activeMode=$activeMode")
            
            val buttons = listOf(
                view?.findViewById<MaterialButton>(R.id.btn_mode_manual),
                view?.findViewById<MaterialButton>(R.id.btn_mode_auto),
                view?.findViewById<MaterialButton>(R.id.btn_mode_alarm),
                view?.findViewById<MaterialButton>(R.id.btn_mode_flash),
                view?.findViewById<MaterialButton>(R.id.btn_mode_constant)
            )
    
            buttons.forEachIndexed { index, button ->
                button?.let {
                    try {
                        if (index == activeMode) {
                            // 使用背景色状态列表
                            it.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary_color))
                        } else {
                            // 默认灰色
                            it.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#424242"))
                        }
                        
                        // 确保刷新
                        it.invalidate()
                    } catch (e: Exception) {
                        Log.e(TAG, "设置按钮背景色失败: ${e.message}", e)
                    }
                }
            }
            
            // 记录激活的模式
            when (activeMode) {
                0 -> Log.d(TAG, "当前激活模式: 手动模式")
                1 -> Log.d(TAG, "当前激活模式: 自动模式")
                2 -> Log.d(TAG, "当前激活模式: 警示模式")
                3 -> Log.d(TAG, "当前激活模式: 闪烁模式")
                4 -> Log.d(TAG, "当前激活模式: 常亮模式")
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新模式按钮UI时出错: ${e.message}", e)
        }
    }

    private fun publishControlModeCommand(modeValue: Int) {
        val jsonObject = JSONObject()
        try {
            Log.d(TAG, "准备发布控制模式命令(简化版): modeValue=$modeValue")
            jsonObject.put("mode", modeValue)
            // 移除时间戳，不再添加time字段
            
            // 直接尝试获取MQTT实例并发布
            val mqttManager = MqttClientManager.getInstance(requireContext())
            val message = jsonObject.toString()
            
            // 检查是否连接，如果未连接则先连接
            if (!mqttManager.isConnected()) {
                Log.d(TAG, "检测到MQTT未连接，尝试连接")
                mqttManager.connect()
                
                // 延迟发送消息，等待连接完成
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        mqttManager.publish("control", message, 0, false)
                        Log.d(TAG, "延迟发送成功: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "延迟发送失败: ${e.message}", e)
                    }
                }, 1000)
            } else {
                // 已连接，直接发送
                mqttManager.publish("control", message, 0, false)
                Log.d(TAG, "已连接状态下发送成功: $message")
            }
            
            // 弹出提示信息
            android.widget.Toast.makeText(context, "已发送模式切换命令: 模式$modeValue", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "发布控制模式命令异常: ${e.message}", e)
            android.widget.Toast.makeText(context, "发送模式命令失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun publishLightModeCommand(modeValue: String) {
        val jsonObject = JSONObject()
        try {
            Log.d(TAG, "准备发布灯光模式命令(简化版): modeValue=$modeValue")
            jsonObject.put("lightMode", modeValue)
            // 移除时间戳，不再添加time字段
            
            // 直接尝试获取MQTT实例并发布
            val mqttManager = MqttClientManager.getInstance(requireContext())
            val message = jsonObject.toString()
            
            // 检查是否连接，如果未连接则先连接
            if (!mqttManager.isConnected()) {
                Log.d(TAG, "检测到MQTT未连接，尝试连接")
                mqttManager.connect()
                
                // 延迟发送消息，等待连接完成
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        mqttManager.publish("control", message, 0, false)
                        Log.d(TAG, "延迟发送成功: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "延迟发送失败: ${e.message}", e)
                    }
                }, 1000)
            } else {
                // 已连接，直接发送
                mqttManager.publish("control", message, 0, false)
                Log.d(TAG, "已连接状态下发送成功: $message")
            }
            
            // 弹出提示信息
            android.widget.Toast.makeText(context, "已发送灯光模式命令: $modeValue", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "发布灯光模式命令异常: ${e.message}", e)
            android.widget.Toast.makeText(context, "发送模式命令失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 请求传感器数据
    private fun requestSensorData() {
        try {
            val request = "{\"action\":\"getSensorData\"}"
            mqttClientManager.publish("request", request, 1, false)
            Log.d(TAG, "已请求传感器数据")
        } catch (e: Exception) {
            Log.e(TAG, "请求传感器数据失败: ${e.message}", e)
        }
    }
    
    // 请求警报数据
    private fun requestAlarmData() {
        try {
            val request = "{\"action\":\"getAlarmData\"}"
            mqttClientManager.publish("request", request, 1, false)
            Log.d(TAG, "已请求警报数据")
        } catch (e: Exception) {
            Log.e(TAG, "请求警报数据失败: ${e.message}", e)
        }
    }

    // 处理传感器消息
    private fun handleSensorMessage(message: String) {
        try {
            Log.d(TAG, "处理传感器消息: $message")
            
            // 简单处理显示传感器数据，不做复杂解析
            if (message.contains("{") && message.contains("}")) {
                val jsonStartIndex = message.indexOf("{")
                val jsonEndIndex = message.lastIndexOf("}") + 1
                val jsonStr = message.substring(jsonStartIndex, jsonEndIndex)
                
                val json = org.json.JSONObject(jsonStr)
                
                // 更新UI显示
             activity?.runOnUiThread {
                    // 根据传感器消息更新UI
                    Log.d(TAG, "传感器数据已更新: $jsonStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理传感器消息时出错: ${e.message}", e)
        }
    }

    // 处理灯光状态消息
    private fun handleLightStatusMessage(message: String) {
        try {
            Log.d(TAG, "处理灯光状态消息: $message")
            
            // 尝试从消息中提取JSON
            val jsonStartIndex = message.indexOf("{")
            val jsonEndIndex = message.lastIndexOf("}") + 1
            
            if (jsonStartIndex >= 0 && jsonEndIndex > jsonStartIndex) {
                val jsonStr = message.substring(jsonStartIndex, jsonEndIndex)
                
                // 提取描述文本（如果有）
                val textPart = if (jsonEndIndex < message.length) {
                    message.substring(jsonEndIndex).trim()
                } else {
                    ""
                }
                
                Log.d(TAG, "JSON部分: $jsonStr, 文本部分: $textPart")
                
                val json = org.json.JSONObject(jsonStr)
                
                // 尝试识别这是哪种灯光的消息
                var lightType = ""
                
                // 确定灯光类型
                if (textPart.contains("红色") || message.contains("红色")) {
                    lightType = "红色"
                } else if (textPart.contains("蓝色") || message.contains("蓝色")) {
                    lightType = "蓝色"
                } else if (textPart.contains("冷色") || textPart.contains("冷光") || 
                           message.contains("冷色") || message.contains("冷光")) {
                    lightType = "冷光"
                } else if (textPart.contains("暖色") || textPart.contains("暖光") ||
                           message.contains("暖色") || message.contains("暖光")) {
                    lightType = "暖光"
                } else if (json.has("level1") && !json.has("level2") && !json.has("level3")) {
                    lightType = "冷光"
                } else if (json.has("level2") && !json.has("level1") && !json.has("level3")) {
                    lightType = "蓝色"
                } else if (json.has("level3") && !json.has("level1") && !json.has("level2")) {
                    lightType = "暖光"
                }
                
                // 检查消息是否包含level值
                if (json.has("level1") || json.has("level2") || json.has("level3") ||
                    json.has("level")) {
                    var brightness = 0
                    
                    // 从JSON中提取亮度值
                    if (lightType == "冷光" && json.has("level1")) {
                        brightness = json.getInt("level1")
                        dashboardViewModel.setColdLightLevel(brightness)
                    } else if (lightType == "蓝色" && json.has("level2")) {
                        brightness = json.getInt("level2")
                        dashboardViewModel.setBlueLightBrightness(brightness)
                    } else if (lightType == "暖光" && json.has("level3")) {
                        brightness = json.getInt("level3")
                        dashboardViewModel.setWarmLightLevel(brightness)
                    } else if (lightType == "红色" && json.has("level")) {
                        brightness = json.getInt("level")
                        dashboardViewModel.setRedLightBrightness(brightness)
                    }
                    
                    Log.d(TAG, "更新灯光亮度: 类型=$lightType, 亮度=$brightness")
                    
                    // 更新UI显示
                    if (!isUserAdjustingSlider) {
                        updateStatusDisplay()
                    } else {
                        shouldRefreshUI = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理灯光状态消息失败: ${e.message}", e)
        }
    }

    override fun onDestroyView() {
        try {
        super.onDestroyView()
            
            // 停止定时器并清除挂起的任务
            stopDataRefreshTimer()
            refreshHandler.removeCallbacksAndMessages(null)
            
            // 清理MQTT资源
        if (::mqttClientManager.isInitialized) {
            mqttClientManager.setCallback(null)
                Log.d(TAG, "销毁视图时移除MQTT回调")
            }
            
            // 清除可能的内存泄漏
            currentStatusValue = null
            coldLightBulb = null
            warmLightBulb = null
            redLightBulb = null
            blueLightBulb = null
            coldLightSlider = null
            warmLightSlider = null
            coldLightLevel = null
            warmLightLevel = null
            redLightSlider = null
            blueLightSlider = null
            redLightSliderLevel = null
            blueLightSliderLevel = null
            redLightValue = null
            coldLightSensorValue = null
            blueLightValue = null
            warmLightSensorValue = null
            
            Log.d(TAG, "DashboardFragment视图资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "销毁视图时清理资源出错: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // 停止定时器，避免后台消耗资源
        stopDataRefreshTimer()
        
        try {
            // 将回调置空，防止界面不可见时接收消息导致崩溃
            if (::mqttClientManager.isInitialized) {
                mqttClientManager.setCallback(null)
                Log.d(TAG, "暂停时移除MQTT回调")
            }
        } catch (e: Exception) {
            Log.e(TAG, "暂停时清理资源出错: ${e.message}")
        }
    }

    // 强制初始化MQTT连接
    private fun initializeMqttConnection() {
        try {
            // 获取MQTT客户端实例并连接
            mqttClientManager = MqttClientManager.getInstance(requireContext())
            
            // 设置回调
            mqttClientManager.setCallback(this)
            
            // 如果未连接，强制连接
            if (!mqttClientManager.isConnected()) {
                mqttClientManager.connect()
                Log.d(TAG, "强制初始化MQTT连接")
                
                // 延迟订阅主题
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    subscribeMqttTopics()
                }, 1000)
            } else {
                Log.d(TAG, "MQTT已连接，直接订阅主题")
                subscribeMqttTopics()
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化MQTT连接时出错: ${e.message}", e)
        }
    }
}