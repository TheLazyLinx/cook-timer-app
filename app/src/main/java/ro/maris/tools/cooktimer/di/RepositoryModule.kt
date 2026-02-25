package ro.maris.tools.cooktimer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.data.billing.PlayBillingRepository
import ro.maris.tools.cooktimer.data.repository.DataStoreSettingsRepository
import ro.maris.tools.cooktimer.data.repository.RoomPresetRepository
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        repository: RoomPresetRepository
    ): PresetRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        repository: DataStoreSettingsRepository
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBillingRepository(
        repository: PlayBillingRepository
    ): BillingRepository
}
