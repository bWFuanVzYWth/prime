package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ClusterTranslationBenchmarkCorpusTest {
    @Test
    void typicalCorpusHasAStableEncodedCorrectnessBaseline() {
        assertEquals(
                ClusterTranslationBenchmarkCorpus.expected("typical"),
                ClusterTranslationBenchmarkCorpus.translateAndFingerprint("typical"));
    }

    @Test
    void extremeCorpusHasAStableEncodedCorrectnessBaseline() {
        assertEquals(
                ClusterTranslationBenchmarkCorpus.expected("extreme"),
                ClusterTranslationBenchmarkCorpus.translateAndFingerprint("extreme"));
    }
}
