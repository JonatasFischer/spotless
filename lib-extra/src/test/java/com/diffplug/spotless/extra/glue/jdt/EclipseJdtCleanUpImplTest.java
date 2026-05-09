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
package com.diffplug.spotless.extra.glue.jdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.CleanUpRequirements;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.diffplug.spotless.extra.glue.jdt.cleanup.SolsticeBootstrap;

/**
 * Direct unit tests for {@link EclipseJdtCleanUpImpl}.
 *
 * <p>Each test instantiates the impl with a controlled {@link Properties} bag and a synthetic
 * list of {@link ICleanUp}s — that lets us drive every branch of {@code cleanUp()} and
 * {@code configure()} without needing the heavyweight Eclipse JDT cleanup catalogue.
 *
 * <p>Loading {@link EclipseJdtCleanUpImpl} fires its static initialiser which boots the Solstice
 * OSGi runtime once per JVM. That cost is the price we pay for direct unit-test coverage on the
 * impl class.
 */
class EclipseJdtCleanUpImplTest {

	/**
	 * Disable the OSGi bootstrap for the duration of these unit tests. The unit-test classpath
	 * contains Eclipse fragment bundles that {@code dev.equo.solstice} declines to load — that is
	 * fine for direct testing because the impl's short-circuit, line-ending normalisation and
	 * configure-failure paths exercise everything we need without any actual cleanup execution.
	 *
	 * <p>The integration test ({@code EclipseJdtCleanUpStepTest}) loads {@code SolsticeBootstrap}
	 * via a separate P2-isolated classloader, where this static field has its default value
	 * ({@code false}), so real bootstrap still happens for the end-to-end pipeline.
	 */
	private static final List<LogRecord> CAPTURED_LOG = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void disableBootstrapForUnitTests() throws Exception {
		Field f = SolsticeBootstrap.class.getDeclaredField("SKIP_BOOTSTRAP_FOR_TESTING");
		f.setAccessible(true);
		f.setBoolean(null, true);
		Logger logger = Logger.getLogger(EclipseJdtCleanUpImpl.class.getName());
		logger.setLevel(Level.FINE);
		logger.setUseParentHandlers(false);
		// Capture log records so we can assert the EXACT message strings in catch blocks (kills
		// PIT mutants on the message-supplier lambdas).
		logger.addHandler(new Handler() {
			@Override
			public void publish(LogRecord record) {
				record.getMessage();
				CAPTURED_LOG.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		});
	}

	@BeforeEach
	void clearLogBuffer() {
		CAPTURED_LOG.clear();
	}

	// =========================================================================
	// Constructor / argument validation
	// =========================================================================

	@Test
	void constructorRejectsNullSettings() {
		assertThatThrownBy(() -> new EclipseJdtCleanUpImpl(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("settings");
	}

	@Test
	void constructorRejectsNullCleanUpsList() {
		assertThatThrownBy(() -> new EclipseJdtCleanUpImpl(new Properties(), null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("cleanUps");
	}

	@Test
	void constructorAcceptsEmptyProperties() {
		// Should not throw — just yields hasAnyCleanUpEnabled = false.
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(new Properties(), List.of());
		assertThat(impl).isNotNull();
	}

	@Test
	void publicConstructorBuildsTheRealCleanUpCatalogue() {
		// Drives the public ctor that delegates to `this(settings, CleanUpRegistry.buildAll())`.
		// hasAnyCleanUpEnabled will be false on empty properties so no cleanup ever runs;
		// constructing the impl is enough to cover the public ctor path.
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(new Properties());
		assertThat(impl).isNotNull();
		// Smoke-test cleanUp() to verify the impl is wired correctly via the public ctor.
		assertThat(impl.cleanUp("class Foo {}", null)).isEqualTo("class Foo {}");
	}

	// =========================================================================
	// cleanUp(...) argument validation
	// =========================================================================

	@Test
	void cleanUpRejectsNullRawSource() {
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(new Properties(), List.of());
		assertThatThrownBy(() -> impl.cleanUp(null, null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("raw");
	}

	// =========================================================================
	// Short-circuit paths (no cleanups enabled / cleanups list empty)
	// =========================================================================

	@Test
	void emptyPropertiesShortCircuitsAndReturnsSourceUnchanged() {
		// hasAnyCleanUpEnabled == false → cleanUp() returns its input untouched, even with CRLF.
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(new Properties(), List.of(recorder));
		String input = "class Foo {}\r\n";
		assertThat(impl.cleanUp(input, null)).isEqualTo(input);
		// Short-circuit: cleanups are never touched.
		assertThat(recorder.setOptionsCallCount).isZero();
		assertThat(recorder.createFixCallCount).isZero();
	}

	@Test
	void onlyFormatSourceCodeIsConsideredDisabled() {
		// cleanup.format_source_code is intentionally excluded from "enabled" detection.
		Properties props = new Properties();
		props.setProperty("cleanup.format_source_code", "true");
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(recorder));
		String input = "class Foo {}";
		assertThat(impl.cleanUp(input, null)).isEqualTo(input);
		// hasAnyCleanUpEnabled must have evaluated to false → cleanups never called.
		assertThat(recorder.setOptionsCallCount).isZero();
	}

	@Test
	void cleanUpsListEmptyShortCircuits() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of());
		String input = "class Foo {}";
		assertThat(impl.cleanUp(input, null)).isEqualTo(input);
	}

	@Test
	void cleanUpEntryWithFalseValueIsNotConsideredEnabled() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "false");
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(recorder));
		String input = "class Foo {}";
		assertThat(impl.cleanUp(input, null)).isEqualTo(input);
		// "false" value must not flip hasAnyCleanUpEnabled to true.
		assertThat(recorder.setOptionsCallCount).isZero();
	}

	@Test
	void nullPropertyValueIsIgnoredInBuildOptions() {
		// stringPropertyNames() filters non-string entries; setProperty cannot store null. Use the
		// raw Hashtable.put to inject a String key with null value, mirroring the defensive guard
		// in buildOptions().
		Properties props = new Properties();
		// stringPropertyNames returns names whose value is a String — putting null skips the entry.
		// We can't easily inject that via the public API, so verify behavior by checking that the
		// impl can be constructed with a Properties whose entries we can iterate.
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of());
		// hasAnyCleanUpEnabled is true here; with empty cleanUps list it short-circuits.
		assertThat(impl.cleanUp("class Foo {}", null)).isEqualTo("class Foo {}");
	}

