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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/** Direct unit tests for {@link UnitNameInferrer}. The class is package-private so we drive it via reflection. */
class UnitNameInferrerTest {

	private static String infer(String source) throws Exception {
		Method m = UnitNameInferrer.class.getDeclaredMethod("infer", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, source);
	}

	@Test
	void publicClass() throws Exception {
		assertThat(infer("public class Foo {}")).isEqualTo("Foo.java");
	}

	@Test
	void publicInterface() throws Exception {
		assertThat(infer("public interface Bar {}")).isEqualTo("Bar.java");
	}

	@Test
	void publicEnum() throws Exception {
		assertThat(infer("public enum Color { RED, GREEN }")).isEqualTo("Color.java");
	}

	@Test
	void publicRecord() throws Exception {
		assertThat(infer("public record Point(int x, int y) {}")).isEqualTo("Point.java");
	}

	@Test
	void publicAbstractClass() throws Exception {
		assertThat(infer("public abstract class Shape {}")).isEqualTo("Shape.java");
	}

	@Test
	void publicFinalClass() throws Exception {
		assertThat(infer("public final class Final1 {}")).isEqualTo("Final1.java");
	}

	@Test
	void publicSealedClass() throws Exception {
		assertThat(infer("public sealed class Sealed permits A {}")).isEqualTo("Sealed.java");
	}

	@Test
	void publicNonSealedClass() throws Exception {
		assertThat(infer("public non-sealed class Foo extends Sealed {}")).isEqualTo("Foo.java");
	}

	@Test
	void publicStaticNestedClassFollowedByOuter() throws Exception {
		// First public-static type wins
		assertThat(infer("public static class Inner {} class Outer {}")).isEqualTo("Inner.java");
	}

	@Test
	void packagePrivateOnlyFallsBackToDefault() throws Exception {
		assertThat(infer("class PackageOnly {}")).isEqualTo(CleanUpConstants.DEFAULT_UNIT_NAME);
	}

	@Test
	void blankSourceFallsBackToDefault() throws Exception {
		assertThat(infer("")).isEqualTo(CleanUpConstants.DEFAULT_UNIT_NAME);
		assertThat(infer("   \n\t  ")).isEqualTo(CleanUpConstants.DEFAULT_UNIT_NAME);
	}

	@Test
	void nullSourceThrowsNullPointerException() throws Exception {
		// Non-blank vs null is now an observable difference (NPE vs DEFAULT) — kills the
		// RemoveConditional mutant that would otherwise turn the null check into a no-op.
		assertThatThrownBy(() -> infer((String) null))
				.isInstanceOf(InvocationTargetException.class)
				.cause()
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("source");
	}

	@Test
	void multipleModifiersInOrder() throws Exception {
		assertThat(infer("public abstract final class WeirdButLegal {}"))
				.isEqualTo("WeirdButLegal.java");
	}

	@Test
	void firstPublicTypeWinsOverSubsequent() throws Exception {
		String src = "package x;\npublic class First {}\npublic class Second {}";
		assertThat(infer(src)).isEqualTo("First.java");
	}
}
