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
import java.util.Properties;

import com.diffplug.common.collect.ImmutableMap;
import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.Jvm;
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
 * {@code GrEclipseFormatterStepImpl}) so most Eclipse JDT clean up actions &mdash; including
 * those that depend on the {@code ImportRewrite} pipeline (remove unused imports, lambda
 * conversion, ...) &mdash; work the same way as inside Eclipse IDE itself.
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
 * </ul>
 *
 * <h2>Known limitations</h2>
 *
 * <p>A few cleanups generate a fix successfully but fail when JDT tries to attach that fix to a
 * {@code CompilationUnitChange}. The failure happens because
 * {@code ImportRewriteAnalyzer} walks the {@code PackageFragmentRoot} hierarchy and calls
 * {@code isArchive() / hashCode()} on it &mdash; we cannot stub that out without a real Eclipse
 * workspace. The affected cleanups are silently skipped (logged at FINE) and the source is left
 * unchanged for them:
 * <ul>
 *   <li>{@code cleanup.instanceof} (pattern matching for instanceof)</li>
 *   <li>{@code cleanup.convert_to_switch_expressions}</li>
 *   <li>{@code cleanup.convert_to_enhanced_for_loop}</li>
 * </ul>
 *
 * <p>Achieving 100% coverage would require either spinning up a real Eclipse workspace on disk
 * (much heavier startup cost) or running JDT as a subprocess. Track this as a future improvement.
 *
 * <p>Cleanups that throw an unexpected exception are logged at {@link java.util.logging.Level#FINE}
 * and the affected source is left unchanged; users can opt in to detailed diagnostics by enabling
 * JUL logging.
 *
 * <p>The step intentionally forces {@code cleanup.format_source_code} to {@code false}, since
 * formatting is handled separately by {@link EclipseJdtFormatterStep}.
 */
public final class EclipseJdtCleanUpStep {
	// prevent direct instantiation
	private EclipseJdtCleanUpStep() {}

	private static final String NAME = "eclipse jdt clean up";
	private static final String DEFAULT_VERSION = "4.39";
	private static final int MIN_JVM = 17;
	private static final Jvm.Support<String> JVM_SUPPORT = Jvm.<String> support(NAME).add(MIN_JVM, DEFAULT_VERSION);

	public static String defaultVersion() {
		return JVM_SUPPORT.getRecommendedFormatterVersion();
	}

	public static EclipseJdtCleanUpStep.Builder createBuilder(Provisioner provisioner, P2Provisioner p2Provisioner) {
		return new EclipseJdtCleanUpStep.Builder(NAME, provisioner, p2Provisioner, defaultVersion(), EclipseJdtCleanUpStep::apply, ImmutableMap.builder());
	}

	private static FormatterFunc apply(EquoBasedStepBuilder.State state) throws Exception {
		JVM_SUPPORT.assertFormatterSupported(state.getSemanticVersion());
		Class<?> cleanUpClazz = state.getJarState().getClassLoader().loadClass("com.diffplug.spotless.extra.glue.jdt.EclipseJdtCleanUpImpl");
		var cleanUp = cleanUpClazz.getConstructor(Properties.class).newInstance(state.getPreferences());
		var method = cleanUpClazz.getMethod("cleanUp", String.class, File.class);
		FormatterFunc formatterFunc = (FormatterFunc.NeedsFile) (input, file) -> (String) method.invoke(cleanUp, input, file);
		return JVM_SUPPORT.suggestLaterVersionOnError(state.getSemanticVersion(), formatterFunc);
	}

	public static class Builder extends EquoBasedStepBuilder {
		Builder(
				String formatterName,
				Provisioner mavenProvisioner,
				P2Provisioner p2Provisioner,
				String defaultVersion,
				SerializedFunction<State, FormatterFunc> stateToFormatter,
				ImmutableMap.Builder<String, String> stepProperties) {
			super(formatterName, mavenProvisioner, p2Provisioner, defaultVersion, stateToFormatter, stepProperties);
		}

		@Override
		protected P2Model model(String version) {
			var model = new P2Model();
			addPlatformRepo(model, version);
			model.getInstall().add("org.eclipse.jdt.core");
			model.getInstall().add("org.eclipse.jdt.core.manipulation");
			model.getInstall().add("org.eclipse.ltk.core.refactoring");
			// Required for the Solstice OSGi bootstrap that EclipseJdtCleanUpImpl performs:
			// without an initialised IPreferencesService and JavaModelManager, cleanups that
			// touch ImportRewrite (lambda conversion, remove unused imports, ...) silently
			// fail.
			model.getInstall().add("org.eclipse.core.runtime");
			model.getInstall().add("org.eclipse.equinox.preferences");
			return model;
		}

		@Override
		public void setVersion(String version) {
			// Mirror EclipseJdtFormatterStep: tolerate "4.39.0" as an alias for "4.39"
			// (the P2 repo URL only knows the short form).
			if (version.endsWith(".0")) {
				version = version.substring(0, version.length() - 2);
			}
			super.setVersion(version);
		}
	}
}
