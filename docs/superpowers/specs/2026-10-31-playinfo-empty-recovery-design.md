# 播放信息空响应自愈：路径切换重试 + 两段式恢复 + 错误页诊断行

日期：2026-10-31
前置：docs/superpowers/specs/2026-10-29-bytevc2-ladder-design.md（device_level=1 已上线）；issue #38（0.1.12 播放信息接口返回空响应）。
分支：fix-playinfo-empty-recovery（从最新 main 拉出）。

## 背景

issue #38：用户看《春色韫韫，美人替嫁祁王》第 8 集时「播放信息接口返回空响应，已自动重试 3 次仍无法播放」。

用 `Probe.kt --issue38` 探针（沿用仓库既有 Probe 模式）在 Mac 上做了四轮共约 160 次真实请求，结论：

1. **已排除**：目标剧供给异常（5 档全下发）、`device_level=1` 回归（1/3 均正常）、单 host 故障（hl/lf/c 全通）、时钟偏差（skew=0）、旧分集 id（返回 `code=101000` JSON 而非空 body）、身份被持久标记（首轮失败身份次轮完全正常）。
2. **实测复现**：第一轮 8 个直连探测全部成功后，端到端 `streamLadder` 的 3 次空响应重试（700ms/1500ms）**全部落空**；随后约 145 发请求、跨 10 分钟零复现。空响应是**短暂的服务端异常窗口**，成簇出现，与内容、参数、身份无关。
3. **现有重试逃逸不了窗口**：空响应重试 3 次只跨约 2.2 秒，且复用同一连接、同一 host；上层 `RecoveryBudget` 的 3 次自动恢复（1s/2.5s/5s，每轮内又含 3 次同路径重试）也在约 15 秒内烧完。窗口稍长（秒级到分钟级），用户怎么按「立即重试」都是同一条路。

## 目标行为

1. 播放信息请求（multi_video_model）的空响应重试**每次切换路径**：轮换 host + 强制新建连接，退避跨约 7 秒。多数空响应窗口在第二次、第三次尝试时逃逸，用户只感到加载稍久。
2. 播放页自动恢复改**两段式**：快段（现有 3 次，1s/2.5s/5s）之后进入慢段（15s/30s/60s/90s/105s 共 5 次，累计恰 5 分钟）。窗口为分钟级时慢段仍能恢复，恢复成功自动续播并轻提示「已恢复播放」。
3. 慢段与终局文案诚实引导：慢段显示「服务暂时没有响应，正在自动恢复」；终局显示「本集暂时无法播放，可以先看其他内容，稍后再回来试试」。返回选集后重试停止（观看位置已保存，重新点开即续播）。
4. 错误页末尾新增一行**脱敏诊断**（机型 / Android 版本 / app 版本 / 失败阶段与尝试计数），用户照常截图反馈时每张截图自带定位信息。
5. 其余错误类别（解码降档、断网、睡眠模式）行为不变。

## 组件与改动

- **core**：`streamLadder()` 空响应重试从 `text(retryEmptyPlayback=true)` 的内部循环改为自有循环：host 阶梯 `sinfonlineb → hl → lf`（均经探针实测可用），每次重试换 host、加 `Connection: close`、刷新 `_rticket`；退避 700ms/1500ms/3000ms。抽出单次尝试的 `textOnce()` 供循环复用；`text()` 对其他调用方的行为不变。
- **core**：`EmptyPlaybackResponseException` 增加只读元数据字段（尝试次数、命中的 host 列表），消息文案不变；`playbackFailureSummary` 的匹配逻辑不受影响。
- **core**：`RecoveryBudget` 扩展两段式：快段 3 次 + 慢段 5 次，`delayMillis` 按段返回；新增 `phase` 与 `total` 供文案使用。`consume()/reset()` 语义不变。
- **app**：`scheduleRecovery()` 文案改为按预算动态生成（替换硬编码「3 次」）；快段沿用「准备恢复播放 · X 秒后重试 n/m」，慢段显示「服务暂时没有响应，正在自动恢复 · 第 n 次」；预算烧完显示终局文案。自动恢复成功且经历过慢段时弹「已恢复播放」Toast。
- **app**：错误页 `playbackText` 末尾拼接诊断行，素材取 `Build.MODEL`、`Build.VERSION.RELEASE`、`versionName`、`lastPlaybackFailure`、恢复尝试计数与 `EmptyPlaybackResponseException` 元数据；不含 Cookie、媒体地址与签名参数。
- **app**：抽一个纯函数构建恢复状态文案（供 Robolectric/纯 JUnit 测试），`MainActivity` 只做展示。

## 错误处理

- `Connection: close` 仅加在空响应重试上；正常单次请求与解码降档、断网分支零改动。
- 慢段重试在 `foreground` 判定内，退后台暂停（现有 `scheduleRecovery` 前置条件）；回前台与网络恢复钩子（`MainActivity.kt:176`、`:202`）自动续上慢段。
- 睡眠模式（`sleepStopped`）与「停止自动重试」按钮语义不变：点击后停止全部自动恢复，文案「观看位置已保留」。
- host 阶梯全部失败时异常携带元数据上抛，终局文案照常展示；不因诊断行引入新的失败路径（拼接全程 try/except 兜底）。

## 测试

- 自动化：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。
- core 单测：`RecoveryBudget` 两段式序列（快段 3 次耗尽进慢段、慢段 5 次、reset 复位）；重试计划纯函数（attempt→host 映射、退避序列、重试才加 `Connection: close`）；`EmptyPlaybackResponseException` 元数据断言。
- app 单测：恢复文案状态机（快段/慢段/终局/停止）；诊断行拼接与脱敏断言（不含 `Cookie`、`https://` 子串）。
- 探针回归：`--issue38` 随本分支保留（已验证价值，且与 Probe.kt 既有模式一致），用于上线后复现与对照。
- 真机验收（用户执行）：装 debug APK 后——
  1. 正常剧集播放回归不变；
  2. 飞行模式开/关验证「断网→联网自动续播」分支；
  3. 观察错误页（可用断网到代理拦空响应等方式制造）快段→慢段→终局文案与诊断行展示、恢复后「已恢复播放」提示。

## 风险与回退

- 慢段最多 5 次低频请求，服务端请求量增量可忽略；三 host 均为既有官方域名且探针实测可用。
- 诊断行只读公开设备信息（与 issue 截图习惯一致），无隐私新增。
- 回退 = revert 单个 PR；debug 期间 versionName/versionCode 保持 0.1.12 / 53，正式发版走统一发布入口。

## 不做的事

- 不做诊断二维码（错误页诊断行以零交互覆盖同等定位能力）。
- 不做跨页轮询/选集页「可续播」徽标（终局文案引导重新点开即可）。
- 不动设备注册、相关作品接口、`device_level`、bytevc2 过滤、`DecoderLessons`、自动降档计数。
- 不做身份轮换、时钟纠偏、UA/指纹对抗（实测均已排除或无必要）。
- 不为其他接口（注册、榜单等）扩展路径切换：#38 只发生在播放信息接口，注册失败是另一类故障，保持改动最小。
