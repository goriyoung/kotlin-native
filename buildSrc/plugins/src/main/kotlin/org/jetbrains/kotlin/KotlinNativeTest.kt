/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin

import java.util.regex.Pattern

enum class RunnerLogger {
    GTEST,
    TEAMCITY,
    SIMPLE,
    SILENT
}

abstract class KonanTestRunner : DefaultTask() {
    @Optional
    var testLogger = RunnerLogger.SILENT

    @Input
    lateinit var arguments: MutableList<String>

    @Input
    lateinit var executable: String

    @Optional
    lateinit var source: String

    @Input
    var useFilter = true

    @Suppress("UnstableApiUsage")
    override fun configure(config: Closure<*>): Task {
        super.configure(config)

        // Set Gradle properties for the better navigation
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Kotlin/Native test infrastructure task"

        if (!::arguments.isInitialized) {
            arguments = mutableListOf()
        }
        arguments.add("--ktest_logger=$testLogger")
        if (useFilter && ::source.isInitialized) {
            arguments.add("--ktest_filter=${source.convertToPattern()}")
        }
        project.setDistDependencyFor(this)
        return this
    }

    @TaskAction
    open fun run() = project.executeAndCheck(project.file(executable).toPath(), arguments)

    // Converts to runner's pattern
    private fun String.convertToPattern() = this.replace('/', '.').replace(".kt", "") + (".*")

    internal fun ProcessOutput.print() {
        val (stdOut, stdErr, exitCode) = this
        if (project.verboseTest)
            println("""
                |stdout:$stdOut
                |stderr:$stdErr
                |exit code: $exitCode
                """.trimMargin())
    }
}

/**
 * Task to run tests compiled with TestRunner.
 * Runs tests with GTEST output and parses it to create statistics info
 */
open class KonanGTestRunner : KonanTestRunner() {
    init {
        // Use GTEST logger to parse test results later
        testLogger = RunnerLogger.GTEST
    }

    lateinit var statistics: Statistics

    @TaskAction
    override fun run() = with(runProcess(
            executor = project.executor::execute,
            executable = executable,
            args = arguments
    )) {
        statistics = parse(stdOut)
        print()
        check(exitCode == 0) { "Test $executable exited with $exitCode" }
    }

    private fun parse(output: String): Statistics = Statistics().apply {
        Pattern.compile("\\[  PASSED  ] ([0-9]*) tests\\.").matcher(output)
                .apply { if (find()) pass(group(1).toInt()) }

        Pattern.compile("\\[  FAILED  ] ([0-9]*) tests.*").matcher(output)
                .apply { if (find()) fail(group(1).toInt()) }
        if (total == 0) {
            // No test were run. Try to find if there we tried to run something
            error(Pattern.compile("\\[={10}] Running ([0-9]*) tests from ([0-9]*) test cases\\..*")
                    .matcher(output).run { if (find()) group(1).toInt() else 1 })
        }
    }
}

open class KonanLocalTestRunner : KonanTestRunner() {
    init {
        val target = project.testTarget()
        executable = "${project.testOutputLocal}/${target.name}/localTest.${target.family.exeSuffix}"
    }

    @Optional
    var expectedExitStatus = 0

    @Optional
    var expectedFail = false

    @Optional
    lateinit var goldValue: String

    /**
     * Checks test's output against gold value and returns true if the output matches the expectation
     */
    @Optional
    var outputChecker: (String) -> Boolean = { str -> (!::goldValue.isInitialized || goldValue == str) }

    @Optional
    lateinit var testData: String

    @Optional
    var compilerMessages = false

    @TaskAction
    override fun run() {
        var output = if (::testData.isInitialized)
            runProcessWithInput(project.executor::execute, executable, arguments, testData)
        else
            runProcess(project.executor::execute, executable, arguments)
        if (compilerMessages) {
            val target = project.testTarget()
            val compilationLog = project.file("${project.testOutputLocal}/${target.name}/localTest.compilation.log")
                    .readText()
            output = ProcessOutput(compilationLog + output.stdOut, output.stdErr, output.exitCode)
        }
        output.check()
        output.print()
    }

    private fun ProcessOutput.check() {
        val (stdOut, stdErr, exitCode) = this

        val exitCodeMismatch = exitCode != expectedExitStatus
        if (exitCodeMismatch) {
            val message = "Expected exit status: $expectedExitStatus, actual: $exitCode"
            check(expectedFail) { """
                    |Test failed. $message
                    |stdout: $stdOut
                    |stderr: $stdErr
                    """.trimMargin()
            }
            println("Expected failure. $message")
        }

        val result = stdOut + stdErr
        val goldValueMismatch = !outputChecker(result.replace(System.lineSeparator(), "\n"))
        if (goldValueMismatch) {
            val message = if (::goldValue.isInitialized)
                "Expected output: $goldValue, actual output: $result"
            else
                "Actual output doesn't match with output checker: $result"

            check(expectedFail) { "Test failed. $message" }
            println("Expected failure. $message")
        }

        check(!exitCodeMismatch && !goldValueMismatch && !expectedFail) { "Unexpected pass" }
    }
}

open class KonanStandaloneTestRunner : KonanLocalTestRunner() {
    @Optional
    var flags: MutableList<String>? = null

    @Optional
    var enableKonanAssertions = true

    fun getSources() = buildCompileList(project.testOutputLocal)
}