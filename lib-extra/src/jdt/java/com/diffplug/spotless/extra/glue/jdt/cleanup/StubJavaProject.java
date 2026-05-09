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

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.JavaProject;

/**
 * Headless stand-in for {@link JavaProject}. Created once as a singleton and shared across every
 * cleanup invocation.
 *
 * <p>The Eclipse JDT internals reach for the {@code project} field directly (not via
 * {@link #getProject()}), so we set it via reflection in the static factory; this lets
 * {@link org.eclipse.jdt.internal.core.JavaElement#hashCode()} (which is final and dereferences
 * {@code this.project}) succeed for our stub.
 */
final class StubJavaProject extends JavaProject {

	/** Stub IProject is initialised first because INSTANCE creation depends on it. */
	private static final IProject STUB_PROJECT = StubProxies.createStubProject();
	static final StubJavaProject INSTANCE = createInstance("project");

	/**
	 * Test-only seam: swappable function used by {@link #getOption(String, boolean)} when the
	 * caller asks for an inherited value. Defaults to {@link JavaCore#getOption(String)}; tests
	 * can replace it with a deterministic lookup so PIT mutants on the inheritance ternary can
	 * be killed without depending on a fully initialised OSGi runtime.
	 */
	@SuppressWarnings("CanBeFinal")
	static volatile UnaryOperator<String> JAVA_CORE_LOOKUP = JavaCore::getOption;

	private StubJavaProject() {
		super(null, null);
	}

	/**
	 * Builds a {@link StubJavaProject} and wires its {@code projectFieldName} field on
	 * {@link JavaProject} via reflection. Package-private and parameterised so unit tests can
	 * pass an unknown field name to verify the failure path; production code always calls it
	 * with {@code "project"}.
	 */
	static StubJavaProject createInstance(String projectFieldName) {
		StubJavaProject inst = new StubJavaProject();
		try {
			StubProxies.setField(inst, JavaProject.class, projectFieldName, STUB_PROJECT);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(
					"Eclipse JDT API changed: JavaProject#" + projectFieldName + " is missing; please update spotless",
					e);
		}
		return inst;
	}

	/**
	 * Honours {@code inheritJavaCoreOptions} per the {@link JavaProject#getOptions(boolean)}
	 * contract: when true, merge Spotless-pinned options on top of {@link JavaCore#getOptions()}
	 * so callers inspecting unrelated keys (e.g. {@code COMPILER_PB_RAW_TYPE_REFERENCE}) get the
	 * correct workbench default.
	 */
	@Override
	public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
		if (!inheritJavaCoreOptions) {
			return CleanUpConstants.DEFAULT_COMPILER_OPTIONS;
		}
		Map<String, String> merged = new HashMap<>(JavaCore.getOptions());
		merged.putAll(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
		return merged;
	}

	/**
	 * Always answer Java 17 source level so cleanups gated on
	 * {@code JavaModelUtil.is16OrHigher(project)} (pattern matching, switch expressions, ...) are
	 * eligible. Without this override the call falls back to the workbench-wide default (often
	 * "1.8") and modern transformations silently no-op.
	 */
	@Override
	public String getOption(String optionName, boolean inheritJavaCoreOptions) {
		if (JavaCore.COMPILER_SOURCE.equals(optionName)
				|| JavaCore.COMPILER_COMPLIANCE.equals(optionName)
				|| JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM.equals(optionName)) {
			return CleanUpConstants.JAVA_LEVEL;
		}
		if (inheritJavaCoreOptions) {
			return JAVA_CORE_LOOKUP.apply(optionName);
		}
		return null;
	}

	/**
	 * The default impl traverses {@code JavaModelManager.getInfo()} which dereferences a null
	 * cache field outside of a real Eclipse runtime. Returning null bypasses module resolution;
	 * cleanups never need module info for our use case.
	 */
	@Override
	public IModuleDescription getModuleDescription() {
		return null;
	}

	@Override
	public IProject getProject() {
		return STUB_PROJECT;
	}

	/**
	 * The default impl dereferences the protected {@code project} field via
	 * {@code hasJavaNature(project)}; the field is null even though {@link #getProject()} returns
	 * a stub, so cleanups like {@code PatternMatchingForInstanceofCleanUpCore} NPE while looking
	 * up Java source compliance. Returning null lets the caller fall back to
	 * {@link JavaCore#getOption(String)} workbench defaults.
	 */
	@Override
	public IEclipsePreferences getEclipsePreferences() {
		return null;
	}
}
