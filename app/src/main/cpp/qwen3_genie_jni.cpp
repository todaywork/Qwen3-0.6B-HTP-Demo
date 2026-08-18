#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <set>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

#include "Genie/GenieCommon.h"
#include "Genie/GenieDialog.h"
#include "Genie/GenieLog.h"
#include "Genie/GenieProfile.h"

#define LOG_TAG "Qwen3GenieJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in.good()) {
        throw std::runtime_error("Cannot read file: " + path);
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

std::string toLower(std::string value) {
    for (char& c : value) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return value;
}

bool hasAny(const std::string& value, const std::vector<std::string>& terms) {
    for (const std::string& term : terms) {
        if (value.find(term) != std::string::npos) {
            return true;
        }
    }
    return false;
}

std::string collectRuntimeEvidence() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.good()) {
        return "HTP evidence: UNKNOWN\nCannot read /proc/self/maps.";
    }

    const std::vector<std::string> interestingTerms = {
        "libgenie", "libqwen3genie", "qnn", "htp", "dsp", "rpc",
        "adsp", "cdsp", "fastrpc", "hexagon", "skel"
    };
    const std::vector<std::string> htpTerms = {
        "qnnhtp", "htp", "hexagon", "skel", "cdsp", "adsprpc", "fastrpc"
    };

    std::set<std::string> libs;
    std::string line;
    bool hasGenie = false;
    bool hasGenAiTransformer = false;
    bool hasQnnHtp = false;
    bool hasHtpEvidence = false;

    while (std::getline(maps, line)) {
        const std::string lower = toLower(line);
        if (!hasAny(lower, interestingTerms)) {
            continue;
        }

        std::string path = line;
        const std::size_t slash = line.find('/');
        if (slash != std::string::npos) {
            path = line.substr(slash);
        }
        const std::size_t lastSlash = path.find_last_of('/');
        if (lastSlash != std::string::npos && lastSlash + 1 < path.size()) {
            path = path.substr(lastSlash + 1);
        }

        libs.insert(path);
        hasGenie = hasGenie || lower.find("libgenie") != std::string::npos;
        hasGenAiTransformer = hasGenAiTransformer ||
            lower.find("qnngenaitransformer") != std::string::npos;
        hasQnnHtp = hasQnnHtp || lower.find("libqnnhtp") != std::string::npos;
        hasHtpEvidence = hasHtpEvidence || hasAny(lower, htpTerms);
    }

    std::ostringstream out;
    out << "HTP evidence: " << (hasHtpEvidence ? "DETECTED" : "NOT DETECTED") << "\n";
    out << "HTP/DSP/RPC terms in loaded maps: " << (hasHtpEvidence ? "yes" : "no") << "\n";
    out << "Matched libraries:";

    int count = 0;
    for (const std::string& lib : libs) {
        if (count >= 8) {
            out << "\n  ...";
            break;
        }
        out << "\n  " << lib;
        ++count;
    }
    if (libs.empty()) {
        out << "\n  none";
    }

    return out.str();
}

const char* genieLogLevelName(GenieLog_Level_t level) {
    switch (level) {
        case GENIE_LOG_LEVEL_ERROR:
            return "ERROR";
        case GENIE_LOG_LEVEL_WARN:
            return "WARN";
        case GENIE_LOG_LEVEL_INFO:
            return "INFO";
        case GENIE_LOG_LEVEL_VERBOSE:
            return "VERBOSE";
        default:
            return "UNKNOWN";
    }
}

int genieLogPriority(GenieLog_Level_t level) {
    switch (level) {
        case GENIE_LOG_LEVEL_ERROR:
            return ANDROID_LOG_ERROR;
        case GENIE_LOG_LEVEL_WARN:
            return ANDROID_LOG_WARN;
        case GENIE_LOG_LEVEL_INFO:
            return ANDROID_LOG_INFO;
        case GENIE_LOG_LEVEL_VERBOSE:
            return ANDROID_LOG_VERBOSE;
        default:
            return ANDROID_LOG_DEBUG;
    }
}

