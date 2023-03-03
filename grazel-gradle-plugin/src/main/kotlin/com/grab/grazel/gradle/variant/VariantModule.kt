package com.grab.grazel.gradle.variant

import dagger.Binds
import dagger.Module

@Module
internal interface VariantModule {
    @Binds
    fun DefaultVariantBuilder.bindBuilder(): VariantBuilder

    @Binds
    fun DefaultVariantMatcher.bindMatcher(): VariantMatcher
}