/*
 * Copyright 2024-2026 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.gradle.spotless;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@code eclipseCleanUp} DSL — runs a full {@code spotlessApply} via
 * the Gradle Test Kit so that classloader / Plugin DSL / step-builder wiring is exercised
 * end-to-end. Lighter-weight unit tests live in
 * {@code lib-extra/.../EclipseJdtCleanUpStepTest}.
 */
class JavaEclipseCleanUpTest extends GradleIntegrationHarness {

	@Test
	void cleanUpWithConfigPropertiesString() throws IOException {
		setFile("build.gradle").toLines(
				"plugins {",
				"  id 'com.diffplug.spotless'",
				"  id 'java'",
				"}",
				"repositories { mavenCentral() }",
				"",
				"spotless {",
				"  java {",
				"    eclipseCleanUp().configProperties(\"\"\"",
				"cleanup.make_variable_declarations_final=true",
				"cleanup.make_local_variable_final=true",
				"cleanup.format_source_code=false",
				"\"\"\")",
				"  }",
				"}");
		setFile("src/main/java/test/Foo.java").toLines(
				"package test;",
				"public class Foo {",
				"  public int sum(int a, int b) {",
				"    int r = a + b;",
				"    return r;",
				"  }",
				"}");

		gradleRunner().withArguments("spotlessApply").build();

		assertFile("src/main/java/test/Foo.java").matches(a -> a.contains("final int r = a + b"));
	}

	@Test
	void cleanUpInvalidVersionFailsFast() throws IOException {
		setFile("build.gradle").toLines(
				"plugins {",
				"  id 'com.diffplug.spotless'",
				"  id 'java'",
				"}",
				"repositories { mavenCentral() }",
				"",
				"spotless {",
				"  java {",
				"    eclipseCleanUp('latest')",
				"  }",
				"}");
		setFile("src/main/java/test/Empty.java").toLines("package test;", "public class Empty {}");

		// An unrecognised version should bail at configuration time with a clear error rather
		// than producing an opaque P2 404 deeper in the build.
		String stderr = gradleRunner().withArguments("spotlessApply").buildAndFail().getOutput();
		assertThat(stderr).containsIgnoringCase("Eclipse JDT version");
	}
}
