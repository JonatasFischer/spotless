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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystemSession;

import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;
import com.diffplug.spotless.extra.P2Mirror;
import com.diffplug.spotless.extra.java.EclipseJdtCleanUpStep;
import com.diffplug.spotless.maven.FormatterStepConfig;
import com.diffplug.spotless.maven.FormatterStepFactory;

/**
 * Maven mojo wrapper for {@link EclipseJdtCleanUpStep}.
 *
 * <p>Configured in {@code pom.xml} as:
 * <pre>{@code
 * <eclipseCleanUp>
 *   <!-- either <file> or <settings> (both is fine; <settings> wins) -->
 *   <file>path/to/cleanup.xml</file>
 *   <settings>
 *     <cleanup.make_local_variable_final>true</cleanup.make_local_variable_final>
 *   </settings>
 *   <version>4.40</version>
 * </eclipseCleanUp>
 * }</pre>
 *
 * <p>See {@link EclipseJdtCleanUpStep} for the list of supported and skipped cleanup categories.
 */
public class EclipseCleanUp implements FormatterStepFactory {

	/** Path to the Eclipse JDT clean-up profile XML. Optional when {@link #settings} is supplied. */
	@Parameter
	private String file;

	/**
	 * Inline cleanup settings. The keys mirror the {@code <setting id="..."/>} entries of an
	 * Eclipse-exported profile XML (e.g. {@code cleanup.make_local_variable_final}).
	 *
	 * <p>When both {@link #file} and {@code settings} are provided the inline values are merged on
	 * top of the file's, so an inline entry overrides the file's value for the same key.
	 */
	@Parameter
	private Map<String, String> settings = new LinkedHashMap<>();

	/** Eclipse JDT version. When omitted, {@link EclipseJdtCleanUpStep#defaultVersion()} is used. */
	@Parameter
	private String version;

	/** Java language level of the source, independently of the JVM running Maven. */
	@Parameter
	private String javaVersion = "17";

	/** Fail on ignored actions instead of warning and continuing. */
	@Parameter
	private boolean strict;

	/** P2 mirrors used when resolving the Eclipse JDT bundles. */
	@Parameter
	private List<P2Mirror> p2Mirrors = new ArrayList<>();

	private File cacheDirectory;

	@Override
	public FormatterStep newFormatterStep(FormatterStepConfig stepConfig) {
		return configureBuilder(stepConfig).build();
	}

	/** Configures file preferences first, followed by inline overrides. */
	EquoBasedStepBuilder configureBuilder(FormatterStepConfig stepConfig) {
		EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(
				stepConfig.getProvisioner(), stepConfig.getP2Provisioner());
		builder.setVersion(version != null ? version : EclipseJdtCleanUpStep.defaultVersion());
		builder.setJavaVersion(javaVersion);
		builder.setStrict(strict);
		if (file != null) {
			File settingsFile = stepConfig.getFileLocator().locateFile(file);
			builder.setPreferences(Collections.singletonList(settingsFile));
		}
		if (settings != null && !settings.isEmpty()) {
			builder.setPropertyPreferences(toPropertyLines(settings));
		}
		builder.setP2Mirrors(p2Mirrors);
		builder.setCacheDirectory(cacheDirectory);
		return builder;
	}

	@Override
	public void init(RepositorySystemSession repositorySystemSession) {
		this.cacheDirectory = repositorySystemSession.getLocalRepository().getBasedir();
	}

	/**
	 * Renders the inline {@code <settings>} map as a single {@code .properties}-style string that
	 * {@link EclipseJdtCleanUpStep.Builder#setPropertyPreferences(List)} can ingest.
	 *
	 * <p>Package-private for direct unit tests.
	 */
	static List<String> toPropertyLines(Map<String, String> settings) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : settings.entrySet()) {
			sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
		}
		return Collections.singletonList(sb.toString());
	}
}
