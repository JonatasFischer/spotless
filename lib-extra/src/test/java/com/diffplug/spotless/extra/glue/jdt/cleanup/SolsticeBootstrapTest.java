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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.TestP2Provisioner;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;
import com.diffplug.spotless.extra.java.EclipseJdtCleanUpStep;

class SolsticeBootstrapTest {
	@TempDir
	Path temporary;

	@Test
	void shutdownCleanupStillWorksAfterThePluginClassLoaderIsClosed() throws Exception {
		Path instanceArea = Files.createDirectories(temporary.resolve("workspace with spaces ç"));
		Files.writeString(Files.createDirectories(instanceArea.resolve(".metadata")).resolve("preferences"), "test");
		Runnable cleanup;
		URL classes = SolsticeBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
		String bootstrapName = SolsticeBootstrap.class.getName();
		try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!name.startsWith(bootstrapName)) {
					return super.loadClass(name, resolve);
				}
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = findClass(name);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}) {
			Class<?> bootstrap = loader.loadClass(SolsticeBootstrap.class.getName());
			Method factory = bootstrap.getDeclaredMethod("instanceAreaCleanup", Path.class);
			factory.setAccessible(true);
			cleanup = (Runnable) factory.invoke(null, instanceArea);
		}
		cleanup.run();
		assertThat(instanceArea).doesNotExist();
		cleanup.run();
	}

	@Test
	void instanceAreaRemainsInTheTemporaryDirectoryRatherThanAnEncodedRelativePath() throws Exception {
		var builder = EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
		builder.setPropertyPreferences(List.of("cleanup.make_variable_declarations_final=true", "cleanup.make_local_variable_final=true"));
		FormatterStep step = builder.build();
		String source = "class Example { void run() { int value = 1; System.out.println(value); } }";
		String formatted = step.format(source, new File("Example.java"));
		assertThat(formatted).contains("final int value");
		Method stateMethod = Class.forName("com.diffplug.spotless.FormatterStepEqualityOnStateSerialization").getDeclaredMethod("state");
		stateMethod.setAccessible(true);
		var state = (EquoBasedStepBuilder.State) stateMethod.invoke(step);
		ClassLoader loader = state.getJarState().getClassLoader();
		Object instanceLocation = loader.loadClass("org.eclipse.core.runtime.Platform").getMethod("getInstanceLocation").invoke(null);
		Method getUrl = loader.loadClass("org.eclipse.osgi.service.datalocation.Location").getMethod("getURL");
		URL location = (URL) getUrl.invoke(instanceLocation);
		Path instanceArea = Path.of(location.toURI());
		assertThat(instanceArea).isAbsolute().isDirectory();
		assertThat(instanceArea.getParent()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
		assertThat(instanceArea.getFileName().toString()).startsWith("spotless-jdt-cleanup");
		assertThat(Path.of(URLEncoder.encode(location.toExternalForm(), StandardCharsets.UTF_8)))
				.as("Solstice must not create an encoded file URL relative to the working directory")
				.doesNotExist();
		assertThat(step.format(formatted, new File("Example.java"))).isEqualTo(formatted);
		assertThat(getUrl.invoke(instanceLocation)).isEqualTo(location);
	}
}
