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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;

/**
 * Centralised constants used across the headless Eclipse JDT clean-up implementation.
 *
 * <p>Used by {@link com.diffplug.spotless.extra.glue.jdt.EclipseJdtCleanUpImpl}
 * and its companion source-model classes inside the isolated JDT classloader.
 */
public final class CleanUpConstants {

	private CleanUpConstants() {}

	// -------------------------------------------------------------------------
	// Java source compliance defaults.
	// -------------------------------------------------------------------------

	/** Default Java source/target/compliance level when javaVersion is omitted. */
	public static final String JAVA_LEVEL = "17";

	/** Compiler options seeded for every cleanup AST. Immutable. */
	public static final Map<String, String> DEFAULT_COMPILER_OPTIONS = Map.of(
			JavaCore.COMPILER_SOURCE, JAVA_LEVEL,
			JavaCore.COMPILER_COMPLIANCE, JAVA_LEVEL,
			JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JAVA_LEVEL);

	/** Validate against the selected JDT's capabilities rather than the host JVM's version. */
	public static Map<String, String> compilerOptions(String version) {
		String jdtVersion = "8".equals(version) ? "1.8" : version;
		if (!JavaCore.isJavaSourceVersionSupportedByCompiler(jdtVersion)) {
			throw new IllegalArgumentException("Java source version " + version
					+ " is not supported by the selected Eclipse JDT; supported versions: "
					+ JavaCore.getAllJavaSourceVersionsSupportedByCompiler());
		}
		return Map.of(
				JavaCore.COMPILER_SOURCE, jdtVersion,
				JavaCore.COMPILER_COMPLIANCE, jdtVersion,
				JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, jdtVersion);
	}

	// -------------------------------------------------------------------------
	// Synthetic identity values used by the stub Eclipse model — never user-visible.
	// -------------------------------------------------------------------------

	/** File name advertised on the synthetic compilation unit when the source has no public type. */
	public static final String DEFAULT_UNIT_NAME = "CleanUpUnit.java";

	/** Project name on the synthetic IProject stub. */
	public static final String STUB_PROJECT_NAME = "SpotlessCleanUpProject";

	/** Temp dir prefix used when the Solstice runtime needs an instance area on disk. */
	public static final String INSTANCE_AREA_PREFIX = "spotless-jdt-cleanup";

	// -------------------------------------------------------------------------
	// OSGi bundle symbolic names referenced by the Solstice bootstrap.
	//
	// The same list is duplicated in EclipseJdtCleanUpStep#REQUIRED_BUNDLES (main
	// source set) because the jdt source set runs inside an isolated P2 classloader
	// and cannot import classes from main. EclipseJdtCleanUpStepTest has a guard
	// test that fails the build if the two lists drift.
	// -------------------------------------------------------------------------

	public static final String BUNDLE_FELIX_SCR = "org.apache.felix.scr";
	public static final String BUNDLE_EQUINOX_PREFERENCES = "org.eclipse.equinox.preferences";
	public static final String BUNDLE_CORE_RUNTIME = "org.eclipse.core.runtime";
	public static final String BUNDLE_JDT_CORE = "org.eclipse.jdt.core";
	public static final String BUNDLE_JDT_CORE_MANIPULATION = "org.eclipse.jdt.core.manipulation";
	public static final String BUNDLE_LTK_CORE_REFACTORING = "org.eclipse.ltk.core.refactoring";

	/** The complete set of bundles started during {@code SolsticeBootstrap.startSolstice}. */
	public static final List<String> REQUIRED_BUNDLES = List.of(
			BUNDLE_JDT_CORE,
			BUNDLE_JDT_CORE_MANIPULATION,
			BUNDLE_LTK_CORE_REFACTORING,
			BUNDLE_CORE_RUNTIME,
			BUNDLE_EQUINOX_PREFERENCES);

	// JDT-UI preference defaults seeded into the InstanceScope so that
	// CodeStyleConfiguration.configureImportRewrite() does not NPE when looking
	// up missing keys (the org.eclipse.jdt.ui bundle is intentionally absent).
	// -------------------------------------------------------------------------

	public static final String PREF_NODE_JDT_MANIPULATION = "org.eclipse.jdt.core.manipulation";
	public static final String PREF_KEY_IMPORT_ORDER = "org.eclipse.jdt.ui.importorder";
	public static final String PREF_KEY_ONDEMAND_THRESHOLD = "org.eclipse.jdt.ui.ondemandthreshold";
	public static final String PREF_KEY_STATIC_ONDEMAND_THRESHOLD = "org.eclipse.jdt.ui.staticondemandthreshold";

	/** Default import order — matches Eclipse IDE shipped defaults. */
	public static final String DEFAULT_IMPORT_ORDER = "java;javax;org;com";

	/** Default on-demand threshold — number of imports before "*" is used. */
	public static final String DEFAULT_ONDEMAND_THRESHOLD = "99";

	// -------------------------------------------------------------------------
	// Profile XML keys.
	// -------------------------------------------------------------------------

	/**
	 * Profile key that triggers Eclipse's internal formatter. Forced to {@code false} because
	 * Spotless manages formatting via a dedicated step.
	 */
	public static final String FORMAT_SOURCE_CODE_KEY = "cleanup.format_source_code";
}
