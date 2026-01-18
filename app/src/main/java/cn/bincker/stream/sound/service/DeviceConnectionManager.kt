package cn.bincker.stream.sound.service

import android.util.Log
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.repository.AppConfigRepository
import cn.bincker.stream.sound.vm.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备连接管理器
 * 负责管理所有设备的连接状态、播放状态和错误信息
 * 由AudioService持有，独立于UI生命周期
 */
@Singleton
class DeviceConnectionManager @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) {
    companion object {
        private const val TAG = "DeviceConnectionManager"
    }

    // 设备列表
    private val _deviceList = MutableStateFlow<List<Device>>(emptyList())
    val deviceList: StateFlow<List<Device>> = _deviceList.asStateFlow()

    // 连接状态管理
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    // 播放状态管理
    private val _playingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playingStates: StateFlow<Map<String, Boolean>> = _playingStates.asStateFlow()

    // 错误消息管理
    private val _errorMessages = MutableStateFlow<Map<String, String?>>(emptyMap())
    val errorMessages: StateFlow<Map<String, String?>> = _errorMessages.asStateFlow()

    // 活跃的设备连接
    private val activeDevices = mutableMapOf<String, Device>()

    // 连接任务管理
    private val connectionJobs = mutableMapOf<String, Job>()

    // 监听任务管理
    private val listeningJobs = mutableMapOf<String, Job>()

    // 线程安全锁
    private val mutex = Mutex()

    val publicKey get() = appConfigRepository.publicKey

    val privateKey get() = appConfigRepository.privateKey

    val ecdhKeyPair get() = appConfigRepository.ecdhKeyPair

    init {
        Log.d(TAG, "deviceList: $_deviceList")
    }
    /**
     * 初始化设备列表
     * 从AppConfigRepository的deviceConfigList中加载
     */
    suspend fun initializeDeviceList() {
        mutex.withLock {
            val deviceConfigs = appConfigRepository.deviceConfigList
            val newDeviceList = deviceConfigs.map { config ->
                Device(this, config)
            }
            _deviceList.value = newDeviceList
            Log.d(TAG, "Initialized device list with ${newDeviceList.size} devices")
        }
    }

    /**
     * 添加设备
     */
    suspend fun addDevice(device: Device, job: Job) {
        mutex.withLock {
            if (_deviceList.value.none { it.config.address == device.config.address }) {
                // 更新设备列表
                _deviceList.value += device
                // 同时添加到配置中
                appConfigRepository.addDeviceConfig(device.config)
                appConfigRepository.saveAppConfig()
                Log.d(TAG, "Added device: ${device.config.name} (${device.config.address})")
                listeningJobs[device.config.address] = job
                ecdh(device)
                activeDevices[getDeviceId(device)] = device
            }
        }
    }

    /**
     * 刷新设备列表
     * 重新从配置文件加载设备
     */
    suspend fun refreshDeviceList() {
        appConfigRepository.refresh()
        initializeDeviceList()
    }

    fun getDeviceId(device: Device) = device.config.address

