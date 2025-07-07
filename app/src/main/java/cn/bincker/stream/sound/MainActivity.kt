package cn.bincker.stream.sound

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cn.bincker.stream.sound.ui.theme.StreamSoundTheme
import cn.bincker.stream.sound.vm.DeviceListViewModel

class MainActivity : ComponentActivity() {
    private var audioServiceBinder: AudioService.AudioServiceBinder? = null
    private val vm by viewModels<DeviceListViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamSoundTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    /*Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column {
                            Button({
                                startForegroundService(
                                    Intent(this@MainActivity, AudioService::class.java).apply {
                                        putExtra("cmd", 0)
                                    }
                                )
                            }){
                                Text("Start")
                            }
                            Button({
                                startForegroundService(Intent(this@MainActivity, AudioService::class.java).apply {
                                    putExtra("cmd", 1)
                                })
                            }, modifier = Modifier.padding(top = 10.dp)){
                                Text("Stop")
                            }
                        }
                    }*/
                    DevicesList(modifier = Modifier.padding(innerPadding), vm = vm) { audioServiceBinder }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if(checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK), 0)
        }

        bindService(Intent(this, AudioService::class.java), object: ServiceConnection{
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                audioServiceBinder = service as AudioService.AudioServiceBinder?
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                audioServiceBinder = null
            }
        }, BIND_AUTO_CREATE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesList(modifier: Modifier = Modifier, vm: DeviceListViewModel = DeviceListViewModel(), binderGetter: ()->AudioService.AudioServiceBinder?){
    val isRefresh by vm.isRefresh
    val pullToRefreshState = rememberPullToRefreshState()
    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(isRefresh, onRefresh = {
            vm.refresh(binderGetter.invoke())
        }, state = pullToRefreshState) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(vm.serverList.toList(), {it.id}) {
                    Text(it.name)
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewMainApp(){
    DevicesList() {null}
}
