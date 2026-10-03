# BuildingBoostSystem

## 基本信息

| 属性 | 值 |
|------|----|
| 文件 | `src/silicon/util/BuildingBoostSystem.java` |
| 包 | `silicon.util` |
| 类型 | `final class` 静态工具/调度器 |
| 效果实现位置 | `src/silicon/util/boosts/`（具体 Boost 效果类） |
| 各效果的独立文档 | `docs/boosts/<Boost类名>.md`（逐个效果单元成文，如 `LubricantBoost.md`） |

建筑强化（Boost）体系：一套给「建筑/机器」附加 buff 的**建筑专用强化功能系统**，统一管理强化器（Provider）→ 效果（Boost）→ 目标（Building）的全流程。

## 设计目标

把「谁可以被强化、由谁提供强化、强化什么」解耦成注册式系统，供各类支援方块（如润滑油注入器）复用，避免每个方块私写一套炮塔专用逻辑。

**职责分工**：

| 角色 | 职责 |
|------|------|
| `System` | 驱动循环、资格/队伍校验、跨强化器状态归并、互斥裁决、应用/撤销生命周期、视觉调度 |
| `Boost` | 只写「这个 buff 怎么算、怎么生效」：判定/字段操作/互斥声明/视觉描述 |
| `Provider` | 只声明「我是谁、影响谁、提供哪些 Boost」 |

## 接口契约（System 的嵌套类型）

### `Boost` — 一个 buff 效果单元

| 方法 | 说明 |
|------|------|
| `id()` | 唯一 id，自动注册进注册表，互斥声明/日志引用用 |
| `name()` | 显示名称（本地化，UI 展示用），默认取 `id()` |
| `description()` | **简要强化描述**（本地化），一句话说明收益，如「+100% 旋转速度；+20% 攻击速度」。**每个 Boost 必须实现**；数值须与实际生效值一致 |
| `canTarget(target)` | **目标过滤**（写在各 Boost 里，System 登记前读取）：只接受本效果能作用的对象（如只收炮塔），默认放行 |
| `shouldApply(target)` | 触发条件（无副作用，每帧询问） |
| `apply(target)` | 生效（激活期间**每 tick 一次**，且**只能**由 `flushFrame` 这一个入口触发——见「不叠加规则」） |
| `remove(target)` | 撤销（失格/被顶替/目标失效时一次） |
| `onWorldReset()` | 换图/读档时清本效果自持的静态状态，**默认空实现**。仅自持静态表的效果需覆写（如 `OverclockBoost` 的掉血计时表，其键为 `Building`，不清会泄漏旧世界） |
| `priority()` | 互斥优先级，数值大者胜，默认 0 |
| `conflictsWith(otherId)` | 互斥声明：与指定效果是否冲突。冲突裁决**同级按效果 id 字典序**（纯函数、与放置顺序无关，两端必然一致） |
| `name()` / `description()` | 显示名 / 描述（无目标）。多档位效果的 `description()` 返回**全部档位**作参考 |
| `name(target)` / `description(target)` | **带目标**的显示名 / 描述（默认同上）。多档位效果**应覆写**：`name(target)` 报「名称+档位」、`description(target)` 只报**当前生效档**的加成。UI（消息/面板）一律用这两个 |
| `visual(target)` | 视觉描述（可空，返回 `BoostVisual`） |

`apply` 的两种实现语义（实现自选，保持统一）：

| 语义 | apply | remove | 适用 |
|------|-------|--------|------|
| 注入式 | 每帧把数值加进目标（如 `reloadCounter += ...`） | 无需还原 | 数值累加类（攻速充能） |
| 引用式 | 幂等（内部引用计数，首次才改字段） | 计数递减归零才还原 | 离散倍率类（如 reloadMultiplier） |

### `Provider` — 强化器 mixin

| 方法 | 说明 |
|------|------|
| `building()` | 自身建筑实体 |
| `targets()` | 候选目标集合（默认 `proximity`）；返回 `Seq` 供零分配遍历。强化器可**前置过滤**——非可用对象（如非炮塔）不进 System 循环 |
| `boosts()` | 本强化器提供的效果列表（返回 `Seq`；实现方宜缓存复用该 Seq，避免每帧新建）。**必须恒定返回完整列表**——条件用 `provides()` 表达，否则未列出的效果其旧贡献永不撤销 |
| `provides(boost)` | **逐效果开关**（默认恒为是）。模式/档位类条件写这里——`canTarget` 拿不到「当前是哪个效果」，无法表达「不同模式提供不同效果」。返回 false 即触发该效果的正规撤销路径 |
| `canTarget(target)` | 自身附加过滤（默认全放行，如「本机是否开机」「存油才提供」）。逐效果的条件请用 `provides()` |
| `levelOf(boost)` | 本机为该效果提供的**档位**（默认 0 = 不适用）。供「同一效果多强度档」：效果是单例、档位不能存实例字段（多台塔会互相覆盖），故由 Provider 持有，System 按目标回查（`levelOf(target, boostId)`）后向其索取。正数 = 有效档位（自 1 起） |
| `updateBoosts()` | 接入 System 驱动，Build 的 `update()` 末尾调用 |
| `removeProviderBoosts()` | 在 Build 的 `onRemoved()` 里调用，让 System 撤销本强化器提供的一切强化 |

### `BoostVisual` / `VisualRenderer`

