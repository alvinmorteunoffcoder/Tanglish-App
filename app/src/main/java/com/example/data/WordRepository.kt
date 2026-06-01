package com.example.data

import kotlinx.coroutines.flow.Flow

class WordRepository(private val dao: UserWordDao) {
    val allWordsFlow: Flow<List<UserWord>> = dao.getAllWordsFlow()

    suspend fun getAllWords(): List<UserWord> = dao.getAllWords()

    suspend fun insert(word: UserWord) = dao.insertWord(word)

    suspend fun delete(word: UserWord) = dao.deleteWord(word)

    suspend fun deleteWordByKey(tamilWord: String) = dao.deleteWordByKey(tamilWord)

    suspend fun getWord(tamilWord: String): UserWord? = dao.getWord(tamilWord)
}
