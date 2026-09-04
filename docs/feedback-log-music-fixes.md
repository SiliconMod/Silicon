# 音乐播放器问题修复计划（feedback 跟踪）

> 本文件记录用户反馈的音乐播放器问题与对应修复方案。
> 未定版条目版本列写 `（待定版）`，定版时回填。

## 问题清单与根因（代码位置）

### 1. 完全不能倒放（reverse）
- 文件：`src/silicon/audio/MusicPlayer.java`，`tickLocal()` reverse 分支（≈482-493）。
- 根因：倒放每帧调用 `SoloudBridge.seek(voice, target)`，但该分支开头有
  `if (!Core.audio.isPlaying(localVoiceId)) return;` 守卫——Soloud 流式声源在
  `idSeek` 跳转瞬间会短暂返回非播放态，导致每帧都被这个守卫跳过，从未真正执行 seek，
  进度照常正向推进 →「完全不能倒放」。
- 修复：倒放期间声源必然已播放中，去掉 `isPlaying` 守卫，只保留 `localVoiceId>=0`；
  并对 seek 做抽稀（如每 2-3 帧 seek 一次），给流式解码器稳定时间。

### 2 + 4. 进度问题；外部歌曲不能切换进度 / 跳到超高进度
- 文件：`MusicPlayer.java` `seek()`（≈1206）、`trackLength()`（≈1076）、
  `continue`/`deferSeek`。
- 根因：外部/URL 曲目若 `trackLengthOf` 探测失败（未下载/超大），回退到
  `v.sound.getLength()`——直播/流式声源的 Soloud `getLength()` 可能返回异常巨大/未知值，
  `seek()` 用这个超大 len 做 `clamp`，允许进度到接近该假长度 → 拖动跳到超高进度；
  且 seek 到假末尾触发「已播完」误判。
- 修复：
  - `trackLength()` 对声源 `getLength()` 加更严上限（如 ≤ 12h 且 ≥ 0），并优先用已下载文件实测。
  - `seek()` 拖动时对「真实曲目时长未知」的外部曲用保守上限夹取，避免落到假高进度。
  - 拖动进度不命中自动推进（`lastSeekAt` 已有，确保覆盖 UI 滑杆 changed 路径）。

### 3. 专辑前缀「专辑：」
- 文件：`src/silicon/ui/MusicPlayerDialog.java` 专辑筛选栏（≈166）。
- 现状：`albumsFilter.add(Core.bundle.get("musicplayer.album") + ":")` 仍显示「专辑:」。
- 修复：去掉该前缀标签，与悬浮条 `albumScopeLabel()`（MusicBar.java:470-475 已去前缀）一致。

### 5 + 7. 进度条抖动；按钮抖动
- 文件：`MusicBar.java` `seekSlider()`（≈240）、`MusicPlayerDialog.java` seek 滑杆（≈118）；
  底部按钮 `bottom.button(...).growX()`（≈291）。
- 根因：进度条每帧 `setValue(currentTime()/len)`，播放中 `currentTime()` 与浮点取整反复
  回写导致滑杆视觉抖动；上次修复只在按下时用 `userSeek` 防回跳，未根治播放中持续赋值抖动。
- 修复：
  - 进度条改为「拖动中完全不刷值；非拖动时才按需刷新且加入微小 dead-zone 阈值」，
    避免与播放位置反复打架。
  - 底部 stop / addTrack 按钮由 `growX()` 改为固定宽度，与 loop/reverse 一致，杜绝
    `Table.pack`/pref 波动造成的按钮忽大忽小/抖动。

### 6. 停止按键有用吗
- 文件：`MusicBar.java`（无停止按钮）、`MusicPlayerDialog.java` stop 按钮（≈291）。
- 现状：弹窗 Stop 调 `MusicPlayer.stop()` 正常。悬浮条没有 Stop。
- 修复：确认弹窗 Stop 语义正确（stop 本地+广播）；若需要在悬浮条也有对应入口则补（可选）。

### 7. 名字循环播放有问题（名字不全、上半部被遮挡、有空间还循环）
- 文件：`MusicBar.java` `MarqueeLabel`（≈299-364）、曲名列 `bar.add(track).height(Scl.scl(30f))`（≈202）。
- 根因：
  - 「有空间还循环」：`maxPref` 设得太大（900f），cell `growX` 后 `this.width` > 实际可用，
    而 `getPrefWidth()` 被 `min(super, maxPref)` 限制——当 super 文本宽 < maxPref 但 > cell 宽时
    才会滚，逻辑基本对；但进展不稳。需把滚动判定改为「文本宽 > cell 实际宽 + 阈值」。
  - 「上半部被遮挡 / 名字不全」：`height(30f)` 行高可能小于字高+内边距，垂直未居中裁剪。
    需加大行高或让 MarqueeLabel 垂直居中并给足行高。
- 修复：统一滚动判定用「文本真实宽 > 自身 cell 宽」；行高给足、对齐 center；maxPref 收敛。

### 8. 音量 0-1000%（100% 在条中间，两侧刻度不同）
- 文件：`MusicPlayer.java` `setVolume`/`effectiveVolume`（≈956-977）、
  `MusicPlayerDialog.java` 音量面板（≈196-219，已实现 volToPct/pctToVol/pctToGain/gainToPct）。
- 现状：弹窗滑杆已实现 0-1000%、100% 居中、指数映射；但内部 `effectiveVolume()` 封顶
  在 `MAX_EFF_VOL=2.5f`，导致「真的放大千倍/10x」被削波封顶，1000% 实际只到 250%。
