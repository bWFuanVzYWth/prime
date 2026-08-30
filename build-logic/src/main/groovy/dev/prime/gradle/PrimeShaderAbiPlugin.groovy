package dev.prime.gradle

import dev.prime.gradle.shader.CompilePrimeSlangComputeShaders
import dev.prime.gradle.shader.CompilePrimeSlangProgram
import dev.prime.gradle.shader.AssemblePrimeSlangPrograms
import dev.prime.gradle.shader.GeneratePrimeShaderPrograms
import dev.prime.gradle.shader.GenerateRendererDataContracts
import dev.prime.gradle.shader.PrimeShaderDependencyClosure
import dev.prime.gradle.shader.PrimeSlangCompilerGate
import dev.prime.gradle.shader.GenerateShaderAbi
import dev.prime.gradle.shader.PrepareNsightCapture
import dev.prime.gradle.shader.VerifyGeneratedSlangAbi
import dev.prime.gradle.shader.VerifySlangRayPayloadAbi
import dev.prime.gradle.shader.VerifySlangToolchain
import dev.prime.gradle.shader.VerifyPrimeShaderArchitecture
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeShaderAbiPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.extraProperties.set('primeShaderTaskTypes', [
                generateAbi: GenerateShaderAbi,
                generateRendererData: GenerateRendererDataContracts,
                compileSlang: CompilePrimeSlangComputeShaders,
                compileProgram: CompilePrimeSlangProgram,
                assemblePrograms: AssemblePrimeSlangPrograms,
                generatePrograms: GeneratePrimeShaderPrograms,
                dependencyClosure: PrimeShaderDependencyClosure,
                compilerGate: PrimeSlangCompilerGate,
                verifyPayloadAbi: VerifySlangRayPayloadAbi,
                prepareNsight: PrepareNsightCapture,
                verifyToolchain: VerifySlangToolchain,
                verifyGeneratedAbi: VerifyGeneratedSlangAbi,
                verifyArchitecture: VerifyPrimeShaderArchitecture
        ])
        project.apply(from: new File(project.rootDir,
                'build-logic/conventions/prime-shader-abi.gradle'))
    }
}
