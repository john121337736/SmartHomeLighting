package com.example.smarthomelighting.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * 控制面板ViewModel，用于存储灯光相关的状态
 */
class DashboardViewModel : ViewModel() {

    // 选定的灯光颜色类型
    private val _lightColor = MutableLiveData<String>().apply {
        value = "冷光"
    }
    val lightColor: LiveData<String> = _lightColor
    
    // 冷光亮度级别
    private val _coldLightLevel = MutableLiveData<Int>().apply {
        value = 3
    }
    val coldLightLevel: LiveData<Int> = _coldLightLevel
    
    // 暖光亮度级别
    private val _warmLightLevel = MutableLiveData<Int>().apply {
        value = 3
    }
    val warmLightLevel: LiveData<Int> = _warmLightLevel

    // 红灯亮度级别
    private val _redLightBrightness = MutableLiveData<Int>().apply {
        value = 1 
    }
    val redLightBrightness: LiveData<Int> = _redLightBrightness
    
    // 蓝灯亮度级别
    private val _blueLightBrightness = MutableLiveData<Int>().apply {
        value = 1
    }
    val blueLightBrightness: LiveData<Int> = _blueLightBrightness
    
    // 当前灯光模式
    private val _currentMode = MutableLiveData<Int>().apply {
        value = 0  // 默认为手动模式 (0-手动, 1-自动, 2-警示, 3-闪烁, 4-常亮)
    }
    val currentMode: LiveData<Int> = _currentMode

    // 设置灯光颜色
    fun setLightColor(color: String) {
        _lightColor.value = color
    }
    
    // 设置冷光亮度级别 - 允许存储原始值，包括0
    fun setColdLightLevel(level: Int) {
            _coldLightLevel.value = level
    }
    
    // 设置暖光亮度级别 - 允许存储原始值，包括0
    fun setWarmLightLevel(level: Int) {
            _warmLightLevel.value = level
        }
    
    // 设置红灯亮度 - 允许存储原始值，包括0
    fun setRedLightBrightness(level: Int) {
        _redLightBrightness.value = level
    }
    
    // 设置蓝灯亮度 - 允许存储原始值，包括0
    fun setBlueLightBrightness(level: Int) {
        _blueLightBrightness.value = level
    }
    
    // 设置当前模式
    fun setCurrentMode(mode: Int) {
        _currentMode.value = mode
    }
}