// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ShaderTestBuffer {
    static final int WORD_BYTES = 4 * Integer.BYTES;

    private ShaderTestBuffer() {
    }

    static ByteBuffer inputs(int caseCount, int wordsPerCase) {
        if (caseCount <= 0 || wordsPerCase <= 0) {
            throw new IllegalArgumentException("Shader test dimensions must be positive");
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                        Math.multiplyExact(
                                Math.addExact(1, Math.multiplyExact(caseCount, wordsPerCase)),
                                WORD_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, caseCount);
        buffer.putInt(Integer.BYTES, 1);
        return buffer;
    }

    static InputWriter inputWriter(int caseCount, int wordsPerCase) {
        return new InputWriter(inputs(caseCount, wordsPerCase), wordsPerCase);
    }

    static ByteBuffer control(int invocationCount, int configurationWords) {
        if (invocationCount <= 0 || configurationWords < 0) {
            throw new IllegalArgumentException(
                    "Shader invocation count must be positive and configuration size nonnegative");
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                        Math.multiplyExact(
                                Math.addExact(1, configurationWords),
                                WORD_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, invocationCount);
        buffer.putInt(Integer.BYTES, 1);
        return buffer;
    }

    static void putControlFloat(
            ByteBuffer buffer, int wordIndex, int componentIndex, float value) {
        if (wordIndex < 0 || componentIndex < 0 || componentIndex >= 4) {
            throw new IndexOutOfBoundsException("Invalid shader control coordinate");
        }
        int byteOffset = Math.addExact(
                Math.multiplyExact(wordIndex + 1, WORD_BYTES),
                Math.multiplyExact(componentIndex, Float.BYTES));
        buffer.putFloat(byteOffset, value);
    }

    static void putControlInt(
            ByteBuffer buffer, int wordIndex, int componentIndex, int value) {
        if (wordIndex < 0 || componentIndex < 0 || componentIndex >= 4) {
            throw new IndexOutOfBoundsException("Invalid shader control coordinate");
        }
        int byteOffset = Math.addExact(
                Math.multiplyExact(wordIndex + 1, WORD_BYTES),
                Math.multiplyExact(componentIndex, Integer.BYTES));
        buffer.putInt(byteOffset, value);
    }

    static void setOutputWords(ByteBuffer buffer, int wordsPerCase) {
        if (wordsPerCase <= 0) {
            throw new IllegalArgumentException("Shader output words must be positive");
        }
        buffer.putInt(Integer.BYTES, wordsPerCase);
    }

    static int inputComponentOffset(
            int caseIndex, int wordsPerCase, int wordIndex, int componentIndex) {
        return componentOffset(1, caseIndex, wordsPerCase, wordIndex, componentIndex);
    }

    static int outputComponentOffset(
            int caseIndex, int wordsPerCase, int wordIndex, int componentIndex) {
        return componentOffset(0, caseIndex, wordsPerCase, wordIndex, componentIndex);
    }

    static void putFloat(
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex,
            float value) {
        buffer.putFloat(
                inputComponentOffset(caseIndex, wordsPerCase, wordIndex, componentIndex),
                value);
    }

    static void putInt(
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex,
            int value) {
        buffer.putInt(
                inputComponentOffset(caseIndex, wordsPerCase, wordIndex, componentIndex),
                value);
    }

    static int getInputInt(
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex) {
        return buffer.getInt(
                inputComponentOffset(caseIndex, wordsPerCase, wordIndex, componentIndex));
    }

    static float getFloat(
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex) {
        return buffer.getFloat(
                outputComponentOffset(caseIndex, wordsPerCase, wordIndex, componentIndex));
    }

    static int getInt(
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex) {
        return buffer.getInt(
                outputComponentOffset(caseIndex, wordsPerCase, wordIndex, componentIndex));
    }

    private static int componentOffset(
            int headerWords,
            int caseIndex,
            int wordsPerCase,
            int wordIndex,
            int componentIndex) {
        if (caseIndex < 0
                || wordsPerCase <= 0
                || wordIndex < 0
                || wordIndex >= wordsPerCase
                || componentIndex < 0
                || componentIndex >= 4) {
            throw new IndexOutOfBoundsException("Invalid shader test buffer coordinate");
        }
        int word = Math.addExact(
                headerWords,
                Math.addExact(Math.multiplyExact(caseIndex, wordsPerCase), wordIndex));
        return Math.addExact(
                Math.multiplyExact(word, WORD_BYTES),
                Math.multiplyExact(componentIndex, Integer.BYTES));
    }

    static final class InputWriter {
        private final ByteBuffer buffer;
        private final int wordsPerCase;

        private InputWriter(ByteBuffer buffer, int wordsPerCase) {
            this.buffer = buffer;
            this.wordsPerCase = wordsPerCase;
        }

        ByteBuffer buffer() {
            return buffer;
        }

        void putFloat(int caseIndex, int wordIndex, int componentIndex, float value) {
            ShaderTestBuffer.putFloat(
                    buffer, caseIndex, wordsPerCase, wordIndex, componentIndex, value);
        }

        void putInt(int caseIndex, int wordIndex, int componentIndex, int value) {
            ShaderTestBuffer.putInt(
                    buffer, caseIndex, wordsPerCase, wordIndex, componentIndex, value);
        }

        void putVec4(int caseIndex, int wordIndex, float x, float y, float z, float w) {
            putFloat(caseIndex, wordIndex, 0, x);
            putFloat(caseIndex, wordIndex, 1, y);
            putFloat(caseIndex, wordIndex, 2, z);
            putFloat(caseIndex, wordIndex, 3, w);
        }
    }
}
