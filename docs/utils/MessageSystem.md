# MessageSystem 与 MessagePanel

## 文档范围

本文档记录 Silicon 当前实现的消息数据层、联网同步层和消息面板视图层。消息系统不是单纯的 UI 提示列表：它负责消息模型、排序、生命周期、可见性和变更通知；面板只订阅这些变更并负责绘制、交互和本地阅读状态。

文档按当前源码整理，主要代码位置如下：

| 文件 | 职责 |
|------|------|
| `src/silicon/util/MessageSystem.java` | 消息模型、单例数据源、排序、生命周期和监听通知 |
| `src/silicon/util/MessageSync.java` | 服务器/客户端之间的二进制同步 |
| `src/silicon/ui/MessagePanel.java` | 常驻 HUD 消息面板、滚动列表、展开/收起动画 |
| `src/silicon/util/SiliconSounds.java` | 消息到达音效和清空音效的注册、解析、播放 |
| `src/silicon/world/blocks/sandbox/MessageTest.java` | 消息系统调试方块和完整使用示例 |
| `src/silicon/Silicon.java` | 初始化同步层、创建面板、注册设置和快捷键 |

## 1. 总体架构

消息源不直接操作面板行，而是向 `MessageSystem` 投递 `Message`。数据变更后，`MessageSystem` 通知所有监听器：

```text
消息源 / 方块
    │
    ▼
MessageSystem.instance
    │  add / post / remove / clear
    │
    ├── MessagePanel：维护本地行、动画、已读和 TTL
    │
    └── MessageSync：服务器按可见性广播，客户端应用镜像
                              │
                              ▼
                         MessageSystem 客户端镜像
                              │
                              ▼
                         MessagePanel 客户端视图
```

### 职责边界

| 组件 | 负责 | 不负责 |
|------|------|--------|
| `MessageSystem` | 保存 `Message`、按优先级插入、上限裁剪、持续型握手清扫、发出变更回调 | 绘制 UI、播放音效、执行消息点击回调 |
| `MessageSync` | uid 分配后的跨进程传输、可见性过滤、客户端请求校验、持续型快照更新 | 客户端面板布局、消息本地 TTL 的最终显示逻辑 |
| `MessagePanel` | 行创建和移除动画、展开/收起、滚动、已读、未读提示、实时文本刷新 | 直接修改 `MessageSystem` 的排序数据 |
| `Message` | 描述一条消息的文本、样式、可见范围、生命周期和本地行为 | 决定消息在网络上的传输时机 |

## 2. 消息数据模型

`MessageSystem.Message` 是一个公开可变对象。消息源可以通过链式方法配置它，也可以直接修改公开字段；一旦投递后，面板和网络层会持有同一个对象或该对象的网络镜像。

### 2.1 字段分组

| 分组 | 字段 | 当前用途 |
|------|------|----------|
| 身份/路由 | `type`、`priority`、`team`、`global`、`local`、`uid`、`senderKey` | 生命周期、排序、队伍可见性、**仅本地标记**和跨进程寻址 |
| 文本 | `title`、`content`、`titleKey`、`contentKey`、`vars` | 标题、正文、本地化键和 `{0}` 等占位符 |
| 样式 | `titleColor`、`contentColor`、`titleScale`、`contentScale`、`icon`、`background`、`bubbleColor`、`overlayColor` | 气泡、标题、正文、图标和时限覆盖层绘制 |
| 生命周期 | `ttl`、`handshake`、`contentProvider` | 瞬时消息倒计时、持续型握手和实时内容 |
| 本地行为 | `onClick`、`sound`、`soundName`、`silent` | 点击回调和消息到达音效 |
| 同步内部 | `addedAt`、`lastSyncTitle`、`lastSyncContent`、`lastSyncTime` | 专用服务器 TTL 缓存和持续型更新节流 |

### 2.2 默认值

