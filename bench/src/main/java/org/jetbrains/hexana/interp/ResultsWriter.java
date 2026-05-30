package org.jetbrains.hexana.interp;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.util.Statistics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Canonical entry point ({@code Main-Class} of benchmarks.jar): runs the JMH benchmarks
 * (respecting their fork/warmup/measurement + JVM-args annotations) and writes
 * {@code results.json} plus JMH's raw {@code jmh-result.json}. The forked JVMs also emit
 * {@code compilation.log} (from the {@code -XX:LogFile} fork arg).
 *
 * <p>JMH's own CLI stays reachable for ad-hoc / profiling runs:
 * {@code java -cp bench/target/benchmarks.jar org.openjdk.jmh.Main <regex> -f 1 …}
 *
 * <p>Optional arg: a benchmark include-regex (default: all under {@code …interp.benchmarks}).
 */
public final class ResultsWriter {

    private static final String DEFAULT_INCLUDE = "org\\.jetbrains\\.hexana\\.interp\\.benchmarks\\..*";

    public static void main(String[] args) throws Exception {
        final String include = (args.length > 0 && !args[0].isBlank()) ? args[0] : DEFAULT_INCLUDE;
        final Options opt = new OptionsBuilder()
                .include(include)
                .resultFormat(ResultFormatType.JSON)
                .result("jmh-result.json")
                .build();
        final Collection<RunResult> results = new Runner(opt).run();
        final Path out = Path.of("results.json");
        Files.writeString(out, render(results), StandardCharsets.UTF_8);
        System.out.println("[results] wrote " + out.toAbsolutePath());
        System.out.println("[results] raw JMH json: " + Path.of("jmh-result.json").toAbsolutePath());
    }

    private static String render(Collection<RunResult> results) {
        final ProgramBuilder.Program p = ProgramBuilder.mixing(BenchState.ROUNDS, BenchState.ARITY);
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");

        sb.append("  \"host\": {\n");
        sb.append("    \"cpu_model\": ").append(json(cpuModel())).append(",\n");
        sb.append("    \"kernel\": ").append(json(run("uname", "-sr"))).append(",\n");
        sb.append("    \"jvm\": ").append(json(jvmString())).append(",\n");
        sb.append("    \"macos_version\": ").append(json(run("sw_vers", "-productVersion"))).append("\n");
        sb.append("  },\n");

        sb.append("  \"workload\": {\n");
        sb.append("    \"kind\": \"stack-bytecode-interpreter (mixing kernel)\",\n");
        sb.append("    \"rounds\": ").append(BenchState.ROUNDS).append(",\n");
        sb.append("    \"input_arity\": ").append(BenchState.ARITY).append(",\n");
        sb.append("    \"program_ops\": ").append(p.code().length).append(",\n");
        sb.append("    \"corpus_size\": ").append(BenchState.CORPUS_SIZE).append(",\n");
        sb.append("    \"corpus_seed\": ").append(BenchState.SEED).append("\n");
        sb.append("  },\n");

        sb.append("  \"results\": [\n");
        final List<String> entries = new ArrayList<>();
        for (final RunResult rr : results) {
            entries.add(renderResult(rr));
        }
        for (int i = 0; i < entries.size(); i++) {
            sb.append(entries.get(i)).append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static String renderResult(RunResult rr) {
        final Result<?> primary = rr.getPrimaryResult();
        final Mode mode = rr.getParams().getMode();
        final String benchmark = shortName(rr.getParams().getBenchmark());
        final StringBuilder sb = new StringBuilder(256);
        sb.append("    {\n");
        sb.append("      \"benchmark\": ").append(json(benchmark)).append(",\n");
        if (mode == Mode.AverageTime) {
            final double[] ci = primary.getScoreConfidence();
            sb.append("      \"mode\": \"avgt\",\n");
            sb.append("      \"mean_ns_op\": ").append(num(primary.getScore())).append(",\n");
            sb.append("      \"ci99_low\": ").append(num(ci[0])).append(",\n");
            sb.append("      \"ci99_high\": ").append(num(ci[1])).append(",\n");
            sb.append("      \"samples\": ").append((long) primary.getStatistics().getN()).append("\n");
        } else if (mode == Mode.SampleTime) {
            final Statistics st = primary.getStatistics();
            sb.append("      \"mode\": \"smpl\",\n");
            sb.append("      \"p50_ns\": ").append(num(st.getPercentile(50.0))).append(",\n");
            sb.append("      \"p99_ns\": ").append(num(st.getPercentile(99.0))).append(",\n");
            sb.append("      \"p999_ns\": ").append(num(st.getPercentile(99.9))).append(",\n");
            sb.append("      \"samples\": ").append((long) st.getN()).append("\n");
        } else {
            sb.append("      \"mode\": ").append(json(mode.shortLabel())).append(",\n");
            sb.append("      \"score\": ").append(num(primary.getScore())).append(",\n");
            sb.append("      \"unit\": ").append(json(primary.getScoreUnit())).append("\n");
        }
        sb.append("    }");
        return sb.toString();
    }

    private static String shortName(String fq) {
        final int dot = fq.lastIndexOf('.');
        return dot >= 0 ? fq.substring(dot + 1) : fq;
    }

    private static String jvmString() {
        return System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version")
                + " (" + System.getProperty("java.vendor") + ")";
    }

    private static String cpuModel() {
        final String mac = run("sysctl", "-n", "machdep.cpu.brand_string");
        return (mac != null && !mac.isBlank())
                ? mac
                : System.getProperty("os.arch") + " (" + Runtime.getRuntime().availableProcessors() + " cpus)";
    }

    private static String run(String... cmd) {
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String num(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "null" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String json(String s) {
        return s == null ? "null"
                : '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private ResultsWriter() {
    }
}