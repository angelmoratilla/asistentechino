package com.babelong.asistentechino.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.babelong.asistentechino.AppGraph
import com.babelong.asistentechino.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Detalle de una clase guardada: audio, lectura, copiar, compartir y borrar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<Session?>(null) }
    var showPinyin by remember { mutableStateOf(AppGraph.settings.pinyinEnabled) }
    val coloredPinyin = remember { AppGraph.settings.coloredPinyin }
    var onlyImportant by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var seekToMs by remember { mutableStateOf<Long?>(null) }
    var editTitle by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        session = withContext(Dispatchers.IO) { AppGraph.sessions.load(sessionId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        session?.title
                            ?: session?.let { formatSessionDate(it.startedAtMs) }
                            ?: sessionId,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { onlyImportant = !onlyImportant }) {
                        Icon(
                            imageVector = if (onlyImportant) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Ver solo las frases importantes",
                            tint = if (onlyImportant) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = { editTitle = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Cambiar título")
                    }
                    IconButton(onClick = { showPinyin = !showPinyin }) {
                        Text(
                            "拼",
                            color = if (showPinyin) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val loaded = session
        when {
            loaded == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            loaded.lines.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "Esta sesión no tiene líneas de transcripción.",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            else -> {
                val visibleLines = if (onlyImportant) {
                    loaded.lines.filter { it.important }
                } else {
                    loaded.lines
                }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    loaded.audioFile?.let { audio ->
                        AudioPlayerBar(
                            file = audio,
                            seekToMs = seekToMs,
                            onSeekHandled = { seekToMs = null },
                        )
                    }
                    if (visibleLines.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No hay frases marcadas con ⭐ todavía.\n" +
                                    "Márcalas durante la clase con el botón ★ o tocando " +
                                    "la estrella de cada línea.",
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        TranscriptList(
                            lines = visibleLines,
                            partial = "",
                            fontSizeSp = 26,
                            showPinyin = showPinyin,
                            autoScroll = false,
                            coloredPinyin = coloredPinyin,
                            modifier = Modifier.weight(1f),
                            onLineClick = if (loaded.audioFile != null) {
                                { line -> seekToMs = line.offsetMs }
                            } else {
                                null
                            },
                            onToggleImportant = { line ->
                                val newImportant = !line.important
                                AppGraph.sessions.setImportant(
                                    AppGraph.sessions.fileOf(sessionId),
                                    line.offsetMs,
                                    line.text,
                                    newImportant,
                                )
                                session = loaded.copy(
                                    lines = loaded.lines.map {
                                        if (it.offsetMs == line.offsetMs && it.text == line.text) {
                                            it.copy(important = newImportant)
                                        } else {
                                            it
                                        }
                                    },
                                )
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val compact = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        OutlinedButton(
                            onClick = { copyToClipboard(context, AppGraph.sessions.fullText(loaded)) },
                            modifier = Modifier.weight(1f),
                            contentPadding = compact,
                        ) { Text("Copiar", maxLines = 1) }
                        OutlinedButton(
                            onClick = { shareSession(context, loaded) },
                            modifier = Modifier.weight(1f),
                            contentPadding = compact,
                        ) { Text("Compartir", maxLines = 1) }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = compact,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Borrar", maxLines = 1) }
                    }
                }
            }
        }
    }

    if (editTitle) {
        var text by remember { mutableStateOf(session?.title.orEmpty()) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { editTitle = false },
            title = { Text("Título de la clase") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("P. ej.: Lección 5 · 食物") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editTitle = false
                        AppGraph.sessions.setTitle(sessionId, text)
                        scope.launch {
                            session = withContext(Dispatchers.IO) { AppGraph.sessions.load(sessionId) }
                        }
                    },
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { editTitle = false }) { Text("Cancelar") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar esta clase?") },
            text = { Text("Se eliminarán la transcripción y su audio. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppGraph.sessions.delete(sessionId)
                        confirmDelete = false
                        onBack()
                    },
                ) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

/**
 * Reproductor minimalista del audio de la clase. [seekToMs] permite saltar
 * desde las líneas de la transcripción (tocar una línea = oír ese momento).
 */
@Composable
private fun AudioPlayerBar(
    file: File,
    seekToMs: Long?,
    onSeekHandled: () -> Unit,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(file) {
        val prepared = withContext(Dispatchers.IO) {
            try {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                }
            } catch (_: Exception) {
                null
            }
        }
        player = prepared
        durationMs = prepared?.duration ?: 0
        prepared?.setOnCompletionListener {
            playing = false
            positionMs = durationMs
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    // Reloj de posición mientras suena.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.currentPosition ?: 0
            delay(300)
        }
    }

    // Salto desde una línea de la transcripción.
    LaunchedEffect(seekToMs) {
        val target = seekToMs ?: return@LaunchedEffect
        player?.let { p ->
            val clamped = target.coerceIn(0L, durationMs.toLong())
            p.seekTo(clamped.toInt())
            positionMs = clamped.toInt()
            if (!p.isPlaying) {
                p.start()
                playing = true
            }
        }
        onSeekHandled()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    playing = false
                } else {
                    p.start()
                    playing = true
                }
            }
        }) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pausar" else "Reproducir",
            )
        }
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { value ->
                positionMs = value.toInt()
                player?.seekTo(value.toInt())
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${formatClock(positionMs.toLong())} / ${formatClock(durationMs.toLong())}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
    }
}

private fun shareSession(context: Context, session: Session) {
    val file = AppGraph.sessions.fileOf(session.id)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Transcripción de clase · ${formatSessionDate(session.startedAtMs)}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir transcripción"))
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("transcripción", text))
}
