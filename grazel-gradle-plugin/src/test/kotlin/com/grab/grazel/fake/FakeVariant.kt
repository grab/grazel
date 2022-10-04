/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.fake

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.api.JavaCompileOptions
import com.android.build.gradle.api.SourceKind
import com.android.build.gradle.tasks.AidlCompile
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import com.android.build.gradle.tasks.GenerateBuildConfig
import com.android.build.gradle.tasks.MergeResources
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.android.build.gradle.tasks.RenderscriptCompile
import com.android.builder.model.BuildType
import com.android.builder.model.ClassField
import com.android.builder.model.ProductFlavor
import com.android.builder.model.SourceProvider
import org.gradle.api.DomainObjectCollection
import org.gradle.api.Task
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.resources.TextResource
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

internal const val FLAVOR1 = "flavor1"
internal const val FLAVOR2 = "flavor2"
internal const val DEBUG_FLAVOR1 = "debugFlavor1"
internal const val DEBUG_FLAVOR2 = "debugFlavor2"
internal const val RELEASE_FLAVOR1 = "releaseFlavor1"
internal const val RELEASE_FLAVOR2 = "releaseFlavor2"

class FakeVariant(
    private val name: String,
    private val flavor: String? = null,
    private val sourceSets: List<SourceProvider> = emptyList()
) : BaseVariant {
    override fun getProductFlavors(): MutableList<ProductFlavor> =
        if (flavor != null) mutableListOf(FakeProductFlavor(flavor))
        else mutableListOf()

    override fun getBuildType(): BuildType = FakeBuildType()
    override fun getDirName(): String = ""
    override fun getApplicationId(): String = "id"
    override fun register(task: Task?) {}
    override fun getDescription(): String = ""
    override fun registerExternalAptJavaOutput(folder: ConfigurableFileTree?) {}
    override fun setOutputsAreSigned(isSigned: Boolean) {}
    override fun registerJavaGeneratingTask(task: Task?, vararg sourceFolders: File?) {}
    override fun registerJavaGeneratingTask(task: Task?, sourceFolders: MutableCollection<File>?) {}
    override fun missingDimensionStrategy(dimension: String?, requestedValue: String?) {}
    override fun missingDimensionStrategy(dimension: String?, vararg requestedValues: String?) {}
    override fun missingDimensionStrategy(d: String?, v: MutableList<String>?) {}
    override fun getName(): String = name
    override fun getOutputsAreSigned(): Boolean = false
    override fun getFlavorName(): String = ""
    override fun buildConfigField(type: String?, name: String?, value: String?) {}
    override fun resValue(type: String?, name: String?, value: String?) {}
    override fun registerPostJavacGeneratedBytecode(fileCollection: FileCollection?) {}
    override fun addJavaSourceFoldersToModel(vararg sourceFolders: File?) {}
    override fun addJavaSourceFoldersToModel(sourceFolders: MutableCollection<File>?) {}

    override fun getCheckManifestProvider(): TaskProvider<Task> {
        TODO("Not yet implemented")
    }

    override fun getPreBuild(): Task {
        TODO("Not yet implemented")
    }

    override fun getSourceSets(): MutableList<SourceProvider> {
        return sourceSets.toMutableList()
    }

    override fun getJavaCompileOptions(): JavaCompileOptions {
        TODO("Not yet implemented")
    }

    override fun getMappingFile(): File {
        TODO("Not yet implemented")
    }

    override fun getOutputs(): DomainObjectCollection<BaseVariantOutput> {
        TODO("Not yet implemented")
    }

    override fun registerPreJavacGeneratedBytecode(fileCollection: FileCollection?): Any {
        TODO("Not yet implemented")
    }

    override fun getProcessJavaResources(): AbstractCopyTask {
        TODO("Not yet implemented")
    }


    override fun getRenderscriptCompileProvider(): TaskProvider<RenderscriptCompile> {
        TODO("Not yet implemented")
    }

    override fun getMergedFlavor(): ProductFlavor {
        TODO("Not yet implemented")
    }

    override fun getApplicationIdTextResource(): TextResource {
        TODO("Not yet implemented")
    }

    override fun getAssemble(): Task {
        TODO("Not yet implemented")
    }

    override fun getAidlCompile(): AidlCompile {
        TODO("Not yet implemented")
    }

    override fun getJavaCompiler(): Task {
        TODO("Not yet implemented")
    }

    override fun getSourceFolders(folderType: SourceKind?): MutableList<ConfigurableFileTree> {
        TODO("Not yet implemented")
    }

    override fun getAllRawAndroidResources(): FileCollection {
        TODO("Not yet implemented")
    }

    override fun getAidlCompileProvider(): TaskProvider<AidlCompile> {
        TODO("Not yet implemented")
    }

    override fun getPreBuildProvider(): TaskProvider<Task> {
        TODO("Not yet implemented")
    }

    override fun getJavaCompileProvider(): TaskProvider<JavaCompile> {
        TODO("Not yet implemented")
    }

    override fun getCheckManifest(): Task {
        TODO("Not yet implemented")
    }

    override fun getMergeAssets(): MergeSourceSetFolders {
        TODO("Not yet implemented")
    }

    override fun getMergeResources(): MergeResources {
        TODO("Not yet implemented")
    }

    override fun getRuntimeConfiguration(): Configuration {
        TODO("Not yet implemented")
    }

    override fun getCompileClasspathArtifacts(key: Any?): ArtifactCollection {
        TODO("Not yet implemented")
    }

    override fun getMappingFileProvider(): Provider<FileCollection> {
        TODO("Not yet implemented")
    }

    override fun getJavaCompile(): JavaCompile {
        TODO("Not yet implemented")
    }

    override fun getExternalNativeBuildTasks(): MutableCollection<ExternalNativeBuildTask> {
        TODO("Not yet implemented")
    }

    override fun getMergeAssetsProvider(): TaskProvider<MergeSourceSetFolders> {
        TODO("Not yet implemented")
    }

    override fun getGenerateBuildConfig(): GenerateBuildConfig {
        TODO("Not yet implemented")
    }

    override fun getExternalNativeBuildProviders(): MutableCollection<TaskProvider<ExternalNativeBuildTask>> {
        TODO("Not yet implemented")
    }

    override fun registerGeneratedResFolders(folders: FileCollection?) {
        TODO("Not yet implemented")
    }

    override fun getRenderscriptCompile(): RenderscriptCompile {
        TODO("Not yet implemented")
    }

    override fun getBaseName(): String {
        TODO("Not yet implemented")
    }

    override fun getGenerateBuildConfigProvider(): TaskProvider<GenerateBuildConfig> {
        TODO("Not yet implemented")
    }

    override fun getAssembleProvider(): TaskProvider<Task> {
        TODO("Not yet implemented")
    }

    override fun registerResGeneratingTask(task: Task?, vararg resFolders: File?) {
        TODO("Not yet implemented")
    }

    override fun registerResGeneratingTask(task: Task?, resFolders: MutableCollection<File>?) {
        TODO("Not yet implemented")
    }

    override fun getMergeResourcesProvider(): TaskProvider<MergeResources> {
        TODO("Not yet implemented")
    }

    override fun getCompileConfiguration(): Configuration {
        TODO("Not yet implemented")
    }

    override fun registerGeneratedBytecode(fileCollection: FileCollection?): Any {
        TODO("Not yet implemented")
    }

    override fun getProcessJavaResourcesProvider(): TaskProvider<AbstractCopyTask> {
        TODO("Not yet implemented")
    }

    override fun getAnnotationProcessorConfiguration(): Configuration {
        TODO("Not yet implemented")
    }

    override fun getObfuscation(): Task {
        TODO("Not yet implemented")
    }

    override fun getCompileClasspath(key: Any?): FileCollection {
        TODO("Not yet implemented")
    }

    override fun registerJavaGeneratingTask(
        taskProvider: TaskProvider<out Task>?,
        vararg sourceFolders: File?
    ) {
        TODO("Not yet implemented")
    }

    override fun registerJavaGeneratingTask(
        taskProvider: TaskProvider<out Task>?,
        sourceFolders: MutableCollection<File>?
    ) {
        TODO("Not yet implemented")
    }
}

