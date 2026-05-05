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
package com.diffplug.spotless.extra.glue.jdt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.core.manipulation.CleanUpContextCore;
import org.eclipse.jdt.core.manipulation.CleanUpOptionsCore;
import org.eclipse.jdt.core.manipulation.ICleanUpFixCore;
import org.eclipse.jdt.internal.corext.fix.ICleanUpCore;
import org.eclipse.jdt.internal.ui.fix.CodeStyleCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.LambdaExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptionsCore;
import org.eclipse.jdt.internal.ui.fix.PatternMatchingForInstanceofCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PotentialProgrammingProblemsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PrimitiveComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.SwitchExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnnecessaryCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UseIteratorToForLoopCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ValueOfRatherThanInstantiationCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.VariableDeclarationCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.BooleanValueRatherThanComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.RedundantComparatorCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ReturnExpressionCleanUpCore;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.text.edits.TextEdit;

/**
 * Applies Eclipse JDT Clean Up actions to Java source code.
 *
 * <p>This class is loaded reflectively into the isolated P2 classloader,
 * following the same pattern as {@link EclipseJdtFormatterStepImpl}.
 *
 * <p>The clean up settings are read from an Eclipse clean up profile XML file
 * (exported via <em>Preferences &rarr; Java &rarr; Code Style &rarr; Clean Up &rarr; Export</em>),
 * which is pre-parsed into a {@link Properties} object by {@code EquoBasedStepBuilder.State}.
 *
 * <p>{@code cleanup.format_source_code} is intentionally ignored &mdash; formatting is
 * handled by the Eclipse formatter step separately.
 */
public class EclipseJdtCleanUpImpl {

	/**
	 * Key used in the cleanup profile XML that triggers Eclipse's internal formatter.
	 * Skipped here because Spotless manages formatting via a dedicated step.
	 */
	private static final String FORMAT_SOURCE_CODE_KEY = "cleanup.format_source_code";

	private final Map<String, String> cleanUpOptions;
	private final List<ICleanUpCore> cleanUps;

	public EclipseJdtCleanUpImpl(Properties settings) {
		this.cleanUpOptions = new HashMap<>();
		for (Map.Entry<Object, Object> entry : settings.entrySet()) {
			this.cleanUpOptions.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		// Always disable format_source_code — formatting is a separate Spotless step
		this.cleanUpOptions.put(FORMAT_SOURCE_CODE_KEY, CleanUpOptionsCore.FALSE);
		this.cleanUps = buildCleanUps();
	}

	/**
	 * Applies all enabled clean up actions to the given Java source string.
	 *
	 * @param raw  the raw Java source (LF line endings)
	 * @param file the source file (unused; present for API symmetry with formatter)
	 * @return the cleaned-up source, or the original if no clean up produced changes
	 */
	public String cleanUp(String raw, File file) throws Exception {
		if (cleanUps.isEmpty()) {
			return raw;
		}

		CleanUpOptionsCore options = new MapCleanUpOptionsCore(cleanUpOptions);
		for (ICleanUpCore cleanUp : cleanUps) {
			cleanUp.setOptions(options);
			raw = applyCleanUp(cleanUp, raw);
		}
		return raw;
	}

	private String applyCleanUp(ICleanUpCore cleanUp, String source) {
		try {
			Map<String, String> compilerOptions = new HashMap<>();
			compilerOptions.put(JavaCore.COMPILER_SOURCE, "17");
			compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, "17");
			compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");

			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setSource(source.toCharArray());
			parser.setKind(ASTParser.K_COMPILATION_UNIT);
			parser.setCompilerOptions(compilerOptions);

			org.eclipse.jdt.core.dom.CompilationUnit ast = (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);

			StubCompilationUnit stubUnit = new StubCompilationUnit(source);
			CleanUpContextCore context = new CleanUpContextCore(stubUnit, ast);

			IProgressMonitor monitor = new NullProgressMonitor();
			cleanUp.checkPreConditions(null, new ICompilationUnit[]{stubUnit}, monitor);

			ICleanUpFixCore fix;
			try {
				fix = cleanUp.createFixCore(context);
			} catch (Exception e) {
				// Cleanups that require a full Eclipse workspace will throw here — skip silently
				return source;
			}

			if (fix == null) {
				return source;
			}

			TextChange change = (TextChange) fix.createChange(monitor);
			if (change == null) {
				return source;
			}

			TextEdit edit = change.getEdit();
			if (edit == null) {
				return source;
			}

			IDocument doc = new Document(source);
			edit.apply(doc);
			return doc.get();
		} catch (Exception e) {
			// Skip any cleanup that fails without a real workspace — source unchanged
			return source;
		}
	}

