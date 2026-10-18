package com.ciro.kuota

import android.content.Context
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

object TotpHelper {
    private const val PREFS_KEY = "totp_secret_key"
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun getOrCreateSecret(context: Context): String {
        val prefs = context.getSharedPreferences("cirokuota_prefs", Context.MODE_PRIVATE)
        var secret = prefs.getString(PREFS_KEY, null)
        if (secret.isNullOrBlank()) {
            secret = generateRandomBase32(16)
            prefs.edit().putString(PREFS_KEY, secret).apply()
        }
        return secret
    }

    fun getOtpAuthUri(secret: String): String {
        return "otpauth://totp/CiroKuota:TuanCiro?secret=$secret&issuer=CiroKuota"
    }

    private fun generateRandomBase32(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val idx = random.nextInt(BASE32_CHARS.length)
            sb.append(BASE32_CHARS[idx])
        }
        return sb.toString()
    }

    fun generateCode(secret: String, timeStepSeconds: Long = 30): String {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val timeIndex = currentTimeSeconds / timeStepSeconds
        return generateCodeForIndex(secret, timeIndex)
    }

    fun getSecondsRemaining(timeStepSeconds: Long = 30): Int {
        val currentSec = System.currentTimeMillis() / 1000
        return (timeStepSeconds - (currentSec % timeStepSeconds)).toInt()
    }

    fun verifyCode(secret: String, inputCode: String, timeStepSeconds: Long = 30): Boolean {
        val cleanInput = inputCode.trim()
        if (cleanInput.length != 6 || !cleanInput.all { it.isDigit() }) return false

        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val currentIndex = currentTimeSeconds / timeStepSeconds

        // Cek time window sekarang, 1 step sebelumnya, dan 1 step sesudahnya (toleransi clock drift)
        for (offset in -1..1) {
            val expectedCode = generateCodeForIndex(secret, currentIndex + offset)
            if (expectedCode == cleanInput) {
                return true
            }
        }
        return false
    }

    private fun generateCodeForIndex(secret: String, timeIndex: Long): String {
        val keyBytes = decodeBase32(secret)
        val data = ByteBuffer.allocate(8).putLong(timeIndex).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(data)

        // Dynamic truncation
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 1000000
        return String.format("%06d", otp)
    }

    private fun decodeBase32(base32: String): ByteArray {
        val clean = base32.uppercase().replace("[^A-Z2-7]".toRegex(), "")
        val bytes = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (c in clean) {
            val valIndex = BASE32_CHARS.indexOf(c)
            if (valIndex < 0) continue
            buffer = (buffer shl 5) or valIndex
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return bytes.toByteArray()
    }
}
