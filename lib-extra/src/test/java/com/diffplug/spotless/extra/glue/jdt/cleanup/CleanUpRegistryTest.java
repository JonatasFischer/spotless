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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Properties;

import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Direct unit tests for {@link CleanUpRegistry}. */
class CleanUpRegistryTest {

	@Test
	void reportsUnsupportedEnabledOptionsInStableOrder() {
		Properties profile = new Properties();
		profile.setProperty("cleanup.unknown_z", "true");
		profile.setProperty("cleanup.unknown_a", "true");
		profile.setProperty("cleanup.unknown_disabled", "false");
		profile.setProperty("other.key", "true");
		profile.setProperty("cleanup.format_source_code", "true");
		profile.setProperty("cleanup.make_variable_declarations_final", "true");
		profile.setProperty("cleanup.make_local_variable_final", "true");
		assertThat(CleanUpRegistry.profileProblems(profile, "17")).containsExactly(
				"cleanup.unknown_a: not implemented by the headless cleanup registry",
				"cleanup.unknown_z: not implemented by the headless cleanup registry");
	}

	@Test
	void reportsLanguageRequirementsAndAcceptsTheirBoundaryVersions() {
		Properties profile = new Properties();
		profile.setProperty("cleanup.instanceof", "true");
		profile.setProperty("cleanup.convert_to_switch_expressions", "true");
		assertThat(CleanUpRegistry.profileProblems(profile, "1.8")).containsExactly(
				"cleanup.convert_to_switch_expressions: requires Java 14 or later (configured 1.8)",
				"cleanup.instanceof: requires Java 16 or later (configured 1.8)");
		assertThat(CleanUpRegistry.profileProblems(profile, "14")).containsExactly(
				"cleanup.instanceof: requires Java 16 or later (configured 14)");
		assertThat(CleanUpRegistry.profileProblems(profile, "16")).isEmpty();
	}

	@Test
	void recognizesTheNativeModernizationOptions() {
		Properties profile = new Properties();
		for (String key : List.of("cleanup.use_var", "cleanup.stringconcat_to_textblock",
				"cleanup.stringconcat_stringbuffer_stringbuilder", "cleanup.multi_catch",
				"cleanup.insert_inferred_type_arguments", "cleanup.remove_redundant_type_arguments")) {
			profile.setProperty(key, "true");
		}
		assertThat(CleanUpRegistry.profileProblems(profile, "15")).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"cleanup.use_var,10", "cleanup.stringconcat_to_textblock,15", "cleanup.stringconcat_stringbuffer_stringbuilder,15"})
	void modernizationLanguageBoundaries(String key, int minimum) {
		Properties profile = new Properties();
		profile.setProperty(key, "true");
		assertThat(CleanUpRegistry.profileProblems(profile, Integer.toString(minimum - 1)))
				.containsExactly(key + ": requires Java " + minimum + " or later (configured " + (minimum - 1) + ")");
		assertThat(CleanUpRegistry.profileProblems(profile, Integer.toString(minimum))).isEmpty();
	}

	@Test
	void recognizesNativeSimplificationsAtTheMinimumSupportedJavaLevel() {
		Properties profile = new Properties();
		for (String key : List.of("cleanup.primitive_rather_than_wrapper", "cleanup.stringbuffer_to_stringbuilder",
				"cleanup.stringbuilder_for_local_vars", "cleanup.remove_redundant_modifiers", "cleanup.remove_redundant_semicolons",
				"cleanup.no_super", "cleanup.add_all", "cleanup.collection_cloning", "cleanup.remove_unnecessary_array_creation")) {
			profile.setProperty(key, "true");
		}
		assertThat(CleanUpRegistry.profileProblems(profile, "8")).isEmpty();
	}

	@Test
	void buildAllReturnsAllRegisteredCleanups() {
		List<ICleanUp> cleanUps = CleanUpRegistry.buildAll();
		// Locks the registered count — adding/removing a cleanup is a deliberate behaviour change.
		assertThat(cleanUps).hasSize(27);
	}

	@Test
	void buildAllReturnsFreshInstancesEachCall() {
		// Each cleanup is mutable (setOptions, fix cache); callers must get a fresh list per
		// invocation so concurrent invocations do not share state.
		List<ICleanUp> first = CleanUpRegistry.buildAll();
		List<ICleanUp> second = CleanUpRegistry.buildAll();
		assertThat(first).hasSameSizeAs(second);
		for (int i = 0; i < first.size(); i++) {
			assertThat(first.get(i)).isNotSameAs(second.get(i));
			assertThat(first.get(i).getClass()).isEqualTo(second.get(i).getClass());
		}
	}

	@Test
	void buildAllRegistersExpectedCleanUpClassesByName() {
		// Verifies the full catalogue by simple class-name match. Ordering matters because some
		// cleanups assume earlier ones have already run (e.g. unused-imports before lambda).
		List<String> classNames = CleanUpRegistry.buildAll().stream()
				.map(c -> c.getClass().getSimpleName())
				.toList();
		assertThat(classNames).containsExactly(
				"UnusedCodeCleanUpCore",
				"CodeStyleCleanUpCore",
				"VariableDeclarationCleanUpCore",
				"LambdaExpressionsCleanUpCore",
				"PatternMatchingForInstanceofCleanUpCore",
				"PotentialProgrammingProblemsCleanUpCore",
				"SwitchExpressionsCleanUpCore",
				"PrimitiveComparisonCleanUpCore",
				"ValueOfRatherThanInstantiationCleanUpCore",
				"UnnecessaryCodeCleanUpCore",
				"ConvertLoopCleanUpCore",
				"BooleanValueRatherThanComparisonCleanUpCore",
				"OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore",
				"RedundantComparatorCleanUpCore",
				"ReturnExpressionCleanUpCore",
				"MultiCatchCleanUpCore",
				"TypeParametersCleanUpCore",
				"PrimitiveRatherThanWrapperCleanUpCore",
				"StringBufferToStringBuilderCleanUpCore",
				"RedundantModifiersCleanUp",
				"RedundantSemicolonsCleanUpCore",
				"RedundantSuperCallCleanUp",
				"AddAllCleanUpCore",
				"CollectionCloningCleanUpCore",
				"UnnecessaryArrayCreationCleanUpCore",
				"StringConcatToTextBlockCleanUpCore",
				"VarCleanUpCore");
	}

	@Test
	void privateConstructorIsInvocableViaReflection() throws Exception {
		Constructor<CleanUpRegistry> ctor = CleanUpRegistry.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
		ctor.setAccessible(true);
		assertThat(ctor.newInstance()).isNotNull();
	}
}
