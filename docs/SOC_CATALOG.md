# 内部 SoC 资料库 / Internal SoC catalog

资料库位于 `app/src/main/assets/soc_catalog.json`，修订号 `2026-09-30.2`。本次从 17 个条目扩充到 142 个，覆盖 2020 年以来常见安卓手机使用的平台，兼顾入门、中端和旗舰，也包括同期机型沿用的少量早期芯片。

The catalog is stored in `app/src/main/assets/soc_catalog.json`, revision `2026-09-30.2`. This revision expands 17 entries to 142, covering platforms used in common Android phones since 2020 across entry, mid-range and flagship devices, including some earlier chips retained in those phones.

资料库只补充设备运行时未提供的资料。未收录的 SoC 仍可运行测试；收录某个型号也不表示其所有缓存和 GPU 规格都已确认。界面不展示规格数据库，成绩来自实际执行。

The catalog supplements missing runtime metadata. Unlisted SoCs can still run tests, and a listed model does not imply that every cache or GPU specification is confirmed. The interface does not expose this database; scores come from executed measurements.

| 厂商 / Vendor | 条目 / Entries | 覆盖范围 / Coverage |
|---|---:|---|
| Qualcomm | 45 | Snapdragon 460 / 480，662 / 680 / 685 / 690 / 695，720G 至 782G，4 / 6 / 7 系 Gen 平台及 8 系 / numbered and Gen platforms |
| MediaTek | 65 | Helio G25 至 G200；早期天玑、6000 / 7000 / 8000 / 9000 系 / Helio, early Dimensity and subsequent series |
| Samsung | 15 | Exynos 850 / 880 / 980 / 990 / 1080，1280 至 1680，2100 至 2500 / selected Exynos platforms |
| UNISOC | 11 | T606 / T612 / T616，T7200 / T7250 / T7255 / T7280，T8100 / T8200 / T8300 / T9100 |
| HiSilicon | 3 | Kirin 820 / 985 / 9000 |
| Google | 1 | Tensor / GS101 |
| Xiaomi | 2 | XRING O1 / O3 |

完整型号、代号与来源见下方清单和 JSON。系列范围不表示其中每一个型号均已收录。

The inventory below and the JSON contain exact models, codes and sources. A series range does not imply coverage of every model within it.

## 本次指定的平台 / Specifically requested platforms

| 平台 / Platform | 识别 / Identity | 已确认信息 / Confirmed metadata |
|---|---|---|
| XRING O3 / 玄戒 O3 | XRING O3、Xiaomi XRING O3、玄戒 O3 | 10 个 CPU 核心：2 Ultra + 4 Premium + 4 Pro；16 核 G2-Ultra NX GPU / ten CPU cores and sixteen GPU shader cores |
| Snapdragon 8 Elite Extreme Gen 6 | SM8975、8EE6 | 2 Prime + 6 Performance；Adreno GPU |
| Snapdragon 8 Elite Gen 6 | SM8950、8E6 | 2 Prime + 6 Performance；Adreno GPU |

两颗新骁龙的 16 MB Oryon Flex Cache 按厂商原始名称单独记录，未归类为 CPU L2 / L3。玄戒 O3 未取得一手容量与共享关系证据的缓存字段保持空值。没有将 GPU MC、WGP、shader engine、ALU 或 slice 互相换算。

The new Snapdragon platforms' 16 MB Oryon Flex Cache is recorded under its published name, without assigning it to CPU L2 or L3. XRING O3 cache capacities and sharing relationships remain null without primary evidence. GPU MC counts, WGPs, shader engines, ALUs and slices are not converted into one another.

## 匹配与回退 / Matching and fallback

- 使用系统报告的 SoC 名称、硬件字段及明确别名；忽略大小写、空白与标点，保留中文和 `+`。不按数字前缀猜测型号，也不自动去掉 Ultra / Max / Turbo 后缀。
  Match reported SoC names, hardware fields and explicit aliases. Ignore case, whitespace and punctuation, while retaining Chinese characters and `+`. Numeric prefixes do not identify a model, and Ultra/Max/Turbo suffixes are not removed automatically.
