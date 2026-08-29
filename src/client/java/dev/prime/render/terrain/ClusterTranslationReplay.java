package dev.prime.render.terrain;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned, bounded capture of one complete cluster-translation input. */
public final class ClusterTranslationReplay {
    static final long MAGIC = 0x5052_494d_5452_504cL; // PRIMTRPL
    static final int VERSION = 1;
    static final long ABI_ID = 0x4354_5241_4e53_3031L; // CTRANS01
    static final long MAX_DECODED_BYTES = 256L * 1024L * 1024L;

    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_SPRITES = 1 << 20;
    private static final int MAX_FRAMES = 1 << 20;
    private static final int MAX_QUADS_PER_SECTION = 1 << 22;
    private static final int MAX_BYTE_ARRAY = 64 * 1024 * 1024;
    private static final int MAX_INT_ARRAY = MAX_BYTE_ARRAY / Integer.BYTES;

    private ClusterTranslationReplay() {
    }

    public enum Outcome {
        SUCCESS,
        CANCELLED,
        FAILED
    }

    /** Diagnostic facts that do not participate in translation behavior. */
    public record Metadata(
            Outcome outcome,
            long elapsedNanos,
            String failureType,
            String failureMessage) {
        public Metadata {
            Objects.requireNonNull(outcome, "outcome");
            if (elapsedNanos < 0) {
                throw new IllegalArgumentException("Replay duration must not be negative");
            }
            failureType = failureType == null ? "" : failureType;
            failureMessage = failureMessage == null ? "" : failureMessage;
        }

        public static Metadata success(long elapsedNanos) {
            return new Metadata(Outcome.SUCCESS, elapsedNanos, "", "");
        }

        public static Metadata failure(
                Outcome outcome, long elapsedNanos, Throwable failure) {
            if (outcome == Outcome.SUCCESS) {
                throw new IllegalArgumentException("A failure replay cannot have success outcome");
            }
            Objects.requireNonNull(failure, "failure");
            return new Metadata(
                    outcome,
                    elapsedNanos,
                    failure.getClass().getName(),
                    failure.getMessage());
        }
    }

    public record Decoded(ClusterTranslationInput input, Metadata metadata) {
        public Decoded {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    public static void write(
            Path path, ClusterTranslationInput input, Metadata metadata) throws IOException {
        Objects.requireNonNull(path, "path");
        try (OutputStream stream = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            write(stream, input, metadata);
        }
    }

    public static Decoded read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream stream = Files.newInputStream(path)) {
            return read(stream);
        }
    }

