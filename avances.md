# Avances del Proyecto: Alexa Voice Assistant

**Fecha de corte:** 22 de junio de 2026  
**Autor:** Kurt / Claude Code  
**Repositorios:**  
- Android: `github.com/cahdz41/alexa_android`  
- Laptop: `D:\voice-assistant\` (local)

---

## Resumen General

Asistente de voz personal estilo Alexa que funciona en dos plataformas:

1. **Android** — App nativa Kotlin con Jetpack Compose, wake word detection, y sesiones Gemini Live vía Firebase AI.
2. **Laptop (Windows)** — Servicio Python que corre en background con system tray, wake word detection, y sesiones Gemini Live vía google-genai SDK.

Ambas plataformas comparten la misma arquitectura conceptual:
```
Wake Word Detection → Sesión Gemini Live (audio bidireccional) → Tool Calling → Vuelta a escuchar
```

---

## Plataforma: Android

### Estado actualizado - trabajo del 22 de junio de 2026 por la tarde/noche

**Enfoque de la sesion:** arreglar Spotify en Android, mejorar wake word, mejorar UI de estados y evitar que la sesion Gemini quede enganchada.

#### Cambios realizados en el repo Android local

Ruta del repo: `D:\proyectos\alexa_android`

Archivos modificados:
- `app/build.gradle.kts`
- `app/src/main/java/com/cahdz/alexa/tools/SpotifyTools.kt`
- `app/src/main/java/com/cahdz/alexa/voice/GeminiLiveSession.kt`
- `app/src/main/java/com/cahdz/alexa/service/WakeWordService.kt`
- `app/src/main/java/com/cahdz/alexa/wakeword/WakeWordDetector.kt`
- `app/src/main/java/com/cahdz/alexa/ui/AssistantScreen.kt`

#### Spotify Android

- Se agrego soporte real de Spotify App Remote SDK.
- El AAR ya esta presente en `D:\proyectos\alexa_android\app\libs\spotify-app-remote-release-0.8.0.aar`.
- `SpotifyTools.kt` ya no debe depender de intents/broadcasts para reproducir.
- La implementacion actual usa directamente:
  - `SpotifyAppRemote.connect(...)`
  - `remote.playerApi.play(uri)`
  - `pause()`, `resume()`, `skipNext()`, `skipPrevious()`
- Se agrego busqueda via Spotify Web API para convertir la peticion del usuario en URI real de Spotify (`spotify:track:...`, `spotify:album:...`, `spotify:playlist:...`).
- Se agregaron variables de Spotify a `BuildConfig` leyendo desde `local.properties`:
  - `SPOTIFY_CLIENT_ID`
  - `SPOTIFY_CLIENT_SECRET`
  - `SPOTIFY_REDIRECT_URI`
- Prueba mas reciente: pedir una cancion si reprodujo bien automaticamente.

Config requerida en `D:\proyectos\alexa_android\local.properties`:

```properties
SPOTIFY_CLIENT_ID=...
SPOTIFY_CLIENT_SECRET=...
SPOTIFY_REDIRECT_URI=com.cahdz.alexa://spotify-auth
```

En Spotify Developer Dashboard se uso:
- Package name: `com.cahdz.alexa`
- Redirect URI: `com.cahdz.alexa://spotify-auth`
- SHA-1 debug: `CA:0F:83:1F:EF:92:4D:46:76:0D:E7:62:FE:9D:33:DE:4B:2C:36:EC`

#### Wake word Android

- El threshold se fue ajustando durante pruebas: `0.30` -> `0.25` -> `0.22` -> `0.15` -> `0.10`.
- Estado actual en codigo: `WakeWordDetector.kt` usa threshold `0.10`.
- Con `0.10`, la deteccion inicial mejoro.
- Ultima prueba reportada: detecto la palabra clave, pero al cuarto intento, despues de no hablar, la sesion quedo enganchada.
- Pendiente para manana: confirmar si el nuevo cierre de sesion corrige ese caso.

#### UI Android

- Se reemplazo la UI simple por una interfaz Compose mas clara con estados:
  - `IDLE`: inactiva.
  - `LISTENING`: "Esperando que digas \"Alexa\"" (modo wake word).
  - `SESSION_ACTIVE`: "Palabra clave detectada. Habla ahora".
  - `USER_SPEAKING`: "Te estoy escuchando".
  - `THINKING`: "Procesando tu solicitud".
  - `SPEAKING`: "Respondiendo".
