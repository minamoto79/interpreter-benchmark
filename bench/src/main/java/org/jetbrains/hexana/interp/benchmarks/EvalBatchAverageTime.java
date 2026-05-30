package org.jetbrains.hexana.interp.benchmarks;

import org.jetbrains.hexana.interp.BenchState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Amortized measurement: interpret the program {@code batchSize} times per invocation. Useful
 * because some compiler effects (inlining of {@code run}, code-cache behaviour) show up more
 * clearly under a batched, steadily-hot loop. No allocation in the loop; the corpus is
 * preallocated in {@link BenchState}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 5, jvmArgs = {
        "-Xms4g", "-Xmx4g",
        "-XX:+UseParallelGC",
        "-XX:ReservedCodeCacheSize=512m",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+PreserveFramePointer",
        "-XX:+PrintCompilation",
        "-XX:LogFile=compilation.log",
})
@Warmup(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 2, timeUnit = TimeUnit.SECONDS)
// The class is its own @State holder for the @Param; BenchState is injected separately.
@State(Scope.Thread)
public class EvalBatchAverageTime {

    @Param({"64"})
    public int batchSize;

    @Benchmark
    public void evalBatch(BenchState s, Blackhole bh) {
        final long[][] corpus = s.corpus;
        final int n = corpus.length;
        int i = s.idx;
        for (int k = 0; k < batchSize; k++) {
            final long[] in = corpus[i];
            i = (i + 1) % n;
            bh.consume(s.interpreter.run(s.program, s.constants, in));
        }
        s.idx = i;
    }
}