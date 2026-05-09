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

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaModelException;

import com.diffplug.spotless.extra.glue.jdt.SuppressFBWarnings;

/**
 * Minimal mutable buffer wrapping a Java source string. Used by the headless JDT cleanup pipeline
 * — never registered with the workbench {@code BufferManager}, only consulted directly by our
 * stub compilation unit.
 *
 * <p>Mutating methods use {@link StringBuilder} so cumulative O(n²) cost is avoided when JDT
 * applies many small edits.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean-up context")
final class StubBuffer implements IBuffer {

	private final StringBuilder contents;

	StubBuffer(String source) {
		this.contents = new StringBuilder(source);
	}

	@Override
	public void addBufferChangedListener(IBufferChangedListener listener) {}

	@Override
	public void append(char[] text) {
		contents.append(text);
	}

	@Override
	public void append(String text) {
		contents.append(text);
	}

	@Override
	public void close() {}

	@Override
	public char getChar(int position) {
		return contents.charAt(position);
	}

	@Override
	public char[] getCharacters() {
		char[] result = new char[contents.length()];
		contents.getChars(0, contents.length(), result, 0);
		return result;
	}

	@Override
	public String getContents() {
		return contents.toString();
	}

	@Override
	public int getLength() {
		return contents.length();
	}

	@Override
	public IOpenable getOwner() {
		return null;
	}

	@Override
	public String getText(int offset, int length) {
		return contents.substring(offset, offset + length);
	}

	@Override
	public IResource getUnderlyingResource() {
		return null;
	}

	@Override
	public boolean hasUnsavedChanges() {
		return false;
	}

	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public void removeBufferChangedListener(IBufferChangedListener listener) {}

	@Override
	public void replace(int position, int length, char[] text) {
		contents.replace(position, position + length, new String(text));
	}

	@Override
	public void replace(int position, int length, String text) {
		contents.replace(position, position + length, text);
	}

	@Override
	public void save(IProgressMonitor progress, boolean force) throws JavaModelException {}

	@Override
	public void setContents(char[] newContents) {
		contents.setLength(0);
		contents.append(newContents);
	}

	@Override
	public void setContents(String newContents) {
		contents.setLength(0);
		contents.append(newContents);
	}
}
