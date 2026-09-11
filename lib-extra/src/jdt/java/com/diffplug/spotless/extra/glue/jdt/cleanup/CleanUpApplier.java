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

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;

/**
 * Applies a single {@link ICleanUp} to a Java source string by parsing the source, asking the
 * cleanup for a fix, and re-applying the resulting {@link TextEdit} to an in-memory document.
 *
 * <p>Centralises the AST parsing and reflection-based wiring so that
 * {@code EclipseJdtCleanUpImpl} only orchestrates and never has to know which JDT internal field
 * needs to be poked next.
 */
public final class CleanUpApplier {

	private static final IProgressMonitor MONITOR = new NullProgressMonitor();

	/**
	 * Cached reflective handle to {@code CompilationUnit.typeRoot}. Looked up once and reused per
	 * AST creation. Fail-fast at class init if the field has been renamed in a future JDT release
	 * — that is a contract change we want to surface immediately rather than swallow at runtime.
	 */
	private static final Field AST_TYPE_ROOT_FIELD = locateTypeRootField("typeRoot");

	/**
	 * Looks up the supplied field on {@link CompilationUnit} and makes it accessible. Package
	 * private so tests can verify behaviour for both real and synthetic field names without
	 * touching production state.
	 */
	static Field locateTypeRootField(String fieldName) {
		try {
			Field f = CompilationUnit.class.getDeclaredField(fieldName);
			f.setAccessible(true);
			return f;
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(
					"Eclipse JDT API changed: org.eclipse.jdt.core.dom.CompilationUnit#" + fieldName + " is missing; "
							+ "please update spotless to match the new layout",
					e);
		}
	}

	private CleanUpApplier() {}

	/**
	 * Runs {@code cleanUp} against {@code source} and returns the resulting source. If the cleanup
	 * declines to produce a fix, throws an exception, or yields a no-op edit, the original
	 * {@code source} is returned unchanged.
	 */
	public static String apply(ICleanUp cleanUp, String source) {
		return apply(cleanUp, source, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, new CleanUpDiagnostics(false), null);
	}

	public static String apply(ICleanUp cleanUp, String source, Map<String, String> compilerOptions,
			CleanUpDiagnostics diagnostics, File file) {
		Objects.requireNonNull(cleanUp, "cleanUp");
		Objects.requireNonNull(source, "source");
		String unitName = UnitNameInferrer.infer(source);
		String phase = "parsing";
		try {
			CompilationUnit ast = parse(source, mergeCompilerOptions(cleanUp, compilerOptions), unitName);
			ICompilationUnit stubUnit = (ICompilationUnit) ast.getTypeRoot();

			CleanUpContext context = new CleanUpContext(stubUnit, ast);
			phase = "precondition check";
			checkStatus(cleanUp.checkPreConditions(stubUnit.getJavaProject(), new ICompilationUnit[]{stubUnit}, MONITOR));

			phase = "creating fix";
			ICleanUpFix fix = cleanUp.createFix(context);
			if (fix == null) {
				return source;
			}

			// ICleanUpFix.createChange is contractually a CompilationUnitChange (which extends
			// TextChange), so we can read the TextEdit directly without an instanceof guard.
			phase = "creating change";
			CompilationUnitChange change = fix.createChange(MONITOR);
			TextEdit edit = change.getEdit();
			if (edit == null) {
				return source;
			}
			phase = "postcondition check";
			checkStatus(cleanUp.checkPostConditions(MONITOR));

			phase = "applying edit";
			IDocument doc = new Document(source);
			edit.apply(doc);
			return doc.get();
		} catch (CoreException | BadLocationException | RuntimeException e) {
			diagnostics.skipped(cleanUp.getClass().getSimpleName(), file,
					phase + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
			return source;
		}
	}

	/**
	 * Parses {@code source} into an AST configured with both the project default compiler options
	 * and the cleanup-required problem options (e.g. {@code COMPILER_PB_UNUSED_IMPORT=WARNING}).
	 *
	 * <p>Package-private so tests can assert the resulting AST has the configuration required by
	 * downstream cleanups (bindings resolved, recovery enabled, etc.) — covering PIT mutants that
	 * would otherwise survive by stripping the {@code parser.setX} calls.
	 */
	static CompilationUnit parse(String source, Map<String, String> compilerOptions, String unitName) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(source.toCharArray());
		parser.setCompilerOptions(compilerOptions);
		// Bindings are required by most cleanups (lambda conversion, final-parameter detection,
		// pattern-matching-for-instanceof, ...). The empty environment makes the parser resolve
		// types from the runtime JRE only.
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		parser.setUnitName(unitName);
		parser.setEnvironment(new String[0], new String[0], new String[0], true);

		CompilationUnit ast = (CompilationUnit) parser.createAST(MONITOR);
		String packageName = ast.getPackage() == null ? "" : ast.getPackage().getName().getFullyQualifiedName();
		StubCompilationUnit stubUnit = new StubCompilationUnit(source, unitName, packageName, compilerOptions);
		linkStubAsTypeRoot(ast, stubUnit);
		return ast;
	}

	/**
	 * Wires the synthetic {@link ICompilationUnit} into {@code ast.typeRoot} via reflection so
	 * cleanups can locate it via {@code ast.getTypeRoot()} when constructing a
	 * {@code CompilationUnitChange}. Without it the cast inside JDT's
	 * {@code CompilationUnitRewrite} chokes on a null typeRoot.
	 *
	 * <p>{@link IllegalAccessException} cannot fire because {@link #locateTypeRootField} called
	 * {@code setAccessible(true)} on the field; we translate it to {@link IllegalStateException}
	 * to keep a tidy unchecked signature.
	 *
	 * <p>Package-private so tests can directly verify the wiring effect on the AST.
	 */
	static void linkStubAsTypeRoot(CompilationUnit ast, StubCompilationUnit stubUnit) {
		try {
			AST_TYPE_ROOT_FIELD.set(ast, stubUnit);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("CompilationUnit.typeRoot inaccessible", e);
		}
	}

	/** Merge the current requirements without retaining mutable Eclipse option maps globally. */
	static Map<String, String> mergeCompilerOptions(ICleanUp cleanUp) {
		return mergeCompilerOptions(cleanUp, CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	}

	static Map<String, String> mergeCompilerOptions(ICleanUp cleanUp, Map<String, String> compilerOptions) {
		Map<String, String> required = cleanUp.getRequirements().getCompilerOptions();
		if (required == null || required.isEmpty()) {
			return compilerOptions;
		}
		Map<String, String> merged = new HashMap<>(required);
		merged.putAll(compilerOptions);
		return Map.copyOf(merged);
	}

	private static void checkStatus(RefactoringStatus status) {
		if (status.hasError()) {
			throw new IllegalStateException(status.getEntryWithHighestSeverity().getMessage());
		}
	}
}
