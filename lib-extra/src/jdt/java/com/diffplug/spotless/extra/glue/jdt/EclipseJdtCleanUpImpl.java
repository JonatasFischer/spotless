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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;

import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpApplier;
import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants;
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

	private static final Logger LOGGER = Logger.getLogger(EclipseJdtCleanUpImpl.class.getName());

	private final CleanUpOptions cleanUpOptions;
	private final List<ICleanUp> cleanUps;

	public EclipseJdtCleanUpImpl(Properties settings) {
		this(settings, CleanUpRegistry.buildAll());
	}

	/**
	 * Internal constructor used by the public ctor and by unit tests. Lets tests inject a
	 * synthetic list of {@link ICleanUp} instances (e.g. ones whose {@code setOptions} throws) to
	 * exercise the {@link #configure} branch coverage without depending on the real catalogue.
	 */
	EclipseJdtCleanUpImpl(Properties settings, List<ICleanUp> cleanUps) {
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(cleanUps, "cleanUps");
		this.cleanUpOptions = buildOptions(settings);
		this.hasAnyCleanUpEnabled = anyCleanUpEnabled(settings);
		this.cleanUps = cleanUps;
	}

	private final boolean hasAnyCleanUpEnabled;

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
			if (CleanUpOptions.TRUE.equals(settings.getProperty(key))) {
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
	 * <p><strong>Threading:</strong> not thread-safe. Each {@link EclipseJdtCleanUpImpl} instance
	 * owns mutable {@link ICleanUp} instances whose state ({@code setOptions}, fix cache) is
	 * invalidated across calls. Callers must serialise invocations on the same instance, or
	 * construct a fresh instance per worker.
	 *
	 * @param raw  the raw Java source; line endings are normalised to LF before parsing
	 * @param file reserved for API symmetry with the formatter step — do not remove (the
	 *             reflective binding in {@code EclipseJdtCleanUpStep#apply} requires this exact
	 *             signature). May be {@code null}.
	 * @return the cleaned-up source, or the original if no clean up produced changes
	 */
	@SuppressWarnings("unused")
	public String cleanUp(String raw, File file) {
		Objects.requireNonNull(raw, "raw");
		if (!hasAnyCleanUpEnabled || cleanUps.isEmpty()) {
			return raw;
		}
		// Lazy bootstrap: only fire OSGi runtime when we actually have cleanups to apply. Keeping
		// this out of the static initialiser lets unit tests verify the short-circuit paths
		// without spinning up Solstice.
		SolsticeBootstrap.ensureBootstrapped();
		// Normalise to LF so that JDT's parser-emitted offsets line up with our in-memory
		// Document. The replace calls are no-ops on input without CR, so we skip the redundant
		// short-circuit guard that PIT flagged as an equivalent mutant.
		String current = raw.replace("\r\n", "\n").replace("\r", "\n");
		for (ICleanUp cleanUp : cleanUps) {
			if (!configure(cleanUp)) {
				continue;
			}
			current = CleanUpApplier.apply(cleanUp, current);
		}
		return current;
	}

	private boolean configure(ICleanUp cleanUp) {
		try {
			cleanUp.setOptions(cleanUpOptions);
			return true;
		} catch (RuntimeException e) {
			LOGGER.log(Level.FINE, e,
					() -> "Cleanup " + cleanUp.getClass().getSimpleName() + " setOptions failed; skipping");
			return false;
		}
	}
}
