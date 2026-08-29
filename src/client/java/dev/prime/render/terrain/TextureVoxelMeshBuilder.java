package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpritePixelView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Interns one pixel-height mesh per texture/UV/orientation and records lightweight face instances.
 */
final class TextureVoxelMeshBuilder {
    static final float MAXIMUM_HEIGHT = VoxelSurfaceSettings.BASE_HEIGHT;

    private final boolean buildOpacityMicromap;
    private final float maximumHeight;
    private final ClusterTranslationWork work;
    private final Map<Key, Integer> meshIndices = new HashMap<>();
    private final Set<Key> rejected = new HashSet<>();
    private final ArrayList<CpuVoxelMesh> meshes = new ArrayList<>();
    private final CpuVoxelInstances.Builder instances = new CpuVoxelInstances.Builder();

    TextureVoxelMeshBuilder(boolean buildOpacityMicromap, float maximumHeight) {
        this(
                buildOpacityMicromap,
                maximumHeight,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    TextureVoxelMeshBuilder(
            boolean buildOpacityMicromap,
            float maximumHeight,
            ClusterTranslationWork work) {
        if (!Float.isFinite(maximumHeight) || maximumHeight < 0.0F) {
            throw new IllegalArgumentException(
                    "Voxel-surface maximum height must be finite and nonnegative");
        }
        this.buildOpacityMicromap = buildOpacityMicromap;
        this.maximumHeight = maximumHeight;
        this.work = Objects.requireNonNull(work, "work");
    }

    boolean add(MergeFace face) {
        int flags = PrimitivePacking.unpackControl(
                face.primitive()[3], face.primitive()[5])
                & ~PrimitivePacking.CONTROL_TANGENT_NEGATIVE;
        Key key = new Key(
                face.sprite(),
                face.planeAxis(),
                face.normalSign(),
                face.uv0U(),
                face.uv0V(),
                face.uv1U(),
                face.uv1V(),
                face.uv2U(),
                face.uv2V(),
                face.sprite(),
                face.uv0U(),
                face.uv0V(),
                face.uv1U(),
                face.uv1V(),
                face.uv2U(),
                face.uv2V(),
                flags,
                null);
        return this.add(
                face,
                face.labPbrHeightMap(),
                face.labPbrMaterialMap(),
                null,
                key,
                face.primitive()[3]);
    }

    boolean addComposite(MergeFace base, MergeFace overlay) {
        if (base.planeAxis() != overlay.planeAxis()
                || base.normalSign() != overlay.normalSign()
                || base.planeCell() != overlay.planeCell()
                || base.cellU() != overlay.cellU()
                || base.cellV() != overlay.cellV()) {
            throw new IllegalArgumentException(
                    "Voxel-surface material layers are not coincident");
        }
        int baseFlags = PrimitivePacking.unpackControl(
                base.primitive()[3], base.primitive()[5])
                & ~PrimitivePacking.CONTROL_TANGENT_NEGATIVE;
        int overlayFlags = PrimitivePacking.unpackControl(
                overlay.primitive()[3], overlay.primitive()[5])
                & ~PrimitivePacking.CONTROL_TANGENT_NEGATIVE;
        if (PrimitivePacking.isCutout(baseFlags)
                || PrimitivePacking.isTransmissive(baseFlags)
                || !PrimitivePacking.isCutout(overlayFlags)
                || PrimitivePacking.isTransmissive(overlayFlags)
                || PrimitivePacking.isFoliage(overlayFlags)
                || ((baseFlags | overlayFlags) & PrimitivePacking.CONTROL_ANIMATED) != 0
                || base.sprite().animated()
                || overlay.sprite().animated()) {
            return false;
        }
        int resolvedOverlayFlags = overlayFlags & ~PrimitivePacking.CONTROL_ALPHA_CUTOUT;
        PrimitivePacking.requireValidControl(resolvedOverlayFlags);
        Overlay resolvedOverlay = new Overlay(
                overlay.sprite(),
                overlay.uv0U(),
                overlay.uv0V(),
                overlay.uv1U(),
                overlay.uv1V(),
                overlay.uv2U(),
                overlay.uv2V(),
                resolvedOverlayFlags,
                base.primitive()[3] & 0x00ff_ffff);
        Key key = new Key(
                base.sprite(),
                base.planeAxis(),
                base.normalSign(),
                base.uv0U(),
                base.uv0V(),
                base.uv1U(),
                base.uv1V(),
                base.uv2U(),
                base.uv2V(),
                base.sprite(),
                base.uv0U(),
                base.uv0V(),
                base.uv1U(),
                base.uv1V(),
                base.uv2U(),
                base.uv2V(),
                baseFlags,
                resolvedOverlay);
        return this.add(
                base,
                base.labPbrHeightMap(),
                base.labPbrMaterialMap(),
                overlay.labPbrMaterialMap(),
                key,
                overlay.primitive()[3]);
    }

    private boolean add(
            MergeFace face,
            LabPbrHeightMap heightMap,
            LabPbrMaterialMap materialMap,
            LabPbrMaterialMap overlayMaterialMap,
            Key key,
            int packedTintFlags) {
        this.work.step();
        if (this.rejected.contains(key)) {
            return false;
        }
        Integer meshIndex = this.meshIndices.get(key);
        if (meshIndex == null) {
            CpuVoxelMesh mesh = buildMesh(
                    key,
                    heightMap,
                    materialMap,
                    overlayMaterialMap,
                    this.maximumHeight,
                    this.buildOpacityMicromap,
                    this.work);
            if (mesh == null) {
                this.rejected.add(key);
                return false;
            }
            meshIndex = this.meshes.size();
            this.meshes.add(mesh);
            this.meshIndices.put(key, meshIndex);
        }
        float translationX;
        float translationY;
        float translationZ;
        switch (face.planeAxis()) {
            case 0 -> {
                translationX = face.plane();
                translationY = face.cellU();
                translationZ = face.cellV();
            }
            case 1 -> {
                translationX = face.cellU();
                translationY = face.plane();
                translationZ = face.cellV();
            }
            case 2 -> {
                translationX = face.cellU();
                translationY = face.cellV();
                translationZ = face.plane();
            }
            default -> throw new IllegalArgumentException("Invalid face plane axis");
        }
        this.instances.add(
                meshIndex,
                packedTintFlags & 0x00ff_ffff,
                translationX,
                translationY,
                translationZ);
        return true;
    }

    ListResult build() {
        this.work.checkpoint();
        return new ListResult(List.copyOf(this.meshes), this.instances.build());
    }

    static float heightFromArgb(int argb) {
        return lumaFromArgb(argb) * MAXIMUM_HEIGHT;
    }

    private static float lumaFromArgb(int argb) {
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        // Fixed-point BT.601 Y' coefficients preserve exact black and white endpoints.
        return (77 * red + 150 * green + 29 * blue) / (255.0F * 256.0F);
    }

    private static CpuVoxelMesh buildMesh(
            Key key,
            LabPbrHeightMap labPbrHeightMap,
            LabPbrMaterialMap labPbrMaterialMap,
            LabPbrMaterialMap overlayLabPbrMaterialMap,
            float maximumHeight,
            boolean buildOpacityMicromap,
            ClusterTranslationWork work) {
        work.checkpoint();
        SpritePixels reliefPixels = SpritePixels.create(key.reliefSprite);
        if (reliefPixels == null) {
            return null;
        }
        if (reliefPixels.width != reliefPixels.height) {
            throw new IllegalArgumentException(
                    "Voxel-surface textures must have square animation frames");
        }
        int size = reliefPixels.width;
        float[] heights = new float[Math.multiplyExact(size, size)];
        MaterialSample[] materials = new MaterialSample[heights.length];
        SpritePixels materialPixels = key.sprite.equals(key.reliefSprite)
                ? reliefPixels
                : SpritePixels.create(key.sprite);
        if (materialPixels == null) {
            return null;
        }
        UvTransform materialUv = new UvTransform(
                key.uv0U,
                key.uv0V,
                key.uv1U,
                key.uv1V,
                key.uv2U,
                key.uv2V);
        UvTransform reliefUv = new UvTransform(
                key.reliefUv0U,
                key.reliefUv0V,
                key.reliefUv1U,
                key.reliefUv1V,
                key.reliefUv2U,
                key.reliefUv2V);
        Overlay overlay = key.overlay;
        SpritePixels overlayPixels =
                overlay == null ? null : SpritePixels.create(overlay.sprite);
        if (overlay != null && overlayPixels == null) {
            return null;
        }
        boolean bakeBase = overlay != null
                || canBakeMaterial(key.sprite, key.flags);
        UvTransform overlayUv = overlay == null
                ? null
                : new UvTransform(
                        overlay.uv0U,
                        overlay.uv0V,
                        overlay.uv1U,
                        overlay.uv1V,
                        overlay.uv2U,
                        overlay.uv2V);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                work.step();
                int index = x + y * size;
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float localU = materialUv.u(u, v);
                float localV = materialUv.v(u, v);
                float reliefLocalU = reliefUv.u(u, v);
                float reliefLocalV = reliefUv.v(u, v);
                int baseArgb = bakeBase
                        ? materialPixels.sample(localU, localV, key.sprite)
                        : 0;
                MaterialSample material = bakeBase
                        ? bakedMaterial(
                                key.sprite,
                                materialPixels,
                                localU,
                                localV,
                                key.flags,
                                labPbrMaterialMap,
                                baseArgb,
                                0x00ff_ffff,
                                false)
                        : sampledMaterial(
                                key.sprite, localU, localV, key.flags);
                if (overlay != null) {
                    float overlayLocalU = overlayUv.u(u, v);
                    float overlayLocalV = overlayUv.v(u, v);
                    int overlayArgb = overlayPixels.sample(
                            overlayLocalU, overlayLocalV, overlay.sprite);
                    material = overlayArgb >>> 24 >= 128
                            ? bakedMaterial(
                                    overlay.sprite,
                                    overlayPixels,
                                    overlayLocalU,
                                    overlayLocalV,
                                    overlay.flags,
                                    overlayLabPbrMaterialMap,
                                    overlayArgb,
                                    0x00ff_ffff,
                                    false)
                            : bakedMaterial(
                                    key.sprite,
                                    materialPixels,
                                    localU,
                                    localV,
                                    key.flags,
                                    labPbrMaterialMap,
                                    baseArgb,
                                    overlay.baseTint,
                                    true);
                }
                materials[index] = material;
                heights[index] = labPbrHeightMap == null
                        ? lumaFromArgb(reliefPixels.sample(
                                reliefLocalU, reliefLocalV, key.reliefSprite))
                        : labPbrHeightMap.sample(
                                reliefPixels.firstFrame,
                                reliefPixels.localU(reliefLocalU),
                                reliefPixels.localV(reliefLocalV));
            }
        }
        scaleHeights(heights, maximumHeight);
        float referenceHeight = borderReferenceHeight(heights, size);
        alignToReferencePlane(heights, referenceHeight);
        return buildHeightField(
                key,
                size,
                heights,
                materials,
                buildOpacityMicromap,
                work);
    }