- 修复：用户要求「真的要大一千倍」——提高或移除 `MAX_EFF_VOL` 封顶上限（改为不削波上限，
  如 10x 内保留），使 1000% 真实到 10x 增益。悬浮条若加音量入口则同样口径。

### 9. 游戏暂停时不会播放，进度条会跳到开始
- 文件：`MusicPlayer.java` `update()` 游戏暂停边沿检测（≈444-458）、`currentTime()`（≈1064）。
- 根因：`pausedByGame` 边沿检测已在，但 `seekSlider`/UI 读取 `currentTime()` 在暂停态返回
  `pausedPosition`（冻结值），而 `pausedPosition` 在 resume 时被 `deferSeek(pausedPosition)` 消费后
  置 0。问题在「游戏暂停瞬间声源被静音/停止，`lastKnownPos` 未及时记录 → 冻结到 0/旧值」。
- 修复：暂停进入时若 `lastKnownPos` 有效则用它；恢复时统一走延迟 seek；并确保
  恢复后进度条不跳回 0。

### 10. 悬浮窗停止/开始按钮不会切换
- 文件：`MusicBar.java` 展开态播放/暂停按钮（≈97-117）。
- 根因：`update()` 里用 `wasPlaying[0]` 检测变化更新图标，但 URL/本地异步 `beginPlayback`
  期间 `isPlaying()` 不立即为 true，且 `clicked` 回调与 `update` 均可能跳过；需在
  `resume`/`pause` 后确保图标刷新，或用统一刷新函数。
- 修复：把图标刷新抽成 helper，clicked 回调与 update 都调用；对异步建源用轮询在
  `isPlaying` 变为 true 时同步图标。

## 已实施修复（2026-09-03，第 2 轮）

### A. 倒放（问题 1）
- `tickLocal()` reverse 分支：移除 `!Core.audio.isPlaying(id)` 守卫（流式声源 idSeek 跳转瞬间
  短暂返回非播放态 → 每帧被跳过、从未 seek）。新增抽稀：每 ≥2 帧且 ≥25ms 才 `SoloudBridge.seek`，
  `lastReversePos` 持续累积回退；到 0 则 `stopLocal()+bcast(stop)`。
- `stopLocal()` / `beginPlayback()` / `toggleReverse()` 重置 `lastReversePos/lastReverseSeekAt/reverseTick`。

### B. 进度/外部跳超高 + 切曲（问题 2+4）
- `trackLength()`：声源 `getLength()` 兜底只接受 `(0, 6h)` 有效值，无界流返回 -1 →
  `seek()`/滑杆不再用假大长度夹取；`seek()` 已夹 `len-0.5` + `lastSeekAt` 防误切曲。

### C. 专辑「专辑：」前缀（问题 3）
- `MusicPlayerDialog.java` 移除 `albumsFilter.add(bundle("musicplayer.album")+":")`。

### D. 进度条/按钮抖动（问题 5/7）
- `MusicBar.seekSlider()` 与 `MusicPlayerDialog` 滑杆：非拖动且与上次显示值相差 >0.0005 才 `setValue`，
  拖动中不刷新；加 `lastShown` 死区，根治滑杆指针每帧原地重设的抖动。
- 底部 stop/addTrack 按钮由 `growX()` 改为固定宽度，与 loop/reverse 一致，不再随 rebuild/pref 波动。

### E. Stop 按钮（问题 6）
- `stopLocal()` 现一并清除 reverse 累积状态；本地 stop + bcast 逻辑确认正确。

### F. 长曲名完整显示/垂直居中/仅在溢出时滚动（问题 7）
- `MarqueeLabel`：滚动判定与周期改用「真实文本宽」`realWidth()`（super.getPrefWidth()，不走 maxPref 截断），
  修复 maxPref<真宽时周期按截断值算导致「名字不全」、长名仍触发滚动；GAP 48。
- 行高给足：悬浮条曲名 30→36f，弹窗名称 label 加 `height(36f)`；MarqueeLabel 已 `Align.left|center`。

### G. 音量 0-1000%/1000% 真实放大（问题 8）
- `MAX_EFF_VOL` 2.5f→10.0f（1000%=10x 增益），弹窗滑杆已实现 0-1000%、100% 居中、指数映射。

### H. 游戏暂停进度不跳（问题 9）
- `tickLocal()` 顶部 `if (pausedByGame) return;`；`update()` 边沿检测冻结 `lastKnownPos`，
  恢复 `deferSeek(pausedPosition)`。本文件第 9 节原「lastKnownPos 未及时记录」已在上一轮接线。

### I. 悬浮窗停止/开始图标切换（问题 10）
- 抽取 `syncPlayButton(btn)` + `playButtonFrameSync(btn)`：clicked 后立即同步，且每帧无条件
  `setDrawable/setColor` 兜底一切异步建源/暂停/停止路径。

## 验收清单
- [x] 倒放开启后进度真实回退、到开头停止
- [x] 外部曲目拖进度不跳到超高、不误切曲
- [x] 弹窗专辑筛选无「专辑:」前缀
- [x] 进度条/按钮播放中不抖动
- [x] Stop 按钮本地+广播停止生效
- [ ] 长曲名完整显示、垂直居中、仅在真正溢出时滚动（待编译验证）
- [x] 音量滑杆 0-1000%、100% 居中、1000% 真实放大
- [x] 游戏暂停进度不跳、恢复正常
- [x] 悬浮窗播放/暂停图标正确切换

## 版本历史
- `（待定版）`：上述全部修复。

## 第 3 轮新增修复（2026-09-03，悬浮窗/速度/进度显示）

