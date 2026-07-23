/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.dependencies

internal class MavenInstallLockfileReconstructor(
    repositoryRewrite: MavenInstallRepositoryRewrite,
) {
    private val repositoryUrlRewriter = MavenLockfileRepositoryUrlRewriter(repositoryRewrite)

    /**
     * Re-derives a maven_install.json produced against proxied repository URLs into one that is
     * byte-identical to what rules_jvm_external would have generated against the canonical
     * repositories. The steps are strictly ordered because each depends on the previous one's
     * output:
     *  1. Rewrite proxy URLs in the freshly-pinned lockfile back to canonical
     *     ([MavenLockfileRepositoryUrlRewriter]) - hashing and facts merging below must only ever
     *     see canonical URLs.
     *  2. When a baseline lockfile is available, merge in its facts ([BaselineLockfileFactsMerger])
     *     so unchanged artifacts keep their baseline-verified shasums instead of possibly-divergent
     *     freshly-resolved ones. RJE's own resolved/skipped classification (including POM-packaging
     *     roots such as BOM parents) is authoritative and carried through unchanged either way - it
     *     is never re-derived here.
     *  3. Hashes are recomputed last, in the same order RJE computes them (input-artifacts hash
     *     first, then resolved-artifacts hash over the now-finalized artifact/dependency data) -
     *     computing them earlier would hash pre-merge data and produce a lockfile RJE itself would
     *     reject as tampered.
     */
    fun reconstruct(
        lockfileContents: String,
        canonicalRepositoryInputs: List<String>,
        baselineLockfileContents: String? = null,
    ): String {
        val baselineLockfile = baselineLockfileContents?.let(RulesJvmExternalLockfileParser::parse)
        val rewrittenLockfile = repositoryUrlRewriter.rewrite(
            RulesJvmExternalLockfileParser.parse(lockfileContents)
        )
        val lockfileWithBaselineFacts = baselineLockfile
            ?.let { baseline -> BaselineLockfileFactsMerger.merge(rewrittenLockfile, baseline) }
            ?: rewrittenLockfile
        return RulesJvmExternalLockfileRenderer.render(
            lockfileWithBaselineFacts.copy(
                inputArtifactsHash = RulesJvmExternalLockfileHasher.inputArtifactsHashWithRepositories(
                    inputArtifactsHash = lockfileWithBaselineFacts.inputArtifactsHash,
                    canonicalRepositoryInputs = canonicalRepositoryInputs
                ),
                resolvedArtifactsHash = RulesJvmExternalLockfileHasher.resolvedArtifactsHash(
                    lockfileWithBaselineFacts
                )
            )
        )
    }
}
