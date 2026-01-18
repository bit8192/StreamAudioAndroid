package cn.bincker.stream.sound

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.bincker.stream.sound.ui.components.DeviceCard
import cn.bincker.stream.sound.vm.ConnectionState
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.hilt.navigation.compose.hiltViewModel
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.ui.theme.StreamAudioTheme
import cn.bincker.stream.sound.utils.generateEd25519AsBase64
import cn.bincker.stream.sound.utils.loadPrivateEd25519
import cn.bincker.stream.sound.vm.DeviceListViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var audioServiceBinder: AudioService.AudioServiceBinder? = null
    private val vm by viewModels<DeviceListViewModel>()
    private lateinit var barcodeLauncher : ActivityResultLauncher<ScanOptions>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            StreamAudioTheme {
                Page(vm, barcodeLauncher)
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
                // 将Service Binder传递给ViewModel
                vm.setServiceBinder(audioServiceBinder)
                // 刷新设备列表
                vm.refresh(this@MainActivity)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                audioServiceBinder = null
                // 清除ViewModel中的Service Binder
                vm.setServiceBinder(null)
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

        // 启动Service作为前台服务
        startService(Intent(this, AudioService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesList(modifier: Modifier = Modifier, vm: DeviceListViewModel){
    val pullToRefreshState = rememberPullToRefreshState()
    val context = LocalContext.current
    val refresh by vm.isRefresh.collectAsState()
    val connectionStates by vm.connectionStates.collectAsState()
    val playingStates by vm.playingStates.collectAsState()
    val errorMessages by vm.errorMessages.collectAsState()
    Log.d(TAG, "DevicesList: deviceList=${vm.deviceList}")
    val deviceList by vm.deviceList.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(refresh, onRefresh = {
            vm.refresh(context)
        }, state = pullToRefreshState) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                items(deviceList, { it.config.address }) { device ->
                    val deviceId = device.config.address
                    val connectionState = connectionStates[deviceId] ?: ConnectionState.DISCONNECTED
                    val isPlaying = playingStates[deviceId] ?: false
                    val errorMessage = errorMessages[deviceId]

                    DeviceCard(
                        device = device,
                        connectionState = connectionState,
                        isPlaying = isPlaying,
                        errorMessage = errorMessage,
                        onCardClick = {
                            when (connectionState) {
                                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                                    vm.connectDevice(device)
                                }
                                ConnectionState.CONNECTED -> {
                                    vm.disconnectDevice(deviceId)
                                }
                                ConnectionState.CONNECTING -> {
                                    // 连接中，不做任何操作
                                }
                            }
                        },
                        onPlayStopClick = {
                            vm.togglePlayback(deviceId)
                        },
                        onErrorDismiss = {
                            vm.clearError(deviceId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Page(vm: DeviceListViewModel, barcodeLauncher: ActivityResultLauncher<ScanOptions>? = null) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessages by vm.errorMessages.collectAsState()

    // 显示错误消息
    LaunchedEffect(errorMessages) {
        errorMessages.values.firstOrNull { it != null }?.let { error ->
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(255, 200, 200)),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        DevicesList(modifier = Modifier.padding(innerPadding), vm = vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun PreviewMainApp(){
    val vm = hiltViewModel<DeviceListViewModel>()
    vm.addTestDevices()
    // Preview 中也展示顶部栏
    Page(vm = vm)
}
