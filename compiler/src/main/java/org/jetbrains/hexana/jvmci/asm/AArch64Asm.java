/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2023, Arm Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.jetbrains.hexana.jvmci.asm;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.site.ConstantReference;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.HotSpotConstant;
import jdk.vm.ci.hotspot.HotSpotForeignCallTarget;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.VMConstant;

public class AArch64Asm extends TestAssembler {

    private static final Register scratchRegister = AArch64.rscratch1;
    private static final Register doubleScratch = AArch64.v9;

    public AArch64Asm(CodeCacheProvider codeCache, HexanaVMConfig config) {
        super(codeCache, config,
              16 /* initialFrameSize */, 16 /* stackAlignment */,
              AArch64Kind.DWORD /* narrowOopKind */,
              /* registers */
              AArch64.r0, AArch64.r1, AArch64.r2, AArch64.r3,
              AArch64.r4, AArch64.r5, AArch64.r6, AArch64.r7);
    }

    private static int f(int val, int msb, int lsb) {
        int nbits = msb - lsb + 1;
        assert val >= 0;
        assert val < (1 << nbits);
        assert msb >= lsb;
        return val << lsb;
    }

    private static int f(Register r, int msb, int lsb) {
        assert msb - lsb == 4;
        return f(r.encoding, msb, lsb);
    }

    private void emitNop() {
        code.emitInt(0xd503201f);
    }

