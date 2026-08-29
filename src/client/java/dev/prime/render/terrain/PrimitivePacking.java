package dev.prime.render.terrain;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.material.CoverageMode;
import dev.prime.render.material.MaterialDetail;
import dev.prime.render.material.MaterialRecipe;
import dev.prime.render.material.MediumHint;
import dev.prime.render.material.PrimitiveControl;
import dev.prime.render.material.ScatteringFamily;

public final class PrimitivePacking {
    private static final float UV_FIXED_SCALE = 65_536.0F;
    private static final int UV_FIXED_ONE = 0xffff;
    public static final int CONTROL_ALPHA_CUTOUT = 1;
    public static final int CONTROL_ANIMATED = 1 << 1;
    public static final int CONTROL_SCATTERING_SHIFT = 2;
    public static final int CONTROL_SCATTERING_MASK = 3 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_OPAQUE = 0;
    public static final int CONTROL_DIELECTRIC_SOLID = 1 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_DIELECTRIC_THIN = 2 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_FOLIAGE_THIN = 3 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_WATER_MEDIUM = 1 << 4;
    public static final int CONTROL_NORMAL_TEXTURE = 1 << 5;
    public static final int CONTROL_OPTICAL_TEXTURE = 1 << 6;
    public static final int CONTROL_TANGENT_NEGATIVE = 1 << 7;
    /** Accept only the canonical BLAS winding's front side during any-hit traversal. */
    public static final int CONTROL_FRONT_FACE_ONLY = 1 << 8;
    /** Retired static-control bit. Dynamic payloads use the corresponding physical bit. */
    private static final int CONTROL_STATIC_RESERVED = 1 << 9;
    /** The 24-bit payload names a section-local light emitter instead of a logical texture. */
    public static final int CONTROL_EMITTER_PAYLOAD = 1 << 10;
    public static final int CONTROL_BUILTIN_SHIFT = 11;
    public static final int CONTROL_BUILTIN_MASK = 15 << CONTROL_BUILTIN_SHIFT;
    public static final int CONTROL_MASK = (1 << 15) - 1;
    public static final int MATERIAL_RECIPE_MASK = 0xff | CONTROL_BUILTIN_MASK;
    public static final int DYNAMIC_TEXTURE_FLAG = 1 << 31;
    public static final int VISIBLE_EMISSION_FLAG = 1 << 10;
    public static final int DYNAMIC_RED_ALPHA_FLAG = 1 << 9;
    public static final int DYNAMIC_TEXTURE_INDEX_MASK = 63;
    public static final int NO_EMITTER_INDEX = -1;
    public static final int MAX_EMITTER_INDEX = (1 << 24) - 2;
    public static final int MAX_TEXTURE_ID = (1 << 24) - 1;
    /**
     * Negative zero tags a constant UV stored as two full-precision floats in uv0 and uv1.
     * Ordinary negative densities remain the periodic macro-face encoding.
     */
    public static final int CONSTANT_UV_DENSITY = Float.floatToRawIntBits(-0.0F);
    /** Baked primitives keep their own color instead of inheriting the voxel instance tint. */
    public static final int CONSTANT_UV_OWN_TINT = 1;
    /** uv0/uv1 contain baked material texels instead of local texture coordinates. */
    public static final int CONSTANT_UV_BAKED_MATERIAL = 1 << 1;
    public static final int CONSTANT_UV_MODE_MASK =
            CONSTANT_UV_OWN_TINT | CONSTANT_UV_BAKED_MATERIAL;

    private PrimitivePacking() {
    }

    /** Packs geometry controls and a logical texture identity for an ordinary static primitive. */
    public static int packControlTexture(int control, int textureId) {
        requireValidControl(control);
        if ((control & (CONTROL_EMITTER_PAYLOAD | CONTROL_STATIC_RESERVED)) != 0) {
            throw new IllegalArgumentException("Texture payload conflicts with primitive control");
        }
        if (textureId <= 0 || textureId > MAX_TEXTURE_ID) {
            throw new IllegalArgumentException("Texture ID exceeds its nonzero 24-bit ABI field");
        }
        return physicalControl(control) | textureId << 3;
    }