	/**
	 * Builds the list of {@link ICleanUpCore} instances that cover the cleanup options.
	 * Each class from Eclipse JDT covers a group of related option keys.
	 * All are instantiated; each will determine internally (via {@code setOptions})
	 * which of its sub-options are active based on the profile settings.
	 */
	private static List<ICleanUpCore> buildCleanUps() {
		List<ICleanUpCore> list = new ArrayList<>();

		// Unused code: remove_unused_imports, remove_unused_private_*
		list.add(new UnusedCodeCleanUpCore());

		// Code style: qualify statics with declaring class, use this.*
		list.add(new CodeStyleCleanUpCore());

		// Variable declarations: make_local_variable_final, make_parameters_final, make_private_fields_final
		list.add(new VariableDeclarationCleanUpCore());

		// Lambda expressions: use_lambda, convert_functional_interfaces, simplify_lambda
		list.add(new LambdaExpressionsCleanUpCore());

		// instanceof pattern matching (Java 16+)
		list.add(new PatternMatchingForInstanceofCleanUpCore());

		// Potential programming problems: add_serial_version_id, add_missing_override_annotations
		list.add(new PotentialProgrammingProblemsCleanUpCore());

		// Switch expressions: convert_to_switch_expressions
		list.add(new SwitchExpressionsCleanUpCore());

		// Primitive comparison: use primitive == instead of .equals()
		list.add(new PrimitiveComparisonCleanUpCore());

		// ValueOf rather than instantiation: use Integer.valueOf() instead of new Integer()
		list.add(new ValueOfRatherThanInstantiationCleanUpCore());

		// Unnecessary code: remove_unnecessary_casts, remove_redundant_type_arguments
		list.add(new UnnecessaryCodeCleanUpCore());

		// Convert classic for-loops to enhanced for-each
		list.add(new UseIteratorToForLoopCleanUpCore());

		// Boolean value rather than comparison: simplify boolean comparisons
		list.add(new BooleanValueRatherThanComparisonCleanUpCore());

		// One if rather than duplicate blocks that fall through
		list.add(new OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore());

		// Redundant comparator: e.g. Comparator.naturalOrder() simplifications
		list.add(new RedundantComparatorCleanUpCore());

		// Return expression: simplify return statements
		list.add(new ReturnExpressionCleanUpCore());

		return list;
	}

	// -------------------------------------------------------------------------
	// Stub inner classes — same pattern as EclipseJdtSortMembers
	// -------------------------------------------------------------------------

	/**
	 * Minimal mutable buffer wrapping a Java source string.
	 */
	@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean up context")
	private static class StubBuffer implements IBuffer {
		private String contents;

		StubBuffer(String contents) {
			this.contents = contents;
		}

		@Override
		public void addBufferChangedListener(IBufferChangedListener listener) {}

		@Override
		public void append(char[] text) {
			contents += new String(text);
		}

		@Override
		public void append(String text) {
			contents += text;
		}

		@Override
		public void close() {}

		@Override
		public char getChar(int position) {
			return contents.charAt(position);
		}

		@Override
		public char[] getCharacters() {
			return contents.toCharArray();
		}

		@Override
		public String getContents() {
			return contents;
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
			contents = contents.substring(0, position) + new String(text) + contents.substring(position + length);
		}

		@Override
		public void replace(int position, int length, String text) {
			contents = contents.substring(0, position) + text + contents.substring(position + length);
		}

		@Override
		public void save(IProgressMonitor progress, boolean force) throws JavaModelException {}

		@Override
		public void setContents(char[] contents) {
			this.contents = new String(contents);
		}

		@Override
		public void setContents(String contents) {
			this.contents = contents;
		}
	}

	/**
	 * Minimal stub of {@link org.eclipse.jdt.internal.core.CompilationUnit}
	 * wrapping a Java source string. Required by {@link CleanUpContextCore}.
	 */
	@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean up context")
	private static class StubCompilationUnit extends CompilationUnit {
		private final StubBuffer buffer;

		StubCompilationUnit(String source) {
			super(null, null, null);
			this.buffer = new StubBuffer(source);
		}

		@Override
		public IBuffer getBuffer() {
			return buffer;
		}

		@Override
		public JavaProject getJavaProject() {
			return StubJavaProject.INSTANCE;
		}

		@Override
		public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
			Map<String, String> opts = new HashMap<>();
			opts.put(JavaCore.COMPILER_SOURCE, "17");
			opts.put(JavaCore.COMPILER_COMPLIANCE, "17");
			opts.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
			return opts;
		}

		@Override
		public ICompilationUnit getPrimary() {
			return this;
		}
	}

	/**
	 * Minimal stub of {@link org.eclipse.jdt.internal.core.JavaProject}
	 * with no real workspace or classpath.
	 */
	private static class StubJavaProject extends JavaProject {
		static final StubJavaProject INSTANCE = new StubJavaProject();

		StubJavaProject() {
			super(null, null);
		}

		@Override
		public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
			Map<String, String> opts = new HashMap<>();
			opts.put(JavaCore.COMPILER_SOURCE, "17");
			opts.put(JavaCore.COMPILER_COMPLIANCE, "17");
			opts.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
			return opts;
		}
	}
}
