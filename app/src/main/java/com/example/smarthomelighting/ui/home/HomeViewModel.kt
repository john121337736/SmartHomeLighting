package com.example.smarthomelighting.ui.home

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.json.JSONObject

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "HomeViewModel"
    
    // 连接状态
    private val _connectionStatus = MutableLiveData<String>().apply { value = "DISCONNECTED" }
    val connectionStatus: LiveData<String> = _connectionStatus

    // 温度数据
    private val _temperature = MutableLiveData<String>().apply { value = "0" }
    val temperature: LiveData<String> = _temperature

    // 湿度数据
    private val _humidity = MutableLiveData<String>().apply { value = "0" }
    val humidity: LiveData<String> = _humidity

    // 距离数据
    private val _distance = MutableLiveData<String>().apply { value = "0" }
    val distance: LiveData<String> = _distance

    // 光强数据
    private val _lightIntensity = MutableLiveData<String>().apply { value = "0" }
    val lightIntensity: LiveData<String> = _lightIntensity

    // 模式数据（自动/手动）
    private val _mode = MutableLiveData<String>().apply { value = "自动模式" }
    val mode: LiveData<String> = _mode

    // 是否有人数据
    private val _humanPresent = MutableLiveData<Boolean>().apply { value = false }
    val humanPresent: LiveData<Boolean> = _humanPresent

    // 处理接收到的MQTT消息 - 现在公开为public方法用于接收模拟数据
    fun processMqttMessage(topic: String, message: String) {
        try {
            Log.d(TAG, "收到数据: $topic -> $message")
            
            when (topic) {
                "alarm" -> {
                    try {
                        // 尝试解析JSON
                        val jsonData = JSONObject(message)
                        
                        // 处理温度(temp)
                        if (jsonData.has("temp")) {
                            val temp = jsonData.getString("temp")
                            _temperature.postValue(temp)
                        }
                        
                        // 处理湿度(humi)
                        if (jsonData.has("humi")) {
                            val humi = jsonData.getString("humi")
                            _humidity.postValue(humi)
                        }
                        
                        // 处理人体存在(human)
                        if (jsonData.has("human")) {
                            val human = jsonData.getInt("human")
                            _humanPresent.postValue(human == 1)
                        }
                        
                        // 处理距离(dist)
                        if (jsonData.has("dist")) {
                            val dist = jsonData.getString("dist")
                            _distance.postValue(dist)
                        }
                        
                        // 处理模式(mode)
                        if (jsonData.has("mode")) {
                            val modeValue = jsonData.getInt("mode")
                            _mode.postValue(if (modeValue == 1) "自动" else "手动")
                        }
                        
                        // 处理光强(lux)
                        if (jsonData.has("lux")) {
                            val lux = jsonData.getString("lux")
                            _lightIntensity.postValue(lux)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析JSON数据错误: ${e.message}")
                        Log.e(TAG, "原始消息: $message")
                        e.printStackTrace()
                    }
                }
                else -> {
                    Log.d(TAG, "未处理的主题: $topic，消息: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息错误: ${e.message}")
            e.printStackTrace()
        }
    }

    // 更新温度数据
    fun setTemperature(value: String) {
        try {
            val temp = value.toFloat()
            _temperature.value = String.format("%.1f", temp)
        } catch (e: Exception) {
            Log.e(TAG, "温度数据格式化失败: $value, ${e.message}")
            _temperature.value = value
        }
    }

    // 更新湿度数据
    fun setHumidity(value: String) {
        try {
            val humid = value.toFloat()
            _humidity.value = String.format("%.1f", humid)
        } catch (e: Exception) {
            Log.e(TAG, "湿度数据格式化失败: $value, ${e.message}")
            _humidity.value = value
        }
    }

    // 更新距离数据
    fun setDistance(value: String) {
        try {
            val dist = value.toFloat()
            _distance.value = String.format("%.1f", dist)
        } catch (e: Exception) {
            Log.e(TAG, "距离数据格式化失败: $value, ${e.message}")
            _distance.value = value
        }
    }

    // 更新光强数据
    fun setLightIntensity(value: String) {
        try {
            val light = value.toFloat()
            _lightIntensity.value = String.format("%.1f", light)
        } catch (e: Exception) {
            Log.e(TAG, "光强数据格式化失败: $value, ${e.message}")
            _lightIntensity.value = value
        }
    }

    // 更新连接状态
    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
        Log.d(TAG, "MQTT连接状态更新: $status")
    }

    // 设置模式
    fun setMode(newMode: String) {
        _mode.value = newMode
        Log.d(TAG, "模式已切换为: ${_mode.value}")
    }

    // 更新传感器数据
    fun updateSensorData(temp: String, humid: String, pres: String, dist: String) {
        _temperature.value = temp
        _humidity.value = humid
        _humanPresent.value = pres == "检测到"
        _distance.value = dist
    }

    // 更新人员状态
    fun setHumanPresent(isPresent: Boolean) {
        _humanPresent.value = isPresent
    }
}