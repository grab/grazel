package com.grab.grazel.fake

import org.gradle.api.Action
import org.gradle.workers.ClassLoaderWorkerSpec
import org.gradle.workers.ProcessWorkerSpec
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerConfiguration
import org.gradle.workers.WorkerExecutor
import org.gradle.workers.WorkerSpec

class FakeWorkerExecutor(
) : WorkerExecutor {
    val workQueue = FakeWorkQueue()

    override fun submit(
        actionClass: Class<out Runnable>?,
        configAction: Action<in WorkerConfiguration>?
    ) {
        // no-op
    }

    override fun noIsolation() = workQueue

    override fun noIsolation(action: Action<in WorkerSpec>?) = workQueue

    override fun classLoaderIsolation() = workQueue

    override fun classLoaderIsolation(action: Action<in ClassLoaderWorkerSpec>?) = workQueue

    override fun processIsolation() = workQueue

    override fun processIsolation(action: Action<in ProcessWorkerSpec>?) = workQueue

    override fun await() {
        // no - op
    }
}

class FakeWorkQueue : WorkQueue {
    override fun <T : WorkParameters?> submit(
        workActionClass: Class<out WorkAction<T>>?,
        parameterAction: Action<in T>?
    ) {
    }

    override fun await() {
        // no-op
    }
}