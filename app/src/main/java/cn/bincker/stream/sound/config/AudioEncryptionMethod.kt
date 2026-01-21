package cn.bincker.stream.sound.config

enum class AudioEncryptionMethod(val label: String, val wireValue: Int) {
    NONE("无加密", 0),
    XOR_256("256位异或加密", 1),
    AES128GCM("AES128GCM", 2),
    AES256GCM("AES256GCM", 3),
    ;

    companion object {
        fun fromWire(value: Int) = when (value) {
            0 -> NONE
            2 -> AES128GCM
            3 -> AES256GCM
            else -> XOR_256
        }
    }
}
