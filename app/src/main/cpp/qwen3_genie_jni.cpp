#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <fstream>
#include <set>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "Genie/GenieCommon.h"
#include "Genie/GenieDialog.h"

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
        hasHtpEvidence = hasHtpEvidence || hasAny(lower, htpTerms);
    }

    std::ostringstream out;
    out << "HTP evidence: " << (hasHtpEvidence ? "DETECTED" : "NOT DETECTED") << "\n";
    out << "Genie loaded: " << (hasGenie ? "yes" : "no") << "\n";
    out << "QnnGenAiTransformer loaded: " << (hasGenAiTransformer ? "yes" : "no") << "\n";
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

void requireFile(const std::string& path) {
    LOGI("Checking required file: %s", path.c_str());
    std::ifstream in(path, std::ios::binary);
    if (!in.good()) {
        throw std::runtime_error("Missing required file: " + path);
    }
    LOGI("Required file readable: %s", path.c_str());
}

std::string buildConfig(const std::string& modelRoot, int maxTokens, int threadCount) {
    const std::string tokenizer = modelRoot + "/model/tokenizer.json";
    const std::string modelBin = modelRoot + "/model/qwen3-0.6b-q4.bin";

    requireFile(tokenizer);
    requireFile(modelBin);

    std::ostringstream json;
    json
        << "{"
        << "\"dialog\":{"
        << "\"version\":1,"
        << "\"type\":\"basic\","
        << "\"stop-sequence\":[\"<|im_end|>\"],"
        << "\"max-num-tokens\":" << maxTokens << ","
        << "\"context\":{\"version\":1,\"size\":2048,\"n-vocab\":151936,\"bos-token\":151643,\"eos-token\":151645},"
        << "\"sampler\":{\"version\":1,\"seed\":42,\"temp\":0.6,\"top-k\":20,\"top-p\":0.95,\"greedy\":true},"
        << "\"tokenizer\":{\"version\":1,\"path\":\"" << tokenizer << "\"},"
        << "\"engine\":{"
        << "\"version\":1,"
        << "\"n-threads\":" << threadCount << ","
        << "\"backend\":{\"version\":1,\"type\":\"QnnGenAiTransformer\","
        << "\"QnnGenAiTransformer\":{\"version\":1,\"n-kv-heads\":8,\"kv-quantization\":false,\"shared-engine\":false}},"
        << "\"model\":{\"version\":1,\"type\":\"library\","
        << "\"library\":{\"version\":1,\"model-bin\":\"" << modelBin << "\"}}"
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
};

void queryCallback(const char* response,
                   const GenieDialog_SentenceCode_t sentenceCode,
                   const void* userData) {
    auto* state = reinterpret_cast<QueryState*>(const_cast<void*>(userData));
    if (!state || !response) {
        return;
    }

    if (sentenceCode == GENIE_DIALOG_SENTENCE_BEGIN ||
        sentenceCode == GENIE_DIALOG_SENTENCE_CONTINUE ||
        sentenceCode == GENIE_DIALOG_SENTENCE_END ||
        sentenceCode == GENIE_DIALOG_SENTENCE_COMPLETE) {
        state->text += response;
    }
}

class GenieSession {
public:
    explicit GenieSession(const std::string& modelRoot, int maxTokens, int threadCount) {
        LOGI("GenieSession create start, modelRoot=%s, maxTokens=%d, threadCount=%d",
             modelRoot.c_str(), maxTokens, threadCount);
        std::string configJson = buildConfig(modelRoot, maxTokens, threadCount);

        LOGI("GenieDialogConfig_createFromJson start, configLength=%zu", configJson.size());
        Genie_Status_t status = GenieDialogConfig_createFromJson(configJson.c_str(), &config_);
        if (status != GENIE_STATUS_SUCCESS || !config_) {
            throw std::runtime_error(statusMessage("GenieDialogConfig_createFromJson", status));
        }
        LOGI("GenieDialogConfig_createFromJson success");

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
    }

    std::string query(const std::string& prompt) {
        std::lock_guard<std::mutex> lock(mutex_);
        QueryState state;
        auto start = std::chrono::steady_clock::now();
        LOGI("GenieDialog_query start, promptLength=%zu", prompt.size());
        Genie_Status_t status = GenieDialog_query(
            dialog_,
            prompt.c_str(),
            GENIE_DIALOG_SENTENCE_COMPLETE,
            queryCallback,
            &state);

        if (status != GENIE_STATUS_SUCCESS && status != GENIE_STATUS_WARNING_CONTEXT_EXCEEDED) {
            throw std::runtime_error(statusMessage("GenieDialog_query", status));
        }
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        LOGI("GenieDialog_query finished, status=%d, elapsedMs=%lld, resultLength=%zu",
             status,
             static_cast<long long>(elapsedMs),
             state.text.size());
        return state.text;
    }

private:
    GenieDialogConfig_Handle_t config_ = nullptr;
    GenieDialog_Handle_t dialog_ = nullptr;
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
Java_com_qairt_qwen3geniedemo_GenieNative_version(JNIEnv* env, jclass) {
    std::ostringstream ss;
    ss << Genie_getApiMajorVersion() << "."
       << Genie_getApiMinorVersion() << "."
       << Genie_getApiPatchVersion();
    return toJString(env, ss.str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_qairt_qwen3geniedemo_GenieNative_create(JNIEnv* env,
                                                 jclass,
                                                 jstring modelRoot,
                                                 jint maxTokens,
                                                 jint threadCount) {
    try {
        auto session = std::make_unique<GenieSession>(
            toString(env, modelRoot),
            static_cast<int>(maxTokens),
            static_cast<int>(threadCount));
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& e) {
        throwJava(env, e);
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3geniedemo_GenieNative_query(JNIEnv* env,
                                                jclass,
                                                jlong handle,
                                                jstring prompt) {
    try {
        auto* session = reinterpret_cast<GenieSession*>(handle);
        if (!session) {
            throw std::runtime_error("Native Genie session is not initialized.");
        }
        return toJString(env, session->query(toString(env, prompt)));
    } catch (const std::exception& e) {
        throwJava(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3geniedemo_GenieNative_runtimeEvidence(JNIEnv* env, jclass) {
    return toJString(env, collectRuntimeEvidence());
}

extern "C" JNIEXPORT void JNICALL
Java_com_qairt_qwen3geniedemo_GenieNative_release(JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<GenieSession*>(handle);
    delete session;
}
