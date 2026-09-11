import java.io.File
import java.math.BigDecimal

plugins {
  java
  jacoco
}

val cleanUpCoverage = extensions.create<CleanUpCoverageExtension>("cleanUpCoverage")

cleanUpCoverage.apply {
  excludedClasses.convention(emptyList())
  classDirectories.from(sourceSets.main.map { it.output.classesDirs })
  sourceDirectories.from(sourceSets.main.map { it.allJava.srcDirs })
  mutationThreshold.convention(95)
  jvmArgs.convention(emptyList())
}

jacoco { toolVersion = "0.8.14" }

val cleanupClasses = providers.provider {
  cleanUpCoverage.classDirectories.asFileTree.matching {
    include(cleanUpCoverage.classes.get().map { it.replace('.', '/') + ".class" })
    exclude(cleanUpCoverage.excludedClasses.get().map { it.replace('.', '/') + ".class" })
  }
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  classDirectories.setFrom(cleanupClasses)
  sourceDirectories.setFrom(cleanUpCoverage.sourceDirectories)
  reports {
    xml.required = true
    html.required = true
  }
}

val jacocoCleanUpVerification =
    tasks.register<JacocoCoverageVerification>("jacocoCleanUpVerification") {
      dependsOn(tasks.test)
      executionData(layout.buildDirectory.file("jacoco/test.exec"))
      classDirectories.setFrom(cleanupClasses)
      violationRules {
        rule {
          for (metric in listOf("INSTRUCTION", "BRANCH", "LINE")) {
            limit {
              counter = metric
              minimum = BigDecimal.ONE
            }
          }
        }
      }
    }

tasks.check { dependsOn(jacocoCleanUpVerification) }

// The PIT Gradle plugin uses APIs removed in Gradle 9; invoke the CLI directly.
val pitestRuntime = configurations.create("pitestRuntime")

dependencies {
  add(pitestRuntime.name, "org.pitest:pitest-command-line:1.20.5")
  add(pitestRuntime.name, "org.pitest:pitest-junit5-plugin:1.2.3")
  add(pitestRuntime.name, libs.junit.jupiter)
  add(pitestRuntime.name, "org.junit.platform:junit-platform-launcher")
}

tasks.register<JavaExec>("pitestCleanUp") {
  group = "verification"
  description = "Runs mutation tests for the Eclipse Clean Up implementation."
  dependsOn(tasks.testClasses, tasks.jar)
  classpath = pitestRuntime + sourceSets.test.get().runtimeClasspath + files(tasks.jar)
  mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
  val reportDirectory = layout.buildDirectory.dir("reports/pitest")
  // None of these tests use snapshots. Selfie's listener assumes one test plan per JVM,
  // whereas PIT creates a plan for each mutation.
  val cleanupJvmArgs =
      listOf(
          "-Djunit.platform.execution.listeners.deactivate=com.diffplug.selfie.junit5.SelfieTestExecutionListener"
      ) + cleanUpCoverage.jvmArgs.get()
  jvmArgs(cleanupJvmArgs)
  args(
      "--reportDir",
      reportDirectory.get().asFile.absolutePath,
      "--targetClasses",
      cleanUpCoverage.classes.get().joinToString(","),
      "--excludedClasses",
      (cleanUpCoverage.excludedClasses.get() + listOf("*Test", "*Test\$*")).joinToString(","),
      "--targetTests",
      cleanUpCoverage.tests.get().joinToString(","),
      "--sourceDirs",
      cleanUpCoverage.sourceDirectories.asPath.replace(File.pathSeparator, ","),
      "--testPlugin",
      "junit5",
      "--mutators",
      "STRONGER",
      "--mutationThreshold",
      cleanUpCoverage.mutationThreshold.get().toString(),
      "--coverageThreshold",
      cleanUpCoverage.mutationThreshold.get().toString(),
      "--timeoutConst",
      "300000",
      "--threads",
      "1",
      "--outputFormats",
      "HTML,XML",
  )
  args("--argLine", cleanupJvmArgs.joinToString(" "))
}
