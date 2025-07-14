package com.example.smarthomelighting.ui.notifications

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsViewModel : ViewModel() {

    // 系统日志
    private val _systemLog = MutableLiveData<String>().apply {
        value = "系统启动中...\n"
    }
    val systemLog: LiveData<String> = _systemLog
    
    // 连接状态
    private val _connectionStatus = MutableLiveData<Boolean>().apply {
        value = false
    }
    val connectionStatus: LiveData<Boolean> = _connectionStatus
    
    // 上一次的连接状态，用于检测状态变化
    private var previousConnectionStatus = false
    
    // 上次连接状态变化时间
    private var lastStatusChangeTime = SystemClock.elapsedRealtime()
    
    // 连接状态稳定性控制
    private val statusStabilityThreshold = 5000L // 连接状态稳定阈值(5秒)
    private var pendingStatusChange: Boolean? = null
    private var pendingStatusChangeTime = 0L
    
    // 日志缓冲区，用于控制日志大小
    private val maxLogLines = 500 // 最多保留500行日志
    private val logBuffer = ArrayList<String>()

    // 日期格式化工具
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // 添加日志信息，并带上时间戳
    fun addToLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logMsg = "[$timestamp] $message"
        
        // 添加到缓冲区
        logBuffer.add(logMsg)
        
        // 如果日志行数超过最大值，移除最早的日志
        if (logBuffer.size > maxLogLines) {
            logBuffer.removeAt(0)
        }
        
        // 更新日志显示
        updateLogDisplay()
    }
    
    // 更新日志显示
    private fun updateLogDisplay() {
        val sb = StringBuilder()
        for (log in logBuffer) {
            sb.append(log).append("\n")
        }
        _systemLog.postValue(sb.toString())
    }
    
    // 清除日志
    fun clearLog() {
        logBuffer.clear()
        logBuffer.add("日志已清除")
        updateLogDisplay()
    }
    
    // 检查上一次是否已连接
    fun wasConnected(): Boolean {
        return previousConnectionStatus
    }
    
    // 获取上次状态变化时间
    fun getLastStatusChangeTime(): Long {
        return lastStatusChangeTime
    }
    
    // 更新连接状态，添加稳定性控制
    fun updateConnectionStatus(isConnected: Boolean) {
        val currentTime = SystemClock.elapsedRealtime()
        val currentStatus = _connectionStatus.value ?: false
        
        // 如果状态没有变化，直接返回
        if (isConnected == currentStatus) {
            return
        }
        
        // 如果是从连接变为断开，需要等待稳定期
        if (!isConnected && currentStatus) {
            // 如果已经有一个待处理的状态变化
            if (pendingStatusChange != null) {
                // 如果新状态与待处理状态相同，更新时间
                if (pendingStatusChange == isConnected) {
                    pendingStatusChangeTime = currentTime
                } 
                // 如果新状态与待处理状态不同，取消待处理状态
                else {
                    pendingStatusChange = null
                }
            } 
            // 如果没有待处理的状态变化，创建一个
            else {
                pendingStatusChange = isConnected
                pendingStatusChangeTime = currentTime
                
                // 延迟更新，等待稳定期
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (pendingStatusChange != null) {
                        // 如果待处理状态仍然存在，且已经过了稳定期
                        if (SystemClock.elapsedRealtime() - pendingStatusChangeTime >= statusStabilityThreshold) {
                            // 保存当前状态为上一次状态
                            previousConnectionStatus = _connectionStatus.value ?: false
                            
                            // 更新状态
                            _connectionStatus.postValue(pendingStatusChange)
                            
                            // 记录状态变化时间
                            lastStatusChangeTime = SystemClock.elapsedRealtime()
                            
                            // 如果状态变化，记录日志
                            if (pendingStatusChange != previousConnectionStatus) {
                                if (pendingStatusChange == true) {
                                    addToLog("MQTT 连接已恢复")
                                } else {
                                    addToLog("MQTT 连接已断开")
                                }
                            }
                            
                            // 清除待处理状态
                            pendingStatusChange = null
                        }
                    }
                }, statusStabilityThreshold)
            }
        } 
        // 如果是从断开变为连接，立即更新
        else if (isConnected && !currentStatus) {
            // 保存当前状态为上一次状态
            previousConnectionStatus = currentStatus
            
            // 更新状态
            _connectionStatus.postValue(isConnected)
            
            // 记录状态变化时间
            lastStatusChangeTime = currentTime
            
            // 记录日志
            addToLog("MQTT 连接已恢复")
            
            // 清除任何待处理的状态变化
            pendingStatusChange = null
        }
    }
}