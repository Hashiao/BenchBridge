// 直接复用 Android 内核，禁止跨文件 LTO 改写测量边界。 / Reuse the Android kernels directly; disable cross-file LTO at measurement boundaries.
#include "../../app/src/main/cpp/ram_kernels.cpp"
#include "../../app/src/main/cpp/compute/compute_cpu_kernels.cpp"
