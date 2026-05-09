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
package com.diffplug.spotless.extra.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.ResourceHarness;
import com.diffplug.spotless.StepHarness;
import com.diffplug.spotless.TestP2Provisioner;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;

/**
 * Behaviour of {@link EclipseJdtCleanUpStep}. Each test loads a profile XML from
 * {@code testlib/src/main/resources/java/eclipse/cleanup/} and applies it to a {@code .test}
 * fixture, comparing against the corresponding {@code .clean} fixture.
 *
 * <p>{@link StepHarness#testResource(String, String)} additionally asserts the step is idempotent
 * (running it on the {@code .clean} output produces no further changes), so we get idempotency
 * coverage for free.
 */
class EclipseJdtCleanUpStepTest extends ResourceHarness {

	private static final String FIXTURES_ROOT = "java/eclipse/cleanup/";

	private static EquoBasedStepBuilder createBuilder() {
		return EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
	}

	private FormatterStep buildStep(String profileResource) {
		File profile = setFile(profileResource.substring(profileResource.lastIndexOf('/') + 1))
				.toResource(profileResource);
		EquoBasedStepBuilder builder = createBuilder();
		builder.setPreferences(List.of(profile));
		return builder.build();
	}

	private void runCleanUp(String profile, String before, String after) {
		StepHarness.forStep(buildStep(profile)).testResource(before, after);
	}

	// =========================================================================
	// Working cleanups (single-cleanup tests)
	// =========================================================================

	@Nested
	@DisplayName("Cleanups that work end-to-end")
	class Working {

		@Test
		@DisplayName("make-final + use-lambda + remove-unused-imports")
		void makeFinal_useLambda_removeUnusedImports() {
			runCleanUp(FIXTURES_ROOT + "cleanup.xml",
					FIXTURES_ROOT + "CleanUpExample.test",
					FIXTURES_ROOT + "CleanUpExample.clean");
		}

		@Test
		@DisplayName("new Integer(v) -> Integer.valueOf(v)")
		void valueOfRatherThanInstantiation() {
			runCleanUp(FIXTURES_ROOT + "ValueOf.xml",
					FIXTURES_ROOT + "ValueOf.test",
					FIXTURES_ROOT + "ValueOf.clean");
		}

		@Test
		@DisplayName("(String) (String) o -> (String) o")
		void removeUnnecessaryCasts() {
			runCleanUp(FIXTURES_ROOT + "UnnecessaryCast.xml",
					FIXTURES_ROOT + "UnnecessaryCast.test",
					FIXTURES_ROOT + "UnnecessaryCast.clean");
		}

		@Test
		@DisplayName("flag == true -> flag")
		void booleanValueRatherThanComparison() {
			runCleanUp(FIXTURES_ROOT + "BooleanComparison.xml",
					FIXTURES_ROOT + "BooleanComparison.test",
					FIXTURES_ROOT + "BooleanComparison.clean");
		}
	}

	// =========================================================================
	// Behavioural guarantees
	// =========================================================================

	@Nested
	@DisplayName("Behavioural guarantees")
	class Guarantees {

		@Test
		@DisplayName("A profile with no cleanups enabled leaves the source unchanged")
		void emptyProfileIsNoOp() {
			StepHarness.forStep(buildStep(FIXTURES_ROOT + "Empty.xml"))
					.testResourceUnaffected(FIXTURES_ROOT + "Simple.test");
		}

		@Test
		@DisplayName("cleanup.format_source_code=true is silently forced to false")
		void formatSourceCodeIsForcedFalse() {
			// Profile sets format_source_code=true plus make_local_variable_final=true. Spotless
			// owns formatting, so the format_source_code option must be ignored — only the
			// structural cleanup should land in the output.
			runCleanUp(FIXTURES_ROOT + "FormatSourceCode.xml",
					FIXTURES_ROOT + "Simple.test",
					FIXTURES_ROOT + "Simple.clean");
		}

		@Test
		@DisplayName("Malformed profile XML is reported when the step runs")
		void invalidProfileXml() {
			File brokenProfile = setFile("Broken.xml").toContent("<?xml version=\"1.0\"?><not-a-profile");
			EquoBasedStepBuilder builder = createBuilder();
			builder.setPreferences(List.of(brokenProfile));
			FormatterStep step = builder.build();
			assertThatThrownBy(() -> StepHarness.forStep(step).test("class X {}", "class X {}"))
					.hasMessageContaining("XML");
		}

		@Test
		@DisplayName("Two builders configured identically produce equal FormatterStep instances")
		void equality() {
			FormatterStep a = buildStep(FIXTURES_ROOT + "cleanup.xml");
			FormatterStep b = buildStep(FIXTURES_ROOT + "cleanup.xml");
			assertThat(a).isEqualTo(b);
			assertThat(a.hashCode()).isEqualTo(b.hashCode());
		}
	}

	// =========================================================================
	// Cleanups whose fix passes through CompilationUnitRewrite.attachChange ->
	// ImportRewriteAnalyzer, which traverses the IPackageFragmentRoot hierarchy
	// (calls isArchive(), root resource, etc.). Stubbing these out without a real
	// workspace runs into a chain of NPEs that goes deeper than is practical to
	// fake. Tracked as a known limitation in EclipseJdtCleanUpStep's Javadoc.
	// =========================================================================

	@Nested
	@DisplayName("Cleanups requiring a real workspace (currently disabled)")
	class RequiresWorkspace {

		@Test
		@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer")
		void patternMatchingForInstanceof() {
			runCleanUp(FIXTURES_ROOT + "PatternInstanceof.xml",
					FIXTURES_ROOT + "PatternInstanceof.test",
					FIXTURES_ROOT + "PatternInstanceof.clean");
		}

		@Test
		@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer")
		void convertToSwitchExpressions() {
			runCleanUp(FIXTURES_ROOT + "SwitchExpression.xml",
					FIXTURES_ROOT + "SwitchExpression.test",
					FIXTURES_ROOT + "SwitchExpression.clean");
		}

		@Test
		@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer")
		void convertToEnhancedForLoop() {
			runCleanUp(FIXTURES_ROOT + "ConvertLoop.xml",
					FIXTURES_ROOT + "ConvertLoop.test",
					FIXTURES_ROOT + "ConvertLoop.clean");
		}
	}
}
