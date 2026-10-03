# EfficiencyControlTower

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `EfficiencyControlTower` |
| 父类 | `Block` |
| 方块 id | `efficiency-control-tower` |
| 分类 | `Category.effect` |
| 尺寸 | 3x3 |
| 血量 | 300 |
| 架构 | `BuildingBoostSystem.Provider`（提供 `EnergySavingBoost`，由 System 驱动） |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 200 |
| Lead | 150 |
| Silicon | 80 |

> 配方为本方块自定初值（需求未指定），可按平衡需要调整。

## Block 属性

- `update`: true
- `solid`: true
- `configurable`: true
- `saveConfig`: true（随存档保存模式）
- `copyConfig`: true（复制/粘贴蓝图保留模式）
- 区域边长：`range = 15f`（格），以本方块**中心**为中心的正方形区域

## 机制说明

效率控制塔是一个**区域型支援方块**：以塔为中心、15×15 格的区域内，己方所有**消耗电力的工厂**
附上 `EnergySavingBoost`（电力 −20%、生产速度 −10%）。

本方块只负责三件事：**圈定区域内耗电建筑**（`targets()`）、**当前是否提供**（`canTarget()`）、
**声明自己提供节能效果**（`boosts()`）。效果本身怎么算、怎么注入全在 `EnergySavingBoost` 里，
资格/队伍/不叠加/apply-remove 生命周期全由 System 兜底。

| 归属 | 内容 |
|------|------|
| 本方块 | 区域有多大、区域内哪些建筑算目标、什么模式下提供 |
| `EnergySavingBoost` | 耗电与速率两个子效果的具体实现、目标过滤、展示信息 |
| `BuildingBoostSystem` | 登记、队伍门禁、不叠加、apply/remove 驱动、撤销生命周期 |

### 目标过滤

| 层级 | 判定 |
|------|------|
| `targets()` | 区域内 + 同队 + `block.consPower != null`（**耗电**）。非耗电建筑不进 System 循环 |
| `canTarget()` | `enabled`（本机是否开机） |
| `provides(boost)` | `!conflicted && levelOf(boost) > 0`（档位为 0 即不提供；关闭 → 都不提供） |
| `levelOf(boost)` | 节能取 `-mode`、超频取 `mode`（夹到 1~3），关闭/越界/冲突为 0 |
| `Boost.canTarget()` | 耗电 **且** `block instanceof GenericCrafter`（**工厂**：炉/熔炉/压机/粉碎机/合金熔炉等及其子类） |
| `Boost.shouldApply()` | 节能：目标工厂自身 `enabled`（被关掉的工厂不生效）；超频：另有「确实在运转」判定 + 去抖（`enabled && efficiency > 0 && warmup >= warmupThreshold`），见 `docs/boosts/OverclockBoost.md` |

> 「工厂」判定在效果侧，故 `targets()` 只做粗筛（耗电），避免把区域内所有建筑都塞进 System 循环。

### 档位（配置 UI）

配置面板只有一个**滑块**，范围 `[-3, +3]`、步长 1，即 **7 个离散档位**。
自左至右为「3级节能 → 2级 → 1级 → **关闭** → 1级超频 → 2级 → 3级」，**关闭恰在正中**且为默认值。

| 滑块值 | 档位 | 效果 |
|--------|------|------|
| −3 | 3 级节能 | 耗电 −70%，生产 −50% |
| −2 | 2 级节能 | 耗电 −40%，生产 −30% |
| −1 | 1 级节能 | 耗电 −20%，生产 −15% |
| **0** | **关闭**（默认） | 不提供任何强化 |
| +1 | 1 级超频 | 生产 +50%，耗电 +30%，每秒 −4 生命 |
| +2 | 2 级超频 | 生产 +100%，耗电 +125%，每秒 −10 生命 |
| +3 | 3 级超频 | 生产 +300%，耗电 +500%，每秒 −45 生命 |

- 编码：**<b>负数 = 节能，0 = 关闭，正数 = 超频</b>，绝对值即档位**（1~3）。
  故「关闭」在 `[-3,+3]` 中天然居中。
- 走**标准 config 链路**：UI `configure(mode)` → `Call.tileConfig` → 两端 `configured()` → 本方块的
  `config(Integer.class, ...)` 处理器，故多人下全端一致。
