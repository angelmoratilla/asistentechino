# Asistente Chino

App Android para **transcribir sobre la marcha lo que dice el profesor durante una clase de chino**.
La transcripción aparece en caracteres chinos en tiempo real, con su **pinyin con tonos** debajo,
y cada clase queda guardada para poder releerla, copiarla o compartirla después.

## Qué hace (v1)

- **Escucha continua**: reinicia automáticamente el reconocedor para aguantar una clase entera
  (el servicio nativo se corta cada pocos segundos; esta app lo relanza sin que notes nada).
- **⭐ Marca frases importantes sobre la marcha**: un botón ★ marca la última frase dicha
  (y otra estrella en cada línea permite marcar cualquiera anterior); en el detalle hay un
  filtro «solo ⭐» para repasarlas, y las copias/exportaciones las llevan marcadas.
- **Sobrevive a la pantalla apagada**: un servicio en primer plano de tipo micrófono mantiene
  la escucha y la grabación aunque bloquees el móvil o cambies de app, con notificación
  permanente y botón «Parar». La app te ofrecerá salir de la optimización de batería
  (en MIUI/Xiaomi es importante aceptarla).
- **Transcripción en vivo** con línea parcial (lo que está interpretando ahora) en gris e
  hipótesis confirmadas con su marca de tiempo. El auto-scroll te deja releer: si subes en
  la lista, no te baja solo hasta que vuelves al final.
- **Pinyin con tonos** (nǐ hǎo) calculado en el propio móvil, sin conexión.
- **Grabación del audio de la clase** (m4a AAC mono, ~43 MB/hora): en el detalle de cada
  sesión tienes un reproductor y puedes **tocar cualquier línea para oír ese momento** de la
  clase. Grabar se puede desactivar en Ajustes.
- **Historial de clases**: cada sesión se guarda sola (autoguardado línea a línea, si el móvil
  se apaga no se pierde lo dicho), con relectura, copiar al portapapeles y compartir (.txt).
- **Pensada para el aula**: tema oscuro, pantalla siempre encendida (opcional),
  tamaño de letra ajustable (botones A− / A+ y slider), contador de 字 y cronómetro.
- **Idioma cambiable al vuelo**: chino simplificado (zh-CN), tradicional (zh-TW) o español
  (es-ES) — toca el chip cuando el profesor cambie de idioma y sigue la misma sesión.
- **Dos motores de voz**: «Google en línea» (por defecto) y «En el dispositivo» (sin red y
  sin pitidos, si tu móvil soporta el idioma; si no, la app revierte sola avisando).
- **Búsqueda en el historial** (por texto de la transcripción, título o fecha) y **títulos
  editables** por clase (icono ✏️ en el detalle).
- **Líneas largas partidas en fragmentos legibles** y **pinyin coloreado por tonos**
  (opcional: 1º rojo, 2º verde, 3º azul, 4º morado).
- **Tema claro u oscuro** a elección, y **borrado automático del audio antiguo**
  (nunca se borra la transcripción).
- Opción para **silenciar el pitido** que el servicio de Google emite al reconocer.

## Motor de reconocimiento (y cómo crecer en v2)

v1 usa el reconocedor nativo de Android (`android.speech.SpeechRecognizer`) con servicios de
Google: **gratis, sin API key** y con buena calidad para chino. Requiere conexión a internet
y que el móvil tenga los servicios de Google (cualquier Android estándar en España los tiene).

La arquitectura está preparada para añadir otros motores sin tocar la UI:

1. Implementa `SpeechEngine` (en `app/src/main/java/com/babelong/asistentechino/speech/`).
2. Regístralo en `SpeechEngineFactory.EngineId` y en su `create()`.

Ideas para la v2 (en el orden en que aportan más):

- **Diccionario al toque** (CC-CEDICT offline): pinyin por palabra y significado al tocar un
  carácter, con vocabulario guardado y export a Anki.
- **Pulido con IA** después de clase: puntuación, corrección de homófonos y traducción vía
  un LLM con tu propia clave API.
- **Traducción al español** bajo demanda (ML Kit offline, ~60 MB de modelos).
- **Motor offline** (Vosk o sherpa-onnx) para clases sin conexión o móviles sin Google;
  habilitaría además la detección automática de idioma.

## Privacidad y grabación de audio

La transcripción va al servicio de Google (como al dictar con el teclado). Si activas la
grabación, el audio de la clase queda en el propio móvil, en la carpeta de la app, y no se
envía a ningún sitio. Si vas a grabar a otras personas, avísales antes — grabar conversaciones
sin consentimiento no es correcto (y en España tiene restricciones legales).

## Estructura del código