- `Boost` 通过 `visual()` 告诉 System「显示什么」（描述对象）；
- `BoostVisual` 目前提供三个字段方法：
  - `type()` 视觉类型标识（渲染管线分发用，未定型）；
  - `icon(target)` **强化图标**（`TextureRegion`）。**当前全项目统一用一张**：
    `BuildingBoostSystem.badgeIcon()`（原版「超频」状态图标 `StatusEffects.overclock.uiIcon`）——
    徽记表达的是「这座建筑身上有强化生效」，而非「生效的是哪一个效果」（具体哪个效果、哪一档由点击后的
    消息面板逐行列出）。**换图标只改 `badgeIcon()` 一处**。返回 null 时 `BoostOverlay` 会回落到该统一图标，
    故「只要有任意强化生效就一定看得到徽记」；
  - `color()` 徽记配色（用作底板色，**null 时渲染器用默认 `Pal.accent`**）；
- System 经可插拔的 `visualRenderer`（`VisualRenderer` 接口）在 `drawBoosts()` 里统一调度绘制：
  `render(target, visual, index)`——`index` 为该 boost 在目标身上的序号（0 起），供多个徽记排开；
- **现成渲染器**：`silicon.util.BoostOverlay`——实现 `VisualRenderer` 并注册为 `visualRenderer`，
  挂 `Trigger.draw` → `drawBoosts()`，绘制与交互规则：
  - **强化信息按钮**：只要目标身上有任意一个生效的 boost，就在方块左下角渲染一个**可点击按钮**；
    固定 **0.5 格（4×4px）**，锚在 footprint 左下角那一格内侧（紧贴角、不越出方块）；
  - **消息内容按「每个生效效果一行」**渲染，行格式 `boost.info.line` = `[cyan]{名称}[]：{加成}`，
    用的是 **`Boost.name(target)` / `Boost.description(target)`**（带目标的重载）：
    多档位效果据此**只报「名称+档位」与「该档的加成」**，不会把三档全列出来。
    例：`2级超频：+100% 生产效率，+125% 电力消耗，-10 生命/秒`。
    无参的 `name()` / `description()` 拿不到目标，故返回通用名与**全部档位**（供文档/调试参考）。
  - **按钮外观**：统一色 `#99cc3366` 底板（与消息气泡同色）+ 亮色细白边 + 原版图标（占内框 80%）。
    - 注：arc 的 `Draw.rect(region,x,y,w,h,float)` 第 6 参是**旋转角**而非描边宽度，故白边用
      「先铺略大白色矩形、再叠内缩的彩色矩形」的内嵌法实现（**勿**用 rect 的第 6 参当线宽，
      否则会画出整块不透明白色把底色盖住）；
  - **按光标距离动态淡入（分段，取代原悬停高亮/放大）**：不透明度取「光标到按钮中心距离」的
    **分段线性**值（`dist` 为光标世界坐标到按钮中心的距离）：
    - `dist ≤ nearDistance`（**2 格 = 16px**）→ 恒为上限 **80%**（近段平台，不渐变）；
    - `2 ~ 10 格`（`[nearDistance, fadeDistance]`）→ 由 80% **线性衰减**到 0，
      即 `maxAlpha × (1 − (dist − nearDistance) / (fadeDistance − nearDistance))`；
    - `dist ≥ fadeDistance`（**10 格 = 80px**）→ **整颗按钮与图标都不渲染**，自然也不可点击；
    - 整个按钮（白边 + 底板 + 图标）统一按该不透明度绘制；
  - **多个效果合并为一个按钮**（只画第一个），各自详情在消息面板里列出；
  - **点击行为**：向消息面板（`MessageSystem`）投递一条 **10s 时限**消息——
    气泡色 `#99cc3366`（`background()` 同时用作消失时间覆盖层色）；
    标题 `{方块名称}中生效的Boost`，其中**方块名以 `[accent][]` 强调**（key `boost.info.title` + `{0}` 占位符）；
    内容逐行一个 boost，格式 `[cyan]{强化名称}[]：{强化效果}`（key `boost.info.line` 经 `bundle.format` 填充，
    青色名称 + 全角冒号分隔，换行分隔多行）；
    **消息图标用该建筑自身的贴图**（`block.uiIcon` 包成 `TextureRegionDrawable` 并染白，
    缺失时回退 `Icon.info`），便于一眼看出是哪个方块上的强化；
    消息标记 **`.local()`** —— 严格仅投递者自己可见，**不广播**给其他在线玩家（含敌队）；
    （`BoostOverlay` 本身即纯客户端：`Vars.headless` 跳过、不写任何网络化状态）
  - 命中测试用**上一帧**记录的按钮命中区（`Trigger.update` 轮询 `keyTap(mouseLeft)` + `mouseWorldX/Y`），
    `Hit` 对象按索引复用，无每帧分配。
    - **槽位必须用「本帧按钮计数游标」`hitCursor` 分配，不能用 `index`**——`index` 是 boost 在
      目标身上的序号（每目标恒为 0），若拿它当槽位会让所有按钮共用一个 `Hit` 互相覆盖，
      最终只有最后一个按钮可点；
    **命中区与视觉尺寸一致（4×4px）**，故「看得见即可点」；不渲染的按钮不进入命中表。
    投递前再校验目标仍 `isValid()` 且仍有生效强化，避免点到已失效的残留条目；
  - 只画己方 + 视野外跳过（视野矩形每帧只算一次）。
  接入入口 `BoostOverlay.init()`（在 `Silicon.init()` 中调用一次；无头服务器自动跳过）。

## 状态表

| 表 | 结构 | 作用 |
|------|------|------|
| `registry` | `id → Boost` | 效果单元注册表（自动注册，重复 id 覆盖） |
| `contributors` | `target → (boostId → 强化器集合)` | **唯一意愿来源**：集合人数即提供者计数，亦是撤销归属；空集合/空条目即时回收 |
| `suppressed` | `target → (败者Id → 胜者Id)` | 互斥挂起 |
| `active` | `target → (boostId → 是否生效)` | 实际生效快照，驱动 apply/remove（**原地更新**，不每 tick 新建快照） |
| `providerSet` | `Provider 集合` | 存活强化器集合，供每 tick 清扫失效 provider |

