package com.pascal.aikorrektur

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
 * Mit dynamischer Modell-Abfrage und automatischer Modell-Erkennung.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var testJob: Job? = null
    private var fetchModelsJob: Job? = null

    private lateinit var etApiKey: EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var btnGetApiKey: Button
    private lateinit var tvStatusBadge: TextView
    private lateinit var actvModel: AutoCompleteTextView
    private lateinit var etCustomInstructions: EditText
    private lateinit var btnSaveInstructions: Button
    private lateinit var btnClearInstructions: Button
    private lateinit var etTestInput: EditText
    private lateinit var btnTestCorrect: Button
    private lateinit var pbTestProgress: ProgressBar
    private lateinit var tvTestResult: TextView
    private lateinit var btnCopyResult: Button
    private lateinit var btnOpenAccessibility: Button

    private val availableModels = mutableListOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-flash-latest"
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
        btnGetApiKey = findViewById(R.id.btnGetApiKey)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        actvModel = findViewById(R.id.actvModel)
        etCustomInstructions = findViewById(R.id.etCustomInstructions)
        btnSaveInstructions = findViewById(R.id.btnSaveInstructions)
        btnClearInstructions = findViewById(R.id.btnClearInstructions)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        etTestInput = findViewById(R.id.etTestInput)
        btnTestCorrect = findViewById(R.id.btnTestCorrect)
        pbTestProgress = findViewById(R.id.pbTestProgress)
        tvTestResult = findViewById(R.id.tvTestResult)
        btnCopyResult = findViewById(R.id.btnCopyResult)

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, availableModels)
        actvModel.setAdapter(adapter)
    }

    private fun loadPreferences() {
        val apiKey = AppPreferences.getApiKey(this)
        etApiKey.setText(apiKey)
        updateStatusBadge(apiKey.isNotBlank())

        val cached = AppPreferences.getCachedModels(this)
        if (cached.isNotEmpty()) {
            availableModels.clear()
            availableModels.addAll(cached)
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, availableModels)
            actvModel.setAdapter(adapter)
        }

        val currentModel = AppPreferences.getModel(this)
        actvModel.setText(currentModel, false)

        val custom = AppPreferences.getCustomInstructions(this)
        etCustomInstructions.setText(custom)

        if (apiKey.isNotBlank()) {
            refreshModelsDynamically(apiKey)
        }
    }

    private fun setupListeners() {
        btnSaveApiKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            AppPreferences.setApiKey(this, key)
            val model = actvModel.text.toString().trim()
            if (model.isNotBlank()) {
                AppPreferences.setModel(this, model)
            }
            val instructions = etCustomInstructions.text.toString().trim()
            AppPreferences.setCustomInstructions(this, instructions)
            updateStatusBadge(key.isNotBlank())

            if (key.isNotBlank()) {
                refreshModelsDynamically(key)
                Toast.makeText(this, "Saved. Updating models...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
            }
        }

        btnGetApiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
            startActivity(intent)
        }

        actvModel.setOnItemClickListener { _, _, position, _ ->
            if (position in 0 until availableModels.size) {
                val selectedModel = availableModels[position]
                AppPreferences.setModel(this, selectedModel)
                Toast.makeText(this, "Model: $selectedModel", Toast.LENGTH_SHORT).show()
            }
        }

        actvModel.doAfterTextChanged { text ->
            val model = text?.toString()?.trim().orEmpty()
            if (model.isNotBlank()) {
                AppPreferences.setModel(this, model)
            }
        }

        btnSaveInstructions.setOnClickListener {
            val text = etCustomInstructions.text.toString().trim()
            AppPreferences.setCustomInstructions(this, text)
            Toast.makeText(this, "Instructions saved.", Toast.LENGTH_SHORT).show()
        }

        btnClearInstructions.setOnClickListener {
            etCustomInstructions.setText("")
            AppPreferences.setCustomInstructions(this, "")
            Toast.makeText(this, "Instructions cleared.", Toast.LENGTH_SHORT).show()
        }

        etCustomInstructions.doAfterTextChanged { text ->
            AppPreferences.setCustomInstructions(this, text?.toString()?.trim().orEmpty())
        }

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Select 'AI Proofreader' in the list and enable it.",
                Toast.LENGTH_LONG
            ).show()
        }

        btnTestCorrect.setOnClickListener {
            performLiveTest()
        }

        btnCopyResult.setOnClickListener {
            val result = tvTestResult.text.toString()
            if (result.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Corrected Text", result)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshModelsDynamically(apiKey: String) {
        fetchModelsJob?.cancel()
        fetchModelsJob = scope.launch {
            val result = GeminiService.fetchAvailableModels(apiKey)
            result.onSuccess { liveModels ->
                if (liveModels.isNotEmpty()) {
                    AppPreferences.setCachedModels(this@MainActivity, liveModels)
                    availableModels.clear()
                    availableModels.addAll(liveModels)

                    val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, availableModels)
                    actvModel.setAdapter(adapter)

                    val currentModel = AppPreferences.getModel(this@MainActivity)
                    if (currentModel !in availableModels) {
                        val fallback = availableModels.first()
                        AppPreferences.setModel(this@MainActivity, fallback)
                        actvModel.setText(fallback, false)
                    }
                }
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

            val result = GeminiService.correctText(
                apiKey = apiKey,
                inputText = inputText,
                model = model,
                systemInstruction = prompt,
                onFallbackModelUsed = { fallbackModel ->
                    AppPreferences.setModel(this@MainActivity, fallbackModel)
                    actvModel.setText(fallbackModel, false)
                    Toast.makeText(this@MainActivity, "Modell automatisch angepasst: $fallbackModel", Toast.LENGTH_SHORT).show()
                }
            )

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
