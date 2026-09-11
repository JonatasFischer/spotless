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

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.diffplug.common.collect.ImmutableMap;
import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.Jvm;
import com.diffplug.spotless.Lint;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.SerializedFunction;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;
import com.diffplug.spotless.extra.P2Provisioner;

import dev.equo.solstice.p2.P2Model;

/**
 * Formatter step which applies Eclipse JDT Clean Up actions.
 *
 * <p>The clean up profile is configured via an XML file exported from Eclipse IDE via
 * <em>Preferences &rarr; Java &rarr; Code Style &rarr; Clean Up &rarr; Export</em>.
 *
 * <p>Usage in {@code build.gradle}:
 * <pre>
 * spotless {
 *   java {
 *     eclipseCleanUp().configFile 'path/to/cleanup.xml'
 *   }
 * }
 * </pre>
 *
 * <p>The implementation bootstraps a full Equo Solstice OSGi runtime (mirroring the pattern from
 * {@code GrEclipseFormatterStepImpl}) so supported Eclipse JDT clean up actions &mdash; including
 * those that depend on the {@code ImportRewrite} pipeline (remove unused imports, lambda
 * conversion, ...) &mdash; can run outside Eclipse IDE.
 *
 * <h2>Verified working cleanups</h2>
 * <ul>
 *   <li>{@code cleanup.make_variable_declarations_final} (master + variants
 *       {@code make_local_variable_final}, {@code make_parameters_final},
 *       {@code make_private_fields_final})</li>
 *   <li>{@code cleanup.convert_functional_interfaces} + {@code cleanup.use_lambda}</li>
 *   <li>{@code cleanup.remove_unused_imports}</li>
 *   <li>{@code cleanup.remove_unnecessary_casts}</li>
 *   <li>{@code cleanup.valueof_rather_than_instantiation}</li>
 *   <li>{@code cleanup.boolean_value_rather_than_comparison}</li>
 *   <li>{@code cleanup.instanceof} (pattern matching for instanceof)</li>
 *   <li>{@code cleanup.convert_to_switch_expressions}</li>
 *   <li>{@code cleanup.convert_to_enhanced_for_loop}</li>
 * </ul>
 *
 * <h2>Known limitations</h2>
 *
 * <p>The Java source level is configurable with {@link Builder#setJavaVersion(String)} and
 * defaults to 17, independently of the JDT version and host JVM. Type resolution uses the runtime
 * JRE, without a project dependency classpath. Only the actions in the headless cleanup registry run.
 *
 * <p>The current source is represented by an in-memory project, source root, package and
 * compilation unit. This supports import rewriting without importing the user's project into
 * an Eclipse workspace, but does not provide cross-file project resolution or workspace indexes.
 *
 * <p>By default, enabled unsupported options, language-level incompatibilities and failed actions
 * produce warnings. A failed action leaves its input unchanged and other actions may continue.
 * {@link Builder#setStrict(boolean)} turns these diagnostics into build-failing Spotless lints.
 * A normal no-change result is not an error. Invalid source-version configuration always fails.
 *
 * <p>The step intentionally forces {@code cleanup.format_source_code} to {@code false}, since
 * formatting is handled separately by {@link EclipseJdtFormatterStep}.
 */
public final class EclipseJdtCleanUpStep {

	private static final String NAME = "eclipse jdt clean up";
	private static final String IMPL_FQN = "com.diffplug.spotless.extra.glue.jdt.EclipseJdtCleanUpImpl";
	private static final String IMPL_METHOD = "cleanUp";

	private static final String DEFAULT_VERSION = "4.40";
	private static final int MIN_JVM = 17;
	private static final Jvm.Support<String> JVM_SUPPORT = Jvm.<String> support(NAME).add(MIN_JVM, DEFAULT_VERSION);

	/**
	 * The complete set of bundles to install via P2 and start during OSGi bootstrap.
	 *
	 * <p><strong>Note:</strong> the runtime activation list lives in
	 * {@code com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants#REQUIRED_BUNDLES}. The
	 * two lists are deliberately duplicated because the {@code jdt} source set runs in an isolated
	 * P2 classloader and cannot see classes from the {@code main} source set. A unit test in
	 * {@code EclipseJdtCleanUpStepTest#requiredBundlesAreInSync} verifies they stay aligned.
	 */
	static final List<String> REQUIRED_BUNDLES = List.of(
			"org.eclipse.jdt.core",
			"org.eclipse.jdt.core.manipulation",
			"org.eclipse.ltk.core.refactoring",
			"org.eclipse.core.runtime",
			"org.eclipse.equinox.preferences");

