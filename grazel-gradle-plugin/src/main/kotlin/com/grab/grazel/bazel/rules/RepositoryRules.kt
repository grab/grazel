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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote

/**
 * Marker interface to denote Bazel Repository rule
 *
 * @see [https://docs.bazel.build/versions/master/repo/]
 */
interface BazelRepositoryRule : BazelRule

fun StatementsBuilder.gitRepository(
    name: String,
    commit: String? = null,
    shallowSince: String? = null,
    remote: String? = null
) {
    load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")
    rule("git_repository") {
        "name" `=` name.quote
        commit?.let { "commit" `=` commit.quote }
        shallowSince?.let { "shallow_since" `=` shallowSince.quote }
        remote?.let { "remote" `=` remote.quote }
    }
}

/**
 * Data structure denoting `git_repository` rule
 *
 * @see [https://docs.bazel.build/versions/master/repo/git.html#git_repository]
 */
data class GitRepositoryRule(
    override val name: String,
    var commit: String? = null,
    var remote: String? = null,
    var shallowSince: String? = null
) : BazelRepositoryRule {
    override fun StatementsBuilder.statements() {
        gitRepository(
            name = name,
            commit = commit,
            shallowSince = shallowSince,
            remote = remote
        )
    }
}

fun StatementsBuilder.httpArchive(
    name: String,
    url: String,
    sha256: String? = null,
    type: String? = null,
    stripPrefix: String? = null
) {
    load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
    rule("http_archive") {
        "name" `=` name.quote
        stripPrefix?.let { "strip_prefix" `=` stripPrefix }
        sha256?.let { "sha256" `=` sha256 }
        "url" `=` url
        type?.let { "type" `=` type }
    }
}

/**
 * Data structure denoting `http_archive`
 *
 * @see [https://docs.bazel.build/versions/master/repo/http.html#http_archive]
 */
data class HttpArchiveRule(
    override val name: String,
    var url: String,
    var sha256: String? = null,
    var type: String? = null,
    var stripPrefix: String? = null
) : BazelRepositoryRule {
    override fun StatementsBuilder.statements() {
        httpArchive(
            name = name.quote,
            url = url.quote,
            sha256 = sha256?.quote,
            type = type?.quote,
            stripPrefix = stripPrefix?.quote
        )
    }
}

