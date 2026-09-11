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

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.JavaProject;

import com.diffplug.spotless.extra.glue.jdt.SuppressFBWarnings;

/**
 * Headless Java project with immutable, per-instance compiler options. Different source levels
 * can coexist in the same classloader without mutating the Eclipse-wide defaults.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "JDT project identity is its IProject resource; each instance owns a distinct resource")
final class StubJavaProject extends JavaProject {

	static final StubJavaProject INSTANCE = new StubJavaProject(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	private final Map<String, String> compilerOptions;

	StubJavaProject(Map<String, String> compilerOptions) {
		super(StubProxies.createStubProject(), null);
		this.compilerOptions = Map.copyOf(compilerOptions);
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
			return compilerOptions;
		}
		Map<String, String> merged = new HashMap<>(JavaCore.getOptions());
		merged.putAll(compilerOptions);
		return merged;
	}

	/** Honor the configured source level in JDT's language-version checks. */
	@Override
	public String getOption(String optionName, boolean inheritJavaCoreOptions) {
		String option = compilerOptions.get(optionName);
		if (option != null) {
			return option;
		}
		if (inheritJavaCoreOptions) {
			return JavaCore.getOptions().get(optionName);
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

	/**
	 * Preferences are supplied by getOptions/getOption. There is no workspace-backed preferences
	 * node for this in-memory project.
	 */
	@Override
	public IEclipsePreferences getEclipsePreferences() {
		return null;
	}
}