- 档位经 `write`/`read` 持久化（`short`，范围 `[-3,+3]`），随存档保存；`config()` 返回当前值，
  故复制蓝图/放置预设会带上档位。
- 被开关禁用的塔（`enabled == false`）停止提供。
- 档位说明**不另建 key**，直接用效果的 `name(level)`（`boost.energy_saving.levelName` /
  `boost.overclock.levelName`）+ `summary(level)` 拼成——单一数据源，配置面板与强化消息面板的文案必然一致。

#### 配置面板布局

```
┌──────────────────────────────┐
│ 模式                          │  标题
│ [=========o=]                 │  滑块（固定宽度）
│  2级节能，-40%电力消耗，-30%生产效率 │  当前档位 + 加成（单行，居中，按档位着色）
└──────────────────────────────┘
```

- **面板宽度固定 320px**（`uiWidth`）。原因：若面板随内容宽度变化，**滑块长度就会跟着变**，
  同一个像素位置在「关闭」与「1级节能」下可能对应不同档位，无法精确点选。
  故 `pane.defaults().width(uiWidth)` 锁死所有单元格宽度，滑块与文本都不撑宽面板。
- 档位说明为**单行**「`{档位名}，{加成摘要}`」（`modeText(mode)`），`setWrap(true)`：
  **超出宽度强制换行**，绝不改变面板尺寸。关闭态显示 `bonus.off`（「无效果」）。
- 滑块拖动时同步刷新该行文本（`setText` + `setColor`，按档位着色），加成取效果的 `summary(level)`，
  与强化消息面板同一份数值，不会两处不同步。
- 实际渲染文本（中文）：

  | 档位 | 面板/详情行 |
  |---|---|
  | 1级节能 | `1级节能，-20%电力消耗，-15%生产效率` |
  | 2级节能 | `2级节能，-40%电力消耗，-30%生产效率` |
  | 3级节能 | `3级节能，-70%电力消耗，-50%生产效率` |
  | 关闭 | `无效果` |
  | 1级超频 | `1级超频，+50%生产效率，+30%电力消耗，-4生命/秒` |
  | 2级超频 | `2级超频，+100%生产效率，+125%电力消耗，-10生命/秒` |
  | 3级超频 | `3级超频，+300%生产效率，+500%电力消耗，-45生命/秒` |

  分隔符（全角逗号）与「百分比后不加空格」由 bundle 决定：
  `boost.energy_saving.line` = `{0}，{1}`、`boost.energy_saving.bonus` = `{0}电力消耗，{1}生产效率`、
  `boost.overclock.line` = `{0}，{1}`、`boost.overclock.bonus` = `{0}生产效率，{1}电力消耗，-{2}生命/秒`。

#### 档位为何不由效果单例持有

两个效果实现都是**单例**（倍率表在其中），若把「当前档位」存成实例字段，**多台塔会互相覆盖**
（A 塔设成 3 级、B 塔设成 1 级，则 A 覆盖的工厂也会按 1 级算）。故：

- 档位由**本 Provider** 持有：`EfficiencyControlTowerBuild.levelOf(Boost)`（负数取 `-mode`、正数取 `mode`）；
- System 侧提供 `BuildingBoostSystem.levelOf(target, boostId)`：按目标查出其提供者（多个时取**建筑 id 最小**者，
  与互斥裁决同口径、两端一致）后向其索取档位；
- 引擎钩子 `BlockConsumerHooks` 在被查询时把档位一并传给效果的 `powerFactor/speedFactor`；
- 掉血档位由 `OverclockBoost.apply` 自己调 `levelOf` 取得。

由于同队两塔范围**不得重叠**，一台工厂的档位本就唯一，回查最多命中一个提供者。

> 本方块尚未发布，档位编码由旧的 `0/1/2`（关/节/超）改为 `-3..3` **不涉及存档兼容**。

### 区域查询与绘制

- 走**本队 `buildingTree` 空间树**做矩形相交查询（与 `ItemTransferHub` 同款），非逐格扫描。
- 结果按 `Vars.state.tick` 缓存，同一 tick 内复用同一 `Seq`（零分配）。
- 区域以**方块中心**为基准，半边长 `(range - 1) / 2 = 7` 格，故 15×15 恰好覆盖 15 格、中心落在中间格。
- **arc 的绘制 API 锚点不一致（重要）**：
  - `Fill.rect(x, y, w, h)` 经 `Draw.rect` → `Batch.draw(x - w/2, y - h/2, ...)`，是**中心锚点**；
  - `Drawf.dashRect(color, x, y, w, h)`（首条线段为 `(x,y) → (x+w,y)`）是**左下角锚点**。

  两者传同一组坐标必然错位，故 `drawArea` 分别按各自锚点换算：填充传中心、虚线框传 `中心 - 半边长`。
  若统一按一种锚点写，会出现「填充对了边框错 / 边框对了填充错」的反复现象（本项目已踩过一轮）。
