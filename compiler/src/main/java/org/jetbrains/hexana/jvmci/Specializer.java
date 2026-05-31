package org.jetbrains.hexana.jvmci;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.Location;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.hotspot.HotSpotConstant;
import jdk.vm.ci.hotspot.HotSpotReferenceMap;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.hexana.jvmci.asm.AArch64Asm;

/**
 * Partial-evaluates {@code Interpreter.run(int[] code, long[] consts, long[] input)} against a
 * FIXED program (the first Futamura projection) and drives {@link AArch64Asm} to emit:
 *
 * <ol>
 *   <li>a guard that the incoming {@code code}/{@code consts} arrays are identity-equal to the ones
 *       this code was specialized for; on mismatch it traps -> HotSpot deoptimizes and re-executes
 *       {@code run} in the interpreter (re-entered at bci 0 with the original arguments);</li>
 *   <li>straight-line code for the program: the opcode dispatch is gone (we walk the program at
 *       compile time), the operand stack lives in registers (modelled here), {@code PUSH_CONST} and
 *       {@code SHR} operands are folded to immediates, and the only runtime memory traffic left is
 *       the {@code input[]} loads.</li>
 * </ol>
 *
 * <p>The program is read through {@link ConstantReflectionProvider} from a compilation-final oracle
 * (see {@link HexanaCompiler}); this is raw-JVMCI's analogue of Truffle's {@code @CompilationFinal}
 * AST. Anything the walk can't model makes {@link #emit} throw, and the caller bails the compile.
 */
final class Specializer {

    // ---- the interpreter's instruction set (mirrors org.jetbrains.hexana.interp.Opcodes) ----
    private static final int RET = 0, PUSH_CONST = 1, LOAD_INPUT = 2, ADD = 3, SUB = 4,
            MUL = 5, XOR = 6, SHR = 7, DUP = 8, POP = 9;

    // ---- HotSpot AArch64 Java calling convention for the non-static run(...) ----
    // j_rarg0..3 == r1..r4 ; confirmed against C2's own compilation of run.
    private static final Register R_THIS = AArch64.r1;
    private static final Register R_CODE = AArch64.r2;
    private static final Register R_CONSTS = AArch64.r3;
    private static final Register R_INPUT = AArch64.r4;

    private final AArch64Asm asm;
    private final MetaAccessProvider meta;
    private final ConstantReflectionProvider cr;

    /** Free scratch registers. Excludes r4 (input, live across the whole body). */
    private final Deque<Register> free = new ArrayDeque<>();
    /** Reference counts for registers currently referenced by operand-stack entries. */
    private final Map<Register, Integer> refs = new HashMap<>();
    /** The compile-time model of the interpreter's operand stack (top = peekFirst). */
    private final Deque<Val> stack = new ArrayDeque<>();

    private final long arrayBaseLong;
    private final int arrayScaleLong;

    private Specializer(AArch64Asm asm, MetaAccessProvider meta, ConstantReflectionProvider cr) {
        this.asm = asm;
        this.meta = meta;
        this.cr = cr;
        // r0,r1,r2,r3,r5,r6,r7 are usable in the fast path: after the guard passes, this/code/consts
        // (r1/r2/r3) are dead; r0 was only a guard scratch; r4 (input) stays reserved.
        for (Register r : new Register[]{AArch64.r0, AArch64.r1, AArch64.r2, AArch64.r3,
                AArch64.r5, AArch64.r6, AArch64.r7}) {
            free.addLast(r);
        }
        this.arrayBaseLong = meta.getArrayBaseOffset(JavaKind.Long);
        this.arrayScaleLong = meta.getArrayIndexScale(JavaKind.Long);
    }

    /** A modelled operand-stack value: either a compile-time constant or a value held in a register. */
    private static final class Val {
        final boolean isConst;
        final long c;
        final Register reg;

        static Val constant(long c) {
            return new Val(true, c, null);
        }

        static Val reg(Register r) {
            return new Val(false, 0, r);
        }