void genieLogCallback(const GenieLog_Handle_t,
                      const char* fmt,
                      GenieLog_Level_t level,
                      uint64_t timestamp,
                      va_list args) {
    char message[2048];
    va_list copy;
    va_copy(copy, args);
    std::vsnprintf(message, sizeof(message), fmt ? fmt : "", copy);
    va_end(copy);
    __android_log_print(genieLogPriority(level),
                        "QNN_GENIE_LOG",
                        "[%s][%llu] %s",
                        genieLogLevelName(level),
                        static_cast<unsigned long long>(timestamp),
                        message);
}

std::string formatTokenIds(const int32_t* tokenIds, uint32_t count) {
    std::ostringstream ss;
    ss << "[";
    for (uint32_t i = 0; tokenIds && i < count; ++i) {
        if (i > 0) ss << ",";
        ss << tokenIds[i];
    }
    ss << "]";
    return ss.str();
}

void logTokenIds(const char* tag, const int32_t* tokenIds, uint32_t count) {
    const std::string ids = formatTokenIds(tokenIds, count);
    if (!tokenIds || count == 0) {
        LOGI("%s tokenIds=%s count=0", tag, ids.c_str());
        return;
    }
    // Truncate log if too long to avoid logcat truncation
    if (ids.size() > 4000) {
        LOGI("%s tokenCount=%u, tokenIds(truncated, first 200)=%s...",
             tag, count, ids.substr(0, 4000).c_str());
    } else {
        LOGI("%s tokenCount=%u, tokenIds=%s", tag, count, ids.c_str());
    }
}

void requireFile(const std::string& path) {
    LOGI("Checking required file: %s", path.c_str());

    struct stat fileStat {};
    if (stat(path.c_str(), &fileStat) != 0) {
        const int error = errno;
        throw std::runtime_error("Required file stat failed: " + path +
                                 ", errno=" + std::to_string(error) +
                                 " (" + std::strerror(error) + ")");
    }
    if (!S_ISREG(fileStat.st_mode)) {
        throw std::runtime_error("Required path is not a regular file: " + path);
    }
    if (access(path.c_str(), R_OK) != 0) {
        const int error = errno;
        throw std::runtime_error("Required file is not readable: " + path +
                                 ", errno=" + std::to_string(error) +
                                 " (" + std::strerror(error) + ")");
    }

    std::ifstream in(path, std::ios::binary);
    if (!in.good()) {
        const int error = errno;
        throw std::runtime_error("Required file open failed: " + path +
                                 ", errno=" + std::to_string(error) +
                                 " (" + std::strerror(error) + ")");
    }
    LOGI("Required file readable: %s, size=%lld",
         path.c_str(),
         static_cast<long long>(fileStat.st_size));
}

void setEnv(const char* name, const std::string& value) {
    LOGI("Setting %s=%s", name, value.c_str());
    setenv(name, value.c_str(), 1);
}

void configureRuntimePaths(const std::string& modelRoot) {
    const std::string dspPath = "/vendor/lib/rfsa/adsp;" + modelRoot + ";" +
        modelRoot + "/dsp;" + modelRoot + "/lib";
    setEnv("ADSP_LIBRARY_PATH", dspPath);
    setEnv("CDSP_LIBRARY_PATH", dspPath);
    setEnv("CDSP1_LIBRARY_PATH", dspPath);
}

