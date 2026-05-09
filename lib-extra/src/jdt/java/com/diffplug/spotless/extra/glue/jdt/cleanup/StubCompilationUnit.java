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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.DefaultWorkingCopyOwner;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.JavaProject;

import com.diffplug.spotless.extra.glue.jdt.SuppressFBWarnings;

/**
 * Headless stand-in for {@link CompilationUnit} that wraps a Java source string in memory.
 *
 * <p>The Eclipse JDT cleanup pipeline navigates {@code CompilationUnit#getJavaProject()},
 * {@link #getResource()}, {@link #getParent()} and {@link #hashCode()} (final, inherited from
 * {@link JavaElement}, which dereferences the {@code owner} field). Each of those is overridden
 * here so the pipeline runs to completion without an Eclipse workspace.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean-up context")
public final class StubCompilationUnit extends CompilationUnit {

	private final StubBuffer buffer;
	private final IFile fakeFile;
	private final String unitName;

	public StubCompilationUnit(String source, String unitName) {
		// owner must be non-null — JavaElement.hashCode() is final and dereferences it via
		// calculateHashCode().
		super(null, Objects.requireNonNull(unitName, "unitName"), DefaultWorkingCopyOwner.PRIMARY);
		this.buffer = new StubBuffer(Objects.requireNonNull(source, "source"));
		this.fakeFile = StubProxies.createFakeFile();
		this.unitName = unitName;
	}

	@Override
	public IBuffer getBuffer() {
		return buffer;
	}

	@Override
	public JavaProject getJavaProject() {
		return StubJavaProject.INSTANCE;
	}

	/**
	 * Honour {@code inheritJavaCoreOptions}: when true, merge the Spotless-pinned options on top of
	 * the workbench-wide defaults so cleanups inspecting unrelated keys (e.g.
	 * {@code COMPILER_PB_RAW_TYPE_REFERENCE}) get the correct answer. The default impl in
	 * {@link CompilationUnit} would otherwise return only Spotless's pin, breaking the contract.
	 */
	@Override
	public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
		if (!inheritJavaCoreOptions) {
			return CleanUpConstants.DEFAULT_COMPILER_OPTIONS;
		}
		Map<String, String> merged = new HashMap<>(JavaCore.getOptions());
		merged.putAll(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
		return merged;
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

	@Override
	public JavaElement getParent() {
		return StubPackageFragment.INSTANCE;
	}
}
