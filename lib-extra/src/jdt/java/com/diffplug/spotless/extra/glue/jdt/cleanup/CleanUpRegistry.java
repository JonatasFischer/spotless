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

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.ui.fix.BooleanValueRatherThanComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.CodeStyleCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ConvertLoopCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.LambdaExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PatternMatchingForInstanceofCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PotentialProgrammingProblemsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PrimitiveComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.RedundantComparatorCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ReturnExpressionCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.SwitchExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnnecessaryCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ValueOfRatherThanInstantiationCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.VariableDeclarationCleanUpCore;
import org.eclipse.jdt.ui.cleanup.ICleanUp;

/**
 * Catalogue of every {@link ICleanUp} the headless cleanup pipeline applies. Each entry is built
 * lazily so the cost of instantiation is only paid when {@link #buildAll()} is called.
 */
public final class CleanUpRegistry {

	private static final List<Supplier<ICleanUp>> CLEANUP_FACTORIES = List.of(
			// Unused code: remove_unused_imports, remove_unused_private_*
			UnusedCodeCleanUpCore::new,
			// Code style: qualify statics with declaring class, use this.*
			CodeStyleCleanUpCore::new,
			// Variable declarations: make_local_variable_final, make_parameters_final, make_private_fields_final
			VariableDeclarationCleanUpCore::new,
			// Lambda expressions: use_lambda, convert_functional_interfaces, simplify_lambda
			LambdaExpressionsCleanUpCore::new,
			// instanceof pattern matching (Java 16+)
			PatternMatchingForInstanceofCleanUpCore::new,
			// Potential programming problems: add_serial_version_id
			PotentialProgrammingProblemsCleanUpCore::new,
			// Switch expressions: convert_to_switch_expressions
			SwitchExpressionsCleanUpCore::new,
			// Primitive comparison: use primitive == instead of .equals()
			PrimitiveComparisonCleanUpCore::new,
			// ValueOf rather than instantiation: use Integer.valueOf() instead of new Integer()
			ValueOfRatherThanInstantiationCleanUpCore::new,
			// Unnecessary code: remove_unnecessary_casts, remove_unused_method_parameters
			UnnecessaryCodeCleanUpCore::new,
			// Convert classic for-loops to enhanced for-each (renamed in newer JDT versions).
			ConvertLoopCleanUpCore::new,
			// Boolean value rather than comparison: simplify boolean comparisons
			BooleanValueRatherThanComparisonCleanUpCore::new,
			// One if rather than duplicate blocks that fall through
			OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore::new,
			// Redundant comparator: e.g. Comparator.naturalOrder() simplifications
			RedundantComparatorCleanUpCore::new,
			// Return expression: simplify return statements
			ReturnExpressionCleanUpCore::new);

