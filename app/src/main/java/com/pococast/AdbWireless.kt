package com.pococast

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Pairs PocoCast with the phone's own Wireless debugging server and runs
 * commands as the adb shell user. That is what lets the app install its own
 * updates with no installer screen and without any second app: the privilege
 * comes from the phone itself, granted once by typing the pairing code.
 */
object AdbWireless {

    private const val PREFS = "pococast-adb"
    private const val PAIRED = "paired"
    private const val DECLINED = "declined"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun isPaired(context: Context): Boolean = prefs(context).getBoolean(PAIRED, false)

    /** True once the user chose the installer instead; we stop offering pairing. */
    fun pairingDeclined(context: Context): Boolean = prefs(context).getBoolean(DECLINED, false)

    fun declinePairing(context: Context) {
        prefs(context).edit().putBoolean(DECLINED, true).apply()
    }

    /**
     * Pairs with the port/code Android shows in "Pair device with pairing code".
     * Returns null on success, otherwise the reason. Blocking.
     */
    fun pair(context: Context, address: String, code: String): String? {
        val parts = address.trim().split(":")
        if (parts.size != 2) return "type the address as IP:port"
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return "the port looks wrong"
        val pairingCode = code.trim()
        if (pairingCode.length < 6) return "the pairing code is 6 digits"
        val manager = manager(context)
        return try {
            manager.setApi(Build.VERSION.SDK_INT)
            manager.setTimeout(30, TimeUnit.SECONDS)
            if (!manager.pair(host, port, pairingCode)) return "the phone rejected the code"
            // Pairing authorises our key; prove the shell is reachable before trusting it.
            val connected = runCatching { manager.connectTls(context, 15_000) }.getOrDefault(false)
            if (!connected) return "paired, but Wireless debugging was not reachable"
            quietPlayProtect(manager)
            prefs(context).edit().putBoolean(PAIRED, true).putBoolean(DECLINED, false).apply()
            Diag.log("update: paired with the phone's own adb at " + host + ":" + port)
            null
        } catch (t: Throwable) {
            Diag.log("update: pairing failed: " + t.javaClass.simpleName + ": " + t.message)
            t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { manager.close() }
        }
    }

    /**
     * Installs [apk] through the shell user. Returns null on success, otherwise
     * the reason. Blocking; the running process is replaced on success.
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() == 0L) return "the update file is missing"
        val manager = manager(context)
        return try {
            manager.setApi(Build.VERSION.SDK_INT)
            manager.setTimeout(60, TimeUnit.SECONDS)
            if (!manager.connectTls(context, 15_000)) return "Wireless debugging is not reachable"
            val size = apk.length()
            manager.openStream("shell:pm install -r -S " + size).use { stream ->
                stream.openOutputStream().use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                }
                val text = stream.openInputStream()
                    .bufferedReader()
                    .readText()
                    .trim()
                Diag.log("update: adb install said: " + text.takeLast(300))
                when {
                    text.contains("Success", ignoreCase = true) -> null
                    text.isEmpty() -> "the installer gave no answer"
                    else -> text
                }
            }
        } catch (t: Throwable) {
            Diag.log("update: adb install failed: " + t.javaClass.simpleName + ": " + t.message)
            t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { manager.close() }
        }
    }

    /**
     * Play Protect verifies what an install request brings in, and for an app it
     * has never seen that surfaces as Google's warning screen. These are the
     * switches behind it, so turning them off keeps later installs quiet; the
     * setting only governs install-time verification, not the running system.
     */
    private fun quietPlayProtect(manager: AbsAdbConnectionManager) {
        listOf(
            "settings put global verifier_verify_adb_installs 0",
            "settings put global package_verifier_user_consent -1",
            "settings put global package_verifier_enable 0"
        ).forEach { command ->
            val answer = runCatching { shell(manager, command) }.getOrNull()
            Diag.log("update: " + command + " -> " + (answer?.trim().orEmpty()))
        }
    }

    private fun shell(manager: AbsAdbConnectionManager, command: String): String =
        manager.openStream("shell:" + command).use { stream ->
            stream.openInputStream().bufferedReader().readText()
        }

    // ---- keys ----

    private fun manager(context: Context): Manager = Manager(context.applicationContext)

    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val keys = load(context)

        override fun getPrivateKey(): PrivateKey = keys.first

        override fun getCertificate(): Certificate = keys.second

        override fun getDeviceName(): String = "PocoCast"
    }

    /** The key pair must survive restarts, otherwise the pairing would be lost. */
    private fun load(context: Context): Pair<PrivateKey, Certificate> {
        val keyFile = File(context.filesDir, "adbkey.pk8")
        val certFile = File(context.filesDir, "adbkey.crt")
        if (keyFile.exists() && certFile.exists()) {
            val stored = runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyFile.readText()))
                )
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(
                    ByteArrayInputStream(Base64.getDecoder().decode(certFile.readText()))
                )
                key to cert
            }.getOrNull()
            if (stored != null) return stored
            Diag.log("update: stored adb key unreadable, generating a new one")
        }
        val fresh = generate()
        runCatching {
            keyFile.writeText(Base64.getEncoder().encodeToString(fresh.first.encoded))
            certFile.writeText(Base64.getEncoder().encodeToString(fresh.second.encoded))
        }
        return fresh
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val provider = BouncyCastleProvider()
        Security.addProvider(provider)
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val pair = generator.generateKeyPair()
        val subject = X500Name("CN=PocoCast")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs(),
            Date(now - DAY_MILLIS),
            Date(now + TEN_YEARS_MILLIS),
            subject,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        return pair.private to cert
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val TEN_YEARS_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
}
