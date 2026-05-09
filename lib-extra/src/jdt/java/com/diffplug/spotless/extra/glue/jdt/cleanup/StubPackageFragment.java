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

import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.PackageFragment;

/**
 * Headless stand-in for {@link PackageFragment} representing the default (unnamed) package.
 *
 * <p>{@link PackageFragment#PackageFragment(org.eclipse.jdt.internal.core.PackageFragmentRoot, String[])}
 * calls {@code internalIsValidPackageName()} which dereferences the parent
 * {@code PackageFragmentRoot} resource — null in our stub — so we override that hook.
 *
 * <p>The {@link JavaElement#parent} field is wired to {@link StubJavaProject#INSTANCE} via
 * reflection so that {@code getParent()} answers a non-null {@link JavaElement}; downstream
 * Eclipse JDT internals call {@code getParent().hashCode()} on the returned value.
 */
final class StubPackageFragment extends PackageFragment {

	static final StubPackageFragment INSTANCE = createInstance("parent");

	private StubPackageFragment() {
		super(null, new String[0]);
	}

	/**
	 * Builds a {@link StubPackageFragment} and wires the supplied {@code parentFieldName} on
	 * {@link JavaElement} via reflection. Package-private and parameterised so unit tests can
	 * pass an unknown field name to verify the failure path.
	 */
	static StubPackageFragment createInstance(String parentFieldName) {
		StubPackageFragment inst = new StubPackageFragment();
		try {
			StubProxies.setField(inst, JavaElement.class, parentFieldName, StubJavaProject.INSTANCE);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(
					"Eclipse JDT API changed: JavaElement#" + parentFieldName + " is missing; please update spotless",
					e);
		}
		return inst;
	}

	@Override
	protected boolean internalIsValidPackageName() {
		return true;
	}

	@Override
	public String getElementName() {
		return "";
	}

	@Override
	public boolean isDefaultPackage() {
		return true;
	}
}
