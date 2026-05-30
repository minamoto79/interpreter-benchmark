package org.jetbrains.hexana.interp;

/**
 * A stack-bytecode interpreter. {@link #run} is THE hot method — a {@code while(true) switch}
 * dispatch loop over the program's opcodes — and is the JVMCI specialization target.
 *
 * <p>Why this is a good demonstration target (vs. the protobuf workload that wasn't):
 * <ul>
 *   <li><b>CPU-bound, high self-time.</b> No I/O, no syscalls, no allocation in the loop — the
 *       operand stack is allocated once and reused, so {@code run}'s time is real Java compute.</li>
 *   <li><b>Structural headroom C2 leaves on the table.</b> C2 compiles a <i>generic</i> dispatch
 *       loop: an indirect/branch-table jump per instruction, independent of which program runs.
 *       A specializing compiler that knows the program is fixed can partial-evaluate the loop
 *       against it — constant-fold the dispatch into straight-line code (the first Futamura
 *       projection, exactly how Truffle/Graal turn interpreters into fast code).</li>
 *   <li><b>Legible in Hexana.</b> Before: the JIT view shows the switch/branch-table dispatch.
 *       After: straight-line specialized arithmetic. The delta IS the demo.</li>
 * </ul>
 *
 * The interpreter holds no per-evaluation state besides the reused stack, so it is constructed
 * once (in {@code @Setup}) and reused across invocations.
 */
public final class Interpreter {

    private final long[] stack;

    public Interpreter(int stackSize) {
        this.stack = new long[stackSize];
    }

    /**
     * Execute {@code code} against {@code consts} and {@code input}, returning the value left on
     * top of the stack by {@code RET}.
     *
     * @param code   opcode stream with inline operands (see {@link Opcodes})
     * @param consts constant pool referenced by {@code PUSH_CONST}
     * @param input  per-evaluation inputs referenced by {@code LOAD_INPUT}
     */
    public long run(int[] code, long[] consts, long[] input) {
        final long[] s = stack;
        int sp = 0;
        int pc = 0;
        while (true) {
            final int op = code[pc++];
            switch (op) {
                case Opcodes.RET:
                    return s[sp - 1];
                case Opcodes.PUSH_CONST:
                    s[sp++] = consts[code[pc++]];
                    break;
                case Opcodes.LOAD_INPUT:
                    s[sp++] = input[code[pc++]];
                    break;
                case Opcodes.ADD: {
                    final long b = s[--sp];
                    s[sp - 1] = s[sp - 1] + b;
                    break;
                }
                case Opcodes.SUB: {
                    final long b = s[--sp];
                    s[sp - 1] = s[sp - 1] - b;
                    break;
                }
                case Opcodes.MUL: {
                    final long b = s[--sp];
                    s[sp - 1] = s[sp - 1] * b;
                    break;
                }
                case Opcodes.XOR: {
                    final long b = s[--sp];
                    s[sp - 1] = s[sp - 1] ^ b;
                    break;
                }
                case Opcodes.SHR:
                    s[sp - 1] = s[sp - 1] >>> code[pc++];
                    break;
                case Opcodes.DUP:
                    s[sp] = s[sp - 1];
                    sp++;
                    break;
                case Opcodes.POP:
                    sp--;
                    break;
                default:
                    throw new IllegalStateException("bad opcode " + op + " @ pc=" + (pc - 1));
            }
        }
    }
}