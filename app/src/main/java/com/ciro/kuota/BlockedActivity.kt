package com.ciro.kuota

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ciro.kuota.ui.theme.*

class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cegah bypass dengan tombol Back fisik / gesture
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finish()
            }
        })

        val appName = intent.getStringExtra("EXTRA_APP_NAME") ?: "Aplikasi Target"
        val reason = intent.getStringExtra("EXTRA_REASON") ?: "Batas kuota atau sesi waktu telah habis."
        val isHardBlock = intent.getBooleanExtra("EXTRA_IS_HARD_BLOCK", false)

        setContent {
            val context = LocalContext.current
            var showChallengeDialog by remember { mutableStateOf(false) }
            val secretKey = remember { TotpHelper.getOrCreateSecret(context) }
            val isTotpConfigured = remember { TotpHelper.isConfigured(context) }

            CiroKuotaTheme(darkTheme = true) {
                BlockedScreen(
                    targetApp = appName,
                    reason = reason,
                    isHardBlock = isHardBlock,
                    isTotpConfigured = isTotpConfigured,
                    onGoHome = {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        finish()
                    },
                    onOpenApp = {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(mainIntent)
                        finish()
                    },
                    onStartChallenge = {
                        if (QuotaManager.isStrictTimerActive(context)) {
                            Toast.makeText(
                                context,
                                "⏳ Mode Gembok Timer Aktif! Tidak ada jalan pintas sampai waktu gembok selesai.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BlockedScreen
                        }

                        if (QuotaManager.isStrictChargerActive(context) && !QuotaManager.isDeviceCharging(context)) {
                            Toast.makeText(
                                context,
                                "🔌 Gembok Ketat: Tancapkan HP ke charger/cas dulu untuk membuka tantangan!",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BlockedScreen
                        }

                        if (QuotaManager.isStrictTotpActive(context) && !isTotpConfigured) {
                            Toast.makeText(
                                context,
                                "Setup Google Authenticator di Tab Strict Mode belum aktif!",
                                Toast.LENGTH_LONG
                            ).show()
                            return@BlockedScreen
                        }

                        showChallengeDialog = true
                    }
                )

                if (showChallengeDialog) {
                    DarkSoulsUnlockDialog(
                        secretKey = secretKey,
                        onDismiss = { showChallengeDialog = false },
                        onSuccess = {
                            showChallengeDialog = false
                            QuotaManager.grantTemporaryPass(context, 15 * 60 * 1000L)
                            QuotaManager.grantSettingsGuardBypass(context, 15 * 60 * 1000L)
                            Toast.makeText(
                                context,
                                "🔥 TANTANGAN DITAKLUKKAN! Akses dibuka sementara selama 15 menit.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BlockedScreen(
    targetApp: String,
    reason: String,
    isHardBlock: Boolean,
    isTotpConfigured: Boolean,
    onGoHome: () -> Unit,
    onOpenApp: () -> Unit,
    onStartChallenge: () -> Unit
) {
    Scaffold(
        containerColor = DarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon Badge
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(AccentRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isHardBlock) "KUOTA HANGUS - BLOKIR TOTAL" else "AKSES DIBATASI",
                fontFamily = RighteousFontFamily,
                fontSize = if (isHardBlock) 22.sp else 26.sp,
                color = AccentRed,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Target: $targetApp",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentAmber
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Reason Card & Hukuman Kejam
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = DarkCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isHardBlock) AccentRed.copy(alpha = 0.5f) else DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = reason,
                        fontFamily = PoppinsFontFamily,
                        fontSize = 13.5.sp,
                        color = DarkTextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    if (isHardBlock) {
                        Text(
                            text = "☠️ HUKUMAN KEJAM ☠️\nNafsu murahan 5 menit mau ngorbanin masa depan lu?! Kuota lu udah habis dilahap Douyin/konten pemicu. Pintu darurat DIHANCURKAN! Gaada matematika, gaada TOTP. Tarik nafas, taruh HP, cuci muka, lu bukan budak dopamin!",
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = AccentRed,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    } else {
                        Text(
                            text = "💡 Tuan Ciro, kuota Wi-Fi & waktu Anda sangat berharga. Saatnya istirahat sejenak!",
                            fontFamily = PoppinsFontFamily,
                            fontSize = 12.sp,
                            color = DarkTextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Action: Tantangan Neraka (Emergency Unlock) - HANYA jika bukan Hard Block (bukan kuota habis)
            if (!isHardBlock && isTotpConfigured) {
                Button(
                    onClick = onStartChallenge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed.copy(alpha = 0.2f),
                        contentColor = AccentRed
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed)
                ) {
                    Icon(imageVector = Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buka Darurat 15 Menit",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // Action: Go to Home
            Button(
                onClick = onGoHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = PureBlack
                )
            ) {
                Icon(imageVector = Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Kembali ke Beranda (Home)",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action: Open CiroKuota Dashboard
            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DarkTextPrimary
                )
            ) {
                Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Buka Dashboard CiroKuota",
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