    private static boolean canBakeMaterial(CapturedSprite sprite, int flags) {
        return !PrimitivePacking.isCutout(flags)
                && !PrimitivePacking.isTransmissive(flags)
                && (flags & PrimitivePacking.CONTROL_ANIMATED) == 0
                && !sprite.animated();
    }

    private static MaterialSample sampledMaterial(
            CapturedSprite sprite, float localU, float localV, int flags) {
        return new MaterialSample(
                sprite,
                sprite.textureId(),
                localU,
                localV,
                flags,
                0x00ff_ffff,
                LabPbrMaterialMap.DEFAULT_NORMAL,
                LabPbrMaterialMap.DEFAULT_SPECULAR,
                0);
    }

    private static MaterialSample bakedMaterial(
            CapturedSprite sprite,
            SpritePixels pixels,
            float localU,
            float localV,
            int flags,
            LabPbrMaterialMap materialMap,
            int argb,
            int packedTint,
            boolean ownsTint) {
        float sampledU = pixels.localU(localU);
        float sampledV = pixels.localV(localV);
        int packedNormal = materialMap == null
                ? LabPbrMaterialMap.DEFAULT_NORMAL
                : materialMap.sampleNormal(pixels.firstFrame, sampledU, sampledV);
        int packedSpecular = materialMap == null
                ? LabPbrMaterialMap.DEFAULT_SPECULAR
                : materialMap.sampleSpecular(pixels.firstFrame, sampledU, sampledV);
        int mode = PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL
                | (ownsTint ? PrimitivePacking.CONSTANT_UV_OWN_TINT : 0);
        return new MaterialSample(
                sprite,
                sprite.textureId(),
                localU,
                localV,
                flags,
                bakeSrgbTint(argb, packedTint),
                packedNormal,
                packedSpecular,
                mode);
    }

