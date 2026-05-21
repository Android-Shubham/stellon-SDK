/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

#include "bitnet_runtime.h"

#include "llama.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

struct StellonBitnetRuntimeState {
    llama_model* model = nullptr;
    std::string model_path;
};

namespace {

struct GenerationRequest {
    std::string prompt;
    int max_tokens = 128;
    float temperature = 0.7f;
    int top_k = 40;
    float top_p = 0.95f;
    float repetition_penalty = 1.1f;
    std::vector<std::string> stop_sequences;
};

bool backend_initialized = false;
std::mutex backend_mutex;

void ensure_backend_initialized() {
    std::lock_guard<std::mutex> lock(backend_mutex);
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }
}

bool file_readable(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    return file.good();
}

std::string unescape_json_string(const std::string& value) {
    std::string output;
    output.reserve(value.size());
    bool escaped = false;
    for (char ch : value) {
        if (!escaped) {
            if (ch == '\\') {
                escaped = true;
            } else {
                output.push_back(ch);
            }
            continue;
        }

        switch (ch) {
            case '"': output.push_back('"'); break;
            case '\\': output.push_back('\\'); break;
            case '/': output.push_back('/'); break;
            case 'b': output.push_back('\b'); break;
            case 'f': output.push_back('\f'); break;
            case 'n': output.push_back('\n'); break;
            case 'r': output.push_back('\r'); break;
            case 't': output.push_back('\t'); break;
            default: output.push_back(ch); break;
        }
        escaped = false;
    }
    return output;
}

std::string read_string_field(const std::string& json, const std::string& field, const std::string& fallback = {}) {
    const std::string key = "\"" + field + "\"";
    const auto key_pos = json.find(key);
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos + key.size());
    if (colon == std::string::npos) return fallback;
    const auto quote = json.find('"', colon + 1);
    if (quote == std::string::npos) return fallback;

    std::string raw;
    bool escaped = false;
    for (auto i = quote + 1; i < json.size(); ++i) {
        const char ch = json[i];
        if (!escaped && ch == '"') break;
        raw.push_back(ch);
        escaped = !escaped && ch == '\\';
        if (ch != '\\') escaped = false;
    }
    return unescape_json_string(raw);
}

int read_int_field(const std::string& json, const std::string& field, int fallback) {
    const std::string key = "\"" + field + "\"";
    const auto key_pos = json.find(key);
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos + key.size());
    if (colon == std::string::npos) return fallback;
    const auto start = json.find_first_of("-0123456789", colon + 1);
    if (start == std::string::npos) return fallback;
    const auto end = json.find_first_not_of("0123456789", start + 1);
    return std::stoi(json.substr(start, end - start));
}

float read_float_field(const std::string& json, const std::string& field, float fallback) {
    const std::string key = "\"" + field + "\"";
    const auto key_pos = json.find(key);
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos + key.size());
    if (colon == std::string::npos) return fallback;
    const auto start = json.find_first_of("-0123456789.", colon + 1);
    if (start == std::string::npos) return fallback;
    const auto end = json.find_first_not_of("0123456789.eE+-", start + 1);
    return std::stof(json.substr(start, end - start));
}

std::vector<std::string> read_string_array_field(const std::string& json, const std::string& field) {
    std::vector<std::string> result;
    const std::string key = "\"" + field + "\"";
    const auto key_pos = json.find(key);
    if (key_pos == std::string::npos) return result;
    const auto colon = json.find(':', key_pos + key.size());
    const auto open = json.find('[', colon);
    if (colon == std::string::npos || open == std::string::npos) return result;

    for (auto i = open + 1; i < json.size();) {
        while (i < json.size() && std::isspace(static_cast<unsigned char>(json[i]))) ++i;
        if (i >= json.size() || json[i] == ']') break;
        if (json[i] != '"') {
            ++i;
            continue;
        }
        const auto start = i++;
        bool escaped = false;
        std::string raw;
        for (; i < json.size(); ++i) {
            const char ch = json[i];
            if (!escaped && ch == '"') break;
            raw.push_back(ch);
            escaped = !escaped && ch == '\\';
            if (ch != '\\') escaped = false;
        }
        if (i > start) result.push_back(unescape_json_string(raw));
        ++i;
    }
    return result;
}

