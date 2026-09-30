# GPGPU 测量说明 / GPGPU measurement notes

GPGPU 使用独立配置及结果目录，不改变 RAM / ROM 的预设和测量内核。默认测试全部 12 项、CPU 与 GPU 两个对象，每项每对象 3 轮，预热 200 ms、正式时段 1 秒。内存及加密项目的总数据工作集为 64 MiB；分形图像为 512×512，最多 128 次迭代。主值为完整有效轮次的中位数。

GPGPU has separate configuration and result storage, leaving RAM/ROM presets and kernels intact. Defaults select all twelve operations and both processors, with three rounds per operation/processor, 200 ms warmup and a one-second measurement window. Memory and cryptographic cases use a 64 MiB total data working set; fractals use 512×512 pixels and up to 128 iterations. Scores are medians of complete, verified rounds.

## 计算口径 / Counting rules

| 项目 / Operation | 计数 / Count |
|---|---|
| FP32 / FP64 | 标量乘法和加法各计 1 FLOP，FMA 计 2 FLOPs / One FLOP per scalar multiply or add; two per FMA |
| INT24 / INT32 / INT64 | 每个整数乘加计 2 次运算，按无符号位宽回绕 / Two operations per multiply-add with unsigned wraparound |
| AES-256 | 独立 64 KiB 消息，内含独立 16 B 块，ECB、无填充；只计输入字节 / Independent 64 KiB messages of 16-byte ECB blocks, without padding; count input bytes |
| SHA-1 | 独立 64 KiB 消息，执行标准填充；只计原始消息字节 / Independent 64 KiB messages with standard padding; count original message bytes |
| Julia / Mandel | 完整图像的像素总数，显示 MPix/s / Pixels in completed images, displayed as MPix/s |
| 内存读取、写入 / Memory read, write | 单方向完成字节 / Completed bytes in one direction |
| 内存拷贝 / Memory copy | 读取与写入之和，即 2×复制字节 / Read plus write traffic: twice the copied bytes |

FP32 每次内层迭代包含 8 个四通道向量乘加，即 8×4×2=64 FLOPs；FP64 使用双通道，即 32 FLOPs。计数已包含向量宽度，不能再乘一次 4 或 2。INT24 的两个乘数限制为 24 位，累加及结果为 32 位。GPU 使用等价的掩码和运算，不要求专用 INT24 指令。GFLOPS / GIOPS 使用十进制 10⁹，MB/s 与 MPix/s 使用 10⁶。

Each FP32 inner iteration has eight four-lane multiply-adds: 8×4×2=64 FLOPs. FP64 uses two lanes, giving 32 FLOPs. Counts already include vector width and must not receive another factor of four or two. INT24 restricts both multiplicands to 24 bits with a 32-bit addend/result. GPU code uses equivalent masking and arithmetic without requiring dedicated INT24 instructions. GFLOPS/GIOPS use decimal 10⁹; MB/s and MPix/s use 10⁶.

## 工作集与算法 / Working sets and algorithms

设配置大小为 N。CPU 和 GPU 的读取输入均为 N，写入输出为 N；拷贝使用 N/2 输入加 N/2 输出，完整一遍计 N 字节。GPU 通过计算着色器直接访问设备本地缓冲区：读取执行校验归约，写入填充缓冲区，拷贝执行逐元素读写。初始化上传和结果读回都在正式计时外，不用主机传输速度代替 GPU 访问速度。读取的 4 KiB 校验输出不计入吞吐。手机的设备本地内存可以与 CPU 共享物理 DRAM。

For a configured size N, CPU and GPU reads use N bytes of input and writes use N bytes of output. Copy uses N/2 input plus N/2 output, counting N bytes per complete pass. GPU compute shaders directly access device-local buffers: read performs a checksum reduction, write fills the buffer and copy loads and stores each element. Initialization uploads and result readbacks occur outside measurement; host transfer speed is not used as GPU access speed. The read case's 4 KiB checksum output is excluded from throughput. On phones, device-local memory may share physical DRAM with the CPU.

AES 使用 N/2 输入和 N/2 输出，固定密钥字节为 00 至 1f。SHA-1 使用 N 输入及独立摘要输出；每条消息执行 1024 个数据压缩块和 1 个填充块。两端使用同一偏移数据序列、消息长度及算法定义。数据生成、分配、密钥展开和结果校验均不计时。CPU 按线程分配消息区间；GPU 以有界批次循环遍历输入，AES 批次按完整 64 KiB 消息对齐。导出记录实际输入、输出、工作集、处理消息数和计量字节。

AES uses N/2 input and N/2 output with fixed key bytes 00 through 1f. SHA-1 uses N input plus separate digest output; each message executes 1024 data compression blocks and one padding block. Both processors use the same offset-derived data, message size and algorithm. Data generation, allocation, key expansion and result verification are outside timing. CPU threads receive disjoint message ranges; GPU batches cycle through the input with AES batches aligned to whole 64 KiB messages. Exports record actual input/output sizes, working sets, processed messages and counted bytes.

Julia 使用 FP32，区域为 x∈[-1.5,1.5]、y∈[-1,1]，常数为 (-0.7,0.27015)；Mandel 使用 FP64，区域为 x∈[-2,1]、y∈[-1.5,1.5]。两者以模平方大于 4 为逃逸条件。CPU 各线程计算完整图像；未完成的图像不计入成绩。

