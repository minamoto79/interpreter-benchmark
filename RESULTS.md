# Results — specializing a bytecode interpreter with a custom JVMCI compiler

The `hexana` JVMCI compiler installs real AArch64 machine code for one method —
`Interpreter.run(int[] code, long[] consts, long[] input)` — by **partial-evaluating it against
the fixed program** (the first Futamura projection). This is the measured outcome.

## TL;DR

| `Interpreter.run` (16-round murmur-style mixing kernel) | ns/op | vs C2 |
|---|--:|--:|
| **C2** (generic `while(true) switch` dispatch loop) | **385.1 ± 6.0** | 1.0× |
| **hexana** (JVMCI, PE'd straight-line machine code) | **33.0 ± 0.6** | **11.7× faster** |
| hand-written `Specialized.eval` under **C2** (the PE *ceiling*) | 23.4 ± 0.8 | 16.5× |

hexana lands within **~1.4× of the hand-written ceiling**, transparently, for an application that
still just calls `interp.run(...)`. The result is independently verified correct:
`run == ProgramBuilder.reference` on all 4096 corpus inputs.

> Hardware/runtime: Apple Silicon, JVMCI-enabled JBR (`jbr21`, no Graal). Measured with JMH
> `avgt`, `-f 0` (see *Caveats*).

## What each engine does

- **C2** compiles `run` *generically* — it cannot know the program is fixed, so it emits an opcode
  dispatch (a compare/branch tree per instruction), keeps the operand stack as a heap `long[]` with
  a bounds check on every push/pop, and re-reads `code[pc++]` (bounds-checked) every iteration. This
  is the disassembly the experiment started from.
- **hexana** reads the fixed `code[]`/`consts[]` at compile time through JVMCI constant reflection
  (a *compilation-final program oracle*, `ProgramOracle.code/consts` — the raw-JVMCI analogue of
  Truffle's `@CompilationFinal` AST), then:
  - walks the opcode stream at compile time → **no dispatch** at run time;
  - models the operand stack in registers → **no heap stack, no bounds checks**;
  - folds `PUSH_CONST`/`SHR` operands to immediates → the only runtime memory traffic left is the
    `input[]` loads;
  - emits an **identity guard** on `code`/`consts`; on mismatch it traps and HotSpot **deoptimizes**
    back into the interpreter, so the installation is correct for *all* callers, not just the
    benchmark.

## Full suite (under hexana, `-f 0`)

```
Benchmark                               (batchSize)  Mode  Cnt     Score    Error  Units
EvalAverageTime.eval                            N/A  avgt    4    32.968 ±  0.573  ns/op
EvalBatchAverageTime.evalBatch                   64  avgt    4  1987.945 ± 27.112  ns/op   (= 31.1 ns/call)
SpecializedAverageTime.evalSpecialized          N/A  avgt    4    37.294 ±  0.835  ns/op   (C1 — see below)
```

A striking wrinkle: under hexana, the JVMCI-specialized `run` (33 ns) **beats the hand-written
`Specialized.eval` (37 ns)** — because with `UseJVMCICompiler=hexana` there is no C2, so everything
*except* `run` is stuck at C1, including the hand-written specialization. Its real C2 ceiling
(23.4 ns) is only reachable in a separate, non-hexana process.

## How the machine code gets installed (the hard parts)

Building correct straight-line arithmetic is easy; making HotSpot *accept and run* a hand-assembled
nmethod is the work:

1. **Skeleton** — vendored the JDK's own JVMCI code-install test assembler
   (`compiler/.../jvmci/asm/{TestAssembler,AArch64Asm,HexanaVMConfig}`), extended with
   `mul`/`eor`/`lsr`/`cmp`/backpatched branches.
2. **Guard → deopt** — `emitTrap(DebugInfo)` with a `BytecodeFrame` at bci 0 (the four parameters in
   their incoming argument registers) re-executes `run` in the interpreter on a program mismatch.
3. **The JDK17+ nmethod entry barrier** — a *default* JVMCI install is rejected with
   `nmethod entry barrier is missing` unless the code carries one, and HotSpot *verifies* it. For
   JVMCI the verifier is lenient: it only requires an `ldr` (literal) at the `ENTRY_BARRIER_PATCH`
   offset with a `section_word` reloc to a 4-byte data-section guard, plus a functional
   disarmed-compare + `method_entry_barrier`-stub tail (`emitNmethodEntryBarrier`). This was the last
   blocker; once emitted, the install succeeds and `run` dispatches to our code.

## Reproduce

```bash
export JAVA_HOME=/path/to/jvmci-jbr           # JVMCI-enabled JBR (jbr21)
mvn -q -pl compiler,bench -am package -DskipTests

# correctness + smoke (prints "[hexana] INSTALLED ..." and verifies run == reference):
bash compiler/run-hexana.sh verify

# benchmark under hexana (in-process; see caveat):
$JAVA_HOME/bin/java <jvmci-flags+exports> \
  -cp bench/target/benchmarks.jar:compiler/target/hexana-compiler-0.1.0-SNAPSHOT.jar \
  org.openjdk.jmh.Main "interp.benchmarks.EvalAverageTime" -f 0 -wi 5 -w 1s -i 5 -r 2s -bm avgt -tu ns
```

(The exact flag/`--add-exports` set is in `compiler/run-hexana.sh`.)

## Caveats

- **`-f 0` (in-process) is required.** `UseJVMCICompiler` makes hexana the *only* top tier; JMH forks
  do **not** inherit the JVMCI flags/exports, so a forked run would never reach hexana. Both the C2
  baseline and the hexana run use the same `-f 0` harness, so the 11.7× is apples-to-apples.
- **No C2 coexists with hexana** (that needs Graal as the JVMCI compiler). So non-`run` code — JMH's
  own measurement loop, and `Specialized.eval` — runs at C1 under hexana, which adds some overhead.
- **The ~33 → 23 ns gap** to the ceiling is one un-done optimization: each `PUSH_CONST` re-materializes
  its 64-bit constant (`4× movz/movk`) instead of being hoisted/deduped. Pure perf, not correctness.
- The program is specialization-bound to `ROUNDS=16, ARITY=4`; the guard deoptimizes for any other
  `code`/`consts`.