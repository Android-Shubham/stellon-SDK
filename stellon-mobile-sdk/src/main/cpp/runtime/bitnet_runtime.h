#pragma once

#include <functional>
#include <memory>
#include <mutex>
#include <string>

struct StellonBitnetRuntimeState;

class StellonBitnetRuntime {
public:
    using CancellationChecker = std::function<bool()>;
    using TokenCallback = std::function<void(const std::string&, int)>;

    StellonBitnetRuntime();
    ~StellonBitnetRuntime();

    void load_model(const std::string& model_path);
    void generate(
        const std::string& request_json,
        const CancellationChecker& is_cancelled,
        const TokenCallback& on_token
    );

private:
    std::mutex mutex_;
    std::unique_ptr<StellonBitnetRuntimeState> state_;
};
