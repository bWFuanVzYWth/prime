package dev.prime.gradle

import dev.prime.gradle.shader.PrimeShaderManifest
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeNativePackagingPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.extraProperties.set('primeShaderManifestType', PrimeShaderManifest)
        project.apply(from: new File(project.rootDir,
                'build-logic/conventions/prime-native-packaging.gradle'))
    }
}
