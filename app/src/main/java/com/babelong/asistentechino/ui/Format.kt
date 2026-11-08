package com.babelong.asistentechino.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Milisegundos → "m:ss" (los minutos no se acotan: 75:03). */
fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Fecha de inicio de una sesión, en formato corto local. */
fun formatSessionDate(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