std::string buildConfig(const std::string& modelRoot,
                        int contextSize,
                        int maxOutputTokens,
                        int threadCount,
                        bool greedy,
                        int topK,
                        float topP,
                        float temperature,
                        float presencePenalty) {
    const std::string tokenizer = modelRoot + "/tokenizer.json";
    const std::string htpConfig = modelRoot + "/htp_backend_ext_config.json";
    const std::string ctxBin1 = modelRoot + "/part1_of_2.bin";
    const std::string ctxBin2 = modelRoot + "/part2_of_2.bin";
    const std::string htpSkel = modelRoot + "/dsp/libQnnHtpV73Skel.so";

    requireFile(tokenizer);
    requireFile(htpConfig);
    requireFile(ctxBin1);
    requireFile(ctxBin2);
    requireFile(htpSkel);

    LOGI("QNN_BACKEND_PROOF backend=QnnHtp extensions=%s ctxBins=[%s,%s]",
         htpConfig.c_str(),
         ctxBin1.c_str(),
         ctxBin2.c_str());

    std::ostringstream json;
    json
        << "{"
        << "\"dialog\":{"
        << "\"version\":1,"
        << "\"type\":\"basic\","
        << "\"stop-sequence\":[\"<|im_end|>\"],"
        << "\"max-num-tokens\":" << maxOutputTokens << ","
        << "\"context\":{\"version\":1,\"size\":" << contextSize
        << ",\"n-vocab\":151936,"
        << "\"bos-token\":151643,\"eos-token\":151645},"
        << "\"sampler\":{\"version\":1,\"seed\":42,\"temp\":" << temperature
        << ",\"top-k\":" << topK << ",\"top-p\":" << topP
        << ",\"greedy\":" << (greedy ? "true" : "false")
        << ",\"token-penalty\":{\"version\":1,\"penalize-last-n\":128,"
        << "\"repetition-penalty\":1.0,\"presence-penalty\":" << presencePenalty
        << ",\"frequency-penalty\":0.0}},"
        << "\"tokenizer\":{\"version\":1,\"path\":\"" << tokenizer << "\"},"
        << "\"engine\":{"
        << "\"version\":1,"
        << "\"n-threads\":" << threadCount << ","
        << "\"backend\":{\"version\":1,\"type\":\"QnnHtp\","
        << "\"QnnHtp\":{\"version\":1,\"use-mmap\":true,\"spill-fill-bufsize\":0,"
        << "\"mmap-budget\":0,\"poll\":false,\"cpu-mask\":\"0xe0\",\"kv-dim\":128,"
        << "\"allow-async-init\":false,\"pos-id-dim\":64,\"rope-theta\":1000000},"
        << "\"extensions\":\"" << htpConfig << "\"},"
        << "\"model\":{\"version\":1,\"type\":\"binary\","
        << "\"binary\":{\"version\":1,\"ctx-bins\":[\"" << ctxBin1 << "\",\""
        << ctxBin2 << "\"]}}"
        << "}"
        << "}"
        << "}";
    return json.str();
}

std::string statusMessage(const std::string& operation, Genie_Status_t status) {
    std::ostringstream ss;
    ss << operation << " failed, status=" << status;
    return ss.str();
}

struct QueryState {
    std::string text;
    int generatedTokenCount = 0;
};

void profileAllocCallback(const size_t size, const char** allocatedData) {
    *allocatedData = static_cast<const char*>(std::malloc(size));
}

const char* sentenceCodeName(GenieDialog_SentenceCode_t code) {
    switch (code) {
        case GENIE_DIALOG_SENTENCE_COMPLETE: return "COMPLETE";
        case GENIE_DIALOG_SENTENCE_BEGIN: return "BEGIN";
        case GENIE_DIALOG_SENTENCE_CONTINUE: return "CONTINUE";
        case GENIE_DIALOG_SENTENCE_END: return "END";
        case GENIE_DIALOG_SENTENCE_ABORT: return "ABORT";
        case GENIE_DIALOG_SENTENCE_REWIND: return "REWIND";
        case GENIE_DIALOG_SENTENCE_RESUME: return "RESUME";
        default: return "UNKNOWN";
    }
}

