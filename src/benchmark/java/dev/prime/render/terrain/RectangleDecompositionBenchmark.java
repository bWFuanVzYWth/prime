// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class RectangleDecompositionBenchmark {
    @Param({"solid", "stripes", "checkerboard", "sparse", "dense", "holes", "max-chords", "many-labels"})
    public String scenario;

    private int[] cells;
    private RectangleDecomposition64.LayerBuilder builder;
    private RectangleDecomposition64.Scratch scratch;

    @Setup
    public void setup() {
        this.cells = RectangleDecompositionCorpus.cells(this.scenario);
        this.builder = new RectangleDecomposition64.LayerBuilder();
        this.scratch = new RectangleDecomposition64.Scratch();
        RectangleDecompositionCorpus.fill(this.builder, this.cells);
        RectangleDecomposition64.Result result = this.builder.finish(this.scratch);
        RectangleDecompositionCorpus.verify(this.cells, result);
        if (result.size() != RectangleDecompositionCorpus.expectedCount(this.scenario)) {
            throw new AssertionError("Rectangle count differs from the reference");
        }
    }

    @Benchmark
    public Object finish() {
        return this.builder.finish(this.scratch);
    }

    @Benchmark
    public Object unitFaces() {
        RectangleDecompositionCorpus.fill(this.builder, this.cells);
        return this.builder.finish(this.scratch);
    }
}
