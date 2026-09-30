# 内部 SoC 资料库 / Internal SoC catalog

`app/src/main/assets/soc_catalog.json` 用于补充运行时无法读取的规格，界面不展示芯片规格表。资料不会用来生成成绩。当前版本包含 17 个条目，覆盖骁龙 888、8 Gen 1 / 2 / 3、8 Elite、8 Elite Gen 5，天玑 8200 / 8300 / 8400 / 9000 / 9200 / 9300 / 9400 / 9400+ / 9500，以及玄戒 O1。8 Elite 七核变体单独记录。

`app/src/main/assets/soc_catalog.json` fills gaps in runtime metadata. The interface does not expose a specification table, and metadata never generates scores. The current revision has 17 entries covering Snapdragon 888, 8 Gen 1/2/3, 8 Elite and 8 Elite Gen 5; Dimensity 8200/8300/8400/9000/9200/9300/9400/9400+/9500; and XRING O1. The seven-core 8 Elite variant has a separate entry.

## 匹配与可信范围 / Matching and scope

匹配使用系统报告的 SoC 名称、硬件字段及已核实别名，忽略大小写与标点。仅接受明确别名，不按数字前缀猜测型号；多个不一致条目命中时不匹配。`cpu_count` 必须符合设备的全部核心数。核心组优先使用 MIDR part；只有条目明确允许且频率分组边界清楚时，才使用频率排序补充映射。运行时缓存值始终优先。

Matching uses reported SoC names, hardware fields and verified aliases, ignoring case and punctuation. Numeric prefixes do not imply a model match. Conflicting matches yield no profile. The device's full core count must match `cpu_count`. Core groups prefer MIDR parts; frequency ordering is permitted only when explicitly enabled and group boundaries are distinct. Runtime cache values always take precedence.

当前已核实的代码包括 SM8350 / SM8450 / SM8550 / SM8650 / SM8750 / SM8850 及所列变体，MT6989 和 MT6991。其他条目保留名称匹配，尚未核实的 MT 代码不硬填。MT6991 的相关时钟变体可能共用代码；缓存回退仍须核对核心组。

Verified codes currently include SM8350/SM8450/SM8550/SM8650/SM8750/SM8850 and the listed variants, plus MT6989 and MT6991. Other entries support name matching without assigning unverified MT codes. Related MT6991 clock variants may share a code; cache fallback still requires matching core groups.

`null` 表示尚未确认，不表示硬件不存在。尤其需要区分 CPU L3、系统级缓存 SLC、GPU 缓存和 Adreno GMEM。Arm 的 MC 数量记录为 shader cores，不转换成 ALU 或 slice 数量。当前未核实的 ALU、slice、GPU 缓存容量均为空。玄戒 O1 的 CPU 总数和 GPU 核心数已收录；尚未取得一手容量依据的 L2 / L3 保持空值。

`null` means unconfirmed, not absent hardware. CPU L3, system-level cache (SLC), GPU caches and Adreno GMEM are distinct. Arm MC counts are recorded as shader cores, not converted into ALUs or slices. Unverified ALU counts, slice counts and GPU cache capacities remain null. XRING O1 includes CPU and GPU core counts; L2/L3 capacities remain null pending primary evidence.

## 来源 / Sources

每个条目含身份、缓存或 GPU 来源链接及核对日期。主要来源如下。

Entries include identity, cache or GPU source links and a review date. Principal sources are:

- [Qualcomm QAIRT supported platforms](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372)：SM 编号与平台对应关系。 / SM codes and platform names.
- [Linux SM8650 device tree](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/sm8650.dtsi)：缓存容量、行大小及共享关系；其他高通条目仅采用各自设备树明确提供的字段。 / Cache sizes, line sizes and sharing; other Qualcomm entries use only fields present in their own device trees.
- [MediaTek product pages](https://www.mediatek.com/products/smartphones)：各产品的核心组合、已公布缓存及 GPU；完整链接在条目内。 / Core configurations, published caches and GPUs, with individual URLs in each entry.
- [Google LiteRT MediaTek support](https://developers.google.com/edge/litert/next/mediatek)：MT6989 / MT6991 对应关系。 / MT6989 and MT6991 mappings.
- [Arm Cortex-X925 TRM](https://documentation-service.arm.com/static/68374ea93f2acc596c2d9d93) 与 [Cortex-X4 TRM](https://documentation-service.arm.com/static/6473ef18bae17b773b05d1e8)：固定 L1D 容量和缓存行大小。A720 / A725 有多种 L1 配置，不仅凭核心名称填入容量。 / Fixed L1D capacities and line sizes. A720/A725 support multiple L1 configurations, so their names alone do not establish capacity.
- [Xiaomi 2025 Q1 results](https://ir.mi.com/static-files/f1dab4f8-8a84-4f23-a972-29012f08ace8) 与 [MiCode O1 CPU topology](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/0b426219a9a24b56c1593ebc84edb8a3f3b897b0/xring-dts/O1/soc/O1_cpu.dtsi)：玄戒 O1 核心规格与共享拓扑。 / XRING O1 core specifications and sharing topology.

补充条目时应分别核对容量、共享范围、单位和芯片变体，并附可追溯来源。不能把“支持的最大缓存”当成某台设备实际采用的缓存，也不能依据理论 FLOPS 倒推 ALU 数。

When adding entries, verify capacity, sharing scope, units and silicon variants separately, with traceable sources. A configurable maximum is not evidence of a device's actual cache, and theoretical FLOPS do not establish an ALU count.
