#include "compute_common.h"
#include "compute_gpu.h"
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace {
struct Session{
    JavaVM* vm=nullptr;jobject owner=nullptr;AAssetManager* assets=nullptr;bool allowSoftware=false;
    std::atomic<bool> cancelled{false};std::mutex call;std::unique_ptr<bbcompute::GpuEngine> gpu;
    ~Session(){gpu.reset();if(owner){JNIEnv* env=nullptr;bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK;
        if(attached)vm->AttachCurrentThread(&env,nullptr);env->DeleteGlobalRef(owner);if(attached)vm->DetachCurrentThread();}}
};
std::mutex registryMutex;std::map<jlong,std::shared_ptr<Session>> sessions;jlong nextHandle=1;
std::shared_ptr<Session> find(jlong handle){std::lock_guard lock(registryMutex);const auto it=sessions.find(handle);return it==sessions.end()?nullptr:it->second;}
std::string failure(const std::string& error,bool cancelled=false,bool restart=false){return "{\"status\":"+bbcompute::quote(cancelled?"INTERRUPTED":"FAILED")+",\"verified\":false,\"error\":"+bbcompute::quote(error)+",\"restart_worker\":"+(restart?"true":"false")+'}';}
}
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_compute_ComputeNative_cpuCapabilities(JNIEnv* env,jobject){
    try{return env->NewStringUTF(bbcompute::cpu_capabilities().c_str());}catch(...){return env->NewStringUTF("{\"cpu_ids\":[],\"error\":\"CPU_PROBE_FAILED\"}");}}
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_compute_ComputeNative_gpuCapabilities(JNIEnv* env,jobject,jboolean allowSoftware){
    try{bbcompute::GpuEngine gpu(nullptr,allowSoftware);return env->NewStringUTF(gpu.capabilities().c_str());}
    catch(const std::exception& e){return env->NewStringUTF(("{\"supported\":false,\"error\":"+bbcompute::quote(e.what())+'}').c_str());}}
extern "C" JNIEXPORT jboolean JNICALL Java_io_benchbridge_app_compute_ComputeNative_selfTest(JNIEnv*,jobject){return bbcompute::crypto_self_test();}
extern "C" JNIEXPORT jlong JNICALL Java_io_benchbridge_app_compute_ComputeNative_createSession(JNIEnv* env,jobject,jobject assets,jboolean allowSoftware){
    try{std::lock_guard lock(registryMutex);if(!sessions.empty()||!assets)throw std::runtime_error("COMPUTE_BUSY");
        auto s=std::make_shared<Session>();env->GetJavaVM(&s->vm);s->owner=env->NewGlobalRef(assets);s->assets=AAssetManager_fromJava(env,assets);s->allowSoftware=allowSoftware;
        if(!s->owner||!s->assets)throw std::runtime_error("ASSET_MANAGER_FAILED");auto id=nextHandle++;sessions[id]=s;return id;
    }catch(...){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Could not create compute session");return 0;}}
extern "C" JNIEXPORT void JNICALL Java_io_benchbridge_app_compute_ComputeNative_cancel(JNIEnv*,jobject,jlong handle){if(auto s=find(handle))s->cancelled=true;}
extern "C" JNIEXPORT void JNICALL Java_io_benchbridge_app_compute_ComputeNative_release(JNIEnv*,jobject,jlong handle){std::lock_guard lock(registryMutex);if(sessions.contains(handle)){sessions[handle]->cancelled=true;sessions.erase(handle);}}
extern "C" JNIEXPORT void JNICALL Java_io_benchbridge_app_compute_ComputeNative_releaseGpu(JNIEnv*,jobject,jlong handle){if(auto s=find(handle)){std::lock_guard lock(s->call);s->gpu.reset();}}
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_compute_ComputeNative_runCpu(JNIEnv* env,jobject,jlong handle,jint kind,jintArray ids,jint warmup,jint duration,jint width,jint height,jint memory){
    auto s=find(handle);std::string out;
    try{if(!s||!ids)throw std::runtime_error("SESSION_MISSING");std::unique_lock lock(s->call,std::try_to_lock);if(!lock.owns_lock())throw std::runtime_error("COMPUTE_BUSY");
        auto n=env->GetArrayLength(ids);if(n<1||n>16)throw std::runtime_error("CPU_COUNT_INVALID");std::vector<int> cpus(n);env->GetIntArrayRegion(ids,0,n,cpus.data());
        out=bbcompute::cpu_round(kind,cpus,warmup,duration,width,height,memory,s->cancelled);
    }catch(const std::exception& e){out=failure(e.what(),s&&s->cancelled.load());}catch(...){out=failure("COMPUTE_NATIVE_FAILED");}
    return env->NewStringUTF(out.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_io_benchbridge_app_compute_ComputeNative_runGpu(JNIEnv* env,jobject,jlong handle,jint kind,jint memory,jint warmup,jint duration,jint width,jint height){
    auto s=find(handle);std::string out;
    try{if(!s)throw std::runtime_error("SESSION_MISSING");std::unique_lock lock(s->call,std::try_to_lock);if(!lock.owns_lock())throw std::runtime_error("COMPUTE_BUSY");
        if(s->cancelled)throw std::runtime_error("RUN_CANCELLED");if(!s->gpu)s->gpu=std::make_unique<bbcompute::GpuEngine>(s->assets,s->allowSoftware);
        out=s->gpu->round(kind,memory,warmup,duration,width,height,s->cancelled);
    }catch(const std::exception& e){out=failure(e.what(),s&&s->cancelled.load(),s&&s->gpu&&s->gpu->poisoned());}catch(...){out=failure("GPU_NATIVE_FAILED");}
    return env->NewStringUTF(out.c_str());}
