# LubricantInjector

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `LubricantInjector` |
| 父类 | `Block` |
| 分类 | Category.turret |
| 尺寸 | 2x2 |
| 血量 | 220 |
| 架构 | `BuildingBoostSystem.Provider`（强化内容封装为 Boost，由 System 驱动） |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 120 |
| Lead | 80 |
| Silicon | 40 |

## Block 属性

- `update`: true
- `solid`: true
- `hasLiquids`: true
- `outputsLiquid`: false
- `liquidCapacity`: 300
- `alwaysUnlocked`: true
- `drawCached`: **false**、`drawDynamic`: **true**（自绘三层贴图 + 动态闪光，必须每帧重绘）

## 贴图与绘制

### 四张分层贴图

共 4 张 64×64（即 2x2），置于 `assets/sprites/blocks/lubricant-injector/`：

| 文件 | 字段 | 作用 |
|------|------|------|
| `lubricant-injector.png` | `region` | **基准图**：`bottom + top + light` 的合成产物，建造菜单图标；<b>运行期不单独绘制</b> |
| `-bottom.png` | `bottomRegion` | 底座（纯色打底，全不透明） |
| `-top.png` | `topRegion` | 顶盖主体（带镂空） |
| `-light.png` | `lightRegion` | 闪光层，绘制在顶盖**之上** |

- 命名沿用原版液体储罐 `LiquidBlock` 的约定（`{name}-bottom` / `-top`），目录不参与 atlas 键名。
- 载入在 `load()`：`loadOrFallback(suffix)` 查 `found()`，缺失则回退基准图并 `SiliconLog.warn`
  （`TextureAtlas.find(String)` 单参版对缺失返回 error 占位图，直接用会糊成缺图且无提示）。
- **基准图必须存在**：`Block.load()` 里 `region = Core.atlas.find(name)` 用的是**单参版**，
  对缺失**抛异常**（与分层贴图的双参回退行为不同）。

### 绘制顺序（四层）

```
① bottomRegion  →  ② 原版液体动画  →  ③ topRegion  →  ④ lightRegion（加色混合，仅工作时）
```

| 层 | 内容 | 条件 |
|----|------|------|
| ① | `bottomRegion` 底座贴图 | 恒绘 |
| ② | **原版液体动画**：`LiquidBlock.drawTiledFrames(size, x, y, liquidPadding, current, amount/capacity)` | 有油时 |
| ③ | `topRegion` 顶盖 | 恒绘（**绘制前先 `Draw.color(Color.white)`**） |
| ④ | `lightRegion` 闪光（`Drawf.additive`） | 工作时 |

- 第 ② 层与原版液体储罐、两用存储器**同一入口**：用引擎 `fluidFrames` 动画帧画出条纹流动的液面，
  按液体自身颜色着色、以填充比例作 alpha（油多则浓、少则淡）。
- **画顶盖前必须重置颜色**：`drawTiledFrames` 经 `Drawf.liquid` 着色后会在 `Draw` 上**残留液体颜色**，
  不重置会把顶盖也染成油色（两用存储器曾专门修过这处，其代码在画顶盖前有 `Draw.color(Color.white)`）。
- `lubricant-injector.png` 不参与绘制：它是 `bottom + top + light` 的合成产物，故四层画完即得基准图外观
  （空罐时无第 ② 层，正好等于基准图）。

### 闪光效果（参照原版爆破钻头）

写法参照原版 `BurstDrill`（爆破钻头）的 glow 分支：用 `Drawf.additive` 而非普通绘制——
加色混合才能得到「发光」观感而非「贴一层颜色」。该辅助方法内部已自行处理
`Draw.z`、颜色与 `Blending.additive` 的设置与**复位**，故调用方不需要（也不应该）再手动调 `Draw.blend`。

**颜色**：取模组润滑油的配色（`Liquids.lubricant` 的 `8a5a2b` 深琥珀），alpha 压低到 `0.35`——
`Color.valueOf` 产出不透明色，加色混合下满 alpha 会过曝成白团、盖掉顶盖细节。
用 `a(0.35f)` 生成新对象而非引用 `Liquids.lubricant.color`，避免「改本方块闪光色」连带改掉全局液体颜色。

**强度**：`Mathf.absin(Time.time, 6f, 1f) * glow`，两个因子相乘——

| 因子 | 作用 |
|------|------|
| `glow`（0~1） | 整体开关的平滑缓动（`Mathf.approachDelta`，`glowSpeed = 0.08f`），避免硬切 |
| `absin(...)` | [0,1] 的正弦脉动，给出持续闪烁的「呼吸」感 |

相乘的好处：未工作时 `glow → 0` 会把脉动一并压到 0，**无需额外分支**判断是否闪烁。