Julia uses FP32 over x∈[-1.5,1.5], y∈[-1,1], with constant (-0.7,0.27015). Mandel uses FP64 over x∈[-2,1], y∈[-1.5,1.5]. Both escape when squared magnitude exceeds four. Each CPU worker computes whole images; incomplete images do not contribute to scores.

## 执行与校验 / Execution and validation

CPU 浮点使用 ARM64 NEON FMA 或 x86_64 SSE2 乘加。ARM64 根据运行时能力使用 AES 加速及 SHA-1 的 SHA1C/P/M/H、SHA1SU0/SU1；消息扩展也在加速路径中。x86_64 支持 AES-NI，SHA-1 保留通用实现。计算线程绑定并核对实际 CPU，自动模式最多 16 线程。三个 CPU 内存项目复用现有 RAM 内核。GPU 先运行，释放资源后再运行 CPU。

CPU floating-point kernels use ARM64 NEON FMA or x86_64 SSE2 multiply/add. Runtime capabilities select ARM64 AES and SHA-1 instructions, including SHA1C/P/M/H and SHA1SU0/SU1 for the message schedule. x86_64 supports AES-NI and retains portable SHA-1. Compute workers pin and verify their CPUs; automatic mode uses up to sixteen threads. The three CPU memory cases reuse existing RAM kernels. GPU cases run first and release resources before CPU cases start.

GPU 通过 Vulkan 查询 FP64、INT64 和队列能力，比较 64 / 128 / 256 的工作组配置。校准独立记录，不进入正式成绩。CPU 和 GPU 主成绩均采用完整正式时段的连续耗时，包含期间的调度和同步；不累加短内核时间来代替完整时段。GPU 另导出设备时间戳累计值 `device_elapsed_ns`，没有时间戳时记录提交至围栏完成的累计时间。编译、初始化、预热、清空输出及校验都在主分母之外。

Vulkan provides GPU FP64, INT64 and queue capabilities; workgroup sizes of 64/128/256 are compared during calibration. Calibration is recorded separately and excluded from scores. Both CPU and GPU scores use elapsed time across the continuous formal measurement window, including scheduling and synchronization within it. Individual kernel times do not replace this denominator. GPU reports separately include `device_elapsed_ns`, accumulated device timestamps or submit-to-fence host time when timestamps are unavailable. Compilation, initialization, warmup, output clearing and verification are outside the score denominator.

正式测量前清空输出，校验只能使用本轮完成的工作。内存读取核对全部归约结果；写入和拷贝检查边界及分散位置。加密输出与通用 CPU 参考实现交叉核对；AES / SHA-1 另运行已知答案，覆盖 AES 批量和尾块、SHA-1 填充边界及 64 KiB 消息。分形抽样验证像素迭代次数，FP32 允许两次迭代的边界差异。

Outputs are cleared before measurement so verification checks only completed work from that round. Memory reads validate every reduction result; writes and copies sample boundaries and distributed positions. Cryptographic outputs are checked against portable CPU references. Additional known-answer tests cover AES batches and tails, SHA-1 padding boundaries and 64 KiB messages. Fractal checks sample escape counts, allowing a two-iteration FP32 boundary difference.

能力探测在独立进程运行。测试与 RAM / ROM 互斥，支持停止、锁屏续跑及进程中断后的已完成结果恢复。GPU 提交采用有界等待，驱动超时后保留记录并重启工作进程。正常运行不将 CPU 类型的 Vulkan 设备当作 GPU；测试可显式允许软件设备验证着色器。未知、不支持和失败分别保存在报告中。

Capability probing runs in a separate process. Tests exclude simultaneous RAM/ROM work and support cancellation, screen-off execution and recovery of completed results after worker interruption. GPU submissions have bounded waits; driver timeouts preserve reports and restart the worker. Normal runs exclude CPU-type Vulkan devices; tests can explicitly allow software devices for shader verification. Unknown, unsupported and failed operations remain distinct in reports.

## 历史记录 / Historical results

0.7.0 使用 `gpgpu-v2`。旧版的 GPU 内存传输及短消息加密成绩保留原值，详情标记为旧版记录，不能作为同一负载直接比较。旧分形 FPS 按该轮保存的宽高换算成 MPix/s，仅改变显示，不改写原始历史或 JSON；缺失尺寸时不推测结果。

Version 0.7.0 uses `gpgpu-v2`. Earlier GPU memory-transfer and short-message cryptographic scores retain their original values and are marked as legacy records in details; they are different workloads. Legacy fractal FPS is displayed as MPix/s using the round's saved dimensions without rewriting history or exported JSON. Missing dimensions do not receive guessed values.

参考 / References: [Vulkan capabilities](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFeatures.html), [Vulkan timestamps](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdWriteTimestamp.html), [Arm intrinsics](https://arm-software.github.io/acle/neon_intrinsics/advsimd.html), [OpenCL mad24 semantics](https://registry.khronos.org/OpenCL/specs/unified/refpages/man/html/mad24.html), [FIPS 197 AES](https://csrc.nist.gov/pubs/fips/197/final), [FIPS 180-4 SHA](https://csrc.nist.gov/pubs/fips/180-4/upd1/final).
