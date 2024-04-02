package com.grazel.lint.rules

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.google.auto.service.AutoService

/*
 * The list of issues that will be checked when running <code>lint</code>.
 */
@AutoService(IssueRegistry::class)
class GrazelIssueRegistry : IssueRegistry() {
    override val issues = WrongTimberUsageDetector.issues.asList()

    override val api: Int
        get() = CURRENT_API

    override val minApi: Int
        get() = 8

    override val vendor: Vendor = Vendor(
        vendorName = "Grazel App",
        feedbackUrl = "https://github.com/grab/grazel/issues",
        contact = "https://github.com/grab/grazel/issues"
    )
}
