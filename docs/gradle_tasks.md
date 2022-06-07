# Gradle Tasks

Grazel gradle plugin does not do any major configuration during Gradle's `Configuration` phase with
the exception of [hybrid builds](hybrid_builds.md). Most of the work for migration is moved to
execution phase via the following Gradle tasks.

All the tasks are available under task group `bazel`.

## Tasks

### migrateToBazel

`migrateToBazel` is a lifecycle task that simply depends on relevant migration tasks and serves as
the entry point to Grazel execution. This task should be preferred over individual tasks since this
wires up the task graph correctly and needed tasks are run.

### generateBazelScripts

Attached to every `project` instance, this task is responsible for generating `BUILD.bazel` for the
given module. The task checks if a module can be migrated and proceeds to generate the script. If
not, it renames `BUILD.bazel` to `BUILD.bazelignore` when module becomes unmigrateable.

### generateRootBazelScripts

Attached to root project, this task generates `BUILD.bazel` and `WORKPSACE` files.

### generateBuildifierScript

Depends on `generateRootBazelScripts` and responsible for downloading the 
appropriate `buildifier` binary that is used for formatting the bazel scripts.

### formatBazelScripts

Depends on `generateBazelScripts` and responsible for formatting the generated file
with `buildifier`. `formatBuildBazel` and `formatWorkSpace` depends on `generateRootBazelScripts` and the buildifier output from `generateBuildifierScript` to
format root project's Bazel scripts.

### postScriptGenerateTask

Runs after all Bazel scripts are successfully generated. At this stage any Bazel command is assumed
to work and Grazel may run any cleanup or post migrate Bazel commands as part of the migration.

## Task Graph

The task graph allows project's migration tasks to run in parallel to increase `migrateToBazel`
performance.

[![](https://mermaid.ink/img/pako:eNp1kc1ugzAQhF8F-eRKyQtwqMRfKNcSqRculr0kLj-LjFGURnn3mjXUh6i3-WbG2Ls8mEQFLGZtjzd5FcZG57wZoyjhFxjBCAup-IG-lkZPdn5bo_Qv-kS0L3HGjbPjFs0gXtOcb8Gie0Up2cVmf6Hp6klIIPfEJ4PfIP_9WsknnK13yu1RZzF3FH7wQV_IwXBRFeZaX6BbDcafdzGdio7H9ygLsvR-RpAHWQR58pWSIFmBRiWsdiwCusl22GWySlo8YeprlQd2YAO4BWjl_tSDmsxeYYCGxU4qYbqGNePT9ZZJudkKpS0aFrein-HAxGKxvo-SxdYssJdyLdx6hq31_AUP1Kg9)](https://mermaid-js.github.io/mermaid-live-editor/edit#pako:eNp1kc1ugzAQhF8F-eRKyQtwqMRfKNcSqRculr0kLj-LjFGURnn3mjXUh6i3-WbG2Ls8mEQFLGZtjzd5FcZG57wZoyjhFxjBCAup-IG-lkZPdn5bo_Qv-kS0L3HGjbPjFs0gXtOcb8Gie0Up2cVmf6Hp6klIIPfEJ4PfIP_9WsknnK13yu1RZzF3FH7wQV_IwXBRFeZaX6BbDcafdzGdio7H9ygLsvR-RpAHWQR58pWSIFmBRiWsdiwCusl22GWySlo8YeprlQd2YAO4BWjl_tSDmsxeYYCGxU4qYbqGNePT9ZZJudkKpS0aFrein-HAxGKxvo-SxdYssJdyLdx6hq31_AUP1Kg9)
