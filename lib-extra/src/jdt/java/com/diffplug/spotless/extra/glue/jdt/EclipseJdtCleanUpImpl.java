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

	static {
		SolsticeBootstrap.ensureBootstrapped();
	}

	private final CleanUpOptions cleanUpOptions;
	private final List<ICleanUp> cleanUps;

	public EclipseJdtCleanUpImpl(Properties settings) {
		this.cleanUpOptions = buildOptions(settings);
		this.cleanUps = CleanUpRegistry.buildAll();
	}

	/** Materialises the profile properties as an immutable {@link CleanUpOptions} bag. */
	private static CleanUpOptions buildOptions(Properties settings) {
		Map<String, String> options = new HashMap<>(settings.size() + 1);
		for (Map.Entry<Object, Object> entry : settings.entrySet()) {
			options.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		// Always disable Eclipse's internal formatter — Spotless owns formatting.
		options.put(CleanUpConstants.FORMAT_SOURCE_CODE_KEY, CleanUpOptions.FALSE);
		return new MapCleanUpOptions(options);
	}

	/**
	 * Applies every enabled clean up action to the given Java source string.
	 *
	 * @param raw  the raw Java source (LF line endings)
	 * @param file unused; present for API symmetry with the formatter step
	 * @return the cleaned-up source, or the original if no clean up produced changes
	 */
	@SuppressWarnings("unused")
	public String cleanUp(String raw, File file) {
		if (cleanUps.isEmpty()) {
			return raw;
		}
		String current = raw;
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
		} catch (Exception e) {
			LOGGER.log(Level.FINE, e,
					() -> "Cleanup " + cleanUp.getClass().getSimpleName() + " setOptions failed; skipping");
			return false;
		}
	}
}
