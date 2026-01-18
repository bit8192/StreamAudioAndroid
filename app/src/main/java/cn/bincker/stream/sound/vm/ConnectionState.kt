package cn.bincker.stream.sound.vm

/**
 * 设备连接状态枚举
 */
enum class ConnectionState {
    DISCONNECTED,   // 未连接
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    ERROR          // 错误
}