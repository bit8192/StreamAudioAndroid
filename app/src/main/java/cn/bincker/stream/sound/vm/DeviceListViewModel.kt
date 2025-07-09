package cn.bincker.stream.sound.vm

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bincker.stream.sound.AudioService
import cn.bincker.stream.sound.entity.AudioServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceListViewModel: ViewModel() {
    val isRefresh = mutableStateOf(false)
    val serverList = mutableStateListOf<AudioServerInfo>()

    fun refresh(nullableBinder: AudioService.AudioServiceBinder?) {
        viewModelScope.launch {
            nullableBinder?.let { binder->
                isRefresh.value = true
                try {
                    withContext(context = Dispatchers.IO) {
                        binder.startScan { server->
                            if (serverList.none { it.address == server.address }){
                                serverList.add(server)
                            }
                        }
                        delay(5000)
                        binder.stopScan()
                    }
                }finally {
                    isRefresh.value = false
                }
            }
        }
    }
}