package cn.bincker.stream.sound.vm

import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.Application
import cn.bincker.stream.sound.BuildConfig
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.DeviceInfo
import cn.bincker.stream.sound.utils.generatePrivateKey
import cn.bincker.stream.sound.utils.loadPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAG = "DeviceListViewModel"
class DeviceListViewModel: ViewModel() {
    val isRefresh = mutableStateOf(false)
    val deviceList = mutableStateListOf<DeviceInfo>()

    fun refresh(context: Context) {
        @Suppress("SimplifyNegatedBinaryExpression")
        if (!(context.applicationContext is Application)) return
        viewModelScope.launch {
            isRefresh.value = true

            try {
                deviceList.clear()
                val app = context.applicationContext as Application
                if (app.getAppConfig().devices.size == deviceList.size) app.refreshAppConfig()
                for (deviceConfig in app.getAppConfig().devices) {
                    try {
                        deviceList.add(DeviceInfo(deviceConfig))
                    }catch (e: Exception){
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "import device [" + deviceConfig.name + "] fail: " + e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                if (BuildConfig.DEBUG){
                    if (deviceList.isEmpty()){
                        deviceList.add(DeviceInfo(DeviceConfig().also {
                            it.name = "test"
                            it.address = "192.168.2.31:8888"
                            it.publicKey = Base64.encodeToString(loadPrivateKey(generatePrivateKey()).generatePublicKey().encoded, Base64.DEFAULT)
                        }))
                    }
                }
                Log.d(TAG, "devices size: ${deviceList.size}")
            }finally {
                isRefresh.value = false
            }
        }
    }
}