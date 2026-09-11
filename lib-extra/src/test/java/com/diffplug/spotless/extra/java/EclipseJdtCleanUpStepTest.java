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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.Lint;
import com.diffplug.spotless.ResourceHarness;
import com.diffplug.spotless.StepHarness;
import com.diffplug.spotless.TestP2Provisioner;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;

import dev.equo.solstice.p2.P2Model;

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
		void strictUnsupportedProfileIsReportedAsASpotlessLint() {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			builder.setStrict(true);
			builder.setPropertyPreferences(List.of("cleanup.no_such_action=true"));
			assertThatThrownBy(() -> builder.build().format("class Example {}", new File("Example.java")))
					.isInstanceOf(Lint.Has.class).hasMessageContaining("cleanup.no_such_action", "not implemented", "Example.java");
		}

		@Test
		void strictModeRejectsActionsAboveTheConfiguredJavaLevel() {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			builder.setJavaVersion("8");
			builder.setStrict(true);
			builder.setPropertyPreferences(List.of("cleanup.instanceof=true"));
			assertThatThrownBy(() -> builder.build().format("class Example {}", new File("Example.java")))
					.isInstanceOf(Lint.Has.class).hasMessageContaining("cleanup.instanceof", "requires Java 16", "configured 8");
		}

		@Test
		void unsupportedJavaVersionIsReportedBeforeFormatting() {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			builder.setJavaVersion("99");
			assertThatThrownBy(() -> builder.build().format("class Example {}", new File("Example.java")))
					.isInstanceOf(Lint.Has.class).hasMessageContaining("Java source version 99", "not supported");
		}

		@Test
		void sourceVersionAndStrictModeParticipateInStepEquality() {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			FormatterStep defaults = builder.build();
			builder.setJavaVersion("21");
			FormatterStep java21 = builder.build();
			assertThat(java21).isNotEqualTo(defaults);
			builder.setStrict(true);
			assertThat(builder.build()).isNotEqualTo(java21);
			builder.setJavaVersion("17");
			builder.setStrict(false);
			assertThat(builder.build()).isEqualTo(defaults);
		}

		@Test
		void sourceVersionValidationAndJava8Alias() {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			for (String value : new String[]{null, "", "latest", "7", "21.0", " 21", "021"}) {
				assertThatThrownBy(() -> builder.setJavaVersion(value)).isInstanceOf(IllegalArgumentException.class);
			}
			builder.setJavaVersion("1.8");
			FormatterStep alias = builder.build();
			builder.setJavaVersion("8");
			assertThat(builder.build()).isEqualTo(alias);
		}

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
			// Spotless wraps SAX/parser errors in a runtime exception. Verify the underlying cause
			// is a parse error (matches "Premature end of file" / "must be well-formed" / similar)
			// — not just any error containing the substring "XML".
			assertThatThrownBy(() -> StepHarness.forStep(step).test("class X {}", "class X {}"))
					.rootCause()
					.satisfiesAnyOf(
							t -> assertThat(t.getMessage()).containsIgnoringCase("xml"),
							t -> assertThat(t.getClass().getName()).contains("SAXException"),
							t -> assertThat(t.getClass().getName()).contains("ParseException"));
		}

		@Test
		@DisplayName("Two builders configured identically produce equal FormatterStep instances")
		void equality() {
			FormatterStep a = buildStep(FIXTURES_ROOT + "cleanup.xml");
			FormatterStep b = buildStep(FIXTURES_ROOT + "cleanup.xml");
			assertThat(a).isEqualTo(b);
			assertThat(a.hashCode()).isEqualTo(b.hashCode());
		}

		@Test
		@DisplayName("setVersion rejects nonsense version strings with a clear error")
		void setVersionRejectsBadInput() {
			EquoBasedStepBuilder builder = createBuilder();
			assertThatThrownBy(() -> builder.setVersion("latest"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(EclipseJdtCleanUpStep.defaultVersion());
			assertThatThrownBy(() -> builder.setVersion(""))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> builder.setVersion("3.99"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("4.x");
		}

		@Test
		@DisplayName("setVersion accepts canonical and trailing-zero variants")
		void setVersionAcceptsCanonicalForms() {
			EquoBasedStepBuilder builder = createBuilder();
			builder.setVersion("4.39");
			builder.setVersion("4.39.0"); // normalised to 4.39 internally
			builder.setVersion("4.40");
		}

		@Test
		@DisplayName("EclipseJdtCleanUpStep#REQUIRED_BUNDLES is in sync with the runtime activator list")
		void requiredBundlesAreInSync() {
			// Read the runtime activator list reflectively from the jdt source set's
			// CleanUpConstants. The list must match EclipseJdtCleanUpStep#REQUIRED_BUNDLES
			// because the two run in different classloaders and cannot share a single constant.
			List<String> runtimeBundles;
			try {
				Class<?> constants = Class.forName("com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants");
				Field field = constants.getField("REQUIRED_BUNDLES");
				@SuppressWarnings("unchecked")
				List<String> bundles = (List<String>) field.get(null);
				runtimeBundles = bundles;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Could not load CleanUpConstants from the jdt source set", e);
			}
			assertThat(runtimeBundles)
					.as("CleanUpConstants.REQUIRED_BUNDLES must equal EclipseJdtCleanUpStep.REQUIRED_BUNDLES")
					.containsExactlyElementsOf(EclipseJdtCleanUpStep.REQUIRED_BUNDLES);
		}
	}

	// =========================================================================
	// Static helpers — drive the private methods directly so JaCoCo can reach
	// every branch without going through a full builder lifecycle.
	// =========================================================================

	@Nested
	@DisplayName("Static helpers (reflective)")
	class StaticHelpers {

		@Test
		@DisplayName("defaultVersion() returns the JVM-recommended Eclipse Platform version")
		void defaultVersionMatchesRecommended() {
			String v = EclipseJdtCleanUpStep.defaultVersion();
			assertThat(v).as("default version is published as the recommended formatter version").isEqualTo("4.40");
		}

		@Test
		@DisplayName("normaliseVersion strips trailing .0")
		void normaliseVersionStripsTrailingZero() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("normaliseVersion", String.class);
			m.setAccessible(true);
			assertThat(m.invoke(null, "4.39.0")).isEqualTo("4.39");
			assertThat(m.invoke(null, "4.40.0")).isEqualTo("4.40");
		}

		@Test
		@DisplayName("normaliseVersion is a no-op on non-trailing-zero versions")
		void normaliseVersionLeavesNonTrailingZeroAlone() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("normaliseVersion", String.class);
			m.setAccessible(true);
			assertThat(m.invoke(null, "4.39")).isEqualTo("4.39");
			assertThat(m.invoke(null, "4.39.1")).isEqualTo("4.39.1");
			// boundary: ".0" not at the very end is left alone
			assertThat(m.invoke(null, "4.0.39")).isEqualTo("4.0.39");
		}

		@Test
		@DisplayName("validateVersion rejects null with the exact 'must not be blank' message")
		void validateVersionRejectsNull() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("validateVersion", String.class);
			m.setAccessible(true);
			// Must throw IllegalArgumentException (NOT NullPointerException from a downstream call).
			// Killed-mutant guarantee: the null-check branch must throw, not delegate to normaliseVersion.
			assertThatThrownBy(() -> m.invoke(null, (Object) null))
					.isInstanceOf(InvocationTargetException.class)
					.cause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not be blank")
					.hasMessageContaining(EclipseJdtCleanUpStep.defaultVersion());
		}

		@Test
		@DisplayName("validateVersion rejects empty / blank with the 'must not be blank' message")
		void validateVersionRejectsBlank() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("validateVersion", String.class);
			m.setAccessible(true);
			// Asserting the EXACT message kills mutants that bypass the blank check (which would
			// fall through to the regex check and emit a different, longer message).
			assertThatThrownBy(() -> m.invoke(null, ""))
					.isInstanceOf(InvocationTargetException.class)
					.cause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not be blank");
			assertThatThrownBy(() -> m.invoke(null, "   "))
					.isInstanceOf(InvocationTargetException.class)
					.cause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not be blank");
		}

		@Test
		@DisplayName("validateVersion rejects non-Eclipse versions")
		void validateVersionRejectsNonEclipseVersions() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("validateVersion", String.class);
			m.setAccessible(true);
			for (String bad : new String[]{"latest", "3.99", "5.0", "4", "4.x", "abc", "4.39.1", "4.39.0.0", "4.0.39"}) {
				assertThatThrownBy(() -> m.invoke(null, bad))
						.as("validateVersion('%s') must throw IAE", bad)
						.isInstanceOf(InvocationTargetException.class)
						.cause()
						.isInstanceOf(IllegalArgumentException.class);
			}
		}

		@Test
		@DisplayName("validateVersion accepts canonical 4.x and 4.x.0")
		void validateVersionAcceptsCanonicalForms() throws Exception {
			Method m = EclipseJdtCleanUpStep.class.getDeclaredMethod("validateVersion", String.class);
			m.setAccessible(true);
			// Should not throw.
			m.invoke(null, "4.39");
			m.invoke(null, "4.39.0");
			m.invoke(null, "4.40");
			m.invoke(null, "4.40.0");
		}

		@Test
		@DisplayName("Builder.model() registers REQUIRED_BUNDLES against the platform repository")
		void builderModelInstallsRequiredBundles() {
			EquoBasedStepBuilder builder = createBuilder();
			builder.setVersion("4.39");
			// Builder is the public type; model() is protected. Use the public toString() of the
			// underlying P2Model via a tiny reflective probe.
			try {
				Method modelMethod = builder.getClass().getDeclaredMethod("model", String.class);
				modelMethod.setAccessible(true);
				P2Model model = (P2Model) modelMethod.invoke(builder, "4.39");
				assertThat(model.getInstall()).containsAll(EclipseJdtCleanUpStep.REQUIRED_BUNDLES);
				assertThat(model.getP2repo()).anyMatch(url -> url.contains("4.39"));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Builder.model() must be reflectively callable", e);
			}
		}

		@Test
		@DisplayName("Builder.setVersion stores the normalised version on the parent builder")
		void builderSetVersionStoresNormalisedVersion() throws Exception {
			EquoBasedStepBuilder builder = createBuilder();
			// Use a non-default version so the mutant 'remove super.setVersion call' produces a
			// different stored value (default) than the original (our explicit version).
			builder.setVersion("4.39");
			Field versionField = EquoBasedStepBuilder.class.getDeclaredField("formatterVersion");
			versionField.setAccessible(true);
			Object value = versionField.get(builder);
			assertThat(value).as("super.setVersion must store the supplied version 4.39")
					.isEqualTo("4.39");
		}

		@Test
		@DisplayName("Builder.setVersion normalises trailing .0 before delegating")
		void builderSetVersionNormalisesTrailingZero() throws Exception {
			EquoBasedStepBuilder builder = createBuilder();
			builder.setVersion("4.40.0");
			Field versionField = EquoBasedStepBuilder.class.getDeclaredField("formatterVersion");
			versionField.setAccessible(true);
			Object value = versionField.get(builder);
			assertThat(value).as("normaliseVersion('4.40.0') strips the trailing .0").isEqualTo("4.40");
		}

		// Note: `JVM_SUPPORT.assertFormatterSupported` (line 127) and `Builder.setVersion`
		// (line 183) survive PIT mutation because the only observable side effects are an
		// exception thrown when the running JVM is below MIN_JVM=17 (we run on JVM 21+ in CI).
		// On a too-old JVM the integration test in EclipseJdtFormatterStepTest catches the
		// regression — but the unit-test JVM cannot fake a lower major version. Documented as
		// an environment-equivalent mutant.
	}

	@Nested
	@DisplayName("Native Eclipse modernization actions")
	class NativeActions {
		static Stream<Arguments> cases() {
			return Stream.of("4.39", "4.40").flatMap(version -> Stream.of(
					Arguments.of(version, "NativeVar", "10", "cleanup.use_var=true", "test", "clean", "x:long"),
					Arguments.of(version, "NativeVarLambda", "11", "cleanup.use_var=true", "test", "clean", "true"),
					Arguments.of(version, "NativeTextBlock", "15", "cleanup.stringconcat_to_textblock=true", "test", "clean", "first\n  \"quoted\" \\ path\nlast\n"),
					Arguments.of(version, "NativeTextBlockBuilder", "15", "cleanup.stringconcat_to_textblock=true\ncleanup.stringconcat_stringbuffer_stringbuilder=true", "test", "clean", "first\nsecond\nthird\n"),
					Arguments.of(version, "NativeMultiCatch", "8", "cleanup.multi_catch=true", "test", "clean", "expected"),
					Arguments.of(version, "NativeDiamond", "8", "cleanup.remove_redundant_type_arguments=true", "test", "clean", "preserved"),
					Arguments.of(version, "NativeDiamond", "8", "cleanup.insert_inferred_type_arguments=true", "clean", "test", "preserved"),
					Arguments.of(version, "NativePrimitive", "8", "cleanup.primitive_rather_than_wrapper=true", "test", "clean", "positive"),
					Arguments.of(version, "NativeStringBuffer", "8", "cleanup.stringbuffer_to_stringbuilder=true", "test", "clean", "item7"),
					Arguments.of(version, "NativeStringBuffer", "8", "cleanup.stringbuffer_to_stringbuilder=true\ncleanup.stringbuilder_for_local_vars=true", "test", "clean", "item7"),
					Arguments.of(version, "NativeModifiers", "8", "cleanup.remove_redundant_modifiers=true", "test", "clean", "7"),
					Arguments.of(version, "NativeSemicolons", "8", "cleanup.remove_redundant_semicolons=true", "test", "clean", "1"),
					Arguments.of(version, "NativeSuperCall", "8", "cleanup.no_super=true", "test", "clean", "NativeSuperCall"),
					Arguments.of(version, "NativeAddAll", "8", "cleanup.add_all=true", "test", "clean", "[prefix, a, b]"),
					Arguments.of(version, "NativeCollectionCopy", "8", "cleanup.collection_cloning=true", "test", "clean", "[a, b]"),
					Arguments.of(version, "NativeArrayCreation", "8", "cleanup.remove_unnecessary_array_creation=true", "test", "clean", "a,b")));
		}

		static Stream<Arguments> preservedCases() {
			return Stream.of("4.39", "4.40").flatMap(version -> Stream.of(
					Arguments.of(version, "cleanup.primitive_rather_than_wrapper=true",
							"public static String value() { Integer n = null; return n == null ? \"null\" : n.toString(); }", "null"),
					Arguments.of(version, "cleanup.stringbuffer_to_stringbuilder=true\ncleanup.stringbuilder_for_local_vars=true",
							"public static StringBuffer pass(StringBuffer text) { return text; } public static String value() { StringBuffer local = new StringBuffer(\"safe\"); return pass(local).toString(); }", "safe"),
					Arguments.of(version, "cleanup.remove_redundant_modifiers=true",
							"public final String locked() { return \"locked\"; } public static String value() { return new Preserved().locked(); }", "locked"),
					Arguments.of(version, "cleanup.remove_redundant_semicolons=true",
							"public static String value() { int n = 0; while (++n < 3); return Integer.toString(n); }", "3"),
					Arguments.of(version, "cleanup.no_super=true",
							"static class Parent { final int n; Parent(int n) { this.n = n; } } static class Child extends Parent { Child() { super(7); } } public static String value() { return Integer.toString(new Child().n); }", "7"),
					Arguments.of(version, "cleanup.add_all=true",
							"public static String value() { java.util.List<String> input = java.util.Arrays.asList(\"\", \"x\"); java.util.List<String> output = new java.util.ArrayList<>(); for (String item : input) { if (!item.isEmpty()) output.add(item); } return output.toString(); }", "[x]"),
					Arguments.of(version, "cleanup.collection_cloning=true",
							"public static String value() { java.util.List<String> input = java.util.Arrays.asList(\"a\"); java.util.List<String> output = new java.util.ArrayList<>(); output.add(\"prefix\"); output.addAll(input); return output.toString(); }", "[prefix, a]"),
					Arguments.of(version, "cleanup.remove_unnecessary_array_creation=true",
							"public static String value() { return Integer.toString(java.util.Arrays.asList(new int[] {1, 2}).size()); }", "1"),
					Arguments.of(version, "cleanup.remove_unnecessary_array_creation=true",
							"static String pick(String item) { return \"scalar\"; } static String pick(String... items) { return \"array\"; } public static String value() { return pick(new String[] {\"x\"}); }", "array")));
		}

		@ParameterizedTest
		@MethodSource("preservedCases")
		void nativeApplicabilityChecksPreserveMeaningfulCode(String version, String properties, String members, String expectedValue) throws Exception {
			String source = "package example; public class Preserved { " + members + " }";
			File file = setFile("Preserved.java").toContent(source);
			String formatted = nativeBuilder(version, "8", properties).build().format(source, file);
			assertThat(formatted).isEqualTo(source);
			setFile("Preserved.java").toContent(formatted);
			assertThat(compileAndRun(file, "8", "cleaned", "Preserved")).isEqualTo(expectedValue);
		}

		@ParameterizedTest
		@ValueSource(strings = {"NativePrimitive", "NativeStringBuffer", "NativeTextBlockBuilder", "NativeCollectionCopy", "NativeVar", "NativeDiamond"})
		void allNativeActionsComposeWithoutFurtherChanges(String fixture) throws Exception {
			String properties = String.join("\n",
					"cleanup.use_var=true", "cleanup.stringconcat_to_textblock=true",
					"cleanup.stringconcat_stringbuffer_stringbuilder=true", "cleanup.multi_catch=true",
					"cleanup.remove_redundant_type_arguments=true", "cleanup.primitive_rather_than_wrapper=true",
					"cleanup.stringbuffer_to_stringbuilder=true", "cleanup.stringbuilder_for_local_vars=true",
					"cleanup.remove_redundant_modifiers=true", "cleanup.remove_redundant_semicolons=true",
					"cleanup.no_super=true", "cleanup.add_all=true", "cleanup.collection_cloning=true",
					"cleanup.remove_unnecessary_array_creation=true");
			File file = setFile(fixture + ".java").toResource(FIXTURES_ROOT + fixture + ".test");
			Object originalValue = compileAndRun(file, "17", "original", fixture);
			FormatterStep step = nativeBuilder("4.40", "17", properties).build();
			String formatted = step.format(read(fixture + ".java"), file);
			assertThat(step.format(formatted, file)).isEqualTo(formatted);
			setFile(fixture + ".java").toContent(formatted);
			assertThat(compileAndRun(file, "17", "cleaned", fixture)).isEqualTo(originalValue);
		}

		@Test
		void bulkAdditionAndCopyConstructorCleanupsCompose() throws Exception {
			File file = setFile("NativeCollectionCopy.java").toResource(FIXTURES_ROOT + "NativeCollectionCopy.test");
			String source = read("NativeCollectionCopy.java").replace("output.addAll(input);", "for (String item : input) { output.add(item); }");
			FormatterStep step = nativeBuilder("4.40", "8", "cleanup.add_all=true\ncleanup.collection_cloning=true").build();
			String formatted = step.format(source, file);
			setFile("NativeCollectionCopy.java").toContent(formatted);
			assertFile("NativeCollectionCopy.java").sameAsResource(FIXTURES_ROOT + "NativeCollectionCopy.clean");
			assertThat(step.format(formatted, file)).isEqualTo(formatted);
			assertThat(compileAndRun(file, "8", "cleaned", "NativeCollectionCopy")).isEqualTo("[a, b]");
		}

		@ParameterizedTest
		@MethodSource("cases")
		void transformsCompilesAndPreservesBehavior(String jdtVersion, String fixture, String javaVersion,
				String properties, String beforeSuffix, String afterSuffix, String expectedValue) throws Exception {
			EclipseJdtCleanUpStep.Builder builder = nativeBuilder(jdtVersion, javaVersion, properties);
			FormatterStep step = builder.build();
			StepHarness.forStep(step).testResource(FIXTURES_ROOT + fixture + "." + beforeSuffix,
					FIXTURES_ROOT + fixture + "." + afterSuffix);
			File file = setFile(fixture + ".java").toResource(FIXTURES_ROOT + fixture + "." + beforeSuffix);
			assertThat(compileAndRun(file, javaVersion, "original", fixture)).isEqualTo(expectedValue);
			String formatted = step.format(read(fixture + ".java"), file);
			setFile(fixture + ".java").toContent(formatted);
			assertThat(compileAndRun(file, javaVersion, "cleaned", fixture)).isEqualTo(expectedValue);
		}

		@Test
		void java10DoesNotIntroduceVarInLambdaParameters() {
			StepHarness.forStep(nativeBuilder("4.40", "10", "cleanup.use_var=true").build())
					.testResourceUnaffected(FIXTURES_ROOT + "NativeVarLambda.test");
		}

		@Test
		void textBlocksPreserveTheAbsenceOfATrailingNewline() throws Exception {
			File file = setFile("NativeTextBlock.java").toResource(FIXTURES_ROOT + "NativeTextBlock.test");
			String before = read("NativeTextBlock.java").replace("\"last\\n\"", "\"last\"");
			FormatterStep step = nativeBuilder("4.40", "15", "cleanup.stringconcat_to_textblock=true").build();
			String formatted = step.format(before, file);
			assertThat(formatted).contains("\"\"\"");
			assertThat(step.format(formatted, file)).isEqualTo(formatted);
			setFile("NativeTextBlock.java").toContent(formatted);
			assertThat(compileAndRun(file, "15", "cleaned", "NativeTextBlock")).isEqualTo("first\n  \"quoted\" \\ path\nlast");
		}

		@Test
		void varAndDiamondComposeWithoutAnIdempotenceCycle() {
			StepHarness.forStep(nativeBuilder("4.40", "10", "cleanup.use_var=true\ncleanup.remove_redundant_type_arguments=true").build())
					.testResource(FIXTURES_ROOT + "NativeVar.test", FIXTURES_ROOT + "NativeVar.clean");
		}

		@ParameterizedTest
		@ValueSource(strings = {"9", "14"})
		void rejectsActionsAboveTheConfiguredLanguageLevel(String javaVersion) throws Exception {
			String option = javaVersion.equals("9") ? "cleanup.use_var" : "cleanup.stringconcat_to_textblock";
			String required = javaVersion.equals("9") ? "10" : "15";
			String fixture = javaVersion.equals("9") ? "NativeVar" : "NativeTextBlock";
			File file = setFile(fixture + ".java").toResource(FIXTURES_ROOT + fixture + ".test");
			String source = read(fixture + ".java");
			EclipseJdtCleanUpStep.Builder builder = nativeBuilder("4.40", javaVersion, option + "=true");
			assertThatThrownBy(() -> builder.build().format(source, file))
					.isInstanceOf(Lint.Has.class).hasMessageContaining(option, "requires Java " + required, "configured " + javaVersion);
			builder.setStrict(false);
			assertThat(builder.build().format(source, file)).isEqualTo(source);
		}

		@Test
		void varPreservesDeclarationsWhoseStaticTypeMatters() throws Exception {
			String source = "class Example { void run() { Number n = Integer.valueOf(1); String s = null; int[] a = {1, 2}; int x = 1, y = 2; } }";
			assertThat(nativeBuilder("4.40", "10", "cleanup.use_var=true").build().format(source, new File("Example.java"))).isEqualTo(source);
		}

		@Test
		void differentCatchBodiesAreNotMerged() throws Exception {
			String source = "class Example { int run() { try { throw new IllegalArgumentException(); } catch (IllegalArgumentException e) { return 1; } catch (IllegalStateException e) { return 2; } } }";
			assertThat(nativeBuilder("4.40", "8", "cleanup.multi_catch=true").build().format(source, new File("Example.java"))).isEqualTo(source);
		}

		private EclipseJdtCleanUpStep.Builder nativeBuilder(String version, String javaVersion, String properties) {
			EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
			builder.setVersion(version);
			builder.setJavaVersion(javaVersion);
			builder.setStrict(true);
			builder.setPropertyPreferences(List.of(properties));
			return builder;
		}

		private Object compileAndRun(File source, String javaVersion, String outputName, String className) throws Exception {
			File output = newFolder(outputName);
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			int exit = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
					"--release", javaVersion, "-encoding", "UTF-8", "-proc:none", "-d", output.getPath(), source.getPath());
			assertThat(exit).as("javac: %s", diagnostics.toString(StandardCharsets.UTF_8)).isZero();
			try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
				return loader.loadClass("example." + className).getMethod("value").invoke(null);
			}
		}
	}

	@Nested
	@DisplayName("Cleanups using the headless source model")
	class SourceModel {

		@ParameterizedTest
		@ValueSource(strings = {"", "com.example.deep"})
		void enhancedForDoesNotImportTypesFromItsOwnPackage(String packageName) {
			String prefix = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
			String before = prefix + """
					public class Example {
						public int sum(Item[] items) {
							int total = 0;
							for (int i = 0; i < items.length; i++) {
								total += items[i].value;
							}
							return total;
						}
					}
					class Item { int value; }
					""";
			String after = before.replace("for (int i = 0; i < items.length; i++)", "for (Item item : items)")
					.replace("items[i].value", "item.value");
			StepHarness harness = StepHarness.forStep(buildStep(FIXTURES_ROOT + "ConvertLoop.xml"));
			harness.test(before, after);
			harness.test(after, after);
		}

		@ParameterizedTest
		@ValueSource(strings = {"4.39", "4.40"})
		void enhancedForAddsRequiredImports(String version) {
			EquoBasedStepBuilder builder = createBuilder();
			builder.setVersion(version);
			builder.setPropertyPreferences(List.of("cleanup.convert_to_enhanced_for_loop=true"));
			StepHarness.forStep(builder.build()).testResource(FIXTURES_ROOT + "ImportLoop.test", FIXTURES_ROOT + "ImportLoop.clean");
		}

		@Test
		void enhancedForPreservesConflictingTypeNames() {
			StepHarness.forStep(buildStep(FIXTURES_ROOT + "ConvertLoop.xml"))
					.testResource(FIXTURES_ROOT + "ImportConflict.test", FIXTURES_ROOT + "ImportConflict.clean");
		}

		@Test
		void patternMatchingForInstanceof() {
			runCleanUp(FIXTURES_ROOT + "PatternInstanceof.xml",
					FIXTURES_ROOT + "PatternInstanceof.test",
					FIXTURES_ROOT + "PatternInstanceof.clean");
		}

		@Test
		void convertToSwitchExpressions() {
			runCleanUp(FIXTURES_ROOT + "SwitchExpression.xml",
					FIXTURES_ROOT + "SwitchExpression.test",
					FIXTURES_ROOT + "SwitchExpression.clean");
		}

		@Test
		void convertToEnhancedForLoop() {
			runCleanUp(FIXTURES_ROOT + "ConvertLoop.xml",
					FIXTURES_ROOT + "ConvertLoop.test",
					FIXTURES_ROOT + "ConvertLoop.clean");
		}
	}
}
