# 修复部分剧集仅剩不可解码档位：device_level=1 + 诚实降档

日期：2026-10-29
前置：docs/superpowers/specs/2026-10-24-portrait-decode-design.md、docs/superpowers/specs/2026-10-24-portrait-auto-design.md（能力感知选流 + 解码失败自学习已上线）。
分支：fix-bytevc2-ladder（从最新 main 拉出）。

## 背景

用户真机反馈（2026-10-29）：剧集《心动处方：总裁请就诊第三季》（hongguoduanju.com/player/7699056933554097214）出现**隔集交替失败**——第 1、3 集能播，第 2、4 集反复尝试且手动调 720/480 清晰度仍显示无法播放。

用 core 模块在 Mac 上直接复现取流链路（设备注册 → 签名 → multi_video_model → 逐档 prepare + moov 解析 + 分段读取）证实：

1. 该剧所有档位均为竖屏 HEVC。正常集（EP1/3/5/6）360P–1080P 五档全是 `bytevc1`（H.265），自动降档到 540P/480P 后可播（既有修复生效）。
2. 问题集（EP2/EP4）在请求参数 `device_level=3`（app 现值）下，**360P–720P 四档全部返回 `bytevc2`**（字节新一代编码，Android MP4 提取器读不出视频轨，core 解析按既有规则过滤），仅剩 1080P `bytevc1` 一档；EP2 的 1080P 还是异常的 1080×1922（比正常高 2 像素）。
3. 失败链条：bytevc2 被过滤 → 阶梯只剩 1080P → 竖屏 1080 超出电视解码上限 → 无低档可自动降级；用户点「尝试 720P/480P」时阶梯里根本没有这些档位，`StreamLadder.select()` 静默回落到 1080P 重放同一流，怎么调都失败。
4. 同请求改 `device_level=1`（或 4）：问题集全部档位恢复 `bytevc1`；对照正常集（EP1/EP5）供给与 `device_level=3` **完全一致**（同档位、同编码、同 CDN 主机）。端到端验证 EP2 540×962、EP4 540×960 均真实可播。

## 目标行为

1. 播放接口（multi_video_model）请求统一使用 `device_level=1`：问题集低档位恢复为可解码的 bytevc1，自动降档路径（含 DecoderLessons 经验表）恢复有效；正常集供给不变。当前集与预取的下一批请求同时生效。
2. 错误面板「尝试 NP」按钮仅在当前集阶梯**确实存在**严格更低档位时显示；按钮目标取阶梯中低于已尝试档位的最低档（而非固定清晰度选项表），点击后实际播放的就是按钮所写的档位。
3. 解码失败且阶梯无更低档时，文案明确提示「本集没有更低清晰度可用」，不再引导无效循环重试。
4. core 的 bytevc2 过滤逻辑保留不动，作为其他来源的防御。

## 组件与改动

- **core**：`ContentRepository.streamLadder()` 的 payload 中 `"device_level":3` → `"device_level":1`（一行）。其余解析、过滤、降档序列不动。
- **app**：`PlaybackSources.nextLowerQuality()` 增加阶梯参数（纯函数，签名改为接收 `tiers: List<Rendition>`）：返回阶梯中小于已尝试档位的最低档 quality，无则返回 null；不再基于 `PlaybackQuality.options` 猜测。
- **app**：`MainActivity.playerFailure()` 用 `currentTiers` 调用新签名；无更低档时不添加按钮，解码类错误的文案区分「可尝试更低清晰度」与「本集没有更低清晰度可用」。
- **app 测试**：`nextLowerQuality` 新语义单测（有更低档、无更低档、阶梯档位不在固定选项表内的情形）。

## 错误处理

- 自动降档重试（`shouldAutoDowngrade`）已自带「存在严格更低档」判断，本次不改；面板按钮与文案是仅有的 UI 改动。
- 预取与 `openStream` 主流程不变；`device_level` 变更对批量请求（当前集 + 后两集）整体生效，无按集分支。

## 测试

- 自动化：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`；若 `ContentRepositoryTest` 存在对请求 payload 的断言，同步更新为 `device_level=1`。
- 真机验收（用户执行）：装 debug APK 后——
  1. 播该剧第 2、4 集：预期自动落在 540P/480P 正常播放，不再出现「无法播放」死局；
  2. 回归第 1、3 集：行为不变；
  3. 抽查一部横屏剧 1080P：供给与画质不变。

## 风险与回退

- 若未来发现某剧在 `device_level=1` 下供给反而缩减（本次全部探针未出现），升级为「level=3 为主 + level=1 兜底重取」；诊断探针补丁存档于 `/tmp/probe-alt-source.patch`（`--alt-source`/`--raw-rows`/`--episode-ladder` 模式），可快速复用定位。
- 极端情况：某集全部低档在 level=1 下仍为 bytevc2 → 由「诚实降档」UI 兜底给出明确提示，不再无限重试。

## 不做的事

- 不做「level=3 为主 + 兜底重取」（除非真机回归出现供给反例）。
- 不把探针模式纳入 `Probe.kt` 正式代码（补丁已存档，需要时再议）。
- 不动 bytevc2 过滤、`DecoderLessons`、自动降档计数、`StreamLadder.select()` 回落逻辑。
- 版本号保持 0.1.11 / versionCode 52 不变；debug APK 按文件名区分；正式发版另行走统一发布入口。
