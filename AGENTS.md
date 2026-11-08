# AGENTS.md — Memoria del proyecto «Asistente Chino»

> Lee esto antes de tocar nada: aquí está el qué, el cómo y — lo más valioso —
> los trucos y trampas descubiertos probando en el móvil real.

## Qué es

App Android para **transcribir en vivo lo que dice el profesor en una clase de chino**,
con pinyin tonal, historial por clases, grabación de audio reescuchable y cambio de
idioma al vuelo (中/繁/Español). Uso personal del dueño del móvil (alumno de chino en
España). UI en español. Todo gratis, sin API keys.

- **Paquete**: `com.babelong.asistentechino` (el usuario quiere paquetes de su dominio
  `babelong.com`, escritos en orden inverso convencional).
- **Stack**: Kotlin 2.0.21 + Jetpack Compose (Material 3), minSdk 26, target/compile 35,
  AGP 8.7.3, Gradle 8.9. Se compila siempre como **debug** (al usuario no le importa la
  release firmada por ahora).

## Entorno de este equipo (importante)

- **Git/GitHub**: remoto `origin` = `git@github.com:angelmoratilla/asistentechino.git`
  (rama `main`, identidad local del repo: Angel Moratilla `<angelmoratilla@gmail.com>`).
  `.zcode/` está excluido en `.gitignore` (planes internos de la herramienta).

- Proyecto: `/home/angel/almacen/trabajo/ZAI/asistenteChino` (`almacen` es symlink a `/media/almacen`).
- **JDK 17**: `~/tools/jdk17` (Temurin, descargado a mano; el Java del sistema es 11 y NO sirve).
- **Gradle 8.9**: `~/tools/gradle89/gradle-8.9/bin/gradle` (el wrapper del proyecto ya funciona).
- **Android SDK**: `~/Android/Sdk` (licencias aceptadas; plataforma 35 instalada a mano).
- **adb**: `~/Android/Sdk/platform-tools/adb`.
- **Móvil de pruebas**: Xiaomi 15 Ultra (2506BPN68G, HyperOS/Android 15, pantalla
  1280x2772) conectado por USB con depuración.

### Comandos

```bash
# compilar + tests (SIEMPRE con JAVA_HOME; si filtras la salida con grep/pipe,
# verifica tú el exit code — ya nos ha pasado instalar una APK vieja por esto)
JAVA_HOME=/home/angel/tools/jdk17 ANDROID_HOME=/home/angel/Android/Sdk ./gradlew assembleDebug testDebugUnitTest

# instalar y lanzar en el móvil
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb shell am start -n com.babelong.asistentechino/.MainActivity
```

La APK entregable se copia a `apk/AsistenteChino-v1.0-debug.apk`.

### ⚠️ Protocolo de pruebas de sonido (preferencia del usuario)

El usuario escucha MÚSICA mientras el agente trabaja. **AVISAR SIEMPRE antes de
reproducir sonido** (TTS por los altavoces del PC) para que pare la música, por dos
motivos: 1) no interferir con su música, y 2) el micro del móvil capta los altavoces
del PC — la música arruinaría la prueba de transcripción. Además, iniciar sesiones
con el motor de Google silencia la música del móvil (mute anti-pitidos).

### Probar reconocimiento sin hablar (TTS por los altavoces del PC)

```bash
# frase china ~11 s (la URL es el TTS no oficial de Google Translate, va bien)
curl -s -A "Mozilla/5.0" -o /tmp/frase.mp3 "https://translate.google.com/translate_tts?ie=UTF-8&q=<urlencoded>&tl=zh-CN&client=tw-ob"
mpv --really-quiet --volume=100 /tmp/frase.mp3   # el móvil escucha por el micro
# logs del motor: adb logcat -s SpeechEngine ClassController
```

### Automatización de UI en el móvil (lecciones aprendidas)

- **NUNCA reutilices coordenadas de toques antiguas**: el layout cambia con el estado.
  Siempre `adb shell uiautomator dump /sdcard/ui.xml` → extraer bounds → tap, todo en el
  mismo comando, y verificar el efecto en `logcat -s SpeechEngine ClassController`.
- Mi estimación visual de capturas es poco fiable: fiarse de uiautomator, no del ojo.
- `adb shell input text` pasa por el teclado chino del móvil: convierte letras en hanzi
  (escribió "C拉瑟" en vez de "Clase"). Sirve para ASCII sencillo; para chino, mejor no.
- Las prefs **string se guardan como contenido de texto** (`<string name="x">valor</string>`),
  NO con `value=`: grepear `value=` da falsos negativos. Además `apply()` es asíncrono:
  espera o haz force-stop antes de leer el fichero.
- Para tocar switches/chips del sheet: buscar por texto/content-desc en el dump.

## Arquitectura