| 属性 | 默认值 |
|------|--------|
| 消息类型 | `MessageType.TRANSIENT` |
| 优先级 | `Priority.LOW` |
| 图标 | `Icon.info` |
| 标题颜色 | `Color.white` |
| 正文颜色 | `Color.lightGray` |
| 标题缩放 | `1.0` |
| 正文缩放 | `0.8` |
| 瞬时消息时限 | `ttl = -1`，表示不启用本地倒计时 |
| 跨进程 uid | `-1`；权威进程 `add` 时分配 |
| 消息队伍 | `null`；按通用消息处理 |
| 仅本地标记 | `local = false`；为 `true` 时不参与联网广播，只出现在投递者自己的面板 |

构造函数会将字符串字面量中的 `\\n` 转换成实际换行符，便于调试面板和文本输入使用。

### 2.3 文本解析顺序

`Message.currentTitle()` 和 `currentContent()` 的实际顺序是：

1. 标题和正文分别先检查自己的 `titleKey`/`contentKey`，通过 `Core.bundle.get(key, fallback)` 解析本地化文本。
2. 对正文而言，如果没有内容本地化键，持续型消息优先调用 `contentProvider`；提供器返回 `null` 时回退静态 `content`。
3. 最后替换 `{0}`、`{1}` 等占位符。变量由 `vars` 中的 `Prov<String>` 每次求值，因此可以显示实时数据。
4. 占位符索引不存在或格式不完整时保留原文本；变量提供器存在但返回 `null` 时替换为空字符串。

固定文本应使用 bundle 键，不要把面向玩家的消息文本直接硬编码在业务方；`MessageSystem` 文件顶部对此有明确约束。

### 2.4 样式和本地行为

- `background(Color)` 同时设置 `bubbleColor`、`overlayColor` 和程序生成的 `background` 贴图。
- `background(Drawable)` 只替换背景贴图，不会自动改变 `bubbleColor` 或 `overlayColor`。
- `icon(Drawable)` 同时用于展开行右侧和收起态小方块中央。
- `onClick` 只在本地进程执行，不会序列化到网络。
- `sound(Sound)` 会尝试通过 `SiliconSounds.nameOf` 推导资源名；`sound(String)` 适合专用服务器等音频不可用的权威进程。
- `silent` 优先于 `sound`，到达时不会播放任何音效。

## 3. 优先级、排序和可见性

### 3.1 排序

`Priority` 声明顺序为 `HIGH`、`MEDIUM`、`LOW`，显示顺序固定为：

```text
高优先级
  └─ 同组最新消息在前
中优先级
  └─ 同组最新消息在前
低优先级
  └─ 同组最新消息在前
```

`MessageSystem.insertIndexFor` 会跳过更高优先级分组，将新消息插入当前分组的第一条位置。因此同一优先级的消息天然按时间倒序显示；没有时间戳排序逻辑。

`MessagePanel` 收到监听器提供的系统索引后，还会用 `visibleIndexOf` 将系统索引转换为“当前观看者可见消息”的索引，避免隐藏的敌方/异队消息影响行位置。

### 3.2 可见性

`Message.visibleTo(viewer)` 的规则如下：

| 条件 | 结果 |
|------|------|
| `global == true` | 所有玩家可见 |
| `team == null` | 通用消息，所有玩家可见 |
| `team == viewer` | 同队可见 |
| 其他情况 | 不可见 |

联网时，服务器在 `MessageSync.push` 中先按玩家队伍过滤；客户端面板仍会再次过滤，主要用于单机、房主本地数据和异常镜像的保护。

### 3.3 仅本地消息（`local`）

`local == true` 的消息**不参与任何联网广播**，只出现在投递者自己的面板上，其他在线玩家（含敌队）收不到。
与 `team`/`global` 的可见性过滤是**两个不同层次**：

| 层次 | 作用 | 实现 |
|------|------|------|
| `local` | **是否广播** | `MessageSync.messageAdded` / `broadcastLiveUpdates` 遇 `local` 直接返回，不发包 |
| `team` / `global` | 广播后**谁能看到** | `Message.visibleTo(viewer)` + `push` 的队伍过滤 |

必要性：未设 `team` 的消息 `visibleTo` 对所有队伍都为 `true`。若房主（host）投递这样一条消息，
`push` 会把它发给**全部**在线玩家——把「玩家本地查看详情」变成全服通知，既是信息泄露也是刷屏。
个人本地查看类消息（典型：点击世界中的标记查看该建筑的详情）应标记 `local`。

