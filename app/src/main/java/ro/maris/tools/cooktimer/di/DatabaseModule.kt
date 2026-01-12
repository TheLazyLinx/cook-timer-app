package ro.maris.tools.cooktimer.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.data.local.db.AppDatabase
import ro.maris.tools.cooktimer.data.local.dao.PresetDao
import ro.maris.tools.cooktimer.data.local.dao.StepDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
    }

    @Provides
    fun providePresetDao(database: AppDatabase): PresetDao {
        return database.presetDao()
    }

    @Provides
    fun provideStepDao(database: AppDatabase): StepDao {
        return database.stepDao()
    }
}
