import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Coverage scope for each module's Eclipse Clean Up implementation. */
abstract class CleanUpCoverageExtension {
  abstract val classes: ListProperty<String>
  abstract val excludedClasses: ListProperty<String>
  abstract val tests: ListProperty<String>
  abstract val classDirectories: ConfigurableFileCollection
  abstract val sourceDirectories: ConfigurableFileCollection
  abstract val mutationThreshold: Property<Int>
  abstract val jvmArgs: ListProperty<String>
}
