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

import org.eclipse.jdt.internal.core.PackageFragment;

/** Package of the current source, read from its AST rather than inferred from a filesystem path. */
final class StubPackageFragment extends PackageFragment {

	StubPackageFragment(String packageName) {
		this(packageName, StubPackageFragmentRoot.INSTANCE);
	}

	StubPackageFragment(String packageName, StubJavaProject project) {
		this(packageName, new StubPackageFragmentRoot(project));
	}

	private StubPackageFragment(String packageName, StubPackageFragmentRoot root) {
		super(root, packageName.isEmpty() ? new String[0] : packageName.split("\\."));
	}

	/** The parser supplies the package name; avoid Eclipse's resource-based validation. */
	@Override
	protected boolean internalIsValidPackageName() {
		return true;
	}

	/** This package contains the source being transformed; no resource lookup is needed. */
	@Override
	public boolean exists() {
		return true;
	}
}
