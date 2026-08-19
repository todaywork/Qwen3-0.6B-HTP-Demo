#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <sstream>
#include <string>
#include <QNN/QnnContext.h>
#include <QNN/QnnDevice.h>
#include <QNN/QnnInterface.h>
#include <QNN/HTP/QnnHtpDevice.h>

namespace {
using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* p = env->GetStringUTFChars(value, nullptr);
    std::string result = p ? p : "";
    if (p) env->ReleaseStringUTFChars(value, p);
    return result;
}
std::string escape(const std::string& value) {
    std::string out;
    for (char c : value) {
        if (c == '"' || c == '\\') out.push_back('\\');
        if (c == '\n') out += "\\n"; else out.push_back(c);
    }
    return out;
}
void statusJson(std::ostringstream& out, const char* key, bool ok, long code, bool comma = true) {
    out << "\"" << key << "\":{\"status\":\"" << (ok ? "PASS" : "FAIL")
        << "\",\"code\":" << code << "}" << (comma ? "," : "");
}
void* openPackagedLibrary(const std::string& nativeDir, const char* soname) {
    void* handle = dlopen(soname, RTLD_NOW | RTLD_GLOBAL);
    if (!handle && !nativeDir.empty())
        handle = dlopen((nativeDir + "/" + soname).c_str(), RTLD_NOW | RTLD_GLOBAL);
    return handle;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_npudiagnostics_NpuDiagnosticsNative_probeQnn(
        JNIEnv* env, jclass, jstring nativeDirValue, jstring dspDirValue,
        jint htpArch) {
    const std::string nativeDir = fromJString(env, nativeDirValue);
    const std::string dspDir = fromJString(env, dspDirValue);
    setenv("ADSP_LIBRARY_PATH", (dspDir + ";" + nativeDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp").c_str(), 1);
    std::ostringstream out; out << "{\"htpArch\":" << htpArch << ',';
    void* system = openPackagedLibrary(nativeDir, "libQnnSystem.so");
    statusJson(out, "systemLibraryLoad", system != nullptr, system ? 0 : -1);
    const char* stubName = nullptr;
    if (htpArch == 68) stubName = "libQnnHtpV68Stub.so";
    else if (htpArch == 73) stubName = "libQnnHtpV73Stub.so";
    if (!stubName) {
        out << "\"result\":\"UNSUPPORTED_HTP_ARCH\"}";
        return env->NewStringUTF(out.str().c_str());
    }
    void* stub = openPackagedLibrary(nativeDir, stubName);
    statusJson(out, "htpStubLoad", stub != nullptr, stub ? 0 : -1);
    out << "\"htpStubName\":\"" << stubName << "\",";
    void* backend = openPackagedLibrary(nativeDir, "libQnnHtp.so");
    statusJson(out, "htpBackendLoad", backend != nullptr, backend ? 0 : -1);
    if (!backend) {
        const char* error = dlerror(); out << "\"error\":\"" << escape(error ? error : "dlopen failed") << "\"}";
        return env->NewStringUTF(out.str().c_str());
    }
    auto getProviders = reinterpret_cast<GetProvidersFn>(dlsym(backend, "QnnInterface_getProviders"));
    const QnnInterface_t** providers = nullptr; uint32_t count = 0;
    Qnn_ErrorHandle_t result = getProviders ? getProviders(&providers, &count) : -1;
    statusJson(out, "getProviders", result == QNN_SUCCESS && count > 0, result);
    out << "\"providerCount\":" << count << ',';
    if (result != QNN_SUCCESS || !providers || count == 0) { out << "\"result\":\"FAIL\"}"; return env->NewStringUTF(out.str().c_str()); }
    const QnnInterface_t* selected = nullptr;
    for (uint32_t i = 0; i < count; ++i) if (providers[i] && providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR) { selected = providers[i]; break; }
    if (!selected) { out << "\"result\":\"NO_COMPATIBLE_PROVIDER\"}"; return env->NewStringUTF(out.str().c_str()); }
    auto qnn = selected->QNN_INTERFACE_VER_NAME;
    out << "\"providerName\":\"" << escape(selected->providerName ? selected->providerName : "") << "\",";
    out << "\"apiVersion\":\"" << selected->apiVersion.coreApiVersion.major << '.' << selected->apiVersion.coreApiVersion.minor << '.' << selected->apiVersion.coreApiVersion.patch << "\",";
    Qnn_BackendHandle_t backendHandle = nullptr;
    result = qnn.backendCreate ? qnn.backendCreate(nullptr, nullptr, &backendHandle) : -1;
    statusJson(out, "backendCreate", result == QNN_SUCCESS, result);
    const QnnDevice_PlatformInfo_t* platform = nullptr;
    result = qnn.deviceGetPlatformInfo ? qnn.deviceGetPlatformInfo(nullptr, &platform) : QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE;
    statusJson(out, "deviceGetPlatformInfo", result == QNN_SUCCESS, result);
    out << "\"platformInfo\":{"; bool foundVtcm = false;
    if (result == QNN_SUCCESS && platform && platform->version == QNN_DEVICE_PLATFORM_INFO_VERSION_1) {
        out << "\"deviceCount\":" << platform->v1.numHwDevices;
        if (platform->v1.numHwDevices && platform->v1.hwDevices) {
            const auto& hw = platform->v1.hwDevices[0];
            out << ",\"deviceId\":" << hw.v1.deviceId << ",\"deviceType\":" << hw.v1.deviceType << ",\"coreCount\":" << hw.v1.numCores;
            auto* ext = reinterpret_cast<const QnnHtpDevice_DeviceInfoExtension_t*>(hw.v1.deviceInfoExtension);
            if (ext) {
                out << ",\"htpDeviceType\":" << static_cast<int>(ext->devType);
                if (ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
                    const auto& chip = ext->onChipDevice;
                    out << ",\"vtcmSizeMb\":" << chip.vtcmSize << ",\"socModel\":" << chip.socModel
                        << ",\"arch\":" << static_cast<int>(chip.arch)
                        << ",\"signedPdSupport\":" << (chip.signedPdSupport ? "true" : "false")
                        << ",\"dlbcSupport\":" << (chip.dlbcSupport ? "true" : "false");
                    foundVtcm = true;
                }
            }
        }
    }
    out << "},";
    if (platform && qnn.deviceFreePlatformInfo) qnn.deviceFreePlatformInfo(nullptr, platform);
    Qnn_DeviceHandle_t device = nullptr;
    result = qnn.deviceCreate ? qnn.deviceCreate(nullptr, nullptr, &device) : -1;
    statusJson(out, "deviceCreate", result == QNN_SUCCESS, result);
    Qnn_ContextHandle_t context = nullptr;
    result = (result == QNN_SUCCESS && qnn.contextCreate) ? qnn.contextCreate(backendHandle, device, nullptr, &context) : -1;
    statusJson(out, "contextCreate", result == QNN_SUCCESS, result);
    if (context && qnn.contextFree) qnn.contextFree(context, nullptr);
    if (device && qnn.deviceFree) qnn.deviceFree(device);
    if (backendHandle && qnn.backendFree) qnn.backendFree(backendHandle);
    out << "\"vtcmSource\":\"" << (foundVtcm ? "QnnDevice_getPlatformInfo" : "UNKNOWN") << "\",";
    out << "\"result\":\"" << (result == QNN_SUCCESS ? "PASS" : "FAIL") << "\"}";
    __android_log_print(ANDROID_LOG_INFO, "NpuQnnProbe", "%s", out.str().c_str());
    return env->NewStringUTF(out.str().c_str());
}
