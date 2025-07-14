package com.example.smarthomelighting.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.smarthomelighting.R
import com.example.smarthomelighting.SmartHomeLightingApplication
import com.example.smarthomelighting.utils.MqttClientManager
import com.example.smarthomelighting.utils.MqttAndroidClientAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage

class SettingsFragment : Fragment() {

    private lateinit var viewModel: SettingsViewModel
    
    // 输入字段
    private lateinit var serverAddressInput: TextInputEditText
    private lateinit var serverPortInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var statusTopicInput: TextInputEditText
    private lateinit var controlTopicInput: TextInputEditText
    private lateinit var clientIdInput: TextInputEditText
    
    // 按钮
    private lateinit var backButton: ImageButton
    private lateinit var saveButton: MaterialButton
    
    // 滚动视图
    private lateinit var scrollView: ScrollView
    
    // SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        
        // 初始化ViewModel
        viewModel = ViewModelProvider(this).get(SettingsViewModel::class.java)
        
        // 初始化SharedPreferences
        sharedPreferences = requireActivity().getSharedPreferences(
            "mqtt_settings", Context.MODE_PRIVATE
        )
        
        // 初始化视图
        initViews(root)
        
        // 加载已保存的设置
        loadSavedSettings()
        
        // 设置点击事件
        setupClickListeners()
        
        // 设置输入框焦点监听，防止输入法遮挡
        setupInputFocusListeners()
        
        return root
    }
    
    private fun initViews(root: View) {
        // 输入字段
        serverAddressInput = root.findViewById(R.id.server_address_input)
        serverPortInput = root.findViewById(R.id.server_port_input)
        usernameInput = root.findViewById(R.id.username_input)
        passwordInput = root.findViewById(R.id.password_input)
        statusTopicInput = root.findViewById(R.id.status_topic_input)
        controlTopicInput = root.findViewById(R.id.control_topic_input)
        clientIdInput = root.findViewById(R.id.client_id_input)
        
        // 按钮
        backButton = root.findViewById(R.id.back_button)
        saveButton = root.findViewById(R.id.save_button)
        
        // 获取滚动视图
        scrollView = root.findViewById(R.id.settings_scroll_view)
    }
    
    private fun setupInputFocusListeners() {
        // 为每个输入框设置焦点监听器
        val inputFields = listOf(
            serverAddressInput,
            serverPortInput,
            usernameInput,
            passwordInput,
            statusTopicInput,
            controlTopicInput,
            clientIdInput
        )
        
        for (input in inputFields) {
            input.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    // 延迟滚动到当前输入框位置
                    scrollView.postDelayed({
                        // 计算滚动位置，加上额外的偏移量确保输入框在可见区域中间位置
                        val scrollY = v.top - scrollView.height / 3
                        scrollView.smoothScrollTo(0, scrollY)
                    }, 300)
                }
            }
        }
    }
    
    private fun loadSavedSettings() {
        try {
            // 从SharedPreferences加载保存的设置
            val savedServerAddress = sharedPreferences.getString("server_address", "k6dffa53.ala.cn-hangzhou.emqxsl.cn")
            val savedServerPort = sharedPreferences.getString("server_port", "8883")
            val savedUsername = sharedPreferences.getString("username", "wan")
            val savedPassword = sharedPreferences.getString("password", "121337736")
            val savedStatusTopic = sharedPreferences.getString("status_topic", "status")
            val savedControlTopic = sharedPreferences.getString("control_topic", "control")
            val savedClientId = sharedPreferences.getString("client_id", "SmartLightingApp_${System.currentTimeMillis()}")
            
            // 设置表单值
            serverAddressInput.setText(savedServerAddress)
            serverPortInput.setText(savedServerPort)
            usernameInput.setText(savedUsername)
            passwordInput.setText(savedPassword)
            statusTopicInput.setText(savedStatusTopic)
            controlTopicInput.setText(savedControlTopic)
            clientIdInput.setText(savedClientId)
            
            Log.d("SettingsFragment", "已加载MQTT设置: 服务器=$savedServerAddress:$savedServerPort")
            
        } catch (e: Exception) {
            Log.e("SettingsFragment", "加载设置失败: ${e.message}")
            Toast.makeText(context, "加载设置失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupClickListeners() {
        // 返回按钮点击事件
        backButton.setOnClickListener {
            // 隐藏键盘
            hideKeyboard()
            findNavController().navigateUp()
        }
        
        // 保存按钮点击事件
        saveButton.setOnClickListener {
            if (validateInputs()) {
                saveSettings()
                reconnectMqttClient()
                // 隐藏键盘
                hideKeyboard()
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        }
    }
    
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }
    
    private fun validateInputs(): Boolean {
        // 验证服务器地址
        if (TextUtils.isEmpty(serverAddressInput.text)) {
            serverAddressInput.error = "服务器地址不能为空"
            return false
        }
        
        // 验证服务器端口
        if (TextUtils.isEmpty(serverPortInput.text)) {
            serverPortInput.error = "服务器端口不能为空"
            return false
        }
        
        // 验证客户端ID
        if (TextUtils.isEmpty(clientIdInput.text)) {
            clientIdInput.error = "客户端ID不能为空"
            return false
        }
        
        // 验证接收数据主题
        if (TextUtils.isEmpty(statusTopicInput.text)) {
            statusTopicInput.error = "接收数据主题不能为空"
            return false
        }
        
        // 验证发送控制命令主题
        if (TextUtils.isEmpty(controlTopicInput.text)) {
            controlTopicInput.error = "发送控制命令主题不能为空"
            return false
        }
        
        return true
    }
    
    private fun saveSettings() {
        // 保存设置到SharedPreferences
        val editor = sharedPreferences.edit()
        editor.putString("server_address", serverAddressInput.text.toString())
        editor.putString("server_port", serverPortInput.text.toString())
        editor.putString("username", usernameInput.text.toString())
        editor.putString("password", passwordInput.text.toString())
        editor.putString("status_topic", statusTopicInput.text.toString())
        editor.putString("control_topic", controlTopicInput.text.toString())
        editor.putString("client_id", clientIdInput.text.toString())
        editor.apply()
    }
    
    private fun reconnectMqttClient() {
        try {
            // 获取当前MQTT客户端实例
            val oldMqttClientManager = SmartHomeLightingApplication.instance.getMqttClientManager()
            
            // 如果已连接，断开连接
            if (oldMqttClientManager.isConnected()) {
                oldMqttClientManager.disconnect()
            }
            
            // 从SharedPreferences读取新设置
            val serverAddress = sharedPreferences.getString("server_address", "") ?: ""
            val serverPort = sharedPreferences.getString("server_port", "1883") ?: "1883"
            val clientId = sharedPreferences.getString("client_id", "SmartLightingApp") ?: "SmartLightingApp"
            val username = sharedPreferences.getString("username", "") ?: ""
            val password = sharedPreferences.getString("password", "") ?: ""
            
            // 创建新的MQTT客户端
            val newMqttClientManager = MqttClientManager(
                requireContext(),
                "$serverAddress:$serverPort",
                clientId,
                username,
                password,
                true // 使用SSL
            )
            
            // 设置为新的单例实例
            MqttClientManager.setInstance(newMqttClientManager)
            
            // 连接新的MQTT客户端
            newMqttClientManager.connect()
            
            Log.d("SettingsFragment", "已应用新的MQTT设置并重新连接")
        } catch (e: Exception) {
            Log.e("SettingsFragment", "重新连接MQTT失败: ${e.message}")
            Toast.makeText(context, "重新连接MQTT失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
} 