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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class CiroVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        stopVpn()

        try {
            val builder = Builder()
                .setSession("CiroKuota Blackhole Firewall")
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)

            val useFamilyDns = QuotaManager.isFamilyDnsActive(this)
            val dnsIp = if (useFamilyDns) "1.1.1.3" else "1.1.1.1"
            builder.addDnsServer(dnsIp)

            val isKillswitch = QuotaManager.isKillswitchActive(this)
            val targetApps = QuotaManager.getBlackholePackages(this)
            val pm = packageManager
            var addedCount = 0

            if (!isKillswitch) {
                // Split-Tunneling: HANYA aplikasi yang dipilih yang dialirkan ke VPN Blackhole
                for (pkg in targetApps) {
                    try {
                        pm.getApplicationInfo(pkg, 0)
                        builder.addAllowedApplication(pkg)
                        addedCount++
                    } catch (_: Exception) {}
                }

                // Pengaman krusial: Jika belum ada aplikasi terpilih/terpasang yang cocok,
                // JANGAN sampai VpnBuilder menangkap seluruh trafik HP!
                // Cukup pasang dummy package aplikasi CiroKuota sendiri.
                if (addedCount == 0) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (_: Exception) {}
                }
            } else {
                // Killswitch Aktif: Tangkap semua aplikasi kecuali aplikasi CiroKuota sendiri
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {}
                }
            }

            builder.setMtu(1500)
            builder.setBlocking(false)

            vpnInterface = builder.establish()
            QuotaManager.setVpnActive(this, true)

            val pfd = vpnInterface ?: return

            // Coroutine utama pengelola paket VPN
            job = scope.launch {
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32768)

                while (isActive) {
                    val length = try {
                        inputStream.read(buffer)
                    } catch (_: Exception) {
                        break
                    }

                    if (length <= 0) {
                        delay(10)
                        continue
                    }

                    // Proses paket IPv4
                    processPacket(buffer, length, outputStream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun processPacket(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 20) return
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return // Hanya IPv4

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        when (protocol) {
            17 -> { // UDP
                if (length >= ihl + 8) {
                    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

                    if (dstPort == 53) {
                        // DNS Query: Teruskan ke upstream Cloudflare (1.1.1.3 atau 1.1.1.1) secara paralel
                        val packetCopy = packet.copyOf(length)
                        dnsScope.launch {
                            forwardDnsQuery(packetCopy, ihl, length, srcIp, dstIp, srcPort, outputStream)
                        }
                    }
                    // UDP lain (misal QUIC/WebRTC) dari aplikasi terblokir dibiarkan drop (blackhole)
                }
            }
            6 -> { // TCP
                if (length >= ihl + 20) {
                    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                    val tcpFlags = packet[ihl + 13].toInt() and 0xFF
                    val isSyn = (tcpFlags and 0x02) != 0

                    // Kirim TCP RST segera agar aplikasi terblokir langsung menolak koneksi (tidak bengong/stuck)
                    if (isSyn) {
                        sendTcpRst(packet, ihl, srcIp, dstIp, srcPort, dstPort, outputStream)
                    }
                }
            }
        }
    }

    private fun forwardDnsQuery(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        outputStream: FileOutputStream
    ) {
        try {
            val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
            if (udpLen < 8 || ihl + udpLen > length) return
            val dnsPayload = packet.copyOfRange(ihl + 8, ihl + udpLen)

            val useFamilyDns = QuotaManager.isFamilyDnsActive(applicationContext)
            val upstreamDnsIp = if (useFamilyDns) "1.1.1.3" else "1.1.1.1"

            val socket = DatagramSocket()
            protect(socket) // PENTING: Socket bypass VPN tunnel agar tembus ke jaringan fisik
            socket.soTimeout = 2500

            val targetAddr = InetAddress.getByName(upstreamDnsIp)
            val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, targetAddr, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(1500)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)
            socket.close()

            val dnsRespLen = recvPacket.length
            val totalLen = 20 + 8 + dnsRespLen
            val respBuf = ByteArray(totalLen)

            // IPv4 Header
            respBuf[0] = 0x45
            respBuf[1] = 0x00
            respBuf[2] = ((totalLen shr 8) and 0xFF).toByte()
            respBuf[3] = (totalLen and 0xFF).toByte()
            respBuf[4] = 0x12
            respBuf[5] = 0x34
            respBuf[6] = 0x40 // Don't Fragment
            respBuf[7] = 0x00
            respBuf[8] = 64 // TTL
            respBuf[9] = 17 // UDP
            System.arraycopy(dstIp, 0, respBuf, 12, 4) // Source IP = DNS Server
            System.arraycopy(srcIp, 0, respBuf, 16, 4) // Destination IP = Client

            val ipCsum = calculateIpChecksum(respBuf, 0, 20)
            respBuf[10] = ((ipCsum shr 8) and 0xFF).toByte()
            respBuf[11] = (ipCsum and 0xFF).toByte()

            // UDP Header
            respBuf[20] = 0x00
            respBuf[21] = 53 // Source Port = 53
            respBuf[22] = ((srcPort shr 8) and 0xFF).toByte()
            respBuf[23] = (srcPort and 0xFF).toByte()
            val udpRespLen = 8 + dnsRespLen
            respBuf[24] = ((udpRespLen shr 8) and 0xFF).toByte()
            respBuf[25] = (udpRespLen and 0xFF).toByte()
            respBuf[26] = 0 // Checksum opsional pada IPv4 UDP
            respBuf[27] = 0

            // DNS Payload
            System.arraycopy(recvBuf, 0, respBuf, 28, dnsRespLen)

            synchronized(outputStream) {
                outputStream.write(respBuf)
            }
        } catch (_: Exception) {}
    }

    private fun sendTcpRst(
        packet: ByteArray,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        outputStream: FileOutputStream
    ) {
        try {
            val seqBytes = packet.copyOfRange(ihl + 4, ihl + 8)
            val seqNum = ByteBuffer.wrap(seqBytes).int.toLong() and 0xFFFFFFFFL
            val ackNum = (seqNum + 1) and 0xFFFFFFFFL

            val totalLen = 40
            val rstBuf = ByteArray(totalLen)

            // IPv4 Header
            rstBuf[0] = 0x45
            rstBuf[1] = 0x00
            rstBuf[2] = 0x00
            rstBuf[3] = 40
            rstBuf[4] = 0x00
            rstBuf[5] = 0x00
            rstBuf[6] = 0x40
            rstBuf[7] = 0x00
            rstBuf[8] = 64
            rstBuf[9] = 6 // TCP
            System.arraycopy(dstIp, 0, rstBuf, 12, 4) // Source IP = Target Server
            System.arraycopy(srcIp, 0, rstBuf, 16, 4) // Destination IP = Client

            val ipCsum = calculateIpChecksum(rstBuf, 0, 20)
            rstBuf[10] = ((ipCsum shr 8) and 0xFF).toByte()
            rstBuf[11] = (ipCsum and 0xFF).toByte()

            // TCP Header
            rstBuf[20] = ((dstPort shr 8) and 0xFF).toByte()
            rstBuf[21] = (dstPort and 0xFF).toByte()
            rstBuf[22] = ((srcPort shr 8) and 0xFF).toByte()
            rstBuf[23] = (srcPort and 0xFF).toByte()

            // Sequence Number = 0
            rstBuf[24] = 0
            rstBuf[25] = 0
            rstBuf[26] = 0
            rstBuf[27] = 0

            // Acknowledgment Number = seqNum + 1
            rstBuf[28] = ((ackNum shr 24) and 0xFF).toByte()
            rstBuf[29] = ((ackNum shr 16) and 0xFF).toByte()
            rstBuf[30] = ((ackNum shr 8) and 0xFF).toByte()
            rstBuf[31] = (ackNum and 0xFF).toByte()

            rstBuf[32] = 0x50 // Data offset = 20 bytes
            rstBuf[33] = 0x14 // RST + ACK
            rstBuf[34] = 0 // Window = 0
            rstBuf[35] = 0

            val tcpCsum = calculateTcpChecksum(dstIp, srcIp, rstBuf, 20, 20)
            rstBuf[36] = ((tcpCsum shr 8) and 0xFF).toByte()
            rstBuf[37] = (tcpCsum and 0xFF).toByte()

            synchronized(outputStream) {
                outputStream.write(rstBuf)
            }
        } catch (_: Exception) {}
    }

    private fun calculateIpChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun calculateTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        // Pseudo-header: Src IP (4), Dst IP (4), Zero (1), Protocol (1 = 6), TCP Length (2)
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6 // Protocol TCP
        sum += length // TCP segment length

        var i = offset
        while (i < offset + length) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun stopVpn() {
        job?.cancel()
        job = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        QuotaManager.setVpnActive(this, false)
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        dnsScope.cancel()
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
        val targetCount = QuotaManager.getBlackholePackages(this).size
        val statusText = if (isKill) {
            "🚨 Killswitch Aktif: Seluruh internet diputus!"
        } else {
            "🛡️ Blackhole Firewall Aktif ($targetCount aplikasi diproteksi)"
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
