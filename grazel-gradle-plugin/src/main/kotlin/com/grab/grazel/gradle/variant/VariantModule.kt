package com.grab.grazel.gradle.variant

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.gradle.api.Project
import javax.inject.Singleton

@Module
internal interface VariantModule {
    @Binds
    fun DefaultVariantBuilder.bindBuilder(): VariantBuilder

    @Binds
    fun DefaultVariantMatcher.bindMatcher(): VariantMatcher

    @Binds
    fun DefaultAndroidVariantsExtractor.bindAndroidVariantsExtractor(): AndroidVariantsExtractor

    companion object {
        @Provides
        @Singleton
        fun GrazelExtension.provideAndroidVariantDataSource(
            androidVariantsExtractor: DefaultAndroidVariantsExtractor,
            @RootProject rootProject: Project
        ): AndroidVariantDataSource = DefaultAndroidVariantDataSource(
            variantFilter = android.variantFilter,
            androidVariantsExtractor = androidVariantsExtractor
        )
    }
}