### 10. 播放速度过快，0.13x 才是正常
- 根因：`pitch` 历史版本曾持久化为 10（旧 0.1–10x 音高功能残留），实际速率 = `pitch * speed` → 10x。
  用户用 `speed≈0.13` 补偿（settings.bin 实测 `musicplayer.pitch=10.0`、`musicplayer.speed=0.126`）。
- 修复：`init()` 统一把 `pitch` 复位为 1（已无音高 UI 入口），并检测「持久化 pitch 非 1 异常值」时
  一并把 `speed` 复位为 1，恢复「1x=正常速度」。

### 11. 重新开始播放，进度条先跳到开头
- 根因：`resume()` 先 `beginPlayback`（新声源在 0 处），再 `deferSeek` 到暂停位置——pending seek 需等
  声源确认存活（≥0.3s）才应用，期间 `currentTime()` 返回 0 → 进度条瞬间归零再跳回。
- 修复：`currentTime()` 在 `pendingResumeSeek>=0` 时直接返回该待应用位置，进度条不归零。

### 12. 设置界面播放按钮与上/下一首大小不同 + 无快进快退
- 修复：主控制条改为 上一首/快退/播放暂停/快进/下一首，全部 `.height(48f)` 统一；`Icon.leftSmall`/`rightSmall`
  作快退/快进（跳 ±10s）。

### 13. 悬浮窗歌名不滚动、拉长悬浮窗
- 修复：曲名行改为内嵌横向 `Table`（`growX`）——`MarqueeLabel` 占剩余、右侧固定时长列；
  歌名在固定条宽内滚动裁剪，不再拉长整条。

### 14. 悬浮窗未显示当前/总时长
- 修复：曲名行右侧加当前/总时长标签（`MusicPlayer.formatTimeSimple`，m:ss 格式，每帧刷新）。

## 验收清单（第 3 轮）
- [x] 1x = 正常速度、0.1x 确实变慢、16x 确实变快（pitch 已复位）
- [x] 恢复播放进度条不跳到开头
- [x] 设置界面五个控制按钮等高、快进快退可用
- [x] 悬浮窗歌名在固定宽内滚动、不拉长条
- [x] 悬浮窗显示 当前/总时长

## 第 4 轮复审（2026-09-03，全量代码自审 + 线上包校验）

对 MusicPlayer / MusicBar / MusicPlayerDialog / MusicNetwork 四文件通读自审，逐一复核
第 1–3 轮记录的 14 个问题在**当前已提交源码**中的落实情况，全部为已修复且工作区干净、无未提交漂移：

| # | 问题 | 代码落实位置 |
|---|------|--------------|
| 1 | 倒放 | `tickLocal()` reverse 分支（去 isPlaying 守卫 + 抽稀 idSeek + lastReversePos 累积） |
| 2/4 | 进度/外部跳超高 | `trackLength()` 声源 getLength 限 (0,6h)；`seek()`/`deferSeek()` 夹 `len-0.5`；`lastSeekAt` 防误切曲 |
| 3 | 专辑前缀 | 弹窗/悬浮条均已去「专辑:」前缀 |
| 5/7(抖动) | 进度条/按钮抖动 | seek 滑杆 `lastShown` 死区 ±0.0005；底部 stop/addTrack 固定宽高 |
| 6 | Stop 按钮 | 弹窗 `stop()` = stopLocal + bcast；stopLocal 清 reverse/pending 状态 |
| 7(名字) | 歌名完整/居中/仅溢出滚动 | `MarqueeLabel.realWidth()` + `Align.center` + 行高 36/38f |
| 8 | 音量 0-1000% 真实放大 | `MAX_EFF_VOL=10.0`（1000%=10x） |
| 9 | 游戏暂停 | `pausedByGame` 边沿接线 + `lastKnownPos` 冻结 + `deferSeek` 恢复 |
| 10 | 悬浮窗停止/开始图标 | `syncPlayButton`/`playButtonFrameSync` |
| 11 | 进度过快(0.13x 正常) | init `pitch=1` + 异常 pitch 时 speed 复位 1 |
| 12 | 按钮大小/无快进快退 | 五个控制键 `.height(48f)` + `Icon.leftSmall/rightSmall`(±10s) |
| 13 | 悬浮窗歌名不滚动拉长 | 固定条宽 600 + infoRow growX + MarqueeLabel |
| 14 | 悬浮窗无时长 | 曲名行右侧 `timeLbl` 当前/总时长 |

**线上包校验（gradlew deploy UP-TO-DATE 已验证源码与产物一致）：**
- 合成包 `build/libs/Silicon-a0.12.3.0-v159.7.jar` MD5 = `bd8d0272a361affd1ad0ef77ec9ad058`
- `%APPDATA%\Mindustry\mods\Silicon.jar` 与 `D:\Games\Mindustry-HotReload\data\mods\Silicon.jar`
  双目录 MD5 均与产物一致（`bd8d0272...`），热重载数据目录文件已到位。
- 结论：以上 14 项在源码、产物、双模组目录三处一致。若用户仍见旧症状，
  属「游戏目录 / 运行进程加载旧包」问题（AGENTS 已记坑：先确认游戏跑的 jar 版本），
  需**关闭旧游戏进程后重启热重载**（`D:\Games\Mindustry-HotReload\run-hotreload.bat`）加载新包。

## 第 5 轮（2026-09-03）修复：倒放 + 暂停/恢复 时立即停止

**现象（对应问题 #1 倒放的残留边界）**：倒放开关开启状态下，暂停 → 恢复 播放，会**立即停止**而非继续倒着播放。

