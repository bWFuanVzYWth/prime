// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Publishes independently cached artifacts and their content manifest. */
abstract class AssemblePrimeSlangPrograms extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract ConfigurableFileCollection getPrograms()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void assemble() {
        def published = new File(outputDirectory.get().asFile, 'prime/shaders')
        published.mkdirs()
        def expected = new TreeSet<String>()
        programs.files.sort { it.name }.each { source ->
            expected.add(source.name)
            java.nio.file.Files.copy(
                    source.toPath(), new File(published, source.name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        expected.add('manifest.sha256')
        published.listFiles().findAll { !expected.contains(it.name) }.each {
            java.nio.file.Files.delete(it.toPath())
        }
        def manifest = expected.findAll { it != 'manifest.sha256' }.collect { name ->
            def digest = java.security.MessageDigest.getInstance('SHA-256')
                    .digest(new File(published, name).bytes).encodeHex().toString()
            return "${digest}  ${name}"
        }.join(System.lineSeparator()) + System.lineSeparator()
        new File(published, 'manifest.sha256').setText(manifest, 'UTF-8')
    }
}
