```markdown
# Plan de Implementación: Asistente de Voz para Laptop
**Proyecto:** voice-assistant — CHOCHOLAND-CENTRO, `D:\voice-assistant\`
**Fecha:** 2026-06-17 (actualizado)
**Stack:** Python 3.11+, Gemini 3.1 Flash Live, openWakeWord, Spotipy, yt-dlp, Task Scheduler

---

## Estructura de Archivos Propuesta

```
D:\voice-assistant\
├── .env                        # API keys (no va al repo)
├── requirements.txt
├── README.md
│
├── src\
│   ├── main.py                 # Entry point
│   ├── config.py               # Carga de .env y constantes
│   │
│   ├── wake_word\
│   │   ├── __init__.py
│   │   ├── detector.py         # Wrapper openWakeWord
│   │   └── models\             # Archivos .onnx / .tflite del wake word
│   │       └── hey_jarvis.onnx
│   │
│   ├── voice\
│   │   ├── __init__.py
│   │   ├── gemini_live.py      # Cliente Gemini 3.1 Flash Live
│   │   ├── audio_input.py      # Captura de mic vía PyAudio
│   │   └── audio_output.py     # Reproducción de respuestas de audio
│   │
│   ├── tools\
│   │   ├── __init__.py
│   │   ├── registry.py         # Registro central de tools para Gemini
│   │   ├── web_search.py       # Gemini con Google Search grounding
│   │   ├── youtube.py          # yt-dlp + reproducción local
│   │   └── spotify.py          # Spotipy + Spotify Connect
│   │
│   └── service\
│       ├── __init__.py
│       └── logger.py           # Logging a archivo rotativo
│
├── logs\
│   └── voice-assistant.log
│
└── scripts\
    ├── auth_spotify.py         # OAuth flow one-time para Spotify
    ├── test_mic.py             # Prueba de captura de audio
    ├── install_task.ps1        # Crea tarea en Task Scheduler
    └── uninstall_task.ps1      # Remueve tarea de Task Scheduler
