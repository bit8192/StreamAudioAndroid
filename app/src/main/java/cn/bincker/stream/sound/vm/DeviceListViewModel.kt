package cn.bincker.stream.sound.vm

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.Application
import cn.bincker.stream.sound.entity.DeviceInfo
import cn.bincker.stream.sound.repository.AppConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

const val TAG = "DeviceListViewModel"
@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {
    val isRefresh: MutableState<Boolean> = mutableStateOf(false)
    val deviceList = mutableStateListOf<DeviceInfo>()

    fun refresh(context: Context) {
        @Suppress("SimplifyNegatedBinaryExpression")
        if (!(context.applicationContext is Application)) return
        viewModelScope.launch {
            isRefresh.value = true

            try {
                deviceList.clear()
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
                isRefresh.value = false
            }
        }
    }
}