- Se agregaron patrones visuales:
  - Sin animacion fuerte en modo wake word para bajar CPU.
  - Ondas durante `USER_SPEAKING`.
  - Arcos/orbitas durante `THINKING`.
  - Arco azul durante `SPEAKING`.
- Se redujo la animacion permanente porque los logs mostraron alto consumo/render a 60fps y podia afectar la deteccion.

#### Sesion Gemini / cierre automatico

- Problema observado en logs:
  - La app detectaba wake word.
  - Entraba a sesion Gemini.
  - Si el usuario no hablaba, aparecia `Idle timeout (15s) - closing session`.
  - Pero no aparecia `Gemini session closed` ni `Session ended - listening again`.
  - Conclusion: el watchdog disparaba cierre, pero la sesion podia quedar bloqueada y no regresar a wake word.
- Cambios realizados:
  - Se agrego `requestClose(reason)` en `GeminiLiveSession`.
  - `requestClose()` ahora completa `sessionDone`, para destrabar el `run()` padre.
  - `session.close()` en `finally` tiene timeout de `1.5s` usando `withTimeoutOrNull`.
  - `stop()` manual usa `requestClose("Manual stop")`.
  - Idle timeout usa `requestClose(...)`.
  - Comandos de Spotify fuerzan cierre de sesion despues de responder la tool.
  - `WakeWordService` ahora tiene `SESSION_SAFETY_TIMEOUT_MS = 18_000L`: si la sesion no vuelve sola, fuerza stop, cancela el job y reinicia wake word.
  - `WakeWordDetector.start()` ahora evita arrancar dos detectores si ya esta escuchando.

#### Prueba pendiente para manana

Probar primero sin musica:

1. Abrir app Android.
2. Activar escucha.
3. Decir "Alexa".
4. No decir nada mas.
5. Esperar 18-20 segundos.
6. Resultado esperado: debe volver a `Esperando que digas "Alexa"` y reiniciar wake word.

Despues probar Spotify:

1. Decir "Alexa".
2. Pedir una cancion en Spotify.
3. Confirmar que reproduce automaticamente.
4. Confirmar que Alexa vuelve a modo wake word despues de ejecutar la tool.
5. Para pausar musica: decir otra vez "Alexa" y luego "pausa Spotify".

#### Comando de verificacion usado

PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
D:\proyectos\alexa_android\gradlew.bat -p D:\proyectos\alexa_android :app:compileDebugKotlin
```

Ultima compilacion: `BUILD SUCCESSFUL`.

#### Riesgos / pendientes inmediatos

- Confirmar si `SESSION_SAFETY_TIMEOUT_MS` realmente devuelve la app a wake word cuando Firebase Live se queda bloqueado.
- Si wake word con threshold `0.10` genera falsos positivos, probar `0.12` o `0.13`.
- Si sigue costando detectar wake word, revisar volumen del microfono / modelo `alexa_v0.1.onnx` / posible entrenamiento de modelo personalizado.
- Si Spotify abre busqueda pero no reproduce, revisar logs buscando `Spotify play failed` o errores de `SpotifyAppRemote.connect`.
- Firebase AppCheck sigue sin configurar; aparecen warnings `No AppCheckProvider installed`, por ahora no bloquean pero conviene resolverlo mas adelante.

### Archivos del proyecto (11 archivos Kotlin)

| Archivo | Descripción |
|---------|-------------|
| `AlexaApp.kt` | Application class, inicializa Firebase y crea notification channels |
| `MainActivity.kt` | Activity principal con Compose UI, bind al servicio |
| `AssistantScreen.kt` | UI — botón de micrófono animado con estados visuales |
| `WakeWordService.kt` | Foreground service que maneja wake word + sesiones Gemini |
| `WakeWordDetector.kt` | Wrapper de openWakeWord (librería ONNX) |
| `GeminiLiveSession.kt` | Sesión bidireccional de audio con Gemini Live API |
| `ToolRegistry.kt` | Registro central de herramientas y function declarations |
| `SpotifyTools.kt` | Control de Spotify vía intents |
| `YouTubeTools.kt` | Apertura de YouTube vía intents |
| `AlarmTools.kt` | Alarmas y recordatorios con AlarmManager + SharedPreferences |
| `Theme.kt` | Tema de Material 3 |

### Lo que YA funciona en Android

- **Wake word detection** — Dice "Alexa" y el detector dispara con threshold 0.3. Usa modelo ONNX local (`alexa_v0.1.onnx`, ~800KB). No requiere internet.
- **Sesión Gemini Live** — Audio bidireccional con modelo `gemini-3.1-flash-live-preview`. Voz Aoede en español mexicano.
- **Barge-in** — Puedes interrumpir a Gemini mientras habla. Limpia el buffer de audio y retoma escucha.
- **Tool calling** — Gemini puede llamar herramientas durante la conversación y recibe respuestas.
- **Idle timeout** — La sesión se cierra automáticamente tras 12s de inactividad post-habla, 15s si no se ha hablado, o 5 min máximo.
- **Model speaking watchdog** — Si Gemini se queda "hablando" sin enviar audio por 5s, fuerza reset a idle.
- **Echo cancellation** — Usa AcousticEchoCanceler del sistema para evitar que Gemini se escuche a sí mismo.
- **Full-screen intent** — Cuando detecta wake word con la app en background, usa full-screen intent (como llamadas telefónicas) para traerla al frente y evitar restricciones BAL de Android 12+.
- **Service lifecycle** — `WakeWordService` es foreground service con `stopWithTask=false`, sigue corriendo al minimizar la app.
- **Notification channels** — Tres canales: escucha (low), sesión activa (high), alarmas (high).
- **UI animada** — Botón de micrófono con pulse ring que cambia de color según estado (idle/listening/session active).
- **Spotify** — Abre búsquedas en Spotify vía intent.
- **YouTube** — Abre búsquedas en YouTube vía intent (app nativa o navegador como fallback).
- **Alarmas/Recordatorios** — Programa alarmas con `AlarmManager`, parsea tiempo relativo ("5 minutos") y absoluto ("7:30"), persiste en SharedPreferences, dispara notificación + vibración + sonido.
- **Package visibility** — Declaraciones `<queries>` para Spotify y YouTube en manifest.
- **Permisos** — Mic, foreground service, notifications, internet, vibrate, exact alarm, wake lock, full screen intent, system alert window.

### Lo que NO funciona bien / Pendiente en Android

- **Spotify control avanzado** — Solo abre búsquedas. No puede pausar/resumir/next/previous de verdad. Los broadcast intents (`PLAY_pause`, `PLAY_next`, etc.) probablemente no funcionan en Android 12+ por restricciones de broadcasts implícitos. `setVolume()` y `currentTrack()` retornan "pendiente" — necesitan App Remote SDK (AAR en `libs/`).
- **AlarmReceiver ringtone** — El `Ringtone` que se reproduce en `onReceive()` nunca se detiene. No hay referencia guardada ni timeout. Puede sonar indefinidamente.
- **Session event → UI state** — El callback `onSessionEvent` en `onWakeWordDetected()` solo hace `Log.d`. Los estados `THINKING` y `SPEAKING` nunca se reflejan en la UI (siempre muestra `SESSION_ACTIVE`).
- **Reconexión automática** — Si la sesión Gemini cae, no hay retry. Simplemente vuelve a modo escucha.
- **Búsqueda web / Google Search** — No está integrada como tool en Android. En laptop usa `GoogleSearch()` grounding nativo, pero en Android via Firebase AI aún no se tiene.
- **Transcripción visible** — Las transcripciones (input/output) solo se loguean. No hay UI que muestre qué dijo el usuario ni qué respondió Gemini.
- **Crash al minimizar** — Reportado en pruebas del 19 de junio. Se agregó `stopWithTask=false` y full-screen intent, pero no se ha confirmado 100% resuelto.
- **Firebase AppCheck** — No configurado. Puede causar problemas de autenticación con la API de Gemini Live.

---

## Plataforma: Laptop (Windows)

### Archivos del proyecto (16 archivos Python)

| Archivo | Descripción |
|---------|-------------|
| `main.py` | Entry point — orquesta wake word → sesión → tools → loop |
| `config.py` | Carga `.env` con python-dotenv |
| `wake_word/detector.py` | Wrapper openWakeWord con PyAudio |
| `voice/gemini_live.py` | Sesión Gemini Live con google-genai SDK |
| `voice/audio_input.py` | `MicStream` — captura 16kHz mono async |
| `voice/audio_output.py` | `AudioPlayer` — reproduce 24kHz PCM en thread |
| `tools/registry.py` | Registro central + FunctionDeclarations para Gemini |
| `tools/spotify.py` | Spotipy (OAuth) — búsqueda, play, pause, next, volume, current track |
| `tools/youtube.py` | yt-dlp + DIAL/SSDP para cast a Chromecast/Smart TV |
| `tools/alarms.py` | Alarmas con scheduler background, TTS local (pyttsx3), sonido WAV |
| `service/tray.py` | Ícono en system tray con pystray (idle/listening/speaking) |
| `service/logger.py` | Logging rotativo a archivo |

### Lo que YA funciona en Laptop

- **Wake word detection** — openWakeWord con modelo "hey jarvis" (o alexa). Threshold configurable via `.env`. Consume ~1-5% CPU.
- **Sesión Gemini Live completa** — Audio bidireccional con `gemini-3.1-flash-live-preview` vía API key directa. Voz Kore, idioma es-MX.
- **VAD avanzado** — Configuración de Activity Detection del servidor: sensibilidad alta para inicio/fin de habla, 1000ms de silencio, 200ms de padding, interrupción por actividad.
- **Barge-in** — Interrupción natural. Limpia buffer de audio.
- **Echo tail** — 300ms de mute después de que Gemini termina de hablar para evitar eco.
- **Tool calling robusto** — Soporta handlers sync y async. Maneja errores por tool y envía respuestas individuales.
- **Google Search grounding** — Integrado como tool nativo de Gemini. Puede buscar clima, noticias, datos actuales sin herramienta custom.
- **Spotify completo** — Vía Spotipy con OAuth. Buscar y reproducir tracks/playlists/albums, pausar, reanudar, siguiente, anterior, volumen (0-100), info de track actual. Detecta dispositivo activo automáticamente. Si no hay dispositivo, abre Spotify en el navegador.
- **YouTube con Cast** — Busca videos con yt-dlp. Puede enviar a Chromecast/Smart TV/Android TV vía protocolo DIAL/SSDP, o abrir en navegador local. Descubrimiento de dispositivos en red con caché de 2 minutos.
- **Alarmas y recordatorios** — Scheduler en background thread. Sonido WAV en loop. TTS local con voz Sabina (es-MX) para anunciar la alarma. Persistencia en JSON. Dismiss por voz ("apaga la alarma").
- **System tray** — Ícono con colores por estado (gris=idle, verde=listening, naranja=processing, azul=speaking). Menú con opción "Salir".
- **Retry automático** — Si la sesión cae, reintenta hasta 3 veces con 5s de delay.
- **Idle timeout** — 12s post-habla, 15s inicial, 5 min máximo de sesión.
- **Sonido de activación** — Reproduce `activate.wav` al iniciar sesión Gemini.
- **Logging** — Rotativo a archivo + consola. Nivel configurable via `.env`.
- **Transcripción** — Input y output transcritos y logueados en tiempo real.

### Lo que NO funciona bien / Pendiente en Laptop

- **Task Scheduler** — Los scripts `install_task.ps1` / `uninstall_task.ps1` para inicio automático al login aún no están creados.
- **Tray icon dice "Jarvis"** — `tray.py` todavía tiene el nombre "Jarvis Voice Assistant" hardcodeado en el menú y título. Debería decir "Alexa".
- **`_receive()` privado** — `gemini_live.py:203` usa `session._receive()` que es un método privado del SDK. Podría romperse en actualizaciones de google-genai.
- **Diferencia de voz** — Laptop usa voz "Kore" y Android usa "Aoede". Deberían ser consistentes.
- **Sin UI visual** — Solo tray icon. No hay ventana con transcripción ni historial de conversación.
- **pyttsx3 thread safety** — El engine de TTS se crea y destruye en cada llamada `_speak()`. En alarmas simultáneas podría haber conflictos.

---

## Comparativa: Android vs Laptop

| Feature | Android | Laptop |
|---------|---------|--------|
| Wake word | openWakeWord (Kotlin lib) | openWakeWord (Python) |
| Modelo Gemini | gemini-3.1-flash-live-preview | gemini-3.1-flash-live-preview |
| Voz | Aoede | Kore |
| SDK | Firebase AI (Kotlin) | google-genai (Python) |
| Spotify | Intents (básico) | Spotipy OAuth (completo) |
| YouTube | Intents (app/browser) | yt-dlp + DIAL Cast |
| Alarmas | AlarmManager + notificación | Background scheduler + TTS + WAV |
| Google Search | No integrado | Grounding nativo |
| UI | Compose (botón animado) | System tray icon |
| Retry | No | 3 intentos con 5s delay |
| Cast | No | DIAL/SSDP a Chromecast/TV |
| VAD config | Server default | High sensitivity + custom config |
| Transcripción | Solo logs | Solo logs |
| Persistencia alarmas | SharedPreferences | JSON file |
| Sonido activación | No | activate.wav |
| Echo cancellation | AcousticEchoCanceler (hardware) | Echo tail (software, 300ms) |

---

## Stack Tecnológico

### Android
- Kotlin + Jetpack Compose + Material 3
- Firebase AI SDK (Gemini Live)
- openWakeWord Android (ONNX Runtime)
- compileSdk 35, minSdk 26, targetSdk 35
- Gradle Kotlin DSL

### Laptop
- Python 3.11+
- google-genai SDK 2.8+
- openWakeWord + ONNX Runtime
- PyAudio (mic + speaker)
- Spotipy 2.24+ (Spotify OAuth)
- yt-dlp (YouTube)
- pystray + Pillow (system tray)
- pyttsx3 (TTS local para alarmas)
- requests (DIAL/SSDP)

---

## Próximos pasos sugeridos

### Prioridad alta
1. **Android: Integrar Spotify App Remote SDK** — Para control real de playback (pause, next, volume, current track)
2. **Android: Fix AlarmReceiver** — Agregar timeout al ringtone y guardar referencia para poder detenerlo
3. **Android: Propagar session events a UI** — Que THINKING/SPEAKING se reflejen en la pantalla
4. **Laptop: Crear scripts de Task Scheduler** — Para inicio automático al login

### Prioridad media
5. **Unificar voz** — Decidir Aoede o Kore y usar la misma en ambas plataformas
6. **Android: Agregar Google Search grounding** — Investigar si Firebase AI lo soporta en sesiones Live
7. **Android: Agregar retry** — Reintentar sesión si cae inesperadamente
8. **Laptop: Renombrar "Jarvis" → "Alexa"** en tray icon
9. **Laptop: Reemplazar `_receive()`** — Usar API pública del SDK para recibir mensajes

### Prioridad baja
10. **UI de transcripción** — Mostrar qué dijo el usuario y qué respondió Gemini (ambas plataformas)
11. **Android: Firebase AppCheck** — Configurar para producción
12. **Laptop: VAD config en Android** — Portar la configuración avanzada de VAD al cliente Android
13. **Sonido de activación en Android** — Reproducir un tono al detectar wake word

---

## Historial de cambios importantes

| Fecha | Cambio |
|-------|--------|
| Jun 15, 2026 | Plan original del proyecto laptop |
| Jun 17, 2026 | Picovoice → openWakeWord (Picovoice eliminó free tier) |
| Jun 17, 2026 | Gemini 2.5 Flash → 3.1 Flash Live (modelo anterior deprecado) |
| Jun 17, 2026 | NSSM → Task Scheduler para servicio en Windows |
| Jun 19, 2026 | Proyecto Android inicializado y subido a GitHub |
| Jun 19, 2026 | Refactor completo de GeminiLiveSession con manual audio streaming |
| Jun 19, 2026 | Integración de tools: Spotify, YouTube, Alarmas en Android |
| Jun 19, 2026 | Full-screen intent para bypass de BAL en Android 12+ |
| Jun 19, 2026 | Reducción de threshold de wake word de 0.5 → 0.3 |
| Jun 19, 2026 | Cambio de voz de Kore → Aoede en Android |
| Jun 19, 2026 | Upgrade de modelo Gemini de 2.5-flash → 3.1-flash-live-preview en Android |
| Jun 22, 2026 | Commit y push a GitHub con todos los cambios acumulados |
