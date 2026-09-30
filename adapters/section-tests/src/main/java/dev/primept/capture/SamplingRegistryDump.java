package dev.primept.capture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.properties.Property;

/** Test-only registry facts for an explicitly approximate world sampling fixture. No window/GPU. */
final class SamplingRegistryDump {
    private static <T extends Comparable<T>> String value(Property<T> property, Object value) {
        return property.getName(property.getValueClass().cast(value));
    }

    static void run(Path output) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var result = new JsonObject();
        for (var block : BuiltInRegistries.BLOCK) {
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (var state : block.getStateDefinition().getPossibleStates()) {
                var properties = new TreeMap<String, String>();
                for (var property : state.getProperties())
                    properties.put(property.getName(), value(property, state.getValue(property)));
                String key = name + "[" +
                             String.join(",", properties.entrySet()
                                                      .stream()
                                                      .map(e -> e.getKey() + "=" + e.getValue())
                                                      .toList()) +
                             "]";
                var entry = new JsonObject();
                entry.addProperty("default", state == block.defaultBlockState());
                entry.addProperty("emission", state.getLightEmission());
                entry.addProperty("air", state.isAir());
                entry.addProperty("solid", state.isSolidRender());
                entry.addProperty("can_occlude", state.canOcclude());
                var boxes = new JsonArray();
                // These are source outline bounds, deliberately not claimed to be baked render faces.
                try {
                    for (var box :
                         state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
                        var b = new JsonArray();
                        for (double v : new double[] {box.minX, box.minY, box.minZ, box.maxX,
                                                      box.maxY, box.maxZ})
                            b.add(v);
                        boxes.add(b);
                    }
                } catch (RuntimeException unavailable) {
                    entry.addProperty("shape_error", unavailable.getClass().getSimpleName());
                }
                entry.add("outline_boxes", boxes);
                result.add(key, entry);
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            new GsonBuilder().create().toJson(result, writer);
        }
        System.out.println("PRIME_SAMPLING_REGISTRY_OK states=" + result.size() +
                           " path=" + output);
    }
}
