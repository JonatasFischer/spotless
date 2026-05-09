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

import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.BUNDLE_FELIX_SCR;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.DEFAULT_IMPORT_ORDER;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.DEFAULT_ONDEMAND_THRESHOLD;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.INSTANCE_AREA_PREFIX;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.PREF_KEY_IMPORT_ORDER;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.PREF_KEY_ONDEMAND_THRESHOLD;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.PREF_KEY_STATIC_ONDEMAND_THRESHOLD;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.PREF_NODE_JDT_MANIPULATION;
import static com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpConstants.REQUIRED_BUNDLES;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.osgi.internal.location.EquinoxLocations;
import org.osgi.framework.Constants;
import org.osgi.service.prefs.BackingStoreException;

import dev.equo.solstice.NestedJars;
import dev.equo.solstice.ShimIdeBootstrapServices;
import dev.equo.solstice.Solstice;
import dev.equo.solstice.p2.CacheLocations;

/**
 * Bootstraps the Equo Solstice OSGi runtime so that {@link Platform} services
 * ({@code IPreferencesService}, {@code JavaModelManager}, ...) are registered before any
 * cleanup runs.
 *
 * <p>This class mirrors the proven pattern from
 * {@code com.diffplug.spotless.extra.glue.groovy.GrEclipseFormatterStepImpl}'s static block.
 * It is a one-shot operation guarded by {@link #BOOTSTRAPPED}; subsequent calls to
 * {@link #ensureBootstrapped()} are no-ops.
 */
public final class SolsticeBootstrap {

	private static final Logger LOGGER = Logger.getLogger(SolsticeBootstrap.class.getName());
	private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

	private SolsticeBootstrap() {}

	/**
	 * Boots the Solstice OSGi runtime if it has not been booted yet. Safe to call multiple times.
	 *
	 * @throws IllegalStateException if the bootstrap fails on the first invocation
	 */
	public static synchronized void ensureBootstrapped() {
		if (BOOTSTRAPPED.get()) {
			return;
		}
		try {
			startSolstice();
			initialiseJavaManipulationPreferenceNodeId();
			seedJdtUiImportPreferences();
			BOOTSTRAPPED.set(true);
		} catch (IOException e) {
			throw new IllegalStateException(
					"Failed to bootstrap Equo Solstice runtime for Eclipse JDT clean up", e);
		}
	}

	private static void startSolstice() throws IOException {
		NestedJars.setToWarnOnly();
		NestedJars.onClassPath().confirmAllNestedJarsArePresentOnClasspath(CacheLocations.p2nestedJars());

		Solstice solstice = Solstice.findBundlesOnClasspath();
		solstice.warnAndModifyManifestsToFix();

		Path instanceArea = Files.createTempDirectory(INSTANCE_AREA_PREFIX);
		registerInstanceAreaCleanup(instanceArea);
		Map<String, String> props = Map.of(
				"osgi.nl", "en_US",
				Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT,
				EquinoxLocations.PROP_INSTANCE_AREA, instanceArea.toAbsolutePath().toString());

		solstice.openShim(props);
		ShimIdeBootstrapServices.apply(props, solstice.getContext());
		// Declarative Services so DS-component bundles activate properly.
		solstice.start(BUNDLE_FELIX_SCR);
		// Activating every non-lazy bundle lets the equinox preferences activator register
		// IPreferencesService and the JDT activators register JavaModelManager etc.
		solstice.startAllWithLazy(false);
		// Belt and braces — explicitly request the bundles we depend on in case any was lazy.
		// Drift between this list and EclipseJdtCleanUpStep#REQUIRED_BUNDLES is guarded by
		// EclipseJdtCleanUpStepTest#requiredBundlesAreInSync.
		for (String bundle : REQUIRED_BUNDLES) {
			solstice.start(bundle);
		}
	}

	/**
	 * Registers a JVM shutdown hook that recursively deletes {@code instanceArea}. Long-lived
	 * Gradle daemons would otherwise accumulate {@code spotless-jdt-cleanup*} directories in the
	 * temp folder until the OS reclaims them.
	 */
	private static void registerInstanceAreaCleanup(Path instanceArea) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				if (!Files.exists(instanceArea)) {
					return;
				}
				Files.walkFileTree(instanceArea, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
						Files.deleteIfExists(f);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
						Files.deleteIfExists(d);
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException ignored) {
				// shutdown-hook best-effort; nothing actionable on failure
			}
		}, "spotless-jdt-cleanup-tempdir-cleanup"));
	}

	/**
	 * The {@code JavaManipulationPlugin#start} activator should have set
	 * {@code JavaManipulation.fgPreferenceNodeId} during {@link Solstice#startAllWithLazy(boolean)},
	 * but if for some reason it did not, force it via reflection (the field is package-private).
	 * Without it, {@code ProjectScope.getNode(null)} throws {@link IllegalArgumentException}.
	 */
	private static void initialiseJavaManipulationPreferenceNodeId() {
		try {
			Class<?> jm = Class.forName("org.eclipse.jdt.core.manipulation.JavaManipulation");
			Field nodeIdField = jm.getDeclaredField("fgPreferenceNodeId");
			nodeIdField.setAccessible(true);
			if (nodeIdField.get(null) == null) {
				nodeIdField.set(null, PREF_NODE_JDT_MANIPULATION);
			}
		} catch (ReflectiveOperationException e) {
			LOGGER.log(Level.FINE, e,
					() -> "Could not verify JavaManipulation.fgPreferenceNodeId after Solstice bootstrap");
		}
	}

	/**
	 * Cleanups that go through {@code CodeStyleConfiguration.configureImportRewrite()} read JDT-UI
	 * preferences (import order, on-demand threshold, ...). The {@code org.eclipse.jdt.ui} bundle
	 * is intentionally absent from our classpath (we use the headless
	 * {@code org.eclipse.jdt.core.manipulation} only), so the InstanceScope node has no defaults
	 * for those keys. Seed the same defaults Eclipse IDE ships with so the import rewrite path is
	 * non-null.
	 */
	private static void seedJdtUiImportPreferences() {
		try {
			IEclipsePreferences node = InstanceScope.INSTANCE.getNode(PREF_NODE_JDT_MANIPULATION);
			node.put(PREF_KEY_IMPORT_ORDER, DEFAULT_IMPORT_ORDER);
			node.put(PREF_KEY_ONDEMAND_THRESHOLD, DEFAULT_ONDEMAND_THRESHOLD);
			node.put(PREF_KEY_STATIC_ONDEMAND_THRESHOLD, DEFAULT_ONDEMAND_THRESHOLD);
			try {
				node.flush();
			} catch (BackingStoreException ignored) {
				// Without an on-disk preferences store the in-memory state is enough; flush is a
				// best-effort.
			}
		} catch (RuntimeException e) {
			LOGGER.log(Level.FINE, e,
					() -> "Could not seed default JDT-UI import preferences; cleanups using import rewrite may fail");
		}
	}
}
