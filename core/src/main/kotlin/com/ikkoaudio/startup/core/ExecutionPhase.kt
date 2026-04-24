package com.ikkoaudio.startup.core

/**
 * When a task is scheduled relative to the first UI frame. Dependencies must be on this phase or an **earlier** one.
 *
 * * [BeforeFirstFrame] — e.g. Application / Activity `onCreate` before content draw; for splash / routing critical work.
 * * [AfterFirstFrame] — after one or more [android.view.Choreographer] frame callbacks; avoids competing with initial measure/layout.
 * * [Idle] — when the main [android.os.Looper] queue is idle (or next idle pass); heavy non-blocking init.
 */
enum class ExecutionPhase {
    BeforeFirstFrame,
    AfterFirstFrame,
    Idle,
}