void queryCallback(const char* response,
                   const GenieDialog_SentenceCode_t sentenceCode,
                   const void* userData) {
    auto* state = reinterpret_cast<QueryState*>(const_cast<void*>(userData));
    if (!state || !response) {
        LOGI("queryCallback: code=%s, response=null", sentenceCodeName(sentenceCode));
        return;
    }

    if (sentenceCode == GENIE_DIALOG_SENTENCE_BEGIN ||
        sentenceCode == GENIE_DIALOG_SENTENCE_CONTINUE ||
        sentenceCode == GENIE_DIALOG_SENTENCE_END ||
        sentenceCode == GENIE_DIALOG_SENTENCE_COMPLETE) {
        LOGI("queryCallback: code=%s, responseLength=%zu, response=[%s]",
             sentenceCodeName(sentenceCode), strlen(response), response);
        state->text += response;
        state->generatedTokenCount++;
    } else {
        LOGI("queryCallback: non-append code=%s, response=[%s]",
             sentenceCodeName(sentenceCode), response);
    }
}

class GenieSession {
public:
    explicit GenieSession(const std::string& modelRoot,
                          int contextSize,
                          int maxTokens,
                          int maxOutputTokens,
                          int threadCount,
                          bool greedy,
                          int topK,
                          float topP,
                          float temperature,
                          float presencePenalty) : maxAllTokens_(maxTokens),
                                                   maxOutputTokens_(maxOutputTokens) {
        if (contextSize <= 0) {
            throw std::invalid_argument("contextSize must be greater than zero");
        }
        if (maxTokens <= 0 || maxTokens > contextSize) {
            throw std::invalid_argument(
                "maxTokens must be in range [1, contextSize]");
        }
        if (maxOutputTokens <= 0 || maxOutputTokens > maxTokens) {
            throw std::invalid_argument(
                "maxOutputTokens must be in range [1, maxTokens]");
        }
        LOGI("GenieSession create start, modelRoot=%s, contextSize=%d, maxTokens=%d, "
             "maxOutputTokens=%d, threadCount=%d, "
             "greedy=%d, topK=%d, topP=%.3f, temperature=%.3f, presencePenalty=%.3f",
             modelRoot.c_str(), contextSize, maxTokens, maxOutputTokens, threadCount,
             greedy ? 1 : 0, topK, topP, temperature, presencePenalty);
        configureRuntimePaths(modelRoot);
        std::string configJson = buildConfig(modelRoot, contextSize, maxOutputTokens, threadCount,
                                             greedy, topK, topP, temperature,
                                             presencePenalty);

        Genie_Status_t status = GenieProfile_create(nullptr, &profile_);
        if (status != GENIE_STATUS_SUCCESS || !profile_) {
            throw std::runtime_error(statusMessage("GenieProfile_create", status));
        }

        LOGI("GenieDialogConfig_createFromJson start, configLength=%zu", configJson.size());
        status = GenieDialogConfig_createFromJson(configJson.c_str(), &config_);
        if (status != GENIE_STATUS_SUCCESS || !config_) {
            throw std::runtime_error(statusMessage("GenieDialogConfig_createFromJson", status));
        }
        LOGI("GenieDialogConfig_createFromJson success");

        status = GenieLog_create(nullptr, genieLogCallback, GENIE_LOG_LEVEL_VERBOSE, &log_);
        if (status != GENIE_STATUS_SUCCESS || !log_) {
            LOGE("GenieLog_create failed, status=%d", status);
        } else {
            LOGI("GenieLog_create success, level=VERBOSE");
            status = GenieDialogConfig_bindLogger(config_, log_);
            if (status != GENIE_STATUS_SUCCESS) {
                LOGE("GenieDialogConfig_bindLogger failed, status=%d", status);
            } else {
                LOGI("GenieDialogConfig_bindLogger success");
            }
        }

        status = GenieDialogConfig_bindProfiler(config_, profile_);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieDialogConfig_bindProfiler", status));
        }
        LOGI("GenieDialogConfig_bindProfiler success");

