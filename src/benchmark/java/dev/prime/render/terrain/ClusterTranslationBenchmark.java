package dev.prime.render.terrain;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@State(Scope.Thread)
public class ClusterTranslationBenchmark {
    @Param({"typical", "extreme"})
    public String scenario;

    private ClusterTranslationInput input;

    @Setup(Level.Trial)
    public void setup() {
        this.input = ClusterTranslationBenchmarkCorpus.input(this.scenario);
        ClusterTranslationBenchmarkCorpus.Fingerprint actual =
                ClusterTranslationBenchmarkCorpus.fingerprint(
                        ClusterSceneTranslator.translate(this.input));
        if (!ClusterTranslationBenchmarkCorpus.expected(this.scenario).equals(actual)) {
            throw new IllegalStateException(
                    "Translation benchmark correctness baseline drifted: " + actual);
        }
    }

    @Benchmark
    public CpuClusterMesh translate() {
        return ClusterSceneTranslator.translate(this.input);
    }
}
