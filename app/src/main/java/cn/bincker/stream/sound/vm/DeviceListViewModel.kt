package cn.bincker.stream.sound.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.Application
import cn.bincker.stream.sound.AudioService
import cn.bincker.stream.sound.config.AppConfig
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.AudioInfo
import cn.bincker.stream.sound.entity.PlaybackStats
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.repository.AppConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

const val TAG = "DeviceListViewModel"

/**
 * 简化的ViewModel，只负责UI状态管理
 * 实际的连接管理由AudioService处理
 */
@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {

    // Service绑定
    private var serviceBinder: AudioService.AudioServiceBinder? = null

    // Service状态流收集Jobs
    private var serviceFlowJobs = mutableListOf<kotlinx.coroutines.Job>()

    // 刷新状态
    private val _isRefresh = MutableStateFlow(false)
    val isRefresh: StateFlow<Boolean> get() = _isRefresh.asStateFlow()

    // 持久化的状态流（避免重新创建）
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val _playingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playingStates: StateFlow<Map<String, Boolean>> = _playingStates.asStateFlow()

    private val _errorMessages = MutableStateFlow<Map<String, String?>>(emptyMap())
    val errorMessages: StateFlow<Map<String, String?>> = _errorMessages.asStateFlow()

    // 设备列表 StateFlow - 持久化的StateFlow
    private val _deviceList = MutableStateFlow<List<Device>>(emptyList())
    val deviceList: StateFlow<List<Device>> = _deviceList.asStateFlow()

    private val _audioInfos = MutableStateFlow<Map<String, AudioInfo>>(emptyMap())
    val audioInfos: StateFlow<Map<String, AudioInfo>> = _audioInfos.asStateFlow()

    private val _playbackStats = MutableStateFlow<Map<String, PlaybackStats>>(emptyMap())
    val playbackStats: StateFlow<Map<String, PlaybackStats>> = _playbackStats.asStateFlow()

    private val _appConfig = MutableStateFlow<AppConfig?>(null)
    val appConfig: StateFlow<AppConfig?> = _appConfig.asStateFlow()

    init {
        viewModelScope.launch {
            _appConfig.value = appConfigRepository.getAppConfigSnapshot()
        }
    }

    /**
     * 设置Service Binder
     */
    fun setServiceBinder(binder: AudioService.AudioServiceBinder?) {
        // 取消之前的收集任务
        serviceFlowJobs.forEach { it.cancel() }
        serviceFlowJobs.clear()

        serviceBinder = binder

        // 如果有新的binder，开始收集Service的状态流
        if (binder != null) {
            // 收集连接状态
            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getConnectionStates().collect { states ->
                        _connectionStates.value = states
                    }
                }
            )

            // 收集播放状态
            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getPlayingStates().collect { states ->
                        _playingStates.value = states
                    }
                }
            )

            // 收集错误消息
            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getErrorMessages().collect { messages ->
                        _errorMessages.value = messages
                    }
                }
            )

            // 收集设备列表
            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getDeviceList().collect { devices ->
                        _deviceList.value = devices
                        Log.d(TAG, "Device list updated from service: ${devices.size} devices")
                    }
                }
            )

            // 收集音频参数（服务端决定）
            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getAudioInfos().collect { infos ->
                        _audioInfos.value = infos
                    }
                }
            )

            serviceFlowJobs.add(
                viewModelScope.launch {
                    binder.getPlaybackStats().collect { stats ->
                        _playbackStats.value = stats
                    }
                }
            )
        }
    }

    /**
     * 刷新设备列表
     */
    fun refresh(context: Context) {
        @Suppress("SimplifyNegatedBinaryExpression")
        if (!(context.applicationContext is Application)) return

        viewModelScope.launch {
            _isRefresh.value = true
            runCatching {
                // 委托给Service刷新设备列表
                serviceBinder?.refreshDeviceList()
                    ?: Log.w(TAG, "Cannot refresh device list: Service not bound")
                Log.d(TAG, "devices size: ${_deviceList.value.size}")
            }
            _isRefresh.value = false
        }
    }

    /**
     * 连接设备 - 委托给Service处理
     */
    fun connectDevice(device: Device) {
        serviceBinder?.connectDevice(device)
            ?: Log.w(TAG, "Cannot connect device: Service not bound")
    }

    /**
     * 断开设备连接 - 委托给Service处理
     */
    fun disconnectDevice(deviceId: String) {
        serviceBinder?.disconnectDevice(deviceId)
            ?: Log.w(TAG, "Cannot disconnect device: Service not bound")
    }

    /**
     * 切换播放状态 - 委托给Service处理
     */
    fun togglePlayback(deviceId: String) {
        serviceBinder?.togglePlayback(deviceId)
            ?: Log.w(TAG, "Cannot toggle playback: Service not bound")
    }

    /**
     * 清除错误消息 - 委托给Service处理
     */
    fun clearError(deviceAddress: String) {
        serviceBinder?.clearError(deviceAddress)
            ?: Log.w(TAG, "Cannot clear error: Service not bound")
    }

    /**
     * 添加测试设备（仅用于调试）
     * 注意：此方法仅用于UI预览，实际设备应通过Service添加
     */
    fun addTestDevices() {
        // 仅用于预览，实际运行时设备通过Service管理
        Log.w(TAG, "addTestDevices called - this should only be used in Preview mode")
    }

    fun refreshAppConfig() {
        viewModelScope.launch {
            _appConfig.value = appConfigRepository.getAppConfigSnapshot()
        }
    }

    fun saveDeviceConfig(originalAddress: String, newConfig: DeviceConfig) {
        viewModelScope.launch {
            val addressChanged = originalAddress != newConfig.address
            if (addressChanged) {
                val state = _connectionStates.value[originalAddress]
                if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
                    serviceBinder?.disconnectDevice(originalAddress)
                }
            }
            appConfigRepository.updateDeviceConfig(originalAddress, newConfig)
            _appConfig.value = appConfigRepository.getAppConfigSnapshot()
            serviceBinder?.refreshDeviceList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 取消所有的收集任务
        serviceFlowJobs.forEach { it.cancel() }
        serviceFlowJobs.clear()
    }
}
