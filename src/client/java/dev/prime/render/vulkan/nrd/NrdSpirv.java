// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class NrdSpirv {
    private static final int MAGIC = 0x07230203;
    private static final int HEADER_WORDS = 5;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_VECTOR_SHUFFLE = 79;
    private static final int OP_IMAGE_WRITE = 99;
    private static final int VECTOR_SHUFFLE_WORDS = 9;

    private NrdSpirv() {}

    /**
     * NRD 4.17.4 stores three-component SH1 in portable RGBA images. DXC emits a three-component
     * texel for those formatless storage writes, while Vulkan requires the texel to cover all four
     * components of the bound RGBA view. Extend only such writes; NRD always reads SH1 as xyz, so
     * the duplicated x in the otherwise unused w component cannot affect denoising.
     */
    static byte[] expandThreeComponentStorageWrites(byte[] spirv) {
        int[] words = words(spirv);
        int bound = words[3];
        int[] vectorComponentTypes = new int[bound];
        int[] vectorSizes = new int[bound];

        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (opcode == OP_TYPE_VECTOR && wordCount == 4) {
                int result = words[offset + 1];
                requireId(result, bound);
                vectorComponentTypes[result] = words[offset + 2];
                vectorSizes[result] = words[offset + 3];
            }
        });

        int[] fourComponentTypeByComponent = new int[bound];
        for (int type = 1; type < bound; type++) {
            int componentType = vectorComponentTypes[type];
            if (vectorSizes[type] == 4 && componentType > 0 && componentType < bound) {
                fourComponentTypeByComponent[componentType] = type;
            }
        }
        int[] fourComponentTypes = new int[bound];
        for (int type = 1; type < bound; type++) {
            if (vectorSizes[type] == 3) {
                int componentType = vectorComponentTypes[type];
                if (componentType > 0 && componentType < bound) {
                    fourComponentTypes[type] = fourComponentTypeByComponent[componentType];
                }
            }
        }

        int[] expandedTypeByValue = new int[bound];
        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (wordCount < 3) {
                return;
            }
            int resultType = words[offset + 1];
            if (resultType <= 0 || resultType >= bound || vectorSizes[resultType] != 3) {
                return;
            }
            int result = words[offset + 2];
            if (result > 0 && result < bound) {
                int expandedType = fourComponentTypes[resultType];
                if (expandedType != 0) {
                    expandedTypeByValue[result] = expandedType;
                }
            }
        });

        int expansionCount = countExpandableWrites(words, expandedTypeByValue);
        if (expansionCount == 0) {
            return spirv;
        }
        if (bound > Integer.MAX_VALUE - expansionCount) {
            throw new IllegalArgumentException("NRD SPIR-V ID bound overflows");
        }

        int[] expanded = new int[words.length + expansionCount * VECTOR_SHUFFLE_WORDS];
        System.arraycopy(words, 0, expanded, 0, HEADER_WORDS);
        expanded[3] = bound + expansionCount;
        int source = HEADER_WORDS;
        int destination = HEADER_WORDS;
        int nextId = bound;
        while (source < words.length) {
            int instruction = words[source];
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            int expandedType = expandedWriteType(words, source, wordCount, opcode, expandedTypeByValue);
            int expandedValue = 0;
            if (expandedType != 0) {
                int value = words[source + 3];
                expandedValue = nextId++;
                expanded[destination++] = VECTOR_SHUFFLE_WORDS << 16 | OP_VECTOR_SHUFFLE;
                expanded[destination++] = expandedType;
                expanded[destination++] = expandedValue;
                expanded[destination++] = value;
                expanded[destination++] = value;
                expanded[destination++] = 0;
                expanded[destination++] = 1;
                expanded[destination++] = 2;
                expanded[destination++] = 0;
            }
            System.arraycopy(words, source, expanded, destination, wordCount);
            if (expandedValue != 0) {
                expanded[destination + 3] = expandedValue;
            }
            source += wordCount;
            destination += wordCount;
        }
        if (destination != expanded.length || nextId != expanded[3]) {
            throw new IllegalStateException("NRD SPIR-V expansion produced an inconsistent module");
        }
        return bytes(expanded);
    }

    static int countThreeComponentStorageWrites(byte[] spirv) {
        int[] words = words(spirv);
        int bound = words[3];
        boolean[] threeComponentTypes = new boolean[bound];
        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (opcode == OP_TYPE_VECTOR && wordCount == 4 && words[offset + 3] == 3) {
                int result = words[offset + 1];
                requireId(result, bound);
                threeComponentTypes[result] = true;
            }
        });
        boolean[] threeComponentValues = new boolean[bound];
        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (wordCount >= 3) {
                int resultType = words[offset + 1];
                int result = words[offset + 2];
                if (resultType > 0
                        && resultType < bound
                        && result > 0
                        && result < bound
                        && threeComponentTypes[resultType]) {
                    threeComponentValues[result] = true;
                }
            }
        });
        int[] count = {0};
        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (opcode == OP_IMAGE_WRITE
                    && wordCount >= 4
                    && words[offset + 3] > 0
                    && words[offset + 3] < bound
                    && threeComponentValues[words[offset + 3]]) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static int countExpandableWrites(int[] words, int[] expandedTypeByValue) {
        int[] count = {0};
        forEachInstruction(words, (offset, wordCount, opcode) -> {
            if (expandedWriteType(words, offset, wordCount, opcode, expandedTypeByValue) != 0) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static int expandedWriteType(
            int[] words, int offset, int wordCount, int opcode, int[] expandedTypeByValue) {
        if (opcode != OP_IMAGE_WRITE || wordCount < 4) {
            return 0;
        }
        int value = words[offset + 3];
        return value > 0 && value < expandedTypeByValue.length ? expandedTypeByValue[value] : 0;
    }

    private static int[] words(byte[] spirv) {
        if (spirv.length < HEADER_WORDS * Integer.BYTES || spirv.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("Malformed bundled NRD SPIR-V");
        }
        ByteBuffer buffer = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[spirv.length / Integer.BYTES];
        buffer.asIntBuffer().get(words);
        if (words[0] != MAGIC || words[3] <= 0) {
            throw new IllegalArgumentException("Malformed bundled NRD SPIR-V header");
        }
        return words;
    }

    private static byte[] bytes(int[] words) {
        ByteBuffer buffer =
                ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(words);
        return buffer.array();
    }

    private static void forEachInstruction(int[] words, InstructionConsumer consumer) {
        for (int offset = HEADER_WORDS; offset < words.length; ) {
            int instruction = words[offset];
            int wordCount = instruction >>> 16;
            if (wordCount <= 0 || offset + wordCount > words.length) {
                throw new IllegalArgumentException("Malformed bundled NRD SPIR-V instruction");
            }
            consumer.accept(offset, wordCount, instruction & 0xffff);
            offset += wordCount;
        }
    }

    private static void requireId(int id, int bound) {
        if (id <= 0 || id >= bound) {
            throw new IllegalArgumentException("Malformed bundled NRD SPIR-V ID");
        }
    }

    @FunctionalInterface
    private interface InstructionConsumer {
        void accept(int offset, int wordCount, int opcode);
    }
}