> 无独立「pending 计数表」：冲洗发生在本 tick 首个强化器登记**之前**，此时 `contributors` 恰是上一 tick
> 登记完的最终状态，直接读集合人数即可得出应生效集合——省掉每目标每 tick 的内层 Map 分配与计数维护。

## 每帧驱动流程（两阶段，按世界 tick 归组）

`updateBoosts(provider)`（每强化器每帧调一次）：

1. **注册阶段**：对每个目标过公共关（Provider 前置 `targets()` 已滤掉的不会到此）`sameTeam(自身, 目标) && isBoostable(目标) && provider.canTarget(目标)`，
   通过后再过每个 Boost 自身的 `canTarget(目标)` 过滤，最后问 `Boost.shouldApply`；把「想要」的强化器记进 `contributors` 集合，
   （**零分配**：仅在真正增删成员时创建内层容器，稳态不新建对象；空集合/空条目即时回收）。
2. **冲洗阶段**（用 `Vars.state.tick` 归组，**每 tick 只会执行一次**，由本 tick 首次调用触发）：应生效集合直接由
   `contributors` 推导（人数 > 0 且未被挂起），无需独立计数表：
   - `resolveMutex`：对目标上所有「有人提供」的效果按优先级排序、贪心保留；与已保留胜者冲突者写进 `suppressed`（挂起不生效）。挂起表每 tick 按本轮结论重建，胜者消失后败者自然恢复参选。**排序键**：`priority()` 降序 → 效果 id 字典序。**刻意只用纯函数做裁决键**（不引入建筑 id / 放置顺序）：字典序两端必然算出同一结果，且结果与玩家操作时序无关——不会因「谁先放」而改变，也就不存在两端时序不一致的隐患。**快速路径**：效果数 ≤ 1 直接判定无冲突，跳过排序与临时集合；
   - `reconcile`：应生效的**每 tick 调用一次 `apply`**（保证注入式逐帧累加、不叠加），不再应生效的调用一次 `remove`；生效状态表**原地更新**，不新建快照；
   - 随后 `sweepInvalid()`（**每 tick 一次**，不再每强化器每帧各跑一遍）清扫失效目标/强化器。

## 不叠加规则

**一个目标、同一个效果 id，无论本 tick 有多少强化器在提供它，效果也只生效一份、不会成倍放大**——只问「提供者人数 > 0」，
因此每 tick 至多 `apply` 一次（如同「两个一样的 buff 不会并存」）。效果**持续由强化器控制**：只要还有强化器在提供
就保持生效；提供方全部撤走 / 强化器失效 / 目标失格时才 `remove`。

> ### ⚠ `apply` 的唯一入口是 `flushFrame`（这条契约被破过一次）
>
> 「每 tick 至多 apply 一次」**不能只靠 `flushedTick` 守卫守住**——守卫只保护了「谁先触发冲洗」，
> 而 `reconcile` 有**两个**调用点：
>
> | 调用点 | 触发时机 | 是否允许 `apply` |
> |---|---|---|
> | `flushFrame()` | 每 tick 一次（有 `flushedTick` 守卫） | **是（唯一入口）** |
> | `removeProviderContributions()` | 拆强化器时**即时**（**无守卫**） | **否** |
>
> 曾因第二处也调 `reconcile` 而留下真实漏洞：**拆掉一台注入器** → `onRemoved()` →
> `removeProviderBoosts()` → `removeProvider()` → `removeProviderContributions()` →
> 对「该目标上仍有其他提供者」的效果**在同一 tick 再 `apply` 一次**。因为注入式效果是
> 「每 tick 追加一份」，两次就等于**双倍**：转角每 tick 推两格、充能/生产进度加两份、掉血多记一次。
> 且拆了又放回去可以反复触发，等价于**可刷产量/转角**。
>
> 现已由 `reconcile(target, tmap, allowApply)` 参数强制区分：`flushFrame` 传 `true`、
> 即时重算路径传 `false`（只撤销、不应用）。
> **新增第三条即时重算路径时，务必传 `false`。**

## 生命周期兜底

- `sweepInvalid()`：**每 tick 一次**（随 `flushFrame` 触发，不再每强化器每帧各跑一遍），扫 `active`/`contributors`/`suppressed`，目标已失效/拆除时调 `removeBuilding()`——撤销该目标仍生效的效果并清光全部状态；同时扫 `providerSet`，强化器本机已失效的调 `removeProvider()` 清掉贡献——**不留残留、不漏撤销**。
- `removeProvider(provider)`：**即时**撤销该强化器对全部目标的贡献并重算受影响目标（在 Build.`onRemoved()` 里经默认方法 `removeProviderBoosts()` 调用，也可由清扫兜底触发），防止拆除后贡献残留在状态表里。
- `reset()`：**换图/读档时清空全部状态表**（已挂在 `EventType.WorldLoadEvent`，见 `Silicon.java`）。**换图必须清**，原因有两条，都不能靠 `sweepInvalid()` 兜住：
  1. **内存泄漏**：`Building → Tile → World` 是强引用链，四张表不清就把**整张旧地图**钉在内存里。
  2. **幽灵徽记**：`Building.isValid()` 的实现是 `tile != null && tile.build == this && !dead()`——
     旧世界的建筑**仍然通过**校验。而新图若无强化器，`flushFrame()` 根本不会被触发
     （它由强化器的 `update()` 驱动），`sweepInvalid()` 也就永不执行，旧建筑会继续被 `drawBoosts()` 画徽记。
  - 附带把 `flushedTick` 复位，使新世界第一个 tick 重新触发一次冲洗。
  - 各 `Boost` 自持的静态表经新增的 `Boost#onWorldReset()` 回调清理（默认空实现；如 `OverclockBoost` 的掉血计时表）。

