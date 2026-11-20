package com.reloj.despertar.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reloj.despertar.alarm.AlarmService
import com.reloj.despertar.alarm.AlarmState
import kotlinx.coroutines.delay

/**
 * Pantalla de alarma. Mientras sube el sonido, el fondo "amanece" despacio de casi negro a ámbar suave
 * (la luz es la señal más potente del reloj biológico, pero se mantiene tenue para no deslumbrar).
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AmanecerTheme {
                RingScreen(
                    onDismiss = { startService(AlarmService.stopIntent(this)) },
                    onSnooze = { startService(AlarmService.snoozeIntent(this)) },
                    onFinished = { finish() },
                )
            }
        }
    }
}

@Composable
private fun RingScreen(onDismiss: () -> Unit, onSnooze: () -> Unit, onFinished: () -> Unit) {
    val ringing = AlarmState.ringing
    LaunchedEffect(ringing == null) { if (ringing == null) onFinished() }

    var progress by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(ringing?.startedAtMs) {
        val r = ringing ?: return@LaunchedEffect
        while (true) {
            val elapsed = (System.currentTimeMillis() - r.startedAtMs) / 1000.0
            progress = (elapsed / r.rampSeconds).coerceIn(0.0, 1.0)
            delay(500)
        }
    }

    val bg = lerp(Color(0xFF05060A), Color(0xFF4A2A10), progress.toFloat())

    Column(
        Modifier.fillMaxSize().background(bg).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (ringing != null) "%02d:%02d".format(ringing.hour, ringing.minute) else "",
            fontSize = 76.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val label = ringing?.label.orEmpty()
        if (label.isNotBlank()) Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(56.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Text("Parar", fontSize = 22.sp)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text("Posponer ${ringing?.snoozeMinutes ?: 5} min", fontSize = 18.sp)
        }
    }
}
