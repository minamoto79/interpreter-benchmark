package org.jetbrains.hexana.interp;

/**
 * The compilation-final program oracle: the fixed program the {@link Interpreter} is running,
 * published so the {@code hexana} JVMCI compiler can read it as a constant at compile time and
 * partial-evaluate {@link Interpreter#run} against it.
 *
 * <p>This is the raw-JVMCI analogue of Truffle's {@code @CompilationFinal} AST fields: a specializing
 * compiler can only fold the dispatch loop away if the program is available to it as a constant.
 * The compiler reads {@link #code}/{@link #consts} via JVMCI constant reflection and emits an
 * identity guard on them, so the installed code stays correct if a different program is ever run
 * (the guard deoptimizes back into the interpreter).
 *
 * <p>The fields are {@code volatile} because they are written by the benchmark setup thread and
 * read by a JVMCI compiler thread; identity (not contents) is what the guard checks.
 */
public final class ProgramOracle {

    /** The opcode stream the interpreter is currently executing, or {@code null} before setup. */
    public static volatile int[] code;

    /** The constant pool referenced by {@code PUSH_CONST}, or {@code null} before setup. */
    public static volatile long[] consts;

    private ProgramOracle() {
    }

    /** Publish the fixed program for the compiler to specialize against. */
    public static void publish(int[] code, long[] consts) {
        ProgramOracle.code = code;
        ProgramOracle.consts = consts;
    }
}