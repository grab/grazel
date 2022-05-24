# Kotlin Databinding Support

Databinding support, especially with Kotlin is an ongoing effort.
See [issue](https://github.com/bazelbuild/bazel/issues/2694) for details.

In order to compile Kotlin based Gradle module that uses databinding without much refactors, Grazel
uses custom `kt_db_android_library` rules
from [grab-bazel-common](https://github.com/grab/grab-bazel-common).
See [databinding.bzl](https://github.com/grab/grab-bazel-common/blob/5076af89a1c0fd56f11a09b42eee5e6aa017dd73/tools/databinding/databinding.bzl#L159)
for more details.

Additionally, Grazel employs
few [custom patches](https://github.com/grab/grab-bazel-common/tree/master/patches) to Bazel's
Android Tools jar for performance and build fixes.

By default, the patched tools are imported to current build via `android_tools` macro in `WORKSPACE`
.

```python
load("@grab_bazel_common//:workspace_defs.bzl", "android_tools")

android_tools(
    commit = "f74ef90479383a38cef1af33d28a3253031e00c1",
    remote = "https://github.com/grab/grab-bazel-common.git",
)
```

Commenting out `android_tools` will use the Android tools version shipped with Bazel.

Supported features:

* TBA