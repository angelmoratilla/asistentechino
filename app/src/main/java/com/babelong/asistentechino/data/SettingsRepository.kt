package com.babelong.asistentechino.data

import android.content.Context
import android.content.SharedPreferences

/** Ajustes de la app, persistidos en SharedPreferences. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Mostrar el pinyin debajo de cada línea transcrita. */
    var pinyinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PINYIN, true)
        set(value) = prefs.edit().putBoolean(KEY_PINYIN, value).apply()

    /** Mantener la pantalla encendida durante la clase. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()

    /** Silenciar el pitido del reconocedor de Google. */
    var muteBeep: Boolean
        get() = prefs.getBoolean(KEY_MUTE_BEEP, true)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_BEEP, value).apply()

    /** Grabar el audio de la clase junto a la transcripción. */
    var recordAudio: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_RECORD_AUDIO, value).apply()

    /** Borrar el audio de clases con más de N días (0 = nunca). */
    var cleanAudioDays: Int
        get() = prefs.getInt(KEY_CLEAN_AUDIO_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_CLEAN_AUDIO_DAYS, value).apply()

    /** Tema: "dark" (por defecto) o "light". */
    var theme: String
        get() = prefs.getString(KEY_THEME, null) ?: "dark"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** Motor de reconocimiento: "online" o "ondevice". */
    var engineId: String
        get() = prefs.getString(KEY_ENGINE, null) ?: "online"
        set(value) = prefs.edit().putString(KEY_ENGINE, value).apply()

    /** Pinyin coloreado por tonos. */
    var coloredPinyin: Boolean
        get() = prefs.getBoolean(KEY_COLORED_PINYIN, false)
        set(value) = prefs.edit().putBoolean(KEY_COLORED_PINYIN, value).apply()

    /** Tamaño de la letra de la transcripción, en sp (18–48). */
    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 28)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(18, 48)).apply()

    /** Idioma del profesor: "zh-CN" (simplificado) o "zh-TW" (tradicional). */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: "zh-CN"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    private companion object {
        const val KEY_PINYIN = "pinyin"
        const val KEY_SCREEN_ON = "pantalla_siempre_activa"
        const val KEY_MUTE_BEEP = "silenciar_pitido"
        const val KEY_RECORD_AUDIO = "grabar_audio"
        const val KEY_CLEAN_AUDIO_DAYS = "borrar_audio_dias"
        const val KEY_THEME = "tema"
        const val KEY_ENGINE = "motor_voz"
        const val KEY_COLORED_PINYIN = "pinyin_color"
        const val KEY_FONT_SIZE = "tamano_fuente"
        const val KEY_LANGUAGE = "idioma"
    }
}
