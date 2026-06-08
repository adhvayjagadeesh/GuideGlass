package com.impairedvision.guideglass.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class SpeechHelper(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SpeechHelper"
        private const val DUPLICATE_COOLDOWN_MS = 3000L
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady = false

    private var lastSpokenText: String? = null
    private var lastSpokenTime: Long = 0

    @Volatile
    private var isSpeaking = false

    private val urgentQueue = ConcurrentLinkedQueue<String>()
    private val normalQueue = ConcurrentLinkedQueue<String>()

    /** Speeches requested before onInit completes. */
    private data class PendingSpeech(
        val text: String,
        val force: Boolean,
        val skipDuplicateCheck: Boolean
    )

    private val pendingUntilReady = ConcurrentLinkedQueue<PendingSpeech>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            } else {
                isReady = true
                tts?.setSpeechRate(1.5f)
                setupProgressListener()
                Log.d(TAG, "TTS initialized successfully")
                flushPendingUntilReady()
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    private fun flushPendingUntilReady() {
        while (true) {
            val pending = pendingUntilReady.poll() ?: break
            speak(pending.text, pending.force, pending.skipDuplicateCheck)
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    processNextInQueue()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeaking = false
                    Log.e(TAG, "TTS error utterance=$utteranceId code=$errorCode")
                    processNextInQueue()
                }
            }
        )
    }

    private fun processNextInQueue() {
        if (!isReady) return
        val nextMessage = urgentQueue.poll() ?: normalQueue.poll()
        nextMessage?.let { speakInternal(it, false) }
    }

    /**
     * Speaks the text if it hasn't been spoken recently.
     * @param text The text to read aloud.
     * @param force If true, interrupts current speech immediately (for urgent messages).
     * @param skipDuplicateCheck If true, bypasses the duplicate cooldown check.
     */
    fun speak(text: String, force: Boolean = false, skipDuplicateCheck: Boolean = false) {
        if (text.isBlank()) return

        if (!isReady) {
            pendingUntilReady.offer(PendingSpeech(text, force, skipDuplicateCheck))
            return
        }

        val currentTime = System.currentTimeMillis()

        if (!skipDuplicateCheck && !force) {
            if (text == lastSpokenText && (currentTime - lastSpokenTime) < DUPLICATE_COOLDOWN_MS) {
                Log.d(TAG, "Skipping duplicate message: $text")
                return
            }
        }

        if (force) {
            urgentQueue.clear()
            normalQueue.clear()
            isSpeaking = false
            tts?.stop()
            speakInternal(text, true)
        } else if (isSpeaking) {
            normalQueue.offer(text)
        } else {
            speakInternal(text, false)
        }
    }

    private fun speakInternal(text: String, isUrgent: Boolean) {
        val engine = tts
        if (engine == null) {
            isSpeaking = false
            return
        }

        val params = Bundle()
        if (isUrgent) {
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak() failed result=$result text=${text.take(80)}")
            isSpeaking = false
            processNextInQueue()
            return
        }

        lastSpokenText = text
        lastSpokenTime = System.currentTimeMillis()
        isSpeaking = true
    }

    /**
     * Speaks a navigation instruction. Queued normally, won't interrupt urgent safety alerts.
     */
    fun speakNavigation(instruction: String) {
        if (instruction.isBlank()) return
        val cleanInstruction =
            instruction.replace(Regex("<[^>]*>"), "").trim()
        speak(cleanInstruction, force = false, skipDuplicateCheck = true)
    }

    /**
     * Speaks an urgent safety warning, interrupting everything else.
     */
    fun speakUrgent(warning: String) {
        speak(warning, force = true, skipDuplicateCheck = true)
    }

    fun stop() {
        urgentQueue.clear()
        normalQueue.clear()
        tts?.stop()
        isSpeaking = false
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun isCurrentlySpeaking(): Boolean = isSpeaking

    fun shutdown() {
        stop()
        pendingUntilReady.clear()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
