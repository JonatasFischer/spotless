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
import java.util.function.Supplier;

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
			// Potential programming problems: add_serial_version_id, add_missing_override_annotations
			PotentialProgrammingProblemsCleanUpCore::new,
			// Switch expressions: convert_to_switch_expressions
			SwitchExpressionsCleanUpCore::new,
			// Primitive comparison: use primitive == instead of .equals()
			PrimitiveComparisonCleanUpCore::new,
			// ValueOf rather than instantiation: use Integer.valueOf() instead of new Integer()
			ValueOfRatherThanInstantiationCleanUpCore::new,
			// Unnecessary code: remove_unnecessary_casts, remove_redundant_type_arguments
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

	private CleanUpRegistry() {}

	/** Builds a fresh list of cleanup instances, in the order they should be applied. */
	public static List<ICleanUp> buildAll() {
		return CLEANUP_FACTORIES.stream().map(Supplier::get).toList();
	}
}
