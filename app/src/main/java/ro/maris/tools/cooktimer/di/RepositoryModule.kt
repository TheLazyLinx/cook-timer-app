package ro.maris.tools.cooktimer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.data.repository.RoomPresetRepository
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        repository: RoomPresetRepository
    ): PresetRepository
}
