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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;

import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpApplier;
import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants;
import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpDiagnostics;
import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpRegistry;
import com.diffplug.spotless.extra.glue.jdt.cleanup.SolsticeBootstrap;

/**
 * Applies Eclipse JDT Clean Up actions to Java source code.
 *
 * <p>This class is loaded reflectively into the isolated P2 classloader, following the same
 * pattern as {@code EclipseJdtFormatterStepImpl}. It is intentionally a thin orchestrator: the
 * heavy lifting lives in the {@code com.diffplug.spotless.extra.glue.jdt.cleanup} sub-package.
 *
 * <p>The clean up settings are read from an Eclipse clean up profile XML file (exported via
 * <em>Preferences &rarr; Java &rarr; Code Style &rarr; Clean Up &rarr; Export</em>), which is
 * pre-parsed into a {@link Properties} object by {@code EquoBasedStepBuilder.State}.
 *
 * <p>{@code cleanup.format_source_code} is intentionally forced to {@code false} &mdash;
 * formatting is handled by the Eclipse formatter step separately.
 */
public class EclipseJdtCleanUpImpl {

	private final CleanUpOptions cleanUpOptions;
	private final List<ICleanUp> cleanUps;
	private final Runnable bootstrap;
	private final boolean hasAnyCleanUpEnabled;
	private final Map<String, String> compilerOptions;
	private final CleanUpDiagnostics diagnostics;
	private final List<String> profileProblems;
	private boolean profileReported;

	public EclipseJdtCleanUpImpl(Properties settings) {
		this(settings, Map.of());
	}

	public EclipseJdtCleanUpImpl(Properties settings, Map<String, String> stepProperties) {
		this(settings, stepProperties, CleanUpRegistry.buildAll(), SolsticeBootstrap::ensureBootstrapped);
	}

	EclipseJdtCleanUpImpl(Properties settings, List<ICleanUp> cleanUps, Runnable bootstrap) {
		this(settings, Map.of(), cleanUps, bootstrap);
	}

	EclipseJdtCleanUpImpl(Properties settings, Map<String, String> stepProperties, List<ICleanUp> cleanUps, Runnable bootstrap) {
		Objects.requireNonNull(settings, "settings");
		String javaVersion = stepProperties.getOrDefault("sp_cleanup.java_version", "17");
		this.compilerOptions = CleanUpConstants.compilerOptions(javaVersion);
		this.diagnostics = new CleanUpDiagnostics(Boolean.parseBoolean(stepProperties.getOrDefault("sp_cleanup.strict", "false")));
		this.profileProblems = CleanUpRegistry.profileProblems(settings, javaVersion);
		this.cleanUpOptions = buildOptions(settings);
		this.hasAnyCleanUpEnabled = anyCleanUpEnabled(settings);
		this.cleanUps = List.copyOf(Objects.requireNonNull(cleanUps, "cleanUps"));
		this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
	}

	/**
	 * Returns {@code true} if at least one {@code cleanup.* = true} entry exists in the profile
	 * (other than the always-disabled {@code cleanup.format_source_code}). Used to short-circuit
	 * the AST parsing pipeline when the profile is empty/disabled.
	 *
	 * <p>Package-private so unit tests can drive every branch directly with assert-on-result
	 * semantics rather than relying on downstream side effects.
	 */
	static boolean anyCleanUpEnabled(Properties settings) {
		for (String key : settings.stringPropertyNames()) {
			if (CleanUpConstants.FORMAT_SOURCE_CODE_KEY.equals(key)) {
				continue;
			}
			if (key.startsWith("cleanup.") && CleanUpOptions.TRUE.equals(settings.getProperty(key))) {
				return true;
			}
		}
		return false;
	}

	/** Materialises the profile properties as an immutable {@link CleanUpOptions} bag. */
	static CleanUpOptions buildOptions(Properties settings) {
		// Properties.stringPropertyNames() returns only keys whose value is a non-null String;
		// the legacy Hashtable.put(Object,Object) contract is therefore moot for our purposes.
		Map<String, String> options = new HashMap<>();
		for (String key : settings.stringPropertyNames()) {
			options.put(key, settings.getProperty(key));
		}
		// Always disable Eclipse's internal formatter — Spotless owns formatting.
		options.put(CleanUpConstants.FORMAT_SOURCE_CODE_KEY, CleanUpOptions.FALSE);
		return new MapCleanUpOptions(options);
	}

	/**
	 * Applies every enabled clean up action to the given Java source string.
	 *
	 * <p>Calls are serialized because Eclipse cleanup instances hold mutable per-file state.
	 *
	 * @param raw  the raw Java source; line endings are normalised to LF before parsing
	 * @param file used only to identify the source in diagnostics; its contents are never read
	 * @return the cleaned-up source, or the original if no clean up produced changes
	 */
	@SuppressWarnings("unused")
	public synchronized String cleanUp(String raw, File file) {
		Objects.requireNonNull(raw, "raw");
		if (!profileReported) {
			if (!profileProblems.isEmpty()) {
				diagnostics.skipped("profile options", file, String.join("; ", profileProblems), null);
			}
			profileReported = true;
		}
		if (!hasAnyCleanUpEnabled || cleanUps.isEmpty()) {
			return raw;
		}
		bootstrap.run();
		// Keep JDT edit offsets aligned with the in-memory document.
		String current = raw.replace("\r\n", "\n").replace("\r", "\n");
		for (ICleanUp cleanUp : cleanUps) {
			if (!configure(cleanUp, file)) {
				continue;
			}
			current = CleanUpApplier.apply(cleanUp, current, compilerOptions, diagnostics, file);
		}
		return current;
	}

	private boolean configure(ICleanUp cleanUp, File file) {
		try {
			cleanUp.setOptions(cleanUpOptions);
			return true;
		} catch (RuntimeException e) {
			diagnostics.skipped(cleanUp.getClass().getSimpleName(), file, "setOptions failed: " + e.getMessage(), e);
			return false;
		}
	}
}
