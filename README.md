# interpreter-jit-experiment

A **CPU-bound bytecode-interpreter** workload measured under JMH — the demonstration target
for an AI-generated **JVMCI compiler** that improves a legacy application *without modifying
it*, using **Hexana's JIT view as the source of truth** for what the JVM/JIT actually does.

This is the redesigned target after the protobuf benchmark turned out to be a poor one (its
hot path was I/O syscalls + timing instrumentation, with no compiler-addressable headroom, and
its profile was unreadable without frame pointers). An interpreter dispatch loop fixes all of
that.

## Why an interpreter is the right demonstration target

- **CPU-bound, high Java self-time.** `Interpreter.run` is a `while(true) switch` over the
  program's opcodes — no I/O, no syscalls, no allocation in the loop (the operand stack is
  allocated once and reused). The time is real Java compute the profiler attributes to one
  method.
- **Structural headroom C2 leaves on the table.** C2 compiles a *generic* dispatch loop — a
  branch-table jump per instruction, regardless of which program runs. The program here is
  **fixed** for the whole run; only inputs vary. A specializing compiler can partial-evaluate
  the loop against that fixed program and constant-fold the dispatch into straight-line code.
  This is the **first Futamura projection** — exactly how Truffle/GraalVM turn interpreters
  into fast native code — so the headroom is principled, not contrived.
- **Legible in Hexana.** *Before*: the JIT view shows the switch/branch-table dispatch in
  `run`. *After*: straight-line specialized arithmetic. That before/after **is** the demo, and
  it's "no source modification" in the literal sense — the app still runs the same interpreter;
  only the compiler changed.

## Runtime — JBR with JVMCI

Targets the source-built JetBrains Runtime at `$HOME/ws/github/jbr`, configured:

```sh
./configure \
    --with-boot-jdk=$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home \
    --with-jvm-variants=server \
    --with-debug-level=release \
    --with-jvm-features=jvmci \
    MAKE=/opt/homebrew/bin/gmake
gmake images
```

`--with-jvm-features=jvmci` enables the JVMCI *interface*; it does **not** bundle Graal (the
compiler slot is empty — that's what `compiler/` fills). `$JAVA_HOME` is the resulting image:

```
$HOME/ws/github/jbr/build/macosx-aarch64-server-release/images/jdk
```

If you only ran the exploded build (no `gmake images`), use
`…/build/macosx-aarch64-server-release/jdk` instead — it's a fully functional JVMCI JDK. See
`.envrc` for a `direnv` snippet. Verify before building:

```sh
$JAVA_HOME/bin/java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -version
```

## Build & run

```sh
export JAVA_HOME=$HOME/ws/github/jbr/build/macosx-aarch64-server-release/jdk
mvn package                              # builds bench/target/benchmarks.jar, runs CorrectnessTest
mvn test                                 # CorrectnessTest only

java -jar bench/target/benchmarks.jar    # full run (~15–20 min) -> results.json, jmh-result.json, compilation.log
# ad-hoc / profiling (single fork, one benchmark):
java -cp bench/target/benchmarks.jar org.openjdk.jmh.Main EvalAverageTime -f 1 -wi 5 -i 1000 -r 1s
```

## The workload

`Interpreter.run(int[] code, long[] consts, long[] input)` interprets a fixed murmur3-style
**mixing kernel** (`BenchState.ROUNDS = 16` rounds folding `ARITY = 4` input words) over a
corpus of random inputs. `ProgramBuilder` emits the program *and* holds the canonical
`reference()` computation the `CorrectnessTest` checks against. Three benchmarks: `EvalAverageTime`
(primary), `EvalSampleTime` (percentiles), `EvalBatchAverageTime` (`@Param` batch, amortized).

JMH config: `@Fork(5)`, 10×2s warmup, 20×2s measurement, ParallelGC, `-Xms == -Xmx`.
**`-XX:+PreserveFramePointer` is in the fork args** — without it Hexana/Instruments/async-profiler
can't unwind JIT frames and the profile is garbage. `EnableJVMCI` is deliberately absent (stock
C2 baseline); add it to the fork args once `hexana` targets `run`.

## Profiling workflow (Hexana as source of truth)

1. Run a single-fork benchmark (above). Attach **Instruments → Time Profiler** during steady
   state, 30–60 s. With `PreserveFramePointer` on, the hot self-time method should resolve to
   `Interpreter.run` (real name, not a bare hex address).
2. Open the run in **Hexana JIT View** (IntelliJ run config with the Hexana extension; the JVMTI
   agent attaches and the `.jit` dump opens). Find `run` — the **Machine code** tab shows C2's
   dispatch (branch table / indirect jumps); the **Combined** tab pairs it with the bytecode.
   *That generic dispatch is the inefficiency a specializing compiler removes.*
3. The AI-generated compiler (v1) reads that, specializes `run` against the fixed program, and
   you re-profile: Hexana now shows straight-line code, and JMH shows the speedup.

## compiler/ — the `hexana` JVMCI compiler

`compiler/` holds the reusable `hexana` scaffold (registers via `JVMCIServiceLocator`, is
selected by `-Djvmci.Compiler=hexana`, safely bails every method). `compiler/run-hexana.sh smoke`
proves the plumbing; see `compiler/README.md`. The real v1 step — specializing `run` — is a
Graal compiler phase / Truffle-style specialization plugged in through this JVMCI slot (not
hand-written codegen, which is months and crash-prone). The app is never modified.