	// Options consumed by the registered implementations (including their subordinate switches).
	// ORGANIZE_IMPORTS is deliberately absent: UnusedCode only consults it to disable its own
	// import removal; implementing organize-imports requires a separate cleanup.
	private static final Set<String> SUPPORTED_OPTIONS = Set.of(
			CleanUpConstants.REMOVE_UNUSED_CODE_IMPORTS,
			CleanUpConstants.REMOVE_UNUSED_CODE_LOCAL_VARIABLES,
			CleanUpConstants.REMOVE_UNUSED_CODE_METHOD_PARAMETERS,
			CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_CONSTRUCTORS,
			CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_FELDS,
			CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_MEMBERS,
			CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_METHODS,
			CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_TYPES,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_FIELD_USE_THIS,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_FIELD_USE_THIS_ALWAYS,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_FIELD_USE_THIS_IF_NECESSARY,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_METHOD_USE_THIS,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_METHOD_USE_THIS_ALWAYS,
			CleanUpConstants.MEMBER_ACCESSES_NON_STATIC_METHOD_USE_THIS_IF_NECESSARY,
			CleanUpConstants.MEMBER_ACCESSES_STATIC_QUALIFY_WITH_DECLARING_CLASS,
			CleanUpConstants.MEMBER_ACCESSES_STATIC_QUALIFY_WITH_DECLARING_CLASS_FIELD,
			CleanUpConstants.MEMBER_ACCESSES_STATIC_QUALIFY_WITH_DECLARING_CLASS_INSTANCE_ACCESS,
			CleanUpConstants.MEMBER_ACCESSES_STATIC_QUALIFY_WITH_DECLARING_CLASS_METHOD,
			CleanUpConstants.MEMBER_ACCESSES_STATIC_QUALIFY_WITH_DECLARING_CLASS_SUBTYPE_ACCESS,
			CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL,
			CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL_LOCAL_VARIABLES,
			CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL_PARAMETERS,
			CleanUpConstants.VARIABLE_DECLARATIONS_USE_FINAL_PRIVATE_FIELDS,
			CleanUpConstants.ALSO_SIMPLIFY_LAMBDA,
			CleanUpConstants.CONVERT_FUNCTIONAL_INTERFACES,
			CleanUpConstants.SIMPLIFY_LAMBDA_EXPRESSION_AND_METHOD_REF,
			CleanUpConstants.USE_ANONYMOUS_CLASS_CREATION,
			CleanUpConstants.USE_LAMBDA,
			CleanUpConstants.USE_PATTERN_MATCHING_FOR_INSTANCEOF,
			CleanUpConstants.ADD_MISSING_SERIAL_VERSION_ID,
			CleanUpConstants.ADD_MISSING_SERIAL_VERSION_ID_DEFAULT,
			CleanUpConstants.ADD_MISSING_SERIAL_VERSION_ID_GENERATED,
			CleanUpConstants.CONTROL_STATEMENTS_CONVERT_TO_SWITCH_EXPRESSIONS,
			CleanUpConstants.PRIMITIVE_COMPARISON,
			CleanUpConstants.VALUEOF_RATHER_THAN_INSTANTIATION,
			CleanUpConstants.REMOVE_UNNECESSARY_CASTS,
			CleanUpConstants.CONTROL_STATEMENTS_CONVERT_FOR_LOOP_ONLY_IF_LOOP_VAR_USED,
			CleanUpConstants.CONTROL_STATEMENTS_CONVERT_FOR_LOOP_TO_ENHANCED,
			CleanUpConstants.BOOLEAN_VALUE_RATHER_THAN_COMPARISON,
			CleanUpConstants.ONE_IF_RATHER_THAN_DUPLICATE_BLOCKS_THAT_FALL_THROUGH,
			CleanUpConstants.REDUNDANT_COMPARATOR,
			CleanUpConstants.RETURN_EXPRESSION);

	private static final Map<String, Integer> MINIMUM_JAVA_VERSION = Map.of(
			CleanUpConstants.USE_PATTERN_MATCHING_FOR_INSTANCEOF, 16,
			CleanUpConstants.CONTROL_STATEMENTS_CONVERT_TO_SWITCH_EXPRESSIONS, 14);

	/** Sorted diagnostics for enabled options this pipeline cannot honor. */
	public static List<String> profileProblems(Properties settings, String javaVersion) {
		int major = "1.8".equals(javaVersion) ? 8 : Integer.parseInt(javaVersion);
		return settings.stringPropertyNames().stream()
				.filter(key -> key.startsWith("cleanup.") && "true".equals(settings.getProperty(key)))
				.filter(key -> !key.equals(CleanUpConstants.FORMAT_SOURCE_CODE))
				.sorted()
				.map(key -> {
					if (!SUPPORTED_OPTIONS.contains(key)) {
						return key + ": not implemented by the headless cleanup registry";
					}
					int minimum = MINIMUM_JAVA_VERSION.getOrDefault(key, 8);
					return major < minimum ? key + ": requires Java " + minimum + " or later (configured " + javaVersion + ")" : "";
				})
				.filter(problem -> !problem.isEmpty())
				.toList();
	}

	private CleanUpRegistry() {}

	/** Builds a fresh list of cleanup instances, in the order they should be applied. */
	public static List<ICleanUp> buildAll() {
		return CLEANUP_FACTORIES.stream().map(Supplier::get).toList();
	}
}
