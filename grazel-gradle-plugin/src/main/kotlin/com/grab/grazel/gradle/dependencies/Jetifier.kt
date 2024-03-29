/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies

import org.gradle.api.artifacts.result.ResolvedComponentResult

/**
 * `jetify_include_list` requires all transitive artifacts to be listed as well in addition to
 * root artifacts. Because [ResolvedComponentResult] won't contain this information since by the time
 * we read it, AGP would have already mapped to jetified artifact. Hence this map is used to do the
 * reverse.
 */
val JetifiedArtifacts = mapOf(
    "androidx.annotation:annotation" to "com.android.support:support-annotations",
    "androidx.appcompat:appcompat" to "com.android.support:appcompat-v7",
    "androidx.arch.core:core" to "android.arch.core:core",
    "androidx.arch.core:core-common" to "android.arch.core:common",
    "androidx.arch.core:core-runtime" to "android.arch.core:runtime",
    "androidx.arch.core:core-testing" to "android.arch.core:core-testing",
    "androidx.asynclayoutinflater:asynclayoutinflater" to "com.android.support:asynclayoutinflater",
    "androidx.biometric:biometric" to "com.android.support:biometric",
    "androidx.browser:browser" to "com.android.support:customtabs",
    "androidx.car:car" to "com.android.support:car",
    "androidx.cardview:cardview" to "com.android.support:cardview-v7",
    "androidx.collection:collection" to "com.android.support:collections",
    "androidx.constraintlayout:constraintlayout" to "com.android.support.constraint:constraint-layout",
    "androidx.constraintlayout:constraintlayout-solver" to "com.android.support.constraint:constraint-layout-solver",
    "androidx.contentpager:contentpager" to "com.android.support:support-content",
    "androidx.coordinatorlayout:coordinatorlayout" to "com.android.support:coordinatorlayout",
    "androidx.core:core" to "com.android.support:support-compat",
    "androidx.cursoradapter:cursoradapter" to "com.android.support:cursoradapter",
    "androidx.customview:customview" to "com.android.support:customview",
    "androidx.databinding:databinding-adapters" to "com.android.databinding:adapters",
    "androidx.databinding:databinding-common" to "com.android.databinding:baseLibrary",
    "androidx.databinding:databinding-compiler" to "com.android.databinding:compiler",
    "androidx.databinding:databinding-compiler-common" to "com.android.databinding:compilerCommon",
    "androidx.databinding:databinding-runtime" to "com.android.databinding:library",
    "androidx.documentfile:documentfile" to "com.android.support:documentfile",
    "androidx.drawerlayout:drawerlayout" to "com.android.support:drawerlayout",
    "androidx.dynamicanimation:dynamicanimation" to "com.android.support:support-dynamic-animation",
    "androidx.emoji:emoji" to "com.android.support:support-emoji",
    "androidx.emoji:emoji-appcompat" to "com.android.support:support-emoji-appcompat",
    "androidx.emoji:emoji-bundled" to "com.android.support:support-emoji-bundled",
    "androidx.exifinterface:exifinterface" to "com.android.support:exifinterface",
    "androidx.fragment:fragment" to "com.android.support:support-fragment",
    "androidx.gridlayout:gridlayout" to "com.android.support:gridlayout-v7",
    "androidx.heifwriter:heifwriter" to "com.android.support:heifwriter",
    "androidx.interpolator:interpolator" to "com.android.support:interpolator",
    "androidx.leanback:leanback" to "com.android.support:leanback-v17",
    "androidx.leanback:leanback-preference" to "com.android.support:preference-leanback-v17",
    "androidx.legacy:legacy-preference-v14" to "com.android.support:preference-v14",
    "androidx.legacy:legacy-support-core-ui" to "com.android.support:support-core-ui",
    "androidx.legacy:legacy-support-core-utils" to "com.android.support:support-core-utils",
    "androidx.legacy:legacy-support-v13" to "com.android.support:support-v13",
    "androidx.legacy:legacy-support-v4" to "com.android.support:support-v4",
    "androidx.lifecycle:lifecycle-common" to "android.arch.lifecycle:common",
    "androidx.lifecycle:lifecycle-common-java8" to "android.arch.lifecycle:common-java8",
    "androidx.lifecycle:lifecycle-compiler" to "android.arch.lifecycle:compiler",
    "androidx.lifecycle:lifecycle-extensions" to "android.arch.lifecycle:extensions",
    "androidx.lifecycle:lifecycle-livedata" to "android.arch.lifecycle:livedata",
    "androidx.lifecycle:lifecycle-livedata-core" to "android.arch.lifecycle:livedata-core",
    "androidx.lifecycle:lifecycle-reactivestreams" to "android.arch.lifecycle:reactivestreams",
    "androidx.lifecycle:lifecycle-runtime" to "android.arch.lifecycle:runtime",
    "androidx.lifecycle:lifecycle-viewmodel" to "android.arch.lifecycle:viewmodel",
    "androidx.loader:loader" to "com.android.support:loader",
    "androidx.localbroadcastmanager:localbroadcastmanager" to "com.android.support:localbroadcastmanager",
    "androidx.media:media" to "com.android.support:support-media-compat",
    "androidx.mediarouter:mediarouter" to "com.android.support:mediarouter-v7",
    "androidx.multidex:multidex" to "com.android.support:multidex",
    "androidx.multidex:multidex-instrumentation" to "com.android.support:multidex-instrumentation",
    "androidx.navigation:navigation-common" to "android.arch.navigation:navigation-common",
    "androidx.navigation:navigation-common-ktx" to "android.arch.navigation:navigation-common-ktx",
    "androidx.navigation:navigation-dynamic-features-fragment" to "android.arch.navigation:navigation-dynamic-features-fragment",
    "androidx.navigation:navigation-dynamic-features-runtime" to "android.arch.navigation:navigation-dynamic-features-runtime",
    "androidx.navigation:navigation-fragment" to "android.arch.navigation:navigation-fragment",
    "androidx.navigation:navigation-fragment-ktx" to "android.arch.navigation:navigation-fragment-ktx",
    "androidx.navigation:navigation-runtime" to "android.arch.navigation:navigation-runtime",
    "androidx.navigation:navigation-runtime-ktx" to "android.arch.navigation:navigation-runtime-ktx",
    "androidx.navigation:navigation-ui" to "android.arch.navigation:navigation-ui",
    "androidx.navigation:navigation-ui-ktx" to "android.arch.navigation:navigation-ui-ktx",
    "androidx.paging:paging-common" to "android.arch.paging:common",
    "androidx.paging:paging-runtime" to "android.arch.paging:runtime",
    "androidx.paging:paging-rxjava2" to "android.arch.paging:rxjava2",
    "androidx.palette:palette" to "com.android.support:palette-v7",
    "androidx.percentlayout:percentlayout" to "com.android.support:percent",
    "androidx.preference:preference" to "com.android.support:preference-v7",
    "androidx.print:print" to "com.android.support:print",
    "androidx.recommendation:recommendation" to "com.android.support:recommendation",
    "androidx.recyclerview:recyclerview" to "com.android.support:recyclerview-v7",
    "androidx.recyclerview:recyclerview-selection" to "com.android.support:recyclerview-selection",
    "androidx.room:room-common" to "android.arch.persistence.room:common",
    "androidx.room:room-compiler" to "android.arch.persistence.room:compiler",
    "androidx.room:room-guava" to "android.arch.persistence.room:guava",
    "androidx.room:room-migration" to "android.arch.persistence.room:migration",
    "androidx.room:room-runtime" to "android.arch.persistence.room:runtime",
    "androidx.room:room-rxjava2" to "android.arch.persistence.room:rxjava2",
    "androidx.room:room-testing" to "android.arch.persistence.room:testing",
    "androidx.slice:slice-builders" to "com.android.support:slices-builders",
    "androidx.slice:slice-core" to "com.android.support:slices-core",
    "androidx.slice:slice-view" to "com.android.support:slices-view",
    "androidx.slidingpanelayout:slidingpanelayout" to "com.android.support:slidingpanelayout",
    "androidx.sqlite:sqlite" to "android.arch.persistence:db",
    "androidx.sqlite:sqlite-framework" to "android.arch.persistence:db-framework",
    "androidx.swiperefreshlayout:swiperefreshlayout" to "com.android.support:swiperefreshlayout",
    "androidx.test.espresso.idling:idling-concurrent" to "com.android.support.test.espresso.idling:idling-concurrent",
    "androidx.test.espresso.idling:idling-net" to "com.android.support.test.espresso.idling:idling-net",
    "androidx.test.espresso:espresso-accessibility" to "com.android.support.test.espresso:espresso-accessibility",
    "androidx.test.espresso:espresso-contrib" to "com.android.support.test.espresso:espresso-contrib",
    "androidx.test.espresso:espresso-core" to "com.android.support.test.espresso:espresso-core",
    "androidx.test.espresso:espresso-idling-resource" to "com.android.support.test.espresso:espresso-idling-resource",
    "androidx.test.espresso:espresso-intents" to "com.android.support.test.espresso:espresso-intents",
    "androidx.test.espresso:espresso-remote" to "com.android.support.test.espresso:espresso-remote",
    "androidx.test.espresso:espresso-web" to "com.android.support.test.espresso:espresso-web",
    "androidx.test.jank:janktesthelper" to "com.android.support.test.janktesthelper:janktesthelper",
    "androidx.test.uiautomator:uiautomator" to "com.android.support.test.uiautomator:uiautomator",
    "androidx.test.uiautomator:uiautomator" to "com.android.support.test.uiautomator:uiautomator-v18",
    "androidx.test:monitor" to "com.android.support.test:monitor",
    "androidx.test:orchestrator" to "com.android.support.test:orchestrator",
    "androidx.test:rules" to "com.android.support.test:rules",
    "androidx.test:runner" to "com.android.support.test:runner",
    "androidx.test:test-services" to "com.android.support.test.services:test-services",
    "androidx.textclassifier:textclassifier" to "com.android.support:textclassifier",
    "androidx.transition:transition" to "com.android.support:transition",
    "androidx.tvprovider:tvprovider" to "com.android.support:support-tv-provider",
    "androidx.vectordrawable:vectordrawable" to "com.android.support:support-vector-drawable",
    "androidx.vectordrawable:vectordrawable-animated" to "com.android.support:animated-vector-drawable",
    "androidx.versionedparcelable:versionedparcelable" to "com.android.support:versionedparcelable",
    "androidx.viewpager:viewpager" to "com.android.support:viewpager",
    "androidx.wear:wear" to "com.android.support:wear",
    "androidx.webkit:webkit" to "com.android.support:webkit",
    "androidx.work:work-runtime" to "android.arch.work:work-runtime",
    "androidx.work:work-runtime-ktx" to "android.arch.work:work-runtime-ktx",
    "androidx.work:work-rxjava2" to "android.arch.work:work-rxjava2",
    "androidx.work:work-testing" to "android.arch.work:work-testing",
    "com.google.android.material:material" to "com.android.support:design"
)

val DefaultJetifierExclusions = setOf("com.android.support:support-v4")