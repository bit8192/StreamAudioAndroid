package cn.bincker.stream.sound.utils

private val hexArray = "0123456789abcdef".toCharArray()
fun ByteArray.toHexString(): String {
    val hexChars = CharArray(size * 2)
    for (j in indices) {
        val v = get(j).toInt() and 0xFF
        hexChars[j * 2] = hexArray[v.ushr(4)]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
    }
    return String(hexChars)
}
fun String.hexToByteArray(): ByteArray {
    val len = length
    require(len % 2 == 0) { "Hex string must have even length" }
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val firstDigit = Character.digit(this[i], 16)
        val secondDigit = Character.digit(this[i + 1], 16)
        require(firstDigit != -1 && secondDigit != -1) { "Invalid hex character: ${this[i]}${this[i + 1]}" }
        data[i / 2] = ((firstDigit shl 4) + secondDigit).toByte()
        i += 2
    }
    return data
}