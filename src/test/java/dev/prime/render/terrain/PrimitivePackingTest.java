package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.material.CoverageMode;
import dev.prime.render.material.MaterialDetail;
import dev.prime.render.material.MaterialRecipe;
import dev.prime.render.material.MediumHint;
import dev.prime.render.material.PrimitiveControl;
import dev.prime.render.material.ScatteringFamily;
import org.junit.jupiter.api.Test;

final class PrimitivePackingTest {
    @Test
    void packsTwoHalfPrecisionCoordinatesInLittleWordOrder() {
        int packed = PrimitivePacking.packHalf2(0.25F, 0.75F);
        float x = Float.float16ToFloat((short) (packed & 0xffff));
        float y = Float.float16ToFloat((short) (packed >>> 16));
        assertEquals(0.25F, x);
        assertEquals(0.75F, y);
    }

    @Test
    void constantUvKeepsAdjacentTexelCentersDistinctOnA4096Atlas() {
        float first = 3073.5F / 4096.0F;
        float second = 3074.5F / 4096.0F;
        assertEquals(
                PrimitivePacking.packHalf2(first, first),
                PrimitivePacking.packHalf2(second, second));
        assertNotEquals(
                PrimitivePacking.packConstantUv(first),
                PrimitivePacking.packConstantUv(second));
        assertEquals(
                first,
                Float.intBitsToFloat(PrimitivePacking.packConstantUv(first)));
        assertEquals(
                PrimitivePacking.CONSTANT_UV_DENSITY,
                Float.floatToRawIntBits(-0.0F));
    }

    @Test
    void localUvPreservesAThreeTexelChainSlice() {
        int textureSize = 16;
        float first = 0.0F;
        float second = 3.0F / textureSize;
        int packedFirst = PrimitivePacking.packUv(first, 0.0F);
        int packedSecond = PrimitivePacking.packUv(second, 1.0F);

        float decodedFirst = PrimitivePacking.unpackUv(packedFirst, false);
        float decodedSecond = PrimitivePacking.unpackUv(packedSecond, false);

        assertEquals(3.0F, (decodedSecond - decodedFirst) * textureSize);
        assertEquals(0.0F, PrimitivePacking.unpackUv(packedFirst, true));
        assertEquals(1.0F, PrimitivePacking.unpackUv(packedSecond, true));
        assertEquals(
                3.0F,
                (Float.float16ToFloat(Float.floatToFloat16(second))
                                - Float.float16ToFloat(Float.floatToFloat16(first)))
                        * textureSize);
    }

    @Test
    void convertsArgbTintToRgba8() {
        assertEquals(0x80102040, PrimitivePacking.packTint(0x80402010));
        assertEquals(0xffffffff, PrimitivePacking.packTint(-1));
    }

    @Test
    void canonicalControlKeepsMaterialAndGeometrySemanticsIndependent() {
        MaterialRecipe recipe = new MaterialRecipe(
                CoverageMode.ALPHA_CUTOUT,
                ScatteringFamily.DIELECTRIC_THIN,
                MediumHint.GLASS,
                MaterialDetail.NORMAL_TEXTURE.bit() | MaterialDetail.OPTICAL_TEXTURE.bit(),
                BuiltinMaterialClass.GLAZED_CERAMIC);
        PrimitiveControl value = new PrimitiveControl(recipe, true, true, true);
        int control = PrimitivePacking.encode(value);

        assertEquals(value, PrimitivePacking.decode(control));
        assertTrue(PrimitivePacking.isCutout(control));
        assertTrue(PrimitivePacking.isTransmissive(control));
        assertTrue(PrimitivePacking.isThinWalled(control));
        assertFalse(PrimitivePacking.isFoliage(control));
        assertEquals(
                BuiltinMaterialClass.GLAZED_CERAMIC.id(),
                control >>> PrimitivePacking.CONTROL_BUILTIN_SHIFT & 15);
    }

