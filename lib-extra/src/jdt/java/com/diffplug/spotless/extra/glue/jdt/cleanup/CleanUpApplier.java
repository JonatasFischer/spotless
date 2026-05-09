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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.TextChange;
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

	private static final Logger LOGGER = Logger.getLogger(CleanUpApplier.class.getName());
	private static final IProgressMonitor MONITOR = new NullProgressMonitor();

	/**
	 * Cached reflective handle to {@code CompilationUnit.typeRoot}. Looked up once at class init
	 * and reused per AST creation.
	 */
	private static final Field AST_TYPE_ROOT_FIELD;

	static {
		Field f = null;
		try {
			f = CompilationUnit.class.getDeclaredField("typeRoot");
			f.setAccessible(true);
		} catch (NoSuchFieldException e) {
			LOGGER.log(Level.FINE, e,
					() -> "Could not locate CompilationUnit.typeRoot; cleanups that need it will be skipped");
		}
		AST_TYPE_ROOT_FIELD = f;
	}

	private CleanUpApplier() {}

	/**
	 * Runs {@code cleanUp} against {@code source} and returns the resulting source. If the cleanup
	 * declines to produce a fix, throws an exception, or yields a no-op edit, the original
	 * {@code source} is returned unchanged.
	 */
	public static String apply(ICleanUp cleanUp, String source) {
		try {
			Map<String, String> compilerOptions = mergeCompilerOptions(cleanUp);
			StubCompilationUnit stubUnit = new StubCompilationUnit(source, UnitNameInferrer.infer(source));
			CompilationUnit ast = parse(source, compilerOptions, stubUnit);

			CleanUpContext context = new CleanUpContext(stubUnit, ast);
			runPreConditionCheck(cleanUp, stubUnit);

			ICleanUpFix fix = cleanUp.createFix(context);
			if (fix == null) {
				return source;
			}

			Change change = fix.createChange(MONITOR);
			if (!(change instanceof TextChange)) {
				return source;
			}
			TextEdit edit = ((TextChange) change).getEdit();
			if (edit == null) {
				return source;
			}

			IDocument doc = new Document(source);
			edit.apply(doc);
			return doc.get();
		} catch (Exception e) {
			// A few cleanups (instanceof pattern matching, switch expressions, classic-for-to-each)
			// reach into Eclipse JDT internals that require a real PackageFragmentRoot/IFile —
			// neither of which we can stub without spinning up a workspace. Logged at FINE so users
			// can opt-in via JUL configuration; the source is left unchanged for that cleanup.
			LOGGER.log(Level.FINE, e,
					() -> "Cleanup " + cleanUp.getClass().getSimpleName() + " skipped: "
							+ e.getClass().getSimpleName() + ": " + e.getMessage());
			return source;
		}
	}

	/**
	 * Parses {@code source} into an AST configured with both the project default compiler options
	 * and the cleanup-required problem options (e.g. {@code COMPILER_PB_UNUSED_IMPORT=WARNING}).
	 */
	private static CompilationUnit parse(String source, Map<String, String> compilerOptions, StubCompilationUnit stubUnit) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(source.toCharArray());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setCompilerOptions(compilerOptions);
		// Bindings are required by most cleanups (lambda conversion, final-parameter detection,
		// pattern-matching-for-instanceof, ...). The empty environment makes the parser resolve
		// types from the runtime JRE only.
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		parser.setUnitName(UnitNameInferrer.infer(source));
		parser.setEnvironment(new String[0], new String[0], new String[0], true);

		CompilationUnit ast = (CompilationUnit) parser.createAST(null);
		linkStubAsTypeRoot(ast, stubUnit);
		return ast;
	}

	/**
	 * Wires the synthetic {@link ICompilationUnit} into {@code ast.typeRoot} via reflection so
	 * cleanups can locate it via {@code ast.getTypeRoot()} when constructing a
	 * {@code CompilationUnitChange}. Without it the cast inside JDT's
	 * {@code CompilationUnitRewrite} chokes on a null typeRoot.
	 */
	private static void linkStubAsTypeRoot(CompilationUnit ast, StubCompilationUnit stubUnit) {
		if (AST_TYPE_ROOT_FIELD == null) {
			return;
		}
		try {
			AST_TYPE_ROOT_FIELD.set(ast, stubUnit);
		} catch (IllegalAccessException e) {
			LOGGER.log(Level.FINE, e,
					() -> "Could not link stub ICompilationUnit into AST.typeRoot");
		}
	}

	private static Map<String, String> mergeCompilerOptions(ICleanUp cleanUp) {
		Map<String, String> required = cleanUp.getRequirements().getCompilerOptions();
		if (required == null || required.isEmpty()) {
			return CleanUpConstants.DEFAULT_COMPILER_OPTIONS;
		}
		Map<String, String> merged = new HashMap<>(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
		merged.putAll(required);
		return merged;
	}

	/**
	 * Best-effort precondition check. The IJavaProject argument is intentionally null because we
	 * have no real project; cleanups that handle null gracefully (almost all) succeed, the rest
	 * are caught and logged.
	 */
	private static void runPreConditionCheck(ICleanUp cleanUp, StubCompilationUnit stubUnit) {
		try {
			cleanUp.checkPreConditions(null, new ICompilationUnit[]{stubUnit}, MONITOR);
		} catch (Exception preConditionError) {
			LOGGER.log(Level.FINE, preConditionError,
					() -> "Cleanup " + cleanUp.getClass().getSimpleName()
							+ " precondition check failed; continuing anyway");
		}
	}
}
