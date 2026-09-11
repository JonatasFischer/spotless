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
package com.diffplug.spotless.maven.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.codehaus.plexus.resource.ResourceManager;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;
import com.diffplug.spotless.extra.P2Mirror;
import com.diffplug.spotless.extra.java.EclipseJdtCleanUpStep;
import com.diffplug.spotless.maven.FileLocator;
import com.diffplug.spotless.maven.FormatterStepConfig;

/**
 * Unit tests for {@link EclipseCleanUp}. Drives every {@code @Parameter}-bound field plus the
 * {@link EclipseCleanUp#init} hook through a hand-constructed {@link FormatterStepConfig}, then
 * inspects the underlying {@link EquoBasedStepBuilder} via reflection so each setter on the
 * builder is observably wired up.
 *
 * <p>Building the {@link FormatterStep} (via {@code newFormatterStep}) triggers real P2
 * provisioning, so we side-step it: tests construct the mojo, set fields reflectively, then
 * verify {@code newFormatterStep} either succeeds (P2 cache primed for the default version) or
 * mutates the builder before {@code build()} is called. Field assertions use a builder probe
 * obtained by reflection on the builder created inside {@code newFormatterStep}.
 */
class EclipseCleanUpUnitTest {

	@TempDir
	File workDir;

	private FormatterStepConfig stepConfig() throws Exception {
		// FileLocator only consults its ResourceManager when the path is NOT an existing local
		// file. Our tests always pass a real File, so the (mocked) ResourceManager is unused.
		FileLocator fileLocator = new FileLocator(
				mock(ResourceManager.class),
				workDir,
				new File(workDir, "build"));
		return new FormatterStepConfig(
				StandardCharsets.UTF_8,
				"//",
				Optional.empty(),
				TestProvisioner.mavenCentral(),
				/* p2Provisioner */ (modelWrapper, mavenProv, cacheDir) -> List.of(),
				fileLocator,
				Optional.empty(),
				Optional.empty());
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field f = EclipseCleanUp.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object getField(Object target, String name) throws Exception {
		Field f = EclipseCleanUp.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	// =========================================================================
	// init(): captures the local repository directory.
	// =========================================================================

	@Test
	void initCapturesLocalRepositoryBasedir() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		File localRepo = new File(workDir, "fake-m2");
		assertThat(localRepo.mkdirs()).isTrue();
		RepositorySystemSession session = mock(RepositorySystemSession.class);
		when(session.getLocalRepository()).thenReturn(new LocalRepository(localRepo));
		mojo.init(session);
		assertThat(getField(mojo, "cacheDirectory")).isEqualTo(localRepo);
	}

	// =========================================================================
	// configureBuilder: verifies every @Parameter field threads through to the
	// underlying EquoBasedStepBuilder. Inspecting the builder's private fields
	// kills PIT mutants on `builder.setX(...)` and on the surrounding
	// `if (...)` guards without needing a real build() call.
	// =========================================================================

	private static Object reflectBuilderField(EquoBasedStepBuilder builder, String fieldName) throws Exception {
		Field f = EquoBasedStepBuilder.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		return f.get(builder);
	}

	@Test
	void newFormatterStepProducesNonNullStep() throws Exception {
		// Smoke: the public surface still works end-to-end. build() is sourced from the
		// cache primed by existing integration tests for the default version.
		EclipseCleanUp mojo = new EclipseCleanUp();
		FormatterStep step = mojo.newFormatterStep(stepConfig());
		assertThat(step).isNotNull();
		assertThat(step.getName()).isEqualTo("eclipse jdt clean up");
	}

	@Test
	void configureBuilderUsesDefaultVersionWhenFieldIsNull() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		assertThat(reflectBuilderField(builder, "formatterVersion"))
				.isEqualTo(EclipseJdtCleanUpStep.defaultVersion());
	}

