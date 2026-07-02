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

    fun reconstruct(
        lockfileContents: String,
        canonicalRepositoryInputs: List<String>,
        baselineLockfileContents: String? = null,
        requireBaselineForPomPackagingArtifacts: Boolean = false,
    ): String {
        val baselineLockfile = baselineLockfileContents?.let(RulesJvmExternalLockfileParser::parse)
        val rewrittenLockfile = repositoryUrlRewriter.rewrite(
            RulesJvmExternalLockfileParser.parse(lockfileContents)
        )
        val lockfileWithBaselineFacts = baselineLockfile
            ?.let { baseline -> BaselineLockfileFactsMerger.merge(rewrittenLockfile, baseline) }
            ?: rewrittenLockfile
        if (requireBaselineForPomPackagingArtifacts && baselineLockfile == null) {
            PomPackagingSkipNormalizer.requireNoPomPackagingArtifactsWithoutBaseline(lockfileWithBaselineFacts)
        }
        val normalizedLockfile = PomPackagingSkipNormalizer.normalize(
            lockfile = lockfileWithBaselineFacts,
            baselineArtifactNames = baselineLockfile?.artifactNames.orEmpty()
        )
        return RulesJvmExternalLockfileRenderer.render(
            normalizedLockfile.copy(
                inputArtifactsHash = RulesJvmExternalLockfileHasher.inputArtifactsHashWithRepositories(
                    inputArtifactsHash = normalizedLockfile.inputArtifactsHash,
                    canonicalRepositoryInputs = canonicalRepositoryInputs
                ),
                resolvedArtifactsHash = RulesJvmExternalLockfileHasher.resolvedArtifactsHash(normalizedLockfile)
            )
        )
    }
}
