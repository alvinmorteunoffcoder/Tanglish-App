package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_dictionary")
data class UserWord(
    @PrimaryKey val tamilWord: String,
    val customTanglish: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)
