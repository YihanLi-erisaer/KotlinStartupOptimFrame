# Android Startup Optimization Framework

A Kotlin/Android library for **cold-start and pre–first-frame initialization**. It models startup work as a **directed acyclic graph (DAG)** of tasks, runs **independent branches in parallel** once their dependencies are satisfied (shortening wall-clock time toward the **critical path**), and provides registration, structured coroutine dispatch, optional timing traces, and runtime wrappers (lazy once, timeout, priority override).

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
- The scheduler runs tasks **only after** all predecessors have finished, and **overlaps** work where the graph allows (subject to thread choice via `runOnMainThread`).
- Initialization is **centralized** and **testable**, instead of ad-hoc call chains scattered across `Application` and feature code.

---

## 2. How this framework optimizes startup (principles)

### 2.1 Model startup as a DAG

- Each task has a unique **`id`**.
- Each entry in **`dependencies`** is the `id` of a task that must **complete successfully before** this task may start.
- The global graph must be a **DAG** (no cycles). If a cycle exists, `sortTasks` fails with a clear error.

Intuitively:

- Tasks with **no dependencies** (in-degree zero) can start as soon as the scheduler allows.
- When **several tasks** have all their dependencies satisfied, they may **run concurrently** (on IO or main, per `runOnMainThread`).

The **optimization** is not magic: it **does not** reduce the duration of a single `run()` implementation. It **reduces total wall-clock time** by **overlapping** independent or partially independent work—especially when tasks are I/O-bound and the device has spare cores—so that end-to-end startup time moves toward the **critical path length** of the graph instead of the sum of all node durations.

### 2.2 Topological sort (Kahn’s algorithm + priority queue)

Implementation: `core` module, `sortTasks` in `Dag.kt`.

1. **Validation:** task **ids are unique**; every dependency **must refer to an existing** task id (catches typos early).
2. **Graph:** for each edge `dep → task`, maintain an adjacency list and **in-degrees**.
3. **Ready set:** repeatedly take nodes whose in-degree is **zero**. The ready set is a **`PriorityQueue`** ordered by:
   - Higher **`priority`** first (default `0` on `StartupTask`);
   - Then stable tie-break by **`id`** (lexicographic).

The resulting order is a **valid topological order** and also defines the order in which **coroutine jobs are created** (see below).

### 2.3 Concurrency: `join` dependencies, then run (avoids deadlock)

`StartupManager.start()` runs inside a **`coroutineScope`** and, for **each task in topological order**, creates one **`Job`**:

1. Inside that job, **`join()` every predecessor `Job`** listed in `dependencies` (predecessors must have completed `run()`).
2. Then **`StartupDispatcher`** runs `task.run()` on **`Dispatchers.Main.immediate`** or **`Dispatchers.IO`** (per `runOnMainThread`) and joins that inner work.
3. Finally, **all root jobs** are joined.

**Why launch in topological order?** If a dependent’s coroutine started before its dependency’s job existed, a pattern of “child waits for parent that is not scheduled yet” could **deadlock**. Launching jobs in topological order ensures **predecessor jobs are always registered** before any dependent **suspends** waiting for them.

**Parallelism:** After `logger` finishes, tasks that only depend on `logger` (for example `network` and `cache`) **both** proceed: their jobs were **launched** in priority/topo order, but each **suspends** on `join(logger)` until `logger` completes, then their `run()` calls **overlap in time**. That is where wall-clock savings come from versus a **single sequential** `run()` chain.

### 2.4 “Traditional” baseline in this repo

`app/.../benchmark/NaiveJetpackStyleStartup.kt` simulates a **single coroutine** calling `run()` in a **fixed order** (`logger → network → cache → database`) without exploiting parallelism between `network` and `cache`. `StartupComparisonScreen` shows **two wall-clock totals** side by side for the same simulated work.

### 2.5 Observability

`StartupTracer` records **per-task wall time** around each `run()` (`TaskTrace`). Useful for logging, debug UI, or before/after comparisons. It is **coarse** (not a distributed trace), but sufficient for startup profiling in development.

---

## 3. Module layout

