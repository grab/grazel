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

### formatBazelScripts

Depends on `generateBazelScripts` and responsible for formatting the generated file
with `buildifier`. `formatBuildBazel` and `formatWorkSpace` depends on `generateRootBazelScripts` to
format root project's Bazel scripts.

### postScriptGenerateTask

Runs after all Bazel scripts are successfully generated. At this stage any Bazel command is assumed
to work and Grazel may run any cleanup or post migrate Bazel commands as part of the migration.

## Task Graph

The task graph allows project's migration tasks to run in parallel to increase `migrateToBazel`
performance.

<img src="../images/task_graph.png" width="100%">