标记 `local` 的消息仍在本地面板正常显示，并按 `ttl` 到期消失。

## 4. 生命周期

### 4.1 瞬时消息

`TRANSIENT` 是默认类型，生命周期由每个客户端自己的面板推进：

1. 消息插入时对应行是未读状态。
2. 展开面板稳定约 `0.35s` 后，`markVisibleRead` 会把至少 95% 高度露出的行标记为已读。
3. 收起态点击小方块也会将对应消息标记为已读。
4. `MessageList.tickTimers` 只对“已读且 `ttl >= 0`”的瞬时消息推进 `age`。
5. `age >= ttl` 后，面板回调 `MessageSystem.remove`；数据立即从系统删除，行再播放移除动画。
6. 未读瞬时消息不会因为 TTL 自动消失；上限裁剪、垃圾桶清空和世界加载清空仍可移除它。

正常调用中，常驻消息应使用 `life(-1f)`。`MessageTest` 将界面上的 `0` 转换为 `-1`；直接调用 `life(0f)` 会满足 `ttl >= 0` 的计时条件，行为是已读后立即到期。

### 4.2 持续型消息

`PERSISTENT` 不使用面板 TTL，也不受普通消息数量上限和垃圾桶清空影响。它由消息源负责结束：

- 消息源可以给消息挂 `Handshake`，主动调用 `handshake.disconnect()`。
- `Handshake` 也可以提供 `Prov<Boolean>` 探活器；每帧全局 tick 发现探活失败后自动移除。
- 权威进程也可以直接调用 `MessageSystem.remove(message)`。
- 客户端请求创建的持续型消息带有 `senderKey`，服务器会定期检查来源方块是否存在且仍属于请求者队伍；方块被拆除或换队时自动撤销。

持续型消息可以携带 `contentProvider` 和 `vars`。权威端每帧本地刷新时使用实时提供器；跨进程传输时不会传输提供器、变量或回调，而是传输服务器计算出的标题/正文快照。

### 4.3 专用服务器的生命周期例外

专用托管服务器没有本地面板，因此 `MessageSystem.tickLifecycle` 会按 `addedAt + ttl` 静默清理瞬时消息，作为中途加入玩家的中继缓存。此逻辑只在 `Vars.headless && Vars.net.server()` 时运行：

- 房主不是生命周期权威，房主的瞬时消息仍由自己的本地面板管理。
- 普通客户端的瞬时消息由各自面板管理。
- `sweepDisconnected` 仍由所有进程执行，但客户端镜像没有握手，通常是无操作。

### 4.4 世界生命周期

静态初始化注册了 `WorldLoadEvent`：

```text
WorldLoadEvent
    └── MessageSystem.instance.clear()
```

`clear()` 会清空当前进程的全部消息并通知面板无动画清空；它不重置 uid 计数器。`MessageSync.messagesCleared` 当前不广播 CLEAR，因此各进程在加载世界时自行清理。

## 5. 监听器通知

监听器通过 `setListener` 或 `addListener` 注册，重复注册同一个对象会被忽略。当前没有对应的移除监听器 API。

| 回调 | 触发时机 | 数据状态 | 面板行为 |
|------|----------|----------|----------|
| `messageAdded(msg, index)` | 消息已插入 `data` 后 | 已在系统中 | 播放到达音效、插入行、下一帧置顶 |
| `messageRemoved(msg, index)` | 消息已从 `data` 删除后 | 已不在系统中 | 播放左滑淡出移除动画 |
| `messageTrimmed(msg, index)` | 超上限从底部删除后 | 已不在系统中 | 立即删除行，不播放移除动画 |
| `messagesCleared()` | `clear()` 完成清空后 | 列表为空 | 立即清空行和滚动位置 |

`MessagePanel` 对移除事件使用消息对象查找行，而不是直接使用回调索引，因为移除动画期间行仍在 `MessageList` 中，索引可能已经移位。

## 6. MessageSystem API

