package org.jetbrains.hexana.interp.benchmarks;

import org.jetbrains.hexana.interp.BenchState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Primary measurement: average time to interpret the fixed program once over one input vector.
 *
 * <p>Fork JVM args mirror the perf-measurement baseline AND add
 * {@code -XX:+PreserveFramePointer} — without it, Hexana / Instruments / async-profiler cannot
 * unwind the JIT frames and the profile attribution is garbage (the lesson from the protobuf
 * run). {@code EnableJVMCI}/{@code jvmci.Compiler} are deliberately ABSENT: this is the stock
 * C2 baseline. Add them (and to these very args) once the hexana compiler targets {@code run}.
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
public class EvalAverageTime {

    @Benchmark
    public long eval(BenchState s) {
        final long[] in = s.corpus[s.idx];
        s.idx = (s.idx + 1) % s.corpus.length;
        return s.interpreter.run(s.program, s.constants, in);
    }
}