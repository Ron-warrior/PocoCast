package com.pococast.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The client half of SRP-6a over the 3072-bit group from RFC 5054.
 *
 * An Apple TV running a current tvOS answers 403 to `/play` until the
 * connection has completed a HomeKit pairing exchange, and that exchange is
 * SRP. The transient form AirPlay uses carries a fixed PIN, so nobody types
 * anything; what the exchange actually proves is that both ends derived the
 * same secret from the same group.
 *
 * Every step below mirrors the reference client exactly, down to which values
 * are zero padded and which are not, because one differing byte makes the Apple
 * TV reject the proof.
 */
object Srp {

    private const val HASH = "SHA-512"

    /** The width the protocol zero-pads values to: the byte length of N. */
    private const val KEY_BYTES = 384

    private val N = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
            "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
            "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
            "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D" +
            "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226" +
            "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC" +
            "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16
    )

    private val G = BigInteger.valueOf(5)

    class Pairing(
        val clientPublic: ByteArray,
        val proof: ByteArray,
        val serverProof: ByteArray,
        val sessionKey: ByteArray
    )

    /**
     * Runs the SRP arithmetic for one exchange: [salt] and [serverKey] come from
     * the Apple TV's first reply, [username] is this client's identifier and
     * [pin] is the fixed transient PIN.
     */
    fun client(username: String, pin: String, salt: ByteArray, serverKey: ByteArray): Pairing {
        val serverPublic = BigInteger(1, serverKey)
        require(serverPublic.mod(N).signum() != 0) { "the Apple TV sent an unusable key" }

        val multiplier = hashInt(unsigned(N), pad(unsigned(G)))
        val secret = hash((username + ":" + pin).toByteArray(Charsets.UTF_8))
        val passwordHash = hashInt(salt, secret)
        val verifier = G.modPow(passwordHash, N)

        val privateKey = BigInteger(1024, SecureRandom())
        val clientPublic = G.modPow(privateKey, N)

        val scrambling = hashInt(pad(unsigned(clientPublic)), pad(unsigned(serverPublic)))
        val shared = serverPublic.subtract(multiplier.multiply(verifier)).mod(N)
            .modPow(privateKey.add(scrambling.multiply(passwordHash)), N)
        val sessionKey = hash(unsigned(shared))

        val proof = hash(
            unsigned(hashInt(unsigned(N)).xor(hashInt(unsigned(G)))),
            unsigned(hashInt(username.toByteArray(Charsets.UTF_8))),
            salt,
            unsigned(clientPublic),
            unsigned(serverPublic),
            sessionKey
        )
        val serverProof = hash(unsigned(clientPublic), proof, sessionKey)

        return Pairing(unsigned(clientPublic), proof, serverProof, sessionKey)
    }

    private fun hash(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(HASH)
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun hashInt(vararg parts: ByteArray): BigInteger = BigInteger(1, hash(*parts))

    /** Big-endian bytes without the sign byte BigInteger adds, as the protocol expects. */
    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        if (bytes.size >= KEY_BYTES) return bytes
        val padded = ByteArray(KEY_BYTES)
        System.arraycopy(bytes, 0, padded, KEY_BYTES - bytes.size, bytes.size)
        return padded
    }
}
