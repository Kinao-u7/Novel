package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentParagraphIndex = MutableStateFlow(-1)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex.asStateFlow()

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    private var paragraphs: List<String> = emptyList()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isInitialized = true
            setupListener()
            updateAvailableVoices()
        }
    }

    private fun updateAvailableVoices() {
        try {
            val voices = tts?.voices?.map { it.name }?.filter { it.isNotBlank() } ?: emptyList()
            _availableVoices.value = voices
        } catch (e: Exception) {
            // Ignore on older Android versions or devices without voice listing
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isPlaying.value = true
            }

            override fun onDone(utteranceId: String?) {
                val nextIdx = _currentParagraphIndex.value + 1
                if (nextIdx < paragraphs.size) {
                    _currentParagraphIndex.value = nextIdx
                    speakParagraph(nextIdx)
                } else {
                    _isPlaying.value = false
                    _currentParagraphIndex.value = -1
                }
            }

            override fun onError(utteranceId: String?) {
                _isPlaying.value = false
            }
        })
    }

    fun startReading(paragraphsList: List<String>, startIndex: Int = 0) {
        paragraphs = paragraphsList
        val start = if (startIndex in paragraphsList.indices) startIndex else 0
        _currentParagraphIndex.value = start
        speakParagraph(start)
    }

    private fun speakParagraph(index: Int) {
        if (!isInitialized || index !in paragraphs.indices) return
        val text = paragraphs[index]
        tts?.setSpeechRate(_speechRate.value)
        tts?.setPitch(_pitch.value)
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "para_$index")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "para_$index")
        _isPlaying.value = true
    }

    fun pause() {
        tts?.stop()
        _isPlaying.value = false
    }

    fun resume() {
        val idx = _currentParagraphIndex.value
        if (idx in paragraphs.indices) {
            speakParagraph(idx)
        }
    }

    fun stop() {
        tts?.stop()
        _isPlaying.value = false
        _currentParagraphIndex.value = -1
    }

    fun nextParagraph() {
        val next = _currentParagraphIndex.value + 1
        if (next < paragraphs.size) {
            _currentParagraphIndex.value = next
            speakParagraph(next)
        } else {
            stop()
        }
    }

    fun previousParagraph() {
        val prev = (_currentParagraphIndex.value - 1).coerceAtLeast(0)
        _currentParagraphIndex.value = prev
        speakParagraph(prev)
    }

    fun setSpeed(rate: Float) {
        _speechRate.value = rate
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitchVal: Float) {
        _pitch.value = pitchVal
        tts?.setPitch(pitchVal)
    }

    fun setVoice(voiceName: String) {
        try {
            val matched = tts?.voices?.firstOrNull { it.name == voiceName }
            if (matched != null) {
                tts?.voice = matched
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
