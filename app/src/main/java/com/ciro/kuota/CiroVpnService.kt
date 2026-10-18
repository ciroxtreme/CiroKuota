package com.ciro.kuota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class CiroVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "com.ciro.kuota.ACTION_VPN_START"
        const val ACTION_STOP = "com.ciro.kuota.ACTION_VPN_STOP"
        const val CHANNEL_ID = "cirokuota_firewall_channel"
        const val NOTIFICATION_ID = 101

        fun start(context: Context) {
            val intent = Intent(context, CiroVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CiroVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("CiroKuota Firewall")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)

            val useFamilyDns = QuotaManager.isFamilyDnsActive(this)
            if (useFamilyDns) {
                // Cloudflare 1.1.1.3 Family DNS (Blokir Otomatis Situs Dewasa & Malware di Level Jaringan)
                builder.addDnsServer("1.1.1.3")
                builder.addDnsServer("1.0.0.3")
            } else {
                builder.addDnsServer("1.1.1.1")
            }

            // Exclude our own app from VPN loop
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {}
            }

            vpnInterface = builder.establish()

            // Coroutine loop to drain/drop traffic if Killswitch is engaged
            job = scope.launch {
                val pfd = vpnInterface ?: return@launch
                val input = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32768)

                while (isActive) {
                    try {
                        val isKillswitch = QuotaManager.isKillswitchActive(applicationContext)
                        val quotaStatus = QuotaManager.getQuotaStatus(applicationContext)
                        val isAutoCutoff = QuotaManager.isAutoCutoffActive(applicationContext) && quotaStatus.isLimitReached

                        val shouldDropAll = isKillswitch || isAutoCutoff

                        val length = input.read(buffer)
                        if (length > 0) {
                            if (shouldDropAll) {
                                // Blackhole / Killswitch: Paket langsung dibuang (0 bytes forwarded)
                                continue
                            }
                            // Local DNS pass-through filtering
                        } else {
                            delay(50)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun stopVpn() {
        job?.cancel()
        job = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CiroKuota Firewall & Killswitch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status perlindungan firewall dan pembatas kuota Wi-Fi CiroKuota"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isKill = QuotaManager.isKillswitchActive(this)
        val statusText = if (isKill) {
            "🚨 Killswitch Aktif: Seluruh internet diputus!"
        } else {
            "🛡️ Firewall & Family DNS Aktif: Menghemat kuota Wi-Fi"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CiroKuota Shield")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
