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

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@code eclipseCleanUp} DSL — runs a full {@code spotlessApply} via
 * the Gradle Test Kit so that classloader / Plugin DSL / step-builder wiring is exercised
 * end-to-end. Lighter-weight unit tests live in
 * {@code lib-extra/.../EclipseJdtCleanUpStepTest}.
 */
class JavaEclipseCleanUpTest extends GradleIntegrationHarness {

	@Test
	void cleanUpWithConfigPropertiesString() throws IOException {
		setFile("build.gradle").toLines(
				"plugins {",
				"  id 'com.diffplug.spotless'",
				"  id 'java'",
				"}",
				"repositories { mavenCentral() }",
				"",
				"spotless {",
				"  java {",
				"    eclipseCleanUp().configProperties(\"\"\"",
				"cleanup.make_variable_declarations_final=true",
				"cleanup.make_local_variable_final=true",
				"cleanup.format_source_code=false",
				"\"\"\")",
				"  }",
				"}");
		setFile("src/main/java/test/Foo.java").toLines(
				"package test;",
				"public class Foo {",
				"  public int sum(int a, int b) {",
				"    int r = a + b;",
				"    return r;",
				"  }",
				"}");

		gradleRunner().withArguments("spotlessApply").build();

		assertFile("src/main/java/test/Foo.java").matches(a -> a.contains("final int r = a + b"));
	}

	@Test
	void java21AndStrictModeArePassedToTheCleanupEngine() throws IOException {
		setFile("build.gradle").toLines(
				"plugins { id 'com.diffplug.spotless'; id 'java' }",
				"repositories { mavenCentral() }",
				"spotless { java { eclipseCleanUp().javaVersion('21').strict(true).configProperties('''",
				"cleanup.make_variable_declarations_final=true",
				"cleanup.make_local_variable_final=true",
				"''') } }");
		String path = "src/main/java/example/Java21.java";
		setFile(path).toResource("java/eclipse/cleanup/Java21.test");
		gradleRunner().withArguments("spotlessApply").build();
		assertFile(path).sameAsResource("java/eclipse/cleanup/Java21.clean");
	}

	@Test
	void strictModeFailsAndTolerantModeWarnsForUnsupportedOptions() throws IOException {
		setFile("build.gradle").toLines(
				"plugins { id 'com.diffplug.spotless'; id 'java' }",
				"repositories { mavenCentral() }",
				"spotless { java { eclipseCleanUp().strict(project.hasProperty('strictCleanup')).configProperties('''",
				"cleanup.no_such_action=true",
				"cleanup.make_variable_declarations_final=true",
				"cleanup.make_local_variable_final=true",
				"''') } }");
		String path = "src/main/java/test/Simple.java";
		setFile(path).toResource("java/eclipse/cleanup/Simple.test");
		String strict = gradleRunner().withArguments("spotlessApply", "-PstrictCleanup").buildAndFail().getOutput();
		assertThat(strict).contains("cleanup.no_such_action", "not implemented", "Simple.java");
		assertFile(path).sameAsResource("java/eclipse/cleanup/Simple.test");
		String tolerant = gradleRunner().withArguments("spotlessApply").build().getOutput();
		assertThat(tolerant).contains("cleanup.no_such_action", "not implemented", "Simple.java");
		assertFile(path).sameAsResource("java/eclipse/cleanup/Simple.clean");
	}

	@Test
	void nativeModernizationsApplyTogetherAndCompile() throws IOException {
		setFile("build.gradle").toLines(
				"plugins { id 'com.diffplug.spotless'; id 'java' }",
				"repositories { mavenCentral() }",
				"spotless { java { eclipseCleanUp().javaVersion('17').strict(true).configProperties('''",
				"cleanup.use_var=true",
				"cleanup.stringconcat_to_textblock=true",
				"cleanup.multi_catch=true",
				"cleanup.remove_redundant_type_arguments=true",
				"''') } }",
				"tasks.named('compileJava') { dependsOn 'spotlessApply'; options.release = 17 }");
		String[] names = {"NativeVar", "NativeVarLambda", "NativeTextBlock", "NativeMultiCatch", "NativeDiamond"};
		for (String name : names) {
			setFile("src/main/java/example/" + name + ".java").toResource("java/eclipse/cleanup/" + name + ".test");
		}
		gradleRunner().withArguments("compileJava").build();
		for (String name : names) {
			assertFile("src/main/java/example/" + name + ".java").sameAsResource("java/eclipse/cleanup/" + name + ".clean");
		}
		gradleRunner().withArguments("spotlessCheck").build();
	}

