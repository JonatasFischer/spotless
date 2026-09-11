/*
 * Copyright 2026 DiffPlug
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
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reports skipped actions without treating a normal "no fix needed" result as an error. */
public final class CleanUpDiagnostics {
	private static final Logger LOGGER = Logger.getLogger(CleanUpDiagnostics.class.getName());
	private final boolean strict;

	public CleanUpDiagnostics(boolean strict) {
		this.strict = strict;
	}

	public void skipped(String action, File file, String reason, Exception cause) {
		String message = "Eclipse Clean Up [" + action + "] skipped in "
				+ (file == null ? "<source>" : file.getPath()) + ": " + reason;
		if (strict) {
			throw new IllegalStateException(message, cause);
		}
		LOGGER.warning(message);
		if (cause != null) {
			LOGGER.log(Level.FINE, message, cause);
		}
	}
}
