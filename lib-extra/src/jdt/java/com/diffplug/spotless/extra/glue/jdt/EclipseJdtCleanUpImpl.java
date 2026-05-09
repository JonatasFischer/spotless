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
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.DefaultWorkingCopyOwner;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.ui.fix.BooleanValueRatherThanComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.CodeStyleCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ConvertLoopCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.LambdaExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.MapCleanUpOptions;
import org.eclipse.jdt.internal.ui.fix.OneIfRatherThanDuplicateBlocksThatFallThroughCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PatternMatchingForInstanceofCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PotentialProgrammingProblemsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.PrimitiveComparisonCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.RedundantComparatorCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ReturnExpressionCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.SwitchExpressionsCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnnecessaryCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.ValueOfRatherThanInstantiationCleanUpCore;
import org.eclipse.jdt.internal.ui.fix.VariableDeclarationCleanUpCore;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.osgi.internal.location.EquinoxLocations;
import org.eclipse.text.edits.TextEdit;
import org.osgi.framework.Constants;

import dev.equo.solstice.NestedJars;
import dev.equo.solstice.ShimIdeBootstrapServices;
import dev.equo.solstice.Solstice;
import dev.equo.solstice.p2.CacheLocations;

/**
 * Applies Eclipse JDT Clean Up actions to Java source code.
 *
 * <p>This class is loaded reflectively into the isolated P2 classloader,
 * following the same pattern as {@code EclipseJdtFormatterStepImpl}.
 *
 * <p>The clean up settings are read from an Eclipse clean up profile XML file
 * (exported via <em>Preferences &rarr; Java &rarr; Code Style &rarr; Clean Up &rarr; Export</em>),
 * which is pre-parsed into a {@link Properties} object by {@code EquoBasedStepBuilder.State}.
 *
 * <p>{@code cleanup.format_source_code} is intentionally forced to {@code false} &mdash;
 * formatting is handled by the Eclipse formatter step separately.
 */
public class EclipseJdtCleanUpImpl {

	private static final Logger LOGGER = Logger.getLogger(EclipseJdtCleanUpImpl.class.getName());

	/** Matches the first {@code public class|interface|record|enum Foo} declaration. */
	private static final Pattern PUBLIC_TYPE_PATTERN = Pattern.compile(
			"public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*(?:class|interface|enum|record)\\s+(\\w+)");

	/**
	 * Returns the unit name (e.g. {@code "Foo.java"}) that the AST parser should use, derived from
	 * the first public type declaration in the source. Falls back to a generic name when no public
	 * type is found. The compiler complains with {@code "The public type X must be defined in its
	 * own file"} if the unit name does not match the public type, and that single error blocks the
	 * detection of every other problem (including {@code IProblem.UnusedImport}), so picking the
	 * right name is essential for cleanups like {@code remove_unused_imports} to fire.
	 */
	private static String inferUnitName(String source) {
		Matcher m = PUBLIC_TYPE_PATTERN.matcher(source);
		if (m.find()) {
			return m.group(1) + ".java";
		}
		return "CleanUpUnit.java";
	}

