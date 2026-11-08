package com.babelong.asistentechino

import android.content.Context
import com.babelong.asistentechino.data.SessionRepository
import com.babelong.asistentechino.data.SettingsRepository

/**
 * Contenedor de dependencias mínimo para v1: crea los repositorios una vez con
 * el contexto de la aplicación. Si el proyecto crece, se puede sustituir por
 * Hilt/Koin sin cambiar el resto del código (solo aquí).
 */
object AppGraph {

    lateinit var sessions: SessionRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    @Volatile
    private var initialized = false

    fun init(appContext: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            sessions = SessionRepository(appContext)
            settings = SettingsRepository(appContext)
            ClassController.init(appContext)
            initialized = true
        }
    }
}
