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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

/**
 * Helpers that build {@link Proxy}-based stub instances of Eclipse Resources interfaces
 * ({@link IFile}, {@link IProject}). The stubs return safe defaults for every method so the
 * Eclipse JDT cleanup pipeline does not NPE when it crosses into the workspace API even though
 * we are running outside of one.
 */
final class StubProxies {

	private StubProxies() {}

	/**
	 * Returns the conventional default value for a method's primitive return type, or {@code null}
	 * for object types. Centralised so the same fallback logic is not duplicated across stubs.
	 */
	private static Object defaultReturnValue(Class<?> returnType) {
		if (returnType == boolean.class)
			return Boolean.FALSE;
		if (returnType == int.class || returnType == short.class || returnType == byte.class)
			return 0;
		if (returnType == long.class)
			return 0L;
		if (returnType == double.class)
			return 0.0d;
		if (returnType == float.class)
			return 0.0f;
		if (returnType == char.class)
			return '\0';
		return null;
	}

	/**
	 * Creates an {@link InvocationHandler} that consults {@code overrides} first and falls back to
	 * sensible defaults ({@code Object#hashCode}, {@code equals}, {@code toString}) and primitive
	 * defaults from {@link #defaultReturnValue(Class)} for everything else.
	 */
	private static InvocationHandler buildHandler(Map<String, Object> overrides, String label) {
		return (proxy, method, args) -> {
			Object override = overrides.get(method.getName());
			if (override != null) {
				return override;
			}
			switch (method.getName()) {
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return proxy == args[0];
			case "toString":
				return label;
			default:
				return defaultReturnValue(method.getReturnType());
			}
		};
	}

	/**
	 * No-op {@link IFile} that satisfies {@code TextFileChange.<init>}. The cleanup pipeline never
	 * reads the file's contents — we extract the {@code TextEdit} from the change and apply it
	 * against an in-memory {@code Document}.
	 */
	static IFile createFakeFile() {
		Map<String, Object> overrides = Map.of(
				"getFileExtension", "java",
				"getName", CleanUpConstants.DEFAULT_UNIT_NAME,
				"exists", Boolean.TRUE,
				"getModificationStamp", 0L,
				"getLocalTimeStamp", 0L);
		return (IFile) Proxy.newProxyInstance(
				IFile.class.getClassLoader(),
				new Class<?>[]{IFile.class},
				buildHandler(overrides, "StubIFile[" + CleanUpConstants.DEFAULT_UNIT_NAME + "]"));
	}

	/**
	 * No-op {@link IProject} that satisfies {@code new ProjectScope(IProject)}. Cleanups query
	 * preferences via {@code ProjectScope}; with this stub the {@code Preferences} machinery falls
	 * back to instance-scope defaults instead of NPEing.
	 */
	static IProject createStubProject() {
		Map<String, Object> overrides = Map.of(
				"getName", CleanUpConstants.STUB_PROJECT_NAME,
				"exists", Boolean.TRUE,
				"isAccessible", Boolean.TRUE,
				"isOpen", Boolean.TRUE);
		return (IProject) Proxy.newProxyInstance(
				IProject.class.getClassLoader(),
				new Class<?>[]{IProject.class},
				buildHandler(overrides, "StubIProject[" + CleanUpConstants.STUB_PROJECT_NAME + "]"));
	}

	/**
	 * Reflectively writes {@code value} to {@code declaringClass#fieldName} on {@code target}.
	 * Used by the stub Eclipse-model classes whose own constructors do not let us pass the
	 * required parent references in.
	 */
	static void setField(Object target, Class<?> declaringClass, String fieldName, Object value) throws ReflectiveOperationException {
		Field f = declaringClass.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(target, value);
	}
}