	static {
		// Bootstrap the Equo Solstice OSGi runtime so that bundle activators run and services like
		// IPreferencesService, JavaModelManager, etc. are registered. This unlocks the cleanups
		// that depend on ImportRewrite / Platform.getPreferencesService() (lambda conversion,
		// remove unused imports, missing override annotations, ...).
		//
		// This static block mirrors the proven pattern from
		// com.diffplug.spotless.extra.glue.groovy.GrEclipseFormatterStepImpl. The dev.equo.ide:solstice
		// dependency is already on the classpath because P2Provisioner adds it to every Equo-based
		// step.
		NestedJars.setToWarnOnly();
		NestedJars.onClassPath().confirmAllNestedJarsArePresentOnClasspath(CacheLocations.p2nestedJars());
		try {
			Solstice solstice = Solstice.findBundlesOnClasspath();
			solstice.warnAndModifyManifestsToFix();
			Map<String, String> props = Map.of(
					"osgi.nl", "en_US",
					Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT,
					EquinoxLocations.PROP_INSTANCE_AREA, Files.createTempDirectory("spotless-jdt-cleanup").toAbsolutePath().toString());
			solstice.openShim(props);
			ShimIdeBootstrapServices.apply(props, solstice.getContext());
			// org.apache.felix.scr powers Declarative Services so DS-based bundles activate properly.
			solstice.start("org.apache.felix.scr");
			// Activating every non-lazy bundle lets the equinox preferences activator register
			// IPreferencesService, the JDT activators register JavaModelManager, etc.
			solstice.startAllWithLazy(false);
			// Explicitly start the JDT bundles in case any of them was left lazy.
			solstice.start("org.eclipse.equinox.preferences");
			solstice.start("org.eclipse.core.runtime");
			solstice.start("org.eclipse.jdt.core");
			solstice.start("org.eclipse.jdt.core.manipulation");
		} catch (IOException e) {
			throw new RuntimeException("Failed to bootstrap Equo Solstice runtime for Eclipse JDT clean up", e);
		}

		// Belt and braces: the JavaManipulationPlugin.start() activator should have set
		// JavaManipulation.fgPreferenceNodeId by now, but if for some reason it did not, force it.
		try {
			Class<?> jm = Class.forName("org.eclipse.jdt.core.manipulation.JavaManipulation");
			Field nodeIdField = jm.getDeclaredField("fgPreferenceNodeId");
			nodeIdField.setAccessible(true);
			if (nodeIdField.get(null) == null) {
				nodeIdField.set(null, "org.eclipse.jdt.core.manipulation");
			}
		} catch (ReflectiveOperationException e) {
			LOGGER.log(Level.FINE, e, () -> "Could not verify JavaManipulation.fgPreferenceNodeId after Solstice bootstrap");
		}

		// Cleanups that go through CodeStyleConfiguration.configureImportRewrite() read several
		// JDT-UI preferences (import order, on-demand threshold, ...). The org.eclipse.jdt.ui
		// bundle is NOT on our classpath (we use the headless org.eclipse.jdt.core.manipulation
		// only), so the InstanceScope node has no defaults for those keys and a null lookup ends
		// in NPE inside CodeStyleConfiguration.configureImportRewrite. Seed the same defaults
		// the Eclipse IDE ships with so the import rewrite path is non-null.
		try {
			Class<?> instanceScope = Class.forName("org.eclipse.core.runtime.preferences.InstanceScope");
			Object scope = instanceScope.getField("INSTANCE").get(null);
			Object node = instanceScope.getMethod("getNode", String.class).invoke(scope, "org.eclipse.jdt.core.manipulation");
			Class<?> prefs = Class.forName("org.osgi.service.prefs.Preferences");
			Method put = prefs.getMethod("put", String.class, String.class);
			put.invoke(node, "org.eclipse.jdt.ui.importorder", "java;javax;org;com");
			put.invoke(node, "org.eclipse.jdt.ui.ondemandthreshold", "99");
			put.invoke(node, "org.eclipse.jdt.ui.staticondemandthreshold", "99");
		} catch (ReflectiveOperationException e) {
			LOGGER.log(Level.FINE, e, () -> "Could not seed default JDT-UI import preferences; cleanups using import rewrite may fail");
		}
	}

	/**
	 * Key used in the cleanup profile XML that triggers Eclipse's internal formatter.
	 * Forced to {@code false} here because Spotless manages formatting via a dedicated step.
	 */
	private static final String FORMAT_SOURCE_CODE_KEY = "cleanup.format_source_code";

	private final Map<String, String> cleanUpOptions;
	private final List<ICleanUp> cleanUps;

