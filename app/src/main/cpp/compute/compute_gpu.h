#pragma once
#include "compute_common.h"
#include <android/asset_manager.h>
#include <memory>
namespace bbcompute {
class GpuEngine {
public:
    explicit GpuEngine(AAssetManager* assets,bool allow_software=false);
    ~GpuEngine();
    std::string capabilities() const;
    std::string round(int kind,int memory_mib,int warmup_ms,int duration_ms,int width,int height,std::atomic<bool>& cancelled);
    bool poisoned() const;
private:
    struct Impl;
    std::unique_ptr<Impl> p;
};
}
