# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

StreamAudioAndroid（StreamAudioAndroid）是一个通过网络使用 UDP 通信进行音频流传输的 Android 应用。该应用通过二维码实现设备配对，使用 Ed25519 进行身份认证，使用 X25519 进行密钥交换加密。

## 构建命令

```bash
# 构建项目
./gradlew build

# 运行单元测试
./gradlew test

# 在连接的设备/模拟器上运行插桩测试
./gradlew connectedAndroidTest

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 安装 Debug 版本到连接的设备
./gradlew installDebug

# 运行特定测试类
./gradlew test --tests cn.bincker.stream.sound.ExampleUnitTest

# 清理构建产物
./gradlew clean
```

## 架构设计

### 核心组件

**Application (Application.kt)**
- 自定义 Application 类，初始化 BouncyCastle 安全提供者
- 管理从 YAML 加载的应用级配置（AppConfig）
- 生成并持有用于 ECDH 密钥交换的 X25519 密钥对
- 加载用于设备认证的 Ed25519 私钥

**AudioService (AudioService.kt)**
- 用于音频流传输和网络通信的前台服务
- 使用 DatagramChannel（UDP）在 8888 端口进行设备通信
- 实现通过广播进行设备发现、配对协议和音频流传输
- 通过服务绑定暴露设备配对和扫描方法
- 数据包类型定义为字节常量（PING/PONG、ECDH、PAIR、AUDIO、ENCRYPTED、SIGN）

**MainActivity (MainActivity.kt)**
- 使用 Jetpack Compose UI 的单一 Activity
- 绑定到 AudioService 并将其作为前台服务启动
- 集成 ZXing 条码扫描器进行二维码设备配对
- 使用 DeviceListViewModel 显示带下拉刷新功能的设备列表

### 配置系统

**AppConfig 和 DeviceConfig**
- 配置以 YAML 格式存储在应用内部存储的 `app_config.yaml` 文件中
- AppConfig 包含：端口号、Ed25519 私钥（base64 编码）、设备列表
- DeviceConfig 包含：设备名称、地址（host:port 格式）、Ed25519 公钥（base64 编码）
- AppConfigUtils.kt 中的工具函数使用 SnakeYAML 处理加载/保存

### 加密机制

**安全模块 (SecurityUtils.kt)**
- 使用 BouncyCastle 提供者实现 Ed25519（签名/认证）和 X25519（密钥交换）
- Ed25519：通过公钥/私钥对进行设备身份识别和认证
- X25519：每次应用启动时生成临时密钥对用于加密通信
- 密钥以 base64 编码字符串形式存储/传输

### 数据流程

1. 应用启动 → Application 初始化加密并加载配置
2. MainActivity 启动 → 绑定到 AudioService → 服务以前台模式启动
3. UI 显示来自配置的设备列表（DeviceListViewModel）
4. 用户扫描二维码 → 解析 "streamaudio://host:port" URI → AudioService.pairDevice()
5. AudioService 处理 UDP 通信（设备发现、配对、音频流传输）

### 网络协议

- 基于 UDP 的通信，使用 8888 端口
- 使用广播地址 255.255.255.255 进行设备发现
- 数据包大小：1200 字节（PACKAGE_SIZE）
- 协议使用单字节数据包类型头部标识
- 设备地址解析为 "host:port" 格式（DeviceInfo 中默认端口为 12345）

## 技术栈

- Kotlin
- Jetpack Compose（Material3、UI 工具包）
- Jetpack Lifecycle 和 ViewModel
- BouncyCastle（bcprov-jdk15to18、bcpkix-jdk15to18）用于加密
- SnakeYAML 用于配置持久化
- ZXing（zxing-android-embedded）用于二维码扫描
- Java NIO channels 用于网络 I/O
- Coroutines 用于异步操作

## 关键依赖版本

- compileSdk: 36
- minSdk: 35
- targetSdk: 35
- Kotlin: 2.0.21
- Android Gradle Plugin: 8.13.1
- Compose BOM: 2025.12.00

## 所需权限

- INTERNET
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK
- ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE
- CHANGE_WIFI_MULTICAST_STATE
- CAMERA（用于二维码扫描）

## 开发注意事项

- 包名：`cn.bincker.stream.sound`
- 启用 BuildConfig 以使用 DEBUG 标志
- 使用 Java 11 兼容性（sourceCompatibility/targetCompatibility）
- 网络接口过滤条件：活跃、非回环、非虚拟接口
- "wlan" 接口专门配置用于 IP 组播
