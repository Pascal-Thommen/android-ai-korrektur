package com.pascal.aikorrektur

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Einstellungen und Test-Playground für die Gemini KI-Korrektur.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var testJob: Job? = null

    private lateinit var etApiKey: EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var tvStatusBadge: TextView
    private lateinit var actvModel: AutoCompleteTextView
    private lateinit var etSystemPrompt: EditText
    private lateinit var btnResetPrompt: Button
    private lateinit var etTestInput: EditText
    private lateinit var btnTestCorrect: Button
    private lateinit var pbTestProgress: ProgressBar
    private lateinit var tvTestResult: TextView
    private lateinit var btnCopyResult: Button

    private val availableModels = listOf(
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.5-flash-lite",
        "gemini-3.1-pro-preview"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        setupListeners()
    }

    private fun initViews() {
        etApiKey = findViewById(R.id.etApiKey)
        btnSaveApiKey = findViewById(R.id.btnSaveApiKey)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        actvModel = findViewById(R.id.actvModel)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        btnResetPrompt = findViewById(R.id.btnResetPrompt)
        etTestInput = findViewById(R.id.etTestInput)
        btnTestCorrect = findViewById(R.id.btnTestCorrect)
        pbTestProgress = findViewById(R.id.pbTestProgress)
        tvTestResult = findViewById(R.id.tvTestResult)
        btnCopyResult = findViewById(R.id.btnCopyResult)

        // Dropdown Adapter für Gemini-Modelle
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, availableModels)
        actvModel.setAdapter(adapter)
    }

    private fun loadPreferences() {
        val apiKey = AppPreferences.getApiKey(this)
        etApiKey.setText(apiKey)
        updateStatusBadge(apiKey.isNotBlank())

        val currentModel = AppPreferences.getModel(this)
        actvModel.setText(currentModel, false)

        val prompt = AppPreferences.getSystemPrompt(this)
        etSystemPrompt.setText(prompt)
    }

    private fun setupListeners() {
        btnSaveApiKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            AppPreferences.setApiKey(this, key)
            val model = actvModel.text.toString().trim()
            if (model.isNotBlank()) {
                AppPreferences.setModel(this, model)
            }
            updateStatusBadge(key.isNotBlank())
            Toast.makeText(this, "Einstellungen gespeichert.", Toast.LENGTH_SHORT).show()
        }

        actvModel.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = availableModels[position]
            AppPreferences.setModel(this, selectedModel)
            Toast.makeText(this, "Modell geändert: $selectedModel", Toast.LENGTH_SHORT).show()
        }

        actvModel.doAfterTextChanged { text ->
            val model = text?.toString()?.trim().orEmpty()
            if (model.isNotBlank()) {
                AppPreferences.setModel(this, model)
            }
        }

        etSystemPrompt.doAfterTextChanged { text ->
            val prompt = text?.toString()?.trim().orEmpty()
            if (prompt.isNotBlank()) {
                AppPreferences.setSystemPrompt(this, prompt)
            }
        }

        btnResetPrompt.setOnClickListener {
            etSystemPrompt.setText(AppPreferences.DEFAULT_PROMPT)
            AppPreferences.setSystemPrompt(this, AppPreferences.DEFAULT_PROMPT)
            Toast.makeText(this, "Standard-Prompt wiederhergestellt.", Toast.LENGTH_SHORT).show()
        }

        btnTestCorrect.setOnClickListener {
            performLiveTest()
        }

        btnCopyResult.setOnClickListener {
            val result = tvTestResult.text.toString()
            if (result.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Korrigierter Text", result)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "In Zwischenablage kopiert.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatusBadge(isConfigured: Boolean) {
        if (isConfigured) {
            tvStatusBadge.text = "Bereit (API-Key aktiv)"
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
        } else {
            tvStatusBadge.text = "Nicht eingerichtet"
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_inactive)
        }
    }

    private fun performLiveTest() {
        val apiKey = AppPreferences.getApiKey(this)
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Bitte zuerst API-Key eingeben und speichern.", Toast.LENGTH_SHORT).show()
            return
        }

        val inputText = etTestInput.text.toString()
        if (inputText.isBlank()) {
            Toast.makeText(this, "Bitte Beispieltext für den Test eingeben.", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestCorrect.isEnabled = false
        pbTestProgress.visibility = View.VISIBLE
        tvTestResult.text = ""
        btnCopyResult.visibility = View.GONE

        testJob?.cancel()
        testJob = scope.launch {
            val model = AppPreferences.getModel(this@MainActivity)
            val prompt = AppPreferences.getSystemPrompt(this@MainActivity)
            val result = GeminiService.correctText(apiKey, inputText, model, prompt)

            pbTestProgress.visibility = View.GONE
            btnTestCorrect.isEnabled = true

            result.onSuccess { corrected ->
                tvTestResult.text = corrected
                btnCopyResult.visibility = View.VISIBLE
            }.onFailure { err ->
                tvTestResult.text = "Fehler: ${err.localizedMessage}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
