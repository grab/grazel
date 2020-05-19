# Android Sample Project
Sample project with multiple modules/dependencies for grazel plugin testing purposes.

## Dependency graph
`:app` -> `:android-library`, `:kotlin-library1`

`:android-library` -> `:kotlin-library2`, `:kotlin-library3`

`:kotlin-library2` -> `:kotlin-library3`