### 6.1 投递和查询

| API | 说明 |
|-----|------|
| `newMessage(title, content)` | 创建默认瞬时、低优先级消息 |
| `post(message)` | 调用 `add` 并返回同一个消息对象，便于保存持续型句柄 |
| `instance.add(message)` | 唯一数据写入口；排序、通知监听器并裁剪上限 |
| `remove(message)` / `removeAt(index)` | 立即从数据删除并通知视图移除 |
| `clear()` | 清空全部消息 |
| `clearAnimated()` | 从最旧开始移除全部瞬时消息，保留持续型消息 |
| `sweepDisconnected()` | 清扫握手断开或探活失败的持续型消息 |
| `get(index)` / `size()` | 读取系统排序后的消息 |
| `all()` | 返回底层 `Seq<Message>`；按只读方式使用，不要直接插入、删除或替换元素 |
| `maxMessagesSetting()` | 读取并限制消息上限，范围 20～100，默认 40 |

### 6.2 权威判断

| API | 条件 | 用途 |
|-----|------|------|
| `isAuthoritative()` | 无网络、单机或当前进程是服务器 | 决定是否直接登记并分配 uid |
| `isLifecycleAuthority()` | `Vars.headless && Vars.net.server()` | 决定是否推进专用服务器中继缓存 TTL |

在纯客户端进程中，直接调用 `MessageSystem.instance.add` 只会影响本地面板，不会向服务器登记；需要跨进程显示时必须使用 `MessageSync.requestAdd`。

### 6.3 消息工厂和链式配置

| 工厂 | 默认类型/优先级 | 默认样式 |
|------|-----------------|----------|
| `newMessage` | 瞬时 / 低 | 默认灰色气泡、信息图标 |
| `persistent(title, content, provider)` | 持续 / 低 | 信息图标，支持实时内容 |
| `emergency(title, content)` | 瞬时 / 高 | 红色气泡、警告图标、白色标题 |
| `warning(title, content)` | 瞬时 / 中 | 黄色气泡、警告图标、白色标题 |
| `info(title, content)` | 瞬时 / 低 | 蓝色气泡、信息图标、白色标题 |
| `normal(title, content)` | 瞬时 / 低 | 灰白气泡、信息图标、浅灰标题 |

工厂的带 `life` 重载会在模板基础上设置时限。常用链式配置包括：

```java
// 注意：post 是实例方法，需经 MessageSystem.instance 调用
MessageSystem.instance.post(
    MessageSystem.warning(title, content)
        .titleKey("example.warning.title")
        .contentKey("example.warning.content")
        .team(team)
        .global(false)
        .icon(Icon.warning)
        .life(5f)
);

// 玩家本地查看详情：标记 local，仅投递者自己可见、不广播
MessageSystem.instance.post(
    MessageSystem.info(title, content)
        .local()
        .life(10f)
);
```

`persistent` 不会自动创建握手。消息源必须主动挂 `Handshake` 或在结束时调用 `remove`。

## 7. MessageSync 联网同步

### 7.1 初始化和方向

`Silicon.init()` 调用 `MessageSync.init()`，它会：

- 注册服务器端二进制请求处理器；
- 注册客户端端广播处理器；
- 将 `MessageSync.instance` 注册为 `MessageSystem` 监听器；
- 注册持续型实时更新、客户端来源清扫和 `PlayerJoin` 补发事件。

包名固定为 `silicon-message-sync`。如果模组初始化时网络对象尚未创建，`WorldLoadEvent` 会再次尝试注册处理器。

### 7.2 服务器广播

| 事件 | 是否广播 | 说明 |
|------|----------|------|
| 新增瞬时消息 | 是 | `OP_ADD`，按队伍/全局可见性发送 |
| 新增持续型消息 | 是 | `OP_ADD`，后续还可能收到 `OP_UPDATE` |
| 新增 `local` 消息 | **否** | 仅本地，`messageAdded` 直接返回，不发包（见 3.3） |
| 瞬时消息移除 | 否 | 各玩家自己的已读 TTL、上限和清空互不影响 |
| 持续型消息移除 | 是 | `OP_REMOVE` |
| 持续型消息剔除 | 是 | `OP_TRIM`，正常情况下持续型不参与上限剔除 |
| 全部清空 | 当前不广播 | 各进程自行在 `WorldLoadEvent` 清空 |
| 持续型内容变化 | 是 | `OP_UPDATE`，默认最短间隔 0.5 秒；`local` 消息跳过 |