```

---

## Fases y Tareas

### Fase 0 — Setup del Entorno

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-01 | Crear directorio `D:\voice-assistant\` y estructura de carpetas | XS | — |
| T-02 | Crear `venv` con Python 3.11 en `D:\voice-assistant\.venv\` | XS | T-01 |
| T-03 | Escribir `requirements.txt` con todas las dependencias | XS | T-02 |
| T-04 | Instalar dependencias vía pip (`pip install -r requirements.txt`) | S | T-03 |
| T-05 | Crear `.env` con placeholders para todas las API keys | XS | T-01 |
| T-06 | Crear `src\config.py` que cargue `.env` con `python-dotenv` y exponga constantes | XS | T-05 |

**Dependencias externas a obtener en T-05/T-06:**
- `GEMINI_API_KEY` — Google AI Studio
- `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` / `SPOTIFY_REDIRECT_URI` — Spotify Developer Dashboard

**Nota:** openWakeWord no requiere API key — es completamente offline y gratuito (Apache 2.0).

---

### Fase 1 — Wake Word Detection

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-07 | Descargar/entrenar modelo `.onnx` para wake word con openWakeWord (usar modelo pre-entrenado "hey jarvis" o entrenar uno custom en español vía Colab notebook) | S | T-04 |
| T-08 | Implementar `wake_word/detector.py`: clase `WakeWordDetector` con `openwakeword` que lee mic con PyAudio en chunks de 1280 samples (16kHz, mono, int16) y emite callback al detectar wake word con score > threshold | M | T-04, T-07 |
| T-09 | Crear `scripts/test_mic.py` para verificar índice de dispositivo de micrófono en Windows | XS | T-04 |
| T-10 | Probar detector de forma aislada: ejecutar, decir la frase, verificar que el callback dispara. Ajustar `threshold` (0.0–1.0) según falsos positivos/negativos | S | T-08, T-09 |

**Notas T-07:** openWakeWord incluye modelos pre-entrenados ("hey jarvis", "alexa", "hey mycroft", etc.). Para un wake word custom en español, se puede entrenar un modelo en <1 hora usando el Colab notebook oficial con Piper TTS para generar samples sintéticos en español. El modelo resultante es un archivo `.onnx` ligero (~1-5 MB). Soporta 20+ idiomas.

**Notas T-08:** openWakeWord usa ONNX Runtime como backend de inferencia. Consumo: ~1-5% CPU, ~30-50 MB RAM por modelo. Audio requerido: 16kHz, mono, PCM 16-bit, chunks de 1280 samples (80ms).

---

### Fase 2 — Gemini Live Voice Core

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-11 | Implementar `voice/audio_input.py`: clase `MicStream` que abre PyAudio stream de 16kHz mono y produce chunks de audio raw PCM | S | T-04 |
| T-12 | Implementar `voice/audio_output.py`: clase `AudioPlayer` que recibe chunks PCM de Gemini (24kHz por defecto) y los reproduce en tiempo real. Manejar diferencia de sample rate (input 16kHz vs output 24kHz) | S | T-04 |
| T-13 | Implementar `voice/gemini_live.py`: clase `GeminiLiveSession` usando `google-genai` SDK v2.8+, modelo `gemini-3.1-flash-live-preview`, modo `AUDIO` bidireccional, configuración de voz en español (México) con `language_code: "es-MX"`, manejo de turno de habla y fin de sesión | L | T-11, T-12 |
| T-14 | Integrar `GeminiLiveSession` con el callback de `WakeWordDetector`: al detectar wake word → abrir sesión Live → streamear audio → reproducir respuesta → volver a escuchar wake word | M | T-10, T-13 |
| T-15 | Probar conversación básica: wake word → pregunta simple → respuesta hablada | M | T-14 |

**Notas T-13:** Usar el modelo `gemini-3.1-flash-live-preview` (sucesor GA del deprecado `gemini-2.5-flash-preview-native-audio-dialog` que fue removido el 19 marzo 2026). Características del modelo:
- 30 voces HD en 24 idiomas (incluye español MX)
- Affective Dialog: entiende y responde a expresiones emocionales
- Proactive Audio: solo responde cuando es relevante
- Barge-in mejorado: interrupción natural incluso en ambientes ruidosos
- Function calling nativo durante sesiones live
- Google Search grounding integrado
- **Free tier disponible** con límites de quota (suficiente para uso personal)
- Pagado: ~$0.75/1M input tokens, ~$4.50/1M output tokens

Configurar `system_instruction` en español con tono relajado. Manejar correctamente los eventos del stream para detectar cuándo Gemini terminó de hablar.

**Notas T-12:** Gemini Live envía audio de respuesta a 24kHz por defecto. El `AudioPlayer` debe abrir el stream de reproducción a 24kHz o reconfigurar la sesión para output a 16kHz.

---

### Fase 3 — Tool: Búsqueda Web

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-16 | Implementar `tools/web_search.py`: función `search_web(query: str) -> str` que usa Gemini con Google Search grounding para resolver la query y retorna texto plano | M | T-06 |
| T-17 | Crear `tools/registry.py`: lista de `FunctionDeclaration` de Gemini con el esquema de `search_web` | S | T-16 |
| T-18 | Conectar registry a `GeminiLiveSession`: pasar `tools` en la config de la sesión, manejar `tool_call` en el stream, ejecutar la tool, enviar `tool_response` de vuelta | M | T-15, T-17 |
| T-19 | Probar búsqueda: "¿Cuál es el clima en Querétaro hoy?" debe retornar respuesta hablada con datos actuales | S | T-18 |

**Notas T-16:** Google Search grounding es un tool nativo del Live API. Se puede agregar como `types.Tool(google_search=types.GoogleSearch())` en la config de la sesión para que el modelo lo invoque automáticamente cuando necesite información actual.

---

### Fase 4 — Tool: YouTube

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-20 | Implementar `tools/youtube.py`: función `play_youtube(query: str)` que usa `yt-dlp` (v2026.06+) para buscar el video más relevante, extrae URL de audio, y lo reproduce localmente con `ffplay` vía `subprocess` | M | T-04 |
| T-21 | Registrar `play_youtube` en `tools/registry.py` con su `FunctionDeclaration` | XS | T-20 |
| T-22 | Integrar en la sesión y probar: "Pon la canción X en YouTube" debe reproducir audio | M | T-18, T-21 |

**Notas T-20:** yt-dlp se actualiza con frecuencia diaria y soporta 1800+ sitios. En Windows, combinado con `ffplay` (incluido en ffmpeg) es la ruta más simple. Asegurar que `ffmpeg` esté en PATH. Siempre usar la versión más reciente de yt-dlp.

---

### Fase 5 — Tool: Spotify

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-23 | Crear `scripts/auth_spotify.py`: flujo OAuth PKCE una sola vez, guarda token en `D:\voice-assistant\.spotify_cache` | M | T-04, T-06 |
| T-24 | Ejecutar `auth_spotify.py` manualmente para obtener token inicial | XS | T-23 |
| T-25 | Implementar `tools/spotify.py` con las funciones: `play_spotify(query)`, `pause_spotify()`, `next_track()`, `set_volume(level)` usando `spotipy.Spotify` con token cacheado | M | T-24 |
| T-26 | Registrar todas las funciones de Spotify en `tools/registry.py` | S | T-25 |
| T-27 | Integrar y probar: "Pon playlist de jazz en Spotify", "Sube el volumen", "Siguiente canción" | M | T-18, T-26 |

**Notas T-23:** Usar `spotipy.oauth2.SpotifyPKCE` con scope `user-modify-playback-state user-read-playback-state`. El token se renueva automáticamente por Spotipy (v2.24+).

---

### Fase 6 — Inicio Automático con Task Scheduler

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-28 | Implementar `service/logger.py`: `RotatingFileHandler` que escribe en `D:\voice-assistant\logs\` con nivel configurable desde `.env` | XS | T-06 |
| T-29 | Implementar `src/main.py`: entry point que instancia `WakeWordDetector` y el pipeline completo, con manejo de señales de stop (SIGTERM/SIGINT) para shutdown limpio | S | T-15, T-28 |
| T-30 | Escribir `scripts/install_task.ps1`: script PowerShell que crea una tarea en Task Scheduler con trigger "At logon" bajo la cuenta del usuario actual, apuntando a `.venv\Scripts\python.exe src\main.py`, con política de reinicio automático en caso de fallo | S | T-29 |
| T-31 | Escribir `scripts/uninstall_task.ps1` para remover la tarea programada | XS | T-30 |
| T-32 | Instalar la tarea y verificar en Task Scheduler que está registrada y configurada | S | T-30 |
| T-33 | Probar ciclo completo post-reinicio: hacer logon, verificar que la tarea arranca el asistente automáticamente con acceso al micrófono | M | T-32 |

**Notas T-30:** Task Scheduler reemplaza NSSM (sin actualizaciones estables desde 2017). Ventajas:
- Corre con la cuenta del usuario → acceso directo al micrófono (resuelve el problema de SYSTEM)
- Built-in en Windows, no requiere binarios externos
- Soporta trigger "At logon", reinicio en fallo, y condiciones de red
- Se configura vía PowerShell (`Register-ScheduledTask`) o `schtasks.exe`

---

### Fase 7 — Pulido y Hardening

| ID | Tarea | Esfuerzo | Dependencias |
|----|-------|----------|--------------|
| T-34 | Agregar manejo de reconexión automática: si Gemini Live cae, esperar N segundos y reintentar | S | T-29 |
| T-35 | Agregar indicador visual (ícono en system tray con `pystray`) que muestre estado: escuchando / procesando / hablando | M | T-29 |
| T-36 | Configurar `system_instruction` completa: personalidad en español relajado, instrucciones de cuándo llamar cada tool, zona horaria Querétaro (America/Mexico_City) | S | T-15 |
| T-37 | Crear `README.md` con pasos de instalación, variables de `.env` requeridas y comandos de Task Scheduler | S | T-33 |

---

## Orden de Ejecución Recomendado

```
T-01 → T-02 → T-03 → T-04 → T-05 → T-06
                                      ↓
                              T-07 → T-08 → T-09 → T-10
                                                      ↓
                              T-11 → T-12 → T-13 → T-14 → T-15
                                                              ↓
                              T-16 → T-17 → T-18 → T-19
                                                     ↓
                              T-20 → T-21 → T-22
                              T-23 → T-24 → T-25 → T-26 → T-27
                                                              ↓
                              T-28 → T-29 → T-30 → T-31 → T-32 → T-33
                                                                    ↓
                              T-34, T-35, T-36 (paralelos) → T-37
