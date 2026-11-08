package com.babelong.asistentechino.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.babelong.asistentechino.LiveUiState
import com.babelong.asistentechino.MainViewModel
import com.babelong.asistentechino.ui.theme.AmberPinyin

/** Pantalla principal: escucha en vivo durante la clase. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    onOpenHistory: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var settingsOpen by remember { mutableStateOf(false) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var batteryCardDismissed by remember { mutableStateOf(false) }

    // Micrófono + notificación permanente del servicio (Android 13+).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Si solo se pedía el permiso de notificaciones, no tocar hasPermission.
        grants[Manifest.permission.RECORD_AUDIO]?.let { granted -> hasPermission = granted }
        if (hasPermission) viewModel.startListening()
    }

    // Mantener la pantalla encendida mientras la clase está activa (si el ajuste lo permite).
    DisposableEffect(ui.isListening, ui.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = ui.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asistente Chino") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Historial")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            LanguageToggle(language = ui.language, onLanguage = viewModel::setLanguage)
            Spacer(Modifier.height(8.dp))
            StatusRow(
                ui = ui,
                onFontMinus = { viewModel.setFontSizeSp(ui.fontSizeSp - 2) },
                onFontPlus = { viewModel.setFontSizeSp(ui.fontSizeSp + 2) },
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StartStopButton(
                    listening = ui.isListening,
                    enabled = ui.isListening || ui.recognitionAvailable,
                    onStart = {
                        val needNotifications =
                            android.os.Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                        when {
                            !hasPermission -> permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                            needNotifications -> permissionLauncher.launch(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            )
                            else -> viewModel.startListening()
                        }
                    },
                    onStop = viewModel::stopListening,
                    modifier = Modifier.weight(1f),
                )
                // Marcar la última frase confirmada como importante (un toque).
                val lastLine = ui.lines.lastOrNull()
                IconButton(
                    onClick = viewModel::toggleImportantLast,
                    enabled = lastLine != null,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (lastLine?.important == true) {
                            Icons.Filled.Star
                        } else {
                            Icons.Outlined.StarBorder
                        },
                        contentDescription = "Marcar la última frase como importante",
                        tint = if (lastLine?.important == true) {
                            AmberPinyin
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            ui.fatalMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                MessageCard(
                    message = message,
                    onDismiss = viewModel::clearFatalMessage,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            ui.infoMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                MessageCard(
                    message = message,
                    onDismiss = viewModel::clearInfoMessage,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // MIUI y otros capas matan apps en segundo plano: pedir la exención
            // de optimización de batería la primera vez que se escucha.
            if (ui.isListening && !ui.batteryIgnored && !batteryCardDismissed) {
                Spacer(Modifier.height(8.dp))
                BatteryCard(
                    onAllow = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        )
                        viewModel.refreshBatteryState()
                    },
                    onDismiss = { batteryCardDismissed = true },
                )
            }

            Spacer(Modifier.height(8.dp))
            TranscriptList(
                lines = ui.lines,
                partial = ui.partial,
                fontSizeSp = ui.fontSizeSp,
                showPinyin = ui.pinyinEnabled,
                coloredPinyin = ui.coloredPinyin,
                modifier = Modifier.fillMaxSize(),
                onToggleImportant = { line ->
                    viewModel.setImportant(line.offsetMs, line.text, !line.important)
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            ui = ui,
            onPinyin = viewModel::setPinyinEnabled,
            onFontSize = viewModel::setFontSizeSp,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onMuteBeep = viewModel::setMuteBeep,
            onRecordAudio = viewModel::setRecordAudio,
            onCleanAudioDays = viewModel::setCleanAudioDays,
            onEngineId = viewModel::setEngineId,
            onColoredPinyin = viewModel::setColoredPinyin,
            onTheme = viewModel::setTheme,
            onLanguage = viewModel::setLanguage,
            onDismiss = { settingsOpen = false },
        )
    }
}

/**
 * Selector rápido de idioma: funciona también mientras se escucha (el motor se
 * reinicia con el idioma nuevo sin perder la sesión). Útil cuando el profesor
 * pasa al español a explicar algo.
 */
@Composable
private fun LanguageToggle(language: String, onLanguage: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = language == "zh-CN",
            onClick = { onLanguage("zh-CN") },
            label = { Text("中文", fontSize = 18.sp) },
        )
        FilterChip(
            selected = language == "zh-TW",
            onClick = { onLanguage("zh-TW") },
            label = { Text("繁體", fontSize = 18.sp) },
        )
        FilterChip(
            selected = language == "es-ES",
            onClick = { onLanguage("es-ES") },
            label = { Text("Español") },
        )
    }
}

