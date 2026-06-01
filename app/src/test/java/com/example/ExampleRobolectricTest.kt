package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.TamilToTanglishTransliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Tanglish Keyboard", appName)
    }

    @Test
    fun testTamilWordDetection() {
        assertTrue(TamilToTanglishTransliterator.isTamilWord("வணக்கம்"))
        assertTrue(TamilToTanglishTransliterator.isTamilWord("நன்றி"))
        assertFalse(TamilToTanglishTransliterator.isTamilWord("Hello"))
        assertFalse(TamilToTanglishTransliterator.isTamilWord("Tanglish"))
    }

    @Test
    fun testTransliterationCoreSyllables() {
        // "வணக்கம்" -> "vanakkam"
        val word1 = TamilToTanglishTransliterator.transliterate("வணக்கம்")
        assertEquals("vanakkam", word1)

        // "நன்றி" -> "nanri"
        val word2 = TamilToTanglishTransliterator.transliterate("நன்றி")
        assertEquals("nanri", word2)

        // English words remain untouched
        val englishWord = TamilToTanglishTransliterator.transliterate("Keyboard")
        assertEquals("Keyboard", englishWord)
    }

    @Test
    fun testSentenceTransliterationWithCastingAndDict() {
        // Sentence with mixed values
        val sentence = "வணக்கம் Android keyboard"
        val transliterated = TamilToTanglishTransliterator.transliterateSentence(sentence, emptyMap())
        assertEquals("vanakkam Android keyboard", transliterated)

        // Dictionary override behavior
        val userDict = mapOf("வணக்கம்" to "vanakkam_custom")
        val transliteratedCustom = TamilToTanglishTransliterator.transliterateSentence("வணக்கம்", userDict)
        assertEquals("vanakkam_custom", transliteratedCustom)
    }
}
