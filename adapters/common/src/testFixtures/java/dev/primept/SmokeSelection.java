package dev.primept;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One selected CPU fixture suite; successful exit requires every requested group to complete. */
public enum SmokeSelection {
    DEFAULT("default", true,
            List.of("target_resize", "settings", "astronomy", "geometry_cache", "canonical_texture",
                    "fabric_mesh", "item", "custom", "publication", "exclusive_terrain",
                    "frame_profile", "model_sprite", "prototype")),
    TARGET_RESIZE("target_resize", false, List.of("target_resize")),
    FOREIGN("foreign", true, List.of("foreign_geometry", "foreign_item", "section_sources")),
    SECTION("section", true, List.of("section")),
    SAMPLING_REGISTRY("sampling_registry", false, List.of("sampling_registry")),
    ROUTING_COST("routing_cost", false, List.of("routing_cost"));

    private final String id;
    private final boolean nativeRequired;
    private final List<String> groups;

    SmokeSelection(String id, boolean nativeRequired, List<String> groups) {
        this.id = id;
        this.nativeRequired = nativeRequired;
        this.groups = groups;
    }

    public String id() {
        return id;
    }

    public boolean nativeRequired() {
        return nativeRequired;
    }

    public List<String> groups() {
        return groups;
    }

    public static SmokeSelection resolve(Map<String, String> values) {
        var requested = new ArrayList<SmokeSelection>();
        if (flag(values, "primeptSectionSuite"))
            requested.add(SECTION);
        if (flag(values, "primeptSmokeTargetResize"))
            requested.add(TARGET_RESIZE);
        if (flag(values, "primeptSmokeForeign"))
            requested.add(FOREIGN);
        if (!values.getOrDefault("primeptSamplingRegistry", "").isBlank())
            requested.add(SAMPLING_REGISTRY);
        if (!values.getOrDefault("primeptSmokeRoutingCost", "").isBlank())
            requested.add(ROUTING_COST);
        if (requested.size() > 1)
            throw new IllegalArgumentException("Conflicting CPU smoke selectors: " + requested);
        SmokeSelection selection = requested.isEmpty() ? DEFAULT : requested.getFirst();
        if (flag(values, "primeptSectionBench") && selection != SECTION)
            throw new IllegalArgumentException(
                    "primeptSectionBench=true requires primeptSectionSuite=true");
        return selection;
    }

    private static boolean flag(Map<String, String> values, String key) {
        String value = values.getOrDefault(key, "false").trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key + " must be true or false: " + value);
        return value.equalsIgnoreCase("true");
    }

    @FunctionalInterface
    public interface Fixture {
        void run() throws Exception;
    }

    public Report report(Path path, String minecraftVersion) throws IOException {
        return new Report(this, path, minecraftVersion);
    }

    public static final class Report {
        private final SmokeSelection selection;
        private final Path path;
        private final JsonObject document = new JsonObject();
        private final JsonArray executed = new JsonArray();

        private Report(SmokeSelection selection, Path path, String minecraftVersion)
                throws IOException {
            this.selection = selection;
            this.path = path;
            document.addProperty("schema", 1);
            document.addProperty("run", UUID.randomUUID().toString());
            document.addProperty("minecraft", minecraftVersion);
            document.addProperty("selector", selection.id());
            var requested = new JsonArray();
            selection.groups().forEach(requested::add);
            document.add("requested", requested);
            document.add("executed", executed);
            document.addProperty("countUnit", "completed fixture groups");
            write("running");
        }

        public void run(String group, Fixture fixture) throws Exception {
            if (executed.size() >= selection.groups().size() ||
                !selection.groups().get(executed.size()).equals(group))
                throw new IllegalArgumentException("Unexpected CPU smoke group: " + group);
            fixture.run();
            executed.add(group);
        }

        public void passed() throws IOException {
            if (executed.size() != selection.groups().size())
                throw new IllegalStateException("CPU smoke did not execute every requested group");
            write("passed");
        }

        public void failed(Throwable error) throws IOException {
            document.addProperty("error", error.toString());
            write("failed");
        }

        private void write(String status) throws IOException {
            document.addProperty("status", status);
            document.addProperty("count", executed.size());
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, new GsonBuilder().create().toJson(document) + "\n");
        }
    }
}
