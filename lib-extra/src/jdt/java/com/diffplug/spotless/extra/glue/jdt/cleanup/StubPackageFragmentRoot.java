/*
 * Copyright 2026 DiffPlug
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

import org.eclipse.jdt.internal.core.PackageFragmentRoot;

/** In-memory source root located at the synthetic project's root. */
final class StubPackageFragmentRoot extends PackageFragmentRoot {

	static final StubPackageFragmentRoot INSTANCE = new StubPackageFragmentRoot(StubJavaProject.INSTANCE);

	StubPackageFragmentRoot(StubJavaProject project) {
		super(project.getProject(), project);
	}

	@Override
	public int getKind() {
		return K_SOURCE;
	}

	@Override
	public boolean exists() {
		return true;
	}
}
