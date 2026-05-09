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

import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;

/** Direct unit tests for {@link StubCompilationUnit}. */
class StubCompilationUnitTest {

	private static final String SOURCE = "public class Foo {}";
	private static final String UNIT_NAME = "Foo.java";

	@Test
	void constructorRejectsNullSource() {
		assertThatThrownBy(() -> new StubCompilationUnit(null, UNIT_NAME))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("source");
	}

	@Test
	void constructorRejectsNullUnitName() {
		assertThatThrownBy(() -> new StubCompilationUnit(SOURCE, null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("unitName");
	}

	@Test
	void getBufferReturnsStubBufferWithSource() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getBuffer()).isInstanceOf(StubBuffer.class);
		assertThat(unit.getBuffer().getContents()).isEqualTo(SOURCE);
	}

	@Test
	void getJavaProjectReturnsTheStubProject() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getJavaProject()).isSameAs(StubJavaProject.INSTANCE);
	}

	@Test
	void getOptionsTrueIncludesJavaCoreDefaults() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		Map<String, String> opts = unit.getOptions(true);
		assertThat(opts).containsEntry(JavaCore.COMPILER_SOURCE, CleanUpConstants.JAVA_LEVEL);
		// Some unrelated key from JavaCore must come through too.
		assertThat(opts).containsKey(JavaCore.COMPILER_PB_RAW_TYPE_REFERENCE);
	}

	@Test
	void getOptionsFalseReturnsExactlySpotlessPins() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getOptions(false)).isEqualTo(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	}

	@Test
	void getPrimaryReturnsItself() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getPrimary()).isSameAs(unit);
	}

	@Test
	void getElementNameIsTheConstructorArgument() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getElementName()).isEqualTo(UNIT_NAME);
	}

	@Test
	void getContentsReturnsTheBufferCharacters() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(new String(unit.getContents())).isEqualTo(SOURCE);
	}

	@Test
	void getResourceReturnsTheFakeFile() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getResource()).isNotNull();
		assertThat(unit.getResource().getName()).isEqualTo(CleanUpConstants.DEFAULT_UNIT_NAME);
	}

	@Test
	void getParentIsTheStubPackageFragment() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		assertThat(unit.getParent()).isSameAs(StubPackageFragment.INSTANCE);
	}

	@Test
	void hashCodeIsStableAcrossCalls() {
		StubCompilationUnit unit = new StubCompilationUnit(SOURCE, UNIT_NAME);
		// JavaElement#hashCode is final and dereferences `owner`; we set `owner` to PRIMARY in the
		// constructor specifically so this does not NPE.
		int firstCall = unit.hashCode();
		int secondCall = unit.hashCode();
		assertThat(firstCall).isEqualTo(secondCall);
	}
}
