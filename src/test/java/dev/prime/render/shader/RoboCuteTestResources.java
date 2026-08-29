package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

final class RoboCuteTestResources {
    static final int GGX_LUT_WIDTH = 44;
    static final int GGX_LUT_HEIGHT = 32;
    static final int GGX_LUT_DEPTH = 159;
    static final int GGX_LUT_CHANNELS = 4;
    static final int GGX_LUT_BYTE_SIZE = GGX_LUT_WIDTH
            * GGX_LUT_HEIGHT
            * GGX_LUT_DEPTH
            * GGX_LUT_CHANNELS
            * Short.BYTES;

    private RoboCuteTestResources() {
    }

    static ByteBuffer transmissionGgxEnergy() throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of(
                System.getProperty("user.dir"),
                "third_party",
                "robocute",
                "author-bsdf-hotfix-2026-07-24",
                "trans_ggx.bytes"));
        if (bytes.length != GGX_LUT_BYTE_SIZE) {
            throw new IOException(
                    "Unexpected transmission GGX LUT size "
                            + bytes.length
                            + ", expected "
                            + GGX_LUT_BYTE_SIZE);
        }
        ByteBuffer result = ByteBuffer.allocateDirect(bytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return result.put(bytes).flip();
    }

    static ByteBuffer bindTransmissionGgxEnergy(ShaderComputeRunner runner) throws IOException {
        ByteBuffer lut = transmissionGgxEnergy();
        runner.loadTransmissionGgxEnergy(
                lut,
                GGX_LUT_WIDTH,
                GGX_LUT_HEIGHT,
                GGX_LUT_DEPTH);
        return lut;
    }
}