服务器会跳过房主自己的本地连接，房主已经通过本地 `MessageSystem.add` 登记，避免回环导致重复插入。`MessageSystem.applyNetAdd` 还会按 uid 做幂等检查。

### 7.3 客户端请求

纯客户端不能直接成为权威，公开请求 API 为：

| API | 服务器校验 | 成功结果 |
|-----|------------|----------|
| `requestAdd(message)` | 强制 uid 为服务器新分配值；队伍强制为请求者队伍；若有 `senderKey` 必须确实拥有该方块 | 服务器 `add` 后按可见性广播 `OP_ADD` |
| `requestRemove(uid)` | 仅允许 `PERSISTENT`、`senderKey >= 0` 且请求者可见的消息 | 服务器移除并广播 `OP_REMOVE` |
| `requestUpdate(uid, state)` | 仅允许客户端来源的持续型消息；只复制允许联网的字段 | 服务器立即广播 `OP_UPDATE` |

客户端请求不会先在本地 `MessageSystem` 登记；必须等服务器 ADD 广播确认后，客户端镜像才出现。`MessageTest` 用 `pendingSenderKey` 防止确认前重复点击，并在收到镜像后认领自己的持续型句柄。

客户端请求更新时，服务器不会接受 uid、`addedAt`、队伍、`senderKey`、握手、变量、点击回调、内容提供器等本地/权威字段。当前 `copyRequestedFields` 会复制类型、优先级、全局标记、TTL、颜色、缩放、图标、文本/本地化键、静音和声音字段。

> 边界核对：当前 `updateRequested` 只验证目标消息原状态对请求者可见，随后仍会复制请求中的 `global` 和 `type`。如果安全策略要求客户端不能把同队消息提升为全局消息，或不能把持续型改成瞬时型，应在复制前增加字段级校验。

### 7.4 持续型实时更新

服务器每个全局 update 扫描持续型消息：

1. 调用 `currentTitle()` 和 `currentContent()`，得到包含本地化和变量替换的当前快照。
2. 与 `lastSyncTitle`、`lastSyncContent` 比较。
3. 内容变化且距离上次同步至少 0.5 秒时，向所有可见客户端发送完整 `OP_UPDATE`。
4. 客户端 `applyNetUpdateFull` 原地更新镜像字段，不替换对象；`MessageRow` 下一帧刷新标签和颜色。

当前 `broadcastLiveUpdates` 的 `changed` 条件只比较标题和正文，不比较颜色、图标、优先级、队伍或全局标记。因此，权威进程直接修改这些样式/路由字段而正文不变时，客户端不会仅因该改动自动收到 `OP_UPDATE`；纯客户端通过 `requestUpdate` 发出的修改则会立即触发服务器广播。

### 7.5 二进制格式

每个包先写记录数，再写若干记录。单事件包的记录格式为：

```text
int recordCount
byte operation
MessageRecord
```

操作码：

| 常量 | 值 | 方向 | 含义 |
|------|----|------|------|
| `OP_ADD` | 0 | 服务器 → 客户端 | 新增消息 |
| `OP_REMOVE` | 1 | 服务器 → 客户端 | 按 uid 移除 |
| `OP_TRIM` | 2 | 服务器 → 客户端 | 按 uid 裁剪 |
| `OP_CLEAR` | 3 | 服务器 → 客户端 | 清空；当前广播路径未使用 |
| `OP_UPDATE` | 4 | 服务器 → 客户端 | 持续型全量字段刷新 |
| `REQ_ADD` | 100 | 客户端 → 服务器 | 请求新增 |
| `REQ_REMOVE` | 101 | 客户端 → 服务器 | 请求移除 |
| `REQ_UPDATE` | 102 | 客户端 → 服务器 | 请求更新 |

