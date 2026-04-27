# Android Startup Optimization Framework

A Kotlin/Android library for **cold-start and pre–first-frame initialization**. It models startup work as a **directed acyclic graph (DAG)** of tasks, runs **independent branches in parallel** once their dependencies are satisfied (shortening wall-clock time toward the **critical path**), and provides registration, structured coroutine dispatch, **execution phases** (decouple from the first frame), an optional **BeforeFirstFrame “critical” subgraph** (via **`needWait`** and **`startBeforeFirstFrameUntilCritical`**), **supervisor-style failure handling** (one task failure does not cancel siblings), **optional IO concurrency limits**, a **dedicated or global task registry** for tests, **injectable timing sinks** (`TaskTimingSink`) and **per-task run interceptors** (e.g. Android `Trace` / systrace), and runtime wrappers (lazy once, timeout, priority override).

**Demo app:** `:app` · **Modules:** `:core` (engine), `:runtime` (optional wrappers), `:sample` (example tasks).

---

## Table of contents

1. [Why startup optimization matters](#1-why-startup-optimization-matters)  
2. [How this framework optimizes startup (principles)](#2-how-this-framework-optimizes-startup-principles)  
3. [Module layout](#3-module-layout)  
4. [Core APIs](#4-core-apis)  
5. [Using the framework in code](#5-using-the-framework-in-code)  
6. [Running the sample and the benchmark](#6-running-the-sample-and-the-benchmark)  
7. [Integrating into another Android project](#7-integrating-into-another-android-project)  
8. [Relationship to Jetpack / other libraries](#8-relationship-to-jetpack--other-libraries)  
9. [Best practices and caveats](#9-best-practices-and-caveats)  
10. [License](#10-license)

---

## 1. Why startup optimization matters

### 1.1 The problem

During **cold start**, an app typically must initialize logging, configuration, the network stack, local storage, SDKs (push, analytics, maps, and so on). A **naive pattern** is to run everything **sequentially** in a single coroutine or on the main thread:

- **Total time** is approximately the **sum** of all task durations, even when some tasks **do not depend on each other** and could overlap in time.
- Heavy work on the **main thread** increases jank and ANR risk; pushing everything to background threads without explicit ordering can break **implicit contracts** (for example, “configure logger before opening the network layer”).

### 1.2 What this framework does

- You declare **explicit dependencies** between named tasks (`dependencies: List<String>`).
- The scheduler runs tasks **only after** all predecessors have finished successfully (or marks dependents **skipped** if a dependency failed—see [§2.5](#25-failure-handling-supervisor-scope-and-skipped-tasks)), and **overlaps** work where the graph allows (subject to thread choice via `runOnMainThread` and optional **IO concurrency caps**).
- You can split work by **`ExecutionPhase`** and trigger later phases after the first frame or main **idle**, so non-critical work does not compete with first layout.
- Initialization is **centralized** and **testable**, instead of ad-hoc call chains scattered across `Application` and feature code.

---

## 2. How this framework optimizes startup (principles)

### 2.1 Model startup as a DAG

- Each task has a unique **`id`**.
- Each entry in **`dependencies`** is the `id` of a task that must **complete successfully** before this task may run (or the dependent is **skipped** if a dependency failed).
- The global graph must be a **DAG** (no cycles). If a cycle exists, `sortTasks` / phase planning fails with a clear error.
- **`validateDependencyPhases`** ensures a task never depends on a **later** [`ExecutionPhase`](#24-execution-phases) than its own (dependencies must be same or **earlier** phase).

Intuitively:

- Tasks with **no blocking dependencies** (within the current phase, given satisfied earlier phases) can be scheduled.
- When **several tasks** can run, they may **run concurrently** (on IO or main, per `runOnMainThread`), **subject to** the optional **IO semaphore** (see [§2.6](#26-io-concurrency-limit)).

The **optimization** does not reduce a single `run()`’s own duration; it **reduces total wall-clock time** by **overlapping** independent or partially independent work so end-to-end time moves toward the **critical path** of the graph.

### 2.2 Topological sort (Kahn’s algorithm + priority queue)

**Monolithic graph:** `sortTasks` in `Dag.kt` (all tasks, single phase in terms of ordering).

1. **Validation:** **Unique** task ids; every dependency id **exists**; `validateDependencyPhases`.
2. **Graph:** edges, **in-degrees**, `PriorityQueue` for ready nodes ordered by **`priority`**, then **`id`**.

**Per execution phase:** `planRunnableTasksInPhase` can **restrict to a task-id subset** (`onlyInPhaseTaskIds`) and **skip ids already in `satisfiedFromEarlier`**—including from a **previous call** for the **same** phase (e.g. run **critical** BFF work first, then the rest of **BeforeFirstFrame**). It **drops** tasks whose dependencies are in a **failed** set, records **skipped** reasons, then runs **Kahn** on the remaining tasks. Same priority/tie-break rules apply within the phase.

### 2.3 Concurrency: `join` dependencies, then `execute` (avoids deadlock)

`StartupManager` launches one **`Job` per task** in topological order for that phase. Inside each job:

1. For each `dependency` that is not already **satisfied from earlier successful phases**, **`join`** the corresponding **same-phase** `Job`, then check that dependency’s **outcome** (if it **failed**, this task is **skipped** and does not run `run()`).
2. A **`TaskRunInterceptor`** (default **no-op**) wraps the successful path: it calls your **`execute`** block, which in turn runs **`StartupDispatcher.execute(task)`** (so you can add **Android `Trace.beginSection` / `endSection`**, or custom hooks, per task). See [§2.8](#28-observability).
3. **`StartupDispatcher.execute(task)`** runs `task.run()` in **`withContext`(`Main.immediate` or `IO`)** so exceptions are observable in the parent coroutine.
4. Non–main-thread work can be wrapped in **`Semaphore.withPermit`** when [`maxParallelIo`](#43-startupmanager) is set, to cap concurrent IO-style tasks.
5. **All** jobs in the phase are **joined** at the end of that phase.

**Why launch in topological order?** So predecessor `Job`s exist before a dependent could suspend waiting for them, avoiding a **deadlock** from scheduling order.

**Parallelism:** Independent branches in the same phase still **overlap in time** after their `join`s to predecessors complete.

### 2.4 Execution phases

[`ExecutionPhase`](core/src/main/kotlin/com/ikkoaudio/startup/core/ExecutionPhase.kt) (ordinal order):

| Phase | Typical use |
|--------|------------|
| **`BeforeFirstFrame`** | Earliest work (e.g. right after `Application` / `Activity` `onCreate` before first draw). |
| **`AfterFirstFrame`** | After one or more **Choreographer** frames, to avoid contending with measure/layout. |
| **`Idle`** | After the main **Looper** reports idle, for heavier deferred init. |

- **`StartupManager.start()`** runs **all non-empty phases in order** in one **`suspend` coroutine** (no real “wait for frame” between phases—suitable for tests or total work measurement).
- **`StartupManager.startPhase(phase, …)`** runs **one** phase (optionally **`onlyInPhaseTaskIds`** for a sub-DAG in that phase). You call it from the app after **frames** or **idle** as needed; pass **successful** ids in **`satisfiedFromEarlier`** for tasks already finished in a prior chunk (including another **BeforeFirstFrame** run).
- **`StartupManager.startBeforeFirstFrameUntilCritical(isCritical = { it.needWait }, …)`** runs only the **transitive in-phase dependency closure** of tasks matching **`isCritical`** (default: **`needWait == true`**). Dismiss a splash or route when this **`PhaseResult`** returns; then call **`startPhase(BeforeFirstFrame, satisfiedFromEarlier = that.successTaskIds, …)`** for any **remaining** BFF tasks, or use the app’s **`BeforeFirstFrameMode.CriticalThenRemaining`** inside [`runPhasedStartup`](app/src/main/kotlin/com/ikkoaudio/androidstartupoptimizationframework/startup/PhasedStartup.kt) to do both steps automatically.
- The demo **`runPhasedStartup`** (defaults: **`BeforeFirstFrameMode.Full`**, **`PhasedStartupRunPolicy.EveryTime`**) chains: BFF (see below) on a worker dispatcher → 2 **Choreographer** frames → optional **`onAfterVsync`** (default **`Activity.reportFullyDrawn()`** on API 19+) on the main thread → `startPhase(AfterFirstFrame)` → **main idle** (or **`idleHandlerTimeoutMs`**) → `startPhase(Idle)`.

**Choreographer and the main `Looper`:** frame callbacks and `Looper.myQueue()` are tied to the **main** thread. The helpers that wait for frames or for **idle** use **`withContext(Dispatchers.Main.immediate)`** internally, so **`runPhasedStartup` is safe to call from any coroutine** (e.g. `Default`); you do not have to pre-switch to the main dispatcher.

`StartupTask` exposes `executionPhase` (default **`BeforeFirstFrame`**).

### 2.5 Failure handling (supervisor scope and skipped tasks)

- Each **phase** runs in **`supervisorScope`**: if one task’s `run()` **throws** (other than `CancellationException`), **sibling** tasks in the same phase are **not** automatically cancelled; the error is stored in a [`TaskFailure`](core/src/main/kotlin/com/ikkoaudio/startup/core/StartupResult.kt).
- Tasks that **depend** on a failed task are **not run**; they are reported as [`SkippedTask`](core/src/main/kotlin/com/ikkoaudio/startup/core/StartupResult.kt) with a reason (and may also be pre-filtered in `planRunnableTasksInPhase` when `failedFromEarlier` is passed).
- **`startPhase` returns** a [`PhaseResult`](core/src/main/kotlin/com/ikkoaudio/startup/core/StartupResult.kt) with **`successTaskIds`**, **`failures`**, and **`skipped`**.
- **`start` returns** [`FullStartupResult`](core/src/main/kotlin/com/ikkoaudio/startup/core/StartupResult.kt) (`phaseResults` per phase, plus helpers `allFailures`, `isOverallSuccess`, etc.).

### 2.6 IO concurrency limit

- **`StartupManager(..., maxParallelIo = n)`** (optional) installs a **kotlinx** [`Semaphore(n)`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-semaphore/): only **non–main-thread** tasks (`runOnMainThread == false`) acquire a permit for the duration of `execute()`. **Main-thread** tasks are not limited. **`null` / default** means no limit (the demo uses **`4`** in `MainActivity`).

### 2.7 “Traditional” baseline in this repo

[`NaiveJetpackStyleStartup`](app/src/main/kotlin/com/ikkoaudio/androidstartupoptimizationframework/benchmark/NaiveJetpackStyleStartup.kt) simulates a **single coroutine** with a **fixed order** of `run()` calls (no DAG parallelism for independent branches). [`StartupComparisonScreen`](app/src/main/kotlin/com/ikkoaudio/androidstartupoptimizationframework/ui/StartupComparisonScreen.kt) compares its wall time to the **phased** framework path.

### 2.8 Observability

**Per-task wall time (after a successful `run()`):** inject a [`TaskTimingSink`](core/src/main/kotlin/com/ikkoaudio/startup/core/diagnostics/TaskTimingSink.kt) via **`StartupManager(..., taskTiming = …)`**. Defaults to the process-wide in-memory object [`StartupTracer`](core/src/main/kotlin/com/ikkoaudio/startup/core/tracer/StartupTracer.kt) (extends [`InMemoryTaskTraceStore`](core/src/main/kotlin/com/ikkoaudio/startup/core/diagnostics/InMemoryTaskTraceStore.kt)).

- **`InMemoryTaskTraceStore`** — thread-safe list for debug UIs; **`onTaskEnd`**, **`reset`**, **`snapshot()`**, **`printTo`**.  
- **`LogcatTaskTimingSink`** — `Log.d` per task; use **`enabled = { BuildConfig.DEBUG }`** (or a feature flag) so **release** stays quiet.  
- **`CompositeTaskTimingSink(sinks)`** — fan out to several sinks (e.g. in-memory + Logcat).  
- **`SampledTaskTimingSink(delegate, sampleProbability)`** — forward a **fraction** of events (e.g. `0.01` in production for cheap telemetry).

`StartupManager.start` / `startPhase` call **`reset()`** on the sink when appropriate; success paths can **print** in-memory traces to a `sink: (String) -> Unit` (same hook as `println` in the API).

**Systrace / Perfetto (system trace):** inject **`TaskRunInterceptor`**. The built-in [`AndroidTraceTaskRunInterceptor`](core/src/main/kotlin/com/ikkoaudio/startup/core/diagnostics/AndroidTraceTaskRunInterceptor.kt) wraps each task body with [`Trace.beginSection` / `endSection`](https://developer.android.com/reference/android/os/Trace) (labels truncated to 127 bytes). Use **`TaskRunInterceptor.None`** (default) in **release** or when tracing is off. A convenience is **`StartupManager.defaultInterceptor(BuildConfig.DEBUG)`** — `AndroidTraceTaskRunInterceptor` in debug, **no-op** in release (see **`MainActivity`** in `:app`).

This is **not** a full distributed trace; for production, combine **sampling** + **gated** Logcat and optional **A/B** on interceptors.

---

## 3. Module layout

| Module | Role |
|--------|------|
| **`core`** | `StartupTask`, `ExecutionPhase`, `StartupTaskProvider`, `StartupTaskRegistry` / `TaskRegistry`, `withTestRegistry`, `computeBeforeFirstFrameCriticalClosure`, `Dag` (`sortTasks`, `planRunnableTasksInPhase`, …), `StartupDispatcher`, `StartupManager` (`startBeforeFirstFrameUntilCritical`, …), `StartupResult` (`PhaseResult`, `FullStartupResult`, …), `StartupTracer` / `InMemoryTaskTraceStore`, `TaskTimingSink` + `LogcatTaskTimingSink` / `CompositeTaskTimingSink` / `SampledTaskTimingSink`, `TaskRunInterceptor` + `AndroidTraceTaskRunInterceptor` |
| **`runtime`** | Optional wrappers: `LazyTask`, `TimeoutTask`, `PriorityTask` (delegate `executionPhase` too) |
| **`sample`** | Example `Init*` tasks (mixed phases) and `CoreTaskProvider` |
| **`app`** | `Application` registration, Compose UI, `runPhasedStartup`, benchmark |

`settings.gradle.kts` includes `:app`, `:core`, `:runtime`, `:sample`.

---

## 4. Core APIs

### 4.1 `StartupTask`

| Member | Meaning |
|--------|--------|
| `id` | Unique string; referenced by other tasks’ `dependencies`. |
| `dependencies` | All listed tasks must **succeed** before this task runs; otherwise this task may be **skipped** if a dependency failed. |
| `runOnMainThread` | If `true`, `run()` on the main dispatcher; else on `Dispatchers.IO` (and subject to **IO** semaphore if configured). |
| `needWait` | Drives the **BeforeFirstFrame critical closure** (with transitive BFF dependencies): use with **`startBeforeFirstFrameUntilCritical`**, or **`BeforeFirstFrameMode.CriticalThenRemaining`** in `runPhasedStartup`, so splash / routing can wait only for this subset; run the rest of BFF in a follow-up **`startPhase`**. |
| `priority` | Default `0`. Higher = earlier among ready tasks in the same phase (topo tie-break). |
| `executionPhase` | When the task is scheduled: `BeforeFirstFrame` (default), `AfterFirstFrame`, or `Idle`. |
| `suspend fun run()` | Actual work; use **suspend** I/O; avoid blocking the main thread. |

### 4.2 `StartupTaskProvider`, `StartupTaskRegistry`, and `TaskRegistry`

- Implement `StartupTaskProvider` with `fun provide(): List<StartupTask>`.
- The **`StartupTaskRegistry`** class holds the same `register` / `clear` / `collectTasks()` API; **`object TaskRegistry : StartupTaskRegistry()`** is the process-wide default. Register in `Application` or a composition root.
- For **tests**, prefer a local **`StartupTaskRegistry()`** and **`withTestRegistry { … }`** (see [§7.4](#74-testing)) so the global `TaskRegistry` is never touched; pass **`collectTasks()`** (or `StartupManager(registry, …)`) into **`StartupManager`**.

### 4.3 `StartupManager`

```kotlin
val manager = StartupManager(
    tasks = TaskRegistry.collectTasks(),
    // Or: StartupManager(StartupTaskRegistry().apply { register(MyProvider()) }, maxParallelIo = 4, …)
    maxParallelIo = 4, // optional cap for concurrent non–main tasks; omit or null = unlimited
    taskTiming = CompositeTaskTimingSink(
        listOf(
            StartupTracer,
            LogcatTaskTimingSink(enabled = { BuildConfig.DEBUG }),
        ),
    ),
    runInterceptor = StartupManager.defaultInterceptor(BuildConfig.DEBUG),
)
// Omit `taskTiming` and `runInterceptor` to use defaults: `StartupTracer` + `TaskRunInterceptor.None`.

// Run all phases back-to-back in one coroutine (suspending); returns summary
val result: FullStartupResult = manager.start(printDag = true) { line -> Log.d("Startup", line) }

// Or run only the “critical” BFF closure (default predicate: needWait), then the rest in a second call:
val critical: PhaseResult = manager.startBeforeFirstFrameUntilCritical() // { it.needWait } by default
val rest: PhaseResult = manager.startPhase(
    phase = ExecutionPhase.BeforeFirstFrame,
    satisfiedFromEarlier = critical.successTaskIds,
    failedFromEarlier = emptySet(), // or merge failures from your policy
    printDag = false,
)

// Or run a single phase (e.g. after first frame)
val phase: PhaseResult = manager.startPhase(
    phase = ExecutionPhase.AfterFirstFrame,
    satisfiedFromEarlier = setOf("logger"),   // ids that succeeded in previous phases (or in an earlier BFF chunk)
    failedFromEarlier = emptySet(),            // ids that failed; dependents are skipped
    printDag = false,
    onlyInPhaseTaskIds = null,                 // optional subset of ids in this phase
)
```

- **`satisfiedFromEarlier`** should contain only **successful** task ids. **`failedFromEarlier`** should list **failed** ids so the planner can skip dependents.
- **`start` is `suspend`** and uses `coroutineScope` across phases. **`startPhase` is `suspend`** and uses `supervisorScope` for that phase’s tasks. **`startBeforeFirstFrameUntilCritical` is `suspend`** and is **only** for **BeforeFirstFrame** + the computed closure.

### 4.4 `runtime` module (optional)

| Class | Behavior |
|-------|----------|
| `LazyTask(delegate)` | At most one full successful `run()`; delegates `executionPhase` too. |
| `TimeoutTask(delegate, timeoutMs)` | `withTimeout` around `run()`. |
| `PriorityTask(delegate, priority)` | Overrides `priority` (and `executionPhase` from delegate). |

### 4.5 Result types (`core`)

- **`TaskFailure`**, **`SkippedTask`**, **`PhaseResult`**, **`FullStartupResult`** — see `StartupResult.kt`. Use `isOverallSuccess` / `hasTaskFailures` to drive UI or logging.

### 4.6 Phased startup helper (`app` sample)

- **`runPhasedStartup(activity, manager, …)`** — end-to-end: **BeforeFirstFrame** (mode **`Full`** vs **`CriticalThenRemaining`**), then **2× Choreographer** frames, then **`onAfterVsync`** (default **`reportFullyDrawn`**) on the main thread, **AfterFirstFrame**, then **main idle** (with optional **`idleHandlerTimeoutMs`**, because a busy main looper may never go idle), then **Idle**. Uses **`ensureActive()`** between steps. Optional **`onWindowReady(activity)`** at the start (main thread) to hook decor / `ViewTreeObserver` / alignment with your **first draw** definition.  
- **`PhasedStartupRunPolicy`:** **`EveryTime`** (default, e.g. benchmarks), **`AtMostOncePerProcess`**, or **`AtMostOncePerActivity`** (decorView tag) to avoid duplicate phased runs.  
- Frame/idle waiters use **`Dispatchers.Main.immediate`** (see [§2.4](#24-execution-phases)). You can combine with **`ProcessLifecycleOwner`** in the app to avoid double-starting from multiple entry points; policy + lifecycle are complementary.

---

## 5. Using the framework in code

### 5.1 Define a task (with an execution phase)

```kotlin
class InitNetworkTask : StartupTask {
    override val id = "network"
    override val dependencies = listOf("logger")
    override val runOnMainThread = false
    override val needWait = false
    override val executionPhase = ExecutionPhase.AfterFirstFrame

    override suspend fun run() { /* ... */ }
}
```

### 5.2 Register and run

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskRegistry.register(NetworkStartupProvider())
    }
}
```

```kotlin
suspend fun runAllPhasesInOneBlock() {
    val manager = StartupManager(
        tasks = TaskRegistry.collectTasks(),
        maxParallelIo = 4,
        taskTiming = StartupTracer, // or CompositeTaskTimingSink(…) — see §2.8
        runInterceptor = TaskRunInterceptor.None,
    )
    val result = manager.start(printDag = BuildConfig.DEBUG)
    if (!result.isOverallSuccess) { /* log result.allFailures */ }
}

// Isolated test registry (no global TaskRegistry)
fun exampleWithTestRegistry() = withTestRegistry(
    { register(MyStartupProvider()) },
) { r ->
    StartupManager(r, maxParallelIo = 4)
}
```

---

## 6. Running the sample and the benchmark

### Build

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

Run the **`app`** configuration in Android Studio.

The **comparison screen** measures:

1. **Naive sequential total** — `measureTimeMillis { runNaiveJetpackStyleStartup(...) }`
2. **Framework total** — `measureTimeMillis { runPhasedStartup(MainActivity, StartupManager(...)) }` (defaults: **`PhasedStartupRunPolicy.EveryTime`**, **`BeforeFirstFrameMode.Full`**, optional **`idleHandlerTimeoutMs`**; **`:app` wires** `CompositeTaskTimingSink` + `defaultInterceptor` as in [§4.3](#43-startupmanager))

The sample graph uses **mixed phases** (`logger` before first frame; `network` / `cache` after frames; `database` on idle) plus **DAG** parallelism where applicable. **Real device** times include **frame and idle** waits, not just task `delay`s.

`MainActivity` uses **`repeatOnLifecycle(Lifecycle.State.CREATED)`** so the benchmark coroutine is **cancelled when the `Activity` is destroyed** (in addition to structured concurrency from the lifecycle). `MainApplication` registers `CoreTaskProvider()`.

---

## 7. Integrating into another Android project

### 7.1 Option A: Include as Gradle modules (recommended for forks and monorepos)

1. Copy the **`core`** folder (and optionally **`runtime`**) or submodule it; **`include(":core")`** in `settings.gradle.kts`.
2. **`implementation(project(":core"))`** (+ **`runtime`** if needed), same Kotlin + **`kotlinx-coroutines-android`** as this repo.
3. Register providers in your **`Application`**; call **`start()`** or **`startPhase`** from a **lifecycle-aware** scope.

### 7.2 Where to start work (and cancellation)

- Prefer **`lifecycleScope.launch { … }`**, **`repeatOnLifecycle { … }`**, or **`viewModelScope`** so work is **cancelled** when the user leaves the screen, matching **`ensureActive()`** in phased helpers.
- For **process-wide** init without a UI, use a **`SupervisorJob` + `applicationScope`**, but be explicit about what must survive configuration changes.

### 7.3 Option B: Publish an AAR

- `maven-publish` for `:core` (and `:runtime`); POM should declare **kotlinx-coroutines-android**.

### 7.4 Testing

- Prefer **`withTestRegistry { … }`** and a local **`StartupTaskRegistry`**, or **`TaskRegistry.clear()`** if you use the global registry. Build **`StartupManager(tasks = registry.collectTasks(), …)`** or **`StartupManager(registry, …)`**. Call **`InMemoryTaskTraceStore.reset()`** / your **`TaskTimingSink.reset()`** (or the **`StartupTracer`** singleton) between runs. Assert on **`FullStartupResult`** / **`PhaseResult`** when you inject failing tasks. Keep tasks small and **mock** SDKs inside `run()`.

### 7.5 Multi-process

- Register **only in the process** that needs the singletons; use `if (isMainProcess) { ... }` when needed.

---

## 8. Relationship to Jetpack / other libraries

| Library / API | Role |
|---------------|------|
| **This framework** | DAG, phases, coroutines, optional IO cap, `PhaseResult` / failure reporting. |
| **AndroidX Startup** | Merge / manifest initializers; can coexist—use this for **explicit** graphs. |
| **WorkManager** | Post-startup, constrained background work. |
| **Hilt** | Inject dependencies **into** `run()`; ordering remains defined by the graph. |
| **Lifecycle (repeatOnLifecycle)** | Binds long startup coroutines to **UI lifetime**; recommended in `ComponentActivity` / Compose. |

---

## 9. Best practices and caveats

1. **Stable, unique `ids`**; dependencies must exist; **phase** order must not reference a “later” phase as a dependency.
2. **Short** main-thread work; heavy work on IO and/or **`Idle`** phase.
3. **No cycles** in the graph.
4. **`collectTasks()`** on **`TaskRegistry`** or a **`StartupTaskRegistry`** creates new instances from `provide()` each time; avoid accidental shared **mutable** state between runs.
5. **`priority`** does not override **edges**; it only reorders the ready queue.
6. **Handle `PhaseResult.failures` and `FullStartupResult.allFailures` in production** (log, crash reporting, or feature flags). **`CancellationException` is rethrown** and should not be treated as a task failure.
7. **Release:** disable or gate **`printDag`**, **`LogcatTaskTimingSink`** (use **`enabled`**), **`SampledTaskTimingSink`** for any analytics delegate, and **`TaskRunInterceptor`** (keep **`None`** or use **`defaultInterceptor(false)`**). Avoid verbose **failure** strings in user-facing paths.
8. **`maxParallelIo`**: set from empirical testing (too low = underused cores; too high = storage or network **contention**).
9. **Main `IdleHandler`:** if the main thread is **continuously** busy, **idle** may not fire for a long time. Use **`idleHandlerTimeoutMs` in `runPhasedStartup`** (or a similar app-level cap); this **does not** guarantee a true “idle” if the timeout wins—document the trade-off for your product.
10. **Critical BFF and `needWait`:** only tasks in **`BeforeFirstFrame`** participate in the **closure**; if nothing matches **`isCritical`**, **`startBeforeFirstFrameUntilCritical`** returns an empty result—call **`startPhase(BeforeFirstFrame, …)`** for the full phase as needed.
11. **Phased start policies:** use **`AtMostOncePerProcess` / `AtMostOncePerActivity`** when a **second** `Activity` or lifecycle entry must not re-run the whole chain; keep **`EveryTime`** for the benchmark / debug.

---

## 10. License

If there is no `LICENSE` file in the root of your fork, add an explicit open-source or proprietary license before distribution.

---

## Further reading

- [App startup time](https://developer.android.com/topic/performance/vitals/launch-time) — Android Developers  
- [Coroutines overview](https://kotlinlang.org/docs/coroutines-overview.html) — Kotlin documentation  
- [Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines) — `repeatOnLifecycle`  
- Kahn (topological sort) — standard references; this project uses in-degree + priority queue in `Dag.kt`.