- **外边框为原版风格虚线**：用 `Drawf.dashRect`（与电力桥/钻头/传送带的放置预览同一入口）。
  注意 `Drawf` 的两个硬编码：内部 `Lines.stroke(3f)`（**线宽固定 3，外部设线宽无效**）、
  且 RGB 固定为 `Pal.gray`（传入的 `Color` **只有 alpha 生效**）。
  故虚线框恒为**不透明**灰色（alpha 固定 1，与原版一致），状态区分由**填充色**与**提示文字**承担。
- **中心口径**：建筑位置 = `tile.worldx() + block.offset`（原版 `UnitAssembler.canPlaceOn` 等同款；
  `Block.offset = ((size+1)%2)*8/2f`，3x3 为 0）。放置预览、放置校验统一用 `centerX/centerY`
  求中心，运行期用建筑自身的 `x`/`y`——三者同口径。
  （曾按「锚点 + 1 格 + 半格」算中心，导致放置预览向右上偏 12px。）

### 范围重叠检测

**规则：同队两座塔的范围不得重叠。**

| 时机 | 行为 |
|------|------|
| **放置时** | `canPlaceOn` 检测到与同队已建成的塔范围重叠 → **拒绝放置**（幽灵变红） |
| **运行时** | 若仍被放置成功（旧存档、蓝图/命令等绕过路径），该塔**不运行**：`provides()` 恒 false → 不提供任何强化，且原有贡献被 System 撤销 |
| **视觉** | 范围预览色**随档位**：关闭 = 淡灰（`Color.lightGray`，`#bfbfbf`）、节能 = 绿（`Colors.get("green")`，`#38d667`）、超频 = 红（`Pal.remove`，`#e55454`）。**三色与 bundle 的文本标记严格同源**（`[lightgray]` / `[green]` / `[red]`），故「描述里说的颜色」与「画面上看到的颜色」一致——节能早期误用 `Pal.accent`（实为金黄 `#ffd37f`），与文案「节能绿」矛盾，已改正。放置预览恒为淡灰（放置前档位尚不存在，玩家从菜单拿到的永远是默认「关闭」）；选中已建成的塔才显示真实档位色 |
| **范围重叠** | 与同队塔区域重叠（或本身不可放置）时：填充转红 + 经 `drawPlaceText(..., false)` 显示**红色**「范围重叠」（与钻头挖掘速率预览同一入口）。bundle key `block.silicon-efficiency-control-tower.rangeConflict` |

- 判定口径：两塔中心距在**两轴上都小于 `range` 格**即重叠（恰好相切不算重叠）。
  两者 `range` 相同故对称，实现上取各自 `[中心 - range, 中心 + range]` 矩形相交。
- **只拦同队**：敌队塔与本塔的覆盖对象本就不重叠（各自只强化本队工厂），
  互相拦只会被敌方用来「用一座废塔废掉你的塔」，故不拦。
- 运行时结果缓存于 `conflicted` 字段：每 tick 在 `update()` **开头**重算，
  必须早于 `updateBoosts()`——`provides()` 是在登记过程中被同步读取的。
- 客户端与服务器都会执行该判定（纯几何 + 同队塔列表，两端一致），不会出现「一边能放一边不能」。

### 叠加与互斥

- **同模式多塔**：多台塔覆盖同一工厂时，System 按「同一目标同一效果**至多一份**」处理，
  被 3 台塔覆盖也只生效一份（不叠乘）。
- **异模式重叠**：一台节能塔 + 一台超频塔覆盖同一工厂时，两者互相冲突（`conflictsWith`），
  由 System 的互斥裁决按**效果 id 字典序**取一个——`energy_saving` < `overclock`，故**恒为节能胜出、超频不生效**。
  字典序是纯函数：两端必然算出同一结果，且**与放置顺序/玩家操作时序无关**（不会因「谁先放」而改变）。
  （正常放置已被范围重叠检测拦住，此规则主要服务于旧存档/强制放置的遗留重叠。）

