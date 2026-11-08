package com.babelong.asistentechino.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babelong.asistentechino.AppGraph
import com.babelong.asistentechino.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Historial de clases guardadas, con búsqueda por texto, título o fecha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { AppGraph.sessions.listSessions() }
    }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) {
            sessions
        } else {
            val q = query.trim()
            sessions.filter { s ->
                s.title?.contains(q, ignoreCase = true) == true ||
                    formatSessionDate(s.startedAtMs).contains(q, ignoreCase = true) ||
                    s.lines.any { it.text.contains(q, ignoreCase = true) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de clases") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Aún no hay clases guardadas.\n" +
                        "Graba tu primera clase desde la pantalla principal.",
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().padding(padding),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Buscar en las transcripciones…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Borrar búsqueda")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Ninguna clase coincide con «$query».",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered) { session ->
                            ListItem(
                                headlineContent = {
                                    Text(session.title ?: formatSessionDate(session.startedAtMs))
                                },
                                supportingContent = {
                                    Text(
                                        (if (session.title != null) formatSessionDate(session.startedAtMs) + " · " else "") +
                                            "${session.lines.size} líneas · ${session.charCount} 字 · " +
                                            formatClock(session.durationMs) +
                                            (if (session.audioFile != null) " · con audio" else "") +
                                            "\n${session.preview}",
                                    )
                                },
                                modifier = Modifier.clickable { onOpenSession(session.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
