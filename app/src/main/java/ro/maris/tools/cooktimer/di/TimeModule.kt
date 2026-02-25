package ro.maris.tools.cooktimer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider {
        return TimeProvider { System.currentTimeMillis() / 1000L }
    }
}