    @Test
    void everyValidControlRoundTripsThroughItsPhysicalPackingMode() {
        int valid = 0;
        for (int control = 0; control <= PrimitivePacking.CONTROL_MASK; control++) {
            PrimitiveControl decoded;
            try {
                decoded = PrimitivePacking.decode(control);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            valid++;
            assertEquals(control, PrimitivePacking.encode(decoded));
            int tint = PrimitivePacking.packTintControl(0x00a0_6040, control);
            int physical = PrimitivePacking.packControlEmitter(
                    control, PrimitivePacking.MAX_EMITTER_INDEX);
            assertEquals(
                    PrimitivePacking.MAX_EMITTER_INDEX,
                    PrimitivePacking.unpackEmitterIndex(physical));
            int dynamic = PrimitivePacking.packDynamicControl(control, 63, true);
            assertEquals(control, PrimitivePacking.unpackControl(tint, dynamic));
            assertEquals(63, PrimitivePacking.unpackDynamicTextureIndex(dynamic));
            assertEquals(control, PrimitivePacking.unpackControl(tint, physical));
        }
        assertTrue(valid > 0);
    }

    @Test
    void flagsAndEmitterIndexRoundTripAcrossTheWholeAbiRange() {
        int flags = PrimitivePacking.CONTROL_ALPHA_CUTOUT
                | PrimitivePacking.CONTROL_NORMAL_TEXTURE
                | PrimitivePacking.CONTROL_OPTICAL_TEXTURE
                | PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY
                | BuiltinMaterialClass.COPPER.id() << PrimitivePacking.CONTROL_BUILTIN_SHIFT;
        int tint = PrimitivePacking.packTintControl(
                PrimitivePacking.packTint(0x80402010), flags);
        assertEquals(0x00102040, tint & 0x00ff_ffff);
        for (int emitter : new int[] {
            0, 1, 1024, PrimitivePacking.MAX_EMITTER_INDEX
        }) {
            int packed = PrimitivePacking.packControlEmitter(flags, emitter);
            assertEquals(flags, PrimitivePacking.unpackControl(tint, packed));
            assertEquals(emitter, PrimitivePacking.unpackEmitterIndex(packed));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packControlEmitter(flags, PrimitivePacking.MAX_EMITTER_INDEX + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packControlEmitter(flags, PrimitivePacking.NO_EMITTER_INDEX));
        int textured = PrimitivePacking.packControlTexture(flags, PrimitivePacking.MAX_TEXTURE_ID);
        assertEquals(PrimitivePacking.MAX_TEXTURE_ID, PrimitivePacking.unpackTextureId(textured));
        assertEquals(PrimitivePacking.NO_EMITTER_INDEX, PrimitivePacking.unpackEmitterIndex(textured));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packControlEmitter(13 << 11, 0));
    }

    @Test
    void dynamicTextureAndVisibleEmissionNeverDecodeAsALightTreeEmitter() {
        int flags = PrimitivePacking.CONTROL_ALPHA_CUTOUT
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY;
        int visible = PrimitivePacking.packDynamicControl(flags, 63, true);
        int dark = PrimitivePacking.packDynamicControl(flags, 1, false);

        assertEquals(63, PrimitivePacking.unpackDynamicTextureIndex(visible));
        assertEquals(flags, PrimitivePacking.unpackControl(
                PrimitivePacking.packTintControl(-1, flags), visible));
        assertEquals(PrimitivePacking.NO_EMITTER_INDEX,
                PrimitivePacking.unpackEmitterIndex(visible));
        assertTrue(PrimitivePacking.hasVisibleEmission(visible));
        assertEquals(1, PrimitivePacking.unpackDynamicTextureIndex(dark));
        assertFalse(PrimitivePacking.hasVisibleEmission(dark));
        assertEquals(0, PrimitivePacking.unpackDynamicTextureIndex(
                PrimitivePacking.packControlEmitter(flags, 0)));
        int untextured = PrimitivePacking.packDynamicControl(flags, 0, false);
        assertEquals(0, PrimitivePacking.unpackDynamicTextureIndex(untextured));
        assertEquals(
                PrimitivePacking.NO_EMITTER_INDEX,
                PrimitivePacking.unpackEmitterIndex(untextured));
        int redAlpha = PrimitivePacking.packDynamicControl(flags, 7, false, true);
        assertTrue(PrimitivePacking.usesDynamicRedAlpha(redAlpha));
        assertEquals(7, PrimitivePacking.unpackDynamicTextureIndex(redAlpha));
        assertFalse(PrimitivePacking.usesDynamicRedAlpha(dark));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packDynamicControl(flags, 0, false, true));
    }

