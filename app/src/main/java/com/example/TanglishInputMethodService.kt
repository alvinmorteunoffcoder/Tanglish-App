package com.example

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.UserWord
import com.example.data.WordRepository
import com.example.engine.TamilToTanglishTransliterator
import com.example.utils.ServiceLifecycleOwner
import com.example.utils.attachToServiceLifecycle
import kotlinx.coroutines.*
import java.util.*

class TanglishInputMethodService : InputMethodService() {

    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Live variables to render UI state
    private val isShiftActive = mutableStateOf(0) // 0=lower, 1=shifted, 2=caps lock
    private val isKeyboardSymbols = mutableStateOf(false)
    private val isSpeechListening = mutableStateOf(false)
    private val isContinuousVoiceActive = mutableStateOf(false)
    private val speechTranscription = mutableStateOf("")
    private val isSpeechError = mutableStateOf<String?>(null)
    private val speechRmsLevel = mutableStateOf(0f)
    private val hasMicPermission = mutableStateOf(true)
    private var consecutiveSpeechErrors = 0

    // Cached values
    private val userDictionary = mutableStateOf<Map<String, String>>(emptyMap())
    private val activeBatteryProfile = mutableStateOf("Balanced") // "Performance", "Balanced", "Battery Saver"
    private val isQwertzLayout = mutableStateOf(false) // Toggle between QWERTY and QWERTZ

    // Speech Rec
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var composingAnimationJob: Job? = null

    private fun startComposingAnimation(baseText: String) {
        composingAnimationJob?.cancel()
        composingAnimationJob = serviceScope.launch {
            var dots = 1
            while (isActive) {
                val dotsStr = ".".repeat(dots)
                val spacesStr = " ".repeat(3 - dots)
                val animatedText = "$baseText$dotsStr$spacesStr"
                try {
                    val ic = currentInputConnection
                    withContext(Dispatchers.Main) {
                        ic?.setComposingText(animatedText, 1)
                    }
                } catch (e: Throwable) {
                    // Ignore dead input connection exceptions peacefully
                }
                delay(500)
                dots = (dots % 3) + 1
            }
        }
    }

    private fun stopComposingAnimation() {
        composingAnimationJob?.cancel()
        composingAnimationJob = null
        try {
            val ic = currentInputConnection
            ic?.setComposingText("", 1)
            ic?.finishComposingText()
        } catch (e: Throwable) {
            // Ignore dead input connection exceptions peacefully
        }
    }

    // Translator State
    private val isTranslatorActive = mutableStateOf(false)
    private val translationInputText = mutableStateOf("")
    private val translationOutputText = mutableStateOf("")
    private val isTranslationLoading = mutableStateOf(false)
    private val translationError = mutableStateOf<String?>(null)

    // Suggestions cache derived from typing
    private val currentInputBuffer = StringBuilder()
    private val typedSuggestions = mutableStateListOf<String>()

    override fun onCreate() {
        super.onCreate()
        try {
            val owner = ServiceLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
            lifecycleOwner = owner
        } catch (e: Throwable) {
            // Safe fallback
        }
        loadLocalConfig()
        setupSpeechRecognizer()
    }

    override fun onCreateInputView(): View {
        val owner = lifecycleOwner ?: ServiceLifecycleOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner = this
        }

        try {
            val dialog = getWindow()
            val win = dialog?.window
            val decorView = win?.decorView
            decorView?.attachToServiceLifecycle(owner)
        } catch (e: Throwable) {
            // Safe fallback
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        composeView.attachToServiceLifecycle(owner)

        composeView.setContent {
            MaterialTheme {
                KeyboardLayout()
            }
        }
        return composeView
    }