    /**
     * 连接设备
     */
    suspend fun connectDevice(device: Device, scope: CoroutineScope) {
        val deviceId = getDeviceId(device)

        mutex.withLock {
            // 如果已经在连接中，则不重复连接
            if (_connectionStates.value[deviceId] == ConnectionState.CONNECTING) {
                Log.d(TAG, "Device $deviceId is already connecting")
                return
            }

            // 取消之前的连接任务
            connectionJobs[deviceId]?.cancel()
        }

        // 更新状态为连接中
        updateConnectionState(deviceId, ConnectionState.CONNECTING)
        clearError(deviceId)

        // 启动连接任务
        val job = scope.launch(Dispatchers.IO) {
            try {
                // 连接设备
                device.connect()
                Log.d(TAG, "Connected to device: $deviceId")

                // 保存活跃设备
                mutex.withLock {
                    activeDevices[deviceId] = device
                }

                // 启动消息监听
                val existingListeningJob = listeningJobs[deviceId]
                existingListeningJob?.cancel()

                val newListeningJob = device.startListening(scope)
                mutex.withLock {
                    listeningJobs[deviceId] = newListeningJob
                }

                // 执行ECDH密钥交换
                if (device.publicKey != null) {
                    ecdh(device)
                } else {
                    // 设备未配对
                    updateConnectionState(deviceId, ConnectionState.ERROR)
                    updateErrorMessage(deviceId, "设备未配对，请先扫描二维码配对")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to device: $deviceId", e)
                updateConnectionState(deviceId, ConnectionState.ERROR)
                updateErrorMessage(deviceId, "连接失败: ${e.message ?: "未知错误"}")

                // 清理失败的连接
                mutex.withLock {
                    activeDevices.remove(deviceId)
                }
            }
        }

        mutex.withLock {
            connectionJobs[deviceId] = job
        }
    }

    private suspend fun ecdh(
        device: Device
    ) {
        val deviceId = getDeviceId(device)
        try {
            device.ecdh()
            Log.d(TAG, "ECDH completed for device: $deviceId")
            updateConnectionState(deviceId, ConnectionState.CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "ECDH failed for device: $deviceId", e)
            updateConnectionState(deviceId, ConnectionState.ERROR)
            updateErrorMessage(deviceId, "密钥交换失败: ${e.message}")
        }
    }

    /**
     * 断开设备连接
     */
    suspend fun disconnectDevice(deviceId: String) {
        mutex.withLock {
            // 取消连接和监听任务
            connectionJobs[deviceId]?.cancel()
            listeningJobs[deviceId]?.cancel()

            // 获取设备
            val device = activeDevices[deviceId]

            // 断开设备
            device?.let {
                try {
                    it.disconnect()
                    Log.d(TAG, "Disconnected from device: $deviceId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disconnect device: $deviceId", e)
                }
            }

            // 移除活跃设备
            activeDevices.remove(deviceId)
        }

        // 更新状态
        updateConnectionState(deviceId, ConnectionState.DISCONNECTED)
        updatePlayingState(deviceId, false)
    }

    /**
     * 切换播放状态
     */
    suspend fun togglePlayback(deviceId: String, scope: CoroutineScope) {
        val device = mutex.withLock { activeDevices[deviceId] }

        if (device == null) {
            Log.w(TAG, "Device not found: $deviceId")
            updateErrorMessage(deviceId, "设备未连接")
            return
        }

        val isPlaying = _playingStates.value[deviceId] ?: false

        // 检查是否已连接
        if (_connectionStates.value[deviceId] != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot toggle playback, device not connected: $deviceId")
            updateErrorMessage(deviceId, "请先连接设备")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (isPlaying) {
                    // 停止播放
                    device.stop()
                    updatePlayingState(deviceId, false)
                    Log.d(TAG, "Stopped playback for device: $deviceId")
                } else {
                    // 开始播放 - 使用默认端口9999
                    device.play(9999)
                    updatePlayingState(deviceId, true)
                    Log.d(TAG, "Started playback for device: $deviceId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle playback for device: $deviceId", e)
                updateErrorMessage(deviceId, "操作失败: ${e.message}")
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError(deviceAddress: String) {
        _errorMessages.value = _errorMessages.value.toMutableMap().apply {
            remove(deviceAddress)
        }
    }

    /**
     * 更新连接状态
     */
    private fun updateConnectionState(deviceAddress: String, state: ConnectionState) {
        _connectionStates.value = _connectionStates.value.toMutableMap().apply {
            this[deviceAddress] = state
        }
    }

    /**
     * 更新播放状态
     */
    private fun updatePlayingState(deviceAddress: String, isPlaying: Boolean) {
        _playingStates.value = _playingStates.value.toMutableMap().apply {
            this[deviceAddress] = isPlaying
        }
    }

    /**
     * 更新错误消息
     */
    private fun updateErrorMessage(deviceAddress: String, message: String?) {
        _errorMessages.value = _errorMessages.value.toMutableMap().apply {
            this[deviceAddress] = message
        }
    }

    /**
     * 清理所有资源
     */
    suspend fun cleanup() {
        mutex.withLock {
            // 取消所有任务
            connectionJobs.values.forEach { it.cancel() }
            listeningJobs.values.forEach { it.cancel() }

            // 断开所有设备
            activeDevices.values.forEach { device ->
                try {
                    device.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disconnect device during cleanup", e)
                }
            }

            // 清空集合
            connectionJobs.clear()
            listeningJobs.clear()
            activeDevices.clear()
        }

        // 重置状态
        _connectionStates.value = emptyMap()
        _playingStates.value = emptyMap()
        _errorMessages.value = emptyMap()
    }
}