```
app/src/main/java/com/babelong/asistentechino/
├── MainActivity.kt            # NavHost: live / history / detail/{id}; tema según ajustes
├── ClassController.kt         # ⭐ cerebro en el proceso (singleton): posee los motores,
│                              #   la grabación, el autoguardado, el reloj y el monitor
│                              #   de inanición. La clase SOBREVIVE a la UI.
├── ClassService.kt            # Servicio en primer plano tipo micrófono + notificación con «Parar»
├── MainViewModel.kt           # Capa fina sobre ClassController; ajustes; reversión de idioma/motor
├── AppGraph.kt                # Contenedor mínimo (sessions, settings) + init de ClassController
├── speech/
│   ├── SpeechEngine.kt        # Interfaz + EngineState/FinalSegment/RecognitionConfig
│   ├── GoogleSpeechEngine.kt  # SpeechRecognizer con reinicio continuo, watchdog, mute, backoff.
│   │                          #   Flag onDevice=true → createOnDeviceSpeechRecognizer (API 31+)
│   └── SpeechEngineFactory.kt # Enum de motores + prefValue ("online"/"ondevice"); v2 añade Vosk/cloud
├── pinyin/PinyinConverter.kt  # pinyin4j con tonos + fixThirdTone + toneOf() para colores
├── data/
│   ├── Session.kt             # TranscriptLine (mm:ss) + Session (+título, +audioFile)
│   ├── SessionRepository.kt   # Ficheros clases/*.txt (+ .m4a); setTitle; cleanOldAudio; parse puro
│   └── SettingsRepository.kt  # prefs: pinyin, fuente, pantalla, pitido, grabar, borrar-audio,
│                              #   tema, motor, pinyin-color, idioma
└── ui/
    ├── LiveScreen.kt          # Escucha en vivo + hoja de Ajustes (con verticalScroll!)
    ├── HistoryScreen.kt       # Historial + buscador
    ├── SessionDetailScreen.kt # Reproductor + título (✏️) + copiar/compartir/borrar
    ├── TranscriptList.kt      # Lista compartida; auto-scroll que respeta al lector;
    │                          #   líneas largas partidas; pinyin color
    ├── TextSplitter.kt        # split() puro para líneas largas (testeado)
    └── theme/Theme.kt         # Esquemas oscuro (defecto) y claro + colores de tono
```

**Formato de sesión** (`Android/data/com.babelong.asistentechino/files/clases/`):
`.txt` con cabecera `# AsistenteChino v1` / `# inicio=` / `# idioma=` / `# titulo=`,
líneas `mm:ss\ttexto`, pie `# fin=`; audio hermano `<mismo-nombre>.m4a`.

## ⚠️ Trampas descubiertas en el móvil real (no "optimizaciones": son el conocimiento)

1. **Parciales de Google intercalan VACÍOS**: el servicio emite `onPartial len=0` entre
   hipótesis. Hay que CONSERVAR el último parcial no vacío o el texto parpadea hasta
   desaparecer (fue el bug "no aparece nada hasta la pausa").
2. **Pitidos**: el chime suena en CADA `startListening` (cada reinicio) y según el
   dispositivo va por STREAM_MUSIC o STREAM_SYSTEM. Solución: silenciar ambos flujos
   TODA la sesión y despertarlos en stop() siempre (incondicional). El flag persistente
   `flujos_silenciados_por_sesion` + `GoogleSpeechEngine.restoreLatchedMute()` en
   `ClassController.init()` reparan el mute "pegado" si el proceso muere a mitad de clase.
3. **Cambiar de idioma/motor en vivo**: pausa de **400 ms** entre stop() y start() —
   reconectar inmediato = `ERROR_SERVER_DISCONNECTED` (11) en cascada.
4. **Errores de servidor** (4, 2, 11, 10, 1): recrear reconocedor + backoff largo (2-8 s,
   hasta 10 intentos). Otros: 6 intentos con 300 ms-2 s. Códigos nuevos mapeados en
   `errorMessage()` (11=desconectado, 12=idioma no soportado, 13=no disponible, 10=rate limit).
5. **Conflicto de micro en MIUI**: nuestra MediaRecorder (probar MIC y VOICE_RECOGNITION)
   AHOGA al reconocedor de Google recién reiniciado (le llega silencio; ni errores ni
   parciales). → **Monitor de inanición**: >45 s sin ningún callback ⇒ soltar la grabación,
   recrear el reconocedor y avisar (la transcripción es prioritaria). La convivencia real
   llegará con el motor offline v2 (un solo dueño del micro).
6. **Watchdog de vida**: sin `onReadyForSpeech` en 12 s ⇒ recrear. Un servicio de voz
   colgado se ve como "escucha sin resultados jamás". El proceso que hostea el
   RecognitionService es **com.google.android.tts** (Speech Services); `adb shell am
   force-stop com.google.android.tts` lo desatasca, pero la app ya se cura sola.