> #### ⚠ `reset()` 只能挂 `WorldLoadEvent`，**绝不能挂 `PlayerJoin`**
>
> 曾误挂在 `EventType.PlayerJoin` 上（评审 rt334 复审时发现），后果有三条，且**都不是崩溃级、很难自查**：
>
> 1. **任何玩家加入都会清空全局强化状态**——包括 `suppressed`（玩家手动关闭的强化被重新打开）、
>    以及已稳定生效的注入式累积（`reloadCounter` 进度、进度条等）。
> 2. **`onWorldReset()` 被反复触发** ⇒ 超频的掉血计时归零 ⇒ **惩罚可被joining 反复重置**（人人可刷）。
> 3. **客机也会执行**：该调用原先落在 `if (net.server())` **之外**，于是两端在「玩家加入」这一帧
>    各自清表，而加入事件的触发时机/次数并不保证两端一致 ⇒ **纯粹的本地模拟量也可能被清出不同结果**。
>
> 反过来说，`reset()` 是**跨世界**清理，不该由任何「会话内事件」触发——它只回答一个问题：
> 「上一张地图的状态还该不该留着」。答案只在新图/读档时才是「否」。

## 性能与资源约定

| 点 | 做法 |
|----|------|
| 每帧分配 | 登记路径**零分配**：仅在成员真正增删时创建内层容器；`flushBuffer`/`affectedBuffer`/`idBuffer`/`badTargets`/`badProviders` 等暂存集合**全部为静态复用**（含 `sweepInvalid` 的两个容器——早前每 tick `new` 会与本表宣称的「零分配」矛盾） |
| 快照 | `active` 生效表**原地更新**，不再每 tick 为每个目标新建快照 Map |
| 清扫 | `sweepInvalid` 由每帧 N 次（每强化器一次）降为**每 tick 1 次** |
| 互斥 | 效果数 ≤ 1 走快速路径，跳过排序与临时 Seq/Map（候选 > 1 时的 `next`/`ids`/`keep` 仍是临时对象，但只在该分支发生） |
| 迭代 | `ObjectMap`/`ObjectSet` 一律「先收集再删除」（`keys()` 依赖内部数组，边遍历边 remove 会抛并发修改异常）；用可复用缓冲中转。**包括回调第三方代码的场合**——`removeBuilding()` 先把 id 收进 `idBuffer` 再回调 `boost.remove()`，否则第三方 `remove()` 反过来动状态表就会炸 |
| Provider 侧 | `targets()`/`boosts()` 返回 `Seq` 走下标遍历；实现方应缓存复用（注入器按 tick 重建并复用炮塔缓存，扣油阶段共用同一份）。两者返回 `null` 均按「不提供/无目标」安全降级，不抛异常 |
| 渲染 | `BoostOverlay` 每帧只算一次视野矩形；按钮 4px 固定故不会超出方块轮廓；按光标距离**分段**淡入（≤2 格恒 80%，2~10 格线性降到 0，超出 10 格不渲染，同时省去绘制）；命中区按索引复用；无面板 UI（详情走消息面板，零 UI 开销） |

## 多人 / 队伍安全

- `sameTeam` 直接以 `Team` 枚举单例的 `==` 比较：跨端一致、确定，天然排除敌队/derelict，不依赖本地身份。
- **目标过滤四层把关**（缺一不可，任何效果生效前都过）：
  1. Provider `targets()` 前置过滤——非可用对象连 System 循环都不进；
  2. System 全局名单 `boostableTypes`（默认放行**炮塔** `TurretBuild` 与**工厂** `GenericCrafterBuild`；
     新增可强化类型须用 `addBoostable(...)` 登记，否则效果永不生效）；
  3. 同队 `sameTeam`；
  4. 每个 Boost 自带的 `canTarget(target)`（过滤逻辑写在 Boost 单元内，由 System 读取执行）。
  防止对非目标方块误用炮塔专用 API 造成崩溃。

### 多人兼容约定

- 本系统状态（`contributors`/`active`/…）是**纯 JVM 本地**的，客户端与服务器各自驱动、不同步——
  因为它只影响**纯本地模拟**（炮塔 `reloadCounter`/转角，机制同原版强化液），两端由相同输入算出相同结果。
- **队伍**：一律 `==` 比较 `Team`（跨端一致枚举单例）；禁止用本地玩家身份做规则判定（服务器无本地玩家）。
- **确定性红线**：任何决定「扣/加多少网络化状态（液体/功率/库存）」的分支都**不得依赖 update 顺序或
  「谁先注册」**——两端建筑 update 顺序不保证一致，会造成永久分歧（desync）。必须用两端一致的量裁决
  （tile 坐标、队伍、已同步的液体量）。参考实现：`LubricantInjector.owns()` 按 (tileX, tileY)
  裁决唯一认领者。System 曾提供的 `claimedByOther`（基于「谁先跑 update」）因属顺序依赖、存在 desync 隐患，
  **已删除**，不再作为对外 API。

### 档位裁决为什么不用建筑 id

`levelOf(target, boostId)` 在同一目标有多个 Provider 时需要一个**跨端一致的裁决键**。曾经的实现用
`Building.id` 最小者，**现已改为 `(tileX, tileY)` 字典序最小者**，原因：

- `Building` 的 `id` 在**构造时**由 `EntityGroup.nextId()` 在**各端本地**分配，只靠实体状态快照
  间接同步；而 `Building.write(Writes)` 本身是**空实现**（不序列化 `id`）。也就是说 `id` 是否在
  「客户端 / 服务器 / 存档加载后」三者间始终一一对应，属于**未经验证**的假设。
