package ro.maris.tools.cooktimer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.maris.tools.cooktimer.ui.haptics.AndroidVibrationHelper
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HapticsModule {

    @Binds
    @Singleton
    abstract fun bindVibrationHelper(
        impl: AndroidVibrationHelper
    ): VibrationHelper
}
