package cn.bincker.stream.sound.config

import android.util.Log
import android.widget.Toast
import cn.bincker.stream.sound.Constant
import cn.bincker.stream.sound.PORT
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.regex.Pattern

data class DeviceConfig(
    var name: String = "",
    var address: String = "",
    var publicKey: String = ""
){
    constructor()
}