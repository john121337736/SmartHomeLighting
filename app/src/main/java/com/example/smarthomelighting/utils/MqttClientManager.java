package com.example.smarthomelighting.utils;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.os.SystemClock;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MqttClientManager {
    private static final String TAG = "MqttClientManager";
    
    // 单例实例
    private static MqttClientManager instance;
    
    private MqttAndroidClientAdapter mqttClient;
    private final String serverUri;
    private final String clientId;
    private final String username;
    private final String password;
    private final boolean useSSL;
    private MqttStatusCallback mqttStatusCallback;
    
    // 连接状态监控
    private boolean manualDisconnect = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 20; // 增加最大重连次数
    private static final long RECONNECT_DELAY_MS = 3000; // 减少重连延迟到3秒
    private Handler reconnectHandler = new Handler(android.os.Looper.getMainLooper());
    private long lastConnectionAttemptTime = 0; // 记录上次连接尝试时间
    
    // 连接状态稳定性控制
    private boolean isReconnecting = false; // 是否正在重连过程中
    private long lastConnectionStateChangeTime = 0; // 上次连接状态变化时间
    private static final long CONNECTION_STABILITY_THRESHOLD = 10000; // 连接状态稳定阈值(10秒)
    private boolean lastReportedConnectionState = false; // 上次报告的连接状态
    
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected() && !manualDisconnect) {
                // 无限重连，不再限制尝试次数
                Log.d(TAG, "尝试重新连接MQTT，第" + (reconnectAttempts+1) + "次尝试");
                reconnectAttempts++;
                lastConnectionAttemptTime = SystemClock.elapsedRealtime(); // 记录连接尝试时间
                
                // 设置重连状态
                isReconnecting = true;
                
                connect();
                
                // 指数退避策略，最大延迟60秒
                long nextDelay = Math.min(RECONNECT_DELAY_MS * (long)Math.pow(1.5, Math.min(reconnectAttempts, 10)), 60000);
                reconnectHandler.postDelayed(this, nextDelay);
            } else {
                // 如果已连接，结束重连状态
                isReconnecting = false;
            }
        }
    };
    
    // 心跳检测任务
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (isConnected()) {
                    // 发送心跳消息
                    publish("heartbeat", clientId, 0, false);
                    Log.d(TAG, "已发送心跳消息");
                    // 安排下一次心跳
                    Handler handler = new Handler(android.os.Looper.getMainLooper());
                    handler.postDelayed(this, 15000); // 减少心跳间隔到15秒
                } else if (!manualDisconnect && !isReconnecting) {
                    // 检测到连接断开且不是手动断开，也不在重连过程中，尝试重连
                    Log.d(TAG, "心跳检测到连接断开，准备重连");
                    reconnectAttempts = 0;
                    Handler handler = new Handler(android.os.Looper.getMainLooper());
                    handler.post(reconnectRunnable); // 立即开始重连
                }
            } catch (Exception e) {
                Log.e(TAG, "心跳任务异常: " + e.getMessage());
                // 如果心跳发送失败，可能是连接有问题，尝试重连
                if (!manualDisconnect && !isReconnecting) {
                    reconnectHandler.postDelayed(reconnectRunnable, 1000); // 1秒后尝试重连
                }
            }
        }
    };
    
    // 连接状态监控任务
    private final Runnable connectionMonitorTask = new Runnable() {
        @Override
        public void run() {
            if (!manualDisconnect) {
                // 检查是否长时间没有连接尝试（可能是系统休眠后恢复）
                long currentTime = SystemClock.elapsedRealtime();
                if (currentTime - lastConnectionAttemptTime > 120000) { // 如果超过2分钟没有连接尝试
                    Log.d(TAG, "检测到长时间无连接尝试，可能是从休眠状态恢复，强制重连");
                    forceReconnect();
                } else if (!isConnected() && !isReconnecting) {
                    Log.d(TAG, "连接监控检测到连接断开，准备重连");
                    reconnectAttempts = 0;
                    reconnectHandler.post(reconnectRunnable);
                } else {
                    // 连接正常，发送心跳保活
                    try {
                        publish("ping", "{\"client\":\"" + clientId + "\",\"timestamp\":" + System.currentTimeMillis() + "}", 0, false);
                    } catch (Exception e) {
                        Log.e(TAG, "发送ping消息失败: " + e.getMessage());
                    }
                }
            }
            // 每30秒检查一次连接状态
            reconnectHandler.postDelayed(this, 30000);
        }
    };

    // 获取单例实例的方法
    public static synchronized MqttClientManager getInstance(Context context) {
        return instance;
    }
    
    // 设置单例实例的方法（在应用启动时调用）
    public static synchronized void setInstance(MqttClientManager manager) {
        instance = manager;
    }

    public interface MqttStatusCallback {
        void onConnected();
        void onConnectionFailed(String error);
        void onMessageReceived(String topic, String message);
    }

    public MqttClientManager(Context context, String serverUri, String clientId, 
                            String username, String password, boolean useSSL) {
        this.serverUri = useSSL ? "ssl://" + serverUri : "tcp://" + serverUri;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.useSSL = useSSL;
        
        mqttClient = new MqttAndroidClientAdapter(context, this.serverUri, clientId);
        mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                Log.d(TAG, "连接断开: " + (cause != null ? cause.getMessage() : "未知原因"));
                
                // 记录连接状态变化时间
                lastConnectionStateChangeTime = SystemClock.elapsedRealtime();
                
                // 如果不是手动断开连接，尝试自动重连
                if (!manualDisconnect && !isReconnecting) {
                    Log.d(TAG, "非手动断开，准备尝试自动重连");
                    reconnectAttempts = 0;
                    reconnectHandler.removeCallbacks(reconnectRunnable); // 移除可能存在的重连任务
                    reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
                }
                
                // 通知连接状态变化，但添加稳定性控制
                notifyConnectionStateChange(false);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                String messageContent = new String(message.getPayload());
                Log.d(TAG, "收到消息: " + topic + " -> " + messageContent);
                if (mqttStatusCallback != null) {
                    mqttStatusCallback.onMessageReceived(topic, messageContent);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d(TAG, "消息发送完成");
            }
        });
        
        // 设置为单例实例
        setInstance(this);
        
        // 记录初始连接尝试时间
        lastConnectionAttemptTime = SystemClock.elapsedRealtime();
        lastConnectionStateChangeTime = SystemClock.elapsedRealtime();
        
        // 启动连接监控
        reconnectHandler.postDelayed(connectionMonitorTask, 30000);
    }

    public void setCallback(MqttStatusCallback callback) {
        this.mqttStatusCallback = callback;
    }

    /**
     * 获取连接状态，但添加稳定性控制
     * 避免连接状态频繁变化导致UI闪烁
     */
    public boolean isConnected() {
        try {
            boolean currentState = mqttClient != null && mqttClient.isConnected();
            
            // 如果当前状态与上次报告的状态不同，记录变化时间
            if (currentState != lastReportedConnectionState) {
                lastConnectionStateChangeTime = SystemClock.elapsedRealtime();
                // 如果是从未连接变为已连接，立即更新状态
                if (currentState) {
                    lastReportedConnectionState = currentState;
                }
                // 如果是从已连接变为未连接，需要等待稳定期
                // 这个逻辑在notifyConnectionStateChange中处理
            }
            
            return currentState;
        } catch (Exception e) {
            Log.e(TAG, "检查连接状态时发生异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 通知连接状态变化，添加稳定性控制
     * 避免连接状态频繁变化导致UI闪烁
     */
    private void notifyConnectionStateChange(boolean isConnected) {
        long currentTime = SystemClock.elapsedRealtime();
        
        // 如果状态变化时间不足阈值，且是断开连接的通知，暂不通知UI
        if (currentTime - lastConnectionStateChangeTime < CONNECTION_STABILITY_THRESHOLD && 
            !isConnected && lastReportedConnectionState) {
            Log.d(TAG, "连接断开，但在稳定期内，暂不通知UI变化");
            return;
        }
        
        // 更新最后报告的状态
        lastReportedConnectionState = isConnected;
        
        // 通知回调
        if (mqttStatusCallback != null) {
            if (isConnected) {
                mqttStatusCallback.onConnected();
            } else {
                mqttStatusCallback.onConnectionFailed("连接断开");
            }
        }
    }

    /**
     * 强制重新连接MQTT
     * 用于锁屏后恢复或网络变化时调用
     */
    public void forceReconnect() {
        Log.d(TAG, "强制重新连接MQTT");
        
        // 如果正在重连，不重复触发
        if (isReconnecting) {
            Log.d(TAG, "已在重连过程中，不重复触发");
            return;
        }
        
        // 设置重连状态
        isReconnecting = true;
        
        // 如果已连接，先断开
        if (isConnected()) {
            try {
                mqttClient.disconnect(0);
                Log.d(TAG, "断开现有连接以便重新连接");
            } catch (Exception e) {
                Log.e(TAG, "断开连接失败: " + e.getMessage());
            }
        }
        
        // 重置状态
        manualDisconnect = false;
        reconnectAttempts = 0;
        
        // 移除所有待处理的重连任务
        reconnectHandler.removeCallbacks(reconnectRunnable);
        
        // 立即尝试连接
        lastConnectionAttemptTime = SystemClock.elapsedRealtime();
        
        // 使用延迟连接，避免网络还未准备好的情况
        reconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "执行延迟重连");
                connect();
                
                // 再次检查连接状态
                reconnectHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isConnected()) {
                            Log.d(TAG, "延迟重连后仍未连接，再次尝试");
                            connect();
                        }
                        
                        // 无论结果如何，结束重连状态
                        isReconnecting = false;
                    }
                }, 3000);
            }
        }, 1000);
    }

    /**
     * 检查连接状态并尝试恢复连接
     * 用于应用从后台恢复时调用
     */
    public void checkConnectionAndReconnect() {
        Log.d(TAG, "检查连接状态并尝试恢复连接");
        
        // 如果正在重连，不重复触发
        if (isReconnecting) {
            Log.d(TAG, "已在重连过程中，不重复触发");
            return;
        }
        
        if (!isConnected() && !manualDisconnect) {
            Log.d(TAG, "连接已断开，尝试恢复");
            forceReconnect();
        } else if (isConnected()) {
            Log.d(TAG, "连接正常，发送ping消息确认连接");
            try {
                // 发送ping消息确认连接是否真的正常
                publish("ping", "{\"client\":\"" + clientId + "\",\"timestamp\":" + System.currentTimeMillis() + "\",\"action\":\"check\"}", 0, false);
            } catch (Exception e) {
                Log.e(TAG, "发送ping消息失败，连接可能有问题: " + e.getMessage());
                // 如果发送失败，尝试重连
                forceReconnect();
            }
        }
    }

    public boolean connect() {
        try {
            // 如果已经在重连过程中，更新时间戳
            if (isReconnecting) {
                lastConnectionAttemptTime = SystemClock.elapsedRealtime();
            } else {
                isReconnecting = true;
            }
            
            MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
            mqttConnectOptions.setAutomaticReconnect(true);
            mqttConnectOptions.setCleanSession(false);
            mqttConnectOptions.setKeepAliveInterval(30); // 减少keepAlive间隔到30秒
            mqttConnectOptions.setMaxInflight(100); // 增加最大并发消息数
            mqttConnectOptions.setConnectionTimeout(30); // 设置更长的连接超时时间
            // 设置遗嘱消息，当客户端异常断开时，服务器会发布此消息
            mqttConnectOptions.setWill("client/status", 
                                      ("{\"clientId\":\"" + clientId + "\",\"status\":\"offline\"}").getBytes(), 
                                      1, 
                                      true);
            
            // 设置用户名和密码（如果有）
            if (username != null && !username.isEmpty()) {
                mqttConnectOptions.setUserName(username);
            }
            if (password != null && !password.isEmpty()) {
                mqttConnectOptions.setPassword(password.toCharArray());
            }
            
            // 如果使用SSL且需要跳过证书验证
            if (useSSL) {
                mqttConnectOptions.setSocketFactory(new NonValidatingSSLSocketFactory());
            }
            
            Log.d(TAG, "开始连接到: " + serverUri);
            
            mqttClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "连接成功");
                    
                    // 重置重连计数
                    reconnectAttempts = 0;
                    manualDisconnect = false;
                    isReconnecting = false;
                    
                    // 记录连接状态变化时间
                    lastConnectionStateChangeTime = SystemClock.elapsedRealtime();
                    
                    // 通知连接成功
                    notifyConnectionStateChange(true);
                    
                    // 设置断开连接后的缓冲区选项
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(200); // 增加缓冲区大小
                    disconnectedBufferOptions.setPersistBuffer(true); // 持久化缓冲区
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttClient.setBufferOpts(disconnectedBufferOptions);
                    
                    // 发布上线状态
                    try {
                        publish("client/status", 
                               "{\"clientId\":\"" + clientId + "\",\"status\":\"online\",\"timestamp\":" + System.currentTimeMillis() + "}", 
                               1, 
                               true);
                    } catch (Exception e) {
                        Log.e(TAG, "发布上线状态失败: " + e.getMessage());
                    }
                    
                    // 启动心跳检测
                    new Handler(android.os.Looper.getMainLooper()).postDelayed(heartbeatTask, 15000);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "连接失败: " + (exception != null ? exception.getMessage() : "未知原因"));
                    
                    // 记录连接状态变化时间
                    lastConnectionStateChangeTime = SystemClock.elapsedRealtime();
                    
                    // 尝试重新连接
                    if (!manualDisconnect) {
                        // 重连逻辑已经在reconnectRunnable中处理
                        // 通知连接失败
                        notifyConnectionStateChange(false);
                    }
                    
                    // 结束重连状态
                    isReconnecting = false;
                }
            });
            return true;
        } catch (MqttException e) {
            Log.e(TAG, "连接MQTT时发生错误: " + e.getMessage());
            e.printStackTrace();
            
            // 通知连接失败
            notifyConnectionStateChange(false);
            
            // 结束重连状态
            isReconnecting = false;
            
            return false;
        }
    }

    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                // 标记为手动断开连接，不要自动重连
                manualDisconnect = true;
                
                // 停止所有重连和监控任务
                reconnectHandler.removeCallbacks(reconnectRunnable);
                reconnectHandler.removeCallbacks(connectionMonitorTask);
                
                // 发布离线状态
                try {
                    publish("client/status", 
                           "{\"clientId\":\"" + clientId + "\",\"status\":\"offline\",\"timestamp\":" + System.currentTimeMillis() + "}", 
                           1, 
                           true);
                } catch (Exception e) {
                    Log.e(TAG, "发布离线状态失败: " + e.getMessage());
                }
                
                mqttClient.disconnect();
                Log.d(TAG, "MQTT客户端已手动断开连接");
            } catch (MqttException e) {
                Log.e(TAG, "断开连接时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void subscribe(String topic, int qos) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.subscribe(topic, qos, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        Log.d(TAG, "订阅成功: " + topic);
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Log.e(TAG, "订阅失败: " + (exception != null ? exception.getMessage() : "未知原因"));
                        // 如果是因为连接问题导致的订阅失败，尝试重连
                        if (!isConnected() && !manualDisconnect) {
                            reconnectHandler.removeCallbacks(reconnectRunnable);
                            reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
                        }
                    }
                });
            } catch (MqttException e) {
                Log.e(TAG, "订阅时发生错误: " + e.getMessage());
                e.printStackTrace();
                // 如果是因为连接问题导致的订阅失败，尝试重连
                if (!isConnected() && !manualDisconnect) {
                    reconnectHandler.removeCallbacks(reconnectRunnable);
                    reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
                }
            }
        } else {
            Log.e(TAG, "无法订阅，MQTT客户端未连接");
            // 尝试重连后再订阅
            if (!manualDisconnect) {
                Log.d(TAG, "尝试重连后再订阅: " + topic);
                final String topicToSubscribe = topic;
                final int qosToUse = qos;
                reconnectHandler.postDelayed(() -> {
                    if (connect()) {
                        reconnectHandler.postDelayed(() -> subscribe(topicToSubscribe, qosToUse), 1000);
                    }
                }, RECONNECT_DELAY_MS);
            }
        }
    }

    public void publish(String topic, String message, int qos, boolean retained) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage mqttMessage = new MqttMessage();
                mqttMessage.setPayload(message.getBytes());
                mqttMessage.setQos(qos);
                mqttMessage.setRetained(retained);
                
                mqttClient.publish(topic, mqttMessage);
                Log.d(TAG, "消息已发布: " + topic + " -> " + message);
            } catch (MqttException e) {
                Log.e(TAG, "发布消息时发生错误: " + e.getMessage());
                e.printStackTrace();
                
                // 如果发布失败，可能是连接有问题，尝试重连
                if (!isConnected() && !manualDisconnect) {
                    reconnectHandler.removeCallbacks(reconnectRunnable);
                    reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
                }
            }
        } else {
            Log.e(TAG, "无法发布消息，MQTT客户端未连接");
            
            // 尝试重连后再发布
            if (!manualDisconnect) {
                final String topicToPublish = topic;
                final String messageToPublish = message;
                final int qosToUse = qos;
                final boolean retainedToUse = retained;
                
                reconnectHandler.postDelayed(() -> {
                    if (connect()) {
                        reconnectHandler.postDelayed(() -> publish(topicToPublish, messageToPublish, qosToUse, retainedToUse), 1000);
                    }
                }, RECONNECT_DELAY_MS);
            }
        }
    }

    // 获取客户端ID的公共方法
    public String getClientId() {
        return clientId;
    }
} 