    static void write(
            OutputStream destination,
            ClusterTranslationInput input,
            Metadata metadata) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(metadata, "metadata");
        List<CapturedSprite> sprites = collectSprites(input.captured());
        Map<CapturedSprite, Integer> spriteIndices = new HashMap<>();
        for (int index = 0; index < sprites.size(); index++) {
            spriteIndices.put(sprites.get(index), index);
        }
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new LimitedOutputStream(
                        new GZIPOutputStream(destination), MAX_DECODED_BYTES)))) {
            output.writeLong(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(ABI_ID);
            writeMetadata(output, metadata);
            writeSettings(output, input.settings());
            CapturedCluster cluster = input.captured();
            output.writeInt(cluster.clusterX());
            output.writeInt(cluster.clusterY());
            output.writeInt(cluster.clusterZ());
            output.writeInt(sprites.size());
            for (CapturedSprite sprite : sprites) {
                writeSprite(output, sprite, input.materials());
            }
            for (int slot = 0; slot < SectionCluster.SECTION_COUNT; slot++) {
                CapturedSectionGeometry section = cluster.section(slot);
                output.writeBoolean(section != null);
                if (section == null) {
                    continue;
                }
                output.writeInt(section.quads().size());
                for (CapturedSectionGeometry.Quad quad : section.quads()) {
                    writeQuad(output, quad, requireSpriteIndex(spriteIndices, quad.surface().sprite()));
                }
            }
        }
    }

    static Decoded read(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new LimitedInputStream(new GZIPInputStream(source), MAX_DECODED_BYTES)))) {
            try {
                requireHeader(input);
                Metadata metadata = readMetadata(input);
                ClusterTranslationSettings settings = readSettings(input);
                int clusterX = input.readInt();
                int clusterY = input.readInt();
                int clusterZ = input.readInt();
                int spriteCount = readCount(input, MAX_SPRITES, "sprite");
                ArrayList<CapturedSprite> sprites = new ArrayList<>(spriteCount);
                HashMap<SpriteId, Integer> textureIds = new HashMap<>();
                HashSet<SpriteId> normalSprites = new HashSet<>();
                HashSet<SpriteId> specularSprites = new HashSet<>();
                HashMap<SpriteId, LabPbrEmissionMap> emissionMaps = new HashMap<>();
                HashMap<SpriteId, LabPbrHeightMap> heightMaps = new HashMap<>();
                HashMap<SpriteId, LabPbrMaterialMap> materialMaps = new HashMap<>();
                HashSet<SpriteId> ids = new HashSet<>();
                for (int index = 0; index < spriteCount; index++) {
                    SpriteEntry entry = readSprite(input);
                    if (!ids.add(entry.sprite.id())) {
                        throw malformed("Duplicate sprite identity");
                    }
                    sprites.add(entry.sprite);
                    SpriteId id = entry.sprite.id();
                    if (entry.catalogTextureId != null) {
                        textureIds.put(id, entry.catalogTextureId);
                    }
                    if (entry.normal) {
                        normalSprites.add(id);
                    }
                    if (entry.specular) {
                        specularSprites.add(id);
                    }
                    putOptional(emissionMaps, id, entry.emission, "emission");
                    putOptional(heightMaps, id, entry.height, "height");
                    putOptional(materialMaps, id, entry.material, "material");
                }
                CapturedCluster.Builder cluster = new CapturedCluster.Builder(
                        clusterX, clusterY, clusterZ);
                for (int slot = 0; slot < SectionCluster.SECTION_COUNT; slot++) {
                    if (!input.readBoolean()) {
                        continue;
                    }
                    int quadCount = readCount(input, MAX_QUADS_PER_SECTION, "quad");
                    CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
                    for (int index = 0; index < quadCount; index++) {
                        readQuad(input, sprites, section);
                    }
                    cluster.add(
                            clusterX + CapturedCluster.sectionX(slot),
                            clusterY + CapturedCluster.sectionY(slot),
                            clusterZ + CapturedCluster.sectionZ(slot),
                            section.build());
                }
                if (input.read() != -1) {
                    throw malformed("Replay contains trailing decoded data");
                }
                LabPbrMaterialSet materials = new LabPbrMaterialSet(
                        textureIds,
                        normalSprites,
                        specularSprites,
                        emissionMaps,
                        heightMaps,
                        materialMaps);
                return new Decoded(
                        new ClusterTranslationInput(cluster.build(), materials, settings),
                        metadata);
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw malformed("Replay contains invalid semantic data", exception);
            } catch (EOFException exception) {
                throw malformed("Replay is truncated", exception);
            }
        }
    }

    private static void requireHeader(DataInputStream input) throws IOException {
        if (input.readLong() != MAGIC) {
            throw malformed("Replay magic does not match Prime translation replay");
        }
        int version = input.readInt();
        if (version != VERSION) {
            throw malformed("Unsupported replay version: " + version);
        }
        long abi = input.readLong();
        if (abi != ABI_ID) {
            throw malformed("Replay ABI does not match this translator");
        }
    }

    private static void writeMetadata(DataOutputStream output, Metadata metadata)
            throws IOException {
        output.writeByte(metadata.outcome().ordinal());
        output.writeLong(metadata.elapsedNanos());
        writeString(output, metadata.failureType());
        writeString(output, metadata.failureMessage());
    }

    private static Metadata readMetadata(DataInputStream input) throws IOException {
        int outcome = input.readUnsignedByte();
        if (outcome >= Outcome.values().length) {
            throw malformed("Invalid replay outcome: " + outcome);
        }
        return new Metadata(
                Outcome.values()[outcome],
                input.readLong(),
                readString(input),
                readString(input));
    }

    private static void writeSettings(
            DataOutputStream output, ClusterTranslationSettings settings) throws IOException {
        output.writeBoolean(settings.buildOpacityMicromap());
        output.writeInt(settings.segmentTriangleTarget());
        output.writeInt(settings.maxOpacity2StateSubdivisionLevel());
        output.writeInt(settings.maxOpacity4StateSubdivisionLevel());
        output.writeBoolean(settings.voxelSurfacesEnabled());
        output.writeFloat(settings.voxelSurfaceMaximumHeight());
        output.writeBoolean(settings.closeCoveredFluidGap());
        output.writeBoolean(settings.suppressFluidFaceAgainstFullCollision());
    }

    private static ClusterTranslationSettings readSettings(DataInputStream input)
            throws IOException {
        return new ClusterTranslationSettings(
                input.readBoolean(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readBoolean(),
                input.readFloat(),
                input.readBoolean(),
                input.readBoolean());
    }

    private static List<CapturedSprite> collectSprites(CapturedCluster cluster) {
        LinkedHashMap<CapturedSprite, CapturedSprite> result = new LinkedHashMap<>();
        for (int slot = 0; slot < SectionCluster.SECTION_COUNT; slot++) {
            CapturedSectionGeometry section = cluster.section(slot);
            if (section == null) {
                continue;
            }
            for (CapturedSectionGeometry.Quad quad : section.quads()) {
                result.putIfAbsent(quad.surface().sprite(), quad.surface().sprite());
            }
        }
        return List.copyOf(result.values());
    }

    private static int requireSpriteIndex(
            Map<CapturedSprite, Integer> indices, CapturedSprite sprite) {
        Integer index = indices.get(sprite);
        if (index == null) {
            throw new IllegalStateException("Captured quad sprite was not interned");
        }
        return index;
    }

    private static void writeSprite(
            DataOutputStream output,
            CapturedSprite sprite,
            LabPbrMaterialSet materials) throws IOException {
        SpriteId id = sprite.id();
        writeString(output, id.namespace());
        writeString(output, id.path());
        output.writeInt(sprite.textureId());
        output.writeInt(sprite.frameWidth());
        output.writeInt(sprite.frameHeight());
        output.writeBoolean(sprite.animated());
        output.writeInt(sprite.uniqueFrameCount());
        for (int frame = 0; frame < sprite.uniqueFrameCount(); frame++) {
            output.writeInt(sprite.uniqueFrame(frame));
        }
        SpritePixelView pixels = sprite.pixelView();
        output.writeBoolean(pixels != null);
        if (pixels != null) {
            int width = pixels.imageWidth();
            int height = pixels.imageHeight();
            int count = Math.multiplyExact(width, height);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(count);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    output.writeInt(pixels.argb(x, y));
                }
            }
        }
        Integer catalogTextureId = materials.textureIds().get(id);
        output.writeBoolean(catalogTextureId != null);
        if (catalogTextureId != null) {
            output.writeInt(catalogTextureId);
        }
        output.writeBoolean(materials.normalSprites().contains(id));
        output.writeBoolean(materials.specularSprites().contains(id));
        writeEmission(output, materials.emissionMap(id));
        writeHeight(output, materials.heightMap(id));
        writeMaterial(output, materials.materialMap(id));
    }

    private static SpriteEntry readSprite(DataInputStream input) throws IOException {
        SpriteId id = new SpriteId(readString(input), readString(input));
        int textureId = input.readInt();
        int frameWidth = input.readInt();
        int frameHeight = input.readInt();
        boolean animated = input.readBoolean();
        int frameCount = readCount(input, MAX_FRAMES, "sprite frame");
        if (frameCount == 0) {
            throw malformed("Sprite frame sequence is empty");
        }
        int[] frames = new int[frameCount];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = input.readInt();
        }
        SpritePixelView pixels = null;
        if (input.readBoolean()) {
            int width = readPositive(input, "sprite image width");
            int height = readPositive(input, "sprite image height");
            int expected = checkedPixelCount(width, height);
            int count = readCount(input, MAX_INT_ARRAY, "sprite pixel");
            if (count != expected) {
                throw malformed("Sprite pixel count does not match its dimensions");
            }
            int[] argb = new int[count];
            for (int index = 0; index < count; index++) {
                argb[index] = input.readInt();
            }
            pixels = new StoredPixels(width, height, argb);
        }
        CapturedSprite sprite = new CapturedSprite(
                id, textureId, frameWidth, frameHeight, animated, frames, pixels);
        Integer catalogTextureId = input.readBoolean() ? input.readInt() : null;
        if (catalogTextureId != null
                && (catalogTextureId <= 0 || catalogTextureId > CapturedSprite.MAX_TEXTURE_ID)) {
            throw malformed("Catalog texture ID is outside the 24-bit ABI");
        }
        boolean normal = input.readBoolean();
        boolean specular = input.readBoolean();
        return new SpriteEntry(
                sprite,
                catalogTextureId,
                normal,
                specular,
                readEmission(input),
                readHeight(input),
                readMaterial(input));
    }

    private static void writeEmission(
            DataOutputStream output, LabPbrEmissionMap map) throws IOException {
        output.writeBoolean(map != null);
        if (map != null) {
            writeLayout(output, map.replayLayout());
            writeByteArray(output, map.replayEncoded());
        }
    }

    private static LabPbrEmissionMap readEmission(DataInputStream input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        SpriteSheetLayout layout = readLayout(input);
        return LabPbrEmissionMap.replay(
                readByteArray(input, expectedPixelCount(layout)), layout);
    }

    private static void writeHeight(
            DataOutputStream output, LabPbrHeightMap map) throws IOException {
        output.writeBoolean(map != null);
        if (map != null) {
            writeLayout(output, map.replayLayout());
            writeByteArray(output, map.replayEncoded());
        }
    }

    private static LabPbrHeightMap readHeight(DataInputStream input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        SpriteSheetLayout layout = readLayout(input);
        return LabPbrHeightMap.replay(
                readByteArray(input, expectedPixelCount(layout)), layout);
    }

    private static void writeMaterial(
            DataOutputStream output, LabPbrMaterialMap map) throws IOException {
        output.writeBoolean(map != null);
        if (map != null) {
            writeMaterialPixels(output, map.normal());
            writeMaterialPixels(output, map.specular());
        }
    }

    private static LabPbrMaterialMap readMaterial(DataInputStream input) throws IOException {
        return input.readBoolean()
                ? new LabPbrMaterialMap(
                        readMaterialPixels(input), readMaterialPixels(input))
                : null;
    }

    private static void writeMaterialPixels(
            DataOutputStream output, LabPbrMaterialMap.Pixels pixels) throws IOException {
        output.writeBoolean(pixels != null);
        if (pixels != null) {
            writeLayout(output, pixels.replayLayout());
            writeIntArray(output, pixels.replayArgb());
        }
    }

    private static LabPbrMaterialMap.Pixels readMaterialPixels(DataInputStream input)
            throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        SpriteSheetLayout layout = readLayout(input);
        int[] argb = readIntArray(input, expectedPixelCount(layout));
        return new LabPbrMaterialMap.Pixels(
                argb,
                layout.imageWidth(),
                layout.frameWidth(),
                layout.frameHeight(),
                layout.columns(),
                layout.frameCount());
    }

    private static void writeLayout(DataOutputStream output, SpriteSheetLayout layout)
            throws IOException {
        output.writeInt(layout.imageWidth());
        output.writeInt(layout.imageHeight());
        output.writeInt(layout.frameWidth());
        output.writeInt(layout.frameHeight());
        output.writeInt(layout.columns());
        output.writeInt(layout.frameCount());
    }

    private static SpriteSheetLayout readLayout(DataInputStream input) throws IOException {
        return new SpriteSheetLayout(
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt());
    }

    private static void writeQuad(
            DataOutputStream output,
            CapturedSectionGeometry.Quad quad,
            int spriteIndex) throws IOException {
        for (int vertex = 0; vertex < 4; vertex++) {
            output.writeFloat(quad.x(vertex));
            output.writeFloat(quad.y(vertex));
            output.writeFloat(quad.z(vertex));
            output.writeFloat(quad.u(vertex));
            output.writeFloat(quad.v(vertex));
        }
        output.writeFloat(quad.normalX());
        output.writeFloat(quad.normalY());
        output.writeFloat(quad.normalZ());
        output.writeInt(spriteIndex);
        CapturedSectionGeometry.Surface surface = quad.surface();
        for (int vertex = 0; vertex < 4; vertex++) {
            output.writeInt(surface.color(vertex));
        }
        output.writeByte(surface.layer().ordinal());
        int flags = (surface.alphaCutOverride() ? 1 : 0)
                | (surface.collisionEmpty() ? 1 << 1 : 0)
                | (surface.animated() ? 1 << 2 : 0)
                | (surface.water() ? 1 << 3 : 0)
                | (surface.foliage() ? 1 << 4 : 0)
                | (surface.mergeable() ? 1 << 5 : 0)
                | (surface.rasterOverlay() ? 1 << 6 : 0);
        output.writeByte(flags);
        output.writeByte(surface.lightEmission());
        output.writeByte(surface.builtinMaterialClass().id());
        CapturedSectionGeometry.FluidFacts fluid = surface.fluid();
        output.writeBoolean(fluid != null);
        if (fluid != null) {
            output.writeByte(fluid.localX());
            output.writeByte(fluid.localY());
            output.writeByte(fluid.localZ());
            output.writeBoolean(fluid.fullCeiling());
            output.writeByte(fluid.fullCollisionMask());
        }
        CapturedSectionGeometry.BlockFacts block = surface.block();
        output.writeBoolean(block != null);
        if (block != null) {
            output.writeInt(block.x());
            output.writeInt(block.y());
            output.writeInt(block.z());
            output.writeInt(block.mediumFamily());
        }
        output.writeBoolean(quad.peerOnly());
    }

    private static void readQuad(
            DataInputStream input,
            List<CapturedSprite> sprites,
            CapturedSectionGeometry.Builder section) throws IOException {
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = input.readFloat();
            quad.y[vertex] = input.readFloat();
            quad.z[vertex] = input.readFloat();
            quad.u[vertex] = input.readFloat();
            quad.v[vertex] = input.readFloat();
        }
        quad.normalX = input.readFloat();
        quad.normalY = input.readFloat();
        quad.normalZ = input.readFloat();
        int spriteIndex = input.readInt();
        if (spriteIndex < 0 || spriteIndex >= sprites.size()) {
            throw malformed("Quad sprite index is outside the replay table");
        }
        int[] colors = new int[4];
        for (int vertex = 0; vertex < colors.length; vertex++) {
            colors[vertex] = input.readInt();
        }
        int layer = input.readUnsignedByte();
        if (layer >= CapturedSectionGeometry.Layer.values().length) {
            throw malformed("Invalid captured raster layer: " + layer);
        }
        int flags = input.readUnsignedByte();
        if ((flags & ~0x7f) != 0) {
            throw malformed("Captured surface contains unknown flags");
        }
        int lightEmission = input.readUnsignedByte();
        int builtinMaterial = input.readUnsignedByte();
        CapturedSectionGeometry.FluidFacts fluid = null;
        if (input.readBoolean()) {
            fluid = new CapturedSectionGeometry.FluidFacts(
                    input.readUnsignedByte(),
                    input.readUnsignedByte(),
                    input.readUnsignedByte(),
                    input.readBoolean(),
                    input.readUnsignedByte());
        }
        CapturedSectionGeometry.BlockFacts block = null;
        if (input.readBoolean()) {
            block = new CapturedSectionGeometry.BlockFacts(
                    input.readInt(), input.readInt(), input.readInt(), input.readInt());
        }
        CapturedSectionGeometry.Surface surface = new CapturedSectionGeometry.Surface(
                colors[0],
                colors[1],
                colors[2],
                colors[3],
                CapturedSectionGeometry.Layer.values()[layer],
                (flags & 1) != 0,
                (flags & 1 << 1) != 0,
                (flags & 1 << 2) != 0,
                (flags & 1 << 3) != 0,
                (flags & 1 << 4) != 0,
                (flags & 1 << 5) != 0,
                (flags & 1 << 6) != 0,
                lightEmission,
                sprites.get(spriteIndex),
                fluid,
                block,
                BuiltinMaterialClass.fromId(builtinMaterial));
        if (input.readBoolean()) {
            section.addPeer(quad, surface);
        } else {
            section.add(quad, surface);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING_BYTES) {
            throw new IOException("Replay string exceeds the one MiB limit");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, "UTF-8 byte");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw malformed("Replay string is not valid UTF-8", exception);
        }
    }

    private static void writeByteArray(DataOutputStream output, byte[] values)
            throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }

    private static byte[] readByteArray(DataInputStream input, int expected)
            throws IOException {
        int length = readCount(input, MAX_BYTE_ARRAY, "byte array");
        if (length != expected) {
            throw malformed("Replay byte array does not match its layout");
        }
        byte[] result = input.readNBytes(length);
        if (result.length != length) {
            throw new EOFException();
        }
        return result;
    }

    private static void writeIntArray(DataOutputStream output, int[] values)
            throws IOException {
        output.writeInt(values.length);
        for (int value : values) {
            output.writeInt(value);
        }
    }

    private static int[] readIntArray(DataInputStream input, int expected)
            throws IOException {
        int length = readCount(input, MAX_INT_ARRAY, "integer array");
        if (length != expected) {
            throw malformed("Replay integer array does not match its layout");
        }
        int[] result = new int[length];
        for (int index = 0; index < length; index++) {
            result[index] = input.readInt();
        }
        return result;
    }

    private static int readPositive(DataInputStream input, String label) throws IOException {
        int value = input.readInt();
        if (value <= 0) {
            throw malformed("Replay " + label + " must be positive");
        }
        return value;
    }

    private static int readCount(DataInputStream input, int maximum, String label)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw malformed("Replay " + label + " count is out of range: " + count);
        }
        return count;
    }

    private static int expectedPixelCount(SpriteSheetLayout layout) throws IOException {
        return checkedPixelCount(layout.imageWidth(), layout.imageHeight());
    }

    private static int checkedPixelCount(int width, int height) throws IOException {
        long count = (long) width * height;
        if (count <= 0 || count > MAX_INT_ARRAY) {
            throw malformed("Replay image dimensions exceed the decode limit");
        }
        return (int) count;
    }

    private static <T> void putOptional(
            Map<SpriteId, T> output, SpriteId id, T value, String label) throws IOException {
        if (value != null && output.put(id, value) != null) {
            throw malformed("Duplicate " + label + " material entry");
        }
    }

    private static IOException malformed(String message) {
        return new IOException(message);
    }

    private static IOException malformed(String message, Throwable cause) {
        return new IOException(message, cause);
    }

    private record SpriteEntry(
            CapturedSprite sprite,
            Integer catalogTextureId,
            boolean normal,
            boolean specular,
            LabPbrEmissionMap emission,
            LabPbrHeightMap height,
            LabPbrMaterialMap material) {
    }

    private static final class StoredPixels implements SpritePixelView {
        private final int width;
        private final int height;
        private final int[] argb;

        StoredPixels(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }

        @Override
        public int imageWidth() {
            return this.width;
        }

        @Override
        public int imageHeight() {
            return this.height;
        }

        @Override
        public int argb(int x, int y) {
            if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
                throw new IndexOutOfBoundsException();
            }
            return this.argb[x + y * this.width];
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                this.add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int count = super.read(target, offset, length);
            if (count > 0) {
                this.add(count);
            }
            return count;
        }

        private void add(int amount) throws IOException {
            this.count += amount;
            if (this.count > this.maximum) {
                throw malformed("Replay exceeds the 256 MiB decoded-size limit");
            }
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long maximum;
        private long count;

        LimitedOutputStream(OutputStream output, long maximum) {
            super(output);
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            this.require(1);
            this.out.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            this.require(length);
            this.out.write(source, offset, length);
        }

        private void require(int amount) throws IOException {
            if (this.count > this.maximum - amount) {
                throw new IOException("Replay exceeds the 256 MiB decoded-size limit");
            }
            this.count += amount;
        }
    }
}
