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

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StubPackageFragmentTest {

	@ParameterizedTest
	@ValueSource(strings = {"", "test", "com.example.application"})
	void representsAnExistingSourcePackage(String name) {
		StubPackageFragment fragment = new StubPackageFragment(name);
		assertThat(fragment.getElementName()).isEqualTo(name);
		assertThat(fragment.isDefaultPackage()).isEqualTo(name.isEmpty());
		assertThat(fragment.exists()).isTrue();
		assertThat(fragment.internalIsValidPackageName()).isTrue();
		assertThat(fragment.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT)).isSameAs(StubPackageFragmentRoot.INSTANCE);
		assertThat(fragment.getJavaProject()).isSameAs(StubJavaProject.INSTANCE);
		assertThat(fragment).isEqualTo(new StubPackageFragment(name));
		assertThat(fragment.hashCode()).isEqualTo(new StubPackageFragment(name).hashCode());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "com.example"})
	void sourceRootHasAStableResourceAndPath(String name) {
		StubPackageFragment fragment = new StubPackageFragment(name);
		StubPackageFragmentRoot root = (StubPackageFragmentRoot) fragment.getParent();
		assertThat(root.getKind()).isEqualTo(IPackageFragmentRoot.K_SOURCE);
		assertThat(root.isArchive()).isFalse();
		assertThat(root.exists()).isTrue();
		assertThat(root.getResource()).isSameAs(StubJavaProject.INSTANCE.getProject());
		assertThat(root.getPath().toPortableString()).isEqualTo("/" + CleanUpConstants.STUB_PROJECT_NAME);
		assertThat(fragment.getPath()).isEqualTo(root.getPath().append(name.replace('.', '/')));
	}
}
