package org.jetbrains.hexana.interp.benchmarks;

import org.jetbrains.hexana.interp.BenchState;
import org.jetbrains.hexana.interp.Specialized;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The specialization ceiling: same inputs as {@link EvalAverageTime}, but evaluating the
 * partial-evaluated {@link Specialized#eval} instead of the generic interpreter. The ratio
 * {@code EvalAverageTime / SpecializedAverageTime} is the headroom a specializing JVMCI
 * compiler could deliver for {@code Interpreter.run} on this fixed program.
 *
 * <p>Identical fork/warmup/measurement + JVM args (incl. PreserveFramePointer) as the generic
 * benchmark, so the comparison is apples-to-apples.
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
public class SpecializedAverageTime {

    @Benchmark
    public long evalSpecialized(BenchState s) {
        final long[] in = s.corpus[s.idx];
        s.idx = (s.idx + 1) % s.corpus.length;
        return Specialized.eval(in);
    }
}