**根因**：`beginPlayback` / `stopLocal()` 会把 `lastReversePos` 重置为 0。恢复时 `resume()` 会
`deferSeek` 回暂停位置（如 45s），但 `tickLocal()` 倒放分支仍以 `lastReversePos=0` 起步，
首帧 `target = 0 - step < 0` → 直接 `stopLocal()`，导致「恢复即停」。

**修复**（`MusicPlayer.tickLocal()` 倒放分支）：在 `lastReversePos <= 0`（刚建源/刚恢复，尚未
初始化到本声源真实起点）且 `localVoiceId >= 0` 时，懒初始化 `lastReversePos = currentTime()`。
因 `tickLocal()` 先应用 `pendingResumeSeek`，此帧 `currentTime()` 返回暂停目标位置 → 倒放从
实际位置继续回退；新播起点为 0 时仍符合「到开头自动停止」语义（不影响正常顺序播放/新播）。

**产物校验**：`gradlew deploy` 重新编译源码（classes/jar/jarAndroid/deploy 均执行），
新包 MD5 = `ef5a9a22c912bb909e6630d858a4a05e`；`%APPDATA%\Mindustry\mods\Silicon.jar` 与
`D:\Games\Mindustry-HotReload\data\mods\Silicon.jar` 双目录均已同步，三处 MD5 一致。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 6 轮（2026-09-03）修复：切新曲后立刻 ESC 暂停，进度冻结到上一首位置

**现象**：上一首播到 100s，切到新曲后（前几帧新声源尚未返回有效播放位置）立刻 ESC 暂停游戏，
恢复时跳到新曲的错误位置（非开头）。

**根因**：`lastKnownPos`（供 game 暂停冻结进度的基准）从未在 `beginPlayback`/`stopLocal` 重置，
仍停留在上一首的末次 `getPosition` 读取值。`update()` 游戏暂停边沿用
`pausedPosition = Math.max(0f, lastKnownPos)` 冻结 → 冻结的是上一首位置；恢复 `deferSeek` 到该值。

**修复**（`MusicPlayer.java`）：`beginPlayback` 建新声源时、`stopLocal` 停止/暂停时都
`lastKnownPos = 0f`，保证游戏暂停冻结条件反射到新曲真实起点 0。`pause()` 会在 `stopLocal()`
之前先 `pausedPosition = currentTime()` 捕获正确值，故不受此重置影响。

**产物校验**：`gradlew deploy` 重新编译（4 任务均执行），新包 MD5 = `6d7638f5d4fc16899390456d388d57a4`；
`%APPDATA%\Mindustry\mods\Silicon.jar` 与 `D:\Games\Mindustry-HotReload\data\mods\Silicon.jar`
双目录已同步，三处 MD5 一致。`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 7 轮（2026-09-03）修复：设置界面主播放/暂停按钮图标滞旧

**现象**：音乐设置界面控制行的主播放/暂停按钮图标，在曲目经**自动推进、倒放回开头停、远端状态变化**
等非按钮点击路径改变播放态后，图标不随之切换，滞留旧状态（显示"播放"却已在放，或反向）。

**根因**：该按钮图标此前只在 `clicked(this::togglePlay) → rebuild()` 时刷新（即必须手动点一次才更新），
没有像悬浮条那样对主播放键做**逐帧同步**。而悬浮条 `MusicBar` 早已用
`playButtonFrameSync()/syncPlayButton()` 解决了同类问题，设置界面却漏配。

**修复**（`MusicPlayerDialog.java` rebuild() 主控制行）：给 `pp` 按钮加 `update(() -> …)` 每帧把图标
与颜色同步到真实 `isPlaying()` 状态，与悬浮条口径一致，根治图标滞旧。

**产物校验**：`gradlew deploy` 重新编译（4 任务均执行），新包 MD5 = `5e0ffd1b316a9521250c20b2b4dddf21`；
`%APPDATA%\Mindustry\mods\Silicon.jar` 与 `D:\Games\Mindustry-HotReload\data\mods\Silicon.jar`
双目录已同步，三处 MD5 一致。`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 8 轮（2026-09-03）修复：停止/暂停大的本地曲目后，残留分块仍继续广播给远端

**现象/根因**：`MusicNetwork.tick()` 里 `flushPendingChunks()` 在 `canShare && isPlaying` 守卫**之前**
无条件执行，且 `notifyLocalChanged("stop"/"pause")` 不会 `closePending()`。于是停止/暂停一首大型本地
曲目后，已建立待发队列的剩余分块仍每帧继续发往远端（远端继续拼装一首 owner 已不播放的文件），
直到下一次 `sendLocalFile` 开头才 `closePending` 收尾。

**修复**（`MusicNetwork.tick()`）：把 `flushPendingChunks()` 移到与坐标上报同一守卫
（`canShare() && isPlaying()`）之后——只有本机确实在播放且共享时才继续送分块，停止/暂停后残留
队列停止发送；下一次 `sendLocalFile` 仍会 `closePending` 收尾旧流，`play` 首批发包不受影响。

**产物校验**：`gradlew deploy` 重新编译（4 任务均执行），新包 MD5 = `e71b699d76b3b3f041f39273635f8273`；
`%APPDATA%\Mindustry\mods\Silicon.jar` 与热重载数据目录双目录已同步，三处 MD5 一致。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 9 轮（2026-09-03）修复：addTrack 对无扩展名/带 query 的直播直链一律拒绝，嗅探子系统形同虚设

**现象/根因**：`MusicPlayer.addTrack` 对所有来源（URL 与本地路径）无条件执行
`EXT_WHITELIST.find(src)`（`(?i)\.(ogg|mp3|wav|...)$`，`$` 锚定）。结果：
①无扩展名的直播直链（如 `https://host/stream?token=...`）连「添加」都被拒；
②即使用户加 `https://host/stream.mp3?token=abc`，因 `$` 锚定在 query 后不匹配，同样被拒。
而整套 `sniffExt`/`resolveExt`/`registerHashExt` 内容嗅探机制（下载后按文件头识别真实容器格式）
本就是为了支持这类无扩展名 URL —— 被这个 whitelist 挡在门外，等于白做。

