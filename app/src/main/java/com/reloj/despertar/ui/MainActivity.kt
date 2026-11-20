@file:OptIn(ExperimentalMaterial3Api::class)

package com.reloj.despertar.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.reloj.despertar.alarm.AlarmScheduler
import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.AlarmStore
import com.reloj.despertar.data.Schedule
import com.reloj.despertar.sound.WakeSoundPlayer
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AlarmStore(this)
        store.seedIfFirstRun()
        AlarmScheduler.rescheduleAll(this)
        setContent {
            AmanecerTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    App(store, resumeTick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private fun humanDuration(to: ZonedDateTime): String {
    val mins = Duration.between(ZonedDateTime.now(), to).toMinutes().coerceAtLeast(0)
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

@Composable
private fun App(store: AlarmStore, resumeTick: Int) {
    val ctx = LocalContext.current
    var alarms by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var isNew by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun refresh() { alarms = store.all() }

    val e = editing
    if (e != null) {
        BackHandler { editing = null }
        EditScreen(
            alarm = e,
            isNew = isNew,
            onSave = { saved ->
                store.save(saved)
                AlarmScheduler.schedule(ctx, saved)
                refresh()
                editing = null
                if (saved.enabled) {
                    Toast.makeText(
                        ctx, "Sonará en ${humanDuration(Schedule.nextTrigger(saved))}", Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDelete = {
                AlarmScheduler.cancel(ctx, e.id)
                store.delete(e.id)
                refresh()
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                isNew = true
                editing = Alarm(id = store.nextId(), hour = 5, minute = 30, days = Alarm.ALL_DAYS)
            }) { Icon(Icons.Default.Add, contentDescription = "Nueva alarma") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Text("Amanecer", fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 12.dp))
            val next = alarms.filter { it.enabled }.minOfOrNull { Schedule.nextTrigger(it) }
            Text(
                if (next != null) "Próxima alarma en ${humanDuration(next)}" else "Ninguna alarma activa",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            PermissionBanners(resumeTick)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                alarms.forEach { a ->
                    AlarmCard(
                        alarm = a,
                        onClick = { isNew = false; editing = a },
                        onToggle = { on ->
                            val updated = a.copy(enabled = on)
                            store.save(updated)
                            AlarmScheduler.schedule(ctx, updated)
                            refresh()
                            if (on) Toast.makeText(
                                ctx, "Sonará en ${humanDuration(Schedule.nextTrigger(updated))}", Toast.LENGTH_SHORT
                            ).show()
                        },
                    )
                }
                Spacer(Modifier.height(88.dp))
            }
        }
    }
}

@Composable
private fun PermissionBanners(resumeTick: Int) {
    val ctx = LocalContext.current

    // remember(resumeTick): se vuelve a comprobar cada vez que la app vuelve de Ajustes
    val canExact = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
    }
    val canFullScreen = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= 34)
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true
    }

    if (!canExact) {
        Banner("Permite las alarmas exactas para que suene a la hora exacta.") {
            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
            )
        }
    }
    if (!canFullScreen) {
        Banner("Permite mostrar la alarma a pantalla completa con el móvil bloqueado.") {
            if (Build.VERSION.SDK_INT >= 34) ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
            )
        }
    }
}

@Composable
private fun Banner(text: String, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp)
            TextButton(onClick = onFix) { Text("Permitir") }
        }
    }
}

@Composable
private fun AlarmCard(alarm: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val dim = if (alarm.enabled) 1f else 0.5f
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "%02d:%02d".format(alarm.hour, alarm.minute),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                val label = if (alarm.label.isBlank()) "" else "${alarm.label} · "
                Text(
                    label + Schedule.daysSummary(alarm.days) + " · subida ${alarm.rampSeconds} s",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                    fontSize = 13.sp,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EditScreen(
    alarm: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val timeState = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableIntStateOf(alarm.days) }
    var label by remember { mutableStateOf(alarm.label) }
    var ramp by remember { mutableIntStateOf(alarm.rampSeconds) }
    var volume by remember { mutableIntStateOf(alarm.volumePercent) }
    var vibrate by remember { mutableStateOf(alarm.vibrate) }
    var snooze by remember { mutableIntStateOf(alarm.snoozeMinutes) }
    var greeting by remember { mutableStateOf(alarm.speakGreeting) }

    // Vista previa comprimida (20 s) con la misma forma de curva
    var previewing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<WakeSoundPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.stop() } }
    LaunchedEffect(previewing) {
        if (previewing) {
            delay(24_000)
            player?.stop(); player = null; previewing = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (isNew) "Nueva alarma" else "Editar alarma", fontSize = 24.sp, fontWeight = FontWeight.Light)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = timeState) }

        Text("Días", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dayNames = listOf("L", "M", "X", "J", "V", "S", "D")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayNames.forEachIndexed { i, n ->
                val bit = 1 shl i
                FilterChip(
                    selected = (days and bit) != 0,
                    onClick = { days = days xor bit },
                    label = { Text(n) },
                )
            }
        }
        Text(Schedule.daysSummary(days), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Etiqueta") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Subida suave: $ramp s", fontWeight = FontWeight.Medium)
        Slider(value = ramp.toFloat(), onValueChange = { ramp = ((it / 5f).roundToInt() * 5).coerceIn(20, 600) }, valueRange = 20f..600f)
        Text(
            "Empieza casi en silencio y sube en volumen, brillo y ritmo. 60 s es el valor por defecto; más largo = más gradual.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Volumen final: $volume %", fontWeight = FontWeight.Medium)
        Slider(value = volume.toFloat(), onValueChange = { volume = it.roundToInt().coerceIn(10, 100) }, valueRange = 10f..100f)
        Text("Es el volumen de alarma del móvil al que llega al final de la subida.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Posponer: $snooze min", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 9, 10).forEach { m ->
                FilterChip(selected = snooze == m, onClick = { snooze = m }, label = { Text("$m min") })
            }
        }

        SwitchRow("Vibrar al final de la subida", vibrate) { vibrate = it }
        SwitchRow("Voz grave y pausada: \"Buenos días\"", greeting) { greeting = it }

        OutlinedButton(
            onClick = {
                if (previewing) {
                    player?.stop(); player = null; previewing = false
                } else {
                    player = WakeSoundPlayer(20).also { it.start() }
                    previewing = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (previewing) "Parar prueba" else "Probar (versión de 20 s, volumen actual de alarma)") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            Button(
                onClick = {
                    player?.stop()
                    onSave(
                        alarm.copy(
                            hour = timeState.hour, minute = timeState.minute, days = days, enabled = true,
                            label = label.trim(), rampSeconds = ramp, volumePercent = volume,
                            vibrate = vibrate, snoozeMinutes = snooze, speakGreeting = greeting,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Guardar") }
        }
        if (!isNew) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Eliminar alarma") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
