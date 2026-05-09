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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.UnaryOperator;

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
	void getOptionUnpinnedKeyDelegatesToJavaCoreWhenInheriting() throws Exception {
		// Swap the JavaCore lookup with a deterministic stub so the test can verify the
		// delegation path returns a specific, known value. JavaCore.setOptions cannot be used in
		// the unit-test JVM (no OSGi → IEclipsePreferences is null), so we route via the test
		// seam.
		Field lookupField = StubJavaProject.class.getDeclaredField("JAVA_CORE_LOOKUP");
		lookupField.setAccessible(true);
		@SuppressWarnings("unchecked")
		UnaryOperator<String> original = (UnaryOperator<String>) lookupField.get(null);
		try {
			lookupField.set(null, (UnaryOperator<String>) name -> "STUBBED-FOR-TEST-" + name);
			assertThat(StubJavaProject.INSTANCE.getOption("any.unpinned.key", true))
					.isEqualTo("STUBBED-FOR-TEST-any.unpinned.key");
		} finally {
			lookupField.set(null, original);
		}
	}

	@Test
	void getOptionUnpinnedKeyReturnsNullWhenNotInheriting() throws Exception {
		// With a known-non-null lookup installed, the false-branch must still return null
		// (NOT delegate to the lookup). This kills the RemoveConditional mutant on the ternary.
		Field lookupField = StubJavaProject.class.getDeclaredField("JAVA_CORE_LOOKUP");
		lookupField.setAccessible(true);
		@SuppressWarnings("unchecked")
		UnaryOperator<String> original = (UnaryOperator<String>) lookupField.get(null);
		try {
			lookupField.set(null, (UnaryOperator<String>) name -> "should-not-be-returned");
			assertThat(StubJavaProject.INSTANCE.getOption("any.unpinned.key", false)).isNull();
			assertThat(StubJavaProject.INSTANCE.getOption("totally.unknown.key", false)).isNull();
		} finally {
			lookupField.set(null, original);
		}
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

	// =========================================================================
	// Failure path: createInstance with an unknown field name. Covers the catch
	// block that translates ReflectiveOperationException to IllegalStateException
	// — a contract guard for future JDT versions that may rename the field.
	// =========================================================================

	@Test
	void createInstanceThrowsForUnknownProjectField() {
		assertThatThrownBy(() -> StubJavaProject.createInstance("definitelyNotAField"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JavaProject#definitelyNotAField")
				.hasCauseInstanceOf(NoSuchFieldException.class);
	}

	@Test
	void createInstanceWithRealFieldNameReturnsAWiredProject() {
		// Drives the success path of createInstance — kills the NullReturnValsMutator on the
		// `return inst` statement (which would otherwise survive because the singleton is built
		// during static init and the existing tests only consult INSTANCE).
		StubJavaProject built = StubJavaProject.createInstance("project");
		assertThat(built).isNotNull();
		// The project field must point to the stub IProject.
		assertThat(built.getProject()).isNotNull();
		assertThat(built.getProject().getName()).isEqualTo(CleanUpConstants.STUB_PROJECT_NAME);
	}

	@Test
	void getOptionUnpinnedKeyHonoursInheritFlag() throws Exception {
		// Both branches return distinct, observable values when the lookup is stubbed.
		Field lookupField = StubJavaProject.class.getDeclaredField("JAVA_CORE_LOOKUP");
		lookupField.setAccessible(true);
		@SuppressWarnings("unchecked")
		UnaryOperator<String> original = (UnaryOperator<String>) lookupField.get(null);
		try {
			lookupField.set(null, (UnaryOperator<String>) name -> "non-null-stub");
			assertThat(StubJavaProject.INSTANCE.getOption("k", true)).isEqualTo("non-null-stub");
			assertThat(StubJavaProject.INSTANCE.getOption("k", false)).isNull();
			// They MUST differ — kills the ternary-true and ternary-false mutants.
			assertThat(StubJavaProject.INSTANCE.getOption("k", true))
					.isNotEqualTo(StubJavaProject.INSTANCE.getOption("k", false));
		} finally {
			lookupField.set(null, original);
		}
	}
}