- 一个基础代号可能对应多个变体。例如，只有 `SM6225` 时不选定 680 或 685；同时提供 `Snapdragon 685` 或完整代号 `SM6225-AD` 才能消歧。已识别字段相互冲突时不匹配。
  A base code may cover several variants. `SM6225` alone does not select 680 or 685; an explicit `Snapdragon 685` name or complete `SM6225-AD` code resolves the ambiguity. Conflicting recognized identifiers reject a match.
- 型号名与 SM / MT 编号分别核实。多数新增天玑和 Helio 条目目前按完整名称匹配，未核实的 MT 编号不填入。三星和展锐也不依靠数字后缀或相似名字猜测内部代号。
  Verify model names and SM/MT codes independently. Most new Dimensity and Helio entries currently match full names; unverified MT codes are omitted. Samsung and UNISOC internal codes are likewise not inferred from numeric suffixes or similar names.
- 缓存回退要求总核心数一致。未知核心数保留 `null`，使用运行时拓扑；已确认但不符的核心数也不会触发缓存补全。核心组优先使用 MIDR，只有明确允许且频率边界清楚时才按频率排序映射。
  Cache fallback requires a matching total core count. An unconfirmed count stays null and leaves runtime topology intact; a confirmed but mismatched count also prevents fallback. Core groups prefer MIDR, with frequency ordering allowed only when explicitly enabled and boundaries are distinct.
- 运行时缓存值优先，缺失规格保持未知。本次增加了天玑 8450 / 8500 的已公布 L2 / L3 / SLC 资料；L1D 存在可配置容量，未据核心名称填写固定值。SLC 与 CPU L3 分开保存。
  Runtime cache values take precedence, and missing specifications remain unknown. This revision adds published Dimensity 8450/8500 L2/L3/SLC metadata. Configurable L1D capacities are not inferred from core names. SLC and CPU L3 remain separate.

## 来源和维护 / Sources and maintenance

条目提供身份、CPU、缓存和 GPU 来源及核对日期。高通使用产品简报，联发科使用产品页与官方发布稿；三星和展锐使用产品规格；麒麟使用华为规格页；初代 Tensor 使用上游设备树。玄戒 O3 的来源为小米发布稿，由 TAP Magazine 转载。

Entries include identity, CPU, cache and GPU references and a review date. Sources include Qualcomm product briefs, MediaTek product pages and announcements, Samsung and UNISOC specifications, Huawei specifications for Kirin, and the upstream device tree for the original Tensor. XRING O3 uses a Xiaomi press release reproduced by TAP Magazine.

