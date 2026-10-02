package dev.primept.capture;

import dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Test-only serialization of typed sources into the existing independent Rust oracle format. */
final class SourceFixtureWire {
    private static long first(MemorySegment range) {
        return PrimeMcRange.offset(range);
    }
    private static int count(MemorySegment range) {
        return Math.toIntExact(PrimeMcRange.count(range));
    }
    private static String string(McSourceBatch b, MemorySegment range) {
        return new String(
                b.bytes.data().asSlice(first(range), count(range)).toArray(ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
    }
    static void resources(McSourceBatch b, SourcePages out) {
        for (long n = 0; n < b.sprites.count(); n++) {
            var s = b.sprites.get(n);
            out.i(6).i(PrimeMcSprite.id(s)).string(string(b, PrimeMcSprite.name(s)));
            for (int i = 0; i < 4; i++)
                out.f(PrimeMcSprite.bounds(s, i));
            out.i(PrimeMcSprite.extent(s, 0)).i(PrimeMcSprite.extent(s, 1));
            var images = PrimeMcSprite.images(s);
            out.i(count(images));
            for (int i = 0; i < count(images); i++)
                image(b, out, b.images.get(first(images) + i));
            var frames = PrimeMcSprite.frames(s);
            out.i(PrimeMcSprite.interpolate(s)).i(count(frames));
            for (int i = 0; i < count(frames); i++) {
                var f = b.frames.get(first(frames) + i);
                out.i(PrimeMcAnimationFrame.frame(f)).i(PrimeMcAnimationFrame.duration(f));
            }
            int normal = PrimeMcSprite.normal_image(s), specular = PrimeMcSprite.specular_image(s);
            if (normal != -1 || specular != -1) {
                out.i(9).i(PrimeMcSprite.id(s));
                plane(b, out, normal);
                plane(b, out, specular);
            }
        }
        for (long n = 0; n < b.faces.count(); n++) {
            var f = b.faces.get(n);
            var u = PrimeMcFace.u(f);
            var v = PrimeMcFace.v(f);
            var w = PrimeMcFace.words(f);
            out.i(4).i(PrimeMcFace.id(f)).i(count(u)).i(count(v)).i(count(w));
            for (var range : new MemorySegment[] {u, v})
                for (int i = 0; i < count(range); i++)
                    out.d(b.coordinates.get(first(range) + i).get(ValueLayout.JAVA_DOUBLE, 0));
            for (int i = 0; i < count(w); i++)
                out.l(b.words.get(first(w) + i).get(ValueLayout.JAVA_LONG, 0));
        }
        for (long n = 0; n < b.fluids.count(); n++) {
            var f = b.fluids.get(n);
            out.i(5).i(PrimeMcFluid.id(f)).i(PrimeMcFluid.layer(f)).i(PrimeMcFluid.flags(f));
            for (int i = 0; i < 3; i++) {
                out.i(PrimeMcFluid.identities(f, i));
                for (int j = 0; j < 4; j++)
                    out.f(PrimeMcFluid.bounds(f, i * 4 + j));
            }
        }
        for (long n = 0; n < b.models.count(); n++) {
            var m = b.models.get(n);
            int kind = PrimeMcModel.kind(m);
            out.i(2).i(PrimeMcModel.id(m)).i(kind);
            if (kind == 1) {
                var q = PrimeMcModel.quads(m);
                out.i(count(q));
                for (int i = 0; i < count(q); i++) {
                    var v = b.quads.get(first(q) + i);
                    out.i(PrimeMcQuad.face(v))
                            .i(PrimeMcQuad.tint(v))
                            .i(PrimeMcQuad.layer(v))
                            .i(PrimeMcQuad.sprite(v))
                            .i(PrimeMcQuad.emission(v));
                    for (int j = 0; j < 4; j++) {
                        for (int k = 0; k < 3; k++)
                            out.f(PrimeMcQuad.positions(v, j * 3 + k));
                        out.l(PrimeMcQuad.uv_pairs(v, j));
                    }
                }
            } else if (kind == 2 || kind == 3) {
                var c = PrimeMcModel.children(m);
                out.i(count(c));
                for (int i = 0; i < count(c); i++) {
                    var v = b.children.get(first(c) + i);
                    if (kind == 2)
                        out.i(PrimeMcModelChild.weight(v));
                    out.i(PrimeMcModelChild.model(v));
                }
            } else if (kind == 4)
                out.i(PrimeMcModel.alias(m));
        }
        for (long n = 0; n < b.states.count(); n++) {
            var s = b.states.get(n);
            out.i(1).i(PrimeMcState.id(s))
                    .i(PrimeMcState.flags(s))
                    .i(PrimeMcState.model(s))
                    .string(string(b, PrimeMcState.name(s)));
            for (int i = 0; i < 6; i++)
                out.i(PrimeMcState.faces(s, i));
            out.i(PrimeMcState.support(s))
                    .string(string(b, PrimeMcState.fluid_name(s)))
                    .i(PrimeMcState.fluid_level(s))
                    .i(PrimeMcState.fluid_falling(s))
                    .i(PrimeMcState.fluid_material(s))
                    .i(PrimeMcState.emission(s));
            var p = PrimeMcState.placement(s);
            out.i(PrimeMcPlacement.offset(p))
                    .f(PrimeMcPlacement.horizontal(p))
                    .f(PrimeMcPlacement.vertical(p))
                    .i(PrimeMcPlacement.seed(p));
        }
    }
    private static void plane(McSourceBatch b, SourcePages out, int id) {
        out.i(id == -1 ? 0 : 1);
        if (id != -1)
            image(b, out, b.images.get(Integer.toUnsignedLong(id)));
    }
    private static void image(McSourceBatch b, SourcePages out, MemorySegment image) {
        var pixels = PrimeMcImage.pixels(image);
        out.i(PrimeMcImage.width(image)).i(PrimeMcImage.height(image)).i(count(pixels) / 4);
        for (int i = 0; i < count(pixels); i += 4)
            out.i(b.bytes.data().get(ValueLayout.JAVA_INT_UNALIGNED, first(pixels) + i));
    }
    static void sections(McSourceBatch b, SourcePages out) {
        for (long n = 0; n < b.sections.count(); n++) {
            var s = b.sections.get(n);
            out.i(3).i(PrimeMcSection.x(s))
                    .i(PrimeMcSection.y(s))
                    .i(PrimeMcSection.z(s))
                    .i(PrimeMcSection.present(s));
            if (PrimeMcSection.present(s) == 0)
                continue;
            var p = PrimeMcSection.palette(s);
            var w = PrimeMcSection.storage(s);
            out.i(PrimeMcSection.bits(s)).i(count(p)).i(count(w));
            for (int i = 0; i < count(p); i++)
                out.i(b.palette.get(first(p) + i).get(ValueLayout.JAVA_INT, 0));
            for (int i = 0; i < count(w); i++)
                out.l(b.words.get(first(w) + i).get(ValueLayout.JAVA_LONG, 0));
        }
    }
    static void source(McSourceBatch resources, McSourceBatch sections, SourcePages out,
                       long batch) {
        out.header(SectionSources.GAME_VERSION, 2, 1, batch);
        resources(resources, out);
        sections(sections, out);
        out.i(0);
    }
    static void plan(MemorySegment p, SourcePages out) {
        var id = PrimeMcPlan.identity(p);
        out.header(PrimeMcIdentity.game_version(id), 1, PrimeMcIdentity.epoch(id),
                   PrimeMcIdentity.batch(id));
        out.d(PrimeMcPlan.position(p, 0))
                .d(PrimeMcPlan.position(p, 1))
                .i(PrimeMcPlan.radius(p))
                .i(PrimeMcPlan.min_y(p))
                .i(PrimeMcPlan.max_y(p));
        for (int i = 0; i < 4; i++)
            out.i(PrimeMcPlan.source(p, i));
        out.l(PrimeMcPlan.tick(p));
        long count = PrimeMcPlan.event_count(p);
        var events = PrimeMcPlan.events(p).reinterpret(count * PrimeMcEvent.SIZE);
        for (long i = 0; i < count; i++) {
            var e = events.asSlice(i * PrimeMcEvent.SIZE, PrimeMcEvent.SIZE);
            out.i(PrimeMcEvent.kind(e))
                    .i(PrimeMcEvent.x(e))
                    .i(PrimeMcEvent.y(e))
                    .i(PrimeMcEvent.z(e));
        }
        out.i(0);
    }
    static void definitions(McSourceBatch b, SourcePages out) {
        var d = b.definitions.get(0);
        out.l(PrimeMcBiomeDefinitions.seed(d));
        for (int i = 0; i < 256; i++)
            out.i(PrimeMcBiomeDefinitions.permutation(d, i));
        out.d(PrimeMcBiomeDefinitions.offset(d, 0))
                .d(PrimeMcBiomeDefinitions.offset(d, 1))
                .d(PrimeMcBiomeDefinitions.input_scale(d))
                .d(PrimeMcBiomeDefinitions.value_scale(d));
        for (var r : new MemorySegment[] {PrimeMcBiomeDefinitions.grass(d),
                                          PrimeMcBiomeDefinitions.foliage(d),
                                          PrimeMcBiomeDefinitions.dry_foliage(d)}) {
            out.i(count(r));
            for (int i = 0; i < count(r); i++)
                out.i(b.colormaps.get(first(r) + i).get(ValueLayout.JAVA_INT, 0));
        }
    }
    static void biome(MemorySegment b, SourcePages out) {
        out.f(PrimeMcBiome.temperature(b)).f(PrimeMcBiome.downfall(b)).i(PrimeMcBiome.water(b));
        for (int i = 0; i < 3; i++)
            out.i(PrimeMcBiome.overrides(b, i));
        out.i(PrimeMcBiome.flags(b)).i(PrimeMcBiome.modifier(b));
    }
    static void colors(McSourceBatch b, MemorySegment typed, boolean biome, SourcePages out,
                       long requestCount) {
        var id = biome ? PrimeMcBiomeBatch.identity(typed) : PrimeMcColorBatch.identity(typed);
        out.header(PrimeMcIdentity.game_version(id), biome ? 4 : 3, PrimeMcIdentity.epoch(id),
                   PrimeMcIdentity.batch(id));
        if (biome) {
            out.l(requestCount).i(Math.toIntExact(b.biomes.count()));
            for (long i = 0; i < b.biomes.count(); i++)
                biome(b.biomes.get(i), out);
            for (long i = 0; i < b.indices.count(); i++)
                out.i(b.indices.get(i).get(ValueLayout.JAVA_INT, 0));
        } else {
            out.l(b.recipes.count()).i(PrimeMcColorBatch.radius(typed));
            for (long i = 0; i < b.recipes.count(); i++) {
                var r = b.recipes.get(i);
                out.i(PrimeMcColorRecipe.kind(r)).i(PrimeMcColorRecipe.value(r));
            }
            out.i(b.definitions.count() == 0 ? 0 : 1);
            if (b.definitions.count() != 0)
                definitions(b, out);
        }
    }
    static void requests(MemorySegment r, SourcePages out) {
        out.clear();
        var id = PrimeMcRequests.identity(r);
        int phase = PrimeMcRequests.phase(r);
        boolean biome = phase == 4;
        long count = biome ? PrimeMcRequests.biome_count(r) : PrimeMcRequests.color_count(r);
        out.l(PrimeMcIdentity.batch(id))
                .l(count)
                .l(PrimeMcIdentity.epoch(id))
                .i(PrimeMcIdentity.game_version(id))
                .i(biome        ? 3
                   : phase == 3 ? 2
                                : 0);
        long stride = biome ? PrimeMcBiomeRequest.SIZE : PrimeMcColorRequest.SIZE;
        var items = (biome ? PrimeMcRequests.biomes(r) : PrimeMcRequests.colors(r))
                            .reinterpret(count * stride);
        for (long i = 0; i < count; i++) {
            var v = items.asSlice(i * stride, stride);
            if (biome)
                out.i(PrimeMcBiomeRequest.x(v))
                        .i(PrimeMcBiomeRequest.y(v))
                        .i(PrimeMcBiomeRequest.z(v))
                        .l(PrimeMcBiomeRequest.mask(v));
            else
                out.i(PrimeMcColorRequest.x(v))
                        .i(PrimeMcColorRequest.y(v))
                        .i(PrimeMcColorRequest.z(v))
                        .i(PrimeMcColorRequest.state(v))
                        .i(PrimeMcColorRequest.slot(v));
        }
    }
}