- 建筑 update 顺序、以及快照到达的先后都可能让两端 `id` 分配序列出现分歧，一旦用 `id` 决定
  「用谁的档位」，就会算出**不同的耗电/掉血量**——正是上面那条确定性红线要禁止的事。
- `(tileX, tileY)` 由地图派生，**两端必然一致**，且与遍历/登记顺序无关；口径也与
  `LubricantInjector.owns()` 一致，全项目统一。

同理，`resolveMutex` 的互斥裁决只按「优先级 + 效果 id 字典序」决定**哪个效果胜出**（不依赖 Provider 顺序），
`contributors` 用 `ObjectSet` 遍历顺序不影响最终结果，因为结果只依赖上面这些确定性键。
- 客户端独有逻辑（`BoostOverlay` 调试渲染）由 `Vars.headless` 跳过，且只读网络化状态。

## 对外 API

| 方法 | 说明 |
|------|------|
| `addBoostable(Class)` | 向规则名单追加可强化类型（加载期登记） |
| `isBoostable(Building)` | 名单检查 |
| `sameTeam(Building, Building)` | 同队判定 |
| `get(id)` | 注册表按 id 取效果 |
| `register(Boost)` | 自动注册效果（加载期调用，重复 id 覆盖） |
| `levelIndex(level, length)` | 档位（自 1 起）→ 倍率表下标，越界夹到最近合法档。**仅在 `length >= 1` 时保证返回值合法** |
| `levelValue(table, level)` | 按档位取倍率表的值，等价 `table[levelIndex(level, table.length)]`，但**表为空时回落 `1f`（不改变任何量）**而不抛越界。倍率表是各效果上的 public 可变字段，被别的 mod 置空时不应让游戏崩在这种地方——**倍率表一律走本方法，不要用 `levelIndex` 的下标去索引另一张表**（长度不一致即 AIOOBE） |
| `percentText(ratio)` | 倍率差 → 百分比文本，**符号与数值一致**（`0.5f` → `"+50%"`，`-0.2f` → `"-20%"`）。**调用方须传带符号差值 `scale - 1f`**：节能为负、超频为正。本方法只加符号、**不反转** |
| `reset()` | **换图/读档清空全部状态表**（已挂 `WorldLoadEvent`，**不得挂 `PlayerJoin` 等会话内事件**）。必须清的理由见「生命周期兜底」——`isValid()` 放行旧世界建筑，清扫兜不住 |
| `activeBoosts(target)` | 查目标当前生效的**效果单元列表**（`Seq<Boost>`，每次新建，**仅供点击/开面板等低频路径**，勿放每帧循环） |
| `hasActiveBoosts(target)` | 目标是否还有生效强化（零分配，供每帧轮询） |
| `isActive(target, boostId)` | 指定效果当前是否生效（零分配 O(1)）。**供「引擎钩子」在被引擎回调时查询自身倍率**——钩子不能缓存每建筑状态（缓存会产生两端不同步窗口） |
| `levelOf(target, boostId)` | 该效果在目标上的**档位**（0 = 无有效提供者）。**供「同一效果多强度档」**（如效率控制塔 1~3 级节能/超频）：效果实现是**单例**、档位不能存实例字段（多台塔会互相覆盖），故档位由 Provider 持有、按目标回查。多个提供者时取 **格坐标 `(tileX, tileY)` 字典序最小**者（与 `LubricantInjector.owns()` 同口径；**刻意不用建筑 `id`**，见下方「档位裁决为什么不用建筑 id」）。零分配 |
| `levelValue(table, level)` | 按档位取倍率表的值，等价 `table[levelIndex(level, table.length)]`，但**表为空时回落 `1f`（不改变任何量）**而不抛越界。倍率表是各效果上的 public 可变字段，被别的 mod 置空时不应让游戏崩在这种地方 |
| `isProviderOf(target, provider, id)` | 提供归属：本强化器是否当前有效提供者（读 `contributors`，两端一致，**可安全用于**耗网络化资源决策） |
| `updateBoosts(provider)` | 每帧驱动入口（兼触发每 tick 统一冲洗） |
| `removeProvider(provider)` | 移除强化器并即时重算受影响目标（`onRemoved` 钩子 / 清扫兜底） |
| `badgeIcon()` | **强化徽记的统一图标**（`TextureRegion`）。全项目只有这一张，各 `Boost.visual()` 均取此处；要换图标只改这一处 |
| `drawBoosts()` | 渲染管线每帧调用，调度视觉 |

## 使用示例

### 1. 实现一个 Boost 效果（`src/silicon/util/boosts/` 下）

```java
public class AttackSpeedBoost implements BuildingBoostSystem.Boost {
    @Override public String id() { return "attack-speed"; }
    @Override public boolean shouldApply(Building target) {
        return target instanceof Turret.TurretBuild t && t.isShooting();
    }
    @Override public void apply(Building target) {   // 注入式：每帧累加
        Turret.TurretBuild t = (Turret.TurretBuild) target;
        t.reloadCounter += 0.2f * t.edelta() * (t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f);
    }
    @Override public void remove(Building target) { /* 注入式无需还原 */ }
    @Override public int priority() { return 1; }
    @Override public boolean conflictsWith(String otherId) { return otherId.equals("overdrive"); }
    @Override public BoostVisual visual(Building target) { return new MyBoostVisual(); }
}
```

加载期注册：`BuildingBoostSystem.register(new AttackSpeedBoost());`

### 1b. 钩子式效果（改不动「每建筑字段」时）