        private Val(boolean isConst, long c, Register reg) {
            this.isConst = isConst;
            this.c = c;
            this.reg = reg;
        }
    }

    /**
     * Emit the full specialized body (guard + program + deopt) between {@code asm.emitPrologue()}
     * and {@code asm.emitEpilogue()}.
     */
    static void emit(AArch64Asm asm, MetaAccessProvider meta, ConstantReflectionProvider cr,
                     ResolvedJavaMethod runMethod, JavaConstant codeArray, JavaConstant constsArray) {
        new Specializer(asm, meta, cr).run(runMethod, codeArray, constsArray);
    }

    private void run(ResolvedJavaMethod runMethod, JavaConstant codeArray, JavaConstant constsArray) {
        // ---- guard: code/consts identity, else deopt ----
        // r0 is the only scratch used here so r1..r4 still hold the live args at the trap.
        Register g = AArch64.r0;
        asm.emitLoadPointerInto(g, (HotSpotConstant) codeArray);
        asm.emitCmpReg(R_CODE, g);
        int b1 = asm.emitCondBranch(AArch64Asm.COND_NE);
        asm.emitLoadPointerInto(g, (HotSpotConstant) constsArray);
        asm.emitCmpReg(R_CONSTS, g);
        int b2 = asm.emitCondBranch(AArch64Asm.COND_NE);

        // ---- fast path: walk the fixed program ----
        Integer len = cr.readArrayLength(codeArray);
        if (len == null) {
            throw new IllegalStateException("cannot read program length");
        }
        int pc = 0;
        boolean returned = false;
        while (pc < len) {
            int op = readCode(codeArray, pc++);
            switch (op) {
                case PUSH_CONST: {
                    int idx = readCode(codeArray, pc++);
                    stack.push(Val.constant(readConst(constsArray, idx)));
                    break;
                }
                case LOAD_INPUT: {
                    int idx = readCode(codeArray, pc++);
                    Register r = alloc();
                    asm.emitLoadLongFrom(r, R_INPUT, Math.toIntExact(arrayBaseLong + (long) idx * arrayScaleLong));
                    pushReg(r);
                    break;
                }
                case ADD:
                    binOp(Bin.ADD);
                    break;
                case SUB:
                    binOp(Bin.SUB);
                    break;
                case MUL:
                    binOp(Bin.MUL);
                    break;
                case XOR:
                    binOp(Bin.XOR);
                    break;
                case SHR: {
                    int sh = readCode(codeArray, pc++);
                    Val a = stack.pop();
                    boolean aConst = a.isConst;
                    Register ra = ensureReg(a);
                    Register rd = alloc();
                    asm.emitLsrImm(rd, ra, sh & 63);
                    release(a);
                    if (aConst) {
                        freeReg(ra);
                    }
                    pushReg(rd);
                    break;
                }
                case DUP: {
                    Val top = stack.peek();
                    if (top.isConst) {
                        stack.push(Val.constant(top.c));
                    } else {
                        stack.push(Val.reg(top.reg));
                        refs.merge(top.reg, 1, Integer::sum);
                    }
                    break;
                }
                case POP: {
                    release(stack.pop());
                    break;
                }
                case RET: {
                    Val a = stack.pop();
                    Register ra = ensureReg(a);
                    asm.emitIntRet(ra); // moves to x0, restores frame, ret (works for long)
                    returned = true;
                    pc = len; // stop
                    break;
                }
                default:
                    throw new IllegalStateException("unsupported opcode " + op + " @ " + (pc - 1));
            }
        }
        if (!returned) {
            throw new IllegalStateException("program did not RET");
        }

        // ---- deopt target for the guard ----
        int deoptPos = asm.codePos();
        asm.patchBranchTo(b1, deoptPos, AArch64Asm.COND_NE);
        asm.patchBranchTo(b2, deoptPos, AArch64Asm.COND_NE);
        asm.emitTrap(entryDeopt(runMethod));
    }

    private enum Bin { ADD, SUB, MUL, XOR }

