package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.data.model.AccountProfile
import com.example.data.model.BrowserBookmark
import com.example.data.model.MacroScript
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Query("SELECT * FROM macro_scripts ORDER BY id DESC")
    fun getAllScripts(): Flow<List<MacroScript>>

    @Query("SELECT * FROM macro_scripts WHERE id = :id")
    suspend fun getScriptById(id: Long): MacroScript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: MacroScript): Long

    @Update
    suspend fun updateScript(script: MacroScript)

    @Delete
    suspend fun deleteScript(script: MacroScript)

    @Query("DELETE FROM macro_scripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM macro_scripts")
    suspend fun countScripts(): Int
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account_profiles ORDER BY lastUsedTime DESC")
    fun getAllAccounts(): Flow<List<AccountProfile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountProfile): Long

    @Update
    suspend fun updateAccount(account: AccountProfile)

    @Delete
    suspend fun deleteAccount(account: AccountProfile)

    @Query("DELETE FROM account_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM account_profiles")
    suspend fun countAccounts(): Int
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM browser_bookmarks ORDER BY isGamePreset DESC, id ASC")
    fun getAllBookmarks(): Flow<List<BrowserBookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BrowserBookmark): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BrowserBookmark)

    @Query("SELECT COUNT(*) FROM browser_bookmarks")
    suspend fun countBookmarks(): Int
}

@Database(
    entities = [MacroScript::class, AccountProfile::class, BrowserBookmark::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao
    abstract fun accountDao(): AccountDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tabx_browser_database.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
