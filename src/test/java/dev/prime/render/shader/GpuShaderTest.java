// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("gpu-shader")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(ShaderComputeExtension.class)
abstract class GpuShaderTest {
    ShaderComputeRunner runner;
}
