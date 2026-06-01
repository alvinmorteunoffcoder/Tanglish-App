package com.example.engine

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

object GeminiTranslator {
    private const val TAG = "GeminiTranslator"
    
    // As per Gemini API skill guidelines, increase timeouts to 60s since translation/LLM queries can exceed defaults
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Robust cleaning function to ensure translated text contains zero surrounding formatting, quotes, or preambles
    private fun cleanTranslatedText(raw: String): String {
        var text = raw.trim()
        
        // Remove markdown block wraps if present
        if (text.startsWith("```")) {
            val lines = text.lines()
            if (lines.size >= 2) {
                val contentLines = lines.subList(1, lines.size - 1)
                text = contentLines.joinToString("\n").trim()
            } else {
                text = text.replace("```", "").trim()
            }
        }
        
        // Strip common starting line headers or system label prefixes leakage
        val preambles = listOf(
            "Tanglish Translation:", "Tanglish:", "Translation:", "Output:", "Phonetic Tanglish:", "Modern Tanglish:"
        )
        for (preamble in preambles) {
            if (text.startsWith(preamble, ignoreCase = true)) {
                text = text.substring(preamble.length).trim()
            }
        }
        
        // Remove surrounding quotes if the entire string was wrapped, preserving key characters
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            text = text.substring(1, text.length - 1).trim()
        } else if (text.startsWith("'") && text.endsWith("'") && text.length >= 2) {
            text = text.substring(1, text.length - 1).trim()
        }
        
