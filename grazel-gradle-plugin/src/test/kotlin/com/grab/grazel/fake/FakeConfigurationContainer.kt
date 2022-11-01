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
import org.gradle.api.DomainObjectCollection
import org.gradle.api.NamedDomainObjectCollectionSchema
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Namer
import org.gradle.api.Rule
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.*

class FakeConfigurationContainer(private var configurations: List<Configuration> = emptyList()) :
    ConfigurationContainer {
    override fun add(element: Configuration): Boolean {
        TODO("Not yet implemented")
    }

    override fun addAll(elements: Collection<Configuration>): Boolean {
        TODO("Not yet implemented")
    }

    override fun contains(element: Configuration?): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsAll(elements: Collection<Configuration>): Boolean {
        TODO("Not yet implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun iterator(): MutableIterator<Configuration> = configurations.toMutableList().iterator()

    override fun remove(element: Configuration?): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeAll(elements: Collection<Configuration>): Boolean {
        TODO("Not yet implemented")
    }

    override fun retainAll(elements: Collection<Configuration>): Boolean {
        TODO("Not yet implemented")
    }

    override fun addLater(p0: Provider<out Configuration>) {
        TODO("Not yet implemented")
    }

    override fun addAllLater(p0: Provider<out MutableIterable<Configuration>>) {
        TODO("Not yet implemented")
    }

    override fun <S : Configuration?> withType(p0: Class<S>): NamedDomainObjectSet<S> {
        TODO("Not yet implemented")
    }

    override fun <S : Configuration?> withType(
        p0: Class<S>,
        p1: Action<in S>
    ): DomainObjectCollection<S> {
        TODO("Not yet implemented")
    }

    override fun <S : Configuration?> withType(
        p0: Class<S>,
        p1: Closure<*>
    ): DomainObjectCollection<S> {
        TODO("Not yet implemented")
    }

    override fun matching(p0: Spec<in Configuration>): NamedDomainObjectSet<Configuration> {
        TODO("Not yet implemented")
    }

    override fun matching(p0: Closure<*>): NamedDomainObjectSet<Configuration> {
        TODO("Not yet implemented")
    }

    override fun whenObjectAdded(p0: Action<in Configuration>): Action<in Configuration> {
        TODO("Not yet implemented")
    }

    override fun whenObjectAdded(p0: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun whenObjectRemoved(p0: Action<in Configuration>): Action<in Configuration> {
        TODO("Not yet implemented")
    }

    override fun whenObjectRemoved(p0: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun all(p0: Action<in Configuration>) {
        TODO("Not yet implemented")
    }

    override fun all(p0: Closure<*>) {
        TODO("Not yet implemented")
    }

    override fun configureEach(p0: Action<in Configuration>) {
        TODO("Not yet implemented")
    }

    override fun findAll(p0: Closure<*>): MutableSet<Configuration> {
        TODO("Not yet implemented")
    }

    override fun getNamer(): Namer<Configuration> {
        TODO("Not yet implemented")
    }

    override fun getAsMap(): SortedMap<String, Configuration> {
        TODO("Not yet implemented")
    }

    override fun getNames(): SortedSet<String> {
        TODO("Not yet implemented")
    }

    override fun findByName(p0: String): Configuration? {
        TODO("Not yet implemented")
    }

    override fun getByName(p0: String): Configuration {
        TODO("Not yet implemented")
    }

    override fun getByName(p0: String, p1: Closure<*>): Configuration {
        TODO("Not yet implemented")
    }

    override fun getByName(p0: String, p1: Action<in Configuration>): Configuration {
        TODO("Not yet implemented")
    }

    override fun getAt(p0: String): Configuration {
        TODO("Not yet implemented")
    }

    override fun addRule(p0: Rule): Rule {
        TODO("Not yet implemented")
    }

    override fun addRule(p0: String, p1: Closure<*>): Rule {
        TODO("Not yet implemented")
    }

    override fun addRule(p0: String, p1: Action<String>): Rule {
        TODO("Not yet implemented")
    }

    override fun getRules(): MutableList<Rule> {
        TODO("Not yet implemented")
    }

    override fun named(p0: String): NamedDomainObjectProvider<Configuration> {
        TODO("Not yet implemented")
    }

    override fun named(
        p0: String,
        p1: Action<in Configuration>
    ): NamedDomainObjectProvider<Configuration> {
        TODO("Not yet implemented")
    }

    override fun <S : Configuration?> named(
        p0: String,
        p1: Class<S>
    ): NamedDomainObjectProvider<S> {
        TODO("Not yet implemented")
    }

    override fun <S : Configuration?> named(
        p0: String,
        p1: Class<S>,
        p2: Action<in S>
    ): NamedDomainObjectProvider<S> {
        TODO("Not yet implemented")
    }

    override fun getCollectionSchema(): NamedDomainObjectCollectionSchema {
        TODO("Not yet implemented")
    }

    override fun configure(p0: Closure<*>): NamedDomainObjectContainer<Configuration> {
        TODO("Not yet implemented")
    }

    override fun create(p0: String): Configuration {
        TODO("Not yet implemented")
    }

    override fun create(p0: String, p1: Closure<*>): Configuration {
        TODO("Not yet implemented")
    }

    override fun create(p0: String, p1: Action<in Configuration>): Configuration {
        TODO("Not yet implemented")
    }

    override fun maybeCreate(p0: String): Configuration {
        TODO("Not yet implemented")
    }

    override fun register(
        p0: String,
        p1: Action<in Configuration>
    ): NamedDomainObjectProvider<Configuration> {
        TODO("Not yet implemented")
    }

    override fun register(p0: String): NamedDomainObjectProvider<Configuration> {
        TODO("Not yet implemented")
    }

    override fun detachedConfiguration(vararg p0: Dependency?): Configuration {
        TODO("Not yet implemented")
    }

    override val size: Int
        get() = configurations.size
}