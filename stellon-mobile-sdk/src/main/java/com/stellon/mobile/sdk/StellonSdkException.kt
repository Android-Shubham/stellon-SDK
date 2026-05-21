/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.stellon.mobile.sdk

public sealed class StellonSdkException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public class ModelLoadFailed(message: String, cause: Throwable? = null) : StellonSdkException(message, cause)
    public class DownloadFailed(message: String, cause: Throwable? = null) : StellonSdkException(message, cause)
    public class NativeInferenceFailed(message: String, cause: Throwable? = null) : StellonSdkException(message, cause)
}
