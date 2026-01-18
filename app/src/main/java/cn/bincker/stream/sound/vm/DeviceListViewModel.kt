package cn.bincker.stream.sound.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.Application
import cn.bincker.stream.sound.AudioService
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

    // 刷新状态
    private val _isRefresh = MutableStateFlow(false)
    val isRefresh: StateFlow<Boolean> get() = _isRefresh.asStateFlow()

    // 从Service获取的状态流（如果Service未绑定则使用默认值）
    val connectionStates: StateFlow<Map<String, ConnectionState>>
        get() = serviceBinder?.getConnectionStates() ?: MutableStateFlow(emptyMap())

    val playingStates: StateFlow<Map<String, Boolean>>
        get() = serviceBinder?.getPlayingStates() ?: MutableStateFlow(emptyMap())

    val errorMessages: StateFlow<Map<String, String?>>
        get() = serviceBinder?.getErrorMessages() ?: MutableStateFlow(emptyMap())

    // 设备列表
    val deviceList: List<Device>
        get() = serviceBinder?.getDeviceList() ?: emptyList()

    /**
     * 设置Service Binder
     */
    fun setServiceBinder(binder: AudioService.AudioServiceBinder?) {
        serviceBinder = binder
    }

    /**
     * 刷新设备列表
     */
    fun refresh(context: Context) {
        @Suppress("SimplifyNegatedBinaryExpression")
        if (!(context.applicationContext is Application)) return

        viewModelScope.launch {
            _isRefresh.value = true
            try {
                // 委托给Service刷新设备列表
                serviceBinder?.refreshDeviceList()
                    ?: Log.w(TAG, "Cannot refresh device list: Service not bound")
                Log.d(TAG, "devices size: ${deviceList.size}")
            } finally {
                _isRefresh.value = false
            }
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
}