        LOGI("GenieDialog_create start");
        status = GenieDialog_create(config_, &dialog_);
        if (status != GENIE_STATUS_SUCCESS || !dialog_) {
            throw std::runtime_error(statusMessage("GenieDialog_create", status));
        }
        LOGI("GenieDialog_create success");
    }

    ~GenieSession() {
        if (dialog_) {
            Genie_Status_t status = GenieDialog_free(dialog_);
            if (status != GENIE_STATUS_SUCCESS) {
                LOGE("GenieDialog_free failed: %d", status);
            }
            dialog_ = nullptr;
        }

        if (config_) {
            Genie_Status_t status = GenieDialogConfig_free(config_);
            if (status != GENIE_STATUS_SUCCESS) {
                LOGE("GenieDialogConfig_free failed: %d", status);
            }
            config_ = nullptr;
        }

        if (log_) {
            Genie_Status_t status = GenieLog_free(log_);
            if (status != GENIE_STATUS_SUCCESS) {
                LOGE("GenieLog_free failed: %d", status);
            }
            log_ = nullptr;
        }

        if (profile_) {
            Genie_Status_t status = GenieProfile_free(profile_);
            if (status != GENIE_STATUS_SUCCESS) {
                LOGE("GenieProfile_free failed: %d", status);
            }
            profile_ = nullptr;
        }
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!dialog_) {
            throw std::runtime_error("Native Genie dialog is not initialized.");
        }
        Genie_Status_t status = GenieDialog_reset(dialog_);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieDialog_reset", status));
        }
        lastManualPromptTokenCount_ = -1;
        lastManualGeneratedTokenCount_ = -1;
        lastManualPromptTokenIds_ = "[]";
        LOGI("GenieDialog_reset success");
    }

    std::string warmup(const std::string& systemPrompt) {
        std::lock_guard<std::mutex> lock(mutex_);
        QueryState state;
        LOGI("GenieDialog_query warmup start, promptLength=%zu, prompt=[%s]",
             systemPrompt.size(), systemPrompt.c_str());
        Genie_Status_t status = GenieDialog_query(
            dialog_,
            systemPrompt.c_str(),
            GENIE_DIALOG_SENTENCE_BEGIN,
            queryCallback,
            &state);

        if (status != GENIE_STATUS_SUCCESS && status != GENIE_STATUS_WARNING_CONTEXT_EXCEEDED) {
            throw std::runtime_error(statusMessage("GenieDialog_query warmup", status));
        }
        LOGI("GenieDialog_query warmup finished, status=%d, resultLength=%zu, result=[%s]",
             status, state.text.size(), state.text.c_str());
        return state.text;
    }

    std::string query(const std::string& prompt, bool rewind) {
        std::lock_guard<std::mutex> lock(mutex_);
        QueryState state;
        lastManualPromptTokenCount_ = -1;
        lastManualPromptTokenIds_ = "[]";
        GenieTokenizer_Handle_t tokenizer = nullptr;
        Genie_Status_t status = GenieDialog_getTokenizer(dialog_, &tokenizer);
        if (status != GENIE_STATUS_SUCCESS || !tokenizer) {
            throw std::runtime_error(statusMessage("GenieDialog_getTokenizer", status));
        }
        const int32_t* promptTokenIds = nullptr;
        uint32_t promptTokenCount = 0;
        status = GenieTokenizer_encode(tokenizer, prompt.c_str(), profileAllocCallback,
                                       &promptTokenIds, &promptTokenCount);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieTokenizer_encode", status));
        }
        logTokenIds("[query] prompt", promptTokenIds, promptTokenCount);
        lastManualPromptTokenIds_ = formatTokenIds(promptTokenIds, promptTokenCount);
        std::free(const_cast<int32_t*>(promptTokenIds));
        lastManualPromptTokenCount_ = static_cast<int>(promptTokenCount);
        if (promptTokenCount >= static_cast<uint32_t>(maxAllTokens_)) {
            throw std::runtime_error(
                "Input token count " + std::to_string(promptTokenCount) +
                " reaches max_all_token " + std::to_string(maxAllTokens_) +
                "; no output-token budget remains.");
        }
        const uint32_t remainingTokenBudget =
            static_cast<uint32_t>(maxAllTokens_) - promptTokenCount;
        const uint32_t outputTokenBudget =
            remainingTokenBudget < static_cast<uint32_t>(maxOutputTokens_)
                ? remainingTokenBudget
                : static_cast<uint32_t>(maxOutputTokens_);
        status = GenieDialog_setMaxNumTokens(dialog_, outputTokenBudget);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieDialog_setMaxNumTokens", status));
        }
        auto start = std::chrono::steady_clock::now();
        GenieDialog_SentenceCode_t sentenceCode = rewind
            ? GENIE_DIALOG_SENTENCE_REWIND
            : GENIE_DIALOG_SENTENCE_COMPLETE;
        LOGI("GenieDialog_query start, inputCode=%s, promptLength=%zu, prompt=[%s]",
             sentenceCodeName(sentenceCode), prompt.size(), prompt.c_str());
        status = GenieDialog_query(
            dialog_,
            prompt.c_str(),
            sentenceCode,
            queryCallback,
            &state);

        if (status != GENIE_STATUS_SUCCESS && status != GENIE_STATUS_WARNING_CONTEXT_EXCEEDED) {
            throw std::runtime_error(statusMessage("GenieDialog_query", status));
        }
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        LOGI("GenieDialog_query finished, status=%d, promptTokens=%u, outputTokenBudget=%u, "
             "maxAllTokens=%d, elapsedMs=%lld, resultLength=%zu, generatedTokens=%d, result=[%s]",
             status,
             promptTokenCount,
             outputTokenBudget,
             maxAllTokens_,
             static_cast<long long>(elapsedMs),
             state.text.size(),
             state.generatedTokenCount,
             state.text.c_str());
        lastManualGeneratedTokenCount_ = state.generatedTokenCount;
        return state.text;
    }

    std::string profileJson() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!profile_) {
            return "{}";
        }
        const char* jsonData = nullptr;
        Genie_Status_t status = GenieProfile_getJsonData(profile_, profileAllocCallback, &jsonData);
        if (status != GENIE_STATUS_SUCCESS) {
            return statusMessage("GenieProfile_getJsonData", status);
        }
        std::string result(jsonData ? jsonData : "");
        std::free(const_cast<char*>(jsonData));
        return result;
    }

    int lastManualPromptTokenCount() const {
        return lastManualPromptTokenCount_;
    }

    const std::string& lastManualPromptTokenIds() const {
        return lastManualPromptTokenIds_;
    }

    std::string queryStreaming(const std::string& prompt, bool rewind,
                               JNIEnv* env, jobject callback, jmethodID onTokenMethod) {
        std::lock_guard<std::mutex> lock(mutex_);
        lastManualPromptTokenCount_ = -1;
        lastManualPromptTokenIds_ = "[]";

        // 手动 encode 计算 prompt token 数（用于交叉验证）
        GenieTokenizer_Handle_t tokenizer = nullptr;
        Genie_Status_t status = GenieDialog_getTokenizer(dialog_, &tokenizer);
        if (status != GENIE_STATUS_SUCCESS || !tokenizer) {
            throw std::runtime_error(statusMessage("GenieDialog_getTokenizer", status));
        }
        const int32_t* promptTokenIds = nullptr;
        uint32_t promptTokenCount = 0;
        status = GenieTokenizer_encode(tokenizer, prompt.c_str(), profileAllocCallback,
                                       &promptTokenIds, &promptTokenCount);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieTokenizer_encode", status));
        }
        logTokenIds("[queryStreaming] prompt", promptTokenIds, promptTokenCount);
        lastManualPromptTokenIds_ = formatTokenIds(promptTokenIds, promptTokenCount);
        std::free(const_cast<int32_t*>(promptTokenIds));
        lastManualPromptTokenCount_ = static_cast<int>(promptTokenCount);

        if (promptTokenCount >= static_cast<uint32_t>(maxAllTokens_)) {
            throw std::runtime_error(
                "Input token count " + std::to_string(promptTokenCount) +
                " reaches max_all_token " + std::to_string(maxAllTokens_) +
                "; no output-token budget remains.");
        }
        const uint32_t remainingTokenBudget =
            static_cast<uint32_t>(maxAllTokens_) - promptTokenCount;
        const uint32_t outputTokenBudget =
            remainingTokenBudget < static_cast<uint32_t>(maxOutputTokens_)
                ? remainingTokenBudget
                : static_cast<uint32_t>(maxOutputTokens_);
        status = GenieDialog_setMaxNumTokens(dialog_, outputTokenBudget);
        if (status != GENIE_STATUS_SUCCESS) {
            throw std::runtime_error(statusMessage("GenieDialog_setMaxNumTokens", status));
        }

        struct StreamContext {
            JNIEnv* env;
            jobject callback;
            jmethodID onTokenMethod;
            std::string text;
            int generatedTokenCount = 0;
        };
        StreamContext ctx{env, callback, onTokenMethod, "", 0};

        auto start = std::chrono::steady_clock::now();
        GenieDialog_SentenceCode_t sentenceCode = rewind
            ? GENIE_DIALOG_SENTENCE_REWIND
            : GENIE_DIALOG_SENTENCE_COMPLETE;
        LOGI("GenieDialog_queryStreaming start, inputCode=%s, promptLength=%zu",
             sentenceCodeName(sentenceCode), prompt.size());

        status = GenieDialog_query(
            dialog_,
            prompt.c_str(),
            sentenceCode,
            [](const char* response,
               const GenieDialog_SentenceCode_t sentenceCode,
               const void* userData) {
                auto* ctx = reinterpret_cast<StreamContext*>(const_cast<void*>(userData));
                if (!ctx || !ctx->env || !ctx->callback || !response) return;
                if (sentenceCode == GENIE_DIALOG_SENTENCE_BEGIN ||
                    sentenceCode == GENIE_DIALOG_SENTENCE_CONTINUE ||
                    sentenceCode == GENIE_DIALOG_SENTENCE_END ||
                    sentenceCode == GENIE_DIALOG_SENTENCE_COMPLETE) {
                    ctx->text += response;
                    ctx->generatedTokenCount++;
                    jstring jToken = ctx->env->NewStringUTF(response);
                    ctx->env->CallVoidMethod(ctx->callback, ctx->onTokenMethod, jToken);
                    ctx->env->DeleteLocalRef(jToken);
                }
            },
            &ctx);

        if (status != GENIE_STATUS_SUCCESS && status != GENIE_STATUS_WARNING_CONTEXT_EXCEEDED) {
            throw std::runtime_error(statusMessage("GenieDialog_queryStreaming", status));
        }
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        LOGI("GenieDialog_queryStreaming finished, status=%d, promptTokens=%u, outputTokenBudget=%u, "
             "maxAllTokens=%d, elapsedMs=%lld, resultLength=%zu, generatedTokens=%d",
             status,
             promptTokenCount,
             outputTokenBudget,
             maxAllTokens_,
             static_cast<long long>(elapsedMs),
             ctx.text.size(),
             ctx.generatedTokenCount);
        lastManualGeneratedTokenCount_ = ctx.generatedTokenCount;
        return ctx.text;
        return ctx.text;
    }

