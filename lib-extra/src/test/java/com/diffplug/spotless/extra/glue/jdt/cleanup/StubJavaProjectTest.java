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
package com.diffplug.spotless.extra.glue.jdt.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;

/** Direct unit tests for {@link StubJavaProject}. */
class StubJavaProjectTest {

	@Test
	void instanceIsAccessibleAndStable() {
		StubJavaProject a = StubJavaProject.INSTANCE;
		StubJavaProject b = StubJavaProject.INSTANCE;
		assertThat(a).isNotNull();
		// Singleton: every access returns the same instance.
		assertThat(a).isSameAs(b);
	}

	@Test
	void getOptionsTrueMergesJavaCoreDefaults() {
		Map<String, String> merged = StubJavaProject.INSTANCE.getOptions(true);
		// Spotless pinned values must dominate.
		assertThat(merged).containsEntry(JavaCore.COMPILER_SOURCE, CleanUpConstants.JAVA_LEVEL);
		assertThat(merged).containsEntry(JavaCore.COMPILER_COMPLIANCE, CleanUpConstants.JAVA_LEVEL);
		assertThat(merged).containsEntry(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, CleanUpConstants.JAVA_LEVEL);
		// Some other JavaCore option that Spotless does NOT pin must come through.
		assertThat(merged).containsKey(JavaCore.COMPILER_PB_RAW_TYPE_REFERENCE);
	}

	@Test
	void getOptionsFalseReturnsExactlySpotlessPins() {
		Map<String, String> opts = StubJavaProject.INSTANCE.getOptions(false);
		assertThat(opts).isEqualTo(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	}

	@Test
	void getOptionPinnedKeysReturnJavaLevel() {
		// Pinned values must answer with JAVA_LEVEL regardless of inheritJavaCoreOptions.
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_SOURCE, true))
				.isEqualTo(CleanUpConstants.JAVA_LEVEL);
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_SOURCE, false))
				.isEqualTo(CleanUpConstants.JAVA_LEVEL);
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_COMPLIANCE, true))
				.isEqualTo(CleanUpConstants.JAVA_LEVEL);
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true))
				.isEqualTo(CleanUpConstants.JAVA_LEVEL);
	}

	@Test
	void getOptionUnpinnedKeyDelegatesToJavaCoreWhenInheriting() {
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_PB_UNUSED_IMPORT, true))
				.isNotNull().isEqualTo(JavaCore.getOptions().get(JavaCore.COMPILER_PB_UNUSED_IMPORT));
	}

	@Test
	void getOptionUnpinnedKeyReturnsNullWhenNotInheriting() {
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_PB_UNUSED_IMPORT, false)).isNull();
		assertThat(StubJavaProject.INSTANCE.getOption("totally.unknown.key", false)).isNull();
	}

	@Test
	void getModuleDescriptionIsNull() {
		// Documented as null by design — the rest of the JDT model can fall back to JavaCore defaults.
		assertThat(StubJavaProject.INSTANCE.getModuleDescription()).isNull();
	}

	@Test
	void getProjectReturnsTheStubIProject() {
		assertThat(StubJavaProject.INSTANCE.getProject()).isNotNull();
		assertThat(StubJavaProject.INSTANCE.getProject().getName())
				.isEqualTo(CleanUpConstants.STUB_PROJECT_NAME);
	}

	@Test
	void getEclipsePreferencesIsNullByDesign() {
		assertThat(StubJavaProject.INSTANCE.getEclipsePreferences()).isNull();
	}

	@Test
	void sourceLevelsAreIsolatedBetweenProjects() {
		StubJavaProject java8 = new StubJavaProject(CleanUpConstants.compilerOptions("8"));
		StubJavaProject java21 = new StubJavaProject(CleanUpConstants.compilerOptions("21"));
		assertThat(java8.getOption(JavaCore.COMPILER_SOURCE, true)).isEqualTo("1.8");
		assertThat(java21.getOption(JavaCore.COMPILER_SOURCE, false)).isEqualTo("21");
		assertThat(java8.getOptions(false)).containsEntry(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "1.8");
		assertThat(java21.getOptions(true)).containsEntry(JavaCore.COMPILER_COMPLIANCE, "21");
		assertThat(StubJavaProject.INSTANCE.getOption(JavaCore.COMPILER_SOURCE, false)).isEqualTo("17");
		assertThat(java8).isNotEqualTo(java21);
		assertThat(java8.getProject()).isNotSameAs(java21.getProject());
	}

	@Test
	void compilerOptionsAreSnapshotted() {
		Map<String, String> options = new java.util.HashMap<>(CleanUpConstants.compilerOptions("21"));
		StubJavaProject project = new StubJavaProject(options);
		options.clear();
		assertThat(project.getOptions(false)).isUnmodifiable().containsEntry(JavaCore.COMPILER_SOURCE, "21");
	}

	@Test
	void unknownOptionHasNoInheritedValue() {
		assertThat(StubJavaProject.INSTANCE.getOption("unknown.option", true)).isNull();
	}
}
