package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserWordDao {
    @Query("SELECT * FROM user_dictionary ORDER BY addedTimestamp DESC")
    fun getAllWordsFlow(): Flow<List<UserWord>>

    @Query("SELECT * FROM user_dictionary")
    suspend fun getAllWords(): List<UserWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(word: UserWord)

    @Delete
    suspend fun deleteWord(word: UserWord)

    @Query("DELETE FROM user_dictionary WHERE tamilWord = :tamilWord")
    suspend fun deleteWordByKey(tamilWord: String)

    @Query("SELECT * FROM user_dictionary WHERE tamilWord = :tamilWord LIMIT 1")
    suspend fun getWord(tamilWord: String): UserWord?
}
