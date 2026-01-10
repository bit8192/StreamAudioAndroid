package cn.bincker.stream.sound

import cn.bincker.stream.sound.utils.generateX25519KeyPair
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Test

class EcdhTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun ecdh() {
        val keyPair1 = generateX25519KeyPair()
        val keyPair2 = generateX25519KeyPair()
        val publicKey1 = keyPair1.public as X25519PublicKeyParameters
        val privateKey1 = keyPair1.private as X25519PrivateKeyParameters
        val publicKey2 = keyPair2.public as X25519PublicKeyParameters
        val privateKey2 = keyPair2.private as X25519PrivateKeyParameters
        val secret1 = ByteArray(32)
        val secret2 = ByteArray(32)
        privateKey1.generateSecret(publicKey2, secret1, 0)
        privateKey2.generateSecret(publicKey1, secret2, 0)
        println("Secret 1: ${secret1.toHexString()}")
        println("Secret 2: ${secret2.toHexString()}")
        assert(secret1.contentEquals(secret2))
    }
}