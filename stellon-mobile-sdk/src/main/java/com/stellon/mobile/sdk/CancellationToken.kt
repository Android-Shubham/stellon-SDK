/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.stellon.mobile.sdk

import java.util.concurrent.atomic.AtomicBoolean

public class CancellationToken {
    private val cancelled = AtomicBoolean(false)

    public fun cancel() {
        cancelled.set(true)
    }

    public fun isCancelled(): Boolean = cancelled.get()
}
