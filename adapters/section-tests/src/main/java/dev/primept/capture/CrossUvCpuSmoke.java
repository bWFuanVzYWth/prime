package dev.primept.capture;

import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Actual vanilla JSON, texture and FaceBakery output; separate from the triangle-set oracle. */
final class CrossUvCpuSmoke {
    static void run(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String name : List.of("dandelion", "short_grass")) {
            String parentName = name.equals("dandelion") ? "cross" : "tinted_cross";
            var child = load(Identifier.withDefaultNamespace("block/" + name));
            check(child.parent().equals(Identifier.withDefaultNamespace("block/" + parentName)),
                  "Actual vanilla plant parent changed: " + name);
            var parent = load(child.parent());
            var slots = new TextureSlots.Resolver()
                                .addLast(child.textureSlots())
                                .addLast(parent.textureSlots())
                                .resolve(() -> name);
            var texture = slots.getMaterial("#cross").sprite();
            try (var sprite = SourceSpriteFixture.load(texture)) {
                var baker = new FixtureBaker(sprite, texture);
                var collection =
                        parent.geometry().bake(slots, baker, new ModelState() {}, () -> name);
                var quads = collection.getAll();
                check(quads.size() == 4 && collection.getQuads(null).size() == 4,
                      "Vanilla cross must have four unculled source quads");
                for (var direction : Direction.values())
                    check(collection.getQuads(direction).isEmpty(),
                          "Vanilla cross must not acquire a cull condition");
                verify(quads, sprite, name.equals("dandelion") ? -1 : 0);
                writeReference(directory, parentName, quads);
                // A controlled no-offset state isolates the actual model's local UV semantics.
                var state = Blocks.STONE.defaultBlockState();
                var model = new SingleVariant(new SimpleModelWrapper(
                        collection, false, new Material.Baked(sprite, false)));
                var router = new SectionSources(new BlockStateModelSet(Map.of(state, model), model),
                                                new FluidStateModelSet(Map.of(), null));
                try (var source = new SourcePages(); var frame = new SourcePages();
                     var tintResponse = new SourcePages()) {
                    source.header(SectionSources.GAME_VERSION, 2, 1, 1);
                    router.section(source, 0, 0, 0, SectionSourcesCpuSmoke.section(state));
                    source.i(0);
                    SectionSourcesCpuSmoke.write(source, directory.resolve(parentName + ".source"));
                    // One closed section, one model placement at (0,0,0).
                    frame.header(SectionSources.GAME_VERSION, 1, 1, 1)
                            .d(0)
                            .d(0)
                            .i(0)
                            .i(0)
                            .i(0)
                            .i(0)
                            .i(0)
                            .i(0)
                            .i(0)
                            .l(1)
                            .i(1)
                            .i(0)
                            .i(0)
                            .i(0)
                            .i(0);
                    SectionSourcesCpuSmoke.write(frame, directory.resolve(parentName + ".frame"));
                    if (name.equals("short_grass")) {
                        // Stone has no block color source: source kind 8, ARGB white; no callback.
                        tintResponse.header(SectionSources.GAME_VERSION, 3, 1, 1)
                                .l(1)
                                .i(0)
                                .i(8)
                                .i(-1)
                                .i(0);
                        SectionSourcesCpuSmoke.write(tintResponse,
                                                     directory.resolve(parentName + ".tint"));
                    }
                }
                Files.writeString(
                        directory.resolve(parentName + ".properties"),
                        "gameVersion=" + SectionSources.GAME_VERSION + "\nmodel=" + child.parent() +
                                "\nplant=" + name + "\ntexture=" + texture +
                                "\nreference=big-endian i32 quad-count; each quad has four xyzuv f32 vertices\n"
                                + "referenceCoordinates=resource-local, no state offset\n"
                                + "placement=0 0 0\nblocks=1\nstate=minecraft:stone\n"
                                +
                                "tint=controlled white; tinted_cross.tint preserves source slot0\n"
                                +
                                "pairing=reverse shift3, corners 3 2 1 0; U mirrored, V preserved\n");
            }
        }
        System.out.println(
                "PRIME_CROSS_UV_HOST_OK: MC " + SectionSources.GAME_VERSION +
                ", actual cross/tinted_cross JSON + FaceBakery + texture + SectionSources; 4 quads, odd reverse pairing, affine UV, no GPU/window");
    }

    private static CuboidModel load(Identifier id) throws Exception {
        try (var input = CrossUvCpuSmoke.class.getResourceAsStream(
                     "/assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json")) {
            check(input != null, "Missing vanilla model: " + id);
            return CuboidModel.fromStream(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private static void verify(List<BakedQuad> quads, TextureAtlasSprite sprite, int tint) {
        var used = new boolean[4];
        int pairs = 0;
        for (int a = 0; a < 4; ++a) {
            var first = quads.get(a);
            check(first.materialInfo().layer() == ChunkSectionLayer.CUTOUT &&
                          first.materialInfo().tintIndex() == tint,
                  "Actual cross material and tint must survive host baking");
            var diagonal = new Vector3f(first.position(0))
                                   .add(first.position(2))
                                   .sub(first.position(1))
                                   .sub(first.position(3));
            check(diagonal.lengthSquared() < 1e-12f, "Actual cross is a planar parallelogram");
            close(u(first, 0) + u(first, 2), u(first, 1) + u(first, 3), "Affine U");
            close(v(first, 0) + v(first, 2), v(first, 1) + v(first, 3), "Affine V");
            if (used[a])
                continue;
            int found = -1;
            for (int b = a + 1; b < 4; ++b) {
                int shift = reverseShift(first, quads.get(b));
                if (shift < 0)
                    continue;
                check(found < 0 && !used[b], "Each source quad needs a unique opposite");
                check(shift == 3, "Actual cross must exercise an odd reverse shift");
                found = b;
            }
            check(found >= 0, "Missing opposite cross quad");
            var second = quads.get(found);
            for (int i = 0; i < 4; ++i) {
                int other = 3 - i;
                close(u(first, i) + u(second, other), sprite.getU0() + sprite.getU1(),
                      "Opposite same-position U must be mirrored");
                close(v(first, i), v(second, other), "Opposite same-position V must match");
            }
            used[a] = used[found] = true;
            ++pairs;
        }
        check(pairs == 2, "Actual cross must contain two paired planes");
    }

    private static int reverseShift(BakedQuad a, BakedQuad b) {
        for (int shift = 0; shift < 4; ++shift) {
            boolean same = true;
            for (int i = 0; i < 4; ++i) {
                var x = a.position(i);
                var y = b.position((shift + 4 - i) % 4);
                same &= x.x() == y.x() && x.y() == y.y() && x.z() == y.z();
            }
            if (same)
                return shift;
        }
        return -1;
    }
    private static float u(BakedQuad quad, int i) {
        return UVPair.unpackU(quad.packedUV(i));
    }
    private static float v(BakedQuad quad, int i) {
        return UVPair.unpackV(quad.packedUV(i));
    }
    private static void close(float a, float b, String message) {
        check(Math.abs(a - b) < 1e-6f, message + ": " + a + " vs " + b);
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }

    private static void writeReference(Path directory, String name, List<BakedQuad> quads)
            throws Exception {
        var text = new StringBuilder("quad vertex x y z u v\n");
        try (var out = new DataOutputStream(
                     Files.newOutputStream(directory.resolve(name + ".quads")))) {
            out.writeInt(quads.size());
            for (int q = 0; q < quads.size(); ++q) {
                var quad = quads.get(q);
                for (int i = 0; i < 4; ++i) {
                    var p = quad.position(i);
                    float[] values = {p.x(), p.y(), p.z(), u(quad, i), v(quad, i)};
                    text.append(q).append(' ').append(i);
                    for (float value : values) {
                        out.writeFloat(value);
                        text.append(' ').append(value);
                    }
                    text.append('\n');
                }
            }
        }
        Files.writeString(directory.resolve(name + ".txt"), text);
    }

    private static final class FixtureBaker implements ModelBaker, ModelBaker.Interner {
        private final MaterialBaker materials;
        FixtureBaker(TextureAtlasSprite sprite, Identifier texture) {
            materials = CrossUvMaterialBaker.create(sprite, texture);
        }
        public ResolvedModel getModel(Identifier id) {
            throw new AssertionError("Geometry baking must not resolve unrelated models");
        }
        public BlockStateModelPart missingBlockModelPart() {
            throw new AssertionError("Actual cross resources must be complete");
        }
        public MaterialBaker materials() {
            return materials;
        }
        public ModelBaker.Interner interner() {
            return this;
        }
        public <T> T compute(ModelBaker.SharedOperationKey<T> key) {
            throw new AssertionError("Cross geometry has no shared operation callback");
        }
        public Vector3fc vector(Vector3fc vector) {
            return vector;
        }
        public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
            return material;
        }
    }
}