    private void binOp(Bin kind) {
        Val vb = stack.pop();   // top
        Val va = stack.pop();   // below
        boolean bConst = vb.isConst;
        boolean aConst = va.isConst;
        // Materialise operands BEFORE allocating the destination, and release them AFTER, so the
        // destination register can never alias an operand we still need (or a DUP-shared value).
        Register rb = ensureReg(vb);
        Register ra = ensureReg(va);
        Register rd = alloc();
        switch (kind) {
            case ADD: asm.emitAdd(rd, ra, rb); break;
            case SUB: asm.emitSub(rd, ra, rb); break;
            case MUL: asm.emitMul(rd, ra, rb); break;
            case XOR: asm.emitEor(rd, ra, rb); break;
            default: throw new IllegalStateException();
        }
        release(va);
        release(vb);
        if (aConst) {
            freeReg(ra);
        }
        if (bConst) {
            freeReg(rb);
        }
        pushReg(rd);
    }

    /** Return the register holding {@code v}; for a constant, materialise it into a fresh register. */
    private Register ensureReg(Val v) {
        if (!v.isConst) {
            return v.reg;
        }
        Register r = alloc();
        asm.emitLoadLongInto(r, v.c);
        return r;
    }

    private void pushReg(Register r) {
        stack.push(Val.reg(r));
        refs.merge(r, 1, Integer::sum);
    }

    /** Drop one operand-stack reference to {@code v}; free its register when the last one goes. */
    private void release(Val v) {
        if (v.isConst) {
            return;
        }
        int n = refs.getOrDefault(v.reg, 0) - 1;
        if (n <= 0) {
            refs.remove(v.reg);
            freeReg(v.reg);
        } else {
            refs.put(v.reg, n);
        }
    }

    private Register alloc() {
        Register r = free.pollFirst();
        if (r == null) {
            throw new IllegalStateException("out of scratch registers");
        }
        return r;
    }

    private void freeReg(Register r) {
        free.addFirst(r);
    }

    private int readCode(JavaConstant codeArray, int i) {
        JavaConstant e = cr.readArrayElement(codeArray, i);
        if (e == null) {
            throw new IllegalStateException("cannot read code[" + i + "]");
        }
        return e.asInt();
    }

    private long readConst(JavaConstant constsArray, int i) {
        JavaConstant e = cr.readArrayElement(constsArray, i);
        if (e == null) {
            throw new IllegalStateException("cannot read consts[" + i + "]");
        }
        return e.asLong();
    }

    /**
     * Build the deopt frame state for re-entering {@code run} at bci 0: the four parameters live in
     * their incoming argument registers; all other locals are undefined at method entry.
     */
    private DebugInfo entryDeopt(ResolvedJavaMethod runMethod) {
        int numLocals = runMethod.getMaxLocals();
        JavaValue[] values = new JavaValue[numLocals];
        JavaKind[] slotKinds = new JavaKind[numLocals];
        ValueKind<?> objKind = asm.getValueKind(JavaKind.Object);
        Register[] argRegs = {R_THIS, R_CODE, R_CONSTS, R_INPUT};
        for (int i = 0; i < numLocals; i++) {
            if (i < argRegs.length) {
                values[i] = argRegs[i].asValue(objKind);
                slotKinds[i] = JavaKind.Object;
            } else {
                values[i] = Value.ILLEGAL;
                slotKinds[i] = JavaKind.Illegal;
            }
        }
        BytecodeFrame frame = new BytecodeFrame(null, runMethod, 0, false, false, values, slotKinds, numLocals, 0, 0);
        DebugInfo info = new DebugInfo(frame, null);
        Location[] objs = {
                Location.register(R_THIS), Location.register(R_CODE),
                Location.register(R_CONSTS), Location.register(R_INPUT),
        };
        Location[] derived = new Location[objs.length];
        int[] sizes = {8, 8, 8, 8};
        info.setReferenceMap(new HotSpotReferenceMap(objs, derived, sizes, 8));
        return info;
    }
}