```

---

## Resumen de Esfuerzo por Fase

| Fase | Tareas | Esfuerzo Total |
|------|--------|----------------|
| 0 — Setup | T-01 a T-06 | ~2h |
| 1 — Wake Word (openWakeWord) | T-07 a T-10 | ~3h |
| 2 — Gemini Live (3.1 Flash) | T-11 a T-15 | ~5h |
| 3 — Búsqueda Web | T-16 a T-19 | ~3h |
| 4 — YouTube | T-20 a T-22 | ~2h |
| 5 — Spotify | T-23 a T-27 | ~4h |
| 6 — Task Scheduler | T-28 a T-33 | ~2.5h |
| 7 — Pulido | T-34 a T-37 | ~3.5h |
| **Total** | **37 tareas** | **~25h** |

---

## Riesgos y Mitigaciones

| Riesgo | Probabilidad | Mitigación |
|--------|-------------|------------|
| Latencia de Gemini Live en red WiFi doméstica | Media | Buffer de audio adaptativo; indicador visual de estado para que el usuario sepa que está procesando |
| openWakeWord: falsos positivos con wake word en español | Media | Ajustar `threshold` en T-10; entrenar modelo custom con samples de español mexicano si el pre-entrenado no es suficiente; considerar wake word en inglés como fallback |
| Spotify requiere Premium y sesión activa | Alta | Documentar prerequisito; tool falla gracefully con mensaje hablado si no hay dispositivo activo |
| Diferencia de sample rate input/output en Gemini (16kHz vs 24kHz) | Baja | Configurar output a 16kHz en la sesión o manejar resample en `AudioPlayer` |
| Modelo `gemini-3.1-flash-live-preview` aún en preview | Media | Monitorear cambios de nombre del modelo en la documentación de Google; usar variable de entorno para fácil actualización |
| Task Scheduler no arranca si no hay logon interactivo | Baja | Configurar auto-logon en Windows o usar trigger "At startup" con cuenta de usuario almacenada |

---

## Variables Requeridas en `.env`

```env
# Gemini
GEMINI_API_KEY=...
GEMINI_MODEL=gemini-3.1-flash-live-preview