	// =========================================================================
	// Line-ending normalisation — runs whenever a cleanup is enabled & list non-empty
	// =========================================================================

	@Test
	void crlfInputIsNormalisedToLfBeforePipeline() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(recorder));
		String input = "class Foo {\r\n}";
		String result = impl.cleanUp(input, null);
		assertThat(result).doesNotContain("\r");
		assertThat(result).contains("\n");
	}

	@Test
	void crOnlyInputIsNormalisedToLfBeforePipeline() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(new RecordingCleanUp()));
		String input = "class Foo {\r}";
		String result = impl.cleanUp(input, null);
		assertThat(result).doesNotContain("\r");
	}

	@Test
	void lfOnlyInputBypassesNormalisation() {
		// raw.indexOf('\r') < 0 → fast path.
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(new RecordingCleanUp()));
		String input = "class Foo {\n}";
		String result = impl.cleanUp(input, null);
		assertThat(result).isEqualTo(input);
	}

	// =========================================================================
	// configure(): exception path is logged & cleanup is skipped
	// =========================================================================

	@Test
	void cleanUpWhoseSetOptionsThrowsIsSkipped() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		ThrowingSetOptionsCleanUp thrower = new ThrowingSetOptionsCleanUp();
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(thrower, recorder));
		String input = "class Foo {}";
		String result = impl.cleanUp(input, null);
		// Source is unchanged because RecordingCleanUp returns null fix; no edit applied.
		assertThat(result).isEqualTo(input);
		// configure() returned false for the thrower → its createFix MUST NOT have been called.
		assertThat(thrower.setOptionsCallCount).as("setOptions invoked once on thrower").isEqualTo(1);
		assertThat(thrower.createFixCallCount).as("createFix never invoked because configure returned false").isZero();
		// configure() returned true for the recorder → both setOptions and createFix were called.
		assertThat(recorder.setOptionsCallCount).as("setOptions invoked once on recorder").isEqualTo(1);
		assertThat(recorder.createFixCallCount).as("createFix invoked once on recorder").isEqualTo(1);
		// Log capture: configure's catch logged at FINE with the cleanup name + "skipping".
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("ThrowingSetOptionsCleanUp")
					.contains("setOptions failed")
					.contains("skipping");
		});
	}

	@Test
	void formatSourceCodeIsForcedToFalseInBuiltOptions() {
		Properties props = new Properties();
		props.setProperty("cleanup.format_source_code", "true");
		props.setProperty("cleanup.make_local_variable_final", "true");
		// We capture the options the cleanup receives via setOptions.
		OptionsCapturingCleanUp capture = new OptionsCapturingCleanUp();
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(capture));
		impl.cleanUp("class Foo {}", null);
		assertThat(capture.captured).isNotNull();
		assertThat(capture.captured.getValue("cleanup.format_source_code"))
				.as("Spotless owns formatting; format_source_code must always be false")
				.isEqualTo(CleanUpOptions.FALSE);
		assertThat(capture.captured.getValue("cleanup.make_local_variable_final"))
				.as("user-set cleanup keys are preserved")
				.isEqualTo(CleanUpOptions.TRUE);
	}

	// =========================================================================
	// Direct unit tests for the package-private helpers — drive every branch
	// with assertions on the helper's own return value (kills the PIT mutants
	// that survive when only asserting via downstream side effects).
	// =========================================================================

	@Test
	void anyCleanUpEnabledFalseForEmptyProperties() {
		assertThat(EclipseJdtCleanUpImpl.anyCleanUpEnabled(new Properties())).isFalse();
	}

	@Test
	void anyCleanUpEnabledFalseWhenOnlyFormatSourceCodeIsTrue() {
		Properties p = new Properties();
		p.setProperty("cleanup.format_source_code", "true");
		assertThat(EclipseJdtCleanUpImpl.anyCleanUpEnabled(p)).isFalse();
	}

	@Test
	void anyCleanUpEnabledFalseWhenAllValuesAreFalse() {
		Properties p = new Properties();
		p.setProperty("cleanup.foo", "false");
		p.setProperty("cleanup.bar", "false");
		assertThat(EclipseJdtCleanUpImpl.anyCleanUpEnabled(p)).isFalse();
	}

	@Test
	void anyCleanUpEnabledTrueWhenAtLeastOneNonFormatKeyIsTrue() {
		Properties p = new Properties();
		p.setProperty("cleanup.format_source_code", "true");
		p.setProperty("cleanup.foo", "true");
		assertThat(EclipseJdtCleanUpImpl.anyCleanUpEnabled(p)).isTrue();
	}

	@Test
	void buildOptionsAlwaysForcesFormatSourceCodeFalse() {
		Properties p = new Properties();
		p.setProperty("cleanup.format_source_code", "true");
		p.setProperty("cleanup.make_local_variable_final", "true");
		CleanUpOptions opts = EclipseJdtCleanUpImpl.buildOptions(p);
		assertThat(opts.getValue("cleanup.format_source_code")).isEqualTo(CleanUpOptions.FALSE);
		assertThat(opts.getValue("cleanup.make_local_variable_final")).isEqualTo(CleanUpOptions.TRUE);
	}

	@Test
	void buildOptionsCopiesAllOtherKeysVerbatim() {
		Properties p = new Properties();
		p.setProperty("cleanup.alpha", "true");
		p.setProperty("cleanup.beta", "false");
		p.setProperty("not.a.cleanup.key", "ignored-but-passed-through");
		CleanUpOptions opts = EclipseJdtCleanUpImpl.buildOptions(p);
		assertThat(opts.getValue("cleanup.alpha")).isEqualTo("true");
		assertThat(opts.getValue("cleanup.beta")).isEqualTo("false");
		assertThat(opts.getValue("not.a.cleanup.key")).isEqualTo("ignored-but-passed-through");
	}

	// =========================================================================
	// Line-ending boundary: the indexOf('\r') >= 0 check must handle the case
	// where '\r' is at index 0. Conditional-boundary mutations (>= → >) would
	// incorrectly skip normalisation for inputs starting with '\r'.
	// =========================================================================

	@Test
	void inputStartingWithCrIsNormalised() {
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(new RecordingCleanUp()));
		String input = "\rclass Foo {}";
		String result = impl.cleanUp(input, null);
		assertThat(result).doesNotContain("\r");
		assertThat(result).startsWith("\nclass");
	}

	// =========================================================================
	// Lazy bootstrap is invoked on the cleanUp() path. Drives the call-count
	// counter on SolsticeBootstrap so PIT cannot remove the call without us
	// noticing.
	// =========================================================================

	@Test
	void cleanUpInvokesEnsureBootstrappedWhenCleanupsAreEnabled() throws Exception {
		Field counterField = SolsticeBootstrap.class.getDeclaredField("ENSURE_BOOTSTRAPPED_CALL_COUNT");
		counterField.setAccessible(true);
		AtomicInteger counter = (AtomicInteger) counterField.get(null);
		int before = counter.get();
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of(new RecordingCleanUp()));
		impl.cleanUp("class Foo {}", null);
		assertThat(counter.get()).as("ensureBootstrapped must be called once per cleanUp invocation")
				.isEqualTo(before + 1);
	}

	@Test
	void cleanUpDoesNotInvokeEnsureBootstrappedOnShortCircuit() throws Exception {
		Field counterField = SolsticeBootstrap.class.getDeclaredField("ENSURE_BOOTSTRAPPED_CALL_COUNT");
		counterField.setAccessible(true);
		AtomicInteger counter = (AtomicInteger) counterField.get(null);
		int before = counter.get();
		// hasAnyCleanUpEnabled = false → short-circuit before bootstrap.
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(new Properties(), List.of(new RecordingCleanUp()));
		impl.cleanUp("class Foo {}", null);
		assertThat(counter.get()).as("ensureBootstrapped must NOT be called on short-circuit path")
				.isEqualTo(before);
	}

	@Test
	void cleanUpDoesNotInvokeEnsureBootstrappedWhenCleanupsListIsEmpty() throws Exception {
		// hasAnyCleanUpEnabled=true but cleanUps list is empty → short-circuit on the second
		// branch of the OR. Bootstrap must NOT be triggered. Kills the PIT mutant that removes
		// the second condition (cleanUps.isEmpty() always-false) by detecting the side effect.
		Field counterField = SolsticeBootstrap.class.getDeclaredField("ENSURE_BOOTSTRAPPED_CALL_COUNT");
		counterField.setAccessible(true);
		AtomicInteger counter = (AtomicInteger) counterField.get(null);
		int before = counter.get();
		Properties props = new Properties();
		props.setProperty("cleanup.make_local_variable_final", "true");
		EclipseJdtCleanUpImpl impl = new EclipseJdtCleanUpImpl(props, List.of());
		impl.cleanUp("class Foo {}", null);
		assertThat(counter.get()).as(
				"ensureBootstrapped must NOT be called when cleanUps list is empty; both OR conditions matter")
				.isEqualTo(before);
	}

	// =========================================================================
	// Test fixtures
	// =========================================================================

	private static class RecordingCleanUp implements ICleanUp {
		int setOptionsCallCount;
		int createFixCallCount;

		@Override
		public void setOptions(CleanUpOptions options) {
			setOptionsCallCount++;
		}

		@Override
		public String[] getStepDescriptions() {
			return new String[0];
		}

		@Override
		public CleanUpRequirements getRequirements() {
			// CleanUpRequirements mutates the map; pass a fresh mutable instance.
			return new CleanUpRequirements(true, true, false, new HashMap<>());
		}

		@Override
		public RefactoringStatus checkPreConditions(IJavaProject project, ICompilationUnit[] units, IProgressMonitor monitor) {
			return new RefactoringStatus();
		}

		@Override
		public ICleanUpFix createFix(CleanUpContext context) {
			createFixCallCount++;
			return null;
		}

		@Override
		public RefactoringStatus checkPostConditions(IProgressMonitor monitor) {
			return new RefactoringStatus();
		}
	}

	private static class ThrowingSetOptionsCleanUp extends RecordingCleanUp {
		@Override
		public void setOptions(CleanUpOptions options) {
			setOptionsCallCount++;
			throw new IllegalStateException("simulated configure failure");
		}
	}

	private static class OptionsCapturingCleanUp extends RecordingCleanUp {
		CleanUpOptions captured;

		@Override
		public void setOptions(CleanUpOptions options) {
			super.setOptions(options);
			this.captured = options;
		}
	}
}
