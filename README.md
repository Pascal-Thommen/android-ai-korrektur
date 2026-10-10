# Android KI-Korrektur (Gemini 1.5 Flash)

Schlanke, native Android-App zur systemweiten Textkorrektur direkt über das Android-Kontextmenü (`ACTION_PROCESS_TEXT`).

## Funktionsweise

1. In einem beliebigen Eingabefeld (z.B. WhatsApp, Gmail, Chrome, Notizen) Text markieren.
2. Im nativen Kontextmenü die Option **KI-Korrektur** wählen.
3. Der markierte Text wird via Google Gemini 1.5 Flash korrigiert.
4. Der korrigierte Text ersetzt direkt den ursprünglichen Text im Eingabefeld.
5. In schreibgeschützten Feldern wird der korrigierte Text automatisch in die Zwischenablage kopiert.

## Architektur & Komponenten

* `ProcessTextActivity.kt`: Verarbeitet den nativen Intent `android.intent.action.PROCESS_TEXT`. Zeigt ein leichtes, transparentes Lade-Overlay während des API-Aufrufs und übergibt das Ergebnis via `setResult(RESULT_OK, resultIntent)` zurück an die aufrufende App.
* `MainActivity.kt`: Benutzeroberfläche zur Konfiguration des Gemini API-Keys, Modellauswahl (`gemini-1.5-flash`, `gemini-2.0-flash`, `gemini-1.5-pro`), Anpassung des System-Prompts und integrierter Live-Test Playground.
* `GeminiService.kt`: Schlanker, nativer HTTP-Client für die Google Gemini REST API mit JSON-Payload und deterministischer Temperature-Einstellung (0.1).
* `AppPreferences.kt`: Verwaltung von API-Key und Konfiguration.

## Technische Details

* **Android Intent**: `android.intent.action.PROCESS_TEXT` mit `EXTRA_PROCESS_TEXT`
* **Programmiersprache**: Kotlin (Android SDK 34, Min SDK 26)
* **LLM Engine**: Google Gemini 1.5 Flash (schnell, kostengünstig und präzise)
* **Dependencies**: Minimal gehalten (nur AndroidX Core, AppCompat und Material)

## Build & Installation

```bash
# Debug APK erstellen
./gradlew assembleDebug

# APK auf verbundenes Android-Gerät installieren
adb install app/build/outputs/apk/debug/app-debug.apk
```
