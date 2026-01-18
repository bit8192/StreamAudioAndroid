package cn.bincker.stream.sound.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.repository.AppConfigRepository
import cn.bincker.stream.sound.ui.theme.StreamAudioTheme
import cn.bincker.stream.sound.vm.ConnectionState

@Composable
fun DeviceCard(
    device: Device,
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    isPlaying: Boolean = false,
    errorMessage: String? = null,
    onCardClick: () -> Unit = {},
    onPlayStopClick: () -> Unit = {},
    onErrorDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 动画效果
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // 状态颜色动画
    val cardColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "card_color"
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isPlaying) pulseScale else 1f)
            .clickable(
                enabled = connectionState != ConnectionState.CONNECTING,
                onClick = onCardClick
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (connectionState == ConnectionState.CONNECTED) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 主内容行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：设备信息
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 连接状态图标
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (connectionState) {
                            ConnectionState.CONNECTING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                            ConnectionState.CONNECTED -> {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "已连接",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .alpha(if (isPlaying) pulseAlpha else 1f)
                                )
                            }
                            ConnectionState.ERROR -> {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "连接错误",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "未连接",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // 设备信息文本
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.config.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = device.config.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // 状态文本
                        AnimatedVisibility(
                            visible = connectionState != ConnectionState.DISCONNECTED || isPlaying,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                text = when {
                                    isPlaying -> "正在播放"
                                    connectionState == ConnectionState.CONNECTING -> "正在连接..."
                                    connectionState == ConnectionState.CONNECTED -> "已连接"
                                    connectionState == ConnectionState.ERROR -> "连接失败"
                                    else -> "未连接"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (connectionState) {
                                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // 右侧：播放/停止按钮
                FilledTonalIconButton(
                    onClick = onPlayStopClick,
                    enabled = connectionState == ConnectionState.CONNECTED,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "停止" else "播放",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 错误信息展示
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { onErrorDismiss() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "错误",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceCardPreview() {
    StreamAudioTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 预览不同状态的卡片 - 创建简单的mock
            val mockConfig = DeviceConfig().apply {
                name = "我的电脑"
                address = "192.168.1.100:12345"
                publicKey = ""
            }

            // 使用简单的mock数据，避免实际创建Device对象
            @Composable
            fun PreviewCard(
                name: String = "我的电脑",
                address: String = "192.168.1.100:12345",
                connectionState: ConnectionState,
                isPlaying: Boolean = false,
                errorMessage: String? = null
            ) {
                val mockDevice = Device(
                    connectionManager = null,  // 预览时传null
                    config = DeviceConfig().apply {
                        this.name = name
                        this.address = address
                        this.publicKey = ""
                    }
                )

                DeviceCard(
                    device = mockDevice,
                    connectionState = connectionState,
                    isPlaying = isPlaying,
                    errorMessage = errorMessage
                )
            }

            PreviewCard(
                connectionState = ConnectionState.DISCONNECTED
            )

            PreviewCard(
                connectionState = ConnectionState.CONNECTING
            )

            PreviewCard(
                connectionState = ConnectionState.CONNECTED
            )

            PreviewCard(
                connectionState = ConnectionState.CONNECTED,
                isPlaying = true
            )

            PreviewCard(
                connectionState = ConnectionState.ERROR,
                errorMessage = "网络连接失败：无法连接到设备"
            )
        }
    }
}