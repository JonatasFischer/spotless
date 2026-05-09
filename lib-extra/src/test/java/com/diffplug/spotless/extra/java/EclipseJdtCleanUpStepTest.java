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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.diffplug.spotless.ResourceHarness;
import com.diffplug.spotless.StepHarness;
import com.diffplug.spotless.TestP2Provisioner;
import com.diffplug.spotless.TestProvisioner;
import com.diffplug.spotless.extra.EquoBasedStepBuilder;

class EclipseJdtCleanUpStepTest extends ResourceHarness {

	private static EquoBasedStepBuilder createBuilder() {
		return EclipseJdtCleanUpStep.createBuilder(TestProvisioner.mavenCentral(), TestP2Provisioner.defaultProvisioner());
	}

	private void runCleanUp(String profileXmlResource, String beforeResource, String afterResource) {
		File configFile = setFile(profileXmlResource.substring(profileXmlResource.lastIndexOf('/') + 1)).toResource(profileXmlResource);
		EquoBasedStepBuilder builder = createBuilder();
		builder.setPreferences(List.of(configFile));
		StepHarness.forStep(builder.build()).testResource(beforeResource, afterResource);
	}

	// ---------------------------------------------------------------------------
	// Working cleanups
	// ---------------------------------------------------------------------------

	@Test
	void cleanUp_makeFinal_useLambda_removeUnusedImports() {
		runCleanUp("java/eclipse/cleanup/cleanup.xml",
				"java/eclipse/cleanup/CleanUpExample.test",
				"java/eclipse/cleanup/CleanUpExample.clean");
	}

	@Test
	void cleanUp_valueOfRatherThanInstantiation() {
		runCleanUp("java/eclipse/cleanup/ValueOf.xml",
				"java/eclipse/cleanup/ValueOf.test",
				"java/eclipse/cleanup/ValueOf.clean");
	}

	@Test
	void cleanUp_removeUnnecessaryCasts() {
		runCleanUp("java/eclipse/cleanup/UnnecessaryCast.xml",
				"java/eclipse/cleanup/UnnecessaryCast.test",
				"java/eclipse/cleanup/UnnecessaryCast.clean");
	}

	@Test
	void cleanUp_booleanValueRatherThanComparison() {
		runCleanUp("java/eclipse/cleanup/BooleanComparison.xml",
				"java/eclipse/cleanup/BooleanComparison.test",
				"java/eclipse/cleanup/BooleanComparison.clean");
	}

	// ---------------------------------------------------------------------------
	// Cleanups whose fix passes through CompilationUnitRewrite.attachChange ->
	// ImportRewriteAnalyzer, which traverses the IPackageFragmentRoot hierarchy
	// (calls isArchive(), root resource, etc.). Stubbing these out without a real
	// workspace runs into a chain of NPEs that goes deeper than is practical to
	// fake. Tracked as a known limitation; consider implementing a temporary
	// on-disk workspace if 100% coverage becomes mandatory.
	// ---------------------------------------------------------------------------

	@Test
	@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer; see EclipseJdtCleanUpStep Javadoc")
	void cleanUp_patternMatchingForInstanceof() {
		runCleanUp("java/eclipse/cleanup/PatternInstanceof.xml",
				"java/eclipse/cleanup/PatternInstanceof.test",
				"java/eclipse/cleanup/PatternInstanceof.clean");
	}

	@Test
	@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer; see EclipseJdtCleanUpStep Javadoc")
	void cleanUp_convertToSwitchExpressions() {
		runCleanUp("java/eclipse/cleanup/SwitchExpression.xml",
				"java/eclipse/cleanup/SwitchExpression.test",
				"java/eclipse/cleanup/SwitchExpression.clean");
	}

	@Test
	@Disabled("Needs a real PackageFragmentRoot for ImportRewriteAnalyzer; see EclipseJdtCleanUpStep Javadoc")
	void cleanUp_convertToEnhancedForLoop() {
		runCleanUp("java/eclipse/cleanup/ConvertLoop.xml",
				"java/eclipse/cleanup/ConvertLoop.test",
				"java/eclipse/cleanup/ConvertLoop.clean");
	}
}
