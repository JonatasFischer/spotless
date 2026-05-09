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

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/** Direct unit tests for {@link StubPackageFragment}. */
class StubPackageFragmentTest {

	@Test
	void instanceIsAccessibleAndStable() {
		StubPackageFragment a = StubPackageFragment.INSTANCE;
		StubPackageFragment b = StubPackageFragment.INSTANCE;
		assertThat(a).isNotNull();
		assertThat(a).isSameAs(b);
	}

	@Test
	void elementNameIsEmptyForDefaultPackage() {
		assertThat(StubPackageFragment.INSTANCE.getElementName()).isEmpty();
	}

	@Test
	void isDefaultPackageIsTrue() {
		assertThat(StubPackageFragment.INSTANCE.isDefaultPackage()).isTrue();
	}

	@Test
	void internalIsValidPackageNameIsTrue() throws Exception {
		// Method is protected; reach it via reflection.
		Method m = StubPackageFragment.class.getDeclaredMethod("internalIsValidPackageName");
		m.setAccessible(true);
		assertThat((boolean) m.invoke(StubPackageFragment.INSTANCE)).isTrue();
	}

	@Test
	void parentIsWiredToStubJavaProject() {
		// JavaElement#getParent reads the protected `parent` field that we set via reflection in
		// the static factory; verify the wiring took effect.
		assertThat(StubPackageFragment.INSTANCE.getParent()).isSameAs(StubJavaProject.INSTANCE);
	}

	@Test
	void createInstanceThrowsForUnknownParentField() {
		// Drives the catch block that translates a JDT API rename into a clear IllegalStateException.
		assertThatThrownBy(() -> StubPackageFragment.createInstance("definitelyNotAField"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JavaElement#definitelyNotAField")
				.hasCauseInstanceOf(NoSuchFieldException.class);
	}

	@Test
	void createInstanceWithRealFieldNameReturnsAWiredFragment() {
		// Success path: kills the NullReturnValsMutator on `return inst` (which would otherwise
		// survive because the existing tests only look at the singleton built during static init).
		StubPackageFragment built = StubPackageFragment.createInstance("parent");
		assertThat(built).isNotNull();
		// The parent field must point at the stub JavaProject.
		assertThat(built.getParent()).isSameAs(StubJavaProject.INSTANCE);
	}
}