| Module | Role |
|--------|------|
| **`core`** | `StartupTask`, `StartupTaskProvider`, `TaskRegistry`, `sortTasks` / `printDAG`, `StartupDispatcher`, `StartupManager`, `StartupTracer` |
| **`runtime`** | Optional wrappers: `LazyTask`, `TimeoutTask`, `PriorityTask` |
| **`sample`** | Example `Init*` tasks and `CoreTaskProvider` for the demo graph |
| **`app`** | `Application` registration, Compose UI comparing sequential vs framework |

`settings.gradle.kts` includes `:app`, `:core`, `:runtime`, `:sample`.

---

## 4. Core APIs

### 4.1 `StartupTask`

| Member | Meaning |
|--------|--------|
| `id` | Unique string; referenced by other tasks’ `dependencies`. |
| `dependencies` | All listed tasks must finish before this task runs. |
| `runOnMainThread` | If `true`, `run()` executes on the main dispatcher; otherwise on IO. |
| `needWait` | Semantic hint for your app (e.g. splash / routing): “is this part of the critical path?” The core manager still waits for **all** tasks in `start()` unless you split flows yourself. |
| `priority` | Default `0`. Among **ready** tasks (in-degree zero), higher values are **scheduled earlier** (tie-break for topological processing). |
| `suspend fun run()` | Actual initialization; prefer **suspend** I/O over blocking calls, especially on the main dispatcher. |

### 4.2 `StartupTaskProvider` and `TaskRegistry`

- Implement `StartupTaskProvider` with `fun provide(): List<StartupTask>` per feature or layer.
- Call `TaskRegistry.register(MyProvider())` from a central place (often `Application.onCreate`).
- `TaskRegistry.collectTasks()` **flattens** all registered providers (`flatMap`).
- `TaskRegistry.clear()` helps tests or process-scoped resets.

### 4.3 `StartupManager`

```kotlin
val manager = StartupManager(tasks = TaskRegistry.collectTasks())
manager.start(printDag = true) { line -> Log.d("Startup", line) }
```

- Clears the tracer, validates and sorts the DAG, optionally prints the graph, runs the graph, prints traces (default `println`).

### 4.4 `runtime` module (optional)

| Class | Behavior |
|-------|----------|
| `LazyTask(delegate)` | Ensures `delegate.run()` completes at most once (mutex-guarded); useful for idempotent “init once” semantics. |
| `TimeoutTask(delegate, timeoutMs)` | Wraps `run()` with `withTimeout`. |
| `PriorityTask(delegate, priority)` | Overrides `priority` without changing the delegate class. |

---

## 5. Using the framework in code

### 5.1 Define a task

```kotlin
class InitNetworkTask : StartupTask {
    override val id = "network"
    override val dependencies = listOf("logger")
    override val runOnMainThread = false
    override val needWait = false

    override suspend fun run() {
        // Init OkHttp, certificate pinning, etc.
    }
}
```

### 5.2 Provide tasks from a module

```kotlin
class NetworkStartupProvider : StartupTaskProvider {
    override fun provide() = listOf(InitNetworkTask())
}
```