### 闪光与「是否真在工作」严格同步

`glow` 的目标值 `active` 由 `update()` 每帧写入，口径为 **存油 且 至少为一座炮塔提供了强化**：

```java
active = hasOil && provided > 0;   // provided 与扣油数出自同一遍循环
```

- `provided` 与**扣油耗**用的是同一个计数（同一次 `targets()` 遍历的两个产物），
  故闪光与「实际是否在起作用、是否在扣油」严格一致，不会出现「没效果却在闪」或「在扣油却不闪」。
- 判定条件与 `canTarget`/攻速 Boost 激活口径一致（`isShooting()` + `owns()` + `isProviderOf()`）。
- `draw()` 只读 `glow`、不做任何扫描，逐帧成本不压在渲染路径上。

### 为何 `drawCached = false`

本方块自绘且带动态闪光。若被烘焙进区块缓存（`drawCached`），`draw()` 只在缓存重建时调用一次，
工作时不会闪烁；而对齐原版液体方块默认值（`drawCached=false` + `drawDynamic=true`）可保证
只每帧动态画一次，也不会「进缓存 + 每帧再画」导致半透明叠加过饱和
（两用存储器曾踩过这个坑，见 `docs/blocks/` 中该方块的说明）。

## 机制说明

润滑油注入器是建筑强化系统（`BuildingBoostSystem`，见 `docs/utils/BuildingBoostSystem.md`）的第一个
Provider 落地实现。本方块**不再私写炮塔专用逻辑**，只负责四件事：

1. **前置目标过滤**：`targets()` 只把紧贴的炮塔交给 System——非炮塔/非可用对象**连 System 循环都不进入**，
   不尝试附 boost，避免无谓登记。
2. **锁油量 + 认领检查**：`canTarget()` 返回「存油 > 0.001 且 本机独占认领该目标（`owns()`）」——有润滑油才提供强化；
   油尽时 System 自动撤销已生效效果；认领被更小坐标的同队注入器抢走时本机**主动取消提供**。
3. **扣油**：按「本机独占认领且攻击中」的己方炮塔数量消耗（默认 5/s 每只，`consumePerTurret`）——唯生效者才扣油，
   认领判定与 `canTarget` 同一函数，两端口径一致。
4. **声明 Boost**：`boosts()` 返回一个效果单元 `LubricantBoost`。

### 提供的效果（Boost）

本方块只提供**一个效果单元** `LubricantBoost`（id=`lubricant`），把润滑油的全部强化打包在一起
（攻速 +20%、转角 ×2，内部为两个独立判定条件的子效果，注入式语义）。

> 效果单元的完整规格（触发条件、注入公式、目标角三分支、展示信息、数值参数、多人注意事项）
> 见 **`docs/boosts/LubricantBoost.md`**。

本方块与该效果的分工：

| 归属 | 内容 |
|------|------|
| 本方块 | 何时提供（存油 + 独占认领）、扣多少油、提供哪些目标 |
| `LubricantBoost` | 效果本身怎么算、怎么注入、展示什么名称/简述/图标 |
| `BuildingBoostSystem` | 登记、互斥、不叠加、apply/remove 驱动、撤销生命周期 |

### 强化概要

| 子效果 | 内容 | 触发条件 |
|--------|------|----------|
| 攻速 | +20% | 炮塔 `isShooting()`（仅攻击中耗油） |
| 转角 | 速率 ×2 | `hasAmmo() && shouldTurn()`（存油即生效，不额外耗油） |

### 不叠加与持续规则

- **不叠加**：效果持续由注入器控制（只要这台注入器仍独占认领该炮塔并向它提供就保持生效）；同一炮塔的
  `lubricant` 效果无论多少台注入器紧贴都只生效**一份**，`apply` 每帧至多一次、不会成倍放大（见
  `docs/utils/BuildingBoostSystem.md`「不叠加规则」）。
- **确定性认领（唯生效者耗油，多人安全）**：紧贴同一炮塔的多台注入器里，按 **(tileX, tileY) 字典序最小者**
  独占该炮塔的强化与油耗，其余主动让出（既不提供也不耗油）。候选须「同队 + 存油」；本机不满足直接出局。
  - 认领者由 tile 坐标（地图派生，客户端/服务器完全一致）决定，**不依赖 update 顺序**——否则两端可能
    选出不同的认领者，导致扣的是不同机器的油，液体量永久分歧（desync）。参见 BuildingBoostSystem 文档
    「多人兼容约定」。
  - 认领随提供失效自然交接：独占者油耗尽/失格后不再参与竞争，认领自动让给下一台（下一帧生效）。
- 两台注入器夹同一炮塔时 → 效果一份、油耗一份（无双击油耗）。

