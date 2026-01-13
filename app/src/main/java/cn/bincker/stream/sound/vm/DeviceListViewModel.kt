package cn.bincker.stream.sound.vm

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.Application
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.repository.AppConfigRepository
import cn.bincker.stream.sound.utils.generateEd25519AsBase64
import cn.bincker.stream.sound.utils.loadPrivateEd25519
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject

const val TAG = "DeviceListViewModel"
@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {
    private val _isRefresh = MutableStateFlow(false)
    val isRefresh: StateFlow<Boolean> get() = _isRefresh.asStateFlow()
    val deviceList: List<Device> get() = appConfigRepository.deviceList

    fun addDeviceConfig(device: Device) = appConfigRepository.addDevice(device)

    fun addTestDevices() {
        for (i in 0 until 10) {
            addDeviceConfig(Device(
                appConfigRepository,
                DeviceConfig().apply {
                    name = "设备$i"
                    address = "192.168.1.${100 + i}:12345"
                    publicKey = Base64.encodeToString(loadPrivateEd25519(generateEd25519AsBase64()).generatePublicKey().encoded, Base64.DEFAULT)
                }
            ))
        }
    }

    fun refresh(context: Context) {
        @Suppress("SimplifyNegatedBinaryExpression")
        if (!(context.applicationContext is Application)) return
        viewModelScope.launch {
            _isRefresh.value = true

            withContext(Dispatchers.IO) {
                val addr = InetAddress.getByName("192.168.10.236")
                Log.d(TAG, "refresh: addr=${addr.hostName}")
            }
            try {
                appConfigRepository.refresh()
//                val app = context.applicationContext as Application
//                if (app.getAppConfig().devices.size == deviceList.size) app.refreshAppConfig()
//                for (deviceConfig in app.getAppConfig().devices) {
//                    try {
//                        deviceList.add(DeviceInfo(deviceConfig))
//                    }catch (e: Exception){
//                        withContext(Dispatchers.Main) {
//                            Toast.makeText(context, "import device [" + deviceConfig.name + "] fail: " + e.message, Toast.LENGTH_LONG).show()
//                        }
//                    }
//                }
//
//                if (BuildConfig.DEBUG){
//                    if (deviceList.isEmpty()){
//                        deviceList.add(DeviceInfo(DeviceConfig().also {
//                            it.name = "test"
//                            it.address = "192.168.2.31:8888"
//                            it.publicKey = Base64.encodeToString(loadPrivateKey(generatePrivateKey()).generatePublicKey().encoded, Base64.DEFAULT)
//                        }))
//                    }
//                }
                Log.d(TAG, "devices size: ${deviceList.size}")
            }finally {
                _isRefresh.value = false
            }
        }
    }
}