- [8EE6 产品简报 / product brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Extreme-Gen-6-Product-Brief.pdf)
- [8E6 产品简报 / product brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Gen-6-Product-Brief.pdf)
- [小米 XRING O3 发布稿 / Xiaomi XRING O3 release](https://tapmag.ph/xiaomi-unveils-xiaomi-18-fold-the-first-xiaomi-flagship-featuring-xiaomi-xring-o3/)
- [Cortex-A725 TRM](https://documentation-service.arm.com/static/665741a0876c8d213b785bf5)：可配置 L1D 和 L2 容量、64 字节缓存行及私有 L2 关系。 / Configurable L1D/L2 sizes, 64-byte lines and private L2 scope.
- [Linux CPU identifiers](https://github.com/torvalds/linux/blob/master/arch/arm64/include/asm/cputype.h)：Arm MIDR part 定义。 / Arm MIDR part definitions.

维护时分别检查型号、核心数量、容量、单位和共享范围。`null` 表示未确认，不表示硬件不存在。宣传资料中的总缓存、GPU 高速内存或“最大支持容量”不得直接作为某一级 CPU 缓存的工作集依据。

Review model identity, core counts, capacity, units and sharing scope separately. Null means unconfirmed, not absent. Advertised aggregate cache, GPU high-performance memory or maximum supported capacity must not define a specific CPU cache working set.

```sh
python tools/validate-soc-catalog.py
```

校验脚本检查条目唯一性、核心组总数、字段范围、来源和共用别名。设备用例 `SocCatalogTest` 验证主流型号、新平台、别名冲突、未知规格及缓存覆盖规则。

The validator checks unique entries, core-group totals, field ranges, source references and shared aliases. Device cases in `SocCatalogTest` cover mainstream models, requested platforms, identity conflicts, unconfirmed data and cache fallback rules.

## 完整型号清单 / Complete inventory

代号栏只列已收录的芯片编号；`名称 / name` 表示当前使用明确型号名识别。CPU 核数为 `—` 时不启用数据库缓存回退。表内列出主要来源，完整字段来源保存在 JSON 中。

The code column lists recorded silicon identifiers; `名称 / name` means matching currently uses explicit model names. A dash in the CPU column disables catalog cache fallback. The table links primary references; per-field references are retained in JSON.

| 平台 / Platform | 代号 / Codes | CPU cores | GPU | 来源 / Source |
| ---|---|---:|---|--- |
| Snapdragon 888 | SM8350, SM8350-AB | 8 | Adreno 660 | [source](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372) |
| Snapdragon 8 Gen 1 | SM8450, SM8450-AB | 8 | Adreno 730 | [source](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372) |
| Snapdragon 8 Gen 2 | SM8550, SM8550-AB | 8 | Adreno 740 | [source](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372) |
| Snapdragon 8 Gen 3 | SM8650, SM8650-AA, SM8650-AB, SM8650-AC | 8 | Adreno 750 | [source](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372) |
| Snapdragon 8 Elite | SM8750, SM8750-AB, SM8750-AC | 8 | Adreno | [source](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-mobile-platform) |
| Snapdragon 8 Elite (7-core) | SM8750, SM8750-3-AB | 7 | Adreno | [source](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-mobile-platform) |
| Snapdragon 8 Elite Gen 5 | SM8850, SM8850-1-AD, SM8850-AC | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Gen-5-product-brief.pdf) |
| Dimensity 8200 | 名称 / name | 8 | Mali-G610 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8200) |
| Dimensity 8300 | 名称 / name | 8 | Mali-G615 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8300) |
| Dimensity 8400 | 名称 / name | 8 | Mali-G720 (7) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8400) |
| Dimensity 9000 | 名称 / name | 8 | Mali-G710 (10) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9000) |
| Dimensity 9200 | 名称 / name | 8 | Immortalis-G715 | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9200) |
| Dimensity 9300 | MT6989 | 8 | Immortalis-G720 (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9300) |
| Dimensity 9400 | MT6991 | 8 | Immortalis-G925 (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9400) |
| Dimensity 9400+ | 名称 / name | 8 | Immortalis-G925 (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9400-plus) |
| Dimensity 9500 | 名称 / name | 8 | Mali-G1 Ultra (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9500) |
| XRING O1 | 名称 / name | 10 | Immortalis-G925 (16) | [source](https://ir.mi.com/static-files/f1dab4f8-8a84-4f23-a972-29012f08ace8) |
| Snapdragon 460 | SM4250, SM4250-AA | 8 | Adreno 610 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/prod_brief_qcom_sd460_1.pdf) |
| Snapdragon 480 | SM4350 | 8 | Adreno 619 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/snapdragon-480-5g-mobile-platform-product-brief.pdf) |
| Snapdragon 480+ | SM4350, SM4350-AC | 8 | Adreno 619 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/product_brief_-_snapdragon_480_plus_5g_mobile_platform.pdf) |
| Snapdragon 4 Gen 1 | SM4375 | 8 | Adreno 619 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/09162022_Prod_Brief_QCOM_SD_4_Gen_1.pdf) |
| Snapdragon 4 Gen 2 | SM4450 | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-64330-1/87-64330-1_REV_C_Snapdragon_4_Gen2_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 4s Gen 2 | SM4635 | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-78935-1/87-78935-1_REV_C_Snapdragon_4s_Gen_2_Mobile_Platform_Product_Brief____.pdf) |
| Snapdragon 662 | SM6115 | 8 | Adreno 610 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/snapdragon-662-mobile-platform-product-brief.pdf) |
| Snapdragon 680 | SM6225 | 8 | Adreno 610 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/product_brief_-_snapdragon_680_4g_mobile_platform.pdf) |
| Snapdragon 685 | SM6225, SM6225-AD | 8 | Adreno 610 | [source](https://docs.qualcomm.com/doc/87-43683-1/87-43683-1_REV_C_Snapdragon_685_4G_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 690 | SM6350 | 8 | Adreno 619L | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/prod_brief_qcom_sd690_5g.pdf) |
| Snapdragon 695 | SM6375 | 8 | Adreno 619 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/product_brief_-_snapdragon_695_5g_mobile_platform.pdf) |
| Snapdragon 6 Gen 1 | SM6450 | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/09162022_Prod_Brief_QCOM_SD_6_Gen_1.pdf) |
| Snapdragon 6 Gen 3 | SM6475, SM6475-AB | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-82624-1/87-82624-1_REV_A_Snapdragon_6_Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 6 Gen 4 | SM6650 | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-78937-1/87-78937-1_REV_A_Snapdragon_6_Gen_4_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 6s Gen 3 | SM6370, SM6375, SM6375-AC | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-75277-1/87-75277-1_REV_A_Snapdragon_6s_Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 720G | SM7125 | 8 | Adreno 618 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/qualcomm_snapdragon_720g_mobile_platform_product_brief_0.pdf) |
| Snapdragon 732G | SM7150, SM7150-AC | 8 | Adreno 618 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/qualcomm-snapdragon-732g-mobile-platform-product-brief.pdf) |
| Snapdragon 750G | SM7225 | 8 | Adreno 619 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/snapdragon_750g_5g_mobile_platform_product_brief_0.pdf) |
| Snapdragon 768G | SM7250, SM7250-AC | 8 | Adreno 620 | [source](https://www.qualcomm.com/media/documents/files/snapdragon-768g-product-brief.pdf) |
| Snapdragon 778G | SM7315, SM7325 | 8 | Adreno 642L | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-778G-5G-Product-Brief.pdf) |
| Snapdragon 778G+ | SM7325, SM7325-AE | — | Adreno 642L | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-778G-plus-5G_Product-Brief_Update.pdf) |
| Snapdragon 780G | SM7350, SM7350-AB | 8 | Adreno 642 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/snapdragon_780g_product_brief.pdf) |
| Snapdragon 782G | SM7325, SM7325-AF | — | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-782G-Product-Brief.pdf) |
| Snapdragon 7 Gen 1 | SM7425, SM7450, SM7450-0-AB, SM7450-1-AB | 8 | Adreno 644 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-7-Gen-1-Product-Brief.pdf) |
| Snapdragon 7 Gen 3 | SM7550, SM7550-AB | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-64372-1/87-64372-1_REV_B_Snapdragon_7_Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 7 Gen 4 | SM7750, SM7750-AB | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-7-Gen-4-Mobile-Platform-Product-Brief.pdf) |
| Snapdragon 7s Gen 2 | SM7435, SM7435-AB | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-64361-1/87-64361-1_REV_B_Snapdragon_7s_Gen_2_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 7s Gen 3 | SM7635 | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-78936-1/87-78936-1_REV_A_Snapdragon_7s_Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 7s Gen 4 | SM7635, SM7635-AC | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-7s-Gen-4-product-brief.pdf) |
| Snapdragon 7+ Gen 2 | SM7475, SM7475-AB | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-43682-1/87-43682-1_REV_B_Snapdragon_7__Gen_2_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 7+ Gen 3 | SM7675, SM7675-AB | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-73943-1/87-73943-1_REV_E_Snapdragon_7__Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 865 | SM8250 | 8 | Adreno 650 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/prod_brief_qcom_sd865_5g.pdf) |
| Snapdragon 870 | SM8250, SM8250-AC | 8 | Adreno 650 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/prod_brief_qcom_sd870_5g.pdf) |
| Snapdragon 8+ Gen 1 | SM8425, SM8475 | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-plus-Gen-1-Product-Brief.pdf) |
| Snapdragon 8s Gen 3 | SM8635 | 8 | Adreno | [source](https://docs.qualcomm.com/doc/87-73942-1/87-73942-1_REV_D_Snapdragon_8s_Gen_3_Mobile_Platform_Product_Brief.pdf) |
| Snapdragon 8s Gen 4 | SM8735 | 8 | Adreno 825 | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Product-Brief-Snapdragon-8s-Gen-4.pdf) |
| Snapdragon 8 Elite Extreme Gen 6 | SM8975 | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Extreme-Gen-6-Product-Brief.pdf) |
| Snapdragon 8 Elite Gen 6 | SM8950 | 8 | Adreno | [source](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Gen-6-Product-Brief.pdf) |
| Dimensity 6020 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-6020) |
| Dimensity 6080 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-6080) |
| Dimensity 6100+ | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-6100plus) |
| Dimensity 6300 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-6300) |
| Dimensity 6400 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-6400) |
| Dimensity 7020 | 名称 / name | 8 | IMG BXM-8-256 | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7020) |
| Dimensity 7025 | 名称 / name | 8 | IMG BXM-8-256 | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7025) |
| Dimensity 7030 | 名称 / name | 8 | Mali-G610 (3) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7030) |
| Dimensity 7050 | 名称 / name | 8 | Mali-G68 (4) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7050) |
| Dimensity 7200 | 名称 / name | 8 | Mali-G610 (4) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7200) |
| Dimensity 7300 | 名称 / name | 8 | Mali-G615 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7300) |
| Dimensity 7300x | 名称 / name | 8 | Mali-G615 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7300x) |
| Dimensity 7350 | 名称 / name | 8 | Mali-G610 (4) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7350) |
| Dimensity 7400 | 名称 / name | 8 | Mali-G615 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400) |
| Dimensity 7400x | 名称 / name | 8 | Mali-G615 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400x) |
| Dimensity 8000 | 名称 / name | 8 | Mali-G610 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8000) |
| Dimensity 8020 | 名称 / name | 8 | Mali-G77 (9) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8020) |
| Dimensity 8050 | 名称 / name | 8 | Mali-G77 (9) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8050) |
| Dimensity 8100 | 名称 / name | 8 | Mali-G610 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8100) |
| Dimensity 8250 | 名称 / name | 8 | Mali-G610 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8250) |
| Dimensity 8350 | 名称 / name | 8 | Mali-G615 (6) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8350) |
| Dimensity 8450 | 名称 / name | 8 | Mali-G720 (7) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8450) |
| Dimensity 8500 | 名称 / name | 8 | Mali-G720 (8) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8500) |
| Dimensity 9000+ | 名称 / name | 8 | Mali-G710 (10) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9000plus) |
| Dimensity 9200+ | 名称 / name | 8 | Immortalis-G715 | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9200plus) |
| Dimensity 9300+ | 名称 / name | 8 | Immortalis-G720 (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9300-plus) |
| Dimensity 9400e | 名称 / name | 8 | Immortalis-G720 (12) | [source](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9400e) |
| Helio G100 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g100) |
| Helio G200 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g200) |
| Helio G25 | 名称 / name | 8 | IMG PowerVR GE8320 | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g25) |
| Helio G35 | 名称 / name | 8 | IMG PowerVR GE8320 | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g35) |
| Helio G36 | 名称 / name | 8 | IMG PowerVR GE8320 | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g36) |
| Helio G70 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g70) |
| Helio G80 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g80) |
| Helio G81 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g81) |
| Helio G85 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g85) |
| Helio G88 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g88) |
| Helio G91 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g91) |
| Helio G92 | 名称 / name | 8 | Mali-G52 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g92) |
| Helio G95 | 名称 / name | 8 | Mali-G76 (4) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g95) |
| Helio G96 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g96) |
| Helio G99 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/smartphones/mediatek-helio-g99) |
| Dimensity 700 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.mediatek.com/products/laptops-and-tablets/tablets/mediatek-dimensity-700) |
| Dimensity 900 | 名称 / name | 8 | Mali-G68 (4) | [source](https://www.mediatek.com/products/laptops-and-tablets/tablets/mediatek-dimensity-900) |
| Dimensity 720 | 名称 / name | 8 | Mali-G57 | [source](https://www.mediatek.com/press-room/mediatek-announces-dimensity-720-its-newest-5g-chip-for-premium-5g-experiences-on-mid-tier-smartphones) |
| Dimensity 800 | 名称 / name | 8 | Mali-G57 (4) | [source](https://i.mediatek.com/dimensity-800) |
| Dimensity 800U | 名称 / name | 8 | Mali-G57 (3) | [source](https://i.mediatek.com/dimensity-800u) |
| Dimensity 810 | 名称 / name | 8 | Mali-G57 (2) | [source](https://i.mediatek.com/dimensity-810) |
| Dimensity 820 | 名称 / name | 8 | Mali-G57 (5) | [source](https://newsletter.mediatek.com/hubfs/mediatek5gprogress/Dimensity-5G%20progress-info/820_Infographic.pdf?hsLang=en) |
| Dimensity 920 | 名称 / name | 8 | Mali-G68 (4) | [source](https://i.mediatek.com/dimensity-920) |
| Dimensity 930 | 名称 / name | 8 | IMG BXM-8-256 | [source](https://i.mediatek.com/dimensity-930) |
| Dimensity 1000+ | 名称 / name | 8 | Mali-G77 (9) | [source](https://i.mediatek.com/dimensity-1000-plus) |
| Dimensity 1080 | 名称 / name | 8 | Mali-G68 (4) | [source](https://i.mediatek.com/dimensity-1080) |
| Dimensity 1100 | 名称 / name | 8 | Mali-G77 (9) | [source](https://www.mediatek.com/press-room/mediatek-launches-6nm-dimensity-1200-flagship-5g-soc-with-unrivaled-ai-and-multimedia-for-powerful-5g-experiences) |
| Dimensity 1200 | 名称 / name | 8 | Mali-G77 (9) | [source](https://i.mediatek.com/dimensity-1200) |
| Dimensity 1300 | 名称 / name | 8 | Mali (9) | [source](https://www.mediatek.com/hubfs/Dimensity-1300-Infographic.pdf) |
| Exynos 850 | 名称 / name | 8 | Mali-G52 (1) | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-850/) |
| Exynos 880 | 名称 / name | 8 | Mali-G76 (5) | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-880/) |
| Exynos 980 | 名称 / name | 8 | Mali-G76 (5) | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-980/) |
| Exynos 990 | 名称 / name | 8 | Mali-G77 (11) | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-990/) |
| Exynos 1080 | 名称 / name | 8 | Mali-G78 (10) | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1080/) |
| Exynos 1280 | 名称 / name | 8 | Mali-G68 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1280/) |
| Exynos 1330 | 名称 / name | 8 | Mali-G68 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1330/) |
| Exynos 1380 | 名称 / name | 8 | Mali-G68 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1380/) |
| Exynos 1480 | 名称 / name | 8 | Xclipse 530 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1480/) |
| Exynos 1580 | 名称 / name | 8 | Xclipse 540 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1580/) |
| Exynos 1680 | 名称 / name | 8 | Xclipse 550 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1680/) |
| Exynos 2100 | 名称 / name | 8 | Mali-G78 | [source](https://news.samsung.com/global/samsung-sets-new-standard-for-flagship-mobile-processors-with-exynos-2100) |
| Exynos 2200 | 名称 / name | 8 | Xclipse | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2200/) |
| Exynos 2400 | 名称 / name | 10 | Xclipse 940 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/) |
| Exynos 2500 | 名称 / name | 10 | Xclipse 950 | [source](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2500/) |
| T7200 | 名称 / name | 8 | Mali-G57 (1) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T7200) |
| T7250 | 名称 / name | 8 | Mali-G57 | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T7250) |
| T7255 | 名称 / name | 8 | Mali-G57 (1) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T7255) |
| T7280 | 名称 / name | 8 | Mali-G57 | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T7280) |
| T8100 | 名称 / name | 8 | Mali-G57 (4) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T8100) |
| T8200 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T8200) |
| T8300 | 名称 / name | 8 | Mali-G57 (2) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T8300) |
| T9100 | 名称 / name | 8 | Mali-G57 (4) | [source](https://www.unisoc.com/en/product/SmartPhoneUS/T9100) |
| T612 | 名称 / name | 8 | Mali-G57 | [source](https://www.realme.com/ph/realme-note-60x/specs) |
| T616 | 名称 / name | 8 | Mali-G57 | [source](https://www.realme.com/global/realme-c35/specs) |
| T606 | 名称 / name | — | — | [source](https://www.hmd.com/en_gb/hmd-pulse-plus/specs) |
| Kirin 820 | 名称 / name | 8 | Mali-G57 | [source](https://consumer.huawei.com/th/offer/shopee/nova7-se/specs/) |
| Kirin 985 | 名称 / name | 8 | Mali-G77 | [source](https://consumer.huawei.com/th/offer/lazada/nova7/specs/) |
| Kirin 9000 | 名称 / name | 8 | Mali-G78 (24) | [source](https://consumer.huawei.com/th/offer/shopee/mate40-pro/specs/) |
| Tensor | GS101 | 8 | — | [source](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/exynos/google/gs101.dtsi) |
| XRING O3 | 名称 / name | 10 | G2-Ultra NX (16) | [source](https://tapmag.ph/xiaomi-unveils-xiaomi-18-fold-the-first-xiaomi-flagship-featuring-xiaomi-xring-o3/) |
