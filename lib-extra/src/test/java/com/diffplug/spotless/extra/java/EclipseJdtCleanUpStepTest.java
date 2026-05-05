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
package com.diffplug.spotless.extra.java;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.diffplug.spotless.StepHarness;
import com.diffplug.spotless.TestP2Provisioner;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;

class EclipseJdtCleanUpStepTest {

	private static EquoBasedStepBuilder createBuilder() {
		return EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
	}

	@Test
	void cleanUp_makeLocalVariableFinal_useLambda_removeUnusedImports() {
		ClassLoader classLoader = getClass().getClassLoader();
		File configFile = new File(classLoader.getResource("java/eclipse/cleanup/cleanup.xml").getFile());
		EquoBasedStepBuilder builder = createBuilder();
		builder.setPreferences(List.of(configFile));
		StepHarness.forStep(builder.build())
				.testResource("java/eclipse/cleanup/CleanUpExample.test", "java/eclipse/cleanup/CleanUpExample.clean");
	}
}