### 多人安全

- 只作用于**同队**工厂，队伍比较用 `==`（`Team` 枚举单例，跨端一致，天然排除敌队/derelict）。
- 本塔**不消耗任何资源**（不耗电、不耗液体），故不存在「扣多少网络化状态」的分支，
  无需确定性认领规则（对比 `LubricantInjector` 的 `owns()`：那边要扣油，必须裁决唯一认领者）。
- 生效集合完全由「本队空间树 × 固定区域 × 模式」推导，两端输入一致。
- 模式本身经标准 config 链路同步，两端一致。
- 范围重叠判定（放置与运行时）只用几何量与同队塔列表，两端一致；互斥裁决只用效果 id 字典序（纯函数），两端一致。

### 关键实现约束：`boosts()` 恒定返回完整列表

**`boosts()` 永远返回 `[EnergySavingBoost, OverclockBoost]` 完整列表，模式与冲突判断只放在 `provides()`。**

原因：System 撤销某个 Provider 贡献的唯一路径是「该 Provider 仍被遍历到、但它对某个 boost id 的
意愿为 false」（见 `BuildingBoostSystem.collectContributions`）。若某模式下让 `boosts()` 只返回
「该模式那一个效果」（或返回空列表），或让 `targets()` 返回空列表，System 会因 `targets().isEmpty()`
提前 return，**旧模式的贡献永远不会被撤销**——切到超频后节能仍不消失，两者相乘成 0.8×1.75 / 0.9×1.5 的杂交值。

故本方块：

- `boosts()` 恒定返回完整效果列表（与模式无关）；
- `provides(boost)` 里判 `!conflicted && mode == modeOf(boost.id())`——**逐效果**表达开关，
  返回 false 即触发 System 的正规撤销路径；
- `canTarget()` 只判 `enabled`（本机是否开机）；
- `targets()` 也与模式无关地照常返回区域内耗电建筑。

> System 为此在 `Provider` 上新增了 `provides(Boost)`（默认恒为是）——因为「同一 Provider 按模式
> 提供不同效果」无法用 `canTarget(Building)` 表达（它拿不到当前是哪个效果）。

代价是「关闭」/冲突模式下仍会做一次区域查询（为了能正确撤销），但结果按 tick 缓存，开销可忽略。

### 档位贴图

塔的本体贴图**随档位切换**，共 7 张（96×96，即 3x3），置于 `assets/sprites/blocks/efficiency-control-tower/`：

| 档位 | 文件 | 数组下标 |
|------|------|---------|
| 关闭（0，默认） | `efficiency-control-tower.png`（基准图） | `0` |
| 1/2/3 级节能 | `-L1` / `-L2` / `-L3` | `1` / `2` / `3` |
| 1/2/3 级超频 | `-R1` / `-R2` / `-R3` | `4` / `5` / `6` |

- 载入在 `load()`：`modeRegions[0] = region`（即 `super.load()` 取的基准图），其余按
  `Core.atlas.find(name + "-L1")` 这类约定取。目录不参与 atlas 键名，
  实际键为 `silicon-efficiency-control-tower-L1`（模组前缀 `silicon-` 由 `ContentLoader.transformName` 加）。
- 取名在 `regionOf(int mode)`：负数→L 系列（按绝对值）、正数→R 系列（`level + maxLevel`）、
  0 或越界→下标 0。
- **`mode` 与下标的编码不同**：`mode` 是「负=节能 / 0=关闭 / 正=超频」，
  数组下标是单调整数，映射集中在 `regionOf` 一处。
- 贴图缺失时不抛异常也不糊成缺图：`loadOrFallback` 查 `found()`，缺失则回退基准图并
  `SiliconLog.warn`（与 `DualPurposeStorager` 同款约定）。
  （注意 `TextureAtlas.find(String)` 单参版对缺失**抛异常**，双参版才回退——基准图缺失属前者，
  故基准图必须存在。）

#### 绘制方式：叠画而非替换

`draw()` 先 `super.draw()` 画基准图，再在 **`mode != 0` 时叠画**对应状态图：

```java
@Override
public void draw(){
    super.draw();
    if(mode != modeOff){
        Draw.rect(regionOf(mode), x, y, drawrot());
    }
}
```

