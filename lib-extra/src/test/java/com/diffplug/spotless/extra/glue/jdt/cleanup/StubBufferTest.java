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

import java.lang.reflect.Constructor;

import org.eclipse.jdt.core.IBuffer;
import org.junit.jupiter.api.Test;

/** Drives the package-private {@link StubBuffer} through reflection to exercise every IBuffer method. */
class StubBufferTest {

	private static IBuffer newBuffer(String source) throws Exception {
		Constructor<?> c = StubBuffer.class.getDeclaredConstructor(String.class);
		c.setAccessible(true);
		return (IBuffer) c.newInstance(source);
	}

	@Test
	void initialContents() throws Exception {
		IBuffer b = newBuffer("hello");
		assertThat(b.getContents()).isEqualTo("hello");
		assertThat(b.getLength()).isEqualTo(5);
		assertThat(b.getCharacters()).containsExactly('h', 'e', 'l', 'l', 'o');
		assertThat(b.getChar(1)).isEqualTo('e');
		assertThat(b.getText(1, 3)).isEqualTo("ell");
	}

	@Test
	void appendStringAndCharArray() throws Exception {
		IBuffer b = newBuffer("a");
		b.append("b");
		b.append(new char[]{'c', 'd'});
		assertThat(b.getContents()).isEqualTo("abcd");
	}

	@Test
	void replaceWithString() throws Exception {
		IBuffer b = newBuffer("abcdef");
		b.replace(1, 2, "XX"); // replace "bc" with "XX"
		assertThat(b.getContents()).isEqualTo("aXXdef");
	}

	@Test
	void replaceWithCharArray() throws Exception {
		IBuffer b = newBuffer("abcdef");
		b.replace(1, 2, new char[]{'Y', 'Y'});
		assertThat(b.getContents()).isEqualTo("aYYdef");
	}

	@Test
	void setContentsString() throws Exception {
		IBuffer b = newBuffer("old");
		b.setContents("new content");
		assertThat(b.getContents()).isEqualTo("new content");
	}

	@Test
	void setContentsCharArray() throws Exception {
		IBuffer b = newBuffer("old");
		b.setContents(new char[]{'X', 'Y', 'Z'});
		assertThat(b.getContents()).isEqualTo("XYZ");
	}

	@Test
	void noOpMethodsReturnSafeDefaults() throws Exception {
		IBuffer b = newBuffer("hi");
		assertThat(b.getOwner()).isNull();
		assertThat(b.getUnderlyingResource()).isNull();
		assertThat(b.hasUnsavedChanges()).isFalse();
		assertThat(b.isClosed()).isFalse();
		assertThat(b.isReadOnly()).isFalse();
		// Listener registration / save / close are no-ops; just verify no exception.
		b.addBufferChangedListener(null);
		b.removeBufferChangedListener(null);
		b.close();
		b.save(null, false);
	}
}
