package com.example.grabit_test.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.grabit_test.data.history.SearchHistoryDao
import com.example.grabit_test.data.history.SearchHistoryItem
import com.example.grabit_test.data.product.ProductDimension
import com.example.grabit_test.data.product.ProductDimensionDao

@Database(
    entities = [SearchHistoryItem::class, ProductDimension::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun productDimensionDao(): ProductDimensionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "grabit_db"
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
