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

import java.lang.reflect.Method;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.Test;

/** Drives the package-private {@link StubProxies} factories. */
class StubProxiesTest {

	private static IFile fakeFile() throws Exception {
		Method m = StubProxies.class.getDeclaredMethod("createFakeFile");
		m.setAccessible(true);
		return (IFile) m.invoke(null);
	}

	private static IProject stubProject() throws Exception {
		Method m = StubProxies.class.getDeclaredMethod("createStubProject");
		m.setAccessible(true);
		return (IProject) m.invoke(null);
	}

	@Test
	void fakeFileOverrides() throws Exception {
		IFile f = fakeFile();
		assertThat(f.getFileExtension()).isEqualTo("java");
		assertThat(f.getName()).isEqualTo(CleanUpConstants.DEFAULT_UNIT_NAME);
		assertThat(f.exists()).isTrue();
		assertThat(f.getModificationStamp()).isZero();
		assertThat(f.getLocalTimeStamp()).isZero();
		assertThat(f.toString()).contains("StubIFile");
	}

	@Test
	void fakeFilePrimitiveDefaults() throws Exception {
		// Methods we don't explicitly override return primitive defaults / null.
		IFile f = fakeFile();
		assertThat(f.getCharset()).isNull(); // String return → null
		assertThat(f.isReadOnly()).isFalse(); // boolean → false
		assertThat(f.isLinked()).isFalse();
		assertThat(f.isVirtual()).isFalse();
		assertThat(f.isHidden()).isFalse();
		assertThat(f.isDerived()).isFalse();
		assertThat(f.isPhantom()).isFalse();
		assertThat(f.isAccessible()).isFalse();
		assertThat(f.isTeamPrivateMember()).isFalse();
	}

	@Test
	void fakeFileEqualsAndHashCode() throws Exception {
		IFile f1 = fakeFile();
		IFile f2 = fakeFile();
		// Identity-based equals: a stub equals only itself.
		assertThat(f1.equals(f1)).isTrue();
		assertThat(f1).isNotEqualTo(f2);
		// hashCode is identity-based and stable across invocations.
		int firstCall = f1.hashCode();
		int secondCall = f1.hashCode();
		assertThat(firstCall).isEqualTo(secondCall);
		// hashCode values for distinct stubs differ (vanishingly small chance of collision).
		assertThat(f1.hashCode()).isNotEqualTo(f2.hashCode());
	}

	@Test
	void stubProjectOverrides() throws Exception {
		IProject p = stubProject();
		assertThat(p.getName()).isEqualTo(CleanUpConstants.STUB_PROJECT_NAME);
		assertThat(p.exists()).isTrue();
		assertThat(p.isAccessible()).isTrue();
		assertThat(p.isOpen()).isTrue();
		assertThat(p.toString()).contains("StubIProject");
	}

	@Test
	void stubProjectPrimitiveDefaults() throws Exception {
		IProject p = stubProject();
		assertThat(p.isReadOnly()).isFalse();
		assertThat(p.isLinked()).isFalse();
		assertThat(p.isVirtual()).isFalse();
		assertThat(p.isHidden()).isFalse();
		assertThat(p.isDerived()).isFalse();
		assertThat(p.isPhantom()).isFalse();
		assertThat(p.isTeamPrivateMember()).isFalse();
	}

	@Test
	void stubProjectEqualsAndHashCode() throws Exception {
		IProject p1 = stubProject();
		IProject p2 = stubProject();
		assertThat(p1.equals(p1)).isTrue();
		assertThat(p1).isNotEqualTo(p2);
		int firstCall = p1.hashCode();
		int secondCall = p1.hashCode();
		assertThat(firstCall).isEqualTo(secondCall);
		assertThat(p1.hashCode()).isNotEqualTo(p2.hashCode());
	}

	// =========================================================================
	// defaultReturnValue: must produce a sensible "zero" for every primitive
	// return type and null for any reference type. Covered exhaustively because
	// every branch matters for mutation testing.
	// =========================================================================

	private static Object defaultReturnValue(Class<?> returnType) throws Exception {
		Method m = StubProxies.class.getDeclaredMethod("defaultReturnValue", Class.class);
		m.setAccessible(true);
		return m.invoke(null, returnType);
	}

	@Test
	void defaultBooleanIsFalse() throws Exception {
		assertThat(defaultReturnValue(boolean.class)).isEqualTo(Boolean.FALSE);
	}

	@Test
	void defaultIntIsZero() throws Exception {
		assertThat(defaultReturnValue(int.class)).isEqualTo(0);
	}

	@Test
	void defaultShortIsZero() throws Exception {
		assertThat(defaultReturnValue(short.class)).isEqualTo((short) 0);
	}

	@Test
	void defaultByteIsZero() throws Exception {
		assertThat(defaultReturnValue(byte.class)).isEqualTo((byte) 0);
	}

	@Test
	void defaultLongIsZeroL() throws Exception {
		assertThat(defaultReturnValue(long.class)).isEqualTo(0L);
	}

	@Test
	void defaultDoubleIsZeroD() throws Exception {
		assertThat(defaultReturnValue(double.class)).isEqualTo(0.0d);
	}

	@Test
	void defaultFloatIsZeroF() throws Exception {
		assertThat(defaultReturnValue(float.class)).isEqualTo(0.0f);
	}

	@Test
	void defaultCharIsNul() throws Exception {
		assertThat(defaultReturnValue(char.class)).isEqualTo('\0');
	}

	@Test
	void defaultObjectIsNull() throws Exception {
		assertThat(defaultReturnValue(String.class)).isNull();
		assertThat(defaultReturnValue(Object.class)).isNull();
	}

	@Test
	void defaultVoidIsNull() throws Exception {
		// void.class is not a primitive in the if-chain; falls through to "return null"
		assertThat(defaultReturnValue(void.class)).isNull();
	}
}
