package org.jetbrains.hexana.interp;

/**
 * The tiny stack-machine instruction set the {@link Interpreter} dispatches over.
 *
 * <p>A program is an {@code int[]} of opcodes with inline operands (see the per-op comments);
 * constants live in a separate {@code long[]}, per-evaluation inputs in another {@code long[]}.
 * Deliberately minimal — just enough to express a non-trivial CPU-bound mixing kernel so the
 * interpreter's dispatch loop is the unambiguous hot method.
 */
public final class Opcodes {
    private Opcodes() {
    }

    /** Return: pop and return the top of stack. (no operand) */
    public static final int RET = 0;
    /** Push constants[operand]. (1 operand: constant index) */
    public static final int PUSH_CONST = 1;
    /** Push input[operand]. (1 operand: input index) */
    public static final int LOAD_INPUT = 2;
    /** a, b -> a + b */
    public static final int ADD = 3;
    /** a, b -> a - b */
    public static final int SUB = 4;
    /** a, b -> a * b */
    public static final int MUL = 5;
    /** a, b -> a ^ b */
    public static final int XOR = 6;
    /** a -> a >>> operand (unsigned shift). (1 operand: shift amount) */
    public static final int SHR = 7;
    /** a -> a, a (duplicate top) */
    public static final int DUP = 8;
    /** a -> (pop, discard) */
    public static final int POP = 9;
}