### 5.3 Register and run

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskRegistry.register(NetworkStartupProvider())
        TaskRegistry.register(DataStartupProvider())
        // Often: do not block onCreate; see section 7 for where to call start().
    }
}
```

```kotlin
// From a coroutine scope (e.g. Activity, ViewModel, or your own applicationScope)
suspend fun runStartup() {
    val manager = StartupManager(TaskRegistry.collectTasks())
    manager.start(printDag = BuildConfig.DEBUG)
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
2. **Framework total** — `measureTimeMillis { StartupManager(...).start(printDag = false) }`

The sample graph is **`logger → (network ∥ cache) → database`**, with simulated `delay` values so the sequential path is longer than the parallel critical path. **Real device** numbers include scheduler noise; the **design** of the graph is what makes the gap appear.

`MainApplication` registers `CoreTaskProvider()`; `MainActivity` runs both measurements in `LaunchedEffect` and displays the result.

---

## 7. Integrating into another Android project

### 7.1 Option A: Include as Gradle modules (recommended for forks and monorepos)

1. Copy the **`core`** folder (and optionally **`runtime`**) into your multi-module project, or add this repository as a **Git submodule** and `include` the subprojects.
2. In your **`settings.gradle.kts`**:

   ```kotlin
   include(":core")
   include(":runtime") // optional
   ```

3. In your **`app`** or feature module:

   ```kotlin
   implementation(project(":core"))
   implementation(project(":runtime")) // optional
   ```

4. Ensure **`core`** is an **Android Library** with **Kotlin** and **`kotlinx-coroutines-android`** (align versions with your `libs.versions.toml`).

5. Subclass **`Application`**, set `android:name` in the manifest, and in `onCreate()` **register** your providers:

   ```kotlin
   TaskRegistry.clear()
   TaskRegistry.register(FeatureAStartupProvider())
   TaskRegistry.register(FeatureBStartupProvider())
   ```

6. **Where to call `start()`** (choose based on product requirements):

   - **Non-blocking `Application`:** only register providers in `onCreate()`; call `start()` from the first **`Activity`** / **`ViewModel`** / **`ProcessLifecycleOwner`**-driven scope (this repo’s demo uses `LaunchedEffect` in `MainActivity`).
   - **Splash / blocking flow:** call `start()` from a **Splash** `Activity` coroutine and gate navigation until tasks you mark with `needWait` (and your own rules) complete.
   - Use a **dedicated `CoroutineScope`**: e.g. `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` and launch `start()` there if you must kick off work early without blocking `onCreate()`.

7. **Multi-process:** register and run startup only in the **main process** (or the process that needs those initializations), e.g. `if (isMainProcess) { ... }`, to avoid duplicate singleton side effects.

### 7.2 Option B: Publish an AAR

- Configure **`maven-publish`** (or your internal pipeline) for `:core` (and `:runtime` if needed), with a POM that declares **`org.jetbrains.kotlinx:kotlinx-coroutines-android`**.
- Consumer apps add a normal **`implementation`** dependency on the published coordinates.

### 7.3 Testing

- Keep tasks **small and deterministic** in unit tests; **mock** heavy SDKs inside `run()`.
- Call `TaskRegistry.clear()` between tests if you register providers globally.

---

## 8. Relationship to Jetpack / other libraries

| Library / API | Role |
|---------------|------|
| **This framework** | In-process **startup** DAG, **coroutine** scheduling, optional tracing. |
| **AndroidX Startup** (`androidx.startup`) | Manifest-driven initializers, merged ordering; less about explicit parallel DAGs in Kotlin. You can use **both**: e.g. simple `Initializer`s for defaults, this library for a **complex graph** you own in code. |
| **WorkManager** | **Deferred**, **constraint-based**, **persistent** background work—not a substitute for cold-start ordering. Use it **after** startup for non–launch-critical jobs. |
| **Hilt / KSP** | **Dependency injection** for objects your `StartupTask.run()` needs; does not replace explicit **ordering** of side-effectful init. |

---

## 9. Best practices and caveats

1. **Stable, unique `ids`**; typos in `dependencies` are rejected if the target id does not exist.
2. **Avoid long blocking** work on the main dispatcher; keep `runOnMainThread` tasks short.
3. **Cycles** are invalid; fix the graph during development (stack trace from `sortTasks`).
4. **`TaskRegistry.collectTasks()`** invokes each provider’s `provide()`; if you **allocate new task instances** every time, do not reuse **stateful** instances across unrelated runs unless that is intentional.
5. **`priority`** only affects **ready-queue order** and **job creation order**; it **never** overrides dependency edges.
6. **Production:** disable verbose `printDag` / `StartupTracer.print` in release, or route traces to your logging pipeline only in debug builds.

---

## Further reading

- [App startup time](https://developer.android.com/topic/performance/vitals/launch-time) — Android Developers  
- [Coroutines overview](https://kotlinlang.org/docs/coroutines-overview.html) — Kotlin documentation  
- Topological sorting (Kahn’s algorithm) — standard algorithms references; aligns with the in-degree + queue structure in `sortTasks`.