- 关闭态**不叠画**，故基准图即 X。
- 与仓库既有多状态贴图约定一致（`Switch.SwitchBuild#draw` 同样是先 `super.draw()` 再叠画）。
- **叠画成立的前提是状态图不透明**：七张贴图经隔点采样（96×96 取 2304 点）全部 `alpha=255`，
  故上层完整盖住下层，基准图透不出来。已对六张状态图做合成比对，
  「基准图 + 状态图」与「仅状态图」**逐像素差异均为 0**。
  （若状态图带透明区，叠画才会让基准图透出，那时必须改为替换。）
- **两者都绝不能改写 `block.region`**：它是全方块共享的基准图，在 `draw` 里改会让场上所有塔一起变图。
  逐建筑取图、逐建筑传入 `Draw.rect` 才是正确做法。
- 不需要 `recache()`：`Block.drawCached` 默认为 **false**、`drawDynamic` 默认为 true，
  本塔每帧动态绘制，切档位立即生效。（`drawCached` 默认值由字节码确认，勿凭印象。）

### 视觉

- 放置预览与选中时画出 15×15 影响区域（半透明填充 + 描边），与实际判定同口径。
- 区域内被强化的工厂左下角会出现强化信息按钮（`BoostOverlay` 绘制 + 交互），
  点击查看该建筑上生效的强化详情。**按钮的绘制与消息输出不属于本方块**，见 `docs/utils/BuildingBoostSystem.md`。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `range` | float | `15f` | 影响区域边长（格），以本方块为中心 |

## 已知取舍

- **不耗电、不耗资源**：需求未指定成本，故未加。一个 3x3 塔免费强化 15×15 区域，强度偏高；
  若要平衡，可加 `consumePower(...)`（此时本塔进入电网，`Building.cheating()` 路径下 consumer 不参与结算，
  需另行确认断电时的降级行为）。
  配方（Copper 200 / Lead 150 / Silicon 80）亦为自定初值，待定。
- **「关闭」/冲突模式下仍查询区域**：见上「关键实现约束」，是正确性所迫，非疏忽。
- **只拦同队范围重叠**：敌队塔可与本塔范围重叠（见「范围重叠检测」）。
- **异模式重叠时按效果 id 字典序裁决**（恒为节能胜出），而非按玩家意图或塔的类型优先级。
- **不覆盖非 `GenericCrafter` 的耗电建筑**（钻机、抽油机、`Separator`、`Incinerator` 等）：
  「工厂」判定在效果侧，放宽只需改 `EnergySavingBoost.canTarget` / `OverclockBoost.canTarget`。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始：3x3 方块，15×15 区域内耗电工厂附上 `EnergySavingBoost`；配置滑块「关闭/节能」走标准 config 链路 + 存盘持久化；区域查询走本队 buildingTree 并按 tick 缓存；`boosts()` 恒定返回完整列表（条件只判 `provides()`/`canTarget()`），避免旧模式贡献无法撤销；不耗资源 |