有些量（如工厂的**耗电量** `ConsumePower.usage`、**生产速率** `GenericCrafter.craftTime`）在 MJ 里是
**方块类型级共享对象**，直接改会连带影响该类型所有建筑（含敌方）→ 不可用。
此时应改用引擎留给「按建筑个体」的 consumer 扩展点，见
`src/silicon/util/boosts/BlockConsumerHooks.java` 与 `docs/boosts/EnergySavingBoost.md`：

```java
// apply() 只负责惰性安装钩子（幂等，只装被命中的方块）
@Override public void apply(Building target) {
    BlockConsumerHooks.install(target.block, hooks);
}
// 倍率由钩子每次被引擎查询时回调 isActive() 实时读 → 撤销自动回 1.0，无需还原
@Override public void remove(Building target) { /* 钩子式：无需还原 */ }
```

**要点**：钩子**不得缓存**每建筑状态（会产生两端不同步窗口），只能在被查询时回到 `isActive()` 问一次。

### 2. Provider 方块接入

```java
public class LubricantInjectorBuild extends Building implements BuildingBoostSystem.Provider {
    @Override public Building building() { return this; }
    @Override public Iterable<BuildingBoostSystem.Boost> boosts() {
        return Seq.with(BuildingBoostSystem.get("attack-speed"), BuildingBoostSystem.get("rotation-speed"));
    }
    @Override public void update() {
        super.update();
        updateBoosts();   // 一行接入驱动
    }
}
```

#### ⚠ Provider 作者必读：想让效果「停掉」时，只能靠 `canTarget()`

System 撤销某个 Provider 贡献的**唯一**路径是：该 Provider 仍被 `targets()` 遍历到，
但它对某个 boost id 的**意愿为 false**（`collectContributions` 内的 `set.remove(provider)`）。
因此 Provider 若有「开关 / 模式 / 停机条件」，**必须**遵守：

| 错误做法 | 后果 |
|---------|------|
| 关闭时 `boosts()` 返回空列表 | 该 boost id 根本不会被遍历 → 旧贡献永不撤销 → 效果卡在开启状态 |
| 关闭时 `targets()` 返回空列表 | `updateBoosts()` 因 `targets().isEmpty()` 提前 return → 同上 |
| 关闭时干脆不调 `updateBoosts()` | 同上 |

正确做法：`boosts()` / `targets()` 恒定返回完整集合，把条件放进 `canTarget(target)`：

```java
// 正确：条件只判在 canTarget，boosts()/targets() 与开关无关
@Override public boolean canTarget(Building target) { return enabled && mode != modeOff; }
@Override public Seq<Boost> boosts() { return boostList; }            // 恒定
@Override public Seq<Building> targets() { return inRangeBuildings; } // 恒定（含关闭态）
```

参考实现：`EfficiencyControlTower`（`docs/blocks/EfficiencyControlTower.md`）——「关闭」模式下仍会做
一次区域查询，正是为了能走通撤销路径。

