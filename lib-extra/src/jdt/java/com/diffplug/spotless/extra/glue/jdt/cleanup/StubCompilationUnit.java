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

import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.DefaultWorkingCopyOwner;
import org.eclipse.jdt.internal.core.JavaElement;

import com.diffplug.spotless.extra.glue.jdt.SuppressFBWarnings;

/**
 * Headless stand-in for {@link CompilationUnit} that wraps a Java source string in memory.
 *
 * <p>The constructor attaches the unit to its package under an in-memory source root. Buffer
 * and resource access are supplied locally, while parent traversal and {@link JavaElement#hashCode()}
 * use the normal JDT model hierarchy.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean-up context")
public final class StubCompilationUnit extends CompilationUnit {

	private final StubBuffer buffer;
	private final IFile fakeFile;
	private final String unitName;

	public StubCompilationUnit(String source, String unitName) {
		this(source, unitName, new StubPackageFragment(""));
	}

	StubCompilationUnit(String source, String unitName, String packageName, Map<String, String> compilerOptions) {
		this(source, unitName, new StubPackageFragment(packageName, new StubJavaProject(compilerOptions)));
	}

	private StubCompilationUnit(String source, String unitName, StubPackageFragment parent) {
		// owner must be non-null — JavaElement.hashCode() is final and dereferences it via
		// calculateHashCode().
		super(parent, Objects.requireNonNull(unitName, "unitName"), DefaultWorkingCopyOwner.PRIMARY);
		this.buffer = new StubBuffer(Objects.requireNonNull(source, "source"));
		this.fakeFile = StubProxies.createFakeFile();
		this.unitName = unitName;
	}

	@Override
	public IBuffer getBuffer() {
		return buffer;
	}

	/**
	 * Honour {@code inheritJavaCoreOptions}: when true, merge the Spotless-pinned options on top of
	 * the workbench-wide defaults so cleanups inspecting unrelated keys (e.g.
	 * {@code COMPILER_PB_RAW_TYPE_REFERENCE}) get the correct answer. The default impl in
	 * {@link CompilationUnit} would otherwise return only Spotless's pin, breaking the contract.
	 */
	@Override
	public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
		return getJavaProject().getOptions(inheritJavaCoreOptions);
	}

	@Override
	public ICompilationUnit getPrimary() {
		return this;
	}

	@Override
	public String getElementName() {
		return unitName;
	}

	/**
	 * Bypass the parent's fallback path which calls
	 * {@code getResourceContentsAsCharArray(getResource())} — that NPEs on the synthetic IFile.
	 */
	@Override
	public char[] getContents() {
		return buffer.getCharacters();
	}

	@Override
	public IResource getResource() {
		return fakeFile;
	}
}