| a0.x | 新增「超频」档位（`OverclockBoost`：生产 +50%、耗电 +75%、每 3 秒扣 10 生命）；System 新增 `Provider.provides(Boost)` 逐效果开关（`canTarget` 拿不到「当前是哪个效果」，无法表达按模式提供不同效果） |
| a0.x | **修复范围显示错位**：arc 的 `Fill.rect` 是中心锚点而 `Lines.rect` 是左下角锚点，原先两者传同一组坐标 → 填充与边框必然错位（表现为「填充对了边框错 / 边框对了填充错」交替出现）。现 `drawArea` 分别按各自锚点换算 |
| a0.x | **范围重叠检测**：同队两塔范围重叠时①放置被拒（`canPlaceOn`）②仍被放置成功则该塔不运行（`provides()` 恒 false，原贡献被撤销）③预览/选中画红色提示。互斥裁决沿用「优先级 + 效果 id 字典序」（恒为节能胜出），未引入放置顺序 |
| a0.x | **修复放置预览中心偏移**：改用引擎口径 `tile.worldx() + block.offset` 求中心（原按「锚点+1格+半格」偏 12px）。外边框改为原版风格虚线（`Drawf.dashRect`，线宽/灰色由 Drawf 内部固定）。放置预览重叠时经 `drawPlaceText(..., false)` 显示红色「范围重叠」文字（bundle `...rangeConflict`），与钻头挖掘速率预览同一入口 |
| a0.x | **档位化 + 预览配色**：单一滑块扩为 `[-3,+3]` 共 7 档，自左至右「3级节能→2级→1级→**关闭**→1级超频→2级→3级」（关闭居中、默认），编码改为「负数=节能 / 0=关闭 / 正数=超频」。节能、超频各 3 档数值（见「档位（配置 UI）」表）。范围预览色随档位：关闭淡灰 / 节能绿 / 超频红。档位由 Provider 持有（`levelOf`）经 System `levelOf(target, id)` 回查——因效果是单例，存实例字段会被多塔互相覆盖 |
| a0.x | **配置面板重排 + 固定宽度**：档位文本移到滑块**下方**（居中、按档位着色）。面板宽度**固定 320px** 且所有单元格锁死——否则滑块长度随内容变化会导致无法精确点选；文本 `setWrap(true)` 超出即换行，不撑宽面板。档位名不再自建 key，改用效果 `name(level)`（单一数据源，与消息面板一致） |
| a0.x | **加成文本修正 + 两行合一**：① 根因修复——`BuildingBoostSystem.percentText` 原按「正数 = 节省量」渲染成负号（`v > 0 ? "-"`），导致节能恰好正确而**超频把 `+50%` 印成 `-50%`**；改为**如实加符号**（`v > 0 ? "+"`），两个效果统一传带符号差值 `scale - 1f`（节能为负、超频为正）。② 文案去掉「百分比与标签之间的空格」，与方块描述口径一致。③ 面板由「档位名 + 加成摘要」两行合为**单行** `modeText(mode)` = `{档位名}，{加成摘要}`（如 `1级节能，-20%电力消耗，-15%生产效率`），与强化详情面板 `description()` 的行格式完全一致；`bonusText`/`modeName` 两个方法删除。④ `line` 的 `{0}` 由「档位数字」改为**档位名**（自带「节能/超频」），并移除随之失效的 `block.silicon-efficiency-control-tower.mode.off` |
| a0.x | **强化消息只显示生效档**：`Boost` 新增带目标的 `name(target)` / `description(target)`（默认委托无参版），多档位效果覆写为「名称+档位」与「仅当前档加成」。`BoostOverlay` 改用这两个重载，故消息为 `2级超频：+100% 生产效率，+125% 电力消耗，-10 生命/秒`，不再罗列三档 |
| a0.x | 虚线边框改为**不透明**（alpha 固定 1）：`Drawf.dashRect` 只取传入色的 alpha，故显式置 `border.a = 1f`；`drawArea` 去掉 `borderAlpha` 参数。填充仍按模式/冲突着色并保持半透明 |
| a0.x | **节能预览色由金黄改为绿 + 描述文本语义配色**：`modeColor` 的节能分支原用 `Pal.accent`（实测 `#ffd37f`，<b>金黄而非绿</b>），与代码注释/文档/描述文案里的「节能绿」长期矛盾；改为 `Colors.get("green")`（`#38d667`，arc 标准绿），与 bundle 的 `[green]` **取到同一个 `Color` 实例**（故「文本色 == 预览色」由同源保证，而非两处各写一遍）。三色现与文本标记一一对应：关闭 `Color.lightGray`=`[lightgray]`、节能 `Colors.get("green")`=`[green]`、超频 `Pal.remove`=`[red]`。同时重写中英描述：去掉满屏 `[stat]`，改为**语义配色**（节能段绿、超频段红、关闭淡灰、警示用 `[scarlet]`），并把档位表由「逐项标注」压成「1级：耗电 -20%，生产 -15%」的紧凑格式 |
| a0.x | **档位贴图**：本体贴图随档位切换，7 张 96×96（关闭=基准图 `efficiency-control-tower.png`、节能 `-L1~-L3`、超频 `-R1~-R3`），载入于 `load()` 的 `modeRegions[]`，取名于 `regionOf(mode)`。绘制采**叠画**：`super.draw()` 画基准图后在 `mode != 0` 时叠画状态图（与 `Switch` 既有约定一致）——成立前提是状态图不透明（七张全 `alpha=255`，合成比对差异 0 像素）。`region` 为共享字段故不可改写，逐建筑取图传入 `Draw.rect`。贴图缺失由 `loadOrFallback` 回退基准图 + `SiliconLog.warn`。`drawCached` 默认 false，故无需 `recache()`，切档立即生效 |
