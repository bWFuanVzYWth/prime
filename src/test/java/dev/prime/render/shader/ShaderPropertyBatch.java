package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a compact property sweep and replays representative failures with full witnesses. */
final class ShaderPropertyBatch {
    static final int SWEEP_WORDS = 1;

    private static final String[] FAILURE_NAMES = {
        "NaN",
        "+Inf",
        "-Inf",
        "range",
        "unit-length",
        "hemisphere",
        "orthogonal",
        "round-trip",
        "identity",
        "symmetry",
        "reciprocity",
        "event",
        "pdf",
        "value",
        "state",
        "case-index"
    };

    private ShaderPropertyBatch() {
    }

    static void assertProperties(
            ShaderComputeRunner runner,
            String shader,
            ByteBuffer input,
            int caseCount,
            int inputWords,
            int witnessWords,
            long seed)
            throws IOException {
        if (witnessWords <= SWEEP_WORDS) {
            throw new IllegalArgumentException("Witness output must contain more than one word");
        }
        ShaderTestBuffer.setOutputWords(input, SWEEP_WORDS);
        ByteBuffer sweep = runner.dispatch(
                shader,
                input,
                Math.multiplyExact(
                        Math.multiplyExact(caseCount, SWEEP_WORDS),
                        ShaderTestBuffer.WORD_BYTES),
                caseCount);
        Map<Long, FailureRecord> distinctFailures = new LinkedHashMap<>();
        Map<Long, Integer> failureCounts = new LinkedHashMap<>();
        int failedCases = 0;
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            int mask = ShaderTestBuffer.getInt(
                    sweep, caseIndex, SWEEP_WORDS, 0, 0);
            if (mask != 0) {
                failedCases++;
                int kind = ShaderTestBuffer.getInt(
                        sweep, caseIndex, SWEEP_WORDS, 0, 1);
                long key = (Integer.toUnsignedLong(kind) << 32)
                        | Integer.toUnsignedLong(mask);
                failureCounts.merge(key, 1, Integer::sum);
                if (distinctFailures.size() < 8) {
                    distinctFailures.putIfAbsent(
                            key, new FailureRecord(caseIndex, mask));
                }
            }
        }
        if (!distinctFailures.isEmpty()) {
            StringBuilder message = new StringBuilder()
                    .append(failedCases)
                    .append(" of ")
                    .append(caseCount)
                    .append(" shader property cases failed; showing ")
                    .append(distinctFailures.size())
                    .append(" of ")
                    .append(failureCounts.size())
                    .append(" distinct kind/mask witnesses")
                    .append(System.lineSeparator())
                    .append("failure groups:");
            int listedGroups = 0;
            for (Map.Entry<Long, Integer> entry : failureCounts.entrySet()) {
                if (listedGroups == 16) {
                    message.append(System.lineSeparator())
                            .append("  ... ")
                            .append(failureCounts.size() - listedGroups)
                            .append(" more groups");
                    break;
                }
                long key = entry.getKey();
                int kind = (int) (key >>> 32);
                int mask = (int) key;
                message.append(System.lineSeparator())
                        .append("  kind=")
                        .append(Integer.toUnsignedString(kind))
                        .append(" mask=0x")
                        .append(Integer.toHexString(mask))
                        .append(" [")
                        .append(String.join(", ", failureNames(mask)))
                        .append("]: ")
                        .append(entry.getValue());
                listedGroups++;
            }
            for (FailureRecord record : distinctFailures.values()) {
                AssertionError witness = failure(
                        runner,
                        shader,
                        input,
                        record.caseIndex(),
                        inputWords,
                        witnessWords,
                        seed,
                        record.mask());
                message.append(System.lineSeparator())
                        .append(System.lineSeparator())
                        .append(witness.getMessage());
            }
            throw new AssertionError(message.toString());
        }
    }

    private static AssertionError failure(
            ShaderComputeRunner runner,
            String shader,
            ByteBuffer input,
            int caseIndex,
            int inputWords,
            int witnessWords,
            long seed,
            int sweepMask)
            throws IOException {
        ByteBuffer replay = ShaderTestBuffer.inputs(1, inputWords);
        ShaderTestBuffer.setOutputWords(replay, witnessWords);
        for (int word = 0; word < inputWords; word++) {
            for (int component = 0; component < 4; component++) {
                ShaderTestBuffer.putInt(
                        replay,
                        0,
                        inputWords,
                        word,
                        component,
                        ShaderTestBuffer.getInputInt(
                                input, caseIndex, inputWords, word, component));
            }
        }
        ByteBuffer witness = runner.dispatch(
                shader,
                replay,
                Math.multiplyExact(witnessWords, ShaderTestBuffer.WORD_BYTES),
                1);
        int witnessMask = ShaderTestBuffer.getInt(
                witness, 0, witnessWords, 0, 0);
        int kind = ShaderTestBuffer.getInt(
                witness, 0, witnessWords, 0, 1);
        if (witnessMask != sweepMask) {
            return new AssertionError(
                    "Shader property failure was not deterministic: sweep=0x"
                            + Integer.toHexString(sweepMask)
                            + " replay=0x"
                            + Integer.toHexString(witnessMask)
                            + " case="
                            + caseIndex);
        }

        StringBuilder message = new StringBuilder(1024)
                .append("Shader property failure: seed=0x")
                .append(Long.toUnsignedString(seed, 16))
                .append(" case=")
                .append(caseIndex)
                .append(" kind=")
                .append(kind)
                .append(" mask=0x")
                .append(Integer.toHexString(witnessMask))
                .append(" [")
                .append(String.join(", ", failureNames(witnessMask)))
                .append(']');
        appendWords(message, "input", input, caseIndex, inputWords, inputWords, true);
        appendWords(message, "witness", witness, 0, witnessWords, witnessWords, false);
        return new AssertionError(message.toString());
    }

    private static List<String> failureNames(int mask) {
        List<String> names = new ArrayList<>();
        for (int bit = 0; bit < FAILURE_NAMES.length; bit++) {
            if ((mask & (1 << bit)) != 0) {
                names.add(FAILURE_NAMES[bit]);
            }
        }
        int known = (1 << FAILURE_NAMES.length) - 1;
        if ((mask & ~known) != 0) {
            names.add("unknown=0x" + Integer.toHexString(mask & ~known));
        }
        return names;
    }

    private static void appendWords(
            StringBuilder message,
            String label,
            ByteBuffer buffer,
            int caseIndex,
            int wordsPerCase,
            int wordCount,
            boolean input) {
        message.append(System.lineSeparator()).append(label).append(':');
        for (int word = 0; word < wordCount; word++) {
            message.append(System.lineSeparator()).append("  ").append(word).append(": ");
            for (int component = 0; component < 4; component++) {
                int bits = input
                        ? ShaderTestBuffer.getInputInt(
                                buffer, caseIndex, wordsPerCase, word, component)
                        : ShaderTestBuffer.getInt(
                                buffer, caseIndex, wordsPerCase, word, component);
                if (component != 0) {
                    message.append(" | ");
                }
                message.append("0x")
                        .append(String.format("%08x", bits))
                        .append(" (")
                        .append(Float.intBitsToFloat(bits))
                        .append(')');
            }
        }
    }

    private record FailureRecord(int caseIndex, int mask) {
    }
}
