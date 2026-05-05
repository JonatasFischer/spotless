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
 * <p>The step intentionally ignores {@code cleanup.format_source_code} from the profile,
 * since formatting is handled separately by the Eclipse formatter step.
 */
public final class EclipseJdtCleanUpStep {
	// prevent direct instantiation
	private EclipseJdtCleanUpStep() {}

	private static final String NAME = "eclipse jdt clean up";
	private static final Jvm.Support<String> JVM_SUPPORT = Jvm.<String> support(NAME).add(17, "4.39");

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
			return model;
		}
	}
}