	@Test
	void configuresJavaSourceLevelAndStrictnessIndependentlyOfJdtVersion() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		setField(mojo, "javaVersion", "21");
		setField(mojo, "strict", true);
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		var method = EquoBasedStepBuilder.class.getDeclaredMethod("stepProperties");
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, String> properties = (Map<String, String>) method.invoke(builder);
		assertThat(properties).containsEntry("sp_cleanup.java_version", "21").containsEntry("sp_cleanup.strict", "true");
		assertThat(reflectBuilderField(builder, "formatterVersion")).isEqualTo(EclipseJdtCleanUpStep.defaultVersion());
	}

	@Test
	void configureBuilderHonoursExplicitVersionField() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		// Use a non-default version so the mutant 'version != null ? version : default'
		// (EQUAL_ELSE) produces an observably different result than the original ternary.
		setField(mojo, "version", "4.39");
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		assertThat(reflectBuilderField(builder, "formatterVersion")).isEqualTo("4.39");
	}

	@Test
	void configureBuilderWithFileSetsPreferences() throws Exception {
		File profile = new File(workDir, "cleanup.xml");
		Files.writeString(profile.toPath(), "<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>");
		EclipseCleanUp mojo = new EclipseCleanUp();
		setField(mojo, "file", profile.getAbsolutePath());
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		Iterable<File> files = (Iterable<File>) reflectBuilderField(builder, "settingsFiles");
		assertThat(files).containsExactly(profile);
	}

	@Test
	void configureBuilderWithoutFileLeavesPreferencesEmpty() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		Iterable<File> files = (Iterable<File>) reflectBuilderField(builder, "settingsFiles");
		assertThat(files).isEmpty();
	}

	@Test
	void configureBuilderWithSettingsSetsPropertyPreferences() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put("cleanup.make_local_variable_final", "true");
		settings.put("cleanup.format_source_code", "false");
		setField(mojo, "settings", settings);
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		List<String> propPrefs = (List<String>) reflectBuilderField(builder, "settingProperties");
		// Keys are concatenated into a single .properties-formatted string.
		assertThat(propPrefs).hasSize(1);
		assertThat(propPrefs.get(0))
				.contains("cleanup.make_local_variable_final=true")
				.contains("cleanup.format_source_code=false");
	}

	@Test
	void configureBuilderWithEmptySettingsSkipsPropertyPreferencesCall() throws Exception {
		// settings == empty → setPropertyPreferences is NOT called → the builder's settingProperties
		// stays at its default (empty list).
		EclipseCleanUp mojo = new EclipseCleanUp();
		setField(mojo, "settings", new LinkedHashMap<String, String>());
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		List<String> propPrefs = (List<String>) reflectBuilderField(builder, "settingProperties");
		assertThat(propPrefs).isEmpty();
	}

	@Test
	void configureBuilderWithNullSettingsSkipsPropertyPreferencesCall() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		setField(mojo, "settings", null);
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		List<String> propPrefs = (List<String>) reflectBuilderField(builder, "settingProperties");
		assertThat(propPrefs).isEmpty();
	}

	@Test
	void configureBuilderAppliesP2Mirrors() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		P2Mirror mirror = new P2Mirror();
		Field prefixField = P2Mirror.class.getDeclaredField("prefix");
		prefixField.setAccessible(true);
		prefixField.set(mirror, "https://example.org/");
		Field urlField = P2Mirror.class.getDeclaredField("url");
		urlField.setAccessible(true);
		urlField.set(mirror, "https://mirror.example.org/");
		setField(mojo, "p2Mirrors", List.of(mirror));
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		@SuppressWarnings("unchecked")
		Map<String, String> recorded = (Map<String, String>) reflectBuilderField(builder, "p2Mirrors");
		assertThat(recorded).containsEntry("https://example.org/", "https://mirror.example.org/");
	}

	@Test
	void configureBuilderWithCacheDirectoryAppliesIt() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		File cacheDir = new File(workDir, "p2-cache");
		setField(mojo, "cacheDirectory", cacheDir);
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		assertThat(reflectBuilderField(builder, "cacheDirectory")).isEqualTo(cacheDir);
	}

	@Test
	void configureBuilderWithoutCacheDirectoryPropagatesNull() throws Exception {
		// configureBuilder always calls setCacheDirectory (the if-guard was dropped because it
		// was an equivalent mutant). Verify that null propagates correctly through the call.
		EclipseCleanUp mojo = new EclipseCleanUp();
		EquoBasedStepBuilder builder = mojo.configureBuilder(stepConfig());
		assertThat(reflectBuilderField(builder, "cacheDirectory")).isNull();
	}

	// =========================================================================
	// toPropertyLines: package-private static helper. Tested directly to kill
	// PIT mutants on the StringBuilder construction (initial-capacity Math
	// mutators) and on the per-entry append calls.
	// =========================================================================

	@SuppressWarnings("unchecked")
	private static List<String> invokeToPropertyLines(Map<String, String> settings) throws Exception {
		Method m = EclipseCleanUp.class.getDeclaredMethod("toPropertyLines", Map.class);
		m.setAccessible(true);
		return (List<String>) m.invoke(null, settings);
	}

	@Test
	void toPropertyLinesProducesKeyEqualsValueLines() throws Exception {
		Map<String, String> in = new LinkedHashMap<>();
		in.put("alpha", "1");
		in.put("beta", "2");
		List<String> out = invokeToPropertyLines(in);
		assertThat(out).hasSize(1);
		assertThat(out.get(0)).isEqualTo("alpha=1\nbeta=2\n");
	}

	@Test
	void toPropertyLinesPreservesInsertionOrder() throws Exception {
		Map<String, String> in = new LinkedHashMap<>();
		in.put("z.last", "true");
		in.put("a.first", "false");
		List<String> out = invokeToPropertyLines(in);
		// LinkedHashMap insertion order: z.last comes before a.first.
		assertThat(out.get(0)).isEqualTo("z.last=true\na.first=false\n");
	}

	@Test
	void toPropertyLinesWithEmptyMapReturnsSingleEmptyString() throws Exception {
		List<String> out = invokeToPropertyLines(new LinkedHashMap<>());
		assertThat(out).containsExactly("");
	}

	@Test
	void toPropertyLinesEmitsExactlyOneNewlinePerEntry() throws Exception {
		Map<String, String> in = new LinkedHashMap<>();
		in.put("k1", "v1");
		in.put("k2", "v2");
		in.put("k3", "v3");
		String joined = invokeToPropertyLines(in).get(0);
		long newlineCount = joined.chars().filter(c -> c == '\n').count();
		assertThat(newlineCount).isEqualTo(3);
	}

	// =========================================================================
	// Direct field access: documents the @Parameter-bound fields are present
	// and have the documented defaults (mirrors the mojo's contract with Maven
	// Plexus DI).
	// =========================================================================

	@Test
	void mojoHasExpectedFieldsAndDefaults() throws Exception {
		EclipseCleanUp mojo = new EclipseCleanUp();
		assertThat(getField(mojo, "file")).isNull();
		assertThat(getField(mojo, "settings")).isInstanceOf(Map.class);
		assertThat((Map<?, ?>) getField(mojo, "settings")).isEmpty();
		assertThat(getField(mojo, "version")).isNull();
		assertThat((List<?>) getField(mojo, "p2Mirrors")).isEmpty();
		assertThat(getField(mojo, "cacheDirectory")).isNull();
	}

}
