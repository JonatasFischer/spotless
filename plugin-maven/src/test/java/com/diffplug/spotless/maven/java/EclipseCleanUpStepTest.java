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
package com.diffplug.spotless.maven.java;

import org.junit.jupiter.api.Test;

import com.diffplug.spotless.maven.MavenIntegrationHarness;

/**
 * Integration tests for the {@code <eclipseCleanUp>} mojo — runs a full {@code spotless:apply}
 * via the Maven plugin so that Plexus DI / Sisu wiring / step-builder hookup is exercised
 * end-to-end. Lighter-weight unit tests live in
 * {@code lib-extra/.../EclipseJdtCleanUpStepTest}.
 */
class EclipseCleanUpStepTest extends MavenIntegrationHarness {

	@Test
	void cleanUpWithFile() throws Exception {
		writePomWithJavaSteps(
				"<eclipseCleanUp>",
				"  <file>${basedir}/cleanup.xml</file>",
				"</eclipseCleanUp>");
		setFile("cleanup.xml").toResource("java/eclipse/cleanup/cleanup.xml");

		String path = "src/main/java/test/CleanUpExample.java";
		setFile(path).toResource("java/eclipse/cleanup/CleanUpExample.test");
		mavenRunner().withArguments("spotless:apply").runNoError();
		assertFile(path).sameAsResource("java/eclipse/cleanup/CleanUpExample.clean");
	}

	@Test
	void cleanUpWithInlineSettings() throws Exception {
		// Only the locals-final cleanup, matching Simple.clean (which keeps parameters as
		// non-final). Profile-XML-style keys are interpreted by the underlying step builder.
		writePomWithJavaSteps(
				"<eclipseCleanUp>",
				"  <settings>",
				"    <cleanup.make_variable_declarations_final>true</cleanup.make_variable_declarations_final>",
				"    <cleanup.make_local_variable_final>true</cleanup.make_local_variable_final>",
				"    <cleanup.format_source_code>false</cleanup.format_source_code>",
				"  </settings>",
				"</eclipseCleanUp>");

		String path = "src/main/java/test/Simple.java";
		setFile(path).toResource("java/eclipse/cleanup/Simple.test");
		mavenRunner().withArguments("spotless:apply").runNoError();
		assertFile(path).sameAsResource("java/eclipse/cleanup/Simple.clean");
	}
}
