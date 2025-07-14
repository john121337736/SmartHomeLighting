package com.example.smarthomelighting.ui.notifications

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.smarthomelighting.R
import com.example.smarthomelighting.SmartHomeLightingApplication
import com.example.smarthomelighting.utils.MqttClientManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsFragment : Fragment(), MqttClientManager.MqttStatusCallback {

    private val TAG = "NotificationsFragment"
    private lateinit var notificationsViewModel: NotificationsViewModel
    
    // 日志显示
    private lateinit var logScrollView: ScrollView
    private lateinit var logTextView: TextView
    private lateinit var clearLogButton: Button
    
    // MQTT客户端
    private lateinit var mqttClientManager: MqttClientManager
    
    // 连接状态稳定性控制
    private var lastConnectionCheckTime = 0L
    private val connectionCheckInterval = 5000L // 5秒检查一次连接状态
    
    // 自动刷新相关
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L // 1秒刷新一次
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateLogDisplay()
            refreshHandler.postDelayed(this, refreshInterval)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_notifications, container, false)
        
        // 获取ViewModel实例
        notificationsViewModel = ViewModelProvider(this).get(NotificationsViewModel::class.java)
        
        // 初始化视图
        initViews(root)
        
        // 设置观察者
        setupObservers()
        
        // 获取MQTT客户端并设置回调
        setupMqttClient()
        
        return root
    }
    
    private fun initViews(root: View) {
        logScrollView = root.findViewById(R.id.log_scroll_view)
        logTextView = root.findViewById(R.id.log_text_view)
        
        // 添加清除日志按钮
        clearLogButton = root.findViewById(R.id.clear_log_button)
        clearLogButton.setOnClickListener {
            notificationsViewModel.clearLog()
        }
    }
    
    private fun setupObservers() {
        // 观察系统日志更新
        notificationsViewModel.systemLog.observe(viewLifecycleOwner, Observer { logText ->
            logTextView.text = logText
            // 自动滚动到底部
            scrollToBottom()
        })
    }
    
    private fun scrollToBottom() {
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }
    
    private fun setupMqttClient() {
        // 获取MQTT客户端实例
        try {
            mqttClientManager = SmartHomeLightingApplication.instance.getMqttClientManager()
            // 设置回调
            mqttClientManager.setCallback(this)

            // 如果已经连接，记录日志
            if (mqttClientManager.isConnected()) {
                notificationsViewModel.addToLog("MQTT 已连接")
                notificationsViewModel.updateConnectionStatus(true)
                // 确保订阅了所有需要的主题
                subscribeToAllTopics()
            } else {
                notificationsViewModel.addToLog("MQTT 等待连接...")
                notificationsViewModel.updateConnectionStatus(false)
                // 尝试重新连接
                tryReconnect()
            }
            
            // 记录连接检查时间
            lastConnectionCheckTime = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            notificationsViewModel.addToLog("错误：无法获取 MQTT 客户端: ${e.message}")
            Log.e(TAG, "Cannot initialize MqttClientManager", e)
        }
    }
    
    private fun tryReconnect() {
        try {
            Log.d(TAG, "尝试重新连接MQTT")
            notificationsViewModel.addToLog("尝试重新连接MQTT...")
            mqttClientManager.forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "重连失败", e)
            notificationsViewModel.addToLog("重连失败: ${e.message}")
        }
    }
    
    private fun subscribeToAllTopics() {
        try {
            // 订阅所有相关主题，确保能收到所有消息
            mqttClientManager.subscribe("status", 1)
            mqttClientManager.subscribe("control", 1)
            mqttClientManager.subscribe("alarm", 1)
            mqttClientManager.subscribe("sensor/data", 1)
            mqttClientManager.subscribe("time", 1)
            mqttClientManager.subscribe("request", 1)
            mqttClientManager.subscribe("response", 1)
            addSystemLog("已订阅所有主题")
        } catch (e: Exception) {
            addSystemLog("订阅主题失败: ${e.message}")
        }
    }
    
    // 添加日志到ViewModel
    private fun addSystemLog(message: String) {
        activity?.runOnUiThread {
            notificationsViewModel.addToLog(message)
        }
    }
    
    // 更新日志显示
    private fun updateLogDisplay() {
        // 定期检查连接状态，而不是每次刷新都检查
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastConnectionCheckTime > connectionCheckInterval) {
            lastConnectionCheckTime = currentTime
            
            // 检查MQTT连接状态并更新
            if (::mqttClientManager.isInitialized) {
                val isConnected = mqttClientManager.isConnected()
                notificationsViewModel.updateConnectionStatus(isConnected)
                
                // 如果连接状态改变为未连接，且已经稳定一段时间，尝试重连
                if (!isConnected && notificationsViewModel.wasConnected() && 
                    currentTime - notificationsViewModel.getLastStatusChangeTime() > 10000) {
                    Log.d(TAG, "检测到连接已断开一段时间，尝试重新连接")
                    tryReconnect()
                }
            }
        }
        
        // 如果有新消息，确保滚动到底部
        scrollToBottom()
    }
    
    // MQTT状态回调方法
    override fun onConnected() {
        addSystemLog("MQTT 连接成功")
        notificationsViewModel.updateConnectionStatus(true)
        // 订阅所有主题
        subscribeToAllTopics()
    }
    
    override fun onConnectionFailed(error: String) {
        addSystemLog("MQTT 连接失败: $error")
        notificationsViewModel.updateConnectionStatus(false)
    }
    
    override fun onMessageReceived(topic: String, message: String) {
        // 记录收到的消息
        addSystemLog("收到消息 - Topic: $topic | Payload: ${message.take(100)}${if (message.length > 100) "..." else ""}")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Fragment恢复，检查连接状态")
        
        // 启动自动刷新
        refreshHandler.post(refreshRunnable)
        
        // 重新设置MQTT回调，确保能接收消息
        if (::mqttClientManager.isInitialized) {
            mqttClientManager.setCallback(this)
            
            // 检查连接状态并更新UI，但不立即尝试重连
            // 这样可以避免频繁的重连尝试
            val isConnected = mqttClientManager.isConnected()
            notificationsViewModel.updateConnectionStatus(isConnected)
            
            if (isConnected) {
                addSystemLog("MQTT 连接正常")
                // 如果已连接，确保订阅了所有主题
                subscribeToAllTopics()
            } else {
                // 不立即重连，而是等待一段时间
                addSystemLog("MQTT 连接状态检查中...")
                
                // 延迟5秒后检查连接状态，如果仍未连接，再尝试重连
                Handler(Looper.getMainLooper()).postDelayed({
                    if (::mqttClientManager.isInitialized && !mqttClientManager.isConnected()) {
                        addSystemLog("MQTT 连接已断开，尝试重新连接")
                        tryReconnect()
                    }
                }, 5000)
            }
        } else {
            // 如果mqttClientManager未初始化，重新初始化
            setupMqttClient()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 停止自动刷新
        refreshHandler.removeCallbacks(refreshRunnable)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 移除回调，防止内存泄漏
        if (::mqttClientManager.isInitialized) {
            mqttClientManager.setCallback(null)
        }
        // 停止自动刷新
        refreshHandler.removeCallbacks(refreshRunnable)
    }
}