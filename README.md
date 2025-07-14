# 智能家居照明系统设计文档

## 1. 系统概述

### 1.1 系统简介

智能家居照明系统是一款基于Android平台的移动应用，通过MQTT协议与智能照明设备进行通信，实现对家庭照明的智能控制。系统支持实时监控环境数据（温度、湿度、光照强度等），并根据这些数据自动或手动控制照明设备。

### 1.2 系统目标

- 提供直观的用户界面，方便用户监控家庭环境数据
- 实现照明设备的自动化控制，根据环境参数和人体存在情况智能调节
- 支持手动控制模式，允许用户根据个人偏好调节照明
- 确保系统在后台运行时仍能保持与设备的连接
- 提供稳定可靠的数据传输和设备控制

## 2. 系统架构

### 2.1 整体架构

系统采用客户端-服务器架构，其中：

- **客户端**：Android移动应用
- **服务器**：MQTT代理服务器
- **智能设备**：照明控制器、各类传感器（温度、湿度、光照、人体感应等）

```
+---------------+      MQTT      +----------------+      控制信号     +----------------+
|  Android应用  | <------------> |  MQTT代理服务器 | <-------------> |  智能照明设备   |
+---------------+                +----------------+                 +----------------+
                                                                          ^
                                                                          |
                                                                          v
                                                                   +----------------+
                                                                   |  环境传感器     |
                                                                   +----------------+
```

### 2.2 应用架构

应用采用MVVM（Model-View-ViewModel）架构模式，结合Android Jetpack组件：

- **View层**：Activity、Fragment、XML布局文件
- **ViewModel层**：管理UI相关的数据，处理用户交互
- **Model层**：数据源和业务逻辑
- **服务层**：MQTT通信服务，保证后台连接

## 3. 核心功能模块

### 3.1 MQTT通信模块

#### 3.1.1 MqttClientManager

负责管理与MQTT服务器的连接、消息发布和订阅。主要功能包括：

- 建立与MQTT服务器的连接
- 订阅相关主题
- 发布控制命令
- 处理接收到的消息
- 管理连接状态和重连机制

#### 3.1.2 MqttBackgroundService

前台服务，确保应用在后台运行时仍能保持MQTT连接。主要功能包括：

- 维持MQTT连接
- 定期检查连接状态
- 在网络变化或设备唤醒时重新连接
- 定期请求最新数据

### 3.2 用户界面模块

#### 3.2.1 主页面（HomeFragment）

显示当前环境数据和设备状态，包括：

- 温度和湿度显示
- 光照强度显示
- 人体存在状态
- 距离传感器数据
- 当前工作模式（自动/手动）
- 设备控制按钮

#### 3.2.2 控制面板（DashboardFragment）

提供详细的设备控制选项，允许用户：

- 切换自动/手动模式
- 调节灯光亮度
- 设置自动控制参数
- 查看历史数据

#### 3.2.3 通知页面（NotificationsFragment）

显示系统通知和警报信息，如：

- 异常温度警报
- 设备连接状态变化
- 系统更新通知

### 3.3 数据管理模块

#### 3.3.1 HomeViewModel

管理主页面的数据，包括：

- 环境数据（温度、湿度、光照等）
- 设备状态
- 连接状态
- 处理MQTT消息并更新UI

#### 3.3.2 数据处理

负责解析和处理从MQTT服务器接收的JSON数据，更新应用状态。

## 4. 通信协议

### 4.1 MQTT协议

系统使用MQTT（Message Queuing Telemetry Transport）协议进行通信，这是一种轻量级的发布/订阅消息传输协议，特别适合IoT设备通信。

#### 4.1.1 主题设计

- `alarm`：接收传感器数据和状态信息
- `request`：发送数据请求和控制命令

#### 4.1.2 消息格式

系统使用JSON格式进行数据交换：

```json
{
  "temp": "25.5",     // 温度
  "humi": "60.2",     // 湿度
  "human": 1,         // 人体存在（1=存在，0=不存在）
  "dist": "150.0",    // 距离
  "mode": 1,          // 模式（1=自动，0=手动）
  "lux": "500.0"      // 光照强度
}
```

控制命令格式：

```json
{
  "action": "setMode",
  "value": 1
}
```

## 5. 技术实现

### 5.1 开发环境

- **开发语言**：Kotlin
- **Android SDK版本**：
  - 最低SDK：26（Android 8.0）
  - 目标SDK：34（Android 14）
- **构建工具**：Gradle 8.0+

### 5.2 主要依赖库

- **MQTT客户端**：
  - org.eclipse.paho.client.mqttv3:1.2.5
  - org.eclipse.paho.android.service:1.1.1
- **Android Jetpack组件**：
  - androidx.navigation（导航组件）
  - androidx.lifecycle（生命周期组件）
  - androidx.core.ktx（Kotlin扩展）
- **其他依赖**：
  - org.bouncycastle:bcpkix-jdk15on:1.70（安全加密）
  - kotlinx-coroutines（协程支持）

