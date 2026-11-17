package com.pixelisland.timer

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TimerMode { STOPWATCH, TIMER }

data class TimerState(
    val mode: TimerMode = TimerMode.STOPWATCH,
    val running: Boolean = false,
    val accumulatedMs: Long = 0L,
    val startedAt: Long = 0L,
    val timerTotalMs: Long = 5 * 60_000L,
    val finished: Boolean = false,
    val finishedAt: Long = 0L,
) {
    val isActive: Boolean get() = running || accumulatedMs > 0 || finished

    fun elapsedAt(now: Long): Long = accumulatedMs + if (running) now - startedAt else 0L

    /** Stopwatch counts up, timer counts down. */
    fun displayMsAt(now: Long): Long = when (mode) {
        TimerMode.STOPWATCH -> elapsedAt(now)
        TimerMode.TIMER -> (timerTotalMs - elapsedAt(now)).coerceAtLeast(0L)
    }
}

object TimerRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()
    private var finishJob: Job? = null

    private fun now() = SystemClock.elapsedRealtime()

    fun toggle() {
        val s = _state.value
        if (s.finished) {
            reset()
            return
        }
        val now = now()
        _state.value = if (s.running) {
            s.copy(running = false, accumulatedMs = s.elapsedAt(now), startedAt = 0L)
        } else {
            s.copy(running = true, startedAt = now)
        }
        scheduleFinish()
    }

    fun reset() {
        finishJob?.cancel()
        val s = _state.value
        _state.value = TimerState(mode = s.mode, timerTotalMs = s.timerTotalMs)
    }

    fun setMode(mode: TimerMode) {
        if (mode == _state.value.mode) return
        finishJob?.cancel()
        _state.value = TimerState(mode = mode, timerTotalMs = _state.value.timerTotalMs)
    }

    fun addMinute() {
        _state.update { it.copy(timerTotalMs = it.timerTotalMs + 60_000L, finished = false) }
        scheduleFinish()
    }

    private fun scheduleFinish() {
        finishJob?.cancel()
        val s = _state.value
        if (s.mode != TimerMode.TIMER || !s.running) return
        val remaining = (s.timerTotalMs - s.elapsedAt(now())).coerceAtLeast(0L)
        finishJob = scope.launch {
            delay(remaining)
            _state.update {
                if (it.running && it.mode == TimerMode.TIMER) {
                    it.copy(
                        running = false,
                        accumulatedMs = it.timerTotalMs,
                        startedAt = 0L,
                        finished = true,
                        finishedAt = now(),
                    )
                } else it
            }
        }
    }
}
