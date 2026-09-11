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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.ui.cleanup.CleanUpContext;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.jdt.ui.cleanup.CleanUpRequirements;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.diffplug.spotless.extra.glue.jdt.cleanup.CleanUpDiagnostics;

/** Orchestration tests use a local bootstrap callback; the step tests cover real OSGi startup. */
class EclipseJdtCleanUpImplTest {
	private final AtomicInteger bootstrapCalls = new AtomicInteger();

	private EclipseJdtCleanUpImpl impl(Properties settings, ICleanUp... cleanUps) {
		return new EclipseJdtCleanUpImpl(settings, List.of(cleanUps), bootstrapCalls::incrementAndGet);
	}

	private static Properties enabledProfile() {
		Properties settings = new Properties();
		settings.setProperty("cleanup.make_local_variable_final", "true");
		return settings;
	}

	@Test
	void rejectsNullArguments() {
		assertThatThrownBy(() -> new EclipseJdtCleanUpImpl(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new EclipseJdtCleanUpImpl(new Properties(), null, () -> {}))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("cleanUps");
		assertThatThrownBy(() -> new EclipseJdtCleanUpImpl(new Properties(), List.of(), null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("bootstrap");
		assertThatThrownBy(() -> impl(new Properties()).cleanUp(null, null))
				.isInstanceOf(NullPointerException.class).hasMessageContaining("raw");
	}

	@Test
	void publicConstructorAcceptsEmptyProfileWithoutBootstrapping() {
		assertThat(new EclipseJdtCleanUpImpl(new Properties()).cleanUp("class Foo {}", null))
				.isEqualTo("class Foo {}");
	}

	@Test
	void emptyProfileDoesNotBootstrapOrConfigureCleanups() {
		RecordingCleanUp recorder = new RecordingCleanUp();
		String source = "class Foo {}\r\n";
		assertThat(impl(new Properties(), recorder).cleanUp(source, null)).isEqualTo(source);
		assertThat(bootstrapCalls).hasValue(0);
		assertThat(recorder.options).isNull();
	}

	@Test
	void emptyCatalogueDoesNotBootstrap() {
		assertThat(impl(enabledProfile()).cleanUp("class Foo {}", null)).isEqualTo("class Foo {}");
		assertThat(bootstrapCalls).hasValue(0);
	}

	@Test
	void ignoresDisabledFormattingAndUnrelatedOptions() {
		Properties settings = new Properties();
		settings.setProperty("cleanup.format_source_code", "true");
		settings.setProperty("cleanup.make_local_variable_final", "false");
		settings.setProperty("unrelated.option", "true");
		RecordingCleanUp recorder = new RecordingCleanUp();
		impl(settings, recorder).cleanUp("class Foo {}", null);
		assertThat(bootstrapCalls).hasValue(0);
		assertThat(recorder.options).isNull();
	}

	@Test
	void profileDefaultsAreInheritedAndNonStringEntriesAreIgnored() {
		Properties settings = new Properties(enabledProfile());
		settings.put("cleanup.non_string", 42);
		settings.put(42, "true");
		settings.setProperty("cleanup.format_source_code", "true");
		RecordingCleanUp recorder = new RecordingCleanUp();
		impl(settings, recorder).cleanUp("class Foo {}", null);
		assertThat(bootstrapCalls).hasValue(1);
		assertThat(recorder.options.getValue("cleanup.make_local_variable_final")).isEqualTo("true");
		assertThat(recorder.options.getValue("cleanup.format_source_code")).isEqualTo("false");
		assertThatThrownBy(() -> recorder.options.getValue("cleanup.non_string"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"class Foo {}\r\n", "class Foo {}\r", "class Foo {}\n", "\rclass Foo {}"})
	void normalizesLineEndingsBeforeCreatingFix(String source) {
		RecordingCleanUp recorder = new RecordingCleanUp();
		String expected = source.replace("\r\n", "\n").replace("\r", "\n");
		assertThat(impl(enabledProfile(), recorder).cleanUp(source, null)).isEqualTo(expected);
		assertThat(recorder.source).isEqualTo(expected);
		assertThat(bootstrapCalls).hasValue(1);
	}

	@Test
	void snapshotsTheProfileAndCatalogue() {
		Properties settings = enabledProfile();
		RecordingCleanUp recorder = new RecordingCleanUp();
		List<ICleanUp> catalogue = new ArrayList<>(List.of(recorder));
		EclipseJdtCleanUpImpl cleanup = new EclipseJdtCleanUpImpl(settings, catalogue, bootstrapCalls::incrementAndGet);
		settings.clear();
		catalogue.clear();
		cleanup.cleanUp("class Foo {}", null);
		assertThat(recorder.options.getValue("cleanup.make_local_variable_final")).isEqualTo("true");
		assertThat(recorder.source).isEqualTo("class Foo {}");
	}

	@Test
	void strictModeAcceptsAValidProfileWhenNoFixIsNeeded() {
		RecordingCleanUp recorder = new RecordingCleanUp();
		EclipseJdtCleanUpImpl cleanup = new EclipseJdtCleanUpImpl(enabledProfile(),
				Map.of("sp_cleanup.strict", "true"), List.of(recorder), bootstrapCalls::incrementAndGet);
		assertThat(cleanup.cleanUp("class Foo {}", new File("Foo.java"))).isEqualTo("class Foo {}");
		assertThat(recorder.source).isEqualTo("class Foo {}");
	}

	@Test
	void unsupportedProfileOptionsWarnOncePerInstance() {
		Properties profile = new Properties();
		profile.setProperty("cleanup.unknown", "true");
		Logger logger = Logger.getLogger(CleanUpDiagnostics.class.getName());
		List<LogRecord> records = new ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		logger.addHandler(handler);
		try {
			EclipseJdtCleanUpImpl cleanup = impl(profile);
			assertThat(cleanup.cleanUp("class Foo {}", new File("Foo.java"))).isEqualTo("class Foo {}");
			cleanup.cleanUp("class Bar {}", new File("Bar.java"));
			assertThat(records).singleElement().satisfies(record -> {
				assertThat(record.getLevel()).isEqualTo(Level.WARNING);
				assertThat(record.getMessage()).contains("cleanup.unknown", "Foo.java", "not implemented");
			});
			assertThat(bootstrapCalls).hasValue(0);
		} finally {
			logger.removeHandler(handler);
		}
	}

	@Test
	void strictModePropagatesConfigurationFailures() {
		RecordingCleanUp thrower = new RecordingCleanUp() {
			@Override
			public void setOptions(CleanUpOptions options) {
				throw new IllegalArgumentException("broken options");
			}
		};
		EclipseJdtCleanUpImpl cleanup = new EclipseJdtCleanUpImpl(enabledProfile(),
				Map.of("sp_cleanup.strict", "true"), List.of(thrower), bootstrapCalls::incrementAndGet);
		assertThatThrownBy(() -> cleanup.cleanUp("class Foo {}", new File("Foo.java")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Foo.java", "setOptions failed", "broken options");
		assertThat(thrower.source).isNull();
	}

	@Test
	void configurationFailureIsLoggedAndDoesNotPreventOtherCleanups() {
		Logger logger = Logger.getLogger(CleanUpDiagnostics.class.getName());
		Level previous = logger.getLevel();
		List<LogRecord> records = new ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		RecordingCleanUp thrower = new RecordingCleanUp() {
			@Override
			public void setOptions(CleanUpOptions options) {
				throw new IllegalStateException("invalid options");
			}
		};
		RecordingCleanUp recorder = new RecordingCleanUp();
		logger.addHandler(handler);
		logger.setLevel(Level.FINE);
		try {
			assertThat(impl(enabledProfile(), thrower, recorder).cleanUp("class Foo {}", null)).isEqualTo("class Foo {}");
			assertThat(thrower.source).isNull();
			assertThat(recorder.source).isEqualTo("class Foo {}");
			assertThat(records).anySatisfy(record -> {
				assertThat(record.getMessage()).contains("setOptions failed", "skipped");
				assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class);
			});
		} finally {
			logger.removeHandler(handler);
			logger.setLevel(previous);
		}
	}

	private static class RecordingCleanUp implements ICleanUp {
		CleanUpOptions options;
		String source;

		@Override
		public void setOptions(CleanUpOptions options) {
			this.options = options;
		}

		@Override
		public String[] getStepDescriptions() {
			return new String[0];
		}

		@Override
		public CleanUpRequirements getRequirements() {
			return new CleanUpRequirements(true, true, false, new HashMap<>());
		}

		@Override
		public RefactoringStatus checkPreConditions(IJavaProject project, ICompilationUnit[] units, IProgressMonitor monitor) {
			return new RefactoringStatus();
		}

		@Override
		public ICleanUpFix createFix(CleanUpContext context) {
			source = new String(((org.eclipse.jdt.internal.core.CompilationUnit) context.getCompilationUnit()).getContents());
			return null;
		}

		@Override
		public RefactoringStatus checkPostConditions(IProgressMonitor monitor) {
			return new RefactoringStatus();
		}
	}
}
