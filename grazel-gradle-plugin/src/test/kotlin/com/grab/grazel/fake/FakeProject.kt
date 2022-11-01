/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.AntBuilder
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.PathValidation
import org.gradle.api.Project
import org.gradle.api.ProjectState
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.ArtifactHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.DependencyLockingHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.component.SoftwareComponentContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DeleteSpec
import org.gradle.api.file.FileTree
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.SyncSpec
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.logging.LoggingManager
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.ObjectConfigurationAction
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.plugins.PluginManager
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.resources.ResourceHandler
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.WorkResult
import org.gradle.normalization.InputNormalizationHandler
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import java.io.File
import java.net.URI
import java.util.concurrent.Callable

class FakeProject(private val name: String) : Project {
    var shadowConfigurations : List<Configuration> = emptyList()

    override fun toString(): String = name

    override fun compareTo(other: Project?): Int {
        TODO("Not yet implemented")
    }

    override fun getExtensions(): ExtensionContainer {
        TODO("Not yet implemented")
    }

    override fun getPlugins(): PluginContainer {
        TODO("Not yet implemented")
    }

    override fun apply(closure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun apply(action: Action<in ObjectConfigurationAction>) {
        TODO("Not yet implemented")
    }

    override fun apply(options: MutableMap<String, *>) {
        TODO("Not yet implemented")
    }

    override fun getPluginManager(): PluginManager {
        TODO("Not yet implemented")
    }

    override fun getRootProject(): Project {
        TODO("Not yet implemented")
    }

    override fun getRootDir(): File {
        TODO("Not yet implemented")
    }

    override fun getBuildDir(): File {
        TODO("Not yet implemented")
    }

    override fun setBuildDir(path: File) {
        TODO("Not yet implemented")
    }

    override fun setBuildDir(path: Any) {
        TODO("Not yet implemented")
    }

    override fun getBuildFile(): File {
        TODO("Not yet implemented")
    }

    override fun getParent(): Project? {
        TODO("Not yet implemented")
    }

    override fun getName(): String = name

    override fun getDisplayName(): String {
        TODO("Not yet implemented")
    }

    override fun getDescription(): String? {
        TODO("Not yet implemented")
    }

    override fun setDescription(description: String?) {
        TODO("Not yet implemented")
    }

    override fun getGroup(): Any {
        TODO("Not yet implemented")
    }

    override fun setGroup(group: Any) {
        TODO("Not yet implemented")
    }

    override fun getVersion(): Any {
        TODO("Not yet implemented")
    }

    override fun setVersion(version: Any) {
        TODO("Not yet implemented")
    }

    override fun getStatus(): Any {
        TODO("Not yet implemented")
    }

    override fun setStatus(status: Any) {
        TODO("Not yet implemented")
    }

    override fun getChildProjects(): MutableMap<String, Project> {
        TODO("Not yet implemented")
    }

    override fun setProperty(name: String, value: Any?) {
        TODO("Not yet implemented")
    }

    override fun getProject(): Project {
        TODO("Not yet implemented")
    }

    override fun getAllprojects(): MutableSet<Project> {
        TODO("Not yet implemented")
    }

    override fun getSubprojects(): MutableSet<Project> {
        TODO("Not yet implemented")
    }

    override fun task(name: String): Task {
        TODO("Not yet implemented")
    }

    override fun task(args: MutableMap<String, *>, name: String): Task {
        TODO("Not yet implemented")
    }

    override fun task(
        args: MutableMap<String, *>,
        name: String,
        configureClosure: Closure<*>
    ): Task {
        TODO("Not yet implemented")
    }

    override fun task(name: String, configureClosure: Closure<*>): Task {
        TODO("Not yet implemented")
    }

    override fun task(name: String, configureAction: Action<in Task>): Task {
        TODO("Not yet implemented")
    }

    override fun getPath(): String {
        TODO("Not yet implemented")
    }

    override fun getDefaultTasks(): MutableList<String> {
        TODO("Not yet implemented")
    }

    override fun setDefaultTasks(defaultTasks: MutableList<String>) {
        TODO("Not yet implemented")
    }

    override fun defaultTasks(vararg defaultTasks: String?) {
        TODO("Not yet implemented")
    }

    override fun evaluationDependsOn(path: String): Project {
        TODO("Not yet implemented")
    }

    override fun evaluationDependsOnChildren() {
        TODO("Not yet implemented")
    }

    override fun findProject(path: String): Project? {
        TODO("Not yet implemented")
    }

    override fun project(path: String): Project {
        TODO("Not yet implemented")
    }

    override fun project(path: String, configureClosure: Closure<*>): Project {
        TODO("Not yet implemented")
    }

    override fun project(path: String, configureAction: Action<in Project>): Project {
        TODO("Not yet implemented")
    }

    override fun getAllTasks(recursive: Boolean): MutableMap<Project, MutableSet<Task>> {
        TODO("Not yet implemented")
    }

    override fun getTasksByName(name: String, recursive: Boolean): MutableSet<Task> {
        TODO("Not yet implemented")
    }

    override fun getProjectDir(): File {
        TODO("Not yet implemented")
    }

    override fun file(path: Any): File {
        TODO("Not yet implemented")
    }

    override fun file(path: Any, validation: PathValidation): File {
        TODO("Not yet implemented")
    }

    override fun uri(path: Any): URI {
        TODO("Not yet implemented")
    }

    override fun relativePath(path: Any): String {
        TODO("Not yet implemented")
    }

    override fun files(vararg paths: Any?): ConfigurableFileCollection {
        TODO("Not yet implemented")
    }

    override fun files(paths: Any, configureClosure: Closure<*>): ConfigurableFileCollection {
        TODO("Not yet implemented")
    }

    override fun files(
        paths: Any,
        configureAction: Action<in ConfigurableFileCollection>
    ): ConfigurableFileCollection {
        TODO("Not yet implemented")
    }

    override fun fileTree(baseDir: Any): ConfigurableFileTree {
        TODO("Not yet implemented")
    }

    override fun fileTree(baseDir: Any, configureClosure: Closure<*>): ConfigurableFileTree {
        TODO("Not yet implemented")
    }

    override fun fileTree(
        baseDir: Any,
        configureAction: Action<in ConfigurableFileTree>
    ): ConfigurableFileTree {
        TODO("Not yet implemented")
    }

    override fun fileTree(args: MutableMap<String, *>): ConfigurableFileTree {
        TODO("Not yet implemented")
    }

    override fun zipTree(zipPath: Any): FileTree {
        TODO("Not yet implemented")
    }

    override fun tarTree(tarPath: Any): FileTree {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> provider(value: Callable<T>): Provider<T> {
        TODO("Not yet implemented")
    }

    override fun getProviders(): ProviderFactory {
        TODO("Not yet implemented")
    }

    override fun getObjects(): ObjectFactory {
        TODO("Not yet implemented")
    }

    override fun getLayout(): ProjectLayout {
        TODO("Not yet implemented")
    }

    override fun mkdir(path: Any): File {
        TODO("Not yet implemented")
    }

    override fun delete(vararg paths: Any?): Boolean {
        TODO("Not yet implemented")
    }

    override fun delete(action: Action<in DeleteSpec>): WorkResult {
        TODO("Not yet implemented")
    }

    override fun javaexec(closure: Closure<*>): ExecResult {
        TODO("Not yet implemented")
    }

    override fun javaexec(action: Action<in JavaExecSpec>): ExecResult {
        TODO("Not yet implemented")
    }

    override fun exec(closure: Closure<*>): ExecResult {
        TODO("Not yet implemented")
    }

    override fun exec(action: Action<in ExecSpec>): ExecResult {
        TODO("Not yet implemented")
    }

    override fun absoluteProjectPath(path: String): String {
        TODO("Not yet implemented")
    }

    override fun relativeProjectPath(path: String): String {
        TODO("Not yet implemented")
    }

    override fun getAnt(): AntBuilder {
        TODO("Not yet implemented")
    }

    override fun createAntBuilder(): AntBuilder {
        TODO("Not yet implemented")
    }

    override fun ant(configureClosure: Closure<*>): AntBuilder {
        TODO("Not yet implemented")
    }

    override fun ant(configureAction: Action<in AntBuilder>): AntBuilder {
        TODO("Not yet implemented")
    }

    override fun getConfigurations(): ConfigurationContainer =
        FakeConfigurationContainer(shadowConfigurations)

    override fun configurations(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun getArtifacts(): ArtifactHandler {
        TODO("Not yet implemented")
    }

    override fun artifacts(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun artifacts(configureAction: Action<in ArtifactHandler>) {
        TODO("Not yet implemented")
    }

    override fun getConvention(): Convention {
        TODO("Not yet implemented")
    }

    override fun depthCompare(otherProject: Project): Int {
        TODO("Not yet implemented")
    }

    override fun getDepth(): Int {
        TODO("Not yet implemented")
    }

    override fun getTasks(): TaskContainer {
        TODO("Not yet implemented")
    }

    override fun subprojects(action: Action<in Project>) {
        TODO("Not yet implemented")
    }

    override fun subprojects(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun allprojects(action: Action<in Project>) {
        TODO("Not yet implemented")
    }

    override fun allprojects(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun beforeEvaluate(action: Action<in Project>) {
        TODO("Not yet implemented")
    }

    override fun beforeEvaluate(closure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun afterEvaluate(action: Action<in Project>) {
        TODO("Not yet implemented")
    }

    override fun afterEvaluate(closure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun hasProperty(propertyName: String): Boolean {
        TODO("Not yet implemented")
    }

    override fun getProperties(): MutableMap<String, *> {
        TODO("Not yet implemented")
    }

    override fun property(propertyName: String): Any? {
        TODO("Not yet implemented")
    }

    override fun findProperty(propertyName: String): Any? {
        TODO("Not yet implemented")
    }

    override fun getLogger(): Logger {
        TODO("Not yet implemented")
    }

    override fun getGradle(): Gradle {
        TODO("Not yet implemented")
    }

    override fun getLogging(): LoggingManager {
        TODO("Not yet implemented")
    }

    override fun configure(`object`: Any, configureClosure: Closure<*>): Any {
        TODO("Not yet implemented")
    }

    override fun configure(
        objects: MutableIterable<*>,
        configureClosure: Closure<*>
    ): MutableIterable<*> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> configure(
        objects: MutableIterable<T>,
        configureAction: Action<in T>
    ): MutableIterable<T> {
        TODO("Not yet implemented")
    }

    override fun getRepositories(): RepositoryHandler {
        TODO("Not yet implemented")
    }

    override fun repositories(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun getDependencies(): DependencyHandler {
        TODO("Not yet implemented")
    }

    override fun dependencies(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun getBuildscript(): ScriptHandler {
        TODO("Not yet implemented")
    }

    override fun buildscript(configureClosure: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun copy(closure: Closure<*>): WorkResult {
        TODO("Not yet implemented")
    }

    override fun copy(action: Action<in CopySpec>): WorkResult {
        TODO("Not yet implemented")
    }

    override fun copySpec(closure: Closure<*>): CopySpec {
        TODO("Not yet implemented")
    }

    override fun copySpec(action: Action<in CopySpec>): CopySpec {
        TODO("Not yet implemented")
    }

    override fun copySpec(): CopySpec {
        TODO("Not yet implemented")
    }

    override fun sync(action: Action<in SyncSpec>): WorkResult {
        TODO("Not yet implemented")
    }

    override fun getState(): ProjectState {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> container(type: Class<T>): NamedDomainObjectContainer<T> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> container(
        type: Class<T>,
        factory: NamedDomainObjectFactory<T>
    ): NamedDomainObjectContainer<T> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> container(
        type: Class<T>,
        factoryClosure: Closure<*>
    ): NamedDomainObjectContainer<T> {
        TODO("Not yet implemented")
    }

    override fun getResources(): ResourceHandler {
        TODO("Not yet implemented")
    }

    override fun getComponents(): SoftwareComponentContainer {
        TODO("Not yet implemented")
    }

    override fun getNormalization(): InputNormalizationHandler {
        TODO("Not yet implemented")
    }

    override fun normalization(configuration: Action<in InputNormalizationHandler>) {
        TODO("Not yet implemented")
    }

    override fun dependencyLocking(configuration: Action<in DependencyLockingHandler>) {
        TODO("Not yet implemented")
    }

    override fun getDependencyLocking(): DependencyLockingHandler {
        TODO("Not yet implemented")
    }
}