package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.Constant
import cn.bincker.stream.sound.config.DeviceConfig
import java.net.InetSocketAddress
import java.util.regex.Pattern

data class PairDevice(
    val pairCode: String,
    val device: DeviceConfig
){
    companion object{
        const val TAG = "PairDevice"
        val URI_REGEX: Pattern = Pattern.compile("^${Constant.APPLICATION_URI_PREFIX}(\\w+)@([^:]+):(\\d+)$")
        const val DEFAULT_PORT = 8888
        fun parseUri(uri: String): PairDevice{
            val matcher = URI_REGEX.matcher(uri.trim())
            if (!matcher.find()) throw Exception("invalid uri: $uri")
            val pairCode = matcher.group(1) ?: ""
            val host = matcher.group(2)
            val port = matcher.group(3)?.toInt() ?: DEFAULT_PORT
            val address = InetSocketAddress(host, port)
            Log.d(TAG, "parseUri: pairCode=$pairCode\thost=$host\tport=$port\t")
            return PairDevice(pairCode, DeviceConfig(address.hostName, "$host:$port"))
        }
    }
}