`MessageRecord` 当前传输 uid、类型、优先级、队伍 id、全局标记、TTL、四组颜色、两种缩放、图标名、标题/正文快照、静音标记、声音资源名和 `senderKey`。

以下字段不通过普通服务器广播传输：

- `titleKey`、`contentKey`：服务器 → 客户端发送已经解析好的快照文本；
- `vars`、`contentProvider`：无法跨进程传递函数；
- `onClick`：回调只在本地执行；
- `handshake`：由服务器消息源生命周期管理；
- 自定义 `Drawable background`：网络只传颜色和图标名。

### 7.6 当前实现核对项：中途加入补发

`replayTo` 当前直接写入 `writeMessage` 记录，而 `applyClientPacket` 对每条记录先读取一个操作码。按当前代码，中途加入补发的包格式与客户端逐条解析格式不一致；普通 `OP_ADD` 广播路径不受此差异影响。维护中途加入功能时应优先核对：

- `MessageSync.replayTo` 是否需要为每条记录补写 `OP_ADD`；
- 或客户端是否为补发包增加独立的无操作码解析分支。

## 8. MessagePanel 面板

### 8.1 创建和可见性

`Silicon` 在 `ClientLoadEvent` 中创建 `MessagePanel`，调用 `MessagePanel.setInstance` 后加入 `ui.hudGroup`。面板只在以下条件同时满足时可见：

```text
ui != null
ui.hudfrag != null
ui.hudfrag.shown
Vars.state.isGame()
```

面板默认 `expanded = false`，即启动后显示左侧窄条。`I` 键由 `Silicon` 注册为 `silicon_toggle_panel`，在游戏内调用 `MessagePanel.toggle()`。

### 8.2 设置项

| 设置键 | 常量 | 默认值 | 设置范围 | 作用 |
|--------|------|--------|----------|------|
| `message-panel.width` | `MessagePanel.SET_WIDTH` | 20% | 20%～50% | 展开宽度占屏幕宽度 |
| `message-panel.top` | `MessagePanel.SET_TOP` | 40% | 20%～80% | 面板从屏幕左侧底部向上的高度 |
| `message-panel.maxMessages` | `MessageSystem.SET_MAX_MESSAGES` | 40 | 20～100 | 系统消息上限 |

`MessagePanel.applySettings()` 会重建面板；重建保留已读集合，并按消息对象把旧行的 `age` 转移给新行，避免设置变化重置倒计时。

### 8.3 展开和收起状态机

`TogglePhase` 有三个状态：

| 状态 | 行为 |
|------|------|
| `NONE` | 稳定状态 |
| `EXPAND_IN` | 面板先加宽，约 75% 宽度时消息气泡从左侧滑入 |
| `COLLAPSE_OUT` | 气泡先向左滑出，移出约 75% 后开始收窄面板 |

关键动画常量：

| 常量 | 值 | 用途 |
|------|----|------|
| `DURATION` | 0.25s | 面板宽度过渡 |
| `SLIDE_DURATION` | 0.20s | 气泡展开/收起滑动 |
| `INSERT_DURATION` | 0.30s | 新消息或展开时的滑入淡入 |
| `STAGGER` | 0.10s | 多条消息逐条错峰 |
| `SCROLLBAR_FADE` | 0.12s | 滚动条淡入淡出 |
| `UNREAD_FADE` | 0.30s | 未读标签淡入淡出 |

收起完成时，`expanded` 才改为 `false` 并调用 `rebuild()`；展开则在切换开始时设为 `true` 并重建可滚动列表。

### 8.4 面板结构

展开态包含：

- 顶部标题栏：标题和展开/收起按钮；
- 左侧上方垃圾桶按钮：调用 `MessageSystem.clearAnimated()`；
- `ScrollPane` + `MessageList`；
- 面板右缘外侧的未读消息数标签。

收起态保留标题栏中的切换按钮和单列小方块列表。标题栏底色取当前观看者可见的第一条消息颜色；透明度按优先级为 HIGH `0.75`、MEDIUM `0.55`、LOW `0.38`。