### 5.3 关键实现技术

#### 5.3.1 前台服务

使用Android前台服务确保应用在后台运行时仍能保持MQTT连接，并适配不同Android版本的前台服务API。

#### 5.3.2 网络状态监听

通过ConnectivityManager监听网络状态变化，在网络恢复时自动重新连接MQTT服务器。

#### 5.3.3 锁屏监听

监听设备锁屏和解锁事件，在设备唤醒时检查并恢复MQTT连接。

#### 5.3.4 LiveData数据绑定

使用LiveData实现数据与UI的双向绑定，确保UI实时反映最新数据。

## 6. 系统流程

### 6.1 启动流程

1. 应用启动，初始化SmartHomeLightingApplication
2. 初始化MqttClientManager并尝试连接MQTT服务器
3. 启动MqttBackgroundService前台服务
4. 注册网络和锁屏监听器
5. 加载MainActivity和主页面Fragment

### 6.2 数据更新流程

1. 通过MQTT接收传感器数据
2. MqttClientManager处理接收到的消息
3. 将解析后的数据传递给相应的ViewModel
4. ViewModel更新LiveData
5. UI通过观察LiveData自动更新

### 6.3 控制命令流程

1. 用户在UI上触发控制操作
2. ViewModel处理用户操作
3. 调用MqttClientManager发布控制命令
4. 智能设备接收并执行命令
5. 设备状态变化后通过MQTT反馈给应用

## 7. 安全性设计

### 7.1 通信安全

- 支持MQTT over TLS/SSL加密通信
- 使用BouncyCastle加密库进行安全验证

### 7.2 权限管理

应用请求以下关键权限：

- INTERNET：网络通信
- ACCESS_NETWORK_STATE：监控网络状态
- WAKE_LOCK：防止设备休眠时断开连接
- FOREGROUND_SERVICE：运行前台服务
- POST_NOTIFICATIONS：发送通知
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS：请求忽略电池优化
- RECEIVE_BOOT_COMPLETED：开机自启动

## 8. 可靠性设计

### 8.1 连接恢复机制

- 定期检查MQTT连接状态（每30秒）
- 网络变化时自动重连
- 设备唤醒时检查并恢复连接
- 连接失败时实现指数退避重试

### 8.2 错误处理

- 全局异常捕获
- 关键操作的try-catch保护
- 日志记录系统，便于问题诊断
- 降级处理机制，确保核心功能可用

## 9. 性能优化

### 9.1 电池优化

- 使用前台服务而非持续唤醒锁
- 合理设置MQTT心跳间隔
- 优化数据请求频率

### 9.2 内存优化

- 避免内存泄漏
- 合理管理资源
- 使用适当的数据结构

## 10. 开发计划

### 10.1 开发阶段

1. **需求分析与设计**（1周）
   - 确定功能需求
   - 系统架构设计
   - UI/UX设计

2. **核心功能开发**（2周）
   - MQTT通信模块
   - 基础UI框架
   - 数据模型实现

3. **功能完善**（2周）
   - 完整UI实现
   - 自动控制逻辑
   - 用户设置功能

4. **测试与优化**（1周）
   - 功能测试
   - 性能优化
   - 兼容性测试

5. **发布准备**（1周）
   - 文档完善
   - 最终测试
   - 打包发布

### 10.2 后续计划

- 添加用户账户系统
- 实现云端数据存储
- 开发更多智能场景
- 支持更多智能设备接入

## 11. 总结

智能家居照明系统是一款基于Android平台的移动应用，通过MQTT协议实现与智能照明设备的通信和控制。系统采用MVVM架构，结合Android Jetpack组件，实现了稳定可靠的设备连接和直观友好的用户界面。

系统的核心优势在于：

- 稳定的MQTT通信实现
- 完善的后台运行机制
- 智能的自动控制逻辑
- 友好的用户交互界面

通过这些技术和设计，系统能够为用户提供便捷、智能的家居照明控制体验。

---

## 附录：项目结构

```
com.example.smarthomelighting/
├── MainActivity.kt                     # 主活动
├── SmartHomeLightingApplication.kt     # 应用类
├── services/
│   └── MqttBackgroundService.kt        # MQTT后台服务
├── ui/
│   ├── home/                           # 主页面
│   │   ├── HomeFragment.kt
│   │   └── HomeViewModel.kt
│   ├── dashboard/                      # 控制面板
│   │   ├── DashboardFragment.kt
│   │   └── DashboardViewModel.kt
│   ├── notifications/                  # 通知页面
│   │   ├── NotificationsFragment.kt
│   │   └── NotificationsViewModel.kt
│   ├── settings/                       # 设置页面
│   │   ├── SettingsFragment.kt
│   │   └── SettingsViewModel.kt
│   └── assistant/                      # 智能助手
│       ├── AssistantFragment.kt
│       └── AssistantViewModel.kt
└── utils/
    ├── MqttClientManager.kt            # MQTT客户端管理
    └── BootCompletedReceiver.kt        # 开机自启动接收器
```
