package com.example.smarthomelighting.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 一个简单的MQTT客户端适配器，使用直接Socket实现
 * 注意：这是一个简化实现，仅支持基本功能
 */
public class MqttAndroidClientAdapter {
    private static final String TAG = "MqttAndroidClientAdapter";
    private final Context context;
    private final String host;
    private final int port;
    private final String clientId;
    private MqttCallback callback;
    private Socket socket;
    private BufferedOutputStream outputStream;
    private BufferedInputStream inputStream;
    private boolean connected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executorService;
    private Thread readThread;

    // 连接状态监控
    private long lastPingResponseTime = 0;
    private int missedPings = 0;
    private static final int MAX_MISSED_PINGS = 3;
    private final Object connectionLock = new Object();

    public MqttAndroidClientAdapter(Context context, String serverURI, String clientId) {
        this.context = context;
        
        // 解析服务器URI
        // 先移除URI中可能存在的协议前缀
        String cleanServerURI = serverURI;
        if (cleanServerURI.startsWith("tcp://")) {
            cleanServerURI = cleanServerURI.substring(6);
        } else if (cleanServerURI.startsWith("ssl://")) {
            cleanServerURI = cleanServerURI.substring(6);
        }
        
        // 解析主机名和端口
        String[] parts = cleanServerURI.split(":");
        this.host = parts[0];
        this.port = parts.length > 1 ? Integer.parseInt(parts[1]) : 1883;
        this.clientId = clientId;
        
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        
        Log.d(TAG, "MQTT客户端初始化，主机: " + host + ", 端口: " + port);
    }
    
    public MqttAndroidClientAdapter(Context context, String serverURI, String clientId, MqttClientPersistence persistence) {
        this(context, serverURI, clientId); // 忽略持久化选项
    }
    
    public void setCallback(MqttCallback callback) {
        this.callback = callback;
    }
    
    public IMqttToken connect(MqttConnectOptions options) throws MqttException {
        return connect(options, null, null);
    }
    
    public IMqttToken connect() throws MqttException {
        return connect(new MqttConnectOptions(), null, null);
    }
    
    public IMqttToken connect(MqttConnectOptions options, Object userContext, IMqttActionListener callback) throws MqttException {
        executorService.submit(() -> {
            try {
                Log.d(TAG, "正在连接到MQTT服务器: " + host + ":" + port);
                
                // 创建Socket连接
                socket = new Socket();
                
                // 设置Socket选项
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30000); // 30秒读取超时
                
                // 如果使用SSL，则使用SSLSocketFactory包装Socket
                if (options.getSocketFactory() != null) {
                    Log.d(TAG, "使用SSL连接");
                    
                    // 直接使用SSL连接，不需要先创建普通Socket
                    socket = options.getSocketFactory().createSocket(host, port);
                    
                    // 设置SSL Socket选项
                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(30000); // 30秒读取超时
                    
                    Log.d(TAG, "SSL Socket已创建");
                } else {
                    // 普通TCP连接
                    socket.connect(new InetSocketAddress(host, port), 10000);
                }
                
                // 获取输入输出流
                outputStream = new BufferedOutputStream(socket.getOutputStream());
                inputStream = new BufferedInputStream(socket.getInputStream());
                
                // 发送连接请求 (简化版，模拟一个真实的MQTT CONNECT包)
                byte[] connectPacket = createMqttConnectPacket(clientId, options);
                outputStream.write(connectPacket);
                outputStream.flush();
                
                // 更新连接状态
                synchronized (connectionLock) {
                connected = true;
                    lastPingResponseTime = System.currentTimeMillis();
                    missedPings = 0;
                }
                
                // 启动读取线程
                startReadThread();
                
                // 模拟成功的CONNACK (在实际实现中应该读取并解析服务器响应)
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess(null);
                    }
                    
                    if (this.callback != null) {
                        try {
                            // 不能直接调用connectComplete，因为MqttCallback接口中没有此方法
                            // this.callback.connectComplete(false, host + ":" + port);
                            // 只通知连接成功
                            Log.d(TAG, "MQTT连接已成功，准备处理消息");
                        } catch (Exception e) {
                            Log.e(TAG, "回调时出错", e);
                        }
                    }
                });
                
