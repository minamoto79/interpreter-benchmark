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
 * Same single-evaluation work as {@link EvalAverageTime}, in {@link Mode#SampleTime} for a
 * latency distribution (p50/p99/p99.9). Identical fork/warmup/measurement and JVM args.
 */
@BenchmarkMode(Mode.SampleTime)
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
public class EvalSampleTime {

    @Benchmark
    public long evalSampled(BenchState s) {
        final long[] in = s.corpus[s.idx];
        s.idx = (s.idx + 1) % s.corpus.length;
        return s.interpreter.run(s.program, s.constants, in);
    }
}