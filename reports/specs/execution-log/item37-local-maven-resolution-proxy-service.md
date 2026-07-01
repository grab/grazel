# Item 37 - Local Maven Resolution Proxy Service

Status: implemented and locally verified on 2026-07-01; ready for local commit.

## Implemented

- Added Ktor CIO server/client dependencies on the plugin main classpath.
- Added pure HTTP proxy server in `migrate/dependencies`:
  - artifact index file hits
  - lazy POM resolver hits
  - `.sha1` / `.md5` / `.sha256` checksum generation
  - origin fallback for unknown metadata/POMs
  - basic/header auth replay
  - write-through cache
  - same-path concurrent origin miss de-duplication
  - stats counters
- Added dormant Gradle `BuildService` wrapper in `gradle/dependencies`.
  The service is exposed through Dagger but is not requested by any task in
  this slice, so normal migration behavior stays unchanged.

## Decisions

- Use Ktor `2.3.13` because the repo is Kotlin `1.9.25`; Ktor 3.x requires
  Kotlin 2.x.
- Use `ApplicationEngine.resolvedConnectors()` through `runBlocking` after
  server start to discover the ephemeral port.
- Stream file hits through `respondFile`; only checksum calculation and origin
  fallback bytes read into memory.
- Keep BuildService params serializable: cache dir plus ordered repository
  descriptors. The artifact index and `PomFileResolver` are set
  imperatively at execution time.
- Simplify pass decisions:
  - Removed the duplicate intermediate `LocalMavenProxyRepository` DTO.
    `RepositoryWithAuth` remains the Gradle service parameter model and is
    mapped once into proxy-owned HTTP origin facts at the boundary.
  - Added proxy-owned `LocalMavenProxyOrigin` / `LocalMavenProxyAuth` DTOs so
    the pure HTTP server does not depend on Gradle repository models.
  - Kept Ktor despite a simplify suggestion to use JDK HTTP APIs because Item
    37 explicitly requires Ktor CIO.
  - Kept stats despite a simplify suggestion to defer them because Item 37/38
    require proxy hit/miss/fallback summaries.
  - Kept lazy `PomFileResolver` invocation from the HTTP path as an explicit
    Item 36/37 design decision; live Gradle types remain hidden behind the
    interface and Item 38 must configure it during task execution only.
  - Replaced blocking origin coordination with coroutine `Mutex` per repo/path,
    removed mutex entries after use, and streamed checksum digest input from
    files instead of allocating whole jars/aars.

## Verification

- Focused test passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --console=plain --no-daemon`
- Empty generated-output gate passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`
- Full plugin unit test passed after simplify:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
- `git diff --check` passed.

## Remaining

- Item 38 must wire the service into pinner execution behind the experiment
  flag and prove cold pinning on PAX.