> 例外：`onRemoved()` 走 `removeProviderBoosts()`（`removeProvider`），那条路径**不**受上述限制，
> 因为它主动遍历并清空该 Provider 的全部贡献。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始骨架：注册表、状态表、每帧驱动、互斥裁决、撤销生命周期、视觉调度预留 |
| a0.x | 重构为两阶段驱动：`pending` 本 tick 跨强化器累计意愿 + 每 tick 统一冲洗（flushFrame）；生效期间**每帧 apply**、不叠加（同目标同效果至多一份）；新增 `providerSet`/`removeProvider`/`onRemoved` 钩子，强化器拆除即时撤销贡献 |
| a0.x | 目标过滤双层化 + 调试视觉上线：`Boost.canTarget(target)` 过滤由 System 登记前读取；`BoostVisual` 增加 `label`/`color`，新增 `BoostOverlay` 调试渲染器在受惠方块上方绘制效果名（`Trigger.draw` 挂钩，己方 + 视野裁剪） |
| a0.x | 修复生效持续性问题：`pending` 每帧按 `contributors` 集合人数重算（而非仅增删时增量），解决 contributors 持久 + pending 每 tick 清空导致的首帧后计数归零、效果 apply 后被误 remove 的问题 |
| a0.x | 多人兼容加固：`sweepInvalid()` 改为先收集再删除（避免 `ObjectMap.keys()` 边遍历边 remove 抛并发修改）；新增「多人兼容约定」章节（队伍 `==`、网络化状态不得依赖 update 顺序）；`claimedByOther` 标注为仅限本地表现 |
| a0.x | 性能优化：删除 `pending` 计数表（应生效集合直接由 `contributors` 人数推导）；`active` 原地更新不再每 tick 新建快照；`sweepInvalid` 由每帧 N 次降为每 tick 1 次；互斥 ≤1 效果走快速路径；登记路径零分配 + 暂存集合全部复用；`Provider.targets()/boosts()` 改返回 `Seq` 走下标遍历；注入器缓存复用炮塔列表与 boost 列表；`BoostOverlay` 每帧只算一次视野、复用 `GlyphLayout` |
| a0.x | 删除顺序依赖的 `claimedByOther` API（无人调用且有 desync 隐患）；修复 3 处「`ObjectMap.keys()` 边遍历边 remove」的并发修改崩溃点 |
| a0.x | 视觉改为**图标徽记**：移除文字显示，`BoostVisual.label` → `icon(TextureRegion)`；`VisualRenderer.render` 增加 `index` 参数支持多徽记沿底边排开；`BoostOverlay` 在方块左下角绘制原版图标 + 底板（`Layer.overlayUI`）；移除随之无用的 `boost.lubricant.name` bundle |
| a0.x | 徽记样式调整：底板色由润滑油棕改为游戏强调色 `Pal.accent`（绿）；边长按方块尺寸自适应、以 32px 为上限（1×1→8×8，2×2→16×16），紧贴 footprint 左下角内侧且不越出方块轮廓；多个徽记按方块宽度换行 |
| a0.x | 徽记固定为左下角 **1×1 格**（8×8px，不再随方块放大）；多效果合并为一枚徽记。`Boost` 新增 `name()` / `description()`（描述为**必实现**项）。新增**点击交互**：点击徽记展开强化详情面板（列出各生效 boost 名称+简述），支持再点/×/Esc/点别处关闭、目标失效自动关闭；新增 `hasActiveBoosts()` 零分配判活；bundle 新增「建筑强化系统」分区（`boost.panel.title`、`boost.lubricant.name/desc`） |
| a0.x | **重写为强化信息按钮**：去掉面板 UI，改为固定 0.5 格（4×4px）可点击按钮（`#99cc3366` 底板 + 亮色描边 + 原版图标，悬停提亮放大）；点击向消息面板投递 10s 时限消息（气泡 `#99cc3366`）：标题 `[accent]{方块名}[]中生效的Boost`，内容逐行 `[cyan]{强化名}[]：{强化效果}`，消息图标用目标建筑的 `uiIcon` |
| a0.x | 修复按钮配色与点击：白边改用「内嵌法」（arc `Draw.rect` 第 6 参是旋转角，原写法画出不透明白块盖住底色导致配色不生效）；命中区独立放大到 1.5 格（视觉仍 4px）解决小目标点空；投递前校验目标仍有效且有生效强化 |
| a0.x | 消息时限 15s → **10s** |
| a0.x | 强化信息消息标记 `.local()`：房主点击不再把详情广播给全服，严格仅点击者自己可见（`Message.local` + `MessageSync` 跳过广播） |
| a0.x | 修复「只有最后一个按钮可点」：命中区槽位原用 `index`（boost 序号，每目标恒为 0）导致所有按钮共用一个 `Hit` 互相覆盖，改为按本帧按钮计数游标 `hitCursor` 分配 |
| a0.x | 按钮改为**按光标距离动态淡入**（取代悬停高亮/放大）：不透明度 `0.4×(1−dist/24px)` 线性变化，超出 3 格不渲染按钮与图标；命中区回归视觉尺寸 4×4px（移除 `hitSize` 放大与 `hoverScale`，保证可见即可点） |
| a0.x | 新增钩子式效果范式与 `isActive(target, boostId)`（零分配 O(1)）：供效果在「引擎钩子被回调时」查询自身生效状态。新增首个钩子式效果 `EnergySavingBoost`（`docs/boosts/EnergySavingBoost.md`）+ 通用钩子工具 `BlockConsumerHooks`；新增「1b. 钩子式效果」示例章节 |
| a0.x | 名单 `boostableTypes` 追加工厂 `GenericCrafterBuild`（原仅炮塔）；新增「⚠ Provider 作者必读：想让效果停掉时只能靠 `canTarget()`」——记录撤销贡献的唯一路径（`boosts()`/`targets()` 恒定返回完整集合，条件只判 `canTarget`），避免开关类 Provider 出现贡献永不撤销的卡死 |
| a0.x | 互斥裁决**保持**「优先级 + 效果 id 字典序」的纯函数排序键（曾短暂试过按提供者建筑 id 实现「先放置者胜」，已撤回）：字典序两端必然一致，且结果与放置顺序/玩家操作时序无关，不会因「谁先放」而改变；例：节能(`energy_saving`) 与 超频(`overclock`) 冲突时恒为节能胜出 |
| a0.x | 新增「档位」范式：`Provider.levelOf(Boost)` + `BuildingBoostSystem.levelOf(target, boostId)`（零分配，多提供者取 **(tileX, tileY) 字典序最小**者）。供「同一效果多强度档」——效果实现是单例，档位存实例字段会被多台提供者互相覆盖，故档位由 Provider 持有、按目标回查。`BlockConsumerHooks.FactorSource` 的倍率方法改为接收档位参数 |
| a0.x | **强化徽记图标统一**：新增 `silicon.util.boosts.BoostBadge.icon()` 作为唯一图标来源（原版「超频」状态图标），三个效果的 `visual()` 全部改用它，换图标只需改一处。徽记语义收敛为「该建筑有强化生效」，具体效果/档位由点击后的消息面板逐行列出。`BoostOverlay.render` 增加回落：`visual()` 或其 `icon()` 为 null 时用统一图标兜底，保证「有强化必显徽记」 |
| a0.x | 合并小工具类进 System：原独立的 `boosts.BoostBadge` / `boosts.BoostText` 迁入本类，成为 `BuildingBoostSystem.badgeIcon()` / `levelIndex(level, length)` / `percentText(ratio)`，两个文件删除（`boosts` 包只剩 `BlockConsumerHooks` 与三个效果实现） |
| a0.x | 徽记淡入改为**分段**：≤2 格恒定 80%（`maxAlpha` 0.4→0.8，新增 `nearDistance` = 2 格）、2~10 格线性衰减到 0（`fadeDistance` 3 格→10 格）、超出 10 格不渲染 |
| a0.x | **修复「冒号跑到行首」**：`arc` 的 `Label` 换行是**按字符硬断**（`GlyphLayout.setText` 的 `lookupswitch` 只认 `10`=`\n` 强制换行与 `91`=`[` 颜色标记，其余一律当普通字形），**无 CJK 标点禁则**；故加宽/收窄面板时全角冒号 `：` 会落到下一行行首。修为在 bundle 的 `boost.info.line` 里把冒号后的分隔改为**显式换行**：`[cyan]{0}[]：\n{1}`，使「{名称+档位}：」恒在第一行、加成恒在第二行。

