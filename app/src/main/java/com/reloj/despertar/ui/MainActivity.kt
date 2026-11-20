@file:OptIn(ExperimentalMaterial3Api::class)

package com.reloj.despertar.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.RadioButton
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
import com.reloj.despertar.alarm.AlarmState
import com.reloj.despertar.alarm.AlarmVolume
import com.reloj.despertar.alarm.Vibe
import com.reloj.despertar.data.Alarm
import com.reloj.despertar.data.AlarmStore
import com.reloj.despertar.data.Schedule
import com.reloj.despertar.sound.FileSoundPlayer
import com.reloj.despertar.sound.RingSound
import com.reloj.despertar.sound.Sounds
import com.reloj.despertar.sound.WakeSoundPlayer
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale
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

private fun fmtDuration(s: Int): String = when {
    s < 60 -> "$s s"
    s % 60 == 0 -> "${s / 60} min"
    else -> "${s / 60} min ${s % 60} s"
}

private fun soundLabel(a: Alarm): String =
    if (a.sound == Sounds.FILE) a.soundName.ifBlank { "Mi archivo" } else Sounds.name(a.sound)

private fun displayName(ctx: Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Mi archivo"
    } catch (_: Exception) {
        "Mi archivo"
    }
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
            Text("Amanecer", fontSize = 32.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 12.dp))
            val next = alarms.filter { it.enabled }.minOfOrNull { Schedule.nextTrigger(it) }
            Text(
                if (next != null) "Próxima alarma en ${humanDuration(next)}" else "Ninguna alarma activa",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            RingingBanner()
            PermissionBanners(resumeTick)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (alarms.isEmpty()) {
                    Text(
                        "Pulsa + para crear una alarma.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
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
private fun RingingBanner() {
    val ctx = LocalContext.current
    if (AlarmState.ringing == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hay una alarma sonando",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Medium,
            )
            TextButton(onClick = { ctx.startActivity(Intent(ctx, AlarmActivity::class.java)) }) {
                Text("Abrir", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
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
    val noBatteryLimits = remember(resumeTick) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
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
    if (!noBatteryLimits) {
        Banner("Para que nunca falle: en Batería elige «Sin restricciones» para Amanecer. No gasta más; solo evita que el móvil la duerma.") {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
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
            TextButton(onClick = onFix) { Text("Ajustar") }
        }
    }
}

@Composable
private fun DayDots(days: Int) {
    if (days == 0) {
        Text("Una vez", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val names = listOf("L", "M", "X", "J", "V", "S", "D")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        names.forEachIndexed { i, n ->
            val on = (days and (1 shl i)) != 0
            Text(
                n,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
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
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        fontSize = 50.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    )
                    if (alarm.label.isNotBlank()) {
                        Text(alarm.label, color = MaterialTheme.colorScheme.secondary.copy(alpha = dim))
                    }
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            DayDots(alarm.days)
            Spacer(Modifier.height(6.dp))
            val extras = buildList {
                add(soundLabel(alarm))
                add("subida ${fmtDuration(alarm.rampSeconds)}")
                if (alarm.vibLevel > 0) add("vibración ${Vibe.names[alarm.vibLevel.coerceIn(0, 4)].lowercase()}")
                if (alarm.snoozeMinutes == 0) add("sin posponer")
            }
            Text(
                extras.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SoundRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPlay: (() -> Unit)?,
    pickLabel: String? = null,
    onPick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onPick != null) {
            TextButton(onClick = onPick) { Text(pickLabel ?: "Elegir") }
        }
        if (onPlay != null) {
            TextButton(onClick = onPlay) { Text(if (playing) "■ Parar" else "▶ Probar") }
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
    val ctx = LocalContext.current
    val timeState = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableIntStateOf(alarm.days) }
    var label by remember { mutableStateOf(alarm.label) }
    var sound by remember { mutableStateOf(alarm.sound) }
    var soundUri by remember { mutableStateOf(alarm.soundUri) }
    var soundName by remember { mutableStateOf(alarm.soundName) }
    var ramp by remember { mutableIntStateOf(alarm.rampSeconds) }
    var volume by remember { mutableIntStateOf(alarm.volumePercent) }
    var vibLevel by remember { mutableIntStateOf(alarm.vibLevel) }
    var vibStart by remember { mutableIntStateOf(alarm.vibStartPercent) }
    var snooze by remember { mutableIntStateOf(alarm.snoozeMinutes) }
    var greeting by remember { mutableStateOf(alarm.speakGreeting) }
    var greetingText by remember { mutableStateOf(alarm.greetingText) }
    var speakTime by remember { mutableStateOf(alarm.speakTime) }
    var pitch by remember { mutableIntStateOf(alarm.voicePitch) }
    var rate by remember { mutableIntStateOf(alarm.voiceRate) }

    // ---- vista previa del sonido: 20 s con la subida acelerada, al volumen final elegido ----
    var previewId by remember { mutableStateOf<String?>(null) }
    val previewHolder = remember { arrayOfNulls<RingSound>(1) }
    val volumeHolder = remember { intArrayOf(-1) }

    fun stopPreview() {
        previewHolder[0]?.stop()
        previewHolder[0] = null
        AlarmVolume.restore(ctx, volumeHolder[0])
        volumeHolder[0] = -1
        previewId = null
    }

    fun startPreview(id: String) {
        stopPreview()
        val p: RingSound = if (id == Sounds.FILE) {
            FileSoundPlayer(ctx, Uri.parse(soundUri), 20, 0.0) { }
        } else {
            WakeSoundPlayer(20, id)
        }
        volumeHolder[0] = AlarmVolume.raiseTo(ctx, volume)
        previewHolder[0] = p
        p.start()
        previewId = id
    }

    DisposableEffect(Unit) { onDispose { stopPreview() } }
    LaunchedEffect(previewId) {
        if (previewId != null) {
            delay(24_000)
            stopPreview()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            stopPreview()
            soundUri = uri.toString()
            soundName = displayName(ctx, uri)
            sound = Sounds.FILE
        }
    }

    // ---- prueba de vibración y de voz ----
    fun vibrateSample(level: Int) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        v.cancel()
        val effect = Vibe.sample(level, v.hasAmplitudeControl()) ?: return
        v.vibrate(effect)
    }

    val ttsHolder = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) { onDispose { ttsHolder[0]?.shutdown() } }

    fun speakPreview() {
        ttsHolder[0]?.shutdown()
        val text = alarm.copy(
            hour = timeState.hour, minute = timeState.minute,
            greetingText = greetingText, speakTime = speakTime,
        ).spokenText()
        ttsHolder[0] = TextToSpeech(ctx) { status ->
            val engine = ttsHolder[0]
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.setLanguage(Locale("es", "ES"))
                engine.setPitch(pitch / 100f)
                engine.setSpeechRate(rate / 100f)
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    Button(
                        onClick = {
                            stopPreview()
                            val fileOk = sound == Sounds.FILE && soundUri.isNotBlank()
                            onSave(
                                alarm.copy(
                                    hour = timeState.hour, minute = timeState.minute, days = days, enabled = true,
                                    label = label.trim(), rampSeconds = ramp, volumePercent = volume,
                                    snoozeMinutes = snooze,
                                    sound = if (sound == Sounds.FILE && !fileOk) Sounds.DEFAULT else sound,
                                    soundUri = soundUri, soundName = soundName,
                                    vibLevel = vibLevel, vibStartPercent = vibStart,
                                    speakGreeting = greeting,
                                    greetingText = greetingText.trim().ifBlank { "Buenos días" },
                                    speakTime = speakTime, voicePitch = pitch, voiceRate = rate,
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Guardar") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (isNew) "Nueva alarma" else "Editar alarma", fontSize = 26.sp, fontWeight = FontWeight.Light)

            Section("Hora") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = timeState) }
            }

            Section("Repetir") {
                val dayNames = listOf("L", "M", "X", "J", "V", "S", "D")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayNames.forEachIndexed { i, n ->
                        val bit = 1 shl i
                        FilterChip(
                            selected = (days and bit) != 0,
                            onClick = { days = days xor bit },
                            label = { Text(n) },
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = days == Alarm.WEEKDAYS, onClick = { days = Alarm.WEEKDAYS }, label = { Text("Lunes a viernes") })
                    FilterChip(selected = days == Alarm.ALL_DAYS, onClick = { days = Alarm.ALL_DAYS }, label = { Text("Todos los días") })
                    FilterChip(selected = days == 0, onClick = { days = 0 }, label = { Text("Solo una vez") })
                }
                Hint(Schedule.daysSummary(days))
            }

            Section("Nombre") {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Etiqueta (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("Sonido") {
                Sounds.presets.forEach { info ->
                    SoundRow(
                        title = info.name,
                        subtitle = info.description,
                        selected = sound == info.id,
                        playing = previewId == info.id,
                        onSelect = { sound = info.id },
                        onPlay = { if (previewId == info.id) stopPreview() else startPreview(info.id) },
                    )
                }
                val hasFile = soundUri.isNotBlank()
                val playFile: () -> Unit = {
                    if (previewId == Sounds.FILE) stopPreview() else startPreview(Sounds.FILE)
                }
                SoundRow(
                    title = "Mi archivo",
                    subtitle = if (hasFile) soundName.ifBlank { "Archivo elegido" } else "Una canción o un audio de tu móvil.",
                    selected = sound == Sounds.FILE,
                    playing = previewId == Sounds.FILE,
                    onSelect = { if (hasFile) sound = Sounds.FILE else picker.launch(arrayOf("audio/*")) },
                    onPlay = if (hasFile) playFile else null,
                    pickLabel = if (hasFile) "Cambiar" else "Elegir",
                    onPick = { picker.launch(arrayOf("audio/*")) },
                )
                Hint(
                    "La prueba dura 20 s con la subida acelerada y suena al volumen final que elijas más abajo. " +
                        "Tu archivo también empieza casi en silencio y sube igual; si no se pudiera abrir, sonaría «Amanecer» para que nunca falle."
                )
            }

            Section("Subida y volumen") {
                Text("Subida suave: ${fmtDuration(ramp)}", fontWeight = FontWeight.Medium)
                Slider(
                    value = ramp.toFloat(),
                    onValueChange = { ramp = ((it / 5f).roundToInt() * 5).coerceIn(10, 600) },
                    valueRange = 10f..600f,
                )
                Hint("Empieza casi en silencio y sube hasta el volumen final. 1 minuto es lo recomendado; más largo es más gradual.")

                Text("Volumen final: $volume %", fontWeight = FontWeight.Medium)
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { volume = it.roundToInt().coerceIn(10, 100) },
                    valueRange = 10f..100f,
                )
                Hint("Es el volumen de alarma del móvil al que llega al final de la subida.")
            }

            Section("Vibración") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Vibe.names.forEachIndexed { level, name ->
                        FilterChip(
                            selected = vibLevel == level,
                            onClick = { vibLevel = level; vibrateSample(level) },
                            label = { Text(name) },
                        )
                    }
                }
                if (vibLevel > 0) {
                    val startText = when (vibStart) {
                        0 -> "desde el principio"
                        100 -> "al final de la subida"
                        else -> "cuando el sonido va por el $vibStart %"
                    }
                    Text("Empieza $startText", fontWeight = FontWeight.Medium)
                    Slider(
                        value = vibStart.toFloat(),
                        onValueChange = { vibStart = ((it / 10f).roundToInt() * 10).coerceIn(0, 100) },
                        valueRange = 0f..100f,
                    )
                }
                Hint("Al tocar un nivel, el móvil vibra una vez para que notes cómo es. Son golpecitos con pausas largas, no un zumbido continuo.")
            }

            Section("Posponer") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 9, 10, 15, 20).forEach { m ->
                        FilterChip(
                            selected = snooze == m,
                            onClick = { snooze = m },
                            label = { Text(if (m == 0) "Sin posponer" else "$m min") },
                        )
                    }
                }
                Hint("La alarma no se detiene sola: sigue sonando hasta que pulses Parar" + if (snooze > 0) " o Posponer." else ".")
            }

            Section("Voz") {
                SwitchRow("Decir un saludo con voz", greeting) { greeting = it }
                if (greeting) {
                    OutlinedTextField(
                        value = greetingText,
                        onValueChange = { greetingText = it },
                        label = { Text("Qué dice") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Añadir la hora («Son las 5 y 30»)", speakTime) { speakTime = it }
                    Text("Tono de la voz: $pitch %", fontWeight = FontWeight.Medium)
                    Slider(
                        value = pitch.toFloat(),
                        onValueChange = { pitch = ((it / 5f).roundToInt() * 5).coerceIn(60, 140) },
                        valueRange = 60f..140f,
                    )
                    Text("Velocidad de la voz: $rate %", fontWeight = FontWeight.Medium)
                    Slider(
                        value = rate.toFloat(),
                        onValueChange = { rate = ((it / 5f).roundToInt() * 5).coerceIn(50, 130) },
                        valueRange = 50f..130f,
                    )
                    OutlinedButton(onClick = { speakPreview() }, modifier = Modifier.fillMaxWidth()) {
                        Text("▶ Escuchar la voz")
                    }
                    Hint("La voz entra cuando la subida va por el 85 % y se repite cada pocos segundos hasta que pares la alarma.")
                }
            }

            if (!isNew) {
                TextButton(onClick = { stopPreview(); onDelete() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Eliminar alarma")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
