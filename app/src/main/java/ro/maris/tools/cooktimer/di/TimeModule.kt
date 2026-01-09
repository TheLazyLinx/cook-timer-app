package ro.maris.tools.cooktimer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.domain.engine.TimeProvider

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    fun provideTimeProvider(): TimeProvider {
        return TimeProvider { System.currentTimeMillis() / 1000L }
    }
}