                Log.d(TAG, "MQTT连接成功");
                
                // 启动保活
                startKeepAlive();
                
                // 启动连接监控
                startConnectionMonitor();
                
            } catch (Exception e) {
                Log.e(TAG, "MQTT连接失败: " + e.getMessage(), e);
                
                // 清理资源
                cleanUp();
                
                // 通知回调
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onFailure(null, e);
                    }
                    
                    if (this.callback != null) {
                        try {
                            this.callback.connectionLost(e);
                        } catch (Exception callbackEx) {
                            Log.e(TAG, "回调connectionLost时出错", callbackEx);
                        }
                    }
                });
            }
        });
        
        return null;
    }
    
    private void startReadThread() {
        readThread = new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                int bytesRead;
                
                while (connected && (bytesRead = inputStream.read(buffer)) != -1) {
                    // 处理接收到的MQTT包
                    processMqttPacket(buffer, bytesRead);
                }
            } catch (IOException e) {
                if (connected) {
                    Log.e(TAG, "读取MQTT消息错误", e);
                    
                    // 连接丢失
                    mainHandler.post(() -> {
                        if (this.callback != null) {
                            try {
                                this.callback.connectionLost(e);
                            } catch (Exception callbackEx) {
                                Log.e(TAG, "回调connectionLost时出错", callbackEx);
                            }
                        }
                    });
                    
                    cleanUp();
                }
            }
        });
        readThread.setName("MQTT-Read-Thread");
        readThread.setPriority(Thread.MAX_PRIORITY); // 提高读线程优先级
        readThread.start();
    }
    
    private void startKeepAlive() {
        executorService.scheduleAtFixedRate(() -> {
            try {
                // 先检查连接状态和输出流是否可用
                if (connected && outputStream != null && socket != null && !socket.isClosed()) {
                    // 发送PINGREQ包
                    byte[] pingPacket = new byte[] { (byte) 0xC0, 0x00 };
                    outputStream.write(pingPacket);
                    outputStream.flush();
                    Log.d(TAG, "发送MQTT保活包");
                    
                    // 记录未响应的ping次数
                    synchronized (connectionLock) {
                        missedPings++;
                        if (missedPings > MAX_MISSED_PINGS) {
                            Log.e(TAG, "多次ping无响应，认为连接已断开");
                            // 模拟连接丢失
                            Exception e = new IOException("连接超时：" + MAX_MISSED_PINGS + "次ping无响应");
                            mainHandler.post(() -> {
                                if (this.callback != null) {
                                    try {
                                        this.callback.connectionLost(e);
                                    } catch (Exception callbackEx) {
                                        Log.e(TAG, "回调connectionLost时出错", callbackEx);
                                    }
                                }
                            });
                            
                            cleanUp();
                        }
                    }
                } else if (connected) {
                    // 连接标记为已连接但Socket已关闭或输出流不可用
                    Log.e(TAG, "连接标记为已连接但Socket已关闭或输出流不可用，执行清理");
                    Exception e = new IOException("连接异常：Socket已关闭或输出流不可用");
                    mainHandler.post(() -> {
                        if (this.callback != null) {
                            try {
                                this.callback.connectionLost(e);
                            } catch (Exception callbackEx) {
                                Log.e(TAG, "回调connectionLost时出错", callbackEx);
                            }
                        }
                    });
                    
                    cleanUp();
                }
            } catch (Exception e) {
                Log.e(TAG, "发送保活包失败", e);
                
                // 如果发送保活包失败，可能是连接已断开
                if (connected) {
                    mainHandler.post(() -> {
                        if (this.callback != null) {
                            try {
                                this.callback.connectionLost(e);
                            } catch (Exception callbackEx) {
                                Log.e(TAG, "回调connectionLost时出错", callbackEx);
                            }
                        }
                    });
                    
                    cleanUp();
                }
            }
        }, 15, 15, TimeUnit.SECONDS); // 减少保活间隔到15秒
    }
    
    private void startConnectionMonitor() {
        executorService.scheduleAtFixedRate(() -> {
            synchronized (connectionLock) {
                if (connected && socket != null && !socket.isClosed()) {
                    long now = System.currentTimeMillis();
                    long timeSinceLastPing = now - lastPingResponseTime;
                    
                    // 如果超过60秒没收到任何服务器响应，认为连接已断开
                    if (timeSinceLastPing > 60000) {
                        Log.e(TAG, "连接监控：超过60秒未收到服务器响应，认为连接已断开");
                        
                        // 模拟连接丢失
                        Exception e = new IOException("连接监控：超过60秒未收到服务器响应");
                        mainHandler.post(() -> {
                            if (this.callback != null) {
                                try {
                                    this.callback.connectionLost(e);
                                } catch (Exception callbackEx) {
                                    Log.e(TAG, "回调connectionLost时出错", callbackEx);
                                }
                            }
                        });
                        
                        cleanUp();
                    }
                } else if (connected) {
                    // 连接标记为已连接但Socket已关闭
                    Log.e(TAG, "连接监控：连接标记为已连接但Socket已关闭，执行清理");
                    Exception e = new IOException("连接异常：Socket已关闭");
                    mainHandler.post(() -> {
                        if (this.callback != null) {
                            try {
                                this.callback.connectionLost(e);
                            } catch (Exception callbackEx) {
                                Log.e(TAG, "回调connectionLost时出错", callbackEx);
                            }
                        }
                    });
                    
                    cleanUp();
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    private void processMqttPacket(byte[] buffer, int length) {
        // 在实际实现中，这里应该解析MQTT包并调用适当的回调
        // 这里只是一个简化版，仅演示基本概念
        
        if (length <= 0) {
            Log.w(TAG, "收到空的MQTT数据包");
            return;
        }
        
        try {
            // 更新最后一次收到服务器响应的时间
            synchronized (connectionLock) {
                lastPingResponseTime = System.currentTimeMillis();
                missedPings = 0; // 重置未响应ping计数
            }
            
            // 获取包类型
            int packetType = (buffer[0] & 0xF0) >> 4;
            
            // 处理不同类型的包
            switch (packetType) {
                case 3: // PUBLISH
                    // 解析PUBLISH包
                    try {
                        // 记录原始数据包内容供调试
                        logPacketHex(buffer, length);
                        
                        // 确保包长度足够
                        if (length < 4) {
                            Log.w(TAG, "PUBLISH包太短，无法解析");
                            return;
                        }
                        
                        // 解析剩余长度（变长编码）
                        int multiplier = 1;
                        int remainingLength = 0;
                        int bytesUsed = 1; // 已经处理了第一个字节（固定头部）
                        
                        byte encodedByte;
                        do {
                            if (bytesUsed >= length) {
                                Log.e(TAG, "无效的剩余长度编码");
                                return;
                            }
                            
                            encodedByte = buffer[bytesUsed++];
                            remainingLength += (encodedByte & 0x7F) * multiplier;
                            multiplier *= 128;
                            
                            if (multiplier > 128 * 128 * 128) {
                                Log.e(TAG, "剩余长度超出范围");
                                return;
                            }
                        } while ((encodedByte & 0x80) != 0);
                        
                        // 现在bytesUsed指向可变头部的开始
                        
                        // 确保有足够的字节表示主题长度
                        if (bytesUsed + 2 > length) {
                            Log.w(TAG, "数据包中没有足够的字节表示主题长度");
                            return;
                        }
                        
                        // 读取主题长度
                        int topicLength = ((buffer[bytesUsed] & 0xFF) << 8) | (buffer[bytesUsed + 1] & 0xFF);
                        bytesUsed += 2;
                        
                        // 确保主题长度有效
                        if (topicLength <= 0 || bytesUsed + topicLength > length) {
                            Log.e(TAG, "主题长度无效: " + topicLength + ", 剩余字节数: " + (length - bytesUsed));
                            return;
                        }
                        
                        // 提取主题
                        byte[] topicBytes = new byte[topicLength];
                        System.arraycopy(buffer, bytesUsed, topicBytes, 0, topicLength);
                        String topic = new String(topicBytes);
                        bytesUsed += topicLength;
                        
                        // 可能存在报文标识符，如果是QoS 1或2（在固定头部的第1位和第2位）
                        int qos = (buffer[0] & 0x06) >> 1;
                        if (qos > 0) {
                            // 跳过2字节报文标识符
                            bytesUsed += 2;
                        }
                        
                        // 计算有效载荷长度
                        int payloadLength = length - bytesUsed;
                        if (payloadLength <= 0) {
                            Log.w(TAG, "PUBLISH包没有有效载荷");
                            return;
                        }
                        
                        // 提取有效载荷
                        byte[] payloadBytes = new byte[payloadLength];
                        System.arraycopy(buffer, bytesUsed, payloadBytes, 0, payloadLength);
                        String payload = new String(payloadBytes);
                        
                        // 回调消息到达
                        Log.d(TAG, "收到MQTT消息: " + topic + " -> " + payload);
                        
                        final String finalTopic = topic;
                        final String finalPayload = payload;
                        
                        mainHandler.post(() -> {
                            if (this.callback != null) {
                                try {
                                    MqttMessage message = new MqttMessage(finalPayload.getBytes());
                                    message.setQos(qos);
                                    message.setRetained((buffer[0] & 0x01) == 1);
                                    this.callback.messageArrived(finalTopic, message);
                                } catch (Exception e) {
                                    Log.e(TAG, "回调messageArrived时出错", e);
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "解析PUBLISH包时出错: " + e.getMessage(), e);
                    }
                    break;
                    
                case 13: // PINGRESP
                    Log.d(TAG, "收到PING响应");
                    break;
                    
                default:
                    Log.d(TAG, "收到MQTT包类型: " + packetType);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "处理MQTT包时出错", e);
        }
    }
    
    // 用于调试的辅助方法，将包内容以十六进制形式打印出来
    private void logPacketHex(byte[] buffer, int length) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据包内容: ");
        int printLength = Math.min(length, 64); // 限制打印长度，避免日志过长
        
        for (int i = 0; i < printLength; i++) {
            sb.append(String.format("%02X ", buffer[i]));
        }
        
        if (printLength < length) {
            sb.append("... (总长度: ").append(length).append(" 字节)");
        }
        
        Log.d(TAG, sb.toString());
    }
    
    private byte[] createMqttConnectPacket(String clientId, MqttConnectOptions options) {
        // 这是一个简化版的MQTT CONNECT包
        // 在实际实现中，应该根据MQTT规范正确构建包
        
        // 计算CONNECT变长头部长度
        int variableHeaderLength = 10;
        
        // 计算载荷长度 - 客户端ID
        int payloadLength = 2 + clientId.length();
        
        // 如果有用户名，增加用户名长度
        String username = options.getUserName();
        if (username != null && !username.isEmpty()) {
            payloadLength += 2 + username.length();
        }
        
        // 如果有密码，增加密码长度
        char[] password = options.getPassword();
        int passwordLength = 0;
        if (password != null && password.length > 0) {
            passwordLength = password.length;
            payloadLength += 2 + passwordLength;
        }
        
        // 计算CONNECT包总长度
        int remainingLength = variableHeaderLength + payloadLength;
        
        // 创建CONNECT包
        byte[] packet = new byte[1 + 1 + remainingLength]; // 1字节固定头部 + 1字节剩余长度 + 剩余长度
        
        // 设置固定头部 - CONNECT包类型
        packet[0] = 0b00010000; // CONNECT = 1 << 4
        
        // 设置剩余长度
        packet[1] = (byte) remainingLength;
        
        // 设置可变头部
        // 协议名称长度（0x00, 0x04）和协议名称（'M', 'Q', 'T', 'T'）
        packet[2] = 0x00;
        packet[3] = 0x04;
        packet[4] = 'M';
        packet[5] = 'Q';
        packet[6] = 'T';
        packet[7] = 'T';
        
        // 协议版本 (0x04 for MQTT 3.1.1)
        packet[8] = 0x04;
        
        // 连接标志
        byte connectFlags = 0b00000010; // 设置清除会话标志
        
        // 如果提供了用户名和密码，设置相应标志
        if (username != null && !username.isEmpty()) {
            connectFlags |= 0b10000000; // 用户名标志
        }
        
        if (password != null && password.length > 0) {
            connectFlags |= 0b01000000; // 密码标志
        }
        
        packet[9] = connectFlags;
        
        // 保活时间（60秒）
        packet[10] = 0x00;
        packet[11] = 0x3C;
        
        // 客户端ID长度和客户端ID
        packet[12] = 0x00;
        packet[13] = (byte) clientId.length();
        
        // 复制客户端ID
        System.arraycopy(clientId.getBytes(), 0, packet, 14, clientId.length());
        
        // 当前位置
        int currentPosition = 14 + clientId.length();
        
        // 如果有用户名，添加到数据包
        if (username != null && !username.isEmpty()) {
            // 用户名长度
            packet[currentPosition++] = 0x00;
            packet[currentPosition++] = (byte) username.length();
            
            // 复制用户名
            System.arraycopy(username.getBytes(), 0, packet, currentPosition, username.length());
            currentPosition += username.length();
        }
        
        // 如果有密码，添加到数据包
        if (password != null && password.length > 0) {
            // 密码长度
            packet[currentPosition++] = 0x00;
            packet[currentPosition++] = (byte) passwordLength;
            
            // 复制密码
            for (int i = 0; i < passwordLength; i++) {
                packet[currentPosition + i] = (byte) password[i];
            }
        }
        
        return packet;
    }
    
    public void disconnect() throws MqttException {
        disconnect(0L);
    }
    
    public void disconnect(long quiesceTimeout) throws MqttException {
        disconnect(quiesceTimeout, null, null);
    }
    
    public IMqttToken disconnect(long quiesceTimeout, Object userContext, IMqttActionListener callback) throws MqttException {
        executorService.submit(() -> {
            try {
                if (connected) {
                    // 发送DISCONNECT包
                    byte[] disconnectPacket = { (byte)0xE0, 0x00 };
                    outputStream.write(disconnectPacket);
                    outputStream.flush();
                    
                    Log.d(TAG, "发送MQTT断开连接请求");
                    
                    // 清理资源
                    cleanUp();
                    
                    // 通知回调
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onSuccess(null);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "断开MQTT连接失败: " + e.getMessage(), e);
                
                // 清理资源
                cleanUp();
                
                // 通知回调
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onFailure(null, e);
                    }
                });
            }
        });
        
        return null;
    }
    
    private void cleanUp() {
        synchronized (connectionLock) {
            if (!connected) {
                return; // 已经清理过，避免重复清理
            }
            
        connected = false;
        }
        
        try {
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
            
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭Socket时出错", e);
                } finally {
                    socket = null;
                }
            }
            
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭输入流时出错", e);
                } finally {
                    inputStream = null;
                }
            }
            
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭输出流时出错", e);
                } finally {
                    outputStream = null;
                }
            }
            
            Log.d(TAG, "MQTT连接资源已清理");
        } catch (Exception e) {
            Log.e(TAG, "清理资源时出错", e);
        }
    }
    
    public IMqttToken subscribe(String topic, int qos) throws MqttException {
        return subscribe(topic, qos, null, null);
    }
    
    public IMqttToken subscribe(String topic, int qos, Object userContext, IMqttActionListener callback) throws MqttException {
        executorService.submit(() -> {
            try {
                if (connected) {
                    // 创建SUBSCRIBE包
                    byte[] subscribePacket = createMqttSubscribePacket(topic, qos);
                    outputStream.write(subscribePacket);
                    outputStream.flush();
                    
                    Log.d(TAG, "发送MQTT订阅请求: " + topic);
                    
                    // 模拟成功的SUBACK
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onSuccess(null);
                        }
                    });
                } else {
                    throw new IOException("MQTT客户端未连接");
                }
            } catch (Exception e) {
                Log.e(TAG, "订阅MQTT主题失败: " + e.getMessage(), e);
                
                // 通知回调
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onFailure(null, e);
                    }
                });
            }
        });
        
        return null;
    }
    
    private byte[] createMqttSubscribePacket(String topic, int qos) {
        // 固定头部：SUBSCRIBE = 8 << 4 | 2
        byte fixedHeader = (byte) 0x82;
        
        // 计算剩余长度
        int variableHeaderLength = 2; // 报文标识符
        int payloadLength = 2 + topic.length() + 1; // 主题过滤器长度(2) + 主题过滤器 + 请求的QoS(1)
        int remainingLength = variableHeaderLength + payloadLength;
        
        // 创建数据包
        byte[] packet = new byte[1 + 1 + remainingLength]; // 固定头部(1) + 剩余长度(1) + 剩余长度
        
        // 设置固定头部
        packet[0] = fixedHeader;
        packet[1] = (byte) remainingLength;
        
        // 设置可变头部
        packet[2] = 0x00; // 报文标识符 (MSB)
        packet[3] = 0x01; // 报文标识符 (LSB)
        
        // 设置载荷
        packet[4] = 0x00; // 主题过滤器长度 (MSB)
        packet[5] = (byte) topic.length(); // 主题过滤器长度 (LSB)
        
        // 复制主题
        System.arraycopy(topic.getBytes(), 0, packet, 6, topic.length());
        
        // 设置请求的QoS
        packet[6 + topic.length()] = (byte) qos;
        
        return packet;
    }
    
    public IMqttToken unsubscribe(String topic) throws MqttException {
        // 简化实现，不执行实际操作
        Log.w(TAG, "unsubscribe方法未实现");
        return null;
    }
    
    public IMqttToken publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(retained);
        return publish(topic, message);
    }
    
    public IMqttToken publish(String topic, MqttMessage message) throws MqttException {
        executorService.submit(() -> {
            try {
                if (connected) {
                    // 创建PUBLISH包
                    byte[] publishPacket = createMqttPublishPacket(topic, message);
                    outputStream.write(publishPacket);
                    outputStream.flush();
                    
                    Log.d(TAG, "发送MQTT消息: " + topic);
                } else {
                    throw new IOException("MQTT客户端未连接");
                }
            } catch (Exception e) {
                Log.e(TAG, "发布MQTT消息失败: " + e.getMessage(), e);
            }
        });
        
        return null;
    }
    
    private byte[] createMqttPublishPacket(String topic, MqttMessage message) {
        // 固定头部：PUBLISH = 3 << 4 | retained << 0 | qos << 1 | dup << 3
        byte fixedHeader = (byte) (3 << 4);
        if (message.isRetained()) {
            fixedHeader |= 1;
        }
        fixedHeader |= (message.getQos() << 1);
        
        byte[] payload = message.getPayload();
        
        // 计算剩余长度
        int variableHeaderLength = 2 + topic.length(); // 主题长度(2) + 主题
        int remainingLength = variableHeaderLength + payload.length;
        
        // 创建数据包
        byte[] packet = new byte[1 + 1 + remainingLength]; // 固定头部(1) + 剩余长度(1) + 剩余长度
        
        // 设置固定头部
        packet[0] = fixedHeader;
        packet[1] = (byte) remainingLength;
        
        // 设置可变头部
        packet[2] = 0x00; // 主题长度 (MSB)
        packet[3] = (byte) topic.length(); // 主题长度 (LSB)
        
        // 复制主题
        System.arraycopy(topic.getBytes(), 0, packet, 4, topic.length());
        
        // 复制载荷
        System.arraycopy(payload, 0, packet, 4 + topic.length(), payload.length);
        
        return packet;
    }
    
    public boolean isConnected() {
        synchronized (connectionLock) {
            return connected && socket != null && socket.isConnected() && !socket.isClosed();
        }
    }
    
    /**
     * 获取Context实例
     */
    public Context getContext() {
        return context;
    }
    
    public void setBufferOpts(DisconnectedBufferOptions options) {
        Log.w(TAG, "setBufferOpts方法未实现");
    }
} 