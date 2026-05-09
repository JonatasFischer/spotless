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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.CleanUpRequirements;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Direct unit tests for {@link CleanUpApplier}. Drives every branch via simple mock cleanups. */
class CleanUpApplierTest {

	private static final String SOURCE = "public class Foo {}";

	private static final List<LogRecord> CAPTURED_LOG = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void installLogCapture() {
		// Capture every log record emitted from CleanUpApplier so tests can verify the EXACT
		// message lambdas that PIT mutators target (e.g. "replaced return value with """). We
		// keep handlers attached for the entire test suite and clear the list between tests.
		Logger logger = Logger.getLogger(CleanUpApplier.class.getName());
		logger.setLevel(Level.FINE);
		logger.setUseParentHandlers(false);
		logger.addHandler(new Handler() {
			@Override
			public void publish(LogRecord record) {
				// Force the message-supplier lambda to evaluate now (otherwise it would only run
				// inside the parent console handler when the lambda is read).
				record.getMessage();
				CAPTURED_LOG.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		});
	}

	@BeforeEach
	void clearLogBuffer() throws Exception {
		CAPTURED_LOG.clear();
		// Clear the compiler-options cache so each test re-exercises mergeCompilerOptions from
		// scratch. Without this, the "putAll" PIT mutants can survive because a previous test
		// has populated the cache with the correct map.
		Field cacheField = CleanUpApplier.class.getDeclaredField("COMPILER_OPTIONS_CACHE");
		cacheField.setAccessible(true);
		((Map<?, ?>) cacheField.get(null)).clear();
	}

	// =========================================================================
	// Argument validation
	// =========================================================================

	@Test
	void applyRejectsNullCleanUp() {
		assertThatThrownBy(() -> CleanUpApplier.apply(null, SOURCE))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("cleanUp");
	}

	@Test
	void applyRejectsNullSource() {
		assertThatThrownBy(() -> CleanUpApplier.apply(new CleanUpFixtures.NoFix(), null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("source");
	}

	// =========================================================================
	// Result paths
	// =========================================================================

	@Test
	void cleanUpThatReturnsNoFixLeavesSourceUnchanged() {
		String result = CleanUpApplier.apply(new CleanUpFixtures.NoFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		// fix==null is the early return path — NO exception is caught, so NO log emitted.
		assertThat(CAPTURED_LOG).isEmpty();
	}

	@Test
	void cleanUpReturningChangeWithNullEditLeavesSourceUnchanged() {
		String result = CleanUpApplier.apply(new CleanUpFixtures.NullEditFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		// edit==null is the early return path — NO exception is caught, so NO log emitted.
		assertThat(CAPTURED_LOG).isEmpty();
	}

	@Test
	void cleanUpReturningNoOpEditLeavesSourceUnchanged() {
		// MultiTextEdit with no children: applies cleanly but produces no string change.
		String result = CleanUpApplier.apply(new CleanUpFixtures.NoOpEditFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		// edit applies cleanly (just no-op), so no exception caught either.
		assertThat(CAPTURED_LOG).isEmpty();
	}

	@Test
	void cleanUpProducingReplaceEditAppliesIt() throws Exception {
		CleanUpFixtures.RenameFooToBarFix fix = new CleanUpFixtures.RenameFooToBarFix();
		Field rethrow = CleanUpApplier.class.getDeclaredField("RETHROW_FOR_TESTING");
		rethrow.setAccessible(true);
		rethrow.setBoolean(null, true);
		try {
			String result = CleanUpApplier.apply(fix, SOURCE);
			assertThat(result).isEqualTo("public class Bar {}");
		} finally {
			rethrow.setBoolean(null, false);
		}
	}

	// =========================================================================
	// Failure handling — every cleanup throw must be swallowed and source returned
	// =========================================================================

	@Test
	void cleanUpThrowingCoreExceptionInCreateFixLeavesSourceUnchanged() {
		String result = CleanUpApplier.apply(new CleanUpFixtures.ThrowingCoreException(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		// The catch-all logs at FINE with a message naming the cleanup and the exception type.
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("ThrowingCoreException")
					.contains("skipped")
					.contains("CoreException");
		});
	}

	@Test
	void cleanUpThrowingRuntimeInCreateFixLeavesSourceUnchanged() {
		String result = CleanUpApplier.apply(new CleanUpFixtures.ThrowingRuntime(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("ThrowingRuntime")
					.contains("skipped");
		});
	}

	@Test
	void cleanUpThrowingInPreConditionStillProceedsToCreateFix() {
		// Precondition failure must not prevent the cleanup from running. The next call to
		// createFix is invoked, returns null, and the source is unchanged.
		CleanUpFixtures.PreConditionThrows fixture = new CleanUpFixtures.PreConditionThrows();
		String result = CleanUpApplier.apply(fixture, SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		assertThat(fixture.createFixCalled).as("createFix must be invoked even after preconditions throw").isTrue();
		// Precondition failure log message contains the cleanup name + "precondition check failed".
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("PreConditionThrows")
					.contains("precondition check failed");
		});
	}

	@Test
	void editApplyRaisingBadLocationLeavesSourceUnchanged() {
		// ReplaceEdit at offset > source length triggers BadLocationException inside edit.apply().
		String result = CleanUpApplier.apply(new CleanUpFixtures.OutOfBoundsEditFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("OutOfBoundsEditFix")
					.contains("skipped");
		});
	}

	// =========================================================================
	// Compiler-options merging — direct unit tests against the package-private
	// helper to assert the returned map's contents (kills the PIT mutants that
	// would survive when only asserting via apply()'s downstream behaviour).
	// =========================================================================

	@Test
	void mergeCompilerOptionsReturnsDefaultsWhenRequiredIsNull() {
		Map<String, String> result = CleanUpApplier.mergeCompilerOptions(new CleanUpFixtures.NullCompilerOptionsFix());
		// Identity-equal to the constant: hitting the null-branch must not allocate a fresh map.
		assertThat(result).isSameAs(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	}

	@Test
	void mergeCompilerOptionsReturnsDefaultsWhenRequiredIsEmpty() {
		Map<String, String> result = CleanUpApplier.mergeCompilerOptions(new CleanUpFixtures.EmptyCompilerOptionsFix());
		assertThat(result).isSameAs(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
	}

	@Test
	void mergeCompilerOptionsMergesUserKeysOnTopOfDefaults() {
		Map<String, String> required = Map.of(
				JavaCore.COMPILER_PB_UNUSED_IMPORT, JavaCore.WARNING,
				"some.user.key", "some.value");
		Map<String, String> result = CleanUpApplier.mergeCompilerOptions(new CleanUpFixtures.CapturingFix(required));
		// Defaults are present.
		assertThat(result).containsAllEntriesOf(CleanUpConstants.DEFAULT_COMPILER_OPTIONS);
		// User values are also present.
		assertThat(result).containsEntry(JavaCore.COMPILER_PB_UNUSED_IMPORT, JavaCore.WARNING);
		assertThat(result).containsEntry("some.user.key", "some.value");
		// Returned map is immutable (Map.copyOf).
		assertThat(result).isUnmodifiable();
	}

	@Test
	void mergeCompilerOptionsUserValueOverridesDefaultForSameKey() {
		// If the user's required options contain a key that's also in DEFAULT_COMPILER_OPTIONS,
		// the user's value must win because putAll(r) runs after putAll(DEFAULT).
		Map<String, String> required = Map.of(JavaCore.COMPILER_SOURCE, "11");
		Map<String, String> result = CleanUpApplier.mergeCompilerOptions(new CleanUpFixtures.CapturingFix(required));
		assertThat(result).containsEntry(JavaCore.COMPILER_SOURCE, "11");
	}

	@Test
	void mergeCompilerOptionsCachesByIdentity() {
		// Identity-keyed cache: with the same fixture (same Requirements object → same Map ref),
		// the second call must return the same cached map instance.
		CleanUpFixtures.CapturingFix fix = new CleanUpFixtures.CapturingFix(Map.of("cache.key", "cache.value"));
		Map<String, String> first = CleanUpApplier.mergeCompilerOptions(fix);
		Map<String, String> second = CleanUpApplier.mergeCompilerOptions(fix);
		assertThat(first).isSameAs(second);
	}

	@Test
	void cleanUpWithNullCompilerOptionsUsesDefaults() {
		// End-to-end smoke through apply().
		String result = CleanUpApplier.apply(new CleanUpFixtures.NullCompilerOptionsFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
	}

	@Test
	void cleanUpWithEmptyCompilerOptionsUsesDefaults() {
		String result = CleanUpApplier.apply(new CleanUpFixtures.EmptyCompilerOptionsFix(), SOURCE);
		assertThat(result).isEqualTo(SOURCE);
	}

	// =========================================================================
	// Reachability / visibility
	// =========================================================================

	@Test
	void privateConstructorIsInvocableViaReflection() throws Exception {
		Constructor<CleanUpApplier> ctor = CleanUpApplier.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
		ctor.setAccessible(true);
		assertThat(ctor.newInstance()).isNotNull();
	}

	// =========================================================================
	// Reflection-helper failure paths. Both are guards against future JDT API
	// changes; the catch blocks must be reachable in tests so PIT mutants on
	// them get killed.
	// =========================================================================

	@Test
	void locateTypeRootFieldThrowsForUnknownField() {
		assertThatThrownBy(() -> CleanUpApplier.locateTypeRootField("definitelyNotAField"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("definitelyNotAField")
				.hasCauseInstanceOf(NoSuchFieldException.class);
	}

	@Test
	void locateTypeRootFieldReturnsAccessibleFieldForRealName() throws Exception {
		// Drives the success path: returns a non-null Field on which setAccessible(true) was
		// called. Kills both the NullReturnValsMutator on the return statement and the
		// VoidMethodCallMutator on f.setAccessible(true).
		Field located = CleanUpApplier.locateTypeRootField("typeRoot");
		assertThat(located).isNotNull();
		assertThat(located.getName()).isEqualTo("typeRoot");
		// Field.canAccess(null) returns true only for static fields; typeRoot is an instance
		// field, so we assert via the legacy isAccessible() flag (semantic equivalent for our
		// usage — the underlying flag set by setAccessible).
		assertThat(located.canAccess(parseSampleAst())).isTrue();
	}

	private static CompilationUnit parseSampleAst() {
		return CleanUpApplier.parse(SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS,
				new StubCompilationUnit(SOURCE, "Foo.java"), "Foo.java");
	}

	@Test
	void linkStubAsTypeRootRethrowsIllegalAccessException() throws Exception {
		// Swap AST_TYPE_ROOT_FIELD with an inaccessible Field handle so the set() call inside
		// linkStubAsTypeRoot raises IllegalAccessException, which the method must translate to
		// IllegalStateException.
		Field astFieldRef = CleanUpApplier.class.getDeclaredField("AST_TYPE_ROOT_FIELD");
		astFieldRef.setAccessible(true);
		Field original = (Field) astFieldRef.get(null);
		// Acquire a fresh, inaccessible Field handle for typeRoot. Field.set checks the target's
		// non-null first, then the access check, so we need a real CompilationUnit instance to
		// reach the access-check path.
		Field freshInaccessible = CompilationUnit.class.getDeclaredField("typeRoot");
		// Note: we deliberately do NOT call setAccessible(true) here.
		// Build a real AST so Field.set has a valid target instance.
		Method parseMethod = CleanUpApplier.class.getDeclaredMethod(
				"parse", String.class, Map.class, StubCompilationUnit.class, String.class);
		parseMethod.setAccessible(true);
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CompilationUnit ast = (CompilationUnit) parseMethod.invoke(null,
				SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		try {
			astFieldRef.set(null, freshInaccessible);
			Method link = CleanUpApplier.class.getDeclaredMethod(
					"linkStubAsTypeRoot",
					CompilationUnit.class, StubCompilationUnit.class);
			link.setAccessible(true);
			assertThatThrownBy(() -> link.invoke(null, ast, stub))
					.cause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("inaccessible")
					.hasCauseInstanceOf(IllegalAccessException.class);
		} finally {
			astFieldRef.set(null, original);
		}
	}

	// =========================================================================
	// Direct tests for parse / linkStubAsTypeRoot / runPreConditionCheck — kill
	// PIT mutants that strip configuration calls (setKind, setBindingsRecovery,
	// etc.) or remove void-method invocations that have no observable downstream
	// effect via apply()'s assertion surface.
	// =========================================================================

	@Test
	void parseProducesAnAstAtTheLatestSupportedJlsLevel() {
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(
				SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		assertThat(ast).isNotNull();
		// setKind(K_COMPILATION_UNIT) → we get a CompilationUnit, not a Block etc. Removing the
		// setKind call would default to K_CLASS_BODY_DECLARATIONS, producing a different node type.
		assertThat(ast.getNodeType()).isEqualTo(ASTNode.COMPILATION_UNIT);
		assertThat(ast.getAST().apiLevel()).isEqualTo(AST.getJLSLatest());
		// setUnitName + setEnvironment make ast.getProblems() non-null even on syntactically-valid input.
		assertThat(ast.getProblems()).isNotNull();
		// setResolveBindings(true) → at least one top-level type and we can request its binding.
		assertThat(ast.types()).isNotEmpty();
	}

	@Test
	void parseResolvesBindingsForKnownTypes() {
		// Drives setResolveBindings(true). With it disabled, resolveBinding returns null.
		String src = "public class Foo { java.lang.String field; }";
		StubCompilationUnit stub = new StubCompilationUnit(src, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(src, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		AbstractTypeDeclaration topType = (AbstractTypeDeclaration) ast.types().get(0);
		assertThat(topType.resolveBinding()).as("setResolveBindings(true) → AST has bindings").isNotNull();
	}

	@Test
	void parseUsesSuppliedCompilerOptionsForJava17Features() {
		// setCompilerOptions(...) honours the source-level pin (17). Records were added in 16,
		// so a parser without our 17 pin (i.e. defaults to 1.4 or similar) can't even parse this.
		String recordSource = "public record Point(int x, int y) {}";
		StubCompilationUnit stub = new StubCompilationUnit(recordSource, "Point.java");
		CompilationUnit ast = CleanUpApplier.parse(
				recordSource, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Point.java");
		assertThat(ast).isNotNull();
		// Record syntax must parse without producing problems on Java 17.
		assertThat(ast.types()).isNotEmpty();
		assertThat(ast.types().get(0)).isInstanceOf(RecordDeclaration.class);
	}

	@Test
	void parseSetUnitNameMatchesAstSource() {
		// setUnitName drives the unit-name-vs-public-type validation inside the parser. We
		// observe its effect via the typeRoot we wired in: typeRoot.getElementName() must
		// equal the unit name we passed.
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		assertThat(ast.getTypeRoot().getElementName()).isEqualTo("Foo.java");
	}

	@Test
	void parseRecoversBindingsAndStatementsForMalformedSource() {
		// Source with a missing closing brace inside the method body. With
		// setBindingsRecovery(true) + setStatementsRecovery(true), the parser still produces
		// a usable AST whose top-level type has a resolved binding; without those flags the
		// recovery is skipped and the parse output is degraded.
		String malformed = "public class Foo {\n" +
				"  public void doThing() {\n" +
				"    java.util.List<String> xs = new java.util.ArrayList<>();\n" +
				"    xs.add(\n" + // missing argument and closing paren — statement-level error
				"  }\n" +
				"}\n";
		StubCompilationUnit stub = new StubCompilationUnit(malformed, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(malformed, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		assertThat(ast).isNotNull();
		assertThat(ast.types()).isNotEmpty();
		AbstractTypeDeclaration topType = (AbstractTypeDeclaration) ast.types().get(0);
		// With bindings recovery enabled, the type binding is non-null even for a class with a
		// malformed method body. Without recovery the resolveBinding() returns null.
		assertThat(topType.resolveBinding())
				.as("setBindingsRecovery(true) should produce a non-null binding for partially valid sources")
				.isNotNull();
		// With statements recovery, the AST still contains the body declarations of the class.
		assertThat(topType.bodyDeclarations()).isNotEmpty();
	}

	@Test
	void parseWiresStubAsTypeRoot() {
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(
				SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		// linkStubAsTypeRoot was called by parse(): typeRoot now points at our stub.
		assertThat(ast.getTypeRoot()).isSameAs(stub);
	}

	@Test
	void linkStubAsTypeRootInstallsTheStub() {
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CompilationUnit ast = CleanUpApplier.parse(
				SOURCE, CleanUpConstants.DEFAULT_COMPILER_OPTIONS, stub, "Foo.java");
		// Re-link with a fresh stub to verify linkStubAsTypeRoot replaces the existing wiring.
		StubCompilationUnit other = new StubCompilationUnit(SOURCE, "Other.java");
		CleanUpApplier.linkStubAsTypeRoot(ast, other);
		assertThat(ast.getTypeRoot()).isSameAs(other);
	}

	@Test
	void runPreConditionCheckInvokesCleanUpsCheckPreConditions() {
		CleanUpFixtures.PreConditionTracker tracker = new CleanUpFixtures.PreConditionTracker();
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		CleanUpApplier.runPreConditionCheck(tracker, stub);
		assertThat(tracker.checkPreConditionsCalled).isTrue();
	}

	@Test
	void runPreConditionCheckSwallowsRuntimeAndLogsIt() {
		CleanUpFixtures.PreConditionThrows thrower = new CleanUpFixtures.PreConditionThrows();
		StubCompilationUnit stub = new StubCompilationUnit(SOURCE, "Foo.java");
		// Must not propagate even though checkPreConditions throws.
		CleanUpApplier.runPreConditionCheck(thrower, stub);
		assertThat(CAPTURED_LOG).anySatisfy(record -> {
			assertThat(record.getMessage())
					.contains("PreConditionThrows")
					.contains("precondition check failed");
		});
	}

	@Test
	void rethrowForTestingSurfacesUnderlyingException() {
		// Documents the test-only seam: when RETHROW_FOR_TESTING is true, the catch in apply()
		// rethrows wrapped instead of returning the source unchanged. Used by the happy-path
		// test above for diagnosis.
		try {
			Field rethrow = CleanUpApplier.class.getDeclaredField("RETHROW_FOR_TESTING");
			rethrow.setAccessible(true);
			rethrow.setBoolean(null, true);
			assertThatThrownBy(() -> CleanUpApplier.apply(new CleanUpFixtures.ThrowingRuntime(), SOURCE))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("rethrown for testing")
					.hasCauseInstanceOf(IllegalStateException.class);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new AssertionError(e);
		} finally {
			try {
				Field rethrow = CleanUpApplier.class.getDeclaredField("RETHROW_FOR_TESTING");
				rethrow.setAccessible(true);
				rethrow.setBoolean(null, false);
			} catch (NoSuchFieldException | IllegalAccessException ignored) {
				// fall through
			}
		}
	}

	// =========================================================================
	// Test fixtures
	// =========================================================================

	/**
	 * Tiny family of {@link ICleanUp} implementations used to drive each branch of
	 * {@link CleanUpApplier#apply}. Kept in an inner class so each fixture only declares the
	 * methods it actually exercises.
	 */
	private static final class CleanUpFixtures {

		private CleanUpFixtures() {}

		/** Base no-op cleanup; subclasses override the one method they want to exercise. */
		private static class Base implements ICleanUp {
			@Override
			public void setOptions(CleanUpOptions options) {}

			@Override
			public String[] getStepDescriptions() {
				return new String[0];
			}

			@Override
			public CleanUpRequirements getRequirements() {
				// CleanUpRequirements mutates the supplied map, so we must pass a mutable instance.
				return new CleanUpRequirements(true, true, false, new HashMap<>());
			}

			@Override
			public RefactoringStatus checkPreConditions(IJavaProject project, ICompilationUnit[] units, IProgressMonitor monitor) throws CoreException {
				return new RefactoringStatus();
			}

			@Override
			public ICleanUpFix createFix(CleanUpContext context) throws CoreException {
				return null;
			}

			@Override
			public RefactoringStatus checkPostConditions(IProgressMonitor monitor) throws CoreException {
				return new RefactoringStatus();
			}
		}

		static class NoFix extends Base {}

		static class NullEditFix extends Base {
			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				return monitor -> new CompilationUnitChange("test", new StubCompilationUnit(SOURCE, "Foo.java"));
			}
		}

		static class NoOpEditFix extends Base {
			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				return monitor -> {
					CompilationUnitChange change = new CompilationUnitChange("test",
							new StubCompilationUnit(SOURCE, "Foo.java"));
					change.setEdit(new MultiTextEdit());
					return change;
				};
			}
		}

		static class RenameFooToBarFix extends Base {
			Throwable lastError;
			boolean createFixCalled;
			boolean createChangeCalled;

			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				createFixCalled = true;
				return monitor -> {
					createChangeCalled = true;
					try {
						CompilationUnitChange change = new CompilationUnitChange("test",
								new StubCompilationUnit(SOURCE, "Foo.java"));
						MultiTextEdit root = new MultiTextEdit();
						root.addChild(new ReplaceEdit("public class ".length(), 3, "Bar"));
						change.setEdit(root);
						return change;
					} catch (Throwable t) {
						lastError = t;
						throw t;
					}
				};
			}
		}

		static class ThrowingCoreException extends Base {
			@Override
			public ICleanUpFix createFix(CleanUpContext context) throws CoreException {
				throw new CoreException(Status.error("simulated"));
			}
		}

		static class ThrowingRuntime extends Base {
			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				throw new IllegalStateException("simulated");
			}
		}

		static class PreConditionThrows extends Base {
			boolean createFixCalled;

			@Override
			public RefactoringStatus checkPreConditions(IJavaProject project, ICompilationUnit[] units, IProgressMonitor monitor) {
				throw new IllegalStateException("simulated precondition failure");
			}

			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				createFixCalled = true;
				return null;
			}
		}

		static class PreConditionTracker extends Base {
			boolean checkPreConditionsCalled;

			@Override
			public RefactoringStatus checkPreConditions(IJavaProject project, ICompilationUnit[] units, IProgressMonitor monitor) {
				checkPreConditionsCalled = true;
				return new RefactoringStatus();
			}
		}

		static class OutOfBoundsEditFix extends Base {
			@Override
			public ICleanUpFix createFix(CleanUpContext context) {
				return monitor -> {
					// Replace at offset way past end-of-source: edit.apply throws BadLocationException.
					CompilationUnitChange change = new CompilationUnitChange("test",
							new StubCompilationUnit(SOURCE, "Foo.java"));
					MultiTextEdit root = new MultiTextEdit();
					root.addChild(new ReplaceEdit(SOURCE.length() + 100, 1, "X"));
					change.setEdit(root);
					return change;
				};
			}
		}

		static class CapturingFix extends Base {
			private final Map<String, String> required;
			private CleanUpRequirements cachedRequirements;
			boolean requirementsCalled;

			CapturingFix(Map<String, String> required) {
				this.required = required;
			}

			@Override
			public CleanUpRequirements getRequirements() {
				requirementsCalled = true;
				// Same Requirements object across calls so the underlying Map reference is stable
				// and the identity-keyed cache in mergeCompilerOptions can hit on repeat calls.
				if (cachedRequirements == null) {
					cachedRequirements = new CleanUpRequirements(true, true, false, new HashMap<>(required));
				}
				return cachedRequirements;
			}
		}

		static class NullCompilerOptionsFix extends Base {
			@Override
			public CleanUpRequirements getRequirements() {
				return new CleanUpRequirements(true, true, false, null);
			}
		}

		static class EmptyCompilerOptionsFix extends Base {
			@Override
			public CleanUpRequirements getRequirements() {
				// CleanUpRequirements' constructor seeds the supplied map with default compiler
				// problem severities. To produce a truly empty map (so we exercise the
				// "non-null but empty" branch in mergeCompilerOptions), clear the map after
				// construction; getCompilerOptions returns the same reference.
				HashMap<String, String> mutable = new HashMap<>();
				CleanUpRequirements requirements = new CleanUpRequirements(true, true, false, mutable);
				mutable.clear();
				return requirements;
			}
		}
	}

}