7. **On-device** (`createOnDeviceSpeechRecognizer`): en este móvil zh-CN da código 12
   (sin modelo offline). La app revierte sola a "online" avisando. Podría funcionar con
   es-ES u otros idiomas con modelo descargado.
8. **pinyin4j** genera 3er tono con breve (ĭ U+012D) en vez de carón (ǐ U+01D0):
   normalizado en `fixThirdTone`. También: polyfónicos → primera lectura.
8b. **Marcar importantes en vivo**: el offset en memoria tiene MILISEGUNDOS (13.847 ms)
   pero el reloj del fichero es de segundos (00:13 → 13.000). `applyImportantMark`
   compara a precisión de segundo (`offsetMs / 1000 * 1000`) o nunca cuadraría en vivo
   (los tests unitarios pasaban por usar valores alineados: añadir regresión 13_847).
9. **Sesión por delante del motor**: el audio se arranca y el offset temporal lo calcula
   ClassController con timestamps absolutos (los reinicios no descolocan la línea temporal).
10. En MIUI pedir la exención de optimización de batería (la app lo ofrece al escuchar);
    es clave para el FGS con pantalla apagada.

## Estado actual (08/11/2026, noche)

- **v1 completa + Fase 0 + frases importantes**: el usuario probó la app EN CLASE REAL
  ("funciona bastante bien") y pidió marcar frases. Implementado y VERIFICADO en el móvil
  de punta a punta: botón ★ (marca última frase) + estrella por línea + filtro «solo ⭐»
  en el detalle + export con prefijo ★; persistido como prefijo `★` en el .txt
  (`00:13\t★大家好…`), toggle idempotente, cabecera/pie intactos.
- Tests: 21 unitarios en verde (incluida regresión de precisión ms en applyImportantMark).
- **Fase 1 spike VERIFICADO en el móvil** (rama develop): motor offline sherpa-onnx
  v1.13.8 + modelo zipformer2-CTC-zh int8 (21 MB en assets) — parciales en vivo, frase
  final autoguardada, y grabación m4a simultánea en el mismo MIUI que ayer era imposible.
  Calidad vs Google en la misma frase TTS: idéntica salvo 第10页/第十页.
- **A/B con voz humana (08/11 noche)**: el usuario leyó un párrafo de referencia
  (61 caracteres, pronunciación de estudiante) con cada motor. Resultado:
  **Google 77% (47/61) vs Offline 69% (42/61)**. Análisis: ~la mitad de los errores
  del offline son HOMÓFONOS (得/的, 听/盯 tīng, 北/被, 工/公 — sonido correcto,
  carácter distinto) → el homophone replacer / LM los arreglaría; varios errores
  salieron en AMBOS motores (快→怪 kuài/guài, 我们 perdido) → pronunciación del
  lector, no del motor. Con el pinyin debajo, el offline es utilizable para
  seguir la clase. Nota de proceso: el A/B con Google falló dos veces por el
  conflicto de micro (grabación ON ahoga a Google en MIUI) — con grabación OFF
  funcionó; se bajó STARVATION_TIMEOUT a 15 s y un push de prefs roto por stdin
  VACIÓ el fichero de ajustes (restaurar ajustes por UI, no por fichero).
  Pendiente de pulir antes de fusionar a main: validar precisión con voz humana real
  (no solo TTS), quitar los modos debug cuando ya no hagan falta, y decidir el fluxo
  del ajuste de grabación con el motor offline.
- El usuario pidió trabajar en rama `develop` (main = v1 congelada); Fase 1 commiteada allí.
- La app está instalada como debug; reinstalar con `-r` conserva datos.

## Hoja de ruta acordada

- **Fase 1 (siguiente, el gran bloque)**: motor offline propio (Vosk/sherpa-onnx) — la app
  dueña del `AudioRecord` alimenta reconocedor + grabación + análisis. Desbloquea:
  grabación+transcripción sin conflicto, sin pitidos, sin conexión, detección automática
  de idioma. Coste: +15-25 MB APK y +40-150 MB de modelo. Validar precisión comparando
  con Google sobre grabaciones reales.
- **Fase 2**: diccionario al toque (CC-CEDICT, +4-10 MB APK) con vocabulario guardado;
  diarización profesor/alumnos (sherpa speaker embedding, +25-30 MB); export Anki.
- **Fase 3**: "pulir con IA" post-clase (puntuación, homófonos, traducción vía LLM con API
  key del usuario); traducción ES bajo demanda con ML Kit offline (+60 MB).
- **Fase 4 (opcional)**: motor cloud premium (Google Cloud/Azure con diarización nativa,
  ~1-2 €/clase) — hueco ya previsto en `SpeechEngineFactory`.
- Descartado por ahora: APK release firmada (el usuario la pospuso).
