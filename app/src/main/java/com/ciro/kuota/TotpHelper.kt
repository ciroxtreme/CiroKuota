package com.ciro.kuota

import android.content.Context
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object TotpHelper {
    private const val PREFS_KEY = "totp_secret_key"
    private const val PREFS_CONFIGURED = "totp_is_configured"
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun isConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences("cirokuota_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREFS_CONFIGURED, false)
    }

    fun setConfigured(context: Context, configured: Boolean) {
        val prefs = context.getSharedPreferences("cirokuota_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREFS_CONFIGURED, configured).apply()
    }

    fun getOrCreateSecret(context: Context): String {
        val prefs = context.getSharedPreferences("cirokuota_prefs", Context.MODE_PRIVATE)
        var secret = prefs.getString(PREFS_KEY, null)
        if (secret.isNullOrBlank()) {
            secret = generateRandomBase32(16)
            prefs.edit().putString(PREFS_KEY, secret).apply()
        }
        return secret
    }

    fun resetSecret(context: Context): String {
        val prefs = context.getSharedPreferences("cirokuota_prefs", Context.MODE_PRIVATE)
        val newSecret = generateRandomBase32(16)
        prefs.edit()
            .putString(PREFS_KEY, newSecret)
            .putBoolean(PREFS_CONFIGURED, false)
            .apply()
        return newSecret
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
        if (cleanInput.length != 6 || !cleanInput.all { it.isDigit() }) {
            return false
        }
        if (secret.isBlank()) return false

        try {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val currentIndex = currentTimeSeconds / timeStepSeconds

            // Cek window: sekarang, 1 step sebelum, 1 step sesudah (toleransi toleran 30s)
            for (offset in -1..1) {
                val expectedCode = generateCodeForIndex(secret, currentIndex + offset)
                if (expectedCode.isNotEmpty() && expectedCode == cleanInput) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return false
    }

    fun generateCodeForIndex(secret: String, timeIndex: Long): String {
        try {
            val keyBytes = decodeBase32(secret)
            if (keyBytes.isEmpty()) return ""

            val data = ByteBuffer.allocate(8).putLong(timeIndex).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
            val hash = mac.doFinal(data)

            // Dynamic truncation (RFC 4226 / RFC 6238)
            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val otp = abs(binary) % 1000000
            return String.format("%06d", otp)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decodeBase32(base32: String): ByteArray {
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
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        return bytes.toByteArray()
    }
}
