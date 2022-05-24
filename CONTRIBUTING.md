# Contributing to Grazel

## Testing

A full test suite can be executed via the following command and will be tested on these modules

```sh
./gradlew check
```

- `sample-android`
- `sample-android-flavor`
- `sample-android-lib`
- `sample-kotlin-lib`
- `sample-lib-flavor1`
- `sample-lib-flavor2`

In order to test your changes and see if the `BUILD.bazel` is being generated as intended, execute
the following command in the project root directory

```sh
./gradlew migrateToBazel
```

## Publishing

In order to publish to maven central, ensure that you have a `local.properties`
in `grazel-gradle-plugin` module with the following values

P.S. actual values are redacted, please contact the maintainers if you wish to publish to an
artifactory

```properties
signing.keyId=$SIGNING_KEY_ID
signing.password=$SIGNING_PASSWORD
ossrhUsername=$OSSRH_USERNAME
signing.key=$SIGNING_KEY
sonatypeStagingProfileId=$SONATYPE_STAGING_PROFILE_ID
ossrhPassword=$OSSRH_PASSWORD
```

### Publishing to local maven

In the event where publishing to local maven (for testing/verification) is required, please remove
the signing plugin that can be found in `gradle/publishing.gradle` and execute the following command

```sh
./gradlew :grazel-gradle-plugin:publishToMavenLocal
```
