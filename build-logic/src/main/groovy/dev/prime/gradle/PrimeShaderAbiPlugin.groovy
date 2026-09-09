// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle

import dev.prime.gradle.shader.CompilePrimeSlangComputeShaders
import dev.prime.gradle.shader.CompilePrimeSlangProgram
import dev.prime.gradle.shader.AssemblePrimeSlangPrograms
import dev.prime.gradle.shader.GeneratePrimeShaderPrograms
import dev.prime.gradle.shader.PrimeShaderDependencyClosure
import dev.prime.gradle.shader.PrimeShaderDependencyGraph
import dev.prime.gradle.shader.PrimeShaderManifest
import dev.prime.gradle.shader.PrimeSlangCompilerGate
import dev.prime.gradle.shader.GenerateShaderAbi
import dev.prime.gradle.shader.PrepareNsightCapture
import dev.prime.gradle.shader.VerifyGeneratedSlangAbi
import dev.prime.gradle.shader.VerifySlangArtifactAbi
import dev.prime.gradle.shader.VerifySlangToolchain
import dev.prime.gradle.shader.VerifyPrimeShaderArchitecture
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeShaderAbiPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.extraProperties.set('primeShaderManifestType', PrimeShaderManifest)
        project.extensions.extraProperties.set('primeShaderTaskTypes', [
                generateAbi: GenerateShaderAbi,
                compileSlang: CompilePrimeSlangComputeShaders,
                compileProgram: CompilePrimeSlangProgram,
                assemblePrograms: AssemblePrimeSlangPrograms,
                generatePrograms: GeneratePrimeShaderPrograms,
                dependencyClosure: PrimeShaderDependencyClosure,
                dependencyGraph: PrimeShaderDependencyGraph,
                compilerGate: PrimeSlangCompilerGate,
                verifyArtifactAbi: VerifySlangArtifactAbi,
                prepareNsight: PrepareNsightCapture,
                verifyToolchain: VerifySlangToolchain,
                verifyGeneratedAbi: VerifyGeneratedSlangAbi,
                verifyArchitecture: VerifyPrimeShaderArchitecture
        ])
        project.apply(from: new File(project.rootDir,
                'build-logic/conventions/prime-shader-abi.gradle'))
    }
}
