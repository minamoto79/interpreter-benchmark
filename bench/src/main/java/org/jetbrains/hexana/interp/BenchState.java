package org.jetbrains.hexana.interp;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH state: the fixed program + constants, a reusable interpreter, and a corpus of inputs the
 * benchmark rotates through. All built once per trial in {@link #setupTrial()}, so neither
 * program assembly, corpus generation, nor interpreter/stack allocation is ever on the measured
 * path — the only thing timed is {@link Interpreter#run}.
 */
@State(Scope.Thread)
public class BenchState {

    public static final int ROUNDS = 16;
    public static final int ARITY = 4;
    public static final int CORPUS_SIZE = 4096;
    public static final long SEED = 42L;
    public static final int STACK_MAX = 16;

    public int[] program;
    public long[] constants;
    public long[][] corpus;
    public int idx;
    public Interpreter interpreter;

    @Setup(Level.Trial)
    public void setupTrial() {
        final ProgramBuilder.Program p = ProgramBuilder.mixing(ROUNDS, ARITY);
        program = p.code();
        constants = p.consts();
        corpus = InputCorpus.generate(CORPUS_SIZE, SEED, ARITY);
        interpreter = new Interpreter(STACK_MAX);
        idx = 0;
    }
}