    private static int bakeSrgbTint(int argb, int packedTint) {
        // A composite instance owns the overlay's biome tint. Fold the base layer's independent
        // tint into its texel in linear light so both layers still match the atlas shading path.
        int red = bakeSrgbChannel(argb >>> 16 & 0xff, packedTint & 0xff);
        int green = bakeSrgbChannel(argb >>> 8 & 0xff, packedTint >>> 8 & 0xff);
        int blue = bakeSrgbChannel(argb & 0xff, packedTint >>> 16 & 0xff);
        return red | green << 8 | blue << 16;
    }

    private static int bakeSrgbChannel(int texel, int tint) {
        double linear = decodeSrgb(texel / 255.0) * decodeSrgb(tint / 255.0);
        double encoded = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(encoded * 255.0)));
    }

    private static double decodeSrgb(double encoded) {
        return encoded <= 0.04045
                ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    static CpuVoxelMesh buildOpaqueHeightField(
            int size, int[] argb, int planeAxis, int normalSign) {
        if (size <= 0
                || argb.length != Math.multiplyExact(size, size)
                || planeAxis < 0
                || planeAxis > 2
                || Math.abs(normalSign) != 1) {
            throw new IllegalArgumentException(
                    "Invalid source for an opaque voxel height field");
        }
        float[] heights = new float[argb.length];
        MaterialSample[] materials = new MaterialSample[argb.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = x + y * size;
                heights[index] = lumaFromArgb(argb[index]);
                materials[index] = new MaterialSample(
                        null,
                        1,
                        (x + 0.5F) / size,
                        (y + 0.5F) / size,
                        0,
                        PrimitivePacking.packTint(argb[index]) & 0x00ff_ffff,
                        LabPbrMaterialMap.DEFAULT_NORMAL,
                        LabPbrMaterialMap.DEFAULT_SPECULAR,
                        PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL);
            }
        }
        scaleHeights(heights, MAXIMUM_HEIGHT);
        float referenceHeight = borderReferenceHeight(heights, size);
        alignToReferencePlane(heights, referenceHeight);
        Key key = new Key(
                null,
                planeAxis,
                normalSign,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                null,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                null);
        return buildHeightField(
                key,
                size,
                heights,
                materials,
                false,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    static float borderReferenceHeight(float[] heights, int size) {
        if (size <= 0 || heights.length != Math.multiplyExact(size, size)) {
            throw new IllegalArgumentException(
                    "Height field does not match its declared square size");
        }
        float reference = Float.POSITIVE_INFINITY;
        for (int x = 0; x < size; x++) {
            reference = Math.min(reference, heights[x]);
            reference = Math.min(reference, heights[x + (size - 1) * size]);
        }
        for (int y = 1; y < size - 1; y++) {
            reference = Math.min(reference, heights[y * size]);
            reference = Math.min(reference, heights[y * size + size - 1]);
        }
        return reference;
    }

    static void alignToReferencePlane(float[] heights, float referenceHeight) {
        // The border minimum defines the original block plane. Clamp darker interior samples to
        // preserve the outward-only surface contract instead of creating hidden inward cavities.
        for (int index = 0; index < heights.length; index++) {
            heights[index] = Math.max(heights[index] - referenceHeight, 0.0F);
        }
    }

    private static void scaleHeights(float[] heights, float maximumHeight) {
        for (int index = 0; index < heights.length; index++) {
            heights[index] *= maximumHeight;
        }
    }

    private static CpuVoxelMesh buildHeightField(
            Key key,
            int size,
            float[] heights,
            MaterialSample[] materials,
            boolean buildOpacityMicromap,
            ClusterTranslationWork work) {
        Mesh mesh = new Mesh(key, buildOpacityMicromap);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                work.step();
                int index = x + y * size;
                float minimumU = x / (float) size;
                float maximumU = (x + 1) / (float) size;
                float minimumV = y / (float) size;
                float maximumV = (y + 1) / (float) size;
                float height = heights[index];
                mesh.addTop(
                        minimumU,
                        minimumV,
                        maximumU,
                        maximumV,
                        height,
                        materials[index]);
                if (x == 0 || height > heights[index - 1]) {
                    mesh.addWall(
                            0,
                            minimumU,
                            minimumV,
                            maximumV,
                            x == 0 ? 0.0F : heights[index - 1],
                            height,
                            -1,
                            materials[index]);
                }
                if (x == size - 1 || height > heights[index + 1]) {
                    mesh.addWall(
                            0,
                            maximumU,
                            minimumV,
                            maximumV,
                            x == size - 1 ? 0.0F : heights[index + 1],
                            height,
                            1,
                            materials[index]);
                }
                if (y == 0 || height > heights[index - size]) {
                    mesh.addWall(
                            1,
                            minimumV,
                            minimumU,
                            maximumU,
                            y == 0 ? 0.0F : heights[index - size],
                            height,
                            -1,
                            materials[index]);
                }
                if (y == size - 1 || height > heights[index + size]) {
                    mesh.addWall(
                            1,
                            maximumV,
                            minimumU,
                            maximumU,
                            y == size - 1 ? 0.0F : heights[index + size],
                            height,
                            1,
                            materials[index]);
                }
            }
        }
        work.checkpoint();
        return mesh.build();
    }

    record ListResult(List<CpuVoxelMesh> meshes, CpuVoxelInstances instances) {
        ListResult {
            meshes = List.copyOf(meshes);
        }
    }

    private record Key(
            CapturedSprite sprite,
            int planeAxis,
            int normalSign,
            float uv0U,
            float uv0V,
            float uv1U,
            float uv1V,
            float uv2U,
            float uv2V,
            CapturedSprite reliefSprite,
            float reliefUv0U,
            float reliefUv0V,
            float reliefUv1U,
            float reliefUv1V,
            float reliefUv2U,
            float reliefUv2V,
            int flags,
            Overlay overlay) {
    }

    private record Overlay(
            CapturedSprite sprite,
            float uv0U,
            float uv0V,
            float uv1U,
            float uv1V,
            float uv2U,
            float uv2V,
            int flags,
            int baseTint) {
    }

    private record MaterialSample(
            CapturedSprite sprite,
            int textureId,
            float localU,
            float localV,
            int flags,
            int packedTint,
            int packedLabPbrNormal,
            int packedLabPbrSpecular,
            int constantMode) {
        MaterialSample {
            if (textureId <= 0
                    || textureId > PrimitivePacking.MAX_TEXTURE_ID
                    || sprite != null && sprite.textureId() != textureId) {
                throw new IllegalArgumentException("Voxel material has an invalid texture ID");
            }
        }

        boolean baked() {
            return (this.constantMode & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0;
        }
    }

    private record UvTransform(
            float u0, float v0, float u1, float v1, float u2, float v2) {
        float u(float x, float y) {
            return this.u0 + x * (this.u1 - this.u0) + y * (this.u2 - this.u0);
        }

        float v(float x, float y) {
            return this.v0 + x * (this.v1 - this.v0) + y * (this.v2 - this.v0);
        }
    }

    private record SpritePixels(
            SpritePixelView pixels,
            int width,
            int height,
            int firstFrame,
            int frameX,
            int frameY) {
        static SpritePixels create(CapturedSprite sprite) {
            SpritePixelView pixels = sprite.pixelView();
            if (pixels == null) {
                return null;
            }
            int width = sprite.frameWidth();
            int height = sprite.frameHeight();
            int firstFrame = sprite.uniqueFrame(0);
            int columns = Math.max(pixels.imageWidth() / width, 1);
            return new SpritePixels(
                    pixels,
                    width,
                    height,
                    firstFrame,
                    firstFrame % columns * width,
                    firstFrame / columns * height);
        }

        int sample(float localU, float localV, CapturedSprite sprite) {
            localU = this.localU(localU);
            localV = this.localV(localV);
            int x = Math.min((int) (localU * this.width), this.width - 1);
            int y = Math.min((int) (localV * this.height), this.height - 1);
            return this.pixels.argb(this.frameX + x, this.frameY + y);
        }

        float localU(float localU) {
            return clampUnit(localU);
        }

        float localV(float localV) {
            return clampUnit(localV);
        }
    }

    private static final class Mesh {
        private final Key key;
        private final boolean cutout;
        private final boolean transmissive;
        private final boolean cutoutGeometry;
        private final FloatBuilder positions = new FloatBuilder();
        private final IntBuilder primitives = new IntBuilder();
        private final OpacityMicromapData.Builder opacityMicromap;
        private int triangleCount;

        Mesh(Key key, boolean buildOpacityMicromap) {
            this.key = key;
            this.cutout = PrimitivePacking.isCutout(key.flags);
            this.transmissive = PrimitivePacking.isTransmissive(key.flags);
            this.cutoutGeometry = this.cutout && !this.transmissive;
            boolean frontFaceOnly =
                    (key.flags & PrimitivePacking.CONTROL_FRONT_FACE_ONLY) != 0;
            this.opacityMicromap = this.cutoutGeometry
                            && buildOpacityMicromap
                            && !frontFaceOnly
                    ? new OpacityMicromapData.Builder()
                    : null;
        }

        void addTop(
                float minimumU,
                float minimumV,
                float maximumU,
                float maximumV,
                float height,
                MaterialSample material) {
            float[][] corners = {
                this.point(minimumU, minimumV, height),
                this.point(maximumU, minimumV, height),
                this.point(maximumU, maximumV, height),
                this.point(minimumU, maximumV, height)
            };
            float[] normal = new float[3];
            normal[this.key.planeAxis] = this.key.normalSign;
            this.addQuad(corners, normal, material);
        }

        void addWall(
                int projectedAxis,
                float plane,
                float minimumAlong,
                float maximumAlong,
                float minimumHeight,
                float maximumHeight,
                int outwardSign,
                MaterialSample material) {
            if (!(maximumHeight > minimumHeight)) {
                return;
            }
            float[][] corners;
            if (projectedAxis == 0) {
                corners = new float[][] {
                    this.point(plane, minimumAlong, minimumHeight),
                    this.point(plane, maximumAlong, minimumHeight),
                    this.point(plane, maximumAlong, maximumHeight),
                    this.point(plane, minimumAlong, maximumHeight)
                };
            } else {
                corners = new float[][] {
                    this.point(minimumAlong, plane, minimumHeight),
                    this.point(maximumAlong, plane, minimumHeight),
                    this.point(maximumAlong, plane, maximumHeight),
                    this.point(minimumAlong, plane, maximumHeight)
                };
            }
            float[] normal = new float[3];
            int axis = projectedAxis == 0
                    ? MergeFace.projectedAxisU(this.key.planeAxis)
                    : MergeFace.projectedAxisV(this.key.planeAxis);
            normal[axis] = outwardSign;
            this.addQuad(corners, normal, material);
        }

        private float[] point(float u, float v, float height) {
            float[] result = new float[3];
            result[this.key.planeAxis] =
                    height == 0.0F ? 0.0F : this.key.normalSign * height;
            result[MergeFace.projectedAxisU(this.key.planeAxis)] = u;
            result[MergeFace.projectedAxisV(this.key.planeAxis)] = v;
            return result;
        }

        private void addQuad(
                float[][] corners, float[] outward, MaterialSample material) {
            float[] edgeOne = subtract(corners[1], corners[0]);
            float[] edgeTwo = subtract(corners[2], corners[0]);
            float[] cross = cross(edgeOne, edgeTwo);
            if (dot(cross, outward) < 0.0F) {
                float[] swap = corners[1];
                corners[1] = corners[3];
                corners[3] = swap;
            }
            this.addTriangle(corners[0], corners[1], corners[2], outward, material);
            this.addTriangle(corners[0], corners[2], corners[3], outward, material);
        }

        private void addTriangle(
                float[] first,
                float[] second,
                float[] third,
                float[] outward,
                MaterialSample material) {
            this.positions.add(first);
            this.positions.add(second);
            this.positions.add(third);
            int firstMaterialWord = material.baked()
                    ? material.packedLabPbrNormal
                    : PrimitivePacking.packConstantUv(material.localU);
            int secondMaterialWord = material.baked()
                    ? material.packedLabPbrSpecular
                    : PrimitivePacking.packConstantUv(material.localV);
            float[] edgeOne = subtract(second, first);
            float[] edgeTwo = subtract(third, first);
            int packedNormal = PrimitivePacking.packTriangleNormal(
                    edgeOne[0],
                    edgeOne[1],
                    edgeOne[2],
                    edgeTwo[0],
                    edgeTwo[1],
                    edgeTwo[2],
                    outward[0],
                    outward[1],
                    outward[2]);
            long tangent = PrimitivePacking.packTriangleTangent(
                    edgeOne[0],
                    edgeOne[1],
                    edgeOne[2],
                    edgeTwo[0],
                    edgeTwo[1],
                    edgeTwo[2],
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F,
                    packedNormal);
            int flags = material.flags;
            if ((tangent & 0x1_0000_0000L) != 0L
                    && (flags & PrimitivePacking.CONTROL_NORMAL_TEXTURE) != 0) {
                flags |= PrimitivePacking.CONTROL_TANGENT_NEGATIVE;
            }
            this.primitives.add(firstMaterialWord);
            this.primitives.add(secondMaterialWord);
            this.primitives.add(material.constantMode);
            this.primitives.add(
                    PrimitivePacking.packTintControl(material.packedTint, flags));
            this.primitives.add(packedNormal);
            this.primitives.add(PrimitivePacking.packControlTexture(
                    flags, material.textureId));
            this.primitives.add(PrimitivePacking.CONSTANT_UV_DENSITY);
            this.primitives.add((int) tangent);
            if (this.cutoutGeometry) {
                if (material.baked()) {
                    throw new IllegalStateException(
                            "Alpha-tested voxel material cannot discard its coverage texture");
                }
                if (this.opacityMicromap == null) {
                    // Retain the any-hit path when the device cannot consume opacity micromaps.
                } else {
                    this.opacityMicromap.addConstantTriangle(
                            material.sprite, material.localU, material.localV);
                }
            }
            this.triangleCount++;
        }

        CpuVoxelMesh build() {
            OpacityMicromapData opacity = !this.cutoutGeometry
                    ? OpacityMicromapData.EMPTY
                    : (this.opacityMicromap == null
                            ? OpacityMicromapData.fullyUnknown(this.triangleCount)
                            : this.opacityMicromap.build());
            return new CpuVoxelMesh(
                    this.positions.build(),
                    this.primitives.build(),
                    this.transmissive || this.cutoutGeometry ? 0 : this.triangleCount,
                    this.cutoutGeometry ? this.triangleCount : 0,
                    this.transmissive ? this.triangleCount : 0,
                    opacity);
        }
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    private static float[] subtract(float[] first, float[] second) {
        return new float[] {
            first[0] - second[0],
            first[1] - second[1],
            first[2] - second[2]
        };
    }

    private static float[] cross(float[] first, float[] second) {
        return new float[] {
            first[1] * second[2] - first[2] * second[1],
            first[2] * second[0] - first[0] * second[2],
            first[0] * second[1] - first[1] * second[0]
        };
    }

    private static float dot(float[] first, float[] second) {
        return first[0] * second[0]
                + first[1] * second[1]
                + first[2] * second[2];
    }

    private static final class FloatBuilder {
        private float[] values = new float[4096];
        private int size;

        void add(float[] value) {
            this.ensure(3);
            this.values[this.size++] = value[0];
            this.values[this.size++] = value[1];
            this.values[this.size++] = value[2];
        }

        float[] build() {
            return Arrays.copyOf(this.values, this.size);
        }

        private void ensure(int count) {
            if (this.size + count > this.values.length) {
                this.values = Arrays.copyOf(
                        this.values,
                        Math.max(this.values.length * 2, this.size + count));
            }
        }
    }

    private static final class IntBuilder {
        private int[] values = new int[4096];
        private int size;

        void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        int[] build() {
            return Arrays.copyOf(this.values, this.size);
        }
    }
}