> `\n` 须写成 **properties 转义字面量**（反斜杠 + n，非真实换行符）——已实测引擎的 `arc.util.io.PropertiesUtils.load` 会把它还原为 LF(10)（该解析器是 `java.util.Properties` 的移植，含 `SLASH`/`UNICODE`/`CONTINUE` 状态机）。
>
> **为什么不用 TAB（U+0009）**：tab 在 `GlyphLayout` 里落到 `default` 分支被当普通字形，既**不产生断行约束**（换行位置纯由像素宽度决定），其**本身还会占一个字形宽度**（位图字体无 tab 字形），反而更容易把冒号挤下去。 |
| a0.x | **自检修复**（多人/健壮性）：① `levelOf` 多提供者裁决键由「建筑 `id` 最小」改为「`(tileX, tileY)` 字典序最小」，移除对 `EntityGroup.nextId()` 本地分配这一未验证量的依赖（详见「档位裁决为什么不用建筑 id」）；② `BoostOverlay.checkInput` 命中失效条目时由 `return` 改为 `continue`——原先一个恰好盖住光标的失效条目会吞掉整次点击，导致其后真正命中的徽记点不到（徽记密集时尤其明显）；③ `EfficiencyControlTower.drawPlace` 补 `Vars.player == null` 保护（无头/专服渲染时 `Vars.player.team()` 会 NPE），此时退回按「可放置」显示、不做重叠判定；④ `BoostOverlay.postBoostInfo` 补 `target.block == null` 保护；⑤ 新增 `levelValue(table, level)` 并让两个效果的 `FactorSource` 走它——倍率表被置空时回落 `1f` 而非抛数组越界，`OverclockBoost.apply` 的提速/掉血同样加空表保护（空表 = 既不提速也不掉血） |
| a0.x | **采纳外部评审（rt334，实现层）**共 8 条、驳回 1 条：①【阻塞·已修】`reconcile` 的 apply 分支无条件调用，而它有**两个**调用点、只有 `flushFrame` 带 `flushedTick` 守卫：拆强化器 → `onRemoved` → `removeProviderContributions` → 同 tick 对「仍有其他提供者」的效果**再 apply 一次**，注入式效果双倍（转角两格/充能两份/多记一次掉血），且拆了又放回可反复触发＝可刷产量。改为 `reconcile(target, tmap, allowApply)`，`flushFrame` 传 `true`、即时重算路径传 `false`；②【高·已修】四张状态表以 `Building` 为键且全类**无任何 `Events.on`**——`Building → Tile → World` 强引用链会把整张旧地图钉在内存，且 `isValid()`（`tile != null && tile.build == this && !dead()`）**放行旧世界建筑**，新图无强化器时 `flushFrame` 不跑 ⇒ `sweepInvalid` 也不跑 ⇒ 旧建筑继续被画徽记。新增 `reset()` 并挂 `EventType.WorldLoadEvent`（`Silicon.java`），含 `flushedTick` 复位与新增的 `Boost#onWorldReset()` 回调（`OverclockBoost` 覆写以清掉血计时表）；③【中·已修】`sweepInvalid` 每 tick `new ObjectSet`/`new Seq` 改为静态复用 `badTargets`/`badProviders`；④【中·已修·修正建议写法】`BoostOverlay.checkInput` 缺 UI 焦点判定：本钩子跑在输入阶段、**早于 Scene 消费点击**，故在建造菜单/消息面板上点到光标下徽记仍会投递消息。补 `Core.scene.hasMouse()`——**不能用评审建议的 `hasMouse(wx, wy)`**：该重载走 `Scene.hit(x,y)`，要求**控件局部坐标**，传世界坐标无意义（无参版才是 `getHoverElement() != null`）；⑤【中·已修】`LubricantBoost` 攻速注入条件比引擎 `handleReload` 宽：引擎是 `if(!reloadWhileCharging && charging()) return; if(reloadCounter >= reload) return;`，而本注入只看 `isShooting()`——**已充满**的炮塔每 tick 仍被注入，而 `updateShooting` 减去 `reload` 后**保留溢出**，溢出累积成「补弹瞬间连发」的蓄力 bursts，实际收益远超文案 `+20%`；充能中的炮塔也会被推进。新增 `canEngineReload()` 逐条镜像引擎守卫；⑥【低·已修】`summary()` 用「一张表算出的下标索引另一张表」（三张 public 可变表长度不一致即 AIOOBE），改为逐表走 `levelValue`；⑦【低·已修】`Provider.boosts()` 缺 null 判（`targets()` 有），第三方返回 null 会 NPE；⑧【低·已修】`removeBuilding` 是唯一「边遍历 `map.keys()` 边回调第三方 `boost.remove()`」的路径，改走 `idBuffer` 先收集。**驳回**：`Hit.target` 从不置空 alleged 泄漏——`hits.clear()` 每帧执行，引用当帧即释放，且有 `isValid()` 兜底，非缺陷 |
| a0.x | **采纳 PR #74 复审（rt334）2 条仓库级问题**：① `BuildingBoostSystem.reset()` 误挂在 `EventType.PlayerJoin`（而非文档所写的 `WorldLoadEvent`），已移回 `WorldLoadEvent`。挂 `PlayerJoin` 会让**任何玩家加入**都清空全局强化状态（`suppressed` 被清⇒玩家手动关闭的强化重新打开）、反复触发 `onWorldReset()`（超频掉血计时归零⇒**惩罚可刷**），且该调用原在 `if (net.server())` **之外**、**客机也执行**⇒两端清表时机不一致。② 删除 bundle 里的孤儿 key `item.silicon-thulium.*`（铥）及其空的「物品」分区：该物品在 `src/` 与 `test` 分支均无任何定义/引用（仅 6 行 bundle，自带 `(Deprecated)` 标记），给不存在的物品留 key 不产生任何效果，也救不了旧存档 |
