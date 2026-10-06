package dev.primept;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only whole-document validation, before assertions about selected serialized fields. */
public final class StrictTestJson {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private StrictTestJson() {}

    public static JsonObject parse(String text) {
        JsonObject object = JSON.fromJson(text, JsonObject.class);
        if (object == null)
            throw new IllegalArgumentException("Expected a JSON object document");
        return object;
    }

    public static String read(Path path) throws IOException {
        String text = Files.readString(path);
        parse(text);
        return text;
    }
}