class FakeBuildType : BuildType {
    override val applicationIdSuffix: String?
        get() = TODO("Not yet implemented")
    override val buildConfigFields: Map<String, ClassField>
        get() = mapOf()
    override val consumerProguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val isDebuggable: Boolean
        get() = TODO("Not yet implemented")
    override val isEmbedMicroApp: Boolean
        get() = TODO("Not yet implemented")
    override val isJniDebuggable: Boolean
        get() = TODO("Not yet implemented")
    override val isMinifyEnabled: Boolean
        get() = TODO("Not yet implemented")
    override val isPseudoLocalesEnabled: Boolean
        get() = TODO("Not yet implemented")
    override val isRenderscriptDebuggable: Boolean
        get() = TODO("Not yet implemented")
    override val isTestCoverageEnabled: Boolean
        get() = TODO("Not yet implemented")
    override val isZipAlignEnabled: Boolean
        get() = TODO("Not yet implemented")
    override val manifestPlaceholders: Map<String, Any>
        get() = TODO("Not yet implemented")
    override val multiDexEnabled: Boolean?
        get() = TODO("Not yet implemented")
    override val multiDexKeepFile: File?
        get() = TODO("Not yet implemented")
    override val multiDexKeepProguard: File?
        get() = TODO("Not yet implemented")
    override val proguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val renderscriptOptimLevel: Int
        get() = TODO("Not yet implemented")
    override val resValues: Map<String, ClassField>
        get() = TODO("Not yet implemented")
    override val testProguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val versionNameSuffix: String?
        get() = TODO("Not yet implemented")

    override fun getName(): String {
        return "debug"
    }
}