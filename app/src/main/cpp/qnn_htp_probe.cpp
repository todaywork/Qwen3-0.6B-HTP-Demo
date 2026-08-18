#include <jni.h>

#include <QNN/QnnContext.h>
#include <QNN/QnnDevice.h>
#include <QNN/QnnInterface.h>
#include <QNN/QnnTypes.h>

#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <string>

namespace {

constexpr const char* kTag = "QnnHtpProbe";

using QnnInterfaceGetProvidersFn =
    Qnn_ErrorHandle_t (*)(const QnnInterface_t*** providerList, uint32_t* numProviders);

void appendLine(std::ostringstream& out, const std::string& line) {
  __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
  out << line << '\n';
}

std::string statusLine(const char* step, Qnn_ErrorHandle_t status) {
  std::ostringstream out;
  out << step << " status=" << status << (status == QNN_SUCCESS ? " OK" : " FAIL");
  return out.str();
}

void* openLibrary(std::ostringstream& out, const std::string& path, int flags) {
  dlerror();
  void* handle = dlopen(path.c_str(), flags);
  if (handle == nullptr) {
    const char* err = dlerror();
    appendLine(out, "dlopen FAIL: " + path);
    appendLine(out, std::string("  error: ") + (err ? err : "unknown"));
  } else {
    appendLine(out, "dlopen OK: " + path);
  }
  return handle;
}

template <typename T>
T resolveSymbol(std::ostringstream& out, void* handle, const char* symbol) {
  dlerror();
  auto fn = reinterpret_cast<T>(dlsym(handle, symbol));
  const char* err = dlerror();
  if (fn == nullptr || err != nullptr) {
    appendLine(out, std::string("dlsym FAIL: ") + symbol);
    appendLine(out, std::string("  error: ") + (err ? err : "unknown"));
    return nullptr;
  }
  appendLine(out, std::string("dlsym OK: ") + symbol);
  return fn;
}

std::string jstringToString(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_qairt_qwen3htp_MainActivity_probeHtpNative(JNIEnv* env,
                                                          jobject /*thiz*/,
                                                          jstring nativeLibDirString,
                                                          jstring dspDirString) {
  std::ostringstream out;
  const std::string nativeLibDir = jstringToString(env, nativeLibDirString);
  const std::string dspDir = jstringToString(env, dspDirString);

  appendLine(out, "QNN HTP native probe begin");
  appendLine(out, "nativeLibraryDir=" + nativeLibDir);
  appendLine(out, "dspDir=" + dspDir);

  const std::string adspPath = dspDir + ";" + nativeLibDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp";
  setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1);
  appendLine(out, "setenv ADSP_LIBRARY_PATH=" + adspPath);

  void* systemHandle = openLibrary(out, nativeLibDir + "/libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
  void* htpPrepareHandle = openLibrary(out, nativeLibDir + "/libQnnHtpPrepare.so", RTLD_NOW | RTLD_GLOBAL);
  void* htpStubHandle = openLibrary(out, nativeLibDir + "/libQnnHtpV73Stub.so", RTLD_NOW | RTLD_GLOBAL);
  void* htpCalcHandle =
      openLibrary(out, nativeLibDir + "/libQnnHtpV73CalculatorStub.so", RTLD_NOW | RTLD_GLOBAL);
  void* backendHandle = openLibrary(out, nativeLibDir + "/libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);

  if (backendHandle == nullptr) {
    appendLine(out, "Probe stop: libQnnHtp.so did not load.");
    return env->NewStringUTF(out.str().c_str());
  }

  auto getProviders =
      resolveSymbol<QnnInterfaceGetProvidersFn>(out, backendHandle, "QnnInterface_getProviders");
  if (getProviders == nullptr) {
    appendLine(out, "Probe stop: QnnInterface_getProviders not found.");
    return env->NewStringUTF(out.str().c_str());
  }

  const QnnInterface_t** providers = nullptr;
  uint32_t numProviders = 0;
  Qnn_ErrorHandle_t status = getProviders(&providers, &numProviders);
  appendLine(out, statusLine("QnnInterface_getProviders", status));
  appendLine(out, "numProviders=" + std::to_string(numProviders));
  if (status != QNN_SUCCESS || providers == nullptr || numProviders == 0) {
    appendLine(out, "Probe stop: no QNN interface provider.");
    return env->NewStringUTF(out.str().c_str());
  }

  const QnnInterface_t* selectedProvider = nullptr;
  for (uint32_t i = 0; i < numProviders; ++i) {
    const QnnInterface_t* provider = providers[i];
    if (provider == nullptr) continue;
    std::ostringstream providerLine;
    providerLine << "provider[" << i << "] backendId=" << provider->backendId
                 << " name=" << (provider->providerName ? provider->providerName : "<null>")
                 << " api=" << provider->apiVersion.coreApiVersion.major << "."
                 << provider->apiVersion.coreApiVersion.minor << "."
                 << provider->apiVersion.coreApiVersion.patch;
    appendLine(out, providerLine.str());

    if (provider->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
        provider->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
      selectedProvider = provider;
      break;
    }
  }

  if (selectedProvider == nullptr) {
    appendLine(out, "Probe stop: no compatible QNN interface version.");
    return env->NewStringUTF(out.str().c_str());
  }

  QNN_INTERFACE_VER_TYPE qnn = selectedProvider->QNN_INTERFACE_VER_NAME;
  if (qnn.backendCreate == nullptr || qnn.contextCreate == nullptr) {
    appendLine(out, "Probe stop: required QNN function pointer is null.");
    return env->NewStringUTF(out.str().c_str());
  }

  Qnn_BackendHandle_t qnnBackend = nullptr;
  status = qnn.backendCreate(nullptr, nullptr, &qnnBackend);
  appendLine(out, statusLine("backendCreate", status));
  if (status != QNN_BACKEND_NO_ERROR) {
    appendLine(out, "Probe stop: backendCreate failed.");
    return env->NewStringUTF(out.str().c_str());
  }

  Qnn_DeviceHandle_t qnnDevice = nullptr;
  bool deviceCreated = false;
  if (qnn.deviceCreate != nullptr) {
    status = qnn.deviceCreate(nullptr, nullptr, &qnnDevice);
    appendLine(out, statusLine("deviceCreate", status));
    if (status == QNN_SUCCESS) {
      deviceCreated = true;
    } else if (status == QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) {
      appendLine(out, "deviceCreate unsupported; continuing with null device handle.");
    } else {
      appendLine(out, "Probe stop: deviceCreate failed.");
      if (qnn.backendFree != nullptr) {
        qnn.backendFree(qnnBackend);
      }
      return env->NewStringUTF(out.str().c_str());
    }
  } else {
    appendLine(out, "deviceCreate pointer is null; continuing with null device handle.");
  }

  Qnn_ContextHandle_t qnnContext = nullptr;
  status = qnn.contextCreate(qnnBackend, qnnDevice, nullptr, &qnnContext);
  appendLine(out, statusLine("contextCreate", status));

  if (qnnContext != nullptr && qnn.contextFree != nullptr) {
    Qnn_ErrorHandle_t freeStatus = qnn.contextFree(qnnContext, nullptr);
    appendLine(out, statusLine("contextFree", freeStatus));
  }
  if (deviceCreated && qnn.deviceFree != nullptr) {
    Qnn_ErrorHandle_t freeStatus = qnn.deviceFree(qnnDevice);
    appendLine(out, statusLine("deviceFree", freeStatus));
  }
  if (qnnBackend != nullptr && qnn.backendFree != nullptr) {
    Qnn_ErrorHandle_t freeStatus = qnn.backendFree(qnnBackend);
    appendLine(out, statusLine("backendFree", freeStatus));
  }

  if (status == QNN_CONTEXT_NO_ERROR) {
    appendLine(out, "RESULT: APK native HTP probe PASSED.");
  } else {
    appendLine(out, "RESULT: APK native HTP probe FAILED at contextCreate.");
  }

  (void)systemHandle;
  (void)htpPrepareHandle;
  (void)htpStubHandle;
  (void)htpCalcHandle;

  return env->NewStringUTF(out.str().c_str());
}
