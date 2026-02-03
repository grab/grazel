package com.grab.grazel.gradle.variant

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.util.GradleProvider
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

    @Binds
    fun DefaultVariantEquivalenceChecker.bindEquivalenceChecker(): VariantEquivalenceChecker

    @Binds
    fun DefaultVariantCompressor.bindCompressor(): VariantCompressor

    @Binds
    fun DefaultDependencyNormalizer.bindNormalizer(): DependencyNormalizer

    companion object {
        @Provides
        @Singleton
        fun GrazelExtension.provideAndroidVariantDataSource(
            androidVariantsExtractor: DefaultAndroidVariantsExtractor,
        ): AndroidVariantDataSource = DefaultAndroidVariantDataSource(
            variantFilterProvider = { android.variantFilter },
            androidVariantsExtractor = androidVariantsExtractor
        )

        @Provides
        @Singleton
        fun variantCompressionService(
            @RootProject rootProject: Project
        ): GradleProvider<@JvmSuppressWildcards DefaultVariantCompressionService> =
            DefaultVariantCompressionService.register(rootProject)
    }
}