package cn.bincker.stream.sound.ui.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.bincker.stream.sound.config.AudioEncryptionMethod
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.vm.ConnectionState
import cn.bincker.stream.sound.vm.DeviceListViewModel

@Composable
fun DeviceDetailPage(
    vm: DeviceListViewModel,
    deviceId: String,
    onDeviceIdUpdated: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceList by vm.deviceList.collectAsState()
    val connectionStates by vm.connectionStates.collectAsState()
    val playingStates by vm.playingStates.collectAsState()
    val audioInfos by vm.audioInfos.collectAsState()
    val playbackStats by vm.playbackStats.collectAsState()

    val device = remember(deviceList, deviceId) { deviceList.firstOrNull { it.config.address == deviceId } }
    val connectionState = connectionStates[deviceId] ?: ConnectionState.DISCONNECTED
    val isPlaying = playingStates[deviceId] ?: false
    val stats = playbackStats[deviceId]

    var initialized by rememberSaveable(deviceId) { mutableStateOf(false) }

    var name by rememberSaveable(deviceId) { mutableStateOf("") }
    var ip by rememberSaveable(deviceId) { mutableStateOf("") }
    var port by rememberSaveable(deviceId) { mutableStateOf("") }
    var publicKey by rememberSaveable(deviceId) { mutableStateOf("") }
    var autoConnect by rememberSaveable(deviceId) { mutableStateOf(true) }
    var audioEncryption by rememberSaveable(deviceId) { mutableStateOf(AudioEncryptionMethod.XOR_256) }

    LaunchedEffect(deviceId) {
        vm.refreshAppConfig()
    }

    LaunchedEffect(device) {
        if (device != null && !initialized) {
            initialized = true

            name = device.config.name
            val parts = device.config.address.split(":")
            ip = parts.getOrNull(0).orEmpty()
            port = parts.getOrNull(1) ?: "12345"
            publicKey = device.config.publicKey
            autoConnect = device.config.autoPlay
            audioEncryption = device.config.audioEncryption
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (device == null) {
            Text("未找到设备: $deviceId", color = MaterialTheme.colorScheme.error)
            return
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(name.ifBlank { "未命名设备" }, style = MaterialTheme.typography.titleLarge)
                Text(device.config.address, color = MaterialTheme.colorScheme.onSurfaceVariant)

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("连接: $connectionState")
                    Text(if (isPlaying) "播放中" else "未播放")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("UDP 端口")
                    Text(stats?.udpPort?.toString() ?: "-", style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("端到端延迟")
                    Text(stats?.endToEndLatencyMs?.let { "${it}ms" } ?: "-", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (connectionState) {
                ConnectionState.CONNECTED -> {
                    FilledTonalButton(
                        onClick = { vm.disconnectDevice(deviceId) },
                        modifier = Modifier.weight(1f),
                    ) { Text("断开连接") }
                }

                ConnectionState.CONNECTING -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) { Text("连接中...") }
                }

                else -> {
                    Button(
                        onClick = { vm.connectDevice(device) },
                        modifier = Modifier.weight(1f),
                    ) { Text("连接") }
                }
            }

            Button(
                onClick = { vm.togglePlayback(deviceId) },
                enabled = connectionState == ConnectionState.CONNECTED,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isPlaying) "停止播放" else "开始播放")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("音频参数 (服务端)", style = MaterialTheme.typography.titleMedium)
                val info = audioInfos[deviceId]
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("采样率")
                    Text(info?.sampleRate?.toString() ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("位深")
                    Text(info?.bits?.toString() ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("声道数")
                    Text(info?.channels?.toString() ?: "-")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("格式(format)")
                    Text(info?.format?.toString() ?: "-")
                }
                Text(
                    "说明: 音频参数由服务端决定，客户端仅展示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("设备配置", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("设备名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        OutlinedTextField(
            value = publicKey,
            onValueChange = { publicKey = it },
            label = { Text("公钥") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("自动连接")
            Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
        }

        Text("音频流加密方式", style = MaterialTheme.typography.titleSmall)

        AudioEncryptionMethod.entries.forEach { method ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = audioEncryption == method,
                    onClick = { audioEncryption = method },
                )
                Text(method.label)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (ip.trim().isBlank()) {
                    Toast.makeText(context, "IP 不能为空", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val portInt = port.toIntOrNull()
                if (portInt == null) {
                    Toast.makeText(context, "端口格式不正确", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val newAddress = "${ip.trim()}:${portInt}"
                val newDeviceConfig = DeviceConfig(
                    name = name.trim(),
                    address = newAddress,
                    publicKey = publicKey.trim(),
                    autoPlay = autoConnect,
                    audioEncryption = audioEncryption,
                )

                vm.saveDeviceConfig(deviceId, newDeviceConfig)

                if (newAddress != deviceId) {
                    onDeviceIdUpdated(newAddress)
                }
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
    }
}