# Wake Word (openWakeWord)
WAKE_WORD_MODEL=hey_jarvis
WAKE_WORD_MODEL_PATH=D:\voice-assistant\src\wake_word\models\hey_jarvis.onnx
WAKE_WORD_THRESHOLD=0.5
WAKE_WORD_INFERENCE_FRAMEWORK=onnx

# Spotify
SPOTIFY_CLIENT_ID=...
SPOTIFY_CLIENT_SECRET=...
SPOTIFY_REDIRECT_URI=http://localhost:8888/callback
SPOTIFY_CACHE_PATH=D:\voice-assistant\.spotify_cache

# Audio
MIC_DEVICE_INDEX=0
AUDIO_SAMPLE_RATE=16000

# Logging
LOG_LEVEL=INFO
LOG_PATH=D:\voice-assistant\logs\voice-assistant.log
```

---

## `requirements.txt` Propuesto

```txt
# Wake word (offline, gratuito, Apache 2.0)
openwakeword>=0.6.0
onnxruntime>=1.16.0

# Audio I/O
pyaudio>=0.2.14

# Gemini Live
google-genai>=2.8.0

# Tools
spotipy>=2.24.0
yt-dlp>=2026.1.1

# Config
python-dotenv>=1.0.0

# System tray (Fase 7)
pystray>=0.19.5
Pillow>=10.0.0
```

---

## Changelog del Plan

| Fecha | Cambio | Razón |
|-------|--------|-------|
| 2026-06-15 | Plan original | Versión inicial |
| 2026-06-17 | Picovoice → openWakeWord | Picovoice eliminó free tier; openWakeWord es gratuito, offline, Apache 2.0, soporta 20+ idiomas |
| 2026-06-17 | Gemini 2.5 Flash → 3.1 Flash Live | Modelo `gemini-2.5-flash-preview-native-audio-dialog` fue deprecado y removido (19 marzo 2026). Sucesor: `gemini-3.1-flash-live-preview` |
| 2026-06-17 | google-genai >=1.0.0 → >=2.8.0 | SDK alcanzó GA; versión actual 2.8.0 (junio 2026) |
| 2026-06-17 | NSSM → Task Scheduler | NSSM sin actualizaciones estables desde 2017. Task Scheduler es built-in, corre con cuenta de usuario (acceso a mic), y soporta reinicio en fallo |
| 2026-06-17 | Reducción de 38 → 37 tareas | Se eliminó T-30 (descargar NSSM) al no ser necesario |
```
