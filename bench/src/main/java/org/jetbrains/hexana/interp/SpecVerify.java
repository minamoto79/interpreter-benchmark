package org.jetbrains.hexana.interp;

/**
 * Standalone correctness + smoke harness for the {@code hexana} JVMCI compiler's specialized
 * {@link Interpreter#run}. Run under the JVMCI JBR with {@code -XX:+UseJVMCICompiler
 * -Djvmci.Compiler=hexana} (see compiler/run-hexana.sh verify). It:
 *
 * <ol>
 *   <li>publishes the fixed program to {@link ProgramOracle},</li>
 *   <li>warms {@code run} hot so HotSpot routes its top-tier compile to hexana (watch for the
 *       {@code [hexana] INSTALLED ...} line),</li>
 *   <li>checks {@code run} equals {@link ProgramBuilder#reference} on the whole corpus — i.e. the
 *       installed machine code is correct,</li>
 *   <li>prints a rough per-call time (caveat: with hexana as the only top tier, the surrounding
 *       loop is C1, not C2, so this is not an apples-to-apples vs. the full-C2 baseline).</li>
 * </ol>
 */
public final class SpecVerify {

    private SpecVerify() {
    }

    public static void main(String[] args) {
        final ProgramBuilder.Program p = ProgramBuilder.mixing(BenchState.ROUNDS, BenchState.ARITY);
        ProgramOracle.publish(p.code(), p.consts());

        final long[][] corpus = InputCorpus.generate(BenchState.CORPUS_SIZE, BenchState.SEED, BenchState.ARITY);
        final Interpreter interp = new Interpreter(BenchState.STACK_MAX);
        final int mask = corpus.length - 1; // CORPUS_SIZE is a power of two

        // Warm up: drive run hot so the top-tier (hexana) compile is requested + installed.
        long sink = 0;
        final int warm = 500_000;
        for (int i = 0; i < warm; i++) {
            sink += interp.run(p.code(), p.consts(), corpus[i & mask]);
        }

        // Correctness: installed code must equal the independent reference on every input.
        int bad = 0;
        for (int i = 0; i < corpus.length; i++) {
            long got = interp.run(p.code(), p.consts(), corpus[i]);
            long ref = ProgramBuilder.reference(p.consts(), corpus[i], BenchState.ROUNDS, BenchState.ARITY);
            if (got != ref) {
                if (bad < 6) {
                    System.err.println("[verify] MISMATCH @" + i + " got=" + got + " ref=" + ref);
                }
                bad++;
            }
        }

        // Rough timing (see caveat in the class doc).
        final int iters = 5_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += interp.run(p.code(), p.consts(), corpus[i & mask]);
        }
        long t1 = System.nanoTime();
        double nsPerCall = (t1 - t0) / (double) iters;

        System.out.println("[verify] sink=" + sink);
        System.out.printf("[verify] run(): %.1f ns/call (rough; C1 loop around hexana run)%n", nsPerCall);
        System.out.println(bad == 0
                ? "[verify] CORRECT: run == reference on all " + corpus.length + " inputs"
                : "[verify] FAILED: " + bad + " mismatches");
        if (bad != 0) {
            System.exit(1);
        }
    }
}