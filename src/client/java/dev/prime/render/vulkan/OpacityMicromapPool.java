// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import dev.prime.render.terrain.OpacityMicromapData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Render-thread-owned pool for immutable opacity-micromap block sets. */
public final class OpacityMicromapPool implements AutoCloseable {
    private final VulkanContext context;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<Block, ArrayList<Entry>> entriesByBlock = new HashMap<>();
    private boolean closed;

    public OpacityMicromapPool(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    VulkanContext context() {
        return this.context;
    }

    OpacityMicromap acquire(
            OpacityMicromapData source,
            StagingArena.Batch staging,
            VkCommandBuffer commandBuffer,
            String label) {
        this.requireOpen();
        Objects.requireNonNull(source, "source");
        if (!this.context.capabilities().opacityMicromapSupported() || source.isEmpty()) {
            return null;
        }
        source.requireValidTriangleIndices();
        PreparedContent prepared = PreparedContent.from(source);
        Entry entry = null;
        int[] blockRemap = null;
        ArrayList<Entry> candidates = null;
        for (int index = 0; index < prepared.content.blockCount(); index++) {
            ArrayList<Entry> indexed = this.entriesByBlock.get(
                    prepared.content.block(index));
            if (indexed == null) {
                candidates = null;
                break;
            }
            if (candidates == null || indexed.size() < candidates.size()) {
                candidates = indexed;
            }
        }
        if (candidates != null) {
            for (Entry candidate : candidates) {
                if (candidate.content.containsAll(prepared.content)
                        && (entry == null
                                || candidate.content.blockCount()
                                        < entry.content.blockCount())) {
                    entry = candidate;
                }
            }
        }
        if (entry != null) {
            blockRemap = entry.content.remap(prepared.content);
        }
        if (entry == null && prepared.content.blockCount() != 0) {
            OpacityMicromap.Shared shared = OpacityMicromap.Shared.create(
                    this.context,
                    prepared.content,
                    staging,
                    commandBuffer,
                    label);
            entry = new Entry(prepared.content, shared);
            this.entries.add(entry);
            for (int index = 0; index < entry.content.blockCount(); index++) {
                this.entriesByBlock.computeIfAbsent(
                        entry.content.block(index), ignored -> new ArrayList<>()).add(entry);
            }
            blockRemap = identityRemap(prepared.content.blockCount());
        }
        if (entry != null) {
            entry.references++;
        }
        try {
            return OpacityMicromap.createBinding(
                    this,
                    entry,
                    prepared.remapTriangles(blockRemap),
                    staging,
                    commandBuffer,
                    label);
        } catch (RuntimeException exception) {
            try {
                this.release(entry);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    void release(Entry entry) {
        if (entry == null) {
            return;
        }
        if (entry.references <= 0) {
            throw new IllegalStateException("Opacity micromap pool reference underflow");
        }
        entry.references--;
        if (entry.references != 0) {
            return;
        }
        if (!this.entries.remove(entry)) {
            throw new IllegalStateException("Opacity micromap pool lost a live entry");
        }
        for (int index = 0; index < entry.content.blockCount(); index++) {
            Block block = entry.content.block(index);
            ArrayList<Entry> indexed = this.entriesByBlock.get(block);
            if (indexed == null || !indexed.remove(entry)) {
                throw new IllegalStateException(
                        "Opacity micromap pool lost a block index");
            }
            if (indexed.isEmpty()) {
                this.entriesByBlock.remove(block);
            }
        }
        entry.shared.retireFromPool();
    }

    @Override
    public void close() {
        this.closed = true;
        if (!this.entries.isEmpty() || !this.entriesByBlock.isEmpty()) {
            throw new IllegalStateException(
                    "Opacity micromap pool closed with live references");
        }
    }

    int entryCount() {
        return this.entries.size();
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Opacity micromap pool is closed");
        }
    }

    private static int[] identityRemap(int count) {
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = index;
        }
        return result;
    }

    static final class Entry {
        final Content content;
        final OpacityMicromap.Shared shared;
        int references;

        Entry(Content content, OpacityMicromap.Shared shared) {
            this.content = content;
            this.shared = shared;
        }
    }

    static final class PreparedContent {
        final Content content;
        private final int[] triangleIndices;

        private PreparedContent(Content content, int[] triangleIndices) {
            this.content = content;
            this.triangleIndices = triangleIndices;
        }

        static PreparedContent from(OpacityMicromapData source) {
            Block[] sourceBlocks = new Block[source.blockCount()];
            byte[] packedBlocks = source.blocks();
            int[] blockOffsets = source.blockOffsets();
            int[] blockFormats = source.blockFormats();
            int[] blockLevels = source.blockSubdivisionLevels();
            for (int index = 0; index < sourceBlocks.length; index++) {
                int offset = blockOffsets[index];
                int size = OpacityMicromapData.blockByteSize(
                        blockFormats[index], blockLevels[index]);
                sourceBlocks[index] = Block.takeOwnership(
                        blockFormats[index],
                        blockLevels[index],
                        Arrays.copyOfRange(packedBlocks, offset, offset + size));
            }
            Content content = Content.from(Arrays.asList(sourceBlocks));
            int[] sourceToCanonical = new int[sourceBlocks.length];
            for (int index = 0; index < sourceBlocks.length; index++) {
                sourceToCanonical[index] = content.indexOf(sourceBlocks[index]);
            }
            int[] triangleIndices = source.triangleIndices().clone();
            for (int index = 0; index < triangleIndices.length; index++) {
                int block = triangleIndices[index];
                if (block >= 0) {
                    triangleIndices[index] = sourceToCanonical[block];
                }
            }
            return new PreparedContent(content, triangleIndices);
        }

        int[] remapTriangles(int[] blockRemap) {
            int[] result = this.triangleIndices.clone();
            if (blockRemap == null) {
                for (int index : result) {
                    if (index >= 0) {
                        throw new IllegalStateException(
                                "Mapped opacity micromap triangle has no block storage");
                    }
                }
                return result;
            }
            for (int index = 0; index < result.length; index++) {
                int block = result[index];
                if (block >= 0) {
                    result[index] = blockRemap[block];
                }
            }
            return result;
        }
    }

    static final class Content {
        private static final Comparator<Block> BLOCK_ORDER = (first, second) -> {
            int compared = Integer.compare(first.format, second.format);
            if (compared != 0) {
                return compared;
            }
            compared = Integer.compare(first.subdivisionLevel, second.subdivisionLevel);
            if (compared != 0) {
                return compared;
            }
            compared = Integer.compare(first.states.length, second.states.length);
            if (compared != 0) {
                return compared;
            }
            for (int index = 0; index < first.states.length; index++) {
                compared = Integer.compare(
                        Byte.toUnsignedInt(first.states[index]),
                        Byte.toUnsignedInt(second.states[index]));
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        };

        private final List<Block> blocks;
        private final Map<Block, Integer> indices;

        private Content(List<Block> blocks) {
            this.blocks = List.copyOf(blocks);
            this.indices = new HashMap<>(blocks.size());
            for (int index = 0; index < blocks.size(); index++) {
                if (this.indices.put(blocks.get(index), index) != null) {
                    throw new IllegalArgumentException(
                            "Canonical opacity micromap content contains a duplicate block");
                }
            }
        }

        static Content from(List<Block> blocks) {
            ArrayList<Block> ordered = new ArrayList<>(blocks);
            ordered.sort(BLOCK_ORDER);
            int destination = 0;
            for (Block block : ordered) {
                if (destination == 0
                        || !ordered.get(destination - 1).equals(block)) {
                    ordered.set(destination++, block);
                }
            }
            ordered.subList(destination, ordered.size()).clear();
            return new Content(ordered);
        }

        int blockCount() {
            return this.blocks.size();
        }

        int indexOf(Block block) {
            Integer index = this.indices.get(block);
            if (index == null) {
                throw new IllegalStateException(
                        "Canonical opacity micromap content lost a source block");
            }
            return index;
        }

        Block block(int index) {
            return this.blocks.get(index);
        }

        int[] remap(Content source) {
            int[] result = new int[source.blockCount()];
            for (int index = 0; index < source.blockCount(); index++) {
                Integer destination = this.indices.get(source.block(index));
                if (destination == null) {
                    return null;
                }
                result[index] = destination;
            }
            return result;
        }

        boolean containsAll(Content source) {
            for (Block block : source.blocks) {
                if (!this.indices.containsKey(block)) {
                    return false;
                }
            }
            return true;
        }

        byte[] packedBlocks() {
            int size = 0;
            for (Block block : this.blocks) {
                size = Math.addExact(size, block.states.length);
            }
            byte[] result = new byte[size];
            int destination = 0;
            for (Block block : this.blocks) {
                System.arraycopy(
                        block.states, 0, result, destination, block.states.length);
                destination += block.states.length;
            }
            return result;
        }

        int[] blockOffsets() {
            int[] result = new int[this.blocks.size()];
            int offset = 0;
            for (int index = 0; index < this.blocks.size(); index++) {
                result[index] = offset;
                offset = Math.addExact(offset, this.blocks.get(index).states.length);
            }
            return result;
        }

        int[] blockFormats() {
            int[] result = new int[this.blocks.size()];
            for (int index = 0; index < this.blocks.size(); index++) {
                result[index] = this.blocks.get(index).format;
            }
            return result;
        }

        int[] blockSubdivisionLevels() {
            int[] result = new int[this.blocks.size()];
            for (int index = 0; index < this.blocks.size(); index++) {
                result[index] = this.blocks.get(index).subdivisionLevel;
            }
            return result;
        }
    }

    static final class Block {
        final int format;
        final int subdivisionLevel;
        final byte[] states;
        private final int hash;

        Block(int format, int subdivisionLevel, byte[] states) {
            this(format, subdivisionLevel, states, true);
        }

        private Block(
                int format, int subdivisionLevel, byte[] states, boolean copy) {
            int expected = OpacityMicromapData.blockByteSize(
                    format, subdivisionLevel);
            if (states.length != expected) {
                throw new IllegalArgumentException(
                        "Opacity micromap block content has the wrong size");
            }
            this.format = format;
            this.subdivisionLevel = subdivisionLevel;
            this.states = copy ? states.clone() : states;
            this.hash = 31 * (31 * format + subdivisionLevel)
                    + Arrays.hashCode(this.states);
        }

        static Block takeOwnership(
                int format, int subdivisionLevel, byte[] states) {
            return new Block(format, subdivisionLevel, states, false);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Block block
                            && this.format == block.format
                            && this.subdivisionLevel == block.subdivisionLevel
                            && Arrays.equals(this.states, block.states);
        }
    }
}
