package com.pascal.aikorrektur

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Verarbeitet android.intent.action.PROCESS_TEXT aus dem nativen Android-Kontextmenü.
 */
class ProcessTextActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var correctionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        if (selectedText.isNullOrBlank()) {
            Toast.makeText(this, "Kein Text zum Korrigieren ausgewählt.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!AppPreferences.isConfigured(this)) {
            Toast.makeText(
                this,
                "Bitte zuerst Gemini API-Key in der App einrichten.",
                Toast.LENGTH_LONG
            ).show()
            val settingsIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(settingsIntent)
            finish()
            return
        }

        setContentView(R.layout.dialog_process_text)

        val tvStatus = findViewById<TextView>(R.id.tvDialogStatus)
        val tvSubtitle = findViewById<TextView>(R.id.tvDialogSubtitle)
        val btnCancel = findViewById<Button>(R.id.btnDialogCancel)

        tvStatus.text = "KI-Korrektur läuft..."
        tvSubtitle.text = "${AppPreferences.getModel(this)} korrigiert..."

        btnCancel.setOnClickListener {
            correctionJob?.cancel()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val apiKey = AppPreferences.getApiKey(this)
        val model = AppPreferences.getModel(this)
        val prompt = AppPreferences.getSystemPrompt(this)

        correctionJob = scope.launch {
            val result = GeminiService.correctText(
                apiKey = apiKey,
                inputText = selectedText,
                model = model,
                systemInstruction = prompt,
                onFallbackModelUsed = { fallbackModel ->
                    AppPreferences.setModel(this@ProcessTextActivity, fallbackModel)
                }
            )

            result.onSuccess { correctedText ->
                if (isReadOnly) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("KI-Korrektur", correctedText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        this@ProcessTextActivity,
                        "Korrigiert & in Zwischenablage kopiert.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val resultIntent = Intent().apply {
                        putExtra(Intent.EXTRA_PROCESS_TEXT, correctedText)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                }
                finish()
            }.onFailure { error ->
                Toast.makeText(
                    this@ProcessTextActivity,
                    "Fehler: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