GenerationRequest parse_request(const std::string& request_json) {
    GenerationRequest request;
    request.prompt = read_string_field(request_json, "prompt");
    request.max_tokens = std::max(1, read_int_field(request_json, "maxTokens", request.max_tokens));
    request.temperature = std::max(0.0f, read_float_field(request_json, "temperature", request.temperature));
    request.top_k = std::max(0, read_int_field(request_json, "topK", request.top_k));
    request.top_p = std::clamp(read_float_field(request_json, "topP", request.top_p), 0.0f, 1.0f);
    request.repetition_penalty = std::max(0.01f, read_float_field(request_json, "repetitionPenalty", request.repetition_penalty));
    request.stop_sequences = read_string_array_field(request_json, "stopSequences");
    request.stop_sequences.erase(
        std::remove_if(request.stop_sequences.begin(), request.stop_sequences.end(), [](const std::string& value) {
            return value.empty();
        }),
        request.stop_sequences.end()
    );
    return request;
}

std::vector<llama_token> tokenize(llama_model* model, const std::string& prompt) {
    int count = llama_tokenize(model, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (count < 0) {
        count = -count;
    }
    if (count <= 0) {
        throw std::runtime_error("prompt tokenization produced no tokens");
    }

    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int actual = llama_tokenize(
        model,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (actual < 0) {
        throw std::runtime_error("failed to tokenize prompt");
    }
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

std::string token_to_piece(llama_model* model, llama_token token) {
    std::vector<char> buffer(256);
    int size = llama_token_to_piece(model, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (size < 0) {
        buffer.resize(static_cast<size_t>(-size));
        size = llama_token_to_piece(model, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (size < 0) {
        throw std::runtime_error("failed to convert token to text");
    }
    return std::string(buffer.data(), static_cast<size_t>(size));
}

llama_sampler* create_sampler(llama_model* model, const GenerationRequest& request) {
    auto chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler* sampler = llama_sampler_chain_init(chain_params);

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_penalties(
            llama_n_vocab(model),
            llama_token_eos(model),
            llama_token_nl(model),
            64,
            request.repetition_penalty,
            0.0f,
            0.0f,
            true,
            false
        )
    );

    if (request.temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (request.top_k > 0) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(request.top_k));
        }
        if (request.top_p < 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(request.top_p, 1));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(request.temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    return sampler;
}

bool find_stop_sequence(const std::string& text, const std::vector<std::string>& stops, size_t* position) {
    for (const auto& stop : stops) {
        const auto found = text.find(stop);
        if (found != std::string::npos) {
            *position = found;
            return true;
        }
    }
    return false;
}

size_t max_stop_sequence_size(const std::vector<std::string>& stops) {
    size_t max_size = 0;
    for (const auto& stop : stops) {
        max_size = std::max(max_size, stop.size());
    }
    return max_size;
}

bool flush_streaming_text(
    std::string& pending,
    std::string& generated,
    const std::vector<std::string>& stops,
    size_t max_stop_size,
    const StellonBitnetRuntime::TokenCallback& on_token,
    int* index
) {
    size_t stop_position = 0;
    if (find_stop_sequence(generated + pending, stops, &stop_position)) {
        const std::string combined = generated + pending;
        if (stop_position > generated.size()) {
            const std::string safe = combined.substr(generated.size(), stop_position - generated.size());
            if (!safe.empty()) {
                on_token(safe, (*index)++);
            }
        }
        return false;
    }

    const size_t keep = stops.empty() ? 0 : std::max<size_t>(1, max_stop_size) - 1;
    if (pending.size() > keep) {
        const size_t emit_size = pending.size() - keep;
        const std::string safe = pending.substr(0, emit_size);
        pending.erase(0, emit_size);
        generated += safe;
        on_token(safe, (*index)++);
    }
    return true;
}

void flush_remaining(
    std::string& pending,
    std::string& generated,
    const StellonBitnetRuntime::TokenCallback& on_token,
    int* index
) {
    if (!pending.empty()) {
        generated += pending;
        on_token(pending, (*index)++);
        pending.clear();
    }
}

}  // namespace

StellonBitnetRuntime::StellonBitnetRuntime() : state_(std::make_unique<StellonBitnetRuntimeState>()) {
    ensure_backend_initialized();
}

StellonBitnetRuntime::~StellonBitnetRuntime() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_->model != nullptr) {
        llama_free_model(state_->model);
        state_->model = nullptr;
    }
}

void StellonBitnetRuntime::load_model(const std::string& model_path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (model_path.empty()) {
        throw std::runtime_error("model path is empty");
    }
    if (!file_readable(model_path)) {
        throw std::runtime_error("model file is not readable");
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    model_params.use_mlock = false;
    model_params.check_tensors = true;

    llama_model* loaded_model = llama_load_model_from_file(model_path.c_str(), model_params);
    if (loaded_model == nullptr) {
        throw std::runtime_error("llama_load_model_from_file returned null");
    }

    if (state_->model != nullptr) {
        llama_free_model(state_->model);
    }
    state_->model = loaded_model;
    state_->model_path = model_path;
}

void StellonBitnetRuntime::generate(
    const std::string& request_json,
    const CancellationChecker& is_cancelled,
    const TokenCallback& on_token
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_->model == nullptr) {
        throw std::runtime_error("model is not loaded");
    }

    const GenerationRequest request = parse_request(request_json);
    const auto prompt_tokens = tokenize(state_->model, request.prompt);

    llama_context_params context_params = llama_context_default_params();
    const uint32_t requested_ctx = static_cast<uint32_t>(prompt_tokens.size() + request.max_tokens + 8);
    context_params.n_ctx = std::max<uint32_t>(512, requested_ctx);
    context_params.n_batch = std::max<uint32_t>(1, static_cast<uint32_t>(prompt_tokens.size()));
    context_params.n_ubatch = std::min<uint32_t>(context_params.n_batch, 512);
    context_params.n_threads = std::max(1u, std::thread::hardware_concurrency());
    context_params.n_threads_batch = context_params.n_threads;
    context_params.no_perf = true;

    llama_context* context = llama_new_context_with_model(state_->model, context_params);
    if (context == nullptr) {
        throw std::runtime_error("failed to create llama context");
    }

    llama_sampler* sampler = create_sampler(state_->model, request);
    llama_batch batch = llama_batch_get_one(
        const_cast<llama_token*>(prompt_tokens.data()),
        static_cast<int32_t>(prompt_tokens.size()),
        0,
        0
    );

    std::string generated;
    std::string pending;
    const size_t max_stop_size = max_stop_sequence_size(request.stop_sequences);
    int callback_index = 0;

    try {
        int n_pos = 0;
        int decoded = 0;

        while (!is_cancelled() && decoded < request.max_tokens) {
            const int decode_status = llama_decode(context, batch);
            if (decode_status != 0) {
                throw std::runtime_error("llama_decode failed with status " + std::to_string(decode_status));
            }

            n_pos += batch.n_tokens;
            llama_token token = llama_sampler_sample(sampler, context, -1);
            llama_sampler_accept(sampler, token);

            if (llama_token_is_eog(state_->model, token)) {
                break;
            }

            pending += token_to_piece(state_->model, token);
            if (!flush_streaming_text(pending, generated, request.stop_sequences, max_stop_size, on_token, &callback_index)) {
                break;
            }

            batch = llama_batch_get_one(&token, 1, n_pos, 0);
            decoded += 1;
        }

        if (!is_cancelled()) {
            flush_remaining(pending, generated, on_token, &callback_index);
        }
    } catch (...) {
        llama_sampler_free(sampler);
        llama_free(context);
        throw;
    }

    llama_sampler_free(sampler);
    llama_free(context);
}
