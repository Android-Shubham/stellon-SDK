# StellonMobileSdk

Native Android SDK for running BitNet-style local inference behind a Kotlin API.

For a full layer-by-layer explanation of the project internals, see `ARCHITECTURE.md`.

## Architecture

```
Android app
  -> Public SDK API: BitnetClient / ChatSession
  -> Domain layer: model lifecycle, streaming, cancellation, concurrency
  -> Native bridge: JNI marshaling
  -> Native runtime: C++ inference adapter
  -> Hardware: CPU / ARM NEON capable builds
```

## What is implemented

- Android library module: `:stellon-mobile-sdk`
- Public Kotlin API: `BitnetClient`, `ChatSession`, `ModelManager`
- Streaming token generation through `Flow<TokenChunk>`
- Cancellation through `CancellationToken`
- Single-flight inference enforcement with a coroutine `Mutex`
- Resumable model downloads using HTTP `Range`
- Local model cache listing, disk usage, and deletion
- JNI bridge and C++ runtime compiled for `arm64-v8a`
- Vendored Microsoft BitNet.cpp under `third_party/BitNet`
- Real llama.cpp/BitNet model loading, tokenization, sampling, decode, and detokenization
- Typed SDK exceptions for download, load, and inference failures
- Minimal native Android sample app in `:sample-app`

`arm64-v8a` is the production Android device ABI. `armeabi-v7a` is intentionally excluded because the current BitNet.cpp ARM kernels do not compile cleanly for 32-bit Android with NDK 27. `x86_64` emulator builds are also excluded because BitNet's generated TL1 kernels are ARM NEON-specific.

## Usage

```kotlin
val client = BitnetClient.create(context)
client.loadModel(ModelSource.officialBitnetB1582B4T())

val chat = client.createChatSession(systemPrompt = "You are concise.")
chat.sendStreaming("Explain 1-bit inference.", GenerationParameters(maxTokens = 128))
    .collect { chunk ->
        if (chunk.index >= 0) appendToUi(chunk.text)
    }
```

Local model files are supported:

```kotlin
client.loadModel(ModelSource.LocalFile("local-bitnet", File("/sdcard/Download/model.gguf")))
```

Custom Hugging Face GGUF URLs are supported:

```kotlin
client.loadModel(
    ModelSource.huggingFace(
        repoId = "microsoft/BitNet-b1.58-2B-4T-gguf",
        fileName = "ggml-model-i2_s.gguf",
    )
)
```

## Native Runtime

The native build links `stellon_bitnet` against the vendored BitNet.cpp llama target from:

`third_party/BitNet/3rdparty/llama.cpp`

The SDK-owned native boundary remains:

`stellon-mobile-sdk/src/main/cpp/runtime/bitnet_runtime.h`

Keep that boundary stable when upgrading BitNet. Most SDK behavior should continue to live in Kotlin/JNI while BitNet-specific changes stay inside `bitnet_runtime.cpp` and CMake.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug AAR is produced at:

`stellon-mobile-sdk/build/outputs/aar/stellon-mobile-sdk-debug.aar`
