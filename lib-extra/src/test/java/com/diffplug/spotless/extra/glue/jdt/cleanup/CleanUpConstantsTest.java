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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;

/** Locks down constant values that other parts of the system depend on by name. */
class CleanUpConstantsTest {

	@Test
	void acceptsJava8And21AndRejectsUnknownJdtLanguageVersions() {
		assertThat(CleanUpConstants.compilerOptions("8")).containsEntry(JavaCore.COMPILER_SOURCE, "1.8");
		assertThat(CleanUpConstants.compilerOptions("21")).containsEntry(JavaCore.COMPILER_COMPLIANCE, "21");
		assertThatThrownBy(() -> CleanUpConstants.compilerOptions("99"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("99", "selected Eclipse JDT", "supported versions");
	}

	@Test
	void javaLevelIs17() {
		assertThat(CleanUpConstants.JAVA_LEVEL).isEqualTo("17");
	}

	@Test
	void defaultCompilerOptionsArePinnedTo17() {
		assertThat(CleanUpConstants.DEFAULT_COMPILER_OPTIONS)
				.containsEntry(JavaCore.COMPILER_SOURCE, "17")
				.containsEntry(JavaCore.COMPILER_COMPLIANCE, "17")
				.containsEntry(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
	}

	@Test
	void syntheticIdentitiesAreStable() {
		// These values surface to JDT internals as "the file name" / "the project name". A change
		// here can break the cleanup pipeline silently; lock them in.
		assertThat(CleanUpConstants.DEFAULT_UNIT_NAME).isEqualTo("CleanUpUnit.java");
		assertThat(CleanUpConstants.STUB_PROJECT_NAME).isEqualTo("SpotlessCleanUpProject");
		assertThat(CleanUpConstants.INSTANCE_AREA_PREFIX).isEqualTo("spotless-jdt-cleanup");
	}

	@Test
	void requiredBundlesContainsExpectedSymbolicNames() {
		assertThat(CleanUpConstants.REQUIRED_BUNDLES)
				.containsExactly(
						"org.eclipse.jdt.core",
						"org.eclipse.jdt.core.manipulation",
						"org.eclipse.ltk.core.refactoring",
						"org.eclipse.core.runtime",
						"org.eclipse.equinox.preferences");
		// Felix SCR is NOT in the required bundles list; it is started separately for DS bring-up.
		assertThat(CleanUpConstants.REQUIRED_BUNDLES).doesNotContain(CleanUpConstants.BUNDLE_FELIX_SCR);
	}

	@Test
	void bundleSymbolicNameConstantsAreNotBlank() {
		assertThat(CleanUpConstants.BUNDLE_FELIX_SCR).isEqualTo("org.apache.felix.scr");
		assertThat(CleanUpConstants.BUNDLE_EQUINOX_PREFERENCES).isEqualTo("org.eclipse.equinox.preferences");
		assertThat(CleanUpConstants.BUNDLE_CORE_RUNTIME).isEqualTo("org.eclipse.core.runtime");
		assertThat(CleanUpConstants.BUNDLE_JDT_CORE).isEqualTo("org.eclipse.jdt.core");
		assertThat(CleanUpConstants.BUNDLE_JDT_CORE_MANIPULATION).isEqualTo("org.eclipse.jdt.core.manipulation");
		assertThat(CleanUpConstants.BUNDLE_LTK_CORE_REFACTORING).isEqualTo("org.eclipse.ltk.core.refactoring");
	}

	@Test
	void preferenceConstantsAreNotBlank() {
		assertThat(CleanUpConstants.PREF_NODE_JDT_MANIPULATION).isEqualTo("org.eclipse.jdt.core.manipulation");
		assertThat(CleanUpConstants.PREF_KEY_IMPORT_ORDER).isEqualTo("org.eclipse.jdt.ui.importorder");
		assertThat(CleanUpConstants.PREF_KEY_ONDEMAND_THRESHOLD).isEqualTo("org.eclipse.jdt.ui.ondemandthreshold");
		assertThat(CleanUpConstants.PREF_KEY_STATIC_ONDEMAND_THRESHOLD).isEqualTo("org.eclipse.jdt.ui.staticondemandthreshold");
		assertThat(CleanUpConstants.DEFAULT_IMPORT_ORDER).isEqualTo("java;javax;org;com");
		assertThat(CleanUpConstants.DEFAULT_ONDEMAND_THRESHOLD).isEqualTo("99");
	}

	@Test
	void formatSourceCodeKeyMatchesEclipseConvention() {
		assertThat(CleanUpConstants.FORMAT_SOURCE_CODE_KEY).isEqualTo("cleanup.format_source_code");
	}

	@Test
	void privateConstructorIsInvocableViaReflection() throws Exception {
		// Locks down "this is a utility class" — and gives JaCoCo coverage for the private ctor.
		Constructor<CleanUpConstants> ctor = CleanUpConstants.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
		ctor.setAccessible(true);
		try {
			CleanUpConstants instance = ctor.newInstance();
			assertThat(instance).isNotNull();
		} catch (InvocationTargetException e) {
			throw new AssertionError("private ctor should not throw", e);
		}
	}
}
