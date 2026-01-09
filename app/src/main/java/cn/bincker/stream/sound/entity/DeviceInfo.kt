package cn.bincker.stream.sound.entity

import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.utils.loadPublicKey
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.SocketChannel

class DeviceInfo(
    val config: DeviceConfig,
    var connected: Boolean = false,
    var channel: SocketChannel? = null,
) {
    val publicKey: Ed25519PublicKeyParameters = loadPublicKey(config.publicKey)

    val socketAddress get(): SocketAddress {
        val parts = config.address.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 12345
        return InetSocketAddress(host, port)
    }
}