    /** Packs geometry controls, the emitter tag, and a local light-emitter index. */
    public static int packControlEmitter(int control, int emitterIndex) {
        if (emitterIndex < 0 || emitterIndex > MAX_EMITTER_INDEX) {
            throw new IllegalArgumentException("Primitive emitter index exceeds its 24-bit ABI field");
        }
        requireValidControl(control);
        return physicalControl(control | CONTROL_EMITTER_PAYLOAD)
                | (emitterIndex + 1) << 3;
    }

    public static int packTintControl(int packedTint, int control) {
        requireValidControl(control);
        return (packedTint & 0x00ff_ffff) | (control & 0xff) << 24;
    }

    public static int unpackControl(int packedTint, int packedFlagsEmitter) {
        return packedTint >>> 24
                | (packedFlagsEmitter & 3) << 8
                | (packedFlagsEmitter >>> 16 & CONTROL_BUILTIN_MASK);
    }

    public static int unpackEmitterIndex(int packed) {
        if ((packed & DYNAMIC_TEXTURE_FLAG) != 0) {
            return NO_EMITTER_INDEX;
        }
        if ((packed & (CONTROL_EMITTER_PAYLOAD >>> 8)) == 0) {
            return NO_EMITTER_INDEX;
        }
        int encoded = packed >>> 3 & 0x00ff_ffff;
        return encoded == 0 ? NO_EMITTER_INDEX : encoded - 1;
    }

    public static int withEmitterIndex(int packed, int emitterIndex) {
        if ((packed & (CONTROL_EMITTER_PAYLOAD >>> 8)) == 0) {
            throw new IllegalArgumentException(
                    "Primitive payload does not carry an emitter");
        }
        int control = (packed & 3) << 8
                | (packed >>> 16 & CONTROL_BUILTIN_MASK);
        return packControlEmitter(control, emitterIndex);
    }

    public static int unpackTextureId(int packed) {
        if ((packed & DYNAMIC_TEXTURE_FLAG) != 0
                || (packed & (CONTROL_EMITTER_PAYLOAD >>> 8)) != 0) {
            return 0;
        }
        return packed >>> 3 & MAX_TEXTURE_ID;
    }

    /**
     * Encodes a renderer-owned texture without assigning a light-tree emitter.
     *
     * <p>Dynamic geometry never carries a local emitter index. Its otherwise-unused high payload
     * bits select a scene texture and optionally mark directly visible emission.
     */
    public static int packDynamicControl(
            int control, int textureIndex, boolean visibleEmission) {
        return packDynamicControl(control, textureIndex, visibleEmission, false);
    }

    public static int packDynamicControl(
            int control,
            int textureIndex,
            boolean visibleEmission,
            boolean redAlpha) {
        requireValidControl(control);
        if (textureIndex < 0 || textureIndex > DYNAMIC_TEXTURE_INDEX_MASK) {
            throw new IllegalArgumentException("Dynamic texture index exceeds its ABI field");
        }
        if (redAlpha && textureIndex == 0) {
            throw new IllegalArgumentException(
                    "Red-channel coverage requires a dynamic texture");
        }
        return physicalControl(control)
                | textureIndex << 3
                | (visibleEmission ? VISIBLE_EMISSION_FLAG : 0)
                | (redAlpha ? DYNAMIC_RED_ALPHA_FLAG : 0)
                | DYNAMIC_TEXTURE_FLAG;
    }

    public static int unpackDynamicTextureIndex(int packed) {
        return (packed & DYNAMIC_TEXTURE_FLAG) == 0
                ? 0
                : packed >>> 3 & DYNAMIC_TEXTURE_INDEX_MASK;
    }

