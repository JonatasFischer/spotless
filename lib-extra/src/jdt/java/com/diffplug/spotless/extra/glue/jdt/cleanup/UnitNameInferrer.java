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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks a unit name (e.g. {@code "Foo.java"}) for the AST parser to use, derived from the first
 * public type declaration in the source.
 *
 * <p>If the unit name does not match the file's public type, the compiler emits
 * {@code "The public type X must be defined in its own file"} and that single error suppresses
 * every other problem (including {@code IProblem.UnusedImport}). Picking the right name is
 * therefore essential for cleanups like {@code remove_unused_imports} to fire.
 */
final class UnitNameInferrer {

	/**
	 * Matches the first {@code public class|interface|record|enum Foo} declaration. Tolerates
	 * intervening modifier keywords ({@code abstract}, {@code final}, {@code sealed},
	 * {@code non-sealed}, {@code static}).
	 */
	private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile(
			"public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*"
					+ "(?:class|interface|enum|record)\\s+(\\w+)");

	private UnitNameInferrer() {}

	/**
	 * @return the inferred unit name (e.g. {@code "Foo.java"}) or
	 *         {@link CleanUpConstants#DEFAULT_UNIT_NAME} when no public type is found.
	 */
	static String infer(String source) {
		Matcher m = PUBLIC_TYPE_PATTERN.matcher(source);
		if (m.find()) {
			return m.group(1) + ".java";
		}
		return CleanUpConstants.DEFAULT_UNIT_NAME;
	}
}
