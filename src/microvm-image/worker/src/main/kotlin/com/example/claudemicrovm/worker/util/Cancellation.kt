package com.example.claudemicrovm.worker.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Run [block]; if the calling coroutine is cancelled before [block] finishes,
 * invoke [onCancel] — typically to close a stream or kill a process so that
 * blocking I/O inside [block] (which coroutine cancellation cannot interrupt)
 * fails fast. Blocking calls inside [block] must run on another dispatcher
 * (e.g. `withContext(Dispatchers.IO)`) so the cancel hook can be dispatched.
 */
suspend fun <T> withCancellationHook(onCancel: () -> Unit, block: suspend CoroutineScope.() -> T): T =
    coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) onCancel()
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