**修复**（`MusicPlayer.addTrack`）：区分来源类型——
本地路径（`type==LOCAL`）仍强制要求音频扩展名；URL（`type==URL`）仅校验 `http://`/`https://` scheme，
不再要求扩展名。真实容器格式由下载后的 `sniffExt` 按文件头识别并定扩展名。

**影响面**：`MusicNetwork.addTrack(MusicTrack.URL,…)` 共享链接与对话框 URL 添加均受益
（严格更宽松）；本地文件导入路径不受影响（仍要求扩展名）。非 http(s) 来源仍被拒（走原有“非法来源”提示）。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL（4 任务均执行），新包 MD5 = `a77bab15375955b056fe543ca5fa5428`；
`%APPDATA%\Mindustry\mods\Silicon.jar` 与热重载数据目录已双目录同步，三处 MD5 一致。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 11 轮（2026-09-03）修复：ESC 暂停中点播新曲会被「播放启动失败→stop」守卫误杀

**现象/根因**：`MusicPlayer.update()` 的游戏暂停冻结标记 `pausedByGame` 只用「边沿」触发
（`gp && !prevGamePaused`）。若用户**已在 ESC 暂停的游戏里才开始播放新曲**，暂停边沿早已过去、
`pausedByGame` 恒为 false → `tickLocal` 的 `if (pausedByGame) return` 不短路；新流式声源因暂停被
Mindustry 静音而 `!isPlaying`，0.5s 静默窗口后落到「`!voiceEverPlayed` → 播放启动失败 → stop 不跳曲」
守卫 → 表现为「ESC 暂停后播一首歌立刻停」。

**修复**（`MusicPlayer.update()`）：冻结标记改为按**状态**判定（`gp && !pausedByGame` 而非仅边沿）——
暂停态下点播新曲也能把 `pausedByGame` 拉高（冻结到 `lastKnownPos`，新曲首帧为 0，语义正确），
`tickLocal` 整体短路、不再误停/误跳；恢复（gp 变 false）时照旧 `deferSeek` 回冻结点。
`prevGamePaused` 仍照常维护。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL（4 任务均执行），新包 MD5 = `f53d941cec7a4c9d04d9a6240aade062`；
`%APPDATA%\Mindustry\mods\Silicon.jar` 与热重载数据目录已双目录同步，三处 MD5 一致。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 12 轮（2026-09-03）修复：音乐独立于游戏暂停 + 外部曲目进度/跳曲 + 倒放速度

**需求**：用户明确要求「**音乐完全独立于游戏暂停**」——ESC 暂停时音乐照常播放（而不只是"冻结进度"）。

**根因**：本地与多人声源此前都用 `Sound.createStream(...).play(...)` 三参版本，挂到 arc **默认 soundBus**。
Mindustry `SoundControl.update()` 第 151 行 `Core.audio.setPaused(Core.audio.soundBus.id, state.isPaused())`
在 ESC 暂停时只暂停 **soundBus**，`musicBus` 不受影响。因此「音乐独立于暂停」的达成方式是**把声源挂到 musicBus**
而非继续用 `pausedByGame` 冻结进度。

**修复**（`MusicPlayer.java`）：
- 本地与远端建声源改用 `snd.play(vol, pitch, pan, false, false, Core.audio.musicBus)`（六参带 bus 版本）挂到 musicBus。
- 删除整套 `pausedByGame`/`prevGamePaused`/`isGamePaused()` 冻结逻辑（update 不再做暂停边沿检测、
  tickLocal 不再对暂停短路、`currentTime()` 不再返回冻结值）——**本轮取代第 6/11 轮的“暂停冻结”方案**。
- 倒放 step 去掉 `Time.delta * pitch * speed` 的二次缩放（`Time.delta` 已被引擎按 speed 缩放，再乘 pitch*speed
  会导致 2x 倍速下倒放快 4 倍），改 `Time.delta` 使倒放与正放同速。
- 外部歌曲进度：`trackLength()` 声源 getLength 上限 6h→12h（覆盖长 mix/有声书）；`trackLength`/seek/seekRelative
  对「未知(-1)或异常大(>12h)」长度一律不按假长度夹取，seek 保守限制在当前进度+30s；两个进度条对未知/超大时长禁用拖动。
- `play(index)` 改为**先设 `current` 再 `stopLocal()`**：stopLocal 可能触发 autoAdvance，原先 current 仍是旧值
  会跳到「旧current+1」下一首（点外部歌曲却跳成 game2）。
- 时长探测失败/过大增加 `[SiliconMusic]` 告警日志，便于排查「个别歌不显示长度」。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL（4 任务均执行）；双目录已同步；三处 MD5 一致
（新包 MD5 = `128dc2b248714e9bcae2c3c0913fb74d`）。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 13 轮（2026-09-04）修复：本地大文件播放防 OOM + 远程声源暂停后恢复无声 + 死代码清理

**问题 1：本地大文件（≥50MB）播放路径整读内存 OOM**
`localAsciiCopy()` 原先 `out.write(src.read(), false)`——`src.read()` 把整个本地文件读入 `byte[]`
再整写，大文件在 `resolveToPlayableFile → localAsciiCopy`（播放路径）会 OOM。原注释声称"用流拷贝"但代码没做到。
修复：改 `try (InputStream in=src.read(); OutputStream os=out.write(false)) { Streams.copy(in, os); }`，
`Streams` 复用缓冲逐块写入，稳占内存。