        return text
    }
 
    private fun getCleanApiKey(): String {
        var key = BuildConfig.GEMINI_API_KEY.trim()
        if (key.startsWith("\"") && key.endsWith("\"") && key.length > 2) {
            key = key.substring(1, key.length - 1).trim()
        } else if (key.startsWith("'") && key.endsWith("'") && key.length > 2) {
            key = key.substring(1, key.length - 1).trim()
        }
        return key
    }

    val isApiKeyAvailable: Boolean
        get() {
            val key = getCleanApiKey()
            return key.isNotEmpty() && key != "MY_GEMINI_API_KEY"
        }
 
    private fun formatUserFriendlyError(rawMessage: String): String {
        val lower = rawMessage.lowercase()
        return when {
            lower.contains("quota exceeded") || lower.contains("rate limit") || lower.contains("quota") || lower.contains("429") -> {
                "Gemini API quota or rate limit exceeded. Please wait a moment and try again, or check your API key/billing in the Secrets panel."
            }
            lower.contains("api key not valid") || lower.contains("invalid key") || lower.contains("key is invalid") || lower.contains("unauthorized") || lower.contains("invalid") -> {
                "The configured Gemini API key is invalid or unauthorized. Please check your key in the Secrets panel."
            }
            lower.contains("timeout") || lower.contains("connect") || lower.contains("host") || lower.contains("socket") -> {
                "Network timeout or connection issue. Please verify your internet connection and try again."
            }
            else -> {
                rawMessage
            }
        }
    }

    suspend fun translateToTanglish(input: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getCleanApiKey()
        val hasTamil = input.any { it in '\u0B80'..'\u0BFF' }

        if (!isApiKeyAvailable) {
            Log.d(TAG, "No API key config. Checking if input is Tamil for offline fallback or failing gracefully.")
            if (hasTamil) {
                val fallback = TamilToTanglishTransliterator.transliterateSentence(input, emptyMap())
                return@withContext Result.success(fallback)
            } else {
                return@withContext Result.failure(Exception("Gemini API Key is unconfigured! Please enter your GEMINI_API_KEY under the Secrets panel in AI Studio to translate from other languages."))
            }
        }

        // Compliant list of active model names, dropping strictly prohibited models (like 1.5/2.0)
        val modelsToTry = listOf(
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )

        var lastException: Throwable = Exception("Unknown translation error")
        
        for (model in modelsToTry) {
            var currentDelay = 400L
            for (attempt in 1..2) {
                Log.d(TAG, "Attempt $attempt using model $model to translate: \"$input\"")
                val result = tryApiCall(input, apiKey, model)
                if (result.isSuccess) {
                    val rawResult = result.getOrNull() ?: ""
                    val cleanedResult = cleanTranslatedText(rawResult)
                    if (cleanedResult.isNotBlank()) {
                        return@withContext Result.success(cleanedResult)
                    }
                }
                
                val exception = result.exceptionOrNull() ?: Exception("Unknown attempt error")
                val errMsg = exception.message ?: ""
                
                // If it is a quota or key issue, stop immediately to avoid confusing multiple fallbacks
                if (errMsg.contains("Quota exceeded", ignoreCase = true) || errMsg.contains("quota", ignoreCase = true) || 
                    errMsg.contains("key", ignoreCase = true) || errMsg.contains("invalid", ignoreCase = true) || 
                    errMsg.contains("unauthorized", ignoreCase = true)) {
                    val friendlyMsg = formatUserFriendlyError(errMsg)
                    return@withContext Result.failure(Exception(friendlyMsg))
                }
                
                lastException = exception
                Log.w(TAG, "Attempt $attempt using $model failed: ${lastException.message}")
                if (attempt < 2) {
                    try {
                        delay(currentDelay)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    currentDelay *= 2
                }
            }
        }

        // FALLBACK DECISION LOGIC: SUCCESS RATE + ACCURATE REPORTING
        if (hasTamil) {
            Log.e(TAG, "All translation attempts failed. Falling back to local transliterator for Tamil input.")
            val fallbackText = TamilToTanglishTransliterator.transliterateSentence(input, emptyMap())
            Result.success(fallbackText)
        } else {
            Log.e(TAG, "All translation attempts failed for non-Tamil input. Returning failure so user knows it failed.", lastException)
            val friendlyMsg = formatUserFriendlyError(lastException.message ?: "Unknown error")
            Result.failure(Exception(friendlyMsg))
        }
    }

    private suspend fun tryApiCall(input: String, apiKey: String, modelName: String): Result<String> = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        
        val systemInstruction = """
            You are an expert translator and transliterator specializing in converting inputs from ANY language (Tamil, English, Hindi, Telugu, Malayalam, Kannada, Hinglish, Bengali, etc.) into modern natural phonetic Tanglish (Tamil spoken words written in English alphabet characters as used in casual chat/messaging).
            
            Strict Guidelines:
            1. Translate the semantic meaning of any input sentence into natural, colloquial, conversational spoken Tamil first.
            2. Represent this spoken Tamil translation using standard, modern, phonetically natural, and highly readable Tanglish (e.g., "enna panra", "romba nandri", "un peyar enna", "pesunga", "sapteeya", "pannunga", "vaanga", "ponga", "aprom enna vishesam", "veetula ellarum nallama").
            3. CRITICAL - PRESERVE ALL ORIGINAL ENGLISH WORDS: You must identify and preserve ANY and ALL original standard English words (such as "hospital", "accident", "beach", "phone", "call", "office", "bus", "time", "doctor", "message", "chat", "love", "friend", "school", "ticket", "station", "train", "flight", "money", "card", "hotel", "telegram", "whatsapp", "gpay", "app", "download", "link", "online", "clear", "data", "developer", "server", "game") in their exact standard correct English spelling instead of phonetic spelling or transliterations (e.g., write "hospital", not "haaspat" or "haaspital"; write "office", not "aapis"; write "message", not "mesaej" or "meseg").
            4. Keep punctuation intact.
            5. Output ONLY the final Tanglish translation. Absolutely no preamble, no commentary, no bullet points, no wrapping quotes, and no extra explanation! Only the translation line itself.
            
            Few-Shot Examples:
            
            Input: "what are you doing?"
            Output: enna panra?
            
            Input: "आप का नाम क्या है?"
            Output: unga peyar enna?
            
            Input: "എനിക്ക് സുഖമാണ്."
            Output: enakku nalla iruku.
            
            Input: "Where is the nearest hospital?"
            Output: nearest hospital enga iruku?
            
            Input: "ரொம்ப நன்றி, அப்புறம் பார்க்கலாம்"
            Output: romba nandri, aprom pakalam
            
            Input: "Main rasta bhul gaya, please help me locate the correct address"
            Output: naan vazhi marandhutaen, please help me correct address locate panradhula
            
            Input: "Can you send the telegram group invite link?"
            Output: telegram group invite link send panna mudiyuma?
            
            Input: "நான் இப்போது விளையாடிக் கொண்டிருக்கிறேன்"
            Output: naan ippo vilayaditu irukaen
        """.trimIndent()

        try {
            val root = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", input)
                            }
                            put(partObj)
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
                
                val sysInstructionObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", systemInstruction)
                        }
                        put(partObj)
                    }
                    put("parts", parts)
                }
                put("systemInstruction", sysInstructionObj)
            }

            val mediaType = "application/json".toMediaType()
            val requestBody = root.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: throw Exception("Empty response from server")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Request failed: code=${response.code} body=$responseBody")
                    
                    // Attempt parsing detailed server error message if available
                    val errMsg = try {
                        val json = JSONObject(responseBody)
                        json.getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "Gemini server error: ${response.code}"
                    }
                    return@withContext Result.failure(Exception(errMsg))
                }

                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.has("error")) {
                    val errorObj = jsonResponse.getJSONObject("error")
                    val errorMsg = errorObj.optString("message", "API Request Error")
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    if (content != null) {
                        val parts = content.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "").trim()
                            if (text.isNotEmpty()) {
                                return@withContext Result.success(text)
                            }
                        }
                    }
                    
                    // Check safety or blocked reasons
                    val finishReason = candidate.optString("finishReason", "")
                    if (finishReason.isNotEmpty() && finishReason != "STOP") {
                        return@withContext Result.failure(Exception("Blocked by safety filters: $finishReason"))
                    }
                }
                
                Result.failure(Exception("Failed to decode translation response from Gemini API."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation api network error", e)
            Result.failure(e)
        }
    }
}