@Composable
private fun StatusRow(
    ui: LiveUiState,
    onFontMinus: () -> Unit,
    onFontPlus: () -> Unit,
) {
    val statusStyle = MaterialTheme.typography.labelLarge
    // Dos filas compactas: así no desborda ni con REC y contador activos.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (ui.isListening) {
                PulsingDot()
                Spacer(Modifier.width(6.dp))
                Text(
                    "Escuchando…",
                    style = statusStyle,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (ui.recording) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "● REC",
                        style = statusStyle,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            } else {
                Text("En pausa", style = statusStyle, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                formatClock(ui.elapsedMs),
                style = statusStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val chars = ui.lines.sumOf { it.text.length }
            Text("$chars 字", style = statusStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (ui.restartCount > 0) {
                Spacer(Modifier.width(10.dp))
                Text("↻ ${ui.restartCount}", style = statusStyle, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onFontMinus) { Text("A−", fontSize = 14.sp, maxLines = 1) }
            Text("${ui.fontSizeSp}", style = statusStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            IconButton(onClick = onFontPlus) { Text("A+", fontSize = 14.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}

@Composable
private fun StartStopButton(
    listening: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!listening) {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = modifier.height(64.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Empezar a escuchar", fontSize = 18.sp)
        }
    } else {
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = modifier.height(64.dp),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Parar y guardar", fontSize = 18.sp)
        }
    }
}

@Composable
private fun BatteryCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Para que la escucha no se corte con la pantalla apagada, " +
                    "permite que la app ignore la optimización de batería.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onAllow) { Text("Permitir") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: String,
    onDismiss: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), color = content)
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    ui: LiveUiState,
    onPinyin: (Boolean) -> Unit,
    onFontSize: (Int) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onMuteBeep: (Boolean) -> Unit,
    onRecordAudio: (Boolean) -> Unit,
    onCleanAudioDays: (Int) -> Unit,
    onEngineId: (String) -> Unit,
    onColoredPinyin: (Boolean) -> Unit,
    onTheme: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)

            SettingSwitch(
                title = "Mostrar pinyin",
                subtitle = "Muestra el pinyin con tonos debajo de cada línea",
                checked = ui.pinyinEnabled,
                onChange = onPinyin,
            )

            Column {
                Text("Tamaño de letra: ${ui.fontSizeSp} sp")
                Slider(
                    value = ui.fontSizeSp.toFloat(),
                    onValueChange = { onFontSize(it.toInt()) },
                    valueRange = 18f..48f,
                    steps = 14,
                )
            }

            SettingSwitch(
                title = "Pantalla siempre encendida",
                subtitle = "Evita que el móvil se bloquee mientras escuchas",
                checked = ui.keepScreenOn,
                onChange = onKeepScreenOn,
            )

            SettingSwitch(
                title = "Silenciar pitido",
                subtitle = "Silencia los pitidos de Google mientras escucha " +
                    "(también oirás en silencio tu música durante la clase)",
                checked = ui.muteBeep,
                onChange = onMuteBeep,
            )

            SettingSwitch(
                title = "Grabar audio de la clase",
                subtitle = "Guarda el sonido (m4a) para reescucharlo junto a la transcripción. " +
                    "En algunos Xiaomi, si al grabar dejara de transcribirse, la app suelta " +
                    "sola la grabación para no perder ni una palabra. Se aplica a la próxima clase.",
                checked = ui.recordAudio,
                onChange = onRecordAudio,
            )

            Column {
                Text("Borrar audio de clases antiguas")
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    val options = listOf(0 to "Nunca", 7 to "7 días", 30 to "30 días", 90 to "90 días")
                    options.forEach { (days, label) ->
                        FilterChip(
                            selected = ui.cleanAudioDays == days,
                            onClick = { onCleanAudioDays(days) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "El texto de la transcripción nunca se borra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Column {
                Text("Motor de voz")
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = ui.engineId == "online",
                        onClick = { onEngineId("online") },
                        label = { Text("Google en línea") },
                    )
                    FilterChip(
                        selected = ui.engineId == "ondevice",
                        onClick = { onEngineId("ondevice") },
                        label = { Text("En el dispositivo") },
                        enabled = ui.onDeviceAvailable,
                    )
                    FilterChip(
                        selected = ui.engineId == "offline",
                        onClick = { onEngineId("offline") },
                        label = { Text("Offline (beta)") },
                    )
                }
                Text(
                    when {
                        ui.engineId == "offline" ->
                            "«Offline»: sin red y sin pitidos, graba y transcribe a la vez. " +
                                "Solo chino de momento (beta)."
                        ui.onDeviceAvailable ->
                            "«En el dispositivo»: sin pitidos y sin conexión, pero algo menos preciso."
                        else ->
                            "«En el dispositivo» no está disponible en este móvil (requiere Android 12+ " +
                                "y el modelo offline de Google)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            SettingSwitch(
                title = "Pinyin a color",
                subtitle = "Colorea cada sílaba por su tono (1º rojo, 2º verde, 3º azul, 4º morado)",
                checked = ui.coloredPinyin,
                onChange = onColoredPinyin,
            )

            Column {
                Text("Tema")
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = ui.theme == "dark",
                        onClick = { onTheme("dark") },
                        label = { Text("Oscuro") },
                    )
                    FilterChip(
                        selected = ui.theme == "light",
                        onClick = { onTheme("light") },
                        label = { Text("Claro") },
                    )
                }
            }

            Column {
                Text("Idioma del profesor")
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = ui.language == "zh-CN",
                        onClick = { onLanguage("zh-CN") },
                        label = { Text("中文 Simplificado") },
                    )
                    FilterChip(
                        selected = ui.language == "zh-TW",
                        onClick = { onLanguage("zh-TW") },
                        label = { Text("繁體 Tradicional") },
                    )
                    FilterChip(
                        selected = ui.language == "es-ES",
                        onClick = { onLanguage("es-ES") },
                        label = { Text("Español") },
                    )
                }
                Text(
                    "Se puede cambiar en cualquier momento, incluso escuchando: " +
                        "toca Español cuando el profesor explique en español y 中文 para volver.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