	public EclipseJdtCleanUpImpl(Properties settings) {
		this.cleanUpOptions = new HashMap<>();
		for (Map.Entry<Object, Object> entry : settings.entrySet()) {
			this.cleanUpOptions.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		// Always disable format_source_code — formatting is a separate Spotless step
		this.cleanUpOptions.put(FORMAT_SOURCE_CODE_KEY, CleanUpOptions.FALSE);
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

		CleanUpOptions options = new MapCleanUpOptions(cleanUpOptions);
		for (ICleanUp cleanUp : cleanUps) {
			cleanUp.setOptions(options);
			raw = applyCleanUp(cleanUp, raw);
		}
		return raw;
	}

	private String applyCleanUp(ICleanUp cleanUp, String source) {
		try {
			Map<String, String> compilerOptions = new HashMap<>();
			compilerOptions.put(JavaCore.COMPILER_SOURCE, "17");
			compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, "17");
			compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
			// Each cleanup advertises which compiler problems must be enabled for it to detect
			// fixable spots — e.g. UnusedCodeCleanUpCore needs COMPILER_PB_UNUSED_IMPORT=WARNING
			// so the compiler emits IProblem.UnusedImport markers it can act on. Merge those into
			// the parser configuration before resolving the AST.
			Map<String, String> requiredOptions = cleanUp.getRequirements().getCompilerOptions();
			if (requiredOptions != null) {
				compilerOptions.putAll(requiredOptions);
			}

			StubCompilationUnit stubUnit = new StubCompilationUnit(source);

			// Use setSource(char[]) (not setSource(ICompilationUnit)) — the latter goes through
			// JavaModelManager which is not initialised in the isolated P2 classloader.
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setSource(source.toCharArray());
			parser.setKind(ASTParser.K_COMPILATION_UNIT);
			parser.setCompilerOptions(compilerOptions);
			// Bindings are required by most cleanups (lambda conversion, final-parameter detection,
			// pattern-matching-for-instanceof, etc.). Empty environment makes the parser resolve
			// types from the runtime JRE only.
			parser.setResolveBindings(true);
			parser.setBindingsRecovery(true);
			parser.setStatementsRecovery(true);
			parser.setUnitName(inferUnitName(source));
			parser.setEnvironment(new String[0], new String[0], new String[0], true);

			org.eclipse.jdt.core.dom.CompilationUnit ast = (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);

			// Cleanups call ast.getTypeRoot() / context.getCompilationUnit() to build the
			// CompilationUnitChange that wraps the rewrite. Both must be non-null. Wire the
			// stub into the AST via reflection (the typeRoot field is package-private).
			try {
				Field typeRootField = org.eclipse.jdt.core.dom.CompilationUnit.class.getDeclaredField("typeRoot");
				typeRootField.setAccessible(true);
				typeRootField.set(ast, stubUnit);
			} catch (NoSuchFieldException | IllegalAccessException reflectionError) {
				LOGGER.log(Level.FINE, reflectionError, () -> "Could not link stub ICompilationUnit into AST.typeRoot; cleanups that need it will be skipped");
			}

			CleanUpContext context = new CleanUpContext(stubUnit, ast);

			IProgressMonitor monitor = new NullProgressMonitor();
			try {
				cleanUp.checkPreConditions(null, new ICompilationUnit[]{stubUnit}, monitor);
			} catch (Exception preConditionError) {
				LOGGER.log(Level.FINE, preConditionError, () -> "Cleanup " + cleanUp.getClass().getSimpleName() + " precondition check failed; continuing anyway");
			}

			ICleanUpFix fix = cleanUp.createFix(context);
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
			// A cleanup that throws is logged at FINE level — users can opt in to detailed
			// diagnostics via JUL configuration without breaking builds for unrelated cleanups.
			LOGGER.log(Level.FINE, e, () -> "Cleanup " + cleanUp.getClass().getSimpleName() + " skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			return source;
		}
	}

	/**
	 * Builds the list of {@link ICleanUp} instances that cover the cleanup options.
	 * Each class from Eclipse JDT covers a group of related option keys.
	 * All are instantiated; each will determine internally (via {@code setOptions})
	 * which of its sub-options are active based on the profile settings.
	 */
	private static List<ICleanUp> buildCleanUps() {
		List<ICleanUp> list = new ArrayList<>();

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
		// (renamed from UseIteratorToForLoopCleanUpCore to ConvertLoopCleanUpCore in newer Eclipse JDT)
		list.add(new ConvertLoopCleanUpCore());

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
	 * wrapping a Java source string. Required by {@link CleanUpContext}.
	 *
	 * <p>Most internal Eclipse code paths that touch {@code CompilationUnit} delegate
	 * to {@code calculateHashCode()} (via the {@code BufferManager} cache lookup) and
	 * {@code getJavaProject().getProject()} (via {@code CompilationUnitChange#getFile}).
	 * Both NPE on the default super(null, null, null) construction, so we override them.
	 */
	@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "equals not used in clean up context")
	private static class StubCompilationUnit extends CompilationUnit {
		private final StubBuffer buffer;
		private final IFile fakeFile;

		StubCompilationUnit(String source) {
			// (parent, name, owner) — owner must be non-null because JavaElement.hashCode()
			// is final and delegates to calculateHashCode() which dereferences owner.
			super(null, "CleanUpUnit.java", DefaultWorkingCopyOwner.PRIMARY);
			this.buffer = new StubBuffer(source);
			this.fakeFile = createFakeFile();
		}

		/**
		 * Returns a no-op {@link IFile} whose only contract is to be non-null and answer
		 * {@code getFileExtension() -> "java"}. This is required because the cleanup creates
		 * {@code CompilationUnitChange -> TextFileChange} which {@code Assert.isNotNull}'s the
		 * file and queries its extension. The dynamic proxy returns null/0/false for every other
		 * method; that's fine because the change object never actually hits disk in our flow:
		 * we extract the {@link org.eclipse.text.edits.TextEdit} from it and apply it against
		 * an in-memory {@code Document}.
		 */
		private static IFile createFakeFile() {
			return (IFile) Proxy.newProxyInstance(
					IFile.class.getClassLoader(),
					new Class<?>[]{IFile.class},
					(proxy, method, args) -> {
						switch (method.getName()) {
						case "getFileExtension":
							return "java";
						case "getName":
							return "CleanUpUnit.java";
						case "exists":
							return Boolean.TRUE;
						case "isReadOnly":
						case "isLinked":
						case "isVirtual":
						case "isHidden":
						case "isDerived":
						case "isPhantom":
						case "isAccessible":
						case "isTeamPrivateMember":
							return Boolean.FALSE;
						case "getModificationStamp":
						case "getLocalTimeStamp":
							return 0L;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						case "toString":
							return "StubIFile[CleanUpUnit.java]";
						default:
							// Return null for every Object/IFile method we haven't explicitly stubbed.
							// The cleanup pipeline never inspects file contents — we extract the
							// TextEdit from the change and apply it to an in-memory Document.
							Class<?> rt = method.getReturnType();
							if (rt == boolean.class)
								return Boolean.FALSE;
							if (rt == int.class || rt == short.class || rt == byte.class)
								return 0;
							if (rt == long.class)
								return 0L;
							if (rt == double.class)
								return 0.0d;
							if (rt == float.class)
								return 0.0f;
							if (rt == char.class)
								return '\0';
							return null;
						}
					});
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

		@Override
		public String getElementName() {
			return "CleanUpUnit.java";
		}

		/**
		 * Internal Eclipse code goes through {@code getContents()} when reading the source bytes;
		 * the default impl in {@link CompilationUnit} only consults {@code getBuffer()} if the buffer
		 * is registered in the {@code BufferManager}. Our stub buffer is not, so the parent falls back
		 * to {@code getResourceContentsAsCharArray(getResource())} which NPEs (no IFile). We bypass
		 * that path entirely.
		 */
		@Override
		public char[] getContents() {
			return buffer.getCharacters();
		}

		/**
		 * Cleanups that create {@code CompilationUnitChange} pass the result of {@code getResource()}
		 * to {@code TextFileChange.<init>} which asserts non-null. Return a no-op proxy IFile so the
		 * pipeline doesn't crash; we never let the change touch the real workspace.
		 */
		@Override
		public IResource getResource() {
			return fakeFile;
		}
	}

	/**
	 * Minimal stub of {@link org.eclipse.jdt.internal.core.JavaProject}
	 * with no real workspace or classpath.
	 */
	private static class StubJavaProject extends JavaProject {
		static final StubJavaProject INSTANCE = new StubJavaProject();
		private static final IProject STUB_PROJECT = createStubProject();

		StubJavaProject() {
			super(null, null);
		}

		/**
		 * Returns a no-op {@link IProject} so the cleanup pipeline can hand it to
		 * {@code ProjectScope(IProject)} (which {@code throw new IllegalArgumentException()}'s on
		 * null) without crashing. Just like the IFile stub on {@link StubCompilationUnit}, this
		 * project is never actually inspected — cleanups query preferences via {@code ProjectScope}
		 * but our proxy returns null for every method, which the {@code Preferences} machinery
		 * tolerates by falling back to defaults.
		 */
		private static IProject createStubProject() {
			return (IProject) Proxy.newProxyInstance(
					IProject.class.getClassLoader(),
					new Class<?>[]{IProject.class},
					(proxy, method, args) -> {
						switch (method.getName()) {
						case "getName":
							return "CleanUpProject";
						case "exists":
						case "isAccessible":
						case "isOpen":
							return Boolean.TRUE;
						case "isReadOnly":
						case "isLinked":
						case "isVirtual":
						case "isHidden":
						case "isDerived":
						case "isPhantom":
						case "isTeamPrivateMember":
							return Boolean.FALSE;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						case "toString":
							return "StubIProject[CleanUpProject]";
						default:
							Class<?> rt = method.getReturnType();
							if (rt == boolean.class)
								return Boolean.FALSE;
							if (rt == int.class || rt == short.class || rt == byte.class)
								return 0;
							if (rt == long.class)
								return 0L;
							if (rt == double.class)
								return 0.0d;
							if (rt == float.class)
								return 0.0f;
							if (rt == char.class)
								return '\0';
							return null;
						}
					});
		}

		@Override
		public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
			Map<String, String> opts = new HashMap<>();
			opts.put(JavaCore.COMPILER_SOURCE, "17");
			opts.put(JavaCore.COMPILER_COMPLIANCE, "17");
			opts.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
			return opts;
		}

		/**
		 * Default impl traverses {@code JavaModelManager.getInfo()} which dereferences a null
		 * {@code cache} field outside of a real Eclipse runtime. Returning null bypasses module
		 * resolution entirely (clean-ups don't need module info for our use case).
		 */
		@Override
		public IModuleDescription getModuleDescription() {
			return null;
		}

		/**
		 * {@code LambdaExpressionsCleanUpCore} (and a few other cleanups that touch import rewrite)
		 * call {@code new ProjectScope(getProject())} which throws on a null project. Return a
		 * no-op stub so the import-rewrite path doesn't crash.
		 */
		@Override
		public IProject getProject() {
			return STUB_PROJECT;
		}
	}
}