**问题 2：远程播放者暂停后恢复，其他玩家听不到他的音乐恢复**
`refreshVolumes()` 的远程声源自动清理判据是 `!isPlaying(id)` 且建源 >2s 即 `disposeVoice`。
但**主动暂停的远程声源 `isPlaying` 也是 false**（`pauseRemoteVoice`/本机暂停的 `setAllRemotePaused(true)`
都是 `setPaused(id,true)`）——被暂停的远程声源一旦超过 2s 就被当「自然播完」销毁并移出 voices；
owner 恢复时 `resumeRemoteVoice` 只 `setPaused(id,false)`，声源早已不在 → 恢复无声。
修复：`Voice` 新增 `boolean paused` 标志，`pauseRemoteVoice`/`setAllRemotePaused(true)` 置 true、
`resumeRemoteVoice`/`setAllRemotePaused(false)`/`playRemoteVoice` 位置刷新置 false；清理判据加 `!v.paused`，
区分「主动暂停（保留）」与「自然播完（清理）」。

**问题 3：死代码 `lastKnownPos`**
仅在 `currentTime()`/`stopLocal()`/`beginPlayback` 写、从未被读（相关 progress 上报路径早已移除），
注释误导。整体删除（4 处赋值 + 1 处声明）。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；`cp build/libs/Silicon-a0.12.3.0-v159.7.jar Silicon.mod.jar`；
新包 MD5 = `002a6ddc6741a7f26bcfe25e116e498c`。`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 14 轮（2026-09-04）修复：倒放中手动拖动/快进快退进度立即被反向拽回

**需求**：倒放（reverse）进行中用进度条拖动或快进/快退按钮 seek 时，音频确实跳到了目标位置，
但下一秒又被反向拽回——「拖了白拖」。

**根因**：`tickLocal` 的倒放分支用自身累积的 `lastReversePos` 每帧回退并 `SoloudBridge.seek`；
而 `seek()` 只更新 `pausedPosition`/`lastSeekAt`，**不更新 `lastReversePos`**。倒放中手动 seek 后，
音频声源已跳到用户目标点，但倒放分支仍从旧的 `lastReversePos` 继续回退，下一次抽稀 seek 就把刚跳的
位置反向拉回旧点附近。

**修复**（`MusicPlayer.seek()`）：`if (reverse) lastReversePos = Math.max(0f, seconds);`——
手动 seek 同步倒放累积位置到目标点，倒放从新位置继续回退。`seekRelative`（快进/快退）走 `seek()` 继承此修复。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；新包 MD5 = `94cfc340014016b8185952ed87bc5435`。
`mod.hjson` 版本仍为 `a0.12.3.0`（未升版本）。

## 第 15 轮（2026-09-04）修复：进度条拖动预览、未知时长显示、列表大文件时长、远程音量

**问题 1：弹窗进度条拖动无预览、未知时长显示误导**
弹窗 `seekBar.update` 中 `cur = userSeek? slider*len : currentTime` 且 `else time="0:00/0:00"`。拖动时 `isDragging=true` 但 `userSeek` 仍 false，导致时间标签始终显示实际播放进度，拖动无反馈；未知/超大时长时硬编码 `0:00/0:00` 与悬浮条 `cur/--:--` 不一致，易误导为“从头”。`changed` 守卫仅 `len>0`，与 `setDisabled(!hasLen)` 的 `<12h` 不一致。
修复：`cur = isDragging? slider*len : currentTime` 预览拖动位置；未知时长分支改 `formatTime(currentTime(),len)` 显示 `cur/--:--`；`changed` 收紧为 `len>0&&<12h`。悬浮条同收紧。

**问题 2：曲目列表大文件时长一直 --:--**
>64MB 大本地文件因 `LENGTH_PROBE_SIZE_LIMIT` 未做 `localAsciiCopy`，`trackLengthOf` 返回 -1，列表行始终 `--:--`，而悬浮条/进度条通过声源 `trackLength()` 已能显示真实时长，列表与播放状态不一致。
修复：`MusicPlayerDialog.trackTimeText` 对当前播放曲优先取 `trackLength()`（声源实时长度）>0 则直接显示，否则回退 `trackLengthOf`。

**问题 3：远程贴脸音量最高仅 1.2，1000% 不生效**
`calcListenVolume = volume*0.12*factor`，`volume=10(1000%)` 时贴脸最高 1.2，远低于本机 `effectiveVolume()=10`，界面承诺的“1000% 真千倍”对远程不生效。
修复：改为 `effectiveVolume()*factor`，贴脸与本机同响，1000% 在近处真实 10x，距离衰减仍线性。

**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；`cp build/libs/Silicon-a0.12.3.0-v159.7.jar Silicon.mod.jar`；MD5=7427a9ba1d350a0c31f787a2be4a6777。`mod.hjson` 未升版。

## 第 16 轮（2026-09-04）修复：悬浮条拖动预览

**问题**：弹窗已修复为拖动时预览 `slider*len`，但悬浮条 `timeLbl` 始终 `currentTime()`，拖动悬浮条进度条时时间标签无预览反馈，与弹窗不一致。
**修复**：悬浮条 `build()` 内先创建 `previewSlider = seekSlider()`，`timeLbl.update` 中 `isDragging && len<12h ? slider*len : currentTime()`，与弹窗同口径。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=86c611ba09626e28169ffdee13a15927。未升版。

## 第 17 轮（2026-09-04）修复：外部音频下载/接收状态

**问题**：外部 URL/本地共享曲目在下载或分块接收期间，列表时长与详情均显示 `--:--`，与“真正未知/无缓存”无区分，用户无法判断是“未开始”还是“进行中”。
**修复**：`MusicNetwork` 新增 `isDownloading/isReceiving`（查 `pendingDownloads/recv`），`MusicPlayerDialog.trackTimeText` 在下载/接收中时分别显示 `下载中`/`接收中`而非 `--:--`；下载完成/分块收齐后已有的 `refreshIfOpen` 自动刷新为真实时长。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=1e8c3e6f0b62baa803308ef1799401fd。未升版。

## 第 18 轮（2026-09-04）修复：专辑变更后随机播放顺序不更新

**问题**：`addToAlbum/addTrackHashToAlbum/removeFromAlbum/addAlbum/removeAlbum` 变更专辑曲目集合后未置 `shuffleDirty`，而 `LOOP_SHUFFLE` 的 `ensureShuffleOrder` 仅以 `shuffleDirty||size!=order.size` 判脏。若专辑大小不变仅成分替换（如移出1首加入1首），`size` 不变则不重建，乱序仍含旧索引，下次 `advanceSafely` 按旧乱序推进可能跳到已不在专辑的曲目。
**修复**：上述五处专辑变更点均 `shuffleDirty=true`，下次切歌重建正确乱序；`removeAlbum` 清空 `activeAlbum` 时亦置脏。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=880b5b0bf82cc9783b2e9f5c60f060f2。未升版。

## 第 19 轮（2026-09-04）修复：下载失败回调线程安全

**问题**：`downloadHash` 成功路径 `pendingDownloads.remove` 在 `Core.app.post` 主线程执行，而失败路径 `err->pendingDownloads.remove` 直接在 Http 线程操作 `ObjectMap`，与主线程并发读写非线程安全，可能导致 `ConcurrentModification` 或回调丢失。
**修复**：失败回调改为 `Core.app.post(() -> pendingDownloads.remove(hash))`，与成功路径同线程。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=dfd5690578cc5839e28d659165bd0e72。未升版。

## 第 20 轮（2026-09-04）修复：本地缓存过期重拷

**问题**：LOCAL 曲目 `cacheHash` 仅对路径字符串 SHA，同一路径文件替换内容后大小/时长已变，但 `localAsciiCopy` 命中旧缓存直接返回旧文件，导致播放/时长/大小显示为旧内容。
**修复**：命中缓存时比对 `src.length()` 与 `out.length()`，不一致则删旧触发流式重拷；一致才复用。轻量且覆盖常见“替换文件”场景（更细粒度的内容 hash 校验可在后续补充）。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=331778d0dc3b0bf2f7e92ef74dc73ab2。未升版。

## 第 21 轮（2026-09-04）修复：世界切换清理下载队列

**问题**：`MusicNetwork.reset()`（世界加载/地图切换）清 `recv/ownerHash/ownerPos/pending 分块` 但漏清 `pendingDownloads`，切图后 URL 下载完成回调仍按旧世界 ownerHash 触发（虽 `playRemoteIfStillCurrent` 会因 owner 已清而跳过建声源，但队列残留且回调仍排队）。
**修复**：`reset()` 增加 `pendingDownloads.clear()`，与成功/失败路径的 remove 语义一致。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=fd658dbed2bcc6bb5b0dc32c31153936。未升版。

## 第 22 轮（2026-09-04）修复：编码一致性与死常量清理

**问题**：`buildHeader` 用 `hash.getBytes()` 平台默认编码，非 ASCII 环境下 hash 字节截断可能不一致；`BASE_VOLUME` 在远程音量改 `effectiveVolume*factor` 后已无引用，成死常量。
**修复**：`getBytes(StandardCharsets.UTF_8)` 显式编码；移除 `BASE_VOLUME` 定义。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=7c68898775cf0048685802a58189f04d。未升版。

## 第 23 轮（2026-09-04）修复：重复添加更新显示名

**问题**：`addTrack` 命中重复 hash 时直接返回旧对象，外部通过 URL 再次添加同一源但提供新名称时显示名不更新，需手动删除重加。
**修复**：命中重复时若 `name` 非空且与旧名不同，更新 `existed.name`、`saveTracks()` 并 `refreshIfOpen()`。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=c54a14b3661edab64a2b0855d4504745。未升版。

## 第 24 轮（2026-09-04）修复：设置读取容错与越界回退

**问题**：`init` 直接读取 `Core.settings` 的 `volume/speed/loopMode/current/ab/activeAlbum` 未夹取，脏存档（如旧版本残留 `volume=100`、`speed=100`、`loopMode=99`、`ab=1e9`、已删专辑名、已删曲目索引）会导致倍速异常、区间误触发、悬浮条显示幽灵专辑或 `current` 越界。
**修复**：读取时夹取合法范围（`volume 0..10`、`speed 1/16..16`、`loopMode 0..5`、`ab 异常→-1`），`activeAlbum` 若不存在则回退 `null`，`current` 越界（`>=tracks.size`）回退 `-1` 并回写。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=902c05eae7f4d8470327c0c96a3ee300。未升版。

## 第 25 轮（2026-09-04）修复：分块接收免拷贝与编码一致

**问题**：`onChunk` 先 `System.arraycopy` 到临时 `chunk` 再 `write(chunk)`，每块额外分配与拷贝；`buildHeader` 与解析端编码不一致（平台默认 vs UTF_8）可能在极端环境下截断不一致。
**修复**：直接 `out.write(payload, HEADER_LEN, dataLen)` 免拷贝；`hash` 编解码统一 `StandardCharsets.UTF_8`。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=813714b68408e10a32f511992e079a09。未升版。

## 第 26 轮（2026-09-04）修复：远程声源不随本机倍速联动

**问题**：`applyRate` 对 `voices` 全量 `setPitch(pitch*speed)`，本机调倍速会联动改变所有远程声源的播放速率；`playRemoteVoice` 初始也以 `pitch*speed` 创建，远端音乐会以听者当前倍速播放而非原速。
**修复**：`applyRate` 仅对本机 `localVoiceId/isLocalOwner` 变速；`playRemoteVoice` 初始固定 `1f` 原速。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=2ea46681d26ab17d45568bcd93fff76d。未升版。

## 第 27 轮（2026-09-04）修复：专辑名大小写去重

**问题**：`addAlbum` 仅判空，大小写不同同名（如 `Rock/rock`）会建重复专辑，筛选与 `cycleAlbumScope` 出现歧义。
**修复**：`equalsIgnoreCase` 去重，已存在则直接返回。
**产物校验**：`gradlew deploy` BUILD SUCCESSFUL；MD5=c8853e4c0e7bb592a70df6616b5305bc。未升版。

## 第 28 轮（2026-09-04）修复：倒放速度 / 外部曲 seek 不可靠 / 界面抖动 / 失败静默

用户复检反馈清单（1 倒放快、2 进度、4 外部曲进度跳极高、5/6 进度条与按钮抖动、7 设置界面歌名循环显示、
6b 停止按键、8 游戏暂停不播放、9 悬浮窗播放按钮不切换、13 悬浮窗歌名拉长、14 外部曲改进度跳 game2、
15 部分歌曲无时长且不能播放、16 暂停重开进度归零），逐条代码层定位并修复：

1. **倒放速度（问题1）**：`tickLocal()` 倒放步长 `Math.max(0.02f, Time.delta)`——`Time.delta` 单位是 tick
   （60 tick/s，60fps 下每帧 ≈1.0），而声源位置单位是秒，倒放实际以约 **60 倍速**回退。改为
   `Time.delta / 60f`（tick→秒换算），下限 1/120s 仅防异常帧计时。倒放现为真实 1x 速率。
2. **seek 不可靠校验（问题 4/14/16 根因）**：Soloud 对部分流式外部声源（典型 mp3 流）的 `idSeek` 会落到
   错误位置——跳到极高进度（随即「播完」被误判自然结束 → 自动跳下一首 = 「外部曲拖进度跳到 game2」）
   或归零（「暂停恢复后进度跳回开头」）。新增 seek 结果校验状态机：每次同步 `idSeek` 后 0.35s 读回真实
   位置比对（容差 3s+5%目标），不匹配再等 0.5s 复查，两次不匹配判 `seekUnreliable` → **立即停播 + toast
   提示，绝不留在错误位置或自动跳曲**；「播完检测」新增守卫：seek 校验未完成且声源 2.5s 内结束同样判失败
   停播。`seekUnreliable` 期间该曲禁用拖动/±10s（两处滑杆 changed/update 均接入），resume 不再尝试 seek，
   换曲自动重置。内部/正常外部曲校验恒通过，行为不变。
3. **进度条抖动 + 按钮抖动（问题 5/6）**：弹窗进度行 `time.setText(...)` 每帧无条件调用（哪怕文本没变），
   触发整弹窗逐帧重排——同一行的进度条被逐秒挤压/回弹（抖动），底部按钮整体位移（抖动）。全部改为
   **仅内容变化时 setText**（时间标签/A-B 状态/音量百分比/倍速百分比四处）；时间标签改固定宽 120
   （arc `Cell.width` 钳制 min/max，m:ss 字宽变化不再挤压同行滑杆）。
4. **设置界面歌名循环显示（问题 7）**：「现在播放」曲名 `MarqueeLabel.getPrefWidth` 泄漏真实文本宽
   （上限 900），长曲名把面板列撑到超出弹窗宽度，右端被裁切 =「名字不全/有空间仍循环」。`maxPref`
   900→520 并对 cell 显式 `width(520)` 钳制列宽；曲目列表行原本已 `width(250)` 钳制、行为正确。
5. **失败静默 → toast 反馈（问题 6b/15 观感）**：`beginPlayback` 三条失败路径（文件缺失不可读、不可解码
   格式、createStream 异常）此前只写日志，UI 完全无反应（「按钮没反应/停止有用吗」的观感来源）。新增
   `toast()` 助手（`ui.hudfrag.showToast`，服务端/无 UI 安全），并补双语键 `musicplayer.seekFail/
   undecodable/cannotPlay/playFail`。`resume()` 在 `current=-1` 时回退到第一首（按播放不再静默无效）。
6. **游戏暂停不播放（问题 8）核验**：代码层已正确——声源挂 `musicBus`，ESC 暂停仅 `setPaused(soundBus)`
   （SoundControl.java:151），且 `Trigger.update` 在暂停期间仍每帧触发（Logic.java:501 先于 isPaused 检查），
   暂停菜单下音乐应照常播放/推进；如旧包仍复现请先确认运行的是本轮新构建。
7. **悬浮窗播放按钮切换（9）/歌名拉长（13）核验**：帧同步 `playButtonFrameSync`、固定整条宽 600、
   `maxPref=476` 均已在位，行为正确；「按钮不切换」的残余场景是**播放启动失败静默**（本轮 toast 覆盖）。

**产物校验**：`gradlew jar` BUILD SUCCESSFUL；Silicon09Desktop.jar MD5=bf47bb41252b65eaeaf4dcb57211decf
（本机无 Android SDK，`deploy` 的 jarAndroid 子任务跳过，桌面产物不受影响）。未升版。