### 8.5 滚动和滚轮焦点

展开态的 `ScrollPane`：

- 垂直滚动条放在左侧；
- 强制启用垂直滚动条，即使当前没有溢出也保持显示；
- 不淡出滚动条；
- 允许 X/Y 滚动。

收起态的 `ScrollPane`：

- 隐藏滚动条；
- 禁用 X/Y 滚动；
- 允许淡出配置，但当前不显示滚动条。

Arc 的 `ScrollPane` 会在鼠标进入其子树时请求场景滚轮焦点。面板因此在 `MessagePanel.act` 中主动释放焦点：

- 展开态：鼠标离开面板后释放；
- 收起态：无论鼠标是否停在小图标区域，都释放该面板的焦点。

这样悬停收起窄条不会把后续滚轮事件锁定到不可滚动的消息面板，滚轮会继续交给场景中实际可用的滚动区域。

### 8.6 Listener 到行的映射

| 系统回调 | 面板处理 |
|----------|----------|
| `messageAdded` | 过滤不可见消息，播放音效，按可见索引插入行，并请求下一帧置顶 |
| `messageRemoved` | 按消息对象找行，标记为左滑淡出；动画结束后从 `MessageList` 删除 |
| `messageTrimmed` | 按消息对象立即移除行，不播放动画 |
| `messagesCleared` | 清空所有行、列表高度和滚动位置 |

`scrollToTopSoon` 只设置一个待处理标志，真正的 `setScrollYForce(0)` 在下一次 `act` 执行，因此暂停时也能把新消息置顶。

### 8.7 MessageList 手动布局

`MessageList` 是 `Group`，不依赖普通 Table 单元格布局，而是在 `reflow` 中每帧计算每行位置：

- 展开态气泡宽度固定为 `bubbleWidth`，不会随着面板收窄而压缩文字；
- 行间距为 `4px`；
- 新消息、展开和移除可以同时播放，不互相覆盖；
- 纵向位置使用指数逼近，避免多条行同时插入时跳变；
- 收起态只保留小方块，列表高度固定为面板高度。

`addVoidChild` 用于 rebuild 后的行，`insertRow` 用于监听器新增事件；两者都会根据当前展开状态设置行的初始动画。

### 8.8 MessageRow 展开/收起内容

展开行由标题、正文和右侧图标组成；正文只有在 `content` 非空或存在 `contentProvider` 时才创建。

收起行由以下部分组成：

- 消息气泡颜色作为小方块背景；
- 透明 `Button` 铺满方块，负责悬停和点击；
- 中央显示 `ICON_SIZE = 22px` 的消息图标；
- `Tooltip` 显示当前标题；
- 点击方块调用 `markRead`；
- 未读时绘制白色边框；
- 已读且有 TTL 时绘制底部倒计时条。

`MessageRow` 还在 `draw` 中自行处理变换、背景、倒计时条、未读高亮和子元素绘制，因此不依赖普通 Table 的默认背景流程。

### 8.9 已读、未读和倒计时

- `readMessages` 是面板进程本地的 `ObjectSet<Message>`，不属于 `MessageSystem` 数据。
- 展开稳定 0.35 秒后，`markVisibleRead` 以行的 95% 可见比例作为自动已读阈值。
- 收起态点击小方块只标记已读，不立即删除消息。
- 瞬时消息只有已读后才推进 `MessageList.age`；持续型消息跳过 TTL。
- 倒计时条透明度按展开状态区分：展开态为气泡色的 50%（满高条，避免遮挡正文），收起态为 80%（底部 4px 细条，是此时唯一反映剩余时长的视觉元素）。两者再乘以行自身 `color.a` 与 `parentAlpha` 跟随淡入淡出。
- 未读数只统计当前观看者可见且不在 `readMessages` 中的消息。
- `rebuild` 通过消息对象继承已读状态和 `age`；网络持续型更新采用原地更新，因此通常不会丢失阅读状态。

### 8.10 持续型实时刷新

`MessageRow.act` 对持续型消息调用 `refreshLive`：

