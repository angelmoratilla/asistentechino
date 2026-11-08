package com.babelong.asistentechino.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babelong.asistentechino.data.TranscriptLine
import com.babelong.asistentechino.pinyin.PinyinConverter
import com.babelong.asistentechino.ui.theme.AmberPinyin
import com.babelong.asistentechino.ui.theme.Tone1Color
import com.babelong.asistentechino.ui.theme.Tone2Color
import com.babelong.asistentechino.ui.theme.Tone3Color
import com.babelong.asistentechino.ui.theme.Tone4Color
import com.babelong.asistentechino.ui.theme.Tone5Color

/**
 * Lista de transcripción compartida por la pantalla en vivo y el detalle del
 * historial.
 *
 * [autoScroll] solo debe activarse en la pantalla en vivo: baja al final con
 * cada línea nueva SOLO si el usuario está ya mirando el final — si ha subido
 * a releer algo, no le roba el sitio hasta que vuelve al final.
 *
 * [onLineClick] (detalle con audio) se llama al tocar una línea.
 */
@Composable
fun TranscriptList(
    lines: List<TranscriptLine>,
    partial: String,
    fontSizeSp: Int,
    showPinyin: Boolean,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
    coloredPinyin: Boolean = false,
    onLineClick: ((TranscriptLine) -> Unit)? = null,
    onToggleImportant: ((TranscriptLine) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // ¿Está el usuario mirando el final de la lista (con margen de 2 líneas)?
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size, partial, showPinyin, fontSizeSp) {
        if (autoScroll && nearBottom) {
            val last = lines.size + (if (partial.isNotEmpty()) 1 else 0)
            if (last > 0) listState.animateScrollToItem(last - 1)
        }
    }
    LazyColumn(state = listState, modifier = modifier) {
        if (lines.isEmpty() && partial.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Pulsa «Empezar a escuchar» y lo que diga el profesor irá apareciendo " +
                        "aquí en chino, con su pinyin debajo.",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 32.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
        items(lines) { line ->
            val lineModifier = if (onLineClick != null) {
                Modifier.clickable { onLineClick(line) }
            } else {
                Modifier
            }
            LineItem(line, fontSizeSp, showPinyin, coloredPinyin, lineModifier, onToggleImportant)
        }
        if (partial.isNotEmpty()) {
            item(key = "partial") {
                Text(
                    text = "$partial ▍",
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.45f).sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LineItem(
    line: TranscriptLine,
    fontSizeSp: Int,
    showPinyin: Boolean,
    coloredPinyin: Boolean = false,
    modifier: Modifier = Modifier,
    onToggleImportant: ((TranscriptLine) -> Unit)? = null,
) {
    Row(modifier = modifier) {
        if (onToggleImportant != null) {
            IconButton(
                onClick = { onToggleImportant(line) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (line.important) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (line.important) "Quitar de importantes" else "Marcar como importante",
                    tint = if (line.important) AmberPinyin else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = line.clockLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // Las líneas largas se parten en fragmentos legibles (solo presentación).
            val segments = remember(line.text) { TextSplitter.split(line.text) }
            segments.forEach { segment ->
                Text(
                    text = segment,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.45f).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (showPinyin) {
                    val pinyin = remember(segment) { PinyinConverter.toPinyin(segment) }
                    if (pinyin.isNotBlank() && pinyin != segment) {
                        if (coloredPinyin) {
                            Text(
                                text = coloredPinyinText(pinyin),
                                fontSize = (fontSizeSp * 0.6f).coerceAtLeast(12f).sp,
                                lineHeight = (fontSizeSp * 0.9f).sp,
                                fontStyle = FontStyle.Italic,
                            )
                        } else {
                            Text(
                                text = pinyin,
                                fontSize = (fontSizeSp * 0.6f).coerceAtLeast(12f).sp,
                                lineHeight = (fontSizeSp * 0.9f).sp,
                                color = AmberPinyin,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Pinyin coloreado por tono: 1º rojo, 2º verde, 3º azul, 4º morado, 5º gris. */
private fun coloredPinyinText(pinyin: String): AnnotatedString = buildAnnotatedString {
    val palette = listOf(Tone1Color, Tone2Color, Tone3Color, Tone4Color, Tone5Color)
    val syllables = pinyin.split(" ")
    syllables.forEachIndexed { index, syllable ->
        if (index > 0) append(' ')
        if (syllable.isEmpty()) return@forEachIndexed
        pushStyle(SpanStyle(color = palette[PinyinConverter.toneOf(syllable) - 1]))
        append(syllable)
        pop()
    }
}