	@Test
	void nativeSimplificationsApplyTogetherAndCompile() throws IOException {
		setFile("build.gradle").toLines(
				"plugins { id 'com.diffplug.spotless'; id 'java' }",
				"repositories { mavenCentral() }",
				"spotless { java { eclipseCleanUp().javaVersion('8').strict(true).configProperties('''",
				"cleanup.primitive_rather_than_wrapper=true",
				"cleanup.stringbuffer_to_stringbuilder=true",
				"cleanup.stringbuilder_for_local_vars=true",
				"cleanup.remove_redundant_modifiers=true",
				"cleanup.remove_redundant_semicolons=true",
				"cleanup.no_super=true",
				"cleanup.add_all=true",
				"cleanup.collection_cloning=true",
				"cleanup.remove_unnecessary_array_creation=true",
				"''') } }",
				"tasks.named('compileJava') { dependsOn 'spotlessApply'; options.release = 8 }");
		String[] names = {"NativePrimitive", "NativeStringBuffer", "NativeModifiers", "NativeSemicolons",
				"NativeSuperCall", "NativeAddAll", "NativeCollectionCopy", "NativeArrayCreation"};
		for (String name : names) {
			setFile("src/main/java/example/" + name + ".java").toResource("java/eclipse/cleanup/" + name + ".test");
		}
		gradleRunner().withArguments("compileJava").build();
		for (String name : names) {
			assertFile("src/main/java/example/" + name + ".java").sameAsResource("java/eclipse/cleanup/" + name + ".clean");
		}
		gradleRunner().withArguments("spotlessCheck").build();
	}

	@Test
	void headlessRefactoringsCompileAndSupportConfigurationCache() throws IOException {
		setFile("build.gradle").toLines(
				"plugins { id 'com.diffplug.spotless'; id 'java' }",
				"repositories { mavenCentral() }",
				"spotless { java { eclipseCleanUp().javaVersion('21').strict(true).configProperties('''",
				"cleanup.instanceof=true",
				"cleanup.convert_to_switch_expressions=true",
				"cleanup.convert_to_enhanced_for_loop=true",
				"''') } }",
				"tasks.named('compileJava') { dependsOn 'spotlessApply' }");
		String[] names = {"PatternInstanceof", "SwitchExpression", "ConvertLoop", "ImportLoop", "ImportConflict"};
		for (String name : names) {
			setFile("src/main/java/" + name + ".java").toResource("java/eclipse/cleanup/" + name + ".test");
		}
		gradleRunner().withArguments("spotlessApply").build();
		for (String name : names) {
			assertFile("src/main/java/" + name + ".java").sameAsResource("java/eclipse/cleanup/" + name + ".clean");
		}
		// Verify reuse with stable source files after the initial formatting pass.
		gradleRunner().withArguments("compileJava", "--configuration-cache").build();
		String output = gradleRunner().withArguments("compileJava", "--configuration-cache").build().getOutput();
		assertThat(output).contains("Reusing configuration cache.");
	}

	@Test
	void cleanUpInvalidVersionFailsFast() throws IOException {
		setFile("build.gradle").toLines(
				"plugins {",
				"  id 'com.diffplug.spotless'",
				"  id 'java'",
				"}",
				"repositories { mavenCentral() }",
				"",
				"spotless {",
				"  java {",
				"    eclipseCleanUp('latest')",
				"  }",
				"}");
		setFile("src/main/java/test/Empty.java").toLines("package test;", "public class Empty {}");

		// An unrecognised version should bail at configuration time with a clear error rather
		// than producing an opaque P2 404 deeper in the build.
		String stderr = gradleRunner().withArguments("spotlessApply").buildAndFail().getOutput();
		assertThat(stderr).containsIgnoringCase("Eclipse JDT version");
	}
}
