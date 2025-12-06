package cn.bincker.stream.sound

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat.requestPermissions
import cn.bincker.stream.sound.entity.AudioServerInfo
import cn.bincker.stream.sound.ui.theme.StreamSoundTheme
import cn.bincker.stream.sound.vm.DeviceListViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.InetSocketAddress

private const val TAG = "MainActivity"
class MainActivity : ComponentActivity() {
    private var audioServiceBinder: AudioService.AudioServiceBinder? = null
    private val vm by viewModels<DeviceListViewModel>()
    private lateinit var barcodeLauncher : ActivityResultLauncher<ScanOptions>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            StreamSoundTheme {
                Page(vm, audioServiceBinder, barcodeLauncher)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if(checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK), 0)
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.ACCESS_NETWORK_STATE), 0)
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.ACCESS_WIFI_STATE), 0)
        }
        if(checkSelfPermission(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE), 0)
        }

        bindService(Intent(this, AudioService::class.java), object: ServiceConnection{
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                audioServiceBinder = service as AudioService.AudioServiceBinder?
                if (audioServiceBinder?.isPlay() == false) {
                    startForegroundService(
                        Intent(this@MainActivity, AudioService::class.java).apply {
                            putExtra("cmd", 0)
                        }
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                audioServiceBinder = null
            }
        }, BIND_AUTO_CREATE)

        barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
            if(result.contents != null){
                Log.d("MainActivity", "Scanned code: ${result.contents}")
                // 处理扫描结果
                audioServiceBinder?.pairDevice(result.contents)
                Toast.makeText(this, "Scanned: ${result.contents}", Toast.LENGTH_LONG).show()
            } else {
                Log.d("MainActivity", "No code scanned")
            }
        }
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
            LazyColumn(modifier = Modifier
                .fillMaxSize()
                .padding(10.dp, 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(vm.serverList.toList(), {it.address}) {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }) {
                        Text(it.name, fontSize = 36.sp)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Page(vm: DeviceListViewModel, audioServiceBinder: AudioService.AudioServiceBinder? = null, barcodeLauncher: ActivityResultLauncher<ScanOptions>? = null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val context = LocalContext.current
                    val activity = LocalActivity.current
                    IconButton(onClick = {
                        if(context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
                            if(activity != null) requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 0)
                        }
                        if(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED){
                            if(activity != null) requestPermissions(activity, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 0)
                        }
                        Log.d(TAG, "Page: scan btn, barcodeLauncher=$barcodeLauncher")
                        barcodeLauncher?.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("请将二维码置于取景框内扫描")
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                        })
                    }) {
                        Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = "扫码")
                    }
                }
            )
        }
    ) { innerPadding ->
        DevicesList(modifier = Modifier.padding(innerPadding), vm = vm) { audioServiceBinder }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun PreviewMainApp(){
    val vm = DeviceListViewModel()
    for (i in 0 until 10) {
        vm.serverList.add(AudioServerInfo("Device" + (i + 1), InetSocketAddress("192.168.1." + (i + 1), 0)))
    }
    // Preview 中也展示顶部栏
    Page(vm = vm)
}
