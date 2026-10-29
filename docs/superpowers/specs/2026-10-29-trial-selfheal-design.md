# 首页试播失败自愈：解码经验学习 + 自动降档一次

日期：2026-10-29
前置：docs/superpowers/specs/2026-10-29-bytevc2-ladder-design.md（device_level=1 供给修复已提交于 fix-bytevc2-ladder）；本设计叠加在同一分支，随下次发版一起验收。
分支：fix-bytevc2-ladder（续用，不再另开分支）。

## 背景

用户反馈（2026-10-29）：首页推荐试播经常刷到「没有试播视频」（状态栏「暂时无法加载 · 该作品暂时没有可试播的剧集」）或「试播第一集」却播不出（「试播暂不可用」）。对推荐流三轮真实扫描证实两个成因：

1. **结构性**：`device_level=3` 下部分剧第一集 360P–720P 档全部为 bytevc2（被过滤），仅剩 1080P 竖屏（如 1080×1922）——盒子解不了，试播必失败。已由 device_level=1 修复（三轮扫描 L1 零结构性问题），试播与正剧共用同一取流代码，自动受益。
2. **残余缺口**：正剧播放失败会写入 `DecoderLessons` 并自动降档，但首页试播的 `TrialStatus.ERROR` **只显示错误，不学习、不自动降档**。盒子 `MediaCodecList` 虚报竖屏支持（静态放行 1080×1920、运行时失败），因此竖屏剧的试播会反复卡死在同一档位，除非正剧里恰好看过该剧教会了经验表。接口另有偶发空 `video_list` 波动（app 走旧版 fallback 接口兜底 + 手动重试基本可恢复，本次不处理）。

澄清：接口没有独立「试播资源池」，试播即该剧第一集（推荐 payload 自带试播集 id）。

## 目标行为

1. 试播播放失败且为解码类错误（PlaybackException 3000–4999）时，把失败流的「编码+宽×高」写入 `DecoderLessons`（与正剧共用经验表，正剧立即受益）。
2. 学习后自动重载该试播一次：重试经既有 `openStream`，能力谓词读到刚学的失败档位并跳过，自动落到更低档播放；重载期间状态栏提示「设备不支持该清晰度，正在自动切换…」。
3. 每部剧的试播至多自动自愈 1 次（会话内按剧记忆）；仍失败则维持现有「试播暂不可用」面板与手动「重试试播」。
4. 非解码类错误（网络/IO 等）或轨道信息缺失（无法定位学习键，如 moov 解析失败 track==null）时：不学习、不自愈，行为不变。

## 组件与改动（全部 app 层，core 不动）

- `FeedPlayback.kt`：`TrialClip` 增加 `track: VideoTracks.Track?` 字段；`PublicFeedBackend.open()` 改为返回 `OpenedStream`（调用处 `prepare()`/`trial()` 用 `opened.video` + `opened.track` 构造 TrialClip）。
- `TrialPlayback` 接口：新增 `var onError: ((PlaybackException) -> Unit)?`（接口默认访问器为 no-op，不破坏测试 fake）；`Media3TrialPlayback` 实现存储，并在 `onPlayerError` 里先 `onError?.invoke(error)` 再 `status?.invoke(ERROR)`（保证上层处理 ERROR 时已拿到错误码）。
- `FeedPlayback.kt` 新增纯函数 `shouldSelfHealTrial(errorCode: Int?, hasTrack: Boolean, alreadyHealed: Boolean): Boolean`：解码错误范围 + 有轨道 + 未自愈过。
- `HomeFeedScreen`：记录 `lastPlaybackError`（onError 回调）与 `healedFor: String?`（上次自愈的剧 id）；ERROR 分支先尝试自愈——满足条件则 `CodecCapabilities.learnFailure(track.fourcc, track.width, track.height)`、置 `healedFor`、提示文案并 `loadCurrent()` 重载；否则走现有 failed 面板。
- 单测：`TrialSelfHealTest` 覆盖判定各分支（解码/网络错误、有/无轨道、已/未自愈）。
- README（kotlin-tv）：试播段落补自愈行为说明。

## 错误处理

- 自愈重载复用 `loadCurrent()` 全流程（缓存/预取优先，等同手动重试的成本），每剧限 1 次不放大请求。
- 学习键与正剧一致（编码+编码尺寸），横竖屏互不牵连（既有机制）。
- `healedFor` 为单字段，会话内换剧自动失效，无内存增长。

## 测试

- 自动化：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`；既有 `HomeFeedScreenTest` fake 实现不破坏（接口默认访问器）。
- 真机验收（用户执行）：刷到竖屏剧首次试播失败 → 状态栏「正在自动切换…」→ 自动落 540P/480P 播放（logcat `Learned decoder failure`）；同剧第二次试播直接低档、无失败感；正剧横屏 1080P 回归不受影响。

## 不做的事

- 不做 inline + fallback_api 合并取流（接口偶发空 video_list 由既有 fallback + 手动重试兜底，验收后视残余再议）。
- 不改 core 取流与选流逻辑；不给试播加多级自动降档循环（每次试播限 1 次）。