### 驱动流程

```
LubricantInjectorBuild.update()
 ├─ updateBoosts()          → 交给 System：登记本机的提供意愿（targets/canTarget/boosts 已由本类定义），
 │                              逐 tick 统一冲洗、互斥裁决与 apply/remove 均由 System 负责
 └─ 按「本机独占认领(owns)且攻击中(isShooting)」的炮塔数扣油（Time.delta/60 折算真实秒）
LubricantInjectorBuild.onRemoved()
 └─ removeProviderBoosts()  →  System 即时撤销本机提供的一切强化
```

System 内部的登记顺序、目标过滤四层、互斥裁决与清扫流程见 `docs/utils/BuildingBoostSystem.md`。

### 生涯/状态管理

- 目标炮塔失格（停止攻击/弹药耗尽/油尽/被拆）→ System 自动调 `remove()`，无残留。
- 注入器被拆除/摧毁 → `onRemoved()` 里调 `removeProviderBoosts()`，System 即时撤销它的一切贡献。
- 炮塔不满足 `isBoostable`（默认名单仅 `TurretBuild`）→ 直接跳过，不会误用炮塔专用 API。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `consumePerTurret` | float | 5f | 每个正在攻击的受惠炮塔每秒润滑油消耗 |

效果数值（`firePotency` / `rotationPotency`）属效果单元，见 `docs/boosts/LubricantBoost.md`。

## 液体处理

- **仅接受同队供给、且本机留有余量**：`acceptLiquid = source.team == team && liquid == lubricant && 存量 < 容量`
- 多人安全：队伍比较用 `==`（Team 枚举单例，见 BuildingBoostSystem 文档）

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始：攻速 +20%（强化液同池加法）、转角 ×2（自动索敌/玩家控制同等生效）、5/s 消耗、同队门禁 |
| a0.x | 重构为 BuildingBoostSystem Provider：强化内容抽为 Boost（`silicon.util.boosts`），资格/队伍/互斥/撤销生命周期交由 System 统一管理（效果规格见 `docs/boosts/LubricantBoost.md`） |
| a0.x | 合并为单一 `LubricantBoost`（id=`lubricant`）：攻速/转角作为两个子效果打包在一个效果单元内 |
| a0.x | 落实「不叠加 + 持续由注入器控制」：System 每帧统一冲洗、同一效果至多一份；新增 `onRemoved()` → `removeProviderBoosts()` 拆除即撤销 |
| a0.x | 认领制（唯生效者耗油）：目标已被别台注入器认领时本机取消提供；扣油按「认领中且攻击中」计数，双注入器夹同一炮塔不再双倍油耗 |
| a0.x | 多人安全加固：认领由「先到先得（update 顺序）」改为**确定性 tile 坐标裁决** `owns()`——紧贴同一炮塔的同队有油注入器中 (tileX, tileY) 最小者独占，避免两端 update 顺序差异导致扣不同机器的油、液体量 desync |
| a0.x | 性能：boost 列表提为方块级共享只读 Seq（不再每帧 `Seq.with`）；紧贴炮塔列表按 tick 重建并复用同一 Seq，`targets()` 与扣油循环共用（不再每帧新建 + 重扫 proximity） |
| a0.x | 目标过滤双层化：注入器 `targets()` 前置过滤只交炮塔（非可用对象不进 System 循环）；`Boost.canTarget` 目标过滤 + System 读取执行（`LubricantBoost.canTarget` = 仅炮塔） |
| a0.x | 提供展示信息（`name()` / `description()` / `visual()` 图标），**强化按钮的绘制、点击与消息输出由 System + `BoostOverlay` 负责**，不计入本方块；效果规格迁至 `docs/boosts/LubricantBoost.md` |
| a0.x | **分层贴图 + 闪光效果**：改为自绘**四层** `bottom → 原版液体动画 → top → light`（命名与液体层入口沿用原版 `LiquidBlock` 约定），基准图 `lubricant-injector.png` 不再单独绘制（即 `bottom+top+light` 的合成结果，经逐像素验证差异 0 像素）。第②层中 `drawTiledFrames` 会残留液体色，故画顶盖前 `Draw.color(Color.white)` 重置——否则顶盖被染成油色。闪光参照原版爆破钻头 `BurstDrill` 的 glow 分支，用 `Drawf.additive` 加色混合，颜色取润滑油 `8a5a2b`（alpha 压至 0.35 防过曝），强度 = `Mathf.absin(Time.time, 6f, 1f) * glow`，`glow` 由 `Mathf.approachDelta` 平滑缓动。闪光目标状态 `active` 与扣油同一遍循环产出，故与实际工作状态严格同步。新增 `drawCached=false` / `drawDynamic=true` 确保每帧重绘 |