private:
    GenieDialogConfig_Handle_t config_ = nullptr;
    GenieDialog_Handle_t dialog_ = nullptr;
    GenieProfile_Handle_t profile_ = nullptr;
    GenieLog_Handle_t log_ = nullptr;
    int maxAllTokens_ = 256;
    int maxOutputTokens_;
    int lastManualPromptTokenCount_ = -1;
    int lastManualGeneratedTokenCount_ = -1;
    std::string lastManualPromptTokenIds_ = "[]";
    std::mutex mutex_;
};
jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string toString(JNIEnv* env, jstring value) {
    if (!value) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv* env, const std::exception& e) {
    jclass clazz = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(clazz, e.what());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_version(JNIEnv* env, jclass) {
    std::ostringstream ss;
    ss << Genie_getApiMajorVersion() << "."
       << Genie_getApiMinorVersion() << "."
       << Genie_getApiPatchVersion();
    return toJString(env, ss.str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_qairt_qwen3htp_GenieNative_create(JNIEnv* env,
                                                  jclass,
                                                   jstring modelRoot,
                                                   jint contextSize,
                                                   jint maxTokens,
                                                   jint maxOutputTokens,
                                                  jint threadCount,
                                                 jboolean greedy,
                                                 jint topK,
                                                 jfloat topP,
                                                 jfloat temperature,
                                                 jfloat presencePenalty) {
    try {
        auto session = std::make_unique<GenieSession>(
            toString(env, modelRoot),
            static_cast<int>(contextSize),
            static_cast<int>(maxTokens),
            static_cast<int>(maxOutputTokens),
            static_cast<int>(threadCount),
            greedy == JNI_TRUE,
            static_cast<int>(topK),
            static_cast<float>(topP),
            static_cast<float>(temperature),
            static_cast<float>(presencePenalty));
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& e) {
        throwJava(env, e);
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_warmup(JNIEnv* env,
                                                 jclass,
                                                 jlong handle,
                                                 jstring systemPrompt) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }
        return toJString(env, session->warmup(toString(env, systemPrompt)));
    } catch (const std::exception& e) {
        throwJava(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_query(JNIEnv* env,
                                                 jclass,
                                                 jlong handle,
                                                 jstring prompt,
                                                 jboolean rewind) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }
        return toJString(env, session->query(
            toString(env, prompt), rewind == JNI_TRUE));
    } catch (const std::exception& e) {
        throwJava(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_runtimeEvidence(JNIEnv* env, jclass) {
    return toJString(env, collectRuntimeEvidence());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_profileJson(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }
        return toJString(env, session->profileJson());
    } catch (const std::exception& e) {
        throwJava(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_qairt_qwen3htp_GenieNative_getManualPromptTokenCount(JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<GenieSession*>(handle);
    return session ? session->lastManualPromptTokenCount() : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qairt_qwen3htp_GenieNative_reset(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }
        session->reset();
    } catch (const std::exception& e) {
        throwJava(env, e);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_getManualPromptTokenIds(JNIEnv* env,
                                                            jclass,
                                                            jlong handle) {
    auto* session = reinterpret_cast<GenieSession*>(handle);
    return toJString(env, session ? session->lastManualPromptTokenIds() : "[]");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_GenieNative_queryStreaming(JNIEnv* env,
                                                   jclass,
                                                   jlong handle,
                                                   jstring prompt,
                                                   jboolean rewind,
                                                   jobject callback) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }

        jclass callbackClass = env->GetObjectClass(callback);
        if (!callbackClass) {
            throw std::runtime_error("Failed to get StreamCallback class");
        }
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(callbackClass);

        if (!onTokenMethod) {
            throw std::runtime_error("StreamCallback.onToken method not found");
        }

        jobject globalCallback = env->NewGlobalRef(callback);
        if (!globalCallback) {
            throw std::runtime_error("Failed to create global ref for StreamCallback");
        }

        std::string result;
        try {
            result = session->queryStreaming(
                toString(env, prompt), rewind == JNI_TRUE, env, globalCallback, onTokenMethod);
        } catch (...) {
            env->DeleteGlobalRef(globalCallback);
            throw;
        }
        env->DeleteGlobalRef(globalCallback);
        return toJString(env, result);
    } catch (const std::exception& e) {
        throwJava(env, e);
        return nullptr;
    }
}


extern "C" JNIEXPORT void JNICALL
Java_com_qairt_qwen3htp_GenieNative_release(JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<GenieSession*>(handle);
    delete session;
}