    override fun onDestroy() {
        super.onDestroy()
        setSystemMuteState(false)
        try {
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
        } catch (e: Throwable) {
            // Safe fallback
        }
        serviceScope.cancel()
        try {
            speechRecognizer?.destroy()
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        try {
            lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Throwable) {
            // Safe fallback
        }
        loadLocalConfig()
        clearInputBuffer()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        setSystemMuteState(false)
        try {
            lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return true
    }

    private fun loadLocalConfig() {
        try {
            // Load User Dictionary from Room Database
            serviceScope.launch {
                try {
                    val db = AppDatabase.getDatabase(this@TanglishInputMethodService)
                    val repository = WordRepository(db.userWordDao())
                    val words = repository.getAllWords()
                    val dictMap = words.associate { it.tamilWord to it.customTanglish }
                    userDictionary.value = dictMap
                } catch (e: Throwable) {
                    userDictionary.value = emptyMap()
                }
            }

            // Load configs from SharedPreferences
            val prefs = getSharedPreferences("tanglish_keyboard_prefs", Context.MODE_PRIVATE)
            activeBatteryProfile.value = prefs.getString("battery_profile", "Balanced") ?: "Balanced"
            isQwertzLayout.value = prefs.getBoolean("layout_qwertz", false)
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    private fun triggerHapticFeedback() {
        try {
            val profile = activeBatteryProfile.value
            if (profile == "Battery Saver") return // Disabled in Saver

            val duration = when (profile) {
                "Performance" -> 35L
                else -> 15L // Balanced
            }

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    private fun handleKeypress(char: String) {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return

        var insertText = char
        if (isKeyboardSymbols.value) {
            // Symbols layout inserts raw characters
            ic.commitText(insertText, 1)
        } else {
            // Apply casing to letters
            if (char.length == 1 && char[0].isLetter()) {
                insertText = when (isShiftActive.value) {
                    1, 2 -> char.uppercase()
                    else -> char.lowercase()
                }
                // If single shift was active, revert to lowercase
                if (isShiftActive.value == 1) {
                    isShiftActive.value = 0
                }
            }
            ic.commitText(insertText, 1)
            currentInputBuffer.append(insertText)
            updateSuggestions()
        }
    }

    private fun handleBackspace() {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return
        
        if (currentInputBuffer.isNotEmpty()) {
            currentInputBuffer.deleteAt(currentInputBuffer.length - 1)
            updateSuggestions()
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
    }

    private fun handleSpace() {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return
        
        ic.commitText(" ", 1)
        clearInputBuffer()
    }

    private fun handleEnter() {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        clearInputBuffer()
    }

    private fun clearInputBuffer() {
        currentInputBuffer.clear()
        typedSuggestions.clear()
    }

    private fun updateSuggestions() {
        typedSuggestions.clear()
        // Removed suggestion list while typing at the user's explicit request
    }

    private fun applySuggestion(suggestion: String) {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return
        
        // Delete what we typed so far in this word buffer
        val lengthToDel = currentInputBuffer.length
        if (lengthToDel > 0) {
            ic.deleteSurroundingText(lengthToDel, 0)
        }
        ic.commitText(suggestion + " ", 1)
        clearInputBuffer()
    }

    private fun setSystemMuteState(muted: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            val streams = intArrayOf(
                android.media.AudioManager.STREAM_SYSTEM,
                android.media.AudioManager.STREAM_NOTIFICATION,
                android.media.AudioManager.STREAM_RING,
                android.media.AudioManager.STREAM_ALARM,
                android.media.AudioManager.STREAM_MUSIC
            )
            for (stream in streams) {
                try {
                    if (muted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, 0)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.setStreamMute(stream, true)
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.setStreamMute(stream, false)
                        }
                    }
                } catch (e: Throwable) {
                    // Ignore stream-specific volume adjustment limit/exceptions
                }
            }
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    private fun setupSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                hasMicPermission.value = false
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        isSpeechListening.value = true
                        speechTranscription.value = "Listening continuously..."
                        isSpeechError.value = null
                        startComposingAnimation("listening")
                    }

                    override fun onBeginningOfSpeech() {
                        speechTranscription.value = "Hearing voice..."
                        startComposingAnimation("listening")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (activeBatteryProfile.value != "Battery Saver") {
                             speechRmsLevel.value = rmsdB
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        speechTranscription.value = "Processing..."
                        startComposingAnimation("typing")
                    }

                    override fun onError(error: Int) {
                        stopComposingAnimation()
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Speech service client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission missing (Record Audio)"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No spoken words recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                            else -> "Speech system unavailable"
                        }
                        isSpeechError.value = message
                        speechRmsLevel.value = 0f
                        isSpeechListening.value = false

                        val isHardError = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                                          error == SpeechRecognizer.ERROR_CLIENT
                        consecutiveSpeechErrors++

                        if (isContinuousVoiceActive.value && !isHardError && consecutiveSpeechErrors < 3) {
                            restartContinuousSpeech()
                        } else {
                            setSystemMuteState(false)
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        isSpeechListening.value = false
                        speechRmsLevel.value = 0f
                        consecutiveSpeechErrors = 0
                        val voiceResults = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val ic = currentInputConnection
                        if (!voiceResults.isNullOrEmpty()) {
                            val originalTamilText = voiceResults[0]
                            startComposingAnimation("typing")
                            
                            val apiKey = if (com.example.engine.GeminiTranslator.isApiKeyAvailable) "OK" else ""
                            if (apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY") {
                                speechTranscription.value = "Refining..."
                                serviceScope.launch {
                                    val result = com.example.engine.GeminiTranslator.translateToTanglish(originalTamilText)
                                    val transliterated = result.getOrElse {
                                        TamilToTanglishTransliterator.transliterateSentence(
                                            originalTamilText, 
                                            userDictionary.value
                                        )
                                    }
                                    try {
                                        if (ic != null && transliterated.isNotBlank()) {
                                            stopComposingAnimation()
                                            val lastChar = ic.getTextBeforeCursor(1, 0) ?: ""
                                            val prefix = if (lastChar.isNotEmpty() && !lastChar.endsWith(" ")) " " else ""
                                            ic.commitText(prefix + transliterated + " ", 1)
                                            speechTranscription.value = ""
                                        } else {
                                            stopComposingAnimation()
                                            speechTranscription.value = transliterated
                                        }
                                    } catch (e: Throwable) {
                                        stopComposingAnimation()
                                    }
                                }
                            } else {
                                val transliterated = TamilToTanglishTransliterator.transliterateSentence(
                                    originalTamilText, 
                                    userDictionary.value
                                )
                                try {
                                    if (ic != null && transliterated.isNotBlank()) {
                                        stopComposingAnimation()
                                        val lastChar = ic.getTextBeforeCursor(1, 0) ?: ""
                                        val prefix = if (lastChar.isNotEmpty() && !lastChar.endsWith(" ")) " " else ""
                                        ic.commitText(prefix + transliterated + " ", 1)
                                    } else {
                                        stopComposingAnimation()
                                        speechTranscription.value = transliterated
                                    }
                                } catch (e: Throwable) {
                                    stopComposingAnimation()
                                }
                            }
                        } else {
                            stopComposingAnimation()
                        }

                        // Auto-restart listening if voice input is active
                        if (isContinuousVoiceActive.value) {
                            restartContinuousSpeech()
                        } else {
                            setSystemMuteState(false)
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partialVoice = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!partialVoice.isNullOrEmpty()) {
                            val currentTamilPartial = partialVoice[0]
                            val liveTanglish = TamilToTanglishTransliterator.transliterateSentence(
                                currentTamilPartial, 
                                userDictionary.value
                            )
                            speechTranscription.value = liveTanglish
                        }
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }

            speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ta-IN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ta-IN")
                // Enable dual language capabilities so spoken english text stays intact without forcing to tamil script
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("ta-IN", "en-IN", "en-US"))
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 12000L)
                putExtra("android.speech.extra.BEEP_ENABLED", false)
            }
        } catch (e: Throwable) {
            hasMicPermission.value = false
            isSpeechError.value = "Speech recognition init failed: ${e.message}"
        }
    }

    private fun restartContinuousSpeech() {
        if (!isContinuousVoiceActive.value) return
        serviceScope.launch {
            delay(100) // Small delay to recycle audio resources
            if (!isContinuousVoiceActive.value) return@launch
            try {
                setSystemMuteState(true) // Ensure muted
                speechRecognizer?.startListening(speechRecognizerIntent)
                isSpeechListening.value = true
                isSpeechError.value = null
                speechTranscription.value = "Listening constantly..."
                startComposingAnimation("listening")
            } catch (e: Throwable) {
                // Ignore transient errors
            }
        }
    }

    private fun toggleSpeechVoiceInput() {
        triggerHapticFeedback()
        if (isContinuousVoiceActive.value) {
            isContinuousVoiceActive.value = false
            speechRecognizer?.stopListening()
            isSpeechListening.value = false
            setSystemMuteState(false) // Restore volume
            stopComposingAnimation()
        } else {
            // Check Android runtime audio permission safely
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    showMicPermissionErrorInKeyboard()
                    return
                }
            }

            hasMicPermission.value = true
            isSpeechError.value = null
            speechTranscription.value = "Initializing Mic..."
            isContinuousVoiceActive.value = true
            consecutiveSpeechErrors = 0
            setSystemMuteState(true) // Block popping chimes
            try {
                speechRecognizer?.startListening(speechRecognizerIntent)
                isSpeechListening.value = true
                startComposingAnimation("listening")
            } catch (e: Throwable) {
                isSpeechError.value = "Engine error: ${e.message}"
                isContinuousVoiceActive.value = false
                isSpeechListening.value = false
                setSystemMuteState(false) // Restore volume
                stopComposingAnimation()
            }
        }
    }

    private fun showMicPermissionErrorInKeyboard() {
        hasMicPermission.value = false
        isSpeechError.value = "Record Audio Permission Required! Open Tanglish app to enable."
    }

    private fun translateInputToTanglish() {
        val query = translationInputText.value
        if (query.isBlank()) return
        translationError.value = null
        isTranslationLoading.value = true
        translationOutputText.value = "Translating..."
        serviceScope.launch {
            val result = com.example.engine.GeminiTranslator.translateToTanglish(query)
            isTranslationLoading.value = false
            result.onSuccess { output ->
                translationOutputText.value = output
            }.onFailure { error ->
                translationError.value = error.message ?: "Translation failed"
                translationOutputText.value = ""
            }
        }
    }

    @Composable
    fun KeyboardLayout() {
        val micPresent by hasMicPermission
        val symbolsActive by isKeyboardSymbols
        val listening by isSpeechListening
        val continuousVoiceActive by isContinuousVoiceActive
        val transcript by speechTranscription
        val errMsg by isSpeechError
        val rms by speechRmsLevel
        val isQwertz by isQwertzLayout
        val isTranslatorOpen by isTranslatorActive
        val transInput by translationInputText
        val transOutput by translationOutputText
        val transLoading by isTranslationLoading
        val transErr by translationError

        // Background styling matching exactly the Geometric Balance theme (#1A1C1E slate)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1C1E))
                .padding(vertical = 4.dp)
                .testTag("gboard_container")
        ) {
            Column {
                if (isTranslatorOpen) {
                    TranslationPanel(
                        inputText = transInput,
                        outputText = transOutput,
                        isLoading = transLoading,
                        error = transErr,
                        onInputChanged = { translationInputText.value = it },
                        onTranslate = { translateInputToTanglish() },
                        onInsert = {
                            val ic = currentInputConnection
                            if (ic != null && transOutput.isNotBlank() && transOutput != "Translating...") {
                                val lastChar = ic.getTextBeforeCursor(1, 0) ?: ""
                                val prefix = if (lastChar.isNotEmpty() && !lastChar.endsWith(" ")) " " else ""
                                ic.commitText(prefix + transOutput + " ", 1)
                            }
                            isTranslatorActive.value = false
                            translationInputText.value = ""
                            translationOutputText.value = ""
                            translationError.value = null
                        },
                        onClose = {
                            isTranslatorActive.value = false
                            translationInputText.value = ""
                            translationOutputText.value = ""
                            translationError.value = null
                        }
                    )
                } else if (continuousVoiceActive) {
                    VoiceInputBar(
                        listening = listening,
                        transcript = transcript,
                        errorMessage = errMsg,
                        rms = rms,
                        onCancel = {
                            isContinuousVoiceActive.value = false
                            speechRecognizer?.cancel()
                            isSpeechListening.value = false
                            isSpeechError.value = null
                            setSystemMuteState(false)
                        }
                    )
                } else {
                    SuggestionsAndToolbar(
                        onVoiceClick = { toggleSpeechVoiceInput() },
                        onSettingsClick = {
                            val intent = Intent(this@TanglishInputMethodService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },
                        onTranslateClick = {
                            isTranslatorActive.value = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Active Keys Matrix
                if (symbolsActive) {
                    SymbolsKeyboardRows()
                } else {
                    LettersKeyboardRows(isQwertz = isQwertz)
                }
            }
        }
    }

    @Composable
    fun TranslationPanel(
        inputText: String,
        outputText: String,
        isLoading: Boolean,
        error: String?,
        onInputChanged: (String) -> Unit,
        onTranslate: () -> Unit,
        onInsert: () -> Unit,
        onClose: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(Color(0xFF202224), shape = RoundedCornerShape(12.dp))
                .border(width = 1.dp, color = Color(0xFF383C40), shape = RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Translator (Any Language ➔ Tanglish)",
                    color = Color(0xFFD1E4FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Translator",
                        tint = Color(0xFF909094),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Text Input field for foreign text
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChanged,
                placeholder = { Text("Enter text in any language to translate...", fontSize = 11.sp, color = Color(0xFF6B6E72)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 80.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3A86FF),
                    unfocusedBorderColor = Color(0xFF383C40),
                    focusedContainerColor = Color(0xFF16181A),
                    unfocusedContainerColor = Color(0xFF16181A)
                ),
                shape = RoundedCornerShape(8.dp)
            )

            if (outputText.isNotBlank() || error != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF16181A), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = error ?: outputText,
                        color = if (error != null) Color(0xFFFFB4AB) else Color(0xFFE2E2E6),
                        fontSize = 12.sp,
                        fontWeight = if (error != null) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFD1E4FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                TextButton(
                    onClick = onTranslate,
                    enabled = inputText.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF3A86FF),
                        disabledContentColor = Color(0xFF53565A)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Translate", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = onInsert,
                    enabled = outputText.isNotBlank() && outputText != "Translating..." && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF004B73),
                        contentColor = Color(0xFFD1E4FF),
                        disabledContainerColor = Color(0xFF2A2D30),
                        disabledContentColor = Color(0xFF6B6E72)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Insert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun VoiceInputBar(
        listening: Boolean,
        transcript: String,
        errorMessage: String?,
        rms: Float,
        onCancel: () -> Unit
    ) {
        val animatedWaveScale by animateFloatAsState(
            targetValue = if (listening) 1f + (rms / 20f).coerceIn(0f, 1f) else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            label = "Wave Ripple"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2D3033))
                .border(
                    width = 1.dp,
                    color = Color(0xFF44474B),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFD1E4FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (errorMessage != null) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error Indicator",
                                tint = Color(0xFF00314F),
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice Input Active",
                                tint = Color(0xFF00314F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (errorMessage != null) "Speech Error" else "Listening...",
                            color = Color(0xFFD1E4FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = errorMessage ?: transcript,
                            color = Color(0xFF909094),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    val isSaver = activeBatteryProfile.value == "Battery Saver"
                    if (listening && !isSaver) {
                        Box(modifier = Modifier.size(width = 4.dp, height = (12.dp.value * animatedWaveScale).dp).clip(RoundedCornerShape(50)).background(Color(0xFFD1E4FF)))
                        Box(modifier = Modifier.size(width = 4.dp, height = (20.dp.value * animatedWaveScale).dp).clip(RoundedCornerShape(50)).background(Color(0xFFD1E4FF)))
                        Box(modifier = Modifier.size(width = 4.dp, height = (16.dp.value * animatedWaveScale).dp).clip(RoundedCornerShape(50)).background(Color(0xFFD1E4FF)))
                    } else {
                        Box(modifier = Modifier.size(width = 4.dp, height = 8.dp).clip(RoundedCornerShape(50)).background(Color(0xFF44474B)))
                        Box(modifier = Modifier.size(width = 4.dp, height = 12.dp).clip(RoundedCornerShape(50)).background(Color(0xFF44474B)))
                        Box(modifier = Modifier.size(width = 4.dp, height = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF44474B)))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("X", color = Color(0xFFD1E4FF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }

    @Composable
    fun SuggestionsAndToolbar(
        onVoiceClick: () -> Unit,
        onSettingsClick: () -> Unit,
        onTranslateClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFF1A1C1E))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Replaced the 'G' icon in the top left with "Tanglish" text label
            Text(
                text = "Tanglish",
                color = Color(0xFFD1E4FF),
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Microphone continuous tap entry point in the suggestions bar (emojiless)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF00314F))
                            .border(1.dp, Color(0xFFD1E4FF), RoundedCornerShape(20.dp))
                            .clickable { onVoiceClick() }
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Speak Tamil", color = Color(0xFFD1E4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("(English preserved)", color = Color(0xFF909094), fontSize = 9.sp)
                        }
                    }
                }
            }

            // Quick function keys (emojiless)
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onTranslateClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Translate to Tanglish",
                        tint = Color(0xFFD1E4FF),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFFE2E2E6),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier
                        .testTag("microphone_button")
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = Color(0xFFD1E4FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun RowScope.KeyboardKey(
        label: String,
        subLabel: String? = null,
        weight: Float = 1f,
        isFunctional: Boolean = false,
        repeatOnLongPress: Boolean = false,
        icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ) {
        val isEnterKey = (label == "↵" || label == "keyboard_return")
        
        val keyColor = when {
            isEnterKey -> Color(0xFFD1E4FF)
            isFunctional -> Color(0xFF44474B)
            else -> Color(0xFF333539)
        }
        
        val textColor = when {
            isEnterKey -> Color(0xFF00314F)
            label == "Space" || label.startsWith("Tanglish") -> Color(0xFF909094)
            else -> Color(0xFFE2E2E6)
        }

        val keyShape = if (isEnterKey) RoundedCornerShape(12.dp) else RoundedCornerShape(8.dp)
        
        val baseModifier = Modifier
            .height(54.dp)
            .weight(weight)
            .padding(horizontal = 3.dp, vertical = 3.dp)
            .clip(keyShape)
            .background(keyColor)
            .testTag("key_$label")

        val finalModifier = if (repeatOnLongPress) {
            baseModifier.pointerInput(onClick) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        onClick() // Delete immediately on touch
                        
                        var repeatJob: Job? = null
                        var timerJob: Job? = null
                        try {
                            timerJob = serviceScope.launch {
                                delay(400) // Hold duration threshold
                                repeatJob = serviceScope.launch {
                                    while (isActive) {
                                        onClick()
                                        delay(65) // Swift repeat rhythm
                                    }
                                }
                            }
                            waitForUpOrCancellation()
                        } finally {
                            timerJob?.cancel()
                            repeatJob?.cancel()
                        }
                    }
                }
            }
        } else if (onLongClick != null) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            baseModifier.combinedClickable(
                onLongClick = { onLongClick() },
                onClick = { onClick() }
            )
        } else {
            baseModifier.clickable { onClick() }
        }

        Box(
            modifier = finalModifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxHeight()
            ) {
                if (subLabel != null) {
                    Text(
                        text = subLabel,
                        color = Color(0xFF909094),
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.End).padding(end = 5.dp)
                    )
                }
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = if (label.length > 3) 11.sp else 18.sp,
                        fontWeight = if (isEnterKey) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    fun LettersKeyboardRows(isQwertz: Boolean) {
        val row1 = if (isQwertz) {
            listOf("q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5", "z" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0")
        } else {
            listOf("q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5", "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0")
        }
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val shiftValue by isShiftActive

        val row3 = if (isQwertz) {
            listOf("y", "x", "c", "v", "b", "n", "m")
        } else {
            listOf("z", "x", "c", "v", "b", "n", "m")
        }

        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            // Row 1
            Row(modifier = Modifier.fillMaxWidth()) {
                row1.forEach { (char, num) ->
                    KeyboardKey(
                        label = if (shiftValue > 0) char.uppercase() else char,
                        subLabel = num
                    ) {
                        handleKeypress(char)
                    }
                }
            }

            // Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                row2.forEach { char ->
                    KeyboardKey(
                        label = if (shiftValue > 0) char.uppercase() else char
                    ) {
                        handleKeypress(char)
                    }
                }
            }

            // Row 3 (Shift, letters, backspace)
            Row(modifier = Modifier.fillMaxWidth()) {
                val shiftText = when (shiftValue) {
                    2 -> "▲▲"
                    1 -> "▲"
                    else -> "△"
                }
                KeyboardKey(
                    label = shiftText,
                    weight = 1.3f,
                    isFunctional = true
                ) {
                    triggerHapticFeedback()
                    isShiftActive.value = (shiftValue + 1) % 3
                }

                row3.forEach { char ->
                    KeyboardKey(
                        label = if (shiftValue > 0) char.uppercase() else char
                    ) {
                        handleKeypress(char)
                    }
                }

                // Backspace with continuous repeat hold delete
                KeyboardKey(
                    label = "⌫",
                    weight = 1.3f,
                    isFunctional = true,
                    repeatOnLongPress = true
                ) {
                    handleBackspace()
                }
            }

            // Row 4 (Switch, System Settings, Voice Input, Space, Dot, Enter) (Emojiless)
            val currentProfile = activeBatteryProfile.value
            Row(modifier = Modifier.fillMaxWidth()) {
                KeyboardKey(label = "?123", weight = 1.4f, isFunctional = true) {
                    triggerHapticFeedback()
                    isKeyboardSymbols.value = true
                }

                KeyboardKey(
                    label = "SET",
                    weight = 1.1f,
                    isFunctional = true,
                    icon = Icons.Default.Settings
                ) {
                    triggerHapticFeedback()
                    val intent = Intent(this@TanglishInputMethodService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }

                KeyboardKey(
                    label = "MIC",
                    weight = 1.1f,
                    isFunctional = true,
                    icon = Icons.Default.Mic
                ) {
                    toggleSpeechVoiceInput()
                }

                // Space bar with IME long press switcher support
                KeyboardKey(
                    label = "Tanglish ($currentProfile)",
                    weight = 4.2f,
                    isFunctional = false,
                    onLongClick = {
                        triggerHapticFeedback()
                        try {
                            val im = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            im?.showInputMethodPicker()
                        } catch (e: Throwable) {
                            // Safe fallback
                        }
                    }
                ) {
                    handleSpace()
                }

                KeyboardKey(label = ".", weight = 0.9f) {
                    handleKeypress(".")
                }

                KeyboardKey(label = "↵", weight = 1.4f, isFunctional = true) {
                    handleEnter()
                }
            }
        }
    }

    @Composable
    fun SymbolsKeyboardRows() {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row2 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")")
        val row3 = listOf("*", "\"", "'", ":", ";", "!", "?")

        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            // Row 1
            Row(modifier = Modifier.fillMaxWidth()) {
                row1.forEach { char ->
                    KeyboardKey(label = char) {
                        handleKeypress(char)
                    }
                }
            }

            // Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                row2.forEach { char ->
                    KeyboardKey(label = char) {
                        handleKeypress(char)
                    }
                }
            }

            // Row 3 (Switch to Secondary Symbols, primary symbols, Backspace)
            Row(modifier = Modifier.fillMaxWidth()) {
                KeyboardKey(label = "=\\<", weight = 1.3f, isFunctional = true) {
                    triggerHapticFeedback()
                }

                row3.forEach { char ->
                    KeyboardKey(label = char) {
                        handleKeypress(char)
                    }
                }

                // Backspace with continuous repeat hold delete
                KeyboardKey(
                    label = "⌫",
                    weight = 1.3f,
                    isFunctional = true,
                    repeatOnLongPress = true
                ) {
                    handleBackspace()
                }
            }

            // Row 4 (Emojiless)
            Row(modifier = Modifier.fillMaxWidth()) {
                KeyboardKey(label = "ABC", weight = 1.4f, isFunctional = true) {
                    triggerHapticFeedback()
                    isKeyboardSymbols.value = false
                }

                KeyboardKey(
                    label = "SET",
                    weight = 1.1f,
                    isFunctional = true,
                    icon = Icons.Default.Settings
                ) {
                    triggerHapticFeedback()
                    val intent = Intent(this@TanglishInputMethodService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }

                KeyboardKey(
                    label = "MIC",
                    weight = 1.1f,
                    isFunctional = true,
                    icon = Icons.Default.Mic
                ) {
                    toggleSpeechVoiceInput()
                }

                KeyboardKey(
                    label = "Space",
                    weight = 4.2f,
                    onLongClick = {
                        triggerHapticFeedback()
                        try {
                            val im = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            im?.showInputMethodPicker()
                        } catch (e: Throwable) {
                            // Safe fallback
                        }
                    }
                ) {
                    handleSpace()
                }

                KeyboardKey(label = "/", weight = 0.9f) {
                    handleKeypress("/")
                }

                KeyboardKey(label = "↵", weight = 1.4f, isFunctional = true) {
                    handleEnter()
                }
            }
        }
    }
}