    public static boolean hasVisibleEmission(int packed) {
        return (packed & (DYNAMIC_TEXTURE_FLAG | VISIBLE_EMISSION_FLAG))
                == (DYNAMIC_TEXTURE_FLAG | VISIBLE_EMISSION_FLAG);
    }

    public static int packHalf2(float x, float y) {
        int low = Float.floatToFloat16(x) & 0xffff;
        int high = Float.floatToFloat16(y) & 0xffff;
        return low | high << 16;
    }

    public static boolean usesDynamicRedAlpha(int packed) {
        return (packed & (DYNAMIC_TEXTURE_FLAG | DYNAMIC_RED_ALPHA_FLAG))
                == (DYNAMIC_TEXTURE_FLAG | DYNAMIC_RED_ALPHA_FLAG);
    }

    /**
     * Packs normalized local texture coordinates as UQ0.16, reserving {@code 0xffff} for the
     * inclusive endpoint. Power-of-two texel boundaries remain exact up to 32,768 pixels.
     */
    public static int packUv(float u, float v) {
        return packUv(u) | packUv(v) << 16;
    }

    public static float unpackUv(int packed, boolean high) {
        int fixed = high ? packed >>> 16 : packed & 0xffff;
        return fixed == UV_FIXED_ONE ? 1.0F : fixed / UV_FIXED_SCALE;
    }

    static int upgradeHalfUv(int packed) {
        return packUv(
                Float.float16ToFloat((short) packed),
                Float.float16ToFloat((short) (packed >>> 16)));
    }

