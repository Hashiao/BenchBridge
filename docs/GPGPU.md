# GPGPU 测量说明 / GPGPU measurement notes

GPGPU 使用独立配置及结果目录，不改变 RAM / ROM 的预设和测量内核。默认测试全部 12 项、CPU 与 GPU 两个对象，每项每对象 3 轮，预热 200 ms、正式时段 1 秒。内存项目使用 64 MiB 缓冲区，分形固定 512×512、最多 128 次迭代。主值为完整有效轮次的中位数。

GPGPU has separate configuration and result storage, leaving the RAM/ROM presets and kernels intact. Defaults select all twelve operations and both processors, with three rounds per operation/processor, 200 ms warmup and a one-second measurement window. Memory tests use 64 MiB buffers; fractals use 512×512 pixels and up to 128 iterations. Scores are medians of complete, verified rounds.

## 计算口径 / Counting rules

| 项目 / Operation | 计数 / Count |
|---|---|
| FP32 / FP64 | 每个标量乘加计 2 次运算 / Two operations per scalar multiply-add |
| INT24 / INT32 / INT64 | 每个整数乘加计 2 次运算，按无符号位宽回绕 / Two operations per multiply-add with unsigned wraparound |
| AES-256 | 独立的 16 B 数据块，256 位固定测试密钥，密钥展开不计时 / Independent 16-byte blocks with a fixed 256-bit test key; expansion is outside timing |
| SHA-1 | 独立 64 B 消息，含完整填充和两个压缩块 / Independent 64-byte messages, including padding and both compression blocks |
| Julia / Mandel | 完整分形图像数 / Complete fractal images |
| 内存读取、写入 / Memory read, write | 单方向完成字节 / Completed bytes in one direction |
| 内存拷贝 / Memory copy | 读取与写入之和，即 2×复制字节 / Read plus write traffic: twice the copied bytes |

INT24 的两个乘数限制为 24 位，累加及结果为 32 位。GPU 使用等价的无符号掩码与运算，不声明存在独立 INT24 指令。GFLOPS / GIOPS 按十进制 10⁹ 换算，MB/s 按 10⁶ 换算。SHA-1 与 SHA-256、SHA3 是不同项目。

INT24 restricts both multiplicands to 24 bits and uses a 32-bit addend/result. GPU code uses equivalent unsigned masking and arithmetic without claiming a dedicated INT24 instruction. GFLOPS/GIOPS use decimal 10⁹ and MB/s uses 10⁶. SHA-1 is distinct from SHA-256 and SHA3.

Julia 使用 FP32，区域为 x∈[-1.5,1.5]、y∈[-1,1]，常数为 (-0.7,0.27015)；Mandel 使用 FP64，区域为 x∈[-2,1]、y∈[-1.5,1.5]。两者以模平方大于 4 为逃逸条件。CPU 各线程计算完整图像，避免不同区域复杂度造成帧率计数偏差。

Julia uses FP32 over x∈[-1.5,1.5], y∈[-1,1], with constant (-0.7,0.27015). Mandel uses FP64 over x∈[-2,1], y∈[-1.5,1.5]. Both escape when squared magnitude exceeds four. Each CPU worker computes whole images so differing region complexity does not distort frame counts.

## 执行与校验 / Execution and validation

CPU 运算使用独立的原生库：ARM64 浮点采用 NEON FMA，x86_64 采用 SSE2 乘加；AES 与 ARM64 SHA-1 按实际指令支持选择加速路径。每个计算线程绑定并核对实际 CPU。自动模式使用当前可用 CPU，最多 16 个。三个 CPU 内存项目复用现有 RAM 内核；GPU 先运行，释放资源后再运行 CPU。

CPU arithmetic uses a separate native library: NEON FMA on ARM64 and SSE2 multiply/add on x86_64. AES and ARM64 SHA-1 acceleration is selected from runtime instruction support. Compute workers pin and verify their CPUs. Automatic mode uses currently available CPUs, up to sixteen. The three CPU memory cases reuse existing RAM kernels. GPU cases run first and release their resources before CPU cases start.

GPU 通过 Vulkan 查询实际 FP64、INT64 和队列能力。支持的计算项目比较 64 / 128 / 256 的工作组配置，并调整每次提交的工作量；校准独立记录。正式计时优先累加设备时间戳，缺少时间戳时记录提交至围栏完成的主机耗时。报告明确记录计时方式、工作组、运算次数及缓冲区大小。编译、初始化、预热与结果校验不计入主分母。

GPU capabilities for FP64, INT64 and queues are queried through Vulkan. Supported compute cases compare workgroup sizes of 64/128/256 and adjust work per submission; calibration is recorded separately. Timing prefers accumulated device timestamps, with a recorded submit-to-fence host timer when unavailable. Reports include the timer, workgroup, operation counts and buffer size. Compilation, initialization, warmup and validation are outside the score denominator.

GPU 读取使用设备缓冲区→主机缓冲区，写入方向相反；拷贝在两个独立设备缓冲区之间进行。数据由偏移及种子生成，抽样校验传输结果。算术和密码学着色器与 CPU 参考实现交叉核对，AES / SHA-1 另运行标准已知答案；分形抽样验证像素迭代次数，FP32 允许两次迭代的边界差异。

GPU read transfers a device buffer to a host buffer; write reverses that direction. Copy uses two distinct device buffers. Offset/seed-generated data is sampled after transfer. Arithmetic and cryptographic shader outputs are checked against CPU references, with additional standard AES/SHA-1 known-answer tests. Fractal validation samples escape counts, allowing a two-iteration FP32 boundary difference.

能力探测在独立进程运行。测试与 RAM / ROM 互斥，支持停止、锁屏续跑及进程中断后的已完成结果恢复。GPU 提交采用有界等待；驱动超时后保留记录并重启工作进程。正常运行不把 CPU 类型的 Vulkan 设备当作 GPU，测试入口可显式允许软件设备校验着色器。未知、不支持和失败分别保存在报告中。

Capability probing is isolated in a separate process. Runs exclude simultaneous RAM/ROM work and support cancellation, screen-off execution and recovery of completed results after worker interruption. GPU submissions have bounded waits; a driver timeout preserves reports and restarts the worker. Normal runs exclude CPU-type Vulkan devices; tests may explicitly allow software devices for shader verification. Unknown capabilities, unsupported operations and failures remain distinct in reports.

参考 / References: [Vulkan capabilities](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFeatures.html), [Vulkan timestamps](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdWriteTimestamp.html), [OpenCL mad24 semantics](https://registry.khronos.org/OpenCL/specs/unified/refpages/man/html/mad24.html), [FIPS 197 AES](https://csrc.nist.gov/pubs/fips/197/final), [FIPS 180-4 SHA](https://csrc.nist.gov/pubs/fips/180-4/upd1/final).
