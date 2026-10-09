# 苹果首项读取缓存修复 / Apple first-read cache fix

0.12.1 真机截图显示 SEQ 1 MiB Q8T1 读取 53,381.35 MB/s，Q1T1 为 2,672.53 MB/s。截图不足以逐轮核验计数，但代码确认初始化句柄未启用无缓存，首次写入会给第一项读取留下文件缓存。

A 0.12.1 device screenshot shows 53,381.35 MB/s for SEQ 1 MiB Q8T1 versus 2,672.53 MB/s for Q1T1. A screenshot cannot verify per-round counters, but source inspection confirms the preparation handle omitted the no-cache request and could leave file pages resident before the first read.

## 原因 / Cause

旧路径先用普通 `pwrite` 初始化整个文件，再 `fsync`，只对测量句柄设置 `F_NOCACHE`。Apple 的 [F_NOCACHE 实现](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_descrip.c) 设置文件标志；[直接读取路径](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/vfs/vfs_cluster.c) 仍先尝试复制已有 UBC 页。同步写回不等于驱逐内存页。第一项读完后，写入又改变了缓存状态，因此测试顺序可能影响结果。

The old path initialized the whole file with buffered pwrite, called fsync, and requested F_NOCACHE only on measurement handles. Apple's [F_NOCACHE implementation](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_descrip.c) sets a descriptor flag; its [direct-read path](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/vfs/vfs_cluster.c) still checks existing UBC pages first. Flushing dirty data is not cache eviction. Subsequent writes change cache state, so workload order can influence results.

## 0.12.2 修复 / Fix

- 独占创建文件、确立清理所有权后，首次写入前设置 F_NOCACHE；设置失败则停止并清理本次文件。
  After exclusive creation establishes cleanup ownership, request F_NOCACHE before the first write; failure stops preparation and cleans up the owned file.
- 初始化数据池、请求缓冲和校验缓冲均按 64 KiB 对齐。预热、测量和校验也沿用请求的缓存策略。
  Align preparation data, request buffers and validation buffers to 64 KiB; warmup, measurement and validation retain the requested cache policy.
- 分别累计成功提交次数、成功完成次数和系统实际返回字节数；只有账目一致才计分。主值仍为真实完成字节 / 实际耗时，不额外乘除队列深度或修正倍率。
  Independently count successful submissions, completions and returned bytes, accepting scores only when they reconcile. Score completed bytes divided by measured time, without multiplying/dividing by queue depth or an arbitrary correction factor.
- JSON 保存 `preparation_no_cache_hint`、`buffer_alignment_bytes`、`submitted_operations`、`completed_bytes` 和 `completion_wall_ns`。后者包含所有计时 I/O 完成前的等待，独立校验主计时范围。新后端标记为 `posix-aio-nocache-init-v2`，旧报告保持原值。
  JSON adds preparation policy, buffer alignment, submissions, completed bytes and completion_wall_ns. The completion clock independently encloses timed I/O. New samples identify posix-aio-nocache-init-v2; historical reports retain their original values.

## 回归与真机复测 / Regression and device check

苹果原生回归创建两个同大小文件，使用 mincore 查询初始化后的驻留页，不读取映射页；检查普通初始化保留缓存，而无缓存初始化不预先缓存整个文件。另检查 Q8/Q1 顺序读取、Q1/Q8/Q32 随机读写的字节账目、计时和取消清理。Windows 运行可移植计数/生命周期测试，不声称模拟苹果缓存行为。实际通过记录见对应 Release。

Apple native regression compares same-sized buffered and no-cache files with mincore without touching their mappings, checking that no-cache preparation does not prepopulate the entire file. Q8/Q1 sequential reads, Q1/Q8/Q32 random reads/writes, counters, clocks and cancellation cleanup are checked. Windows covers portable counters/lifecycle, not Apple cache semantics. See the matching Release for executed checks.

修复不保证绕过全部硬件缓存，也不宣称与安卓 O_DIRECT 完全等价。请用同一手机、相同设置重新测试，并导出 ROM JSON 对照三轮结果；在收到修复版真机数据前，不宣称已验证该手机的实际闪存速度。

This does not guarantee bypassing hardware caches or equivalence to Android O_DIRECT. Retest the same phone/settings and export ROM JSON for per-round comparison; the corrected phone's physical storage speed remains unverified until device results are received.
