package com.evilgpt.galleryuploader

import android.content.Context
import androidx.room.*

@Entity(tableName = "uploaded_files")
data class UploadedFile(
    @PrimaryKey val fileKey: String,
    val timestamp: Long
)

@Dao
interface UploadDao {
    @Query("SELECT fileKey FROM uploaded_files")
    suspend fun getAllUploadedKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: UploadedFile)
}

@Database(entities = [UploadedFile::class], version = 1)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        @Volatile
        private var INSTANCE: UploadDatabase? = null

        fun getDatabase(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "upload_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