- 展开态每帧重新读取标题和正文；
- 展开态文本变化时更新 `Label`，气泡颜色变化时重新生成背景；
- 收起态至少会刷新 Tooltip 标题；收起态的 `refreshLive` 会直接返回，不重建气泡背景或正文标签。

这条路径不依赖 `MessageSystem` 额外发一个本地 update 回调，联网客户端收到 `OP_UPDATE` 后依靠行自身的逐帧刷新显示快照变化。

## 9. 消息测试方块

`MessageTest` 是当前仓库内的完整调试入口，支持：

- 无模板、紧急、警告、提示、常规五种样式；
- 高/中/低优先级覆盖；
- 无模板时的自定义 HEX 气泡颜色；
- 全局可见或本队可见；
- 正文本地化键演示（标题保持原文）；
- 瞬时消息 TTL；
- 持续型消息发送、文本/样式修改和取消投递；
- 客户端请求服务器确认、来源方块清扫和握手撤销。

它体现了实际接入约定：权威端调用 `MessageSystem.instance.add/post`，纯客户端调用 `MessageSync.requestAdd/requestUpdate/requestRemove`，而不是直接在客户端写入本地数据。

## 10. 使用示例

### 10.1 瞬时本地化消息

```java
Message message = MessageSystem.post(
    MessageSystem.normal(
        Core.bundle.get("example.message.title"),
        Core.bundle.get("example.message.content"))
        .titleKey("example.message.title")
        .contentKey("example.message.content")
        .team(team)
        .icon(Icon.info)
        .life(5f)
);
```

### 10.2 持续型消息和握手

```java
Message message = MessageSystem.post(
    MessageSystem.persistent(Core.bundle.get("example.status.title"), () -> currentStatus)
        .handshake(new MessageSystem.Handshake(() -> isAdded()))
);
```

方块拆除或 `isAdded()` 变为 `false` 后，握手探活失败，消息系统会在下一次全局清扫中移除它。也可以显式调用 `message.handshake.disconnect()` 或 `MessageSystem.remove(message)`。

### 10.3 按运行端选择投递方式

```java
if (MessageSystem.isAuthoritative()) {
    MessageSystem.instance.add(message);
} else {
    MessageSync.requestAdd(message);
}
```

纯客户端必须走请求路径；直接 `add` 只会让消息出现在当前客户端，不会成为服务器权威数据，也不会自动向其他玩家广播。

## 11. 维护约束和回归检查

### 11.1 维护约束

1. 消息源通过 `MessageSystem` 投递，不要直接修改 `MessageSystem.all()`。
2. 固定玩家文本使用 bundle 键；实时变化才使用 `contentProvider` 或 `var`。
3. 瞬时消息的生命周期由每个面板本地推进；持续型消息必须提供明确的撤销路径。
4. 服务器是联网消息的唯一 uid、队伍和来源校验权威。
5. 客户端镜像不传输函数、回调和握手；网络层只处理可序列化的展示快照。
6. 面板是纯视图；需要改变数据时调用 `remove`、`clearAnimated` 或消息源自己的握手。
7. 收起态小图标区域的滚轮焦点必须保持释放，否则 Arc 的 `ScrollPane` 焦点会吞掉后续滚轮事件。

### 11.2 手工回归清单

- 单机投递四种模板，确认优先级和样式正确。
- 展开面板滚轮滚动、鼠标离开后滚轮焦点释放。
- 收起面板悬停小图标，确认其他 UI 仍能接收滚轮。
- 瞬时消息未读、已读、TTL 到期、上限裁剪和垃圾桶清空路径。
- 持续型消息握手断开、来源方块拆除、实时文本/颜色更新。
- 房主、普通客户端、专用服务器和中途加入玩家分别验证同步。
- 改宽度、高度和消息上限，确认 rebuild 不重置已读状态和倒计时。
- 播放自定义消息音效、静音消息和默认 `new-message` 音效。

### 11.3 构建验证

```powershell
.\gradlew.bat jar
.\gradlew.bat check
```

现有独立测试套件覆盖物流算法和仓储规则，不包含消息面板的 Arc 输入事件回归；面板相关行为仍需要游戏内手工验证。