    public void emitAdd(Register Rd, Register Rn, Register Rm) {
        // ADD (shifted register)
        code.emitInt(f(0b10001011000, 31, 21)
                     | f(Rm, 20, 16)
                     | f(0, 15, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    private void emitAdd(Register Rd, Register Rn, int imm12) {
        // ADD (immediate)
        code.emitInt(f(0b1001000100, 31, 22)
                     | f(imm12, 21, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    private void emitSub(Register Rd, Register Rn, int imm12) {
        // SUB (immediate)
        code.emitInt(f(0b1101000100, 31, 22)
                     | f(imm12, 21, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    public void emitSub(Register Rd, Register Rn, Register Rm) {
        // SUB (extended register)
        code.emitInt(f(0b11001011001, 31, 21)
                     | f(Rm, 20, 16)
                     | f(0b011000, 15, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    private void emitMov(Register Rd, Register Rm) {
        // MOV (register)
        code.emitInt(f(0b10101010000, 31, 21)
                     | f(Rm, 20, 16)
                     | f(0, 15, 10)
                     | f(AArch64.zr, 9, 5)
                     | f(Rd, 4, 0));
    }

    private void emitMovz(Register Rd, int imm16, int shift) {
        // MOVZ
        int hw = 0;
        switch (shift) {
            case 0:  hw = 0; break;
            case 16: hw = 1; break;
            case 32: hw = 2; break;
            case 48: hw = 3; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(0b110100101, 31, 23)
                     | f(hw, 22, 21)
                     | f(imm16, 20, 5)
                     | f(Rd, 4, 0));
    }

    private void emitMovk(Register Rd, int imm16, int shift) {
        // MOVK
        int hw = 0;
        switch (shift) {
            case 0:  hw = 0; break;
            case 16: hw = 1; break;
            case 32: hw = 2; break;
            case 48: hw = 3; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(0b111100101, 31, 23)
                     | f(hw, 22, 21)
                     | f(imm16, 20, 5)
                     | f(Rd, 4, 0));
    }

    private void emitShiftLeft(Register Rd, Register Rn, int shift) {
        // LSL (immediate)
        code.emitInt(f(0b1101001101, 31, 22)
                     | f(-shift & 0b111111, 21, 16)
                     | f(63 - shift, 15, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    private void emitLoadRegister(Register Rt, AArch64Kind kind, int offset) {
        // LDR (literal)
        int opc = 0;
        switch (kind) {
            case DWORD: opc = 0; break;
            case QWORD: opc = 1; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(opc, 31, 30)
                     | f(0b011000, 29, 24)
                     | f(offset, 23, 5)
                     | f(Rt, 4, 0));
    }

    private void emitLoadRegister(Register Rt, AArch64Kind kind, Register Rn, int offset) {
        // LDR (immediate)
        assert offset >= 0;
        int size = 0;
        switch (kind) {
            case DWORD: size = 0b10; break;
            case QWORD: size = 0b11; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(size, 31, 30)
                     | f(0b11100101, 29, 22)
                     | f(offset >> size, 21, 10)
                     | f(Rn, 9, 5)
                     | f(Rt, 4, 0));
    }

    private void emitStoreRegister(Register Rt, AArch64Kind kind, Register Rn, int offset) {
        // STR (immediate)
        assert offset >= 0;
        int size = 0, fp = 0;
        switch (kind) {
            case DWORD: size = 0b10; fp = 0; break;
            case QWORD: size = 0b11; fp = 0; break;
            case SINGLE: size = 0b10; fp = 1; break;
            case DOUBLE: size = 0b11; fp = 1; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(size, 31, 30)
                     | f(0b111, 29, 27)
                     | f(fp, 26, 26)
                     | f(0b0100, 25, 22)
                     | f(offset >> size, 21, 10)
                     | f(Rn, 9, 5)
                     | f(Rt, 4, 0));
    }

    private void emitBlr(Register Rn) {
        // BLR
        code.emitInt(f(0b1101011000111111000000, 31, 10)
                     | f(Rn, 9, 5)
                     | f(0, 4, 0));
    }

    private void emitFmov(Register Rd, AArch64Kind kind, Register Rn) {
        // FMOV (general)
        int ftype = 0, sf = 0;
        switch (kind) {
            case SINGLE: sf = 0; ftype = 0b00; break;
            case DOUBLE: sf = 1; ftype = 0b01; break;
            default: throw new IllegalArgumentException();
        }
        code.emitInt(f(sf, 31, 31)
                     | f(0b0011110, 30, 24)
                     | f(ftype, 23, 22)
                     | f(0b100111, 21, 16)
                     | f(0, 15, 10)
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    // ----- Hexana additions: ops the specialized mixing kernel + guard need. -----
    // All operate on 64-bit (X) registers. Encodings cross-checked against the Arm ARM.

    /** Condition codes for {@link #emitCondBranch}. */
    public static final int COND_EQ = 0b0000;
    public static final int COND_NE = 0b0001;

    /** {@code mul Xd, Xn, Xm} == {@code madd Xd, Xn, Xm, xzr}. */
    public void emitMul(Register Rd, Register Rn, Register Rm) {
        code.emitInt(f(0b10011011000, 31, 21)
                     | f(Rm, 20, 16)
                     | f(AArch64.zr, 14, 10)   // Ra = xzr
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    /** {@code eor Xd, Xn, Xm} (shifted register, LSL #0). */
    public void emitEor(Register Rd, Register Rn, Register Rm) {
        code.emitInt(f(0b11001010000, 31, 21)
                     | f(Rm, 20, 16)
                     | f(0, 15, 10)            // imm6 = 0
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    /** {@code lsr Xd, Xn, #shift} == {@code ubfm Xd, Xn, #shift, #63}. */
    public void emitLsrImm(Register Rd, Register Rn, int shift) {
        assert shift >= 0 && shift < 64;
        code.emitInt(f(0b1101001101, 31, 22)
                     | f(shift & 0b111111, 21, 16)  // immr = shift
                     | f(63, 15, 10)                // imms = 63
                     | f(Rn, 9, 5)
                     | f(Rd, 4, 0));
    }

    /** {@code cmp Xn, Xm} == {@code subs xzr, Xn, Xm} (shifted register, LSL #0). Sets NZCV. */
    public void emitCmpReg(Register Rn, Register Rm) {
        code.emitInt(f(0b11101011000, 31, 21)
                     | f(Rm, 20, 16)
                     | f(0, 15, 10)            // imm6 = 0
                     | f(Rn, 9, 5)
                     | f(AArch64.zr, 4, 0));   // Rd = xzr -> discard, keep flags
    }

    /**
     * Emit {@code b.<cond>} with a placeholder offset; returns this branch's code position so it
     * can be {@linkplain #patchBranchTo backpatched} once the target is known.
     */
    public int emitCondBranch(int cond) {
        int at = code.position();
        code.emitInt(0x54000000 | cond);  // imm19 = 0, patched later
        return at;
    }

    /**
     * Patch the imm19 of a {@code b.<cond>} previously emitted at {@code branchPos} to jump to
     * {@code targetPos}. The caller passes the original {@code cond} so we don't need to read the
     * code buffer back.
     */
    public void patchBranchTo(int branchPos, int targetPos, int cond) {
        int offset = targetPos - branchPos;          // bytes, multiple of 4
        int imm19 = (offset >> 2) & 0x7FFFF;
        code.putIntAt(branchPos, 0x54000000 | (imm19 << 5) | (cond & 0xF));
    }

    /** Current code position (byte offset), e.g. to mark a branch target. */
    public int codePos() {
        return code.position();
    }

    /**
     * Load a (wide) oop/metaspace constant into {@code dst}, recording the data patch so HotSpot
     * relocates it to the live object address at install time (and tracks it as an oop for GC).
     * Unlike {@link #emitLoadPointer(HotSpotConstant)} this targets a caller-chosen register rather
     * than allocating one, so the guard can avoid clobbering the incoming argument registers.
     */
    public void emitLoadPointerInto(Register dst, HotSpotConstant c) {
        recordDataPatchInCode(new ConstantReference((VMConstant) c));
        if (c.isCompressed()) {
            emitLoadPointer32(dst, 0xdeaddeadL);
        } else {
            emitLoadPointer48(dst, 0xdeaddeaddeadL);
        }
    }

    /** Load a 64-bit constant into a caller-chosen register (public wrapper over the private form). */
    public void emitLoadLongInto(Register dst, long c) {
        emitLoadLong(dst, c);
    }

    /** {@code ldr Xdst, [Xbase, #byteOffset]} — a wide (8-byte) load, e.g. a long[] element. */
    public void emitLoadLongFrom(Register dst, Register base, int byteOffset) {
        emitLoadRegister(dst, AArch64Kind.QWORD, base, byteOffset);
    }

    /**
     * Emit the JDK17+ nmethod entry barrier (stw_instruction_and_data_patch form) required for a
     * default JVMCI install. HotSpot's JVMCI verifier only requires that the instruction at the
     * {@code ENTRY_BARRIER_PATCH} offset is an {@code ldr (literal)} (top byte {@code 0x18}) with a
     * {@code section_word} reloc to a 4-byte guard in the data section; the rest makes the barrier
     * functional: compare the guard to the thread's disarmed value and, if armed, call the
     * {@code method_entry_barrier} stub (which preserves argument registers). Must be emitted right
     * after the prologue (frame complete) and before the method body.
     */
    public void emitNmethodEntryBarrier() {
        recordMark(config.MARKID_FRAME_COMPLETE);

        // 4-byte guard in the data section, targeted by the ldr(literal) below.
        DataSectionReference guard = new DataSectionReference();
        guard.setOffset(data.position());
        data.emitInt(0);

        // ldr w8, =guard  (LDR literal, 32-bit -> 0x18......, the pattern the verifier checks).
        recordMark(config.MARKID_ENTRY_BARRIER_PATCH);
        recordDataPatchInCode(guard);
        emitLoadRegister(AArch64.rscratch1, AArch64Kind.DWORD, 0xdead); // imm19 patched via the reloc

        // ldr w9, [rthread, #disarmed_offset]
        emitLoadRegister(AArch64.rscratch2, AArch64Kind.DWORD, AArch64.r28, config.threadDisarmedGuardValueOffset);

        // cmp x8, x9 (both are zero-extended 32-bit guard values; 64-bit cmp is equivalent).
        emitCmpReg(AArch64.rscratch1, AArch64.rscratch2);

        // b.eq skip  (guard == disarmed value -> not armed -> skip the stub call).
        int toSkip = emitCondBranch(COND_EQ);

        // Armed path: call the method_entry_barrier stub, then fall through to the body.
        emitLoadPointer48(AArch64.rscratch1, config.nmethodEntryBarrier);
        emitBlr(AArch64.rscratch1);

        patchBranchTo(toSkip, codePos(), COND_EQ);
    }

    @Override
    public void emitGrowStack(int size) {
        assert size % 16 == 0;
        if (size > -4096 && size < 0) {
            emitAdd(AArch64.sp, AArch64.sp, -size);
        } else if (size == 0) {
            // No-op
        } else if (size < 4096) {
            emitSub(AArch64.sp, AArch64.sp, size);
        } else if (size < 65535) {
            emitMovz(scratchRegister, size & 0xffff, 0);
            emitMovk(scratchRegister, (size >> 16) & 0xffff, 16);
            emitSub(AArch64.sp, AArch64.sp, scratchRegister);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void emitPrologue() {
        // Must be patchable by NativeJump::patch_verified_entry
        emitNop();
        if (config.ropProtection) {
            code.emitInt(0xf94003df);  // ldr xzr, [x30]
            code.emitInt(0xd503231f);  // paciaz
        }
        code.emitInt(0xa9bf7bfd);      // stp x29, x30, [sp, #-16]!
        code.emitInt(0x910003fd);      // mov x29, sp

        setDeoptRescueSlot(newStackSlot(AArch64Kind.QWORD));
    }

    @Override
    public void emitEpilogue() {
        recordMark(config.MARKID_DEOPT_HANDLER_ENTRY);
        recordCall(new HotSpotForeignCallTarget(config.handleDeoptStub), 4*4, true, null);
        emitCall(0xdeaddeaddeadL);
    }

    @Override
    public void emitCallPrologue(CallingConvention cc, Object... prim) {
        growFrame(cc.getStackSize());
        AllocatableValue[] args = cc.getArguments();
        for (int i = 0; i < args.length; i++) {
            emitLoad(args[i], prim[i]);
        }
    }

    @Override
    public void emitCallEpilogue(CallingConvention cc) {
        growFrame(-cc.getStackSize());
    }

    @Override
    public void emitCall(long addr) {
        emitLoadPointer48(scratchRegister, addr);
        emitBlr(scratchRegister);
    }

    @Override
    public void emitLoad(AllocatableValue av, Object prim) {
        if (av instanceof RegisterValue) {
            Register reg = ((RegisterValue) av).getRegister();
            if (prim instanceof Float) {
                emitLoadFloat(reg, (Float) prim);
            } else if (prim instanceof Double) {
                emitLoadDouble(reg, (Double) prim);
            } else if (prim instanceof Integer) {
                emitLoadInt(reg, (Integer) prim);
            } else if (prim instanceof Long) {
                emitLoadLong(reg, (Long) prim);
            }
        } else if (av instanceof StackSlot) {
            StackSlot slot = (StackSlot) av;
            if (prim instanceof Float) {
                emitFloatToStack(slot, emitLoadFloat(doubleScratch, (Float) prim));
            } else if (prim instanceof Double) {
                emitDoubleToStack(slot, emitLoadDouble(doubleScratch, (Double) prim));
            } else if (prim instanceof Integer) {
                emitIntToStack(slot, emitLoadInt(scratchRegister, (Integer) prim));
            } else if (prim instanceof Long) {
                emitLongToStack(slot, emitLoadLong(scratchRegister, (Long) prim));
            } else {
                assert false : "Unimplemented";
            }
        } else {
            throw new IllegalArgumentException("Unknown value " + av);
        }
    }

    private void emitLoadPointer32(Register ret, long addr) {
        long a = addr;
        long al = a;
        a >>= 16;
        long ah = a;
        a >>= 16;
        assert a == 0 : "invalid pointer" + Long.toHexString(addr);
        // Set upper 16 bits first. See MacroAssembler::patch_oop().
        emitMovz(ret, ((int)ah & 0xffff), 16);
        emitMovk(ret, ((int)al & 0xffff), 0);
    }

    private void emitLoadPointer48(Register ret, long addr) {
        // 48-bit VA
        long a = addr;
        emitMovz(ret, ((int)a & 0xffff), 0);
        a >>= 16;
        emitMovk(ret, ((int)a & 0xffff), 16);
        a >>= 16;
        emitMovk(ret, ((int)a & 0xffff), 32);
        a >>= 16;
        assert a == 0 : "invalid pointer" + Long.toHexString(addr);
    }

    @Override
    public Register emitLoadPointer(HotSpotConstant c) {
        recordDataPatchInCode(new ConstantReference((VMConstant) c));

        Register ret = newRegister();
        if (c.isCompressed()) {
            emitLoadPointer32(ret, 0xdeaddeadL);
        } else {
            emitLoadPointer48(ret, 0xdeaddeaddeadL);
        }
        return ret;
    }

    @Override
    public Register emitLoadPointer(Register b, int offset) {
        Register ret = newRegister();
        emitLoadRegister(ret, AArch64Kind.QWORD, b, offset);
        return ret;
    }

    @Override
    public Register emitLoadNarrowPointer(DataSectionReference ref) {
        recordDataPatchInCode(ref);

        Register ret = newRegister();
        emitLoadRegister(ret, AArch64Kind.DWORD, 0xdead);
        return ret;
    }

    @Override
    public Register emitLoadPointer(DataSectionReference ref) {
        recordDataPatchInCode(ref);

        Register ret = newRegister();
        emitLoadRegister(ret, AArch64Kind.QWORD, 0xdead);
        return ret;
    }

    private Register emitLoadDouble(Register reg, double c) {
        DataSectionReference ref = new DataSectionReference();
        ref.setOffset(data.position());
        data.emitDouble(c);

        recordDataPatchInCode(ref);
        emitLoadRegister(scratchRegister, AArch64Kind.QWORD, 0xdead);
        emitFmov(reg, AArch64Kind.DOUBLE, scratchRegister);
        return reg;
    }

    private Register emitLoadFloat(Register reg, float c) {
        DataSectionReference ref = new DataSectionReference();
        ref.setOffset(data.position());
        data.emitFloat(c);

        recordDataPatchInCode(ref);
        emitLoadRegister(scratchRegister, AArch64Kind.DWORD, 0xdead);
        emitFmov(reg, AArch64Kind.SINGLE, scratchRegister);
        return reg;
    }

    @Override
    public Register emitLoadFloat(float c) {
        Register ret = AArch64.v0;
        return emitLoadFloat(ret, c);
    }

    private Register emitLoadLong(Register reg, long c) {
        emitMovz(reg, (int)(c & 0xffff), 0);
        emitMovk(reg, (int)((c >> 16) & 0xffff), 16);
        emitMovk(reg, (int)((c >> 32) & 0xffff), 32);
        emitMovk(reg, (int)((c >> 48) & 0xffff), 48);
        return reg;
    }

    @Override
    public Register emitLoadLong(long c) {
        Register ret = newRegister();
        return emitLoadLong(ret, c);
    }

    private Register emitLoadInt(Register reg, int c) {
        emitMovz(reg, (int)(c & 0xffff), 0);
        emitMovk(reg, (int)((c >> 16) & 0xffff), 16);
        return reg;
    }

    @Override
    public Register emitLoadInt(int c) {
        Register ret = newRegister();
        return emitLoadInt(ret, c);
    }

    @Override
    public Register emitIntArg0() {
        return codeCache.getRegisterConfig()
            .getCallingConventionRegisters(HotSpotCallingConventionType.JavaCall, JavaKind.Int)
            .get(0);
    }

    @Override
    public Register emitIntArg1() {
        return codeCache.getRegisterConfig()
            .getCallingConventionRegisters(HotSpotCallingConventionType.JavaCall, JavaKind.Int)
            .get(1);
    }

    @Override
    public Register emitIntAdd(Register a, Register b) {
        emitAdd(a, a, b);
        return a;
    }

    @Override
    public void emitTrap(DebugInfo info) {
        // Dereference null pointer
        emitMovz(scratchRegister, 0, 0);
        recordImplicitException(info);
        emitLoadRegister(AArch64.zr, AArch64Kind.QWORD, scratchRegister, 0);
    }

    @Override
    public void emitIntRet(Register a) {
        emitMov(AArch64.r0, a);
        code.emitInt(0x910003bf);      // mov sp, x29
        code.emitInt(0xa8c17bfd);      // ldp x29, x30, [sp], #16
        if (config.ropProtection) {
            code.emitInt(0xd503239f);  // autiaz
            code.emitInt(0xf94003df);  // ldr xzr, [x30]
        }
        code.emitInt(0xd65f03c0);      // ret
    }

    @Override
    public void emitFloatRet(Register a) {
        assert a == AArch64.v0 : "Unimplemented move " + a;
        code.emitInt(0x910003bf);      // mov sp, x29
        code.emitInt(0xa8c17bfd);      // ldp x29, x30, [sp], #16
        if (config.ropProtection) {
            code.emitInt(0xd503239f);  // autiaz
            code.emitInt(0xf94003df);  // ldr xzr, [x30]
        }
        code.emitInt(0xd65f03c0);      // ret
    }

    @Override
    public void emitPointerRet(Register a) {
        emitIntRet(a);
    }

    @Override
    public StackSlot emitPointerToStack(Register a) {
        return emitLongToStack(a);
    }

    @Override
    public StackSlot emitNarrowPointerToStack(Register a) {
        return emitIntToStack(a);
    }

    @Override
    public Register emitUncompressPointer(Register compressed, long base, int shift) {
        if (shift > 0) {
            emitShiftLeft(compressed, compressed, shift);
        }

        if (base != 0) {
            emitLoadLong(scratchRegister, base);
            emitAdd(compressed, compressed, scratchRegister);
        }

        return compressed;
    }

    private StackSlot emitDoubleToStack(StackSlot slot, Register a) {
        emitStoreRegister(a, AArch64Kind.DOUBLE, AArch64.sp, slot.getOffset(frameSize));
        return slot;
    }

    @Override
    public StackSlot emitDoubleToStack(Register a) {
        StackSlot ret = newStackSlot(AArch64Kind.DOUBLE);
        return emitDoubleToStack(ret, a);
    }

    private StackSlot emitFloatToStack(StackSlot slot, Register a) {
        emitStoreRegister(a, AArch64Kind.SINGLE, AArch64.sp, slot.getOffset(frameSize));
        return slot;
    }

    @Override
    public StackSlot emitFloatToStack(Register a) {
        StackSlot ret = newStackSlot(AArch64Kind.SINGLE);
        return emitFloatToStack(ret, a);
    }

    private StackSlot emitIntToStack(StackSlot slot, Register a) {
        emitStoreRegister(a, AArch64Kind.DWORD, AArch64.sp, slot.getOffset(frameSize));
        return slot;
    }

    @Override
    public StackSlot emitIntToStack(Register a) {
        StackSlot ret = newStackSlot(AArch64Kind.DWORD);
        return emitIntToStack(ret, a);
    }

    private StackSlot emitLongToStack(StackSlot slot, Register a) {
        emitStoreRegister(a, AArch64Kind.QWORD, AArch64.sp, slot.getOffset(frameSize));
        return slot;
    }

    @Override
    public StackSlot emitLongToStack(Register a) {
        StackSlot ret = newStackSlot(AArch64Kind.QWORD);
        return emitLongToStack(ret, a);
    }

}
