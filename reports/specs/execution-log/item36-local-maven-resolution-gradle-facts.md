# Item 36 - Local Maven Resolution Gradle Facts

Status: in progress, first implementation checkpoint reached on 2026-07-01.

## Implemented

- Added proxy-only repository auth facts:
  - `RepositoryAuth.None`
  - `RepositoryAuth.Basic`
  - `RepositoryAuth.Header`
  - `RepositoryWithAuth(name, url, auth)`
- Kept legacy `Repository(name, url, username, password)` unchanged because it
  is already an input to `GenerateDownloaderConfigTask`.
- Added local Maven resolved facts in `gradle/dependencies`:
  - `LocalMavenResolvedFacts(artifactIndex, pomFileResolver)`
  - `ResolvedArtifactIndexBuilder`
  - `ResolvedComponentIndexBuilder`
  - `PomFileResolver`
  - `GradlePomFileResolver`

## Decisions

- Do not add auth directly to `Repository`. Doing so fingerprints header tokens
  in existing downloader-config task inputs even though that task ignores auth.
  `RepositoryWithAuth` is the proxy-only model for Item 37.
- The artifact index key is the Maven relative path reconstructed once from
  resolved artifact identity and the original artifact file name.
- The POM resolver keeps Gradle `ComponentIdentifier` and
  `ArtifactResolutionQuery` private to the Gradle facts layer. Unknown GAV
  returns `null`; a known resolved component with no POM is a hard failure.

## Verification

- Focused tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.RepositoryAuthTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --console=plain --no-daemon`
- Empty generated-output gate passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`
- `git diff --check` passed.

## Remaining

- Wire and exercise these facts in Item 37/38.
- Record real PAX artifact/component/POM counts when the proxy integration is
  active.
