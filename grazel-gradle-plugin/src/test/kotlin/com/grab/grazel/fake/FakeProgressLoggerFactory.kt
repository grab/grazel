package com.grab.grazel.fake

import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.internal.operations.BuildOperationDescriptor

class FakeProgressLoggerFactory : ProgressLoggerFactory {
    override fun newOperation(loggerCategory: String?) = FakeProgressLogger()

    override fun newOperation(loggerCategory: Class<*>?) = FakeProgressLogger()

    override fun newOperation(
        loggerCategory: Class<*>?,
        buildOperationDescriptor: BuildOperationDescriptor?
    ): ProgressLogger = FakeProgressLogger()

    override fun newOperation(
        loggerClass: Class<*>?,
        parent: ProgressLogger?
    ) = FakeProgressLogger()
}

class FakeProgressLogger : ProgressLogger {
    var desc: String = ""
        private set
    var started = false
        private set
    var status: String = ""
        private set
    var failed = false
        private set
    var completed = false
        private set

    val progressMessages = mutableListOf<String>()

    override fun setDescription(description: String?): ProgressLogger {
        this.desc = description ?: ""
        return this
    }

    override fun getDescription(): String = desc

    override fun start(description: String?, status: String?): ProgressLogger {
        setDescription(description)
        started = true
        return this
    }

    override fun started() {
        started = true
    }

    override fun started(status: String?) {
        started = true
        progress(status)
    }

    override fun progress(status: String?) {
        progressMessages.add(status ?: "")
    }

    override fun progress(status: String?, failing: Boolean) {
        progressMessages.add(status ?: "")
    }

    override fun completed() {
        completed = true
    }

    override fun completed(status: String?, failed: Boolean) {
        completed = true
        this.failed = failed
        progress(status)
    }
}