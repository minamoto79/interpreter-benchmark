package org.jetbrains.hexana.specializer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Injects a guarded specialization fast path at the top of {@code Interpreter.run}:
 *
 * <pre>
 *   long run(int[] code, long[] consts, long[] input) {
 *       if (HexanaSpecialized.matches(code)) return HexanaSpecialized.eval(input);
 *       ... original generic dispatch loop ...
 *   }
 * </pre>
 *
 * The original body is left untouched after the guard, so any other program falls back to the
 * interpreter. C2 JITs the fast path (and inlines {@code matches}/{@code eval}) to the
 * specialization ceiling — the application is never modified.
 */
public final class RunTransformer implements ClassFileTransformer {

    private static final String TARGET = "org/jetbrains/hexana/interp/Interpreter";
    private static final String SPEC = "org/jetbrains/hexana/specializer/HexanaSpecialized";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) {
            return null;
        }
        try {
            final ClassReader cr = new ClassReader(classfileBuffer);
            final ClassNode cn = new ClassNode(Opcodes.ASM9);
            cr.accept(cn, 0);

            boolean injected = false;
            for (final MethodNode m : cn.methods) {
                if (m.name.equals("run") && m.desc.equals("([I[J[J)J")) {
                    final InsnList guard = new InsnList();
                    final LabelNode slow = new LabelNode();
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 1));   // code  (0=this,1=code,2=consts,3=input)
                    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SPEC, "matches", "([I)Z", false));
                    guard.add(new JumpInsnNode(Opcodes.IFEQ, slow));
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 3));   // input
                    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SPEC, "eval", "([J)J", false));
                    guard.add(new InsnNode(Opcodes.LRETURN));
                    guard.add(slow);                                 // original instructions follow here
                    m.instructions.insert(guard);
                    injected = true;
                }
            }
            if (!injected) {
                System.err.println("[hexana-spec] WARNING: run([I[J[J)J not found in Interpreter; left unchanged");
                return null;
            }

            // COMPUTE_FRAMES needs class-hierarchy resolution; point it at the target's loader.
            final ClassLoader cl = loader;
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return cl != null ? cl : super.getClassLoader();
                }
            };
            cn.accept(cw);
            System.err.println("[hexana-spec] specialized Interpreter.run fast path injected");
            return cw.toByteArray();
        } catch (Throwable t) {
            // On any failure, leave the class unchanged — never break the app.
            System.err.println("[hexana-spec] transform failed, leaving Interpreter unchanged: " + t);
            return null;
        }
    }
}