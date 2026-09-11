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
package com.diffplug.gradle.spotless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;

/**
 * Unit-level tests for {@link JavaExtension.EclipseCleanUpConfig}. Drives every DSL builder
 * method through an in-process {@link Project} so JaCoCo can instrument the configuration class.
 *
 * <p>End-to-end behaviour (real Gradle Test Kit subprocess) is covered by
 * {@link JavaEclipseCleanUpTest}; this class focuses on argument validation, ordering, and the
 * one-cleanup-per-config-call contract that's hard to assert from a TestKit run.
 */
class JavaEclipseCleanUpUnitTest {

	@TempDir
	File projectDir;

	private Project project;
	private JavaExtension javaExtension;

	@BeforeEach
	void setUp() {
		project = ProjectBuilder.builder().withProjectDir(projectDir).build();
		project.getRepositories().mavenCentral();
		project.getPluginManager().apply("com.diffplug.spotless");
		project.getPluginManager().apply("java");
		SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);
		// Trigger registration of the JavaExtension via the public DSL — the closure body is
		// deferred (lazyActions), but maybeCreate fires synchronously and stores the extension
		// in the package-private `formats` map, which we read here so the unit tests can call
		// methods on it directly.
		spotless.java(j -> {});
		javaExtension = (JavaExtension) spotless.formats.get(JavaExtension.NAME);
	}

	// =========================================================================
	// Factory methods (eclipseCleanUp / eclipseCleanUp(version))
	// =========================================================================

	@Test
	void eclipseCleanUpUsesDefaultVersion() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config).isNotNull();
	}

	@Test
	void eclipseCleanUpWithExplicitVersion() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp("4.39");
		assertThat(config).isNotNull();
	}

	@Test
	void eclipseCleanUpRejectsNullVersion() {
		assertThatThrownBy(() -> javaExtension.eclipseCleanUp((String) null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("version");
	}

	@Test
	void eclipseCleanUpRejectsBlankVersion() {
		assertThatThrownBy(() -> javaExtension.eclipseCleanUp(""))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> javaExtension.eclipseCleanUp("not-a-version"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =========================================================================
	// Builder methods
	// =========================================================================

	@Test
	void configFileRejectsEmptyVarargs() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.configFile(new Object[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least one file");
	}

	@Test
	void configFileRejectsNullElements() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		// requireElementsNonNull throws NullPointerException for null array elements.
		assertThatThrownBy(() -> config.configFile((Object) null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void configFileAcceptsValidFile() throws IOException {
		File profile = new File(projectDir, "profile.xml");
		Files.writeString(profile.toPath(), "<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>");
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		// Returns the same fluent config — chaining works.
		assertThat(config.configFile(profile)).isSameAs(config);
	}

	@Test
	void configPropertiesRejectsEmptyVarargs() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.configProperties(new String[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least one entry");
	}

	@Test
	void configPropertiesRejectsNullElements() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.configProperties((String) null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void configPropertiesAcceptsValidString() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.configProperties("cleanup.foo=true")).isSameAs(config);
	}

	@Test
	void configXmlRejectsEmptyVarargs() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.configXml(new String[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least one entry");
	}

	@Test
	void configXmlRejectsNullElements() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.configXml((String) null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void configXmlAcceptsValidString() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.configXml("<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>")).isSameAs(config);
	}

	@Test
	void withP2MirrorsRejectsNull() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.withP2Mirrors(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("mirrors");
	}

	@Test
	void withP2MirrorsAcceptsEmptyMap() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.withP2Mirrors(Map.of())).isSameAs(config);
	}

	@Test
	void withP2MirrorsAcceptsPopulatedMap() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.withP2Mirrors(Map.of("https://example.org/", "https://mirror.example.org/")))
				.isSameAs(config);
	}

	@Test
	void withCacheDirectoryRejectsNull() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.withCacheDirectory(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("cacheDirectory");
	}

	@Test
	void withCacheDirectoryAcceptsValidDirectory() {
		File cacheDir = new File(projectDir, "p2-cache");
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.withCacheDirectory(cacheDir)).isSameAs(config);
	}

	@Test
	void withVersionRejectsNull() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.withVersion(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("version");
	}

	@Test
	void withVersionAcceptsValidVersion() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThat(config.withVersion("4.40")).isSameAs(config);
	}

	@Test
	void withVersionRejectsInvalidVersion() {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		assertThatThrownBy(() -> config.withVersion("nonsense"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =========================================================================
	// Builder method chaining
	// =========================================================================

	@Test
	void allBuilderMethodsCanBeChained() throws IOException {
		File profile = new File(projectDir, "profile.xml");
		Files.writeString(profile.toPath(), "<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>");
		File cacheDir = new File(projectDir, "p2-cache");
		// Chain every builder method to ensure each returns the config and accepts valid input.
		JavaExtension.EclipseCleanUpConfig result = javaExtension.eclipseCleanUp("4.39")
				.configProperties("cleanup.foo=true")
				.configXml("<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>")
				.configFile(profile)
				.withP2Mirrors(Map.of())
				.withCacheDirectory(cacheDir)
				.withVersion("4.40");
		assertThat(result).isNotNull();
	}

	// =========================================================================
	// Side-effect tests — verify each builder method (a) actually mutates the
	// underlying builder's state and (b) writes a fresh FormatterStep into the
	// extension's `steps` list. These two assertions kill the
	// VoidMethodCallMutator on every builder.setX(...) and replaceStep(...) call.
	//
	// Strategy: read the builder's private state reflectively to verify setX,
	// and read FormatExtension#steps to verify replaceStep. We avoid asserting
	// on the built FormatterStep itself because its build() phase performs P2
	// provisioning, which is unreliable in a unit-test JVM (no network / no
	// real cache for non-default versions).
	// =========================================================================

	private static Object reflect(Object target, Class<?> declaringClass, String fieldName) throws Exception {
		Field f = declaringClass.getDeclaredField(fieldName);
		f.setAccessible(true);
		return f.get(target);
	}

	private static EquoBasedStepBuilder builderOf(JavaExtension.EclipseCleanUpConfig config) throws Exception {
		return (EquoBasedStepBuilder) reflect(config, JavaExtension.EclipseCleanUpConfig.class, "builder");
	}

	@Test
	void sourceLevelAndStrictnessUpdateTheStep() throws Exception {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep before = lastStep();
		assertThat(config.javaVersion("21")).isSameAs(config);
		assertThat(lastStep()).isNotSameAs(before);
		before = lastStep();
		assertThat(config.strict(true)).isSameAs(config);
		assertThat(lastStep()).isNotSameAs(before);
		var method = EquoBasedStepBuilder.class.getDeclaredMethod("stepProperties");
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, String> properties = (Map<String, String>) method.invoke(builderOf(config));
		assertThat(properties).containsEntry("sp_cleanup.java_version", "21").containsEntry("sp_cleanup.strict", "true");
		config.javaVersion("17").strict(false);
		@SuppressWarnings("unchecked")
		Map<String, String> updated = (Map<String, String>) method.invoke(builderOf(config));
		assertThat(updated).containsEntry("sp_cleanup.java_version", "17").containsEntry("sp_cleanup.strict", "false");
	}

	@Test
	void configPropertiesUpdatesBuilderAndStepsList() throws Exception {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep beforeStep = lastStep();
		config.configProperties("cleanup.alpha=true");
		// (a) builder state was actually changed by setPropertyPreferences.
		@SuppressWarnings("unchecked")
		List<String> propPrefs = (List<String>) reflect(builderOf(config), EquoBasedStepBuilder.class, "settingProperties");
		assertThat(propPrefs).contains("cleanup.alpha=true");
		// (b) the steps list was updated (replaceStep called) — the reference at index 0 is new.
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	@Test
	void configXmlUpdatesBuilderAndStepsList() throws Exception {
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep beforeStep = lastStep();
		String xml = "<?xml version=\"1.0\"?><profiles version=\"2\"><profile name=\"a\"/></profiles>";
		config.configXml(xml);
		@SuppressWarnings("unchecked")
		List<String> xmlPrefs = (List<String>) reflect(builderOf(config), EquoBasedStepBuilder.class, "settingXml");
		assertThat(xmlPrefs).contains(xml);
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	@Test
	void configFileUpdatesBuilderAndStepsList() throws Exception {
		File profile = new File(projectDir, "profile.xml");
		Files.writeString(profile.toPath(), "<?xml version=\"1.0\"?><profiles version=\"2\"></profiles>");
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep beforeStep = lastStep();
		config.configFile(profile);
		@SuppressWarnings("unchecked")
		Iterable<File> filePrefs = (Iterable<File>) reflect(builderOf(config), EquoBasedStepBuilder.class, "settingsFiles");
		assertThat(filePrefs).contains(profile);
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	@Test
	void withVersionUpdatesBuilderAndStepsList() throws Exception {
		// Stay on the default version so build() has the P2 cache primed; only the builder's
		// internal version field changes (we re-set the same string).
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp("4.39");
		FormatterStep beforeStep = lastStep();
		config.withVersion("4.39");
		String version = (String) reflect(builderOf(config), EquoBasedStepBuilder.class, "formatterVersion");
		assertThat(version).isEqualTo("4.39");
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	@Test
	void withCacheDirectoryUpdatesBuilderAndStepsList() throws Exception {
		File cacheDir = new File(projectDir, "p2-cache");
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep beforeStep = lastStep();
		config.withCacheDirectory(cacheDir);
		File cached = (File) reflect(builderOf(config), EquoBasedStepBuilder.class, "cacheDirectory");
		assertThat(cached).isEqualTo(cacheDir);
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	@Test
	void withP2MirrorsUpdatesBuilderAndStepsList() throws Exception {
		Map<String, String> mirrors = Map.of("https://example.org/", "https://mirror.example.org/");
		JavaExtension.EclipseCleanUpConfig config = javaExtension.eclipseCleanUp();
		FormatterStep beforeStep = lastStep();
		config.withP2Mirrors(mirrors);
		@SuppressWarnings("unchecked")
		Map<String, String> recorded = (Map<String, String>) reflect(builderOf(config), EquoBasedStepBuilder.class, "p2Mirrors");
		assertThat(recorded).isEqualTo(mirrors);
		assertThat(lastStep()).isNotSameAs(beforeStep);
	}

	private FormatterStep lastStep() {
		return javaExtension.steps.get(javaExtension.steps.size() - 1);
	}
}
