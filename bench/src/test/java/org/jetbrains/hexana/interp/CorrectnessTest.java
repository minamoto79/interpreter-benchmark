package org.jetbrains.hexana.interp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Correctness gate — must be green before any perf number is reported.
 *
 * <ol>
 *   <li>For every input in the corpus, {@link Interpreter#run} equals the independent
 *       {@link ProgramBuilder#reference} computation of the same mixing kernel.</li>
 *   <li>Determinism: same input → identical result across two runs.</li>
 *   <li>Runtime sanity: Java 21+ and the running JDK accepts {@code -XX:+EnableJVMCI}.</li>
 * </ol>
 */
class CorrectnessTest {

    @BeforeAll
    static void runtimeSanity() {
        assertTrue(Runtime.version().feature() >= 21,
                "must run on Java 21+ (the JVMCI JBR); was " + Runtime.version());
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        try {
            final Process p = new ProcessBuilder(
                    java.toString(), "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI", "-version")
                    .redirectErrorStream(true).start();
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "JVMCI sanity subprocess timed out");
            assertEquals(0, p.exitValue(),
                    "running JDK does not accept -XX:+EnableJVMCI — wrong JDK? java.home="
                            + System.getProperty("java.home"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail("could not run JVMCI sanity check: " + e);
        }
    }

    @Test
    void interpreterMatchesReferenceAndIsDeterministic() {
        final ProgramBuilder.Program p = ProgramBuilder.mixing(BenchState.ROUNDS, BenchState.ARITY);
        final long[][] corpus = InputCorpus.generate(2_000, BenchState.SEED, BenchState.ARITY);
        final Interpreter interp = new Interpreter(BenchState.STACK_MAX);

        for (int i = 0; i < corpus.length; i++) {
            final long[] in = corpus[i];
            final long got = interp.run(p.code(), p.consts(), in);
            final long expected = ProgramBuilder.reference(p.consts(), in, p.rounds(), p.arity());
            assertEquals(expected, got, "interpreter != reference @" + i);
            assertEquals(got, interp.run(p.code(), p.consts(), in), "non-deterministic @" + i);
            // The specialization ceiling must compute the same result as the interpreter, or
            // it isn't a faithful partial-evaluation of this program.
            assertEquals(expected, Specialized.eval(in), "Specialized != reference @" + i);
        }
    }
}