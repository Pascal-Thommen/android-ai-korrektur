package com.pascal.aikorrektur

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Android Accessibility Service für die 1-Klick Textkorrektur ohne manuelles Markieren.
 * Zeigt einen schwebenden Button (✨), liest das aktive Textfeld aus und ersetzt den Text direkt.
 */
class CorrectionAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var currentEditableNode: AccessibilityNodeInfo? = null

    private var tvButton: TextView? = null
    private var pbLoading: ProgressBar? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initFloatingOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event?.source ?: return
        if (source.isEditable) {
            currentEditableNode = source
        }
    }

    override fun onInterrupt() {}

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingOverlay() {
        if (floatingView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_floating_button, null)
        floatingView = view

        tvButton = view.findViewById(R.id.tvFloatingButton)
        pbLoading = view.findViewById(R.id.pbFloatingLoading)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 500
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (motionEvent.rawX - initialTouchX).toInt()
                    params.y = initialY + (motionEvent.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(motionEvent.rawX - initialTouchX)
                    val diffY = abs(motionEvent.rawY - initialTouchY)
                    if (diffX < 15 && diffY < 15) {
                        // Klick erkannt -> Text auslesen und korrigieren
                        performOneClickCorrection()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performOneClickCorrection() {
        val apiKey = AppPreferences.getApiKey(this)
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Bitte zuerst API-Key in KI-Korrektur hinterlegen.", Toast.LENGTH_SHORT).show()
            return
        }

        // Aktives Eingabefeld ermitteln
        val targetNode = findActiveInputNode()
        if (targetNode == null) {
            Toast.makeText(this, "Kein aktives Textfeld gefunden. Tippe zuerst in das Feld.", Toast.LENGTH_SHORT).show()
            return
        }

        val textToCorrect = targetNode.text?.toString().orEmpty()
        if (textToCorrect.isBlank()) {
            Toast.makeText(this, "Eingabefeld ist leer.", Toast.LENGTH_SHORT).show()
            return
        }

        // Ladezustand anzeigen
        setLoading(true)

        val model = AppPreferences.getModel(this)
        val prompt = AppPreferences.getSystemPrompt(this)

        serviceScope.launch {
            val result = GeminiService.correctText(
                apiKey = apiKey,
                inputText = textToCorrect,
                model = model,
                systemInstruction = prompt,
                onFallbackModelUsed = { fallback ->
                    AppPreferences.setModel(this@CorrectionAccessibilityService, fallback)
                }
            )

            setLoading(false)

            result.onSuccess { correctedText ->
                applyCorrectedText(targetNode, correctedText)
            }.onFailure { err ->
                Toast.makeText(this@CorrectionAccessibilityService, "Fehler: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findActiveInputNode(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        if (currentEditableNode != null && currentEditableNode?.isEditable == true) {
            return currentEditableNode
        }
        return rootInActiveWindow?.let { root -> findFirstEditable(root) }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun applyCorrectedText(node: AccessibilityNodeInfo, correctedText: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, correctedText)
        }
        val replaced = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (replaced) {
            Toast.makeText(this, "Text korrigiert! ✨", Toast.LENGTH_SHORT).show()
        } else {
            // Fallback: In Zwischenablage kopieren
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("KI-Korrektur", correctedText))
            Toast.makeText(this, "In Zwischenablage kopiert. Tippe auf Einfügen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoading(isLoading: Boolean) {
        tvButton?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        pbLoading?.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
        }
    }
}