```
app/src/main/java/com/babelong/asistentechino/
├── MainActivity.kt            # Activity única + navegación (en vivo / historial / detalle)
├── ClassController.kt         # Cerebro de la sesión (motor, grabación, autoguardado):
│                              #   vive en el proceso, sobrevive a la UI
├── ClassService.kt            # Servicio en primer plano (micrófono) + notificación
├── MainViewModel.kt           # Capa fina de UI sobre el controlador + ajustes
├── AppGraph.kt                # Contenedor de dependencias mínimo
├── speech/
│   ├── SpeechEngine.kt        # Interfaz del motor + tipos (EngineState, FinalSegment…)
│   ├── GoogleSpeechEngine.kt  # SpeechRecognizer nativo con reinicio continuo y backoff
│   └── SpeechEngineFactory.kt # Punto de extensión para motores v2
├── pinyin/PinyinConverter.kt  # Hanzi → pinyin tonal (pinyin4j, offline)
├── data/
│   ├── Session.kt             # TranscriptLine + Session (+ audio)
│   ├── SessionRepository.kt   # Guardado en .txt/.m4a (clases/), listar, borrar, exportar
│   └── SettingsRepository.kt  # Ajustes (pinyin, letra, pantalla, pitido, audio, idioma)
└── ui/
    ├── LiveScreen.kt          # Pantalla principal de clase
    ├── HistoryScreen.kt       # Historial
    ├── SessionDetailScreen.kt # Reproductor + lectura + copiar/compartir/borrar
    ├── TranscriptList.kt      # Lista de transcripción compartida (auto-scroll respetuoso)
    └── theme/Theme.kt         # Tema oscuro
```

Las transcripciones se guardan en `Android/data/com.babelong.asistentechino/files/clases/`
como `.txt` legibles (`mm:ss → texto`, con cabecera de fecha e idioma; las frases que
marques como importantes llevan prefijo `★`) y, si se grabó, el audio como
`<mismo-nombre>.m4a`.

## Cómo compilar

### Con Android Studio (recomendado)

1. Abre la carpeta del proyecto en Android Studio (File → Open).
2. Conecta tu móvil con la depuración USB activada.
3. Pulsa ▶ Run. La app se instalará y arrancará.

### Por línea de comandos (necesita JDK 17 y Android SDK)

```bash
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # tests unitarios (pinyin y formato de sesiones)
```

Si el SDK no está en `~/Android/Sdk`, ajusta `local.properties` (`sdk.dir=...`).

## Cómo probarla en tu móvil

1. Instala la APK (si es una `debug` y vienes de fuera de Play Store, acepta
   «instalar de orígenes desconocidos»).
2. Abre la app y concede el **permiso de micrófono** cuando lo pida.
3. Pulsa **Empezar a escuchar** y di en chino: «你好，我是学生» — deberías ver los
   caracteres aparecer (primero en gris, luego confirmados) con su pinyin debajo.
4. Habla en frases normales: el indicador ↻ muestra cuántas veces se ha relanzado
   el reconocedor (es normal que suba durante la clase).
5. Pulsa **Parar y guardar**, abre el historial y revisa la sesión: cambia el pinyin
   (icono 拼), cópiala o compártela.
6. En Ajustes prueba el tamaño de letra, quitar el pinyin y el idioma tradicional.

**Consejo de uso en clase**: pon el móvil en la mesa cerca del profesor, con la
pantalla siempre encendida activada. El micrófono del móvil capta mejor cuanto
menos ruido de fondo haya; si el aula es muy grande, no esperes milagros con
voces lejanas — la calidad depende mucho de la distancia.

## Limitaciones conocidas (v1)

- **Grabar audio + reconocer a la vez** choca en algunos Xiaomi (MIUI se queda con el
  micrófono para nuestra grabación y el servicio de Google se queda sin señal). La app lo
  detecta (45 s sin actividad del reconocedor) y **suelta la grabación automáticamente**
  avisando — la transcripción es prioritaria. La convivencia real llegará con el motor
  offline de la v2 (un único dueño del micrófono: la propia app). Mientras tanto, si quieres
  el audio de una clase concreta, la app **Grabadora** del propio móvil en paralelo funciona
  sin estorbar.
- El reconocimiento «en el dispositivo» de Google no soporta el chino en todos los móviles
  (depende del modelo offline descargable); si no está soportado, la app vuelve sola a
  «Google en línea» avisando.
- La traducción al español y el motor offline quedan para v2 (ver hoja de ruta).
- Si Android **mata el proceso** (memoria extrema, reinicio del móvil), la clase se corta:
  lo transcrito hasta entonces queda guardado, pero no se reanuda solo. Con el servicio en
  primer plano y la optimización de batería desactivada, esto es raro.
- El reconocedor de Google no puntúa el texto chino (sin ，。)；los ficheros se
  guardan línea a línea tal cual se reconoce.
- El pinyin se calcula carácter a carácter; en caracteres polifónicos (多音字)
  usa la lectura más común.
- En Xiaomi/MIUI conviene además activar el «Autoinicio» de la app en Ajustes si
  observaras cortes con la pantalla apagada.

## Requisitos

- Android 8.0 (API 26) o superior.
- Servicios de Google Play (para el reconocimiento) y conexión a internet.
- JDK 17 y Android SDK con plataforma 35 para compilar.