	private EclipseJdtCleanUpStep() {}

	public static String defaultVersion() {
		return JVM_SUPPORT.getRecommendedFormatterVersion();
	}

	public static Builder createBuilder(Provisioner provisioner, P2Provisioner p2Provisioner) {
		return new Builder(NAME, provisioner, p2Provisioner, defaultVersion(),
				EclipseJdtCleanUpStep::apply, ImmutableMap.builder());
	}

	private static FormatterFunc apply(EquoBasedStepBuilder.State state) throws ReflectiveOperationException {
		JVM_SUPPORT.assertFormatterSupported(state.getSemanticVersion());
		Class<?> implClass = state.getJarState().getClassLoader().loadClass(IMPL_FQN);
		Object impl;
		try {
			impl = implClass.getConstructor(Properties.class, Map.class).newInstance(state.getPreferences(), state.getStepProperties());
		} catch (InvocationTargetException e) {
			throw Lint.atUndefinedLine(NAME, e.getCause().getMessage()).shortcut();
		}
		Method method = implClass.getMethod(IMPL_METHOD, String.class, File.class);
		FormatterFunc func = (FormatterFunc.NeedsFile) (input, file) -> {
			try {
				return (String) method.invoke(impl, input, file);
			} catch (InvocationTargetException e) {
				throw Lint.atUndefinedLine(NAME, e.getCause().getMessage()).shortcut();
			}
		};
		return JVM_SUPPORT.suggestLaterVersionOnError(state.getSemanticVersion(), func);
	}

	/** Tolerates {@code "4.39.0"} as an alias for {@code "4.39"} since the P2 repo URL is short-form only. */
	private static String normaliseVersion(String version) {
		return version.endsWith(".0")
				? version.substring(0, version.length() - 2)
				: version;
	}

	/**
	 * Pre-flight check on the user-supplied version string. Catches obvious typos (non-Eclipse
	 * version numbers, words like "latest") at builder time rather than letting them fail much
	 * later inside the P2 provisioner with a 404 on the repository URL.
	 */
	private static void validateVersion(String version) {
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException(
					"Eclipse JDT version must not be blank; supply a version like \""
							+ DEFAULT_VERSION + "\".");
		}
		String normalised = normaliseVersion(version);
		if (!normalised.matches("4\\.[1-9]\\d*")) {
			throw new IllegalArgumentException(
					"Eclipse JDT version '" + version + "' is not a valid Eclipse Platform version (expected 4.x or 4.x.0, e.g. \""
							+ DEFAULT_VERSION + "\").");
		}
	}

	public static final class Builder extends EquoBasedStepBuilder {
		private String javaVersion = "17";
		private boolean strict;

		Builder(
				String formatterName,
				Provisioner mavenProvisioner,
				P2Provisioner p2Provisioner,
				String defaultVersion,
				SerializedFunction<State, FormatterFunc> stateToFormatter,
				ImmutableMap.Builder<String, String> stepProperties) {
			super(formatterName, mavenProvisioner, p2Provisioner, defaultVersion, stateToFormatter, stepProperties);
		}

		/** Java language level of the source, independently of the JVM running Spotless. */
		public void setJavaVersion(String version) {
			if (version == null || !version.matches("1\\.8|[89]|[1-9][0-9]+")) {
				throw new IllegalArgumentException("Java source version must be a major version of 8 or later, e.g. 17 or 21");
			}
			javaVersion = "1.8".equals(version) ? "8" : version;
		}

		/** Fail on ignored actions when true; otherwise emit warnings and continue. */
		public void setStrict(boolean strict) {
			this.strict = strict;
		}

		@Override
		protected ImmutableMap<String, String> stepProperties() {
			return ImmutableMap.<String, String> builder()
					.putAll(super.stepProperties())
					.put("sp_cleanup.java_version", javaVersion)
					.put("sp_cleanup.strict", Boolean.toString(strict))
					.build();
		}

		@Override
		protected P2Model model(String version) {
			P2Model model = new P2Model();
			addPlatformRepo(model, version);
			REQUIRED_BUNDLES.forEach(model.getInstall()::add);
			return model;
		}

		@Override
		public void setVersion(String version) {
			validateVersion(version);
			super.setVersion(normaliseVersion(version));
		}
	}
}