    private static int packUv(float coordinate) {
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Local texture UV must be finite and normalized");
        }
        if (coordinate == 1.0F) {
            return UV_FIXED_ONE;
        }
        return Math.min(Math.round(coordinate * UV_FIXED_SCALE), UV_FIXED_ONE - 1);
    }

    public static int packConstantUv(float coordinate) {
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Constant local texture UV must be finite and normalized");
        }
        return Float.floatToRawIntBits(coordinate);
    }

    public static int packTint(int argb) {
        int alpha = argb >>> 24;
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    public static int encode(PrimitiveControl value) {
        MaterialRecipe material = value.material();
        int control = (material.coverage() == CoverageMode.ALPHA_CUTOUT
                        ? CONTROL_ALPHA_CUTOUT
                        : 0)
                | (value.animated() ? CONTROL_ANIMATED : 0)
                | material.scattering().encoded() << CONTROL_SCATTERING_SHIFT
                | (material.medium() == MediumHint.WATER ? CONTROL_WATER_MEDIUM : 0)
                | (material.hasDetail(MaterialDetail.NORMAL_TEXTURE)
                        ? CONTROL_NORMAL_TEXTURE
                        : 0)
                | (material.hasDetail(MaterialDetail.OPTICAL_TEXTURE)
                        ? CONTROL_OPTICAL_TEXTURE
                        : 0)
                | (value.tangentNegative() ? CONTROL_TANGENT_NEGATIVE : 0)
                | (value.frontFaceOnly() ? CONTROL_FRONT_FACE_ONLY : 0)
                | material.builtinClass().id() << CONTROL_BUILTIN_SHIFT;
        requireValidControl(control);
        return control;
    }

    public static PrimitiveControl decode(int control) {
        requireValidControl(control);
        ScatteringFamily scattering = ScatteringFamily.fromEncoded(
                control >>> CONTROL_SCATTERING_SHIFT & 3);
        MediumHint medium = (control & CONTROL_WATER_MEDIUM) != 0
                ? MediumHint.WATER
                : scattering == ScatteringFamily.DIELECTRIC_SOLID
                                || scattering == ScatteringFamily.DIELECTRIC_THIN
                        ? MediumHint.GLASS
                        : MediumHint.NONE;
        int details = ((control & CONTROL_NORMAL_TEXTURE) != 0
                        ? MaterialDetail.NORMAL_TEXTURE.bit()
                        : 0)
                | ((control & CONTROL_OPTICAL_TEXTURE) != 0
                        ? MaterialDetail.OPTICAL_TEXTURE.bit()
                        : 0);
        MaterialRecipe recipe = new MaterialRecipe(
                (control & CONTROL_ALPHA_CUTOUT) != 0
                        ? CoverageMode.ALPHA_CUTOUT
                        : CoverageMode.OPAQUE,
                scattering,
                medium,
                details,
                BuiltinMaterialClass.fromId(
                        control >>> CONTROL_BUILTIN_SHIFT & 15));
        return new PrimitiveControl(
                recipe,
                (control & CONTROL_ANIMATED) != 0,
                (control & CONTROL_TANGENT_NEGATIVE) != 0,
                (control & CONTROL_FRONT_FACE_ONLY) != 0);
    }

    public static int materialRecipeControl(int control) {
        requireValidControl(control);
        return control & MATERIAL_RECIPE_MASK;
    }

    public static boolean isCutout(int control) {
        return (control & CONTROL_ALPHA_CUTOUT) != 0;
    }

    public static boolean isTransmissive(int control) {
        int scattering = control & CONTROL_SCATTERING_MASK;
        return scattering == ScatteringFamily.DIELECTRIC_SOLID.encoded()
                        << CONTROL_SCATTERING_SHIFT
                || scattering == ScatteringFamily.DIELECTRIC_THIN.encoded()
                        << CONTROL_SCATTERING_SHIFT;
    }

    public static boolean isFoliage(int control) {
        return (control & CONTROL_SCATTERING_MASK)
                == ScatteringFamily.FOLIAGE_THIN.encoded() << CONTROL_SCATTERING_SHIFT;
    }

    public static boolean isThinWalled(int control) {
        int scattering = control & CONTROL_SCATTERING_MASK;
        return scattering == CONTROL_DIELECTRIC_THIN
                || scattering == CONTROL_FOLIAGE_THIN;
    }

    public static int encodeLegacySemantics(
            boolean cutout,
            boolean animatedTexture,
            boolean transmissive,
            boolean thinWalled,
            boolean water,
            boolean foliage) {
        ScatteringFamily scattering = foliage
                ? ScatteringFamily.FOLIAGE_THIN
                : transmissive
                        ? thinWalled
                                ? ScatteringFamily.DIELECTRIC_THIN
                                : ScatteringFamily.DIELECTRIC_SOLID
                        : ScatteringFamily.OPAQUE;
        MediumHint medium = water
                ? MediumHint.WATER
                : transmissive ? MediumHint.GLASS : MediumHint.NONE;
        return encode(new PrimitiveControl(
                new MaterialRecipe(
                        cutout ? CoverageMode.ALPHA_CUTOUT : CoverageMode.OPAQUE,
                        scattering,
                        medium,
                        0,
                        BuiltinMaterialClass.DEFAULT),
                animatedTexture,
                false,
                false));
    }

    static void requireValidControl(int control) {
        if ((control & ~CONTROL_MASK) != 0
                || (control & (CONTROL_EMITTER_PAYLOAD | CONTROL_STATIC_RESERVED)) != 0) {
            throw new IllegalArgumentException("Primitive control contains reserved ABI bits");
        }
        decodeUnchecked(control);
    }

    public static int withMaterialDetails(
            int control,
            boolean normalMap,
            boolean opticalMap,
            boolean tangentNegative) {
        int result = control
                | (normalMap ? CONTROL_NORMAL_TEXTURE : 0)
                | (opticalMap ? CONTROL_OPTICAL_TEXTURE : 0)
                | (normalMap && tangentNegative ? CONTROL_TANGENT_NEGATIVE : 0);
        requireValidControl(result);
        return result;
    }

    private static int physicalControl(int control) {
        return (control >>> 8 & 7) | (control & CONTROL_BUILTIN_MASK) << 16;
    }

    private static void decodeUnchecked(int control) {
        int scattering = control >>> CONTROL_SCATTERING_SHIFT & 3;
        boolean cutout = (control & CONTROL_ALPHA_CUTOUT) != 0;
        boolean water = (control & CONTROL_WATER_MEDIUM) != 0;
        boolean foliage = scattering == ScatteringFamily.FOLIAGE_THIN.encoded();
        boolean dielectricSolid = scattering == ScatteringFamily.DIELECTRIC_SOLID.encoded();
        if (water && !dielectricSolid) {
            throw new IllegalArgumentException("Water must be a solid dielectric");
        }
        if (foliage && !cutout) {
            throw new IllegalArgumentException("Foliage must use alpha-cutout coverage");
        }
        if ((control & CONTROL_TANGENT_NEGATIVE) != 0
                && (control & CONTROL_NORMAL_TEXTURE) == 0) {
            throw new IllegalArgumentException(
                    "Negative tangent handedness requires a normal texture");
        }
        BuiltinMaterialClass.fromId(control >>> CONTROL_BUILTIN_SHIFT & 15);
    }

    /**
     * Packs the UV tangent into the low 32 bits and reports negative bitangent handedness in bit
     * 32 of the returned value. The supplied normal is the exact triangle cross product used by
     * the BLAS; it is never recovered from a packed shading attribute.
     */
    public static long packTriangleTangent(
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2,
            float normalX,
            float normalY,
            float normalZ) {
        float determinant = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        double normalLength = Math.sqrt(
                (double) normalX * normalX
                        + (double) normalY * normalY
                        + (double) normalZ * normalZ);
        if (!(normalLength > 0.0) || !Double.isFinite(normalLength)) {
            throw new IllegalArgumentException("Triangle tangent requires a finite face normal");
        }
        normalX = (float) (normalX / normalLength);
        normalY = (float) (normalY / normalLength);
        normalZ = (float) (normalZ / normalLength);
        float tangentX;
        float tangentY;
        float tangentZ;
        float bitangentX;
        float bitangentY;
        float bitangentZ;
        if (Math.abs(determinant) > 1.0e-20F && Float.isFinite(determinant)) {
            float inverse = 1.0F / determinant;
            tangentX = (edgeOneX * deltaV2 - edgeTwoX * deltaV1) * inverse;
            tangentY = (edgeOneY * deltaV2 - edgeTwoY * deltaV1) * inverse;
            tangentZ = (edgeOneZ * deltaV2 - edgeTwoZ * deltaV1) * inverse;
            bitangentX = (edgeTwoX * deltaU1 - edgeOneX * deltaU2) * inverse;
            bitangentY = (edgeTwoY * deltaU1 - edgeOneY * deltaU2) * inverse;
            bitangentZ = (edgeTwoZ * deltaU1 - edgeOneZ * deltaU2) * inverse;
        } else {
            float axisX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            float axisY = axisX == 0.0F ? 1.0F : 0.0F;
            tangentX = axisY * normalZ;
            tangentY = -axisX * normalZ;
            tangentZ = axisX * normalY - axisY * normalX;
            bitangentX = normalY * tangentZ - normalZ * tangentY;
            bitangentY = normalZ * tangentX - normalX * tangentZ;
            bitangentZ = normalX * tangentY - normalY * tangentX;
        }
        float normalProjection = tangentX * normalX + tangentY * normalY + tangentZ * normalZ;
        tangentX -= normalProjection * normalX;
        tangentY -= normalProjection * normalY;
        tangentZ -= normalProjection * normalZ;
        float lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        if (!(lengthSquared > 1.0e-20F) || !Float.isFinite(lengthSquared)) {
            tangentX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            tangentY = tangentX == 0.0F ? 1.0F : 0.0F;
            tangentZ = 0.0F;
            normalProjection = tangentX * normalX + tangentY * normalY;
            tangentX -= normalProjection * normalX;
            tangentY -= normalProjection * normalY;
            tangentZ -= normalProjection * normalZ;
            lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(Math.max(lengthSquared, 1.0e-20F));
        tangentX *= inverseLength;
        tangentY *= inverseLength;
        tangentZ *= inverseLength;
        float crossX = normalY * tangentZ - normalZ * tangentY;
        float crossY = normalZ * tangentX - normalX * tangentZ;
        float crossZ = normalX * tangentY - normalY * tangentX;
        boolean negative = crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0F;
        return Integer.toUnsignedLong(packOctahedralNormal(tangentX, tangentY, tangentZ))
                | (negative ? 0x1_0000_0000L : 0L);
    }

    public static int packOctahedralNormal(float x, float y, float z) {
        float inverseLength = 1.0F / Math.max(1.0e-20F, Math.abs(x) + Math.abs(y) + Math.abs(z));
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        if (z < 0.0F) {
            float oldX = x;
            x = (1.0F - Math.abs(y)) * Math.copySign(1.0F, oldX);
            y = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, y);
        }
        int packedX = packSnorm16(x);
        int packedY = packSnorm16(y);
        return packedX & 0xffff | packedY << 16;
    }

    /**
     * Packs the largest normalized local-UV change per world-space unit as one float.
     *
     * <p>This is the largest singular value of the triangle's world-to-UV differential. The hit
     * shader combines it with the logical texture extent and the ray-cone footprint, so arbitrary
     * baked-model scaling is handled without storing triangle positions in the shader record.
     */
    public static int packUvDensity(
            float edge1X,
            float edge1Y,
            float edge1Z,
            float edge2X,
            float edge2Y,
            float edge2Z,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2) {
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        float denominator = normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(denominator > 1.0e-20F) || !Float.isFinite(denominator)) {
            return Float.floatToRawIntBits(0.0F);
        }

        // cross(edge2, normal) and cross(normal, edge1) are the reciprocal tangent basis.
        float firstBasisX = edge2Y * normalZ - edge2Z * normalY;
        float firstBasisY = edge2Z * normalX - edge2X * normalZ;
        float firstBasisZ = edge2X * normalY - edge2Y * normalX;
        float secondBasisX = normalY * edge1Z - normalZ * edge1Y;
        float secondBasisY = normalZ * edge1X - normalX * edge1Z;
        float secondBasisZ = normalX * edge1Y - normalY * edge1X;
        float inverseDenominator = 1.0F / denominator;
        float gradientUx = (deltaU1 * firstBasisX + deltaU2 * secondBasisX) * inverseDenominator;
        float gradientUy = (deltaU1 * firstBasisY + deltaU2 * secondBasisY) * inverseDenominator;
        float gradientUz = (deltaU1 * firstBasisZ + deltaU2 * secondBasisZ) * inverseDenominator;
        float gradientVx = (deltaV1 * firstBasisX + deltaV2 * secondBasisX) * inverseDenominator;
        float gradientVy = (deltaV1 * firstBasisY + deltaV2 * secondBasisY) * inverseDenominator;
        float gradientVz = (deltaV1 * firstBasisZ + deltaV2 * secondBasisZ) * inverseDenominator;

        float uu = gradientUx * gradientUx + gradientUy * gradientUy + gradientUz * gradientUz;
        float vv = gradientVx * gradientVx + gradientVy * gradientVy + gradientVz * gradientVz;
        float uv = gradientUx * gradientVx + gradientUy * gradientVy + gradientUz * gradientVz;
        float discriminant = (uu - vv) * (uu - vv) + 4.0F * uv * uv;
        float largestEigenvalue = 0.5F * (uu + vv + (float) Math.sqrt(Math.max(discriminant, 0.0F)));
        float density = (float) Math.sqrt(Math.max(largestEigenvalue, 0.0F));
        return Float.floatToRawIntBits(Float.isFinite(density) ? density : 0.0F);
    }

    private static int packSnorm16(float value) {
        float clamped = Math.max(-1.0F, Math.min(1.0F, value));
        return Math.round(clamped * 32767.0F) & 0xffff;
    }
}