    @Test
    void octahedralEncodingPreservesNormalDirection() {
        assertNormalDirection(1.0F, 0.0F, 0.0F);
        assertNormalDirection(0.0F, -1.0F, 0.0F);
        assertNormalDirection(0.0F, 0.0F, -1.0F);
        assertNormalDirection(0.25F, -0.5F, 0.75F);
    }

    @Test
    void textureAvailabilityAndTangentHandednessUseIndependentBits() {
        int flags = PrimitivePacking.withMaterialDetails(
                PrimitivePacking.CONTROL_ALPHA_CUTOUT, true, true, true);
        assertEquals(
                PrimitivePacking.CONTROL_ALPHA_CUTOUT
                        | PrimitivePacking.CONTROL_NORMAL_TEXTURE
                        | PrimitivePacking.CONTROL_OPTICAL_TEXTURE
                        | PrimitivePacking.CONTROL_TANGENT_NEGATIVE,
                flags);
        assertEquals(0, PrimitivePacking.withMaterialDetails(0, false, false, true));

        long positive = PrimitivePacking.packTriangleTangent(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F,
                0.0F, 1.0F,
                0.0F, 0.0F, 1.0F);
        assertPackedNormalDirection((int) positive, 1.0F, 0.0F, 0.0F);
        assertFalse((positive & 0x1_0000_0000L) != 0L);

        long negative = PrimitivePacking.packTriangleTangent(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F,
                0.0F, -1.0F,
                0.0F, 0.0F, 1.0F);
        assertTrue((negative & 0x1_0000_0000L) != 0L);
    }

    @Test
    void triangleTangentRejectsMissingFaceNormal() {
        assertThrows(IllegalArgumentException.class, () -> PrimitivePacking.packTriangleTangent(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F,
                0.0F, 1.0F,
                0.0F, 0.0F, 0.0F));
    }

    @Test
    void uvDensityUsesTheLargestWorldToUvSingularValue() {
        int packed = PrimitivePacking.packUvDensity(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                0.25F, 0.0F,
                0.0F, 0.5F);
        assertEquals(0.5F, Float.intBitsToFloat(packed), 1.0e-7F);
        assertEquals(0.0F, Float.intBitsToFloat(PrimitivePacking.packUvDensity(
                1.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F,
                1.0F, 0.0F,
                2.0F, 0.0F)));
    }

    @Test
    void meshLayoutRejectsMismatchedArrayLengths() {
        CpuSectionMesh mesh = new CpuSectionMesh(
                new float[9], new int[CpuSectionMesh.PRIMITIVE_WORDS], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY);
        assertEquals(68L, mesh.byteSize());
        assertThrows(IllegalArgumentException.class, () -> new CpuSectionMesh(
                new float[8], new int[CpuSectionMesh.PRIMITIVE_WORDS], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new CpuSectionMesh(
                new float[9], new int[CpuSectionMesh.PRIMITIVE_WORDS - 1], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY));
    }

    private static void assertNormalDirection(float x, float y, float z) {
        float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        int packed = PrimitivePacking.packOctahedralNormal(x, y, z);
        assertPackedNormalDirection(packed, x, y, z);
    }

    private static void assertPackedNormalDirection(int packed, float x, float y, float z) {
        float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        float decodedX = Math.max(-1.0F, (short) packed / 32767.0F);
        float decodedY = Math.max(-1.0F, (short) (packed >>> 16) / 32767.0F);
        float decodedZ = 1.0F - Math.abs(decodedX) - Math.abs(decodedY);
        if (decodedZ < 0.0F) {
            float oldX = decodedX;
            decodedX = (1.0F - Math.abs(decodedY)) * Math.copySign(1.0F, oldX);
            decodedY = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, decodedY);
        }
        float decodedInverseLength = 1.0F / (float) Math.sqrt(
                decodedX * decodedX + decodedY * decodedY + decodedZ * decodedZ);
        float dot = x * decodedX * decodedInverseLength
                + y * decodedY * decodedInverseLength
                + z * decodedZ * decodedInverseLength;
        assertTrue(dot > 0.9999F, () -> "Decoded normal dot product was " + dot);
    }
}
