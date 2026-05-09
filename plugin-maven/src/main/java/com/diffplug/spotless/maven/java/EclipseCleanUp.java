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
import java.util.Arrays;
import java.util.List;

import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystemSession;

import com.diffplug.spotless.FormatterStep;
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
 *   <file>path/to/cleanup.xml</file>
 *   <version>4.39</version>
 * </eclipseCleanUp>
 * }</pre>
 *
 * <p>See {@link EclipseJdtCleanUpStep} for the list of supported and skipped cleanup categories.
 */
public class EclipseCleanUp implements FormatterStepFactory {

	@Parameter
	private String file;

	@Parameter
	private String version;

	@Parameter
	private List<P2Mirror> p2Mirrors = new ArrayList<>();

	private File cacheDirectory;

	@Override
	public FormatterStep newFormatterStep(FormatterStepConfig stepConfig) {
		EclipseJdtCleanUpStep.Builder builder = EclipseJdtCleanUpStep.createBuilder(stepConfig.getProvisioner(), stepConfig.getP2Provisioner());
		builder.setVersion(version == null ? EclipseJdtCleanUpStep.defaultVersion() : version);
		if (file != null) {
			File settingsFile = stepConfig.getFileLocator().locateFile(file);
			builder.setPreferences(Arrays.asList(settingsFile));
		}
		builder.setP2Mirrors(p2Mirrors);
		if (cacheDirectory != null) {
			builder.setCacheDirectory(cacheDirectory);
		}
		return builder.build();
	}

	@Override
	public void init(RepositorySystemSession repositorySystemSession) {
		this.cacheDirectory = repositorySystemSession.getLocalRepository().getBasedir();
	}
}
