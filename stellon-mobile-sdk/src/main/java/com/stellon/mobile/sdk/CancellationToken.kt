package com.stellon.mobile.sdk

import java.util.concurrent.atomic.AtomicBoolean

public class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    public fun cancel() {
        cancelled.set(true)
    }

    public fun isCancelled(): Boolean = cancelled.get()
}
