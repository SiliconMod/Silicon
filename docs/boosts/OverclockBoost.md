# OverclockBoost

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `OverclockBoost` |
| 文件 | `src/silicon/util/boosts/OverclockBoost.java` |
| 包 | `silicon.util.boosts` |
| 类型 | `BuildingBoostSystem.Boost` + `BlockConsumerHooks.FactorSource`（**钩子式 + 注入式**混合） |
| id | `overclock` |
| 单例 | `OverclockBoost.instance`（静态块自动 `register` 进 System 与钩子注册表） |
| 提供方 | 效率控制塔（`docs/blocks/EfficiencyControlTower.md`），需切到「超频」模式 |

超频：作用对象与 [`EnergySavingBoost`](EnergySavingBoost.md) **完全一致**（耗电工厂），
但方向相反——纯粹的「以机器寿命换产能」。共 **3 个档位**（由效率控制塔的档位决定）。

| 档位 | 生产效率 | 耗电 | 掉血 |
|------|---------|------|------|
| 1 级 | +50% | +30% | −4 生命/秒 |
| 2 级 | +100% | +125% | −10 生命/秒 |
| 3 级 | +300% | +500% | −45 生命/秒 |

> 「生产效率」与「耗电」互相独立（前者走建筑 `efficiency`、后者走电网请求电量），
> 故 3 级是「生产 4.0×、耗电 6.0×」两个不同比例。

## 目标过滤

| 方法 | 行为 |
|------|------|
| `canTarget(Building)` | `block.consPower != null`（耗电）且 `block instanceof GenericCrafter`（工厂）——与节能逐字相同 |
| `shouldApply(Building)` | `target.enabled && efficiency > 0 && warmup >= warmupThreshold`（**已启用 且 确实在运转**，带去抖；见下） |

### 生效条件：必须「确实在运转」（带去抖）

超频是「以寿命换产能」——**没产能就不该付代价**。若只判 `enabled`，工厂缺料/产物堵满时会
照常掉血（且 3 档仍按 6 倍功率占用电网），玩家看到的是「什么都不产、却一直损血烧电」。

`efficiency` 是引擎给出的权威「本帧是否在运转」信号，覆盖全部停机情形
（已由 `Building.updateConsumption()` 字节码确认）：

| 停机原因 | `efficiency` 归零的路径 | `shouldConsumePower` | 电网耗电 |
|---------|----------------------|---------------------|---------|
| 缺料 | 物品 consumer 的 `efficiency()` 返回 0 → 取最小值后归零 | **false** | ❌ 不计 |
| 产物堵满 | `shouldConsume() == false` → 末尾显式置 0 | true | ✅ 仍计（超频撤销后降回标称） |
| 断电 | `potentialEfficiency = 0` → 归零 | true | ✅ 仍计入需求 |
| 被玩家关闭 | 整个分支置 0（`enabled` 另行显式判） | **false** | ❌ 不计 |

> 上表由 `PowerGraph.getPowerNeeded()` 与 `Building.updateConsumption()` 的字节码确认：
> 需求汇总的唯一门是 `shouldConsumePower`，而它仅在「非电力 consumer 的 `efficiency() <= 1e-7`」时置 false
> （该判定显式跳过 `consPower` 自身，否则断电建筑会退出需求、导致供电震荡）。

#### 去抖

`efficiency` 会在缺料/来料交替时于 0/1 之间抖动，直接拿它当开关会让徽记与倍率每帧闪烁。
故要求**引擎维护的平滑量 `warmup` 越过 `warmupThreshold`（默认 0.5）**：

- **启动侧**：`warmup` 需爬升越过阈值才生效 → 滤掉瞬时抖动；
- **停机侧**：`warmup` 在 `efficiency == 0` 时平滑衰减（`approachDelta(warmup, 0, warmupSpeed)`）
  → 短暂缺料不会立刻撤销。

`warmupSpeed = 0.019f`（每 tick 逼近 1.9%），故 0.5 阈值约需 `ln(0.5)/ln(1-0.019) ≈ 36` tick（约 0.6 秒），
**两个方向都是这个量级**。

**为何用引擎自带量而非自建计数器**：`shouldApply` 按契约必须是**无副作用**的纯查询，
且同一目标可能被多台 Provider 各问一次（多台塔覆盖同一工厂时一 tick 问多次），自增计数器会重复计数。
`warmup` 由引擎每 tick 推进一次、两端一致，天然满足这两条约束。

#### 不会与电网形成震荡环

超频是**增加**耗电的效果，撤销它只会让电网更宽裕，不存在「撤销 → 更缺电 → 更多撤销」的正反馈。
（省电类效果才有那个方向的风险。这也是本判据能安全使用含断电项的 `efficiency`、
而不像 `EnergySavingBoost` 那样回避它的原因。）

#### 连带影响

- **徽记随之下线**：`BoostOverlay` 的绘制判据是 `BuildingBoostSystem.hasActiveBoosts(target)`，
  而该表由 `shouldApply` 驱动（`collectContributions` 内 `want = ... && boost.shouldApply(target)`）。
  故停机时徽记消失、恢复时自动出现，与「是否真的在超频」严格一致。
- **掉血计时随撤销清零**：`remove()` 里 `damageTimers.remove(target)`，故恢复供料时从零重新计时，
  不会把停机期间的时间也算进去（否则会「一恢复就立刻扣一次」）。

## 子效果

### 生产效率（按档位）

`speedScales = {1.5f, 2.0f, 4.0f}`（索引 0 = 1 级）。

**不能靠 `efficiency` 提速**（引擎硬限制）：

```java
// Building.updateConsumption()
float min = 1f;                                              // ← 初值就是 1
for (非可选 consumer) min = Math.min(min, c.efficiency(build));
efficiency = min;                                            // ← 故 efficiency 恒 ≤ 1
```

给 `efficiency` 返回 1.5 / 2.0 / 4.0 会被其他 consumer（物品/液体充足时返回 1.0）取小顶掉，
**完全不起作用**（这正是「超频不加速、节能却正常」的原因——节能的 0.85 是**小于** 1 才生效）。

故改为**注入进度**（`BlockConsumerHooks.boostProgress`）：

```java
crafter.progress += crafter.getProgressIncrease(craftTime) * (factor - 1f);
```

- 引擎每 tick 做 `progress += getProgressIncrease(craftTime)`，这里追加「超出 1 倍的那一份」，
  两者相加即得 `factor` 倍推进速度。
- 取的是**同一个** `getProgressIncrease(craftTime)` 调用，故与引擎实际推进量完全一致
  （含 `GenericCrafterBuild` 对液体产出空间的额外处理）。
- `progress` 是**归一化**的（满 1 产出一次，`craft()` 内 `progress %= 1` 保留余量），
  故多注入的量会正常参与取模结转，不会丢失或溢出。
- 注入与引擎自增的先后只影响本 tick 内 `craft()` 触发的那一次，不影响每 tick 总推进量。
- `progress` 是同步字段（服务器权威），两端注入相同量，瞬时差异会被同步抹平。

### 耗电（按档位）

`powerScales = {1.3f, 2.25f, 6.0f}`（索引 0 = 1 级）。经 `BlockConsumerHooks.ScaledConsumePower` 把
`requestedPower(build)` 乘档位倍率，只提高该建筑自己的请求电量（`PowerGraph.getPowerNeeded` 汇总时生效）。

### 持续掉血（按档位，单位：生命/秒）

`damageRates = {4f, 10f, 45f}`，`damageInterval = 1f`（扣血周期，秒）。

- **注入式**：`apply()` 每 tick 调用一次，按 tick 累计真实时间
  （`Time.delta / 60f` 折算为秒，与注入器耗油口径一致）；每跨过一个周期扣一次
  **「速率 × 周期」**点生命，并保留余量以免长期漂移。
  故周期为 1s 时，3 档即每 1 秒扣 4 / 10 / 45 点。
- **档位由效果自己取**：`apply()` 内调 `BuildingBoostSystem.levelOf(target, id())`——
  档位不由本单例持有（多台塔会互相覆盖），需按目标回查其提供者。
- **离散扣除而非逐 tick 连续扣**：为保留原版受击反馈。`Building.damage` 会刷新 `hitTime`，
  若每 tick 都扣，建筑会常驻受击色（看起来像一直在被攻击，而不是「在超频运转」）。
- 走引擎标准伤害链路 `target.damage(点血量)`，因此：
  - 自动计入 `Rules.blockHealth(team)`（规则可调的伤害系数）；
  - 血量归零时由引擎触发 `Call.buildDestroyed` → **正常爆炸拆除**（两端都正确：
    `Call.buildDestroyed` 在客户端走本地销毁、服务端额外广播）；
  - 有命中反馈（`hitTime`）。
- **逐建筑计时**用 `damageTimers`（`ObjectMap<Building, Float>`）记录——引擎没有可借用的每建筑字段。
  清理依赖 System 的撤销保证：`reconcile`（不再生效时）与 `sweepInvalid`（清扫失效目标）
  都必定回调一次 `remove(Building)`，故不会泄漏表项。`remove()` 里删除该条目。

## 生命周期语义

- **倍率部分（耗电 / 速度）**：钩子式——`apply()` 只负责惰性安装钩子（与节能共用同一对钩子），
  倍率由钩子每次被引擎查询时回调 `BlockConsumerHooks.powerFactor/speedFactor` 实时读取。
  撤销时倍率自动回到 1.0，`remove()` 无需还原倍率。
- **掉血部分**：注入式——`remove()` 只清计时器，不需还原（没有累积型副作用）。
- 安装钩子与节能**共用同一次安装**：钩子每次查询时取「所有生效来源倍率之积」，
  与已安装几个效果无关（见 `BlockConsumerHooks` 类注释「多效果叠加」）。

## 互斥：与节能冲突时恒为节能胜出

`conflictsWith("energy_saving")`（节能侧对称声明）——两者是同一概念的相反档位，不应同时作用于一台工厂。

裁决规则（`BuildingBoostSystem.resolveMutex`）：优先级 → **效果 id 字典序**。
`energy_saving` < `overclock`，故冲突时**恒为节能胜出、本效果被挂起不生效**。

> 排序键刻意只用纯函数（优先级 + 效果 id），**不引入建筑 id / 放置顺序**：
> 字典序两端必然算出同一结果，且结果与玩家操作时序无关——不会因「谁先放」而改变。
>
> 单台效率控制塔任一时刻只提供一个效果（由 `Provider.provides(Boost)` 保证），
> 且**同队两塔范围重叠已被放置校验拦截**，故本规则实际只在旧存档/强制放置的遗留重叠场景生效。

## 展示信息

| 方法 | 内容 | 来源 |
|------|------|------|
| `name()` | `超频` / `Overclock` | bundle `boost.overclock.name` |
| `name(int level)` | `1级超频` / `Overclock Lv.1` | bundle `boost.overclock.levelName` |
| `summary(int level)` | `+50%生产效率，+30%电力消耗，-4生命/秒` | bundle `boost.overclock.bonus` = `{0}生产效率，{1}电力消耗，-{2}生命/秒`，三个占位由**倍率表 + 掉血表**现算（`{2}` 取 `damageRates`） |
| `description()` | 逐档一行：`1级超频，+50%生产效率，+30%电力消耗，-4生命/秒` | bundle `boost.overclock.line` = `{0}，{1}`，`{0}` 传 `name(level)`（**档位名**，不是数字） |
| `name(Building)` / `description(Building)` | 只给**当前生效档**（按 `BuildingBoostSystem.levelOf` 回查），不列全部三档 | — |
| `visual(Building)` | **统一图标** `BuildingBoostSystem.badgeIcon()` | 强化徽记（底色 `Pal.lightFlame` 火焰橙） |

> **百分比符号口径**：`BuildingBoostSystem.percentText(ratio)` **如实加符号**（正 → `+`，负 → `-`），
> 调用方统一传带符号差值 `scale - 1f`。超频三项倍率均 > 1，故生产/耗电恒为 `+`；掉血是独立的
> `-{2}` 字面量。早前版本 `percentText` 把「正数」当「节省量」渲染成负号，超频会被印成 `-50%`，已修正。

> 按钮**如何绘制**（位置/尺寸/按光标距离淡入/点击命中）与点击后**投递什么格式的消息**，
> 由 `BuildingBoostSystem` 与 `silicon.util.BoostOverlay` 负责，见 `docs/utils/BuildingBoostSystem.md`。

图标选 `overdrive`（而非 `overclock`——后者已用于润滑油），语义同为「加速」且火焰橙底板表达过热风险，
与润滑油的默认绿、节能的电量蓝区分开。

## 多人安全

- 倍率钩子**不缓存**每建筑状态：每次被引擎查询都回到 System 问一次 `isActive(build, id)`，无两端分歧窗口。
- 掉血在**两端各自**执行：血量是**服务器权威 + 同步**的网络化状态，故客户端的本地扣血只是即时反馈，
  会被服务器同步纠正，不会造成持久分歧。唯一时序风险是「两端进入超频的时刻相差 1 tick」，
  表现为客户端短暂多/少扣一次血，随后被同步抹平。
- 生效集合完全由「本队空间树 × 固定区域 × 模式」推导，两端输入一致。
- 本效果**不扣任何液体/物品**，无「扣多少网络化状态」的分支，故不需要确定性认领规则。

## 已知副作用

- **耗电是真实的，且会「反过来限住」产能**：`requestedPower` 按倍率放大后，电网供电不足时
  `power.status` 下降 → `updateConsumption` 的 `efficiency` 被压低 → **工厂可能完全停摆（产能 0）**。
  3 档超频要 6 倍功率，2 档 2.25 倍，1 档 1.3 倍。也就是说**电网撑不住时超频不是「慢一点」，而是「不产」**。
  这是「+500% 耗电」在 MJ 电力系统下的必然结果（`efficiency ≤ 1` 且与产能直接挂钩），不是 bug；
  若希望「供电不足时按比例降速而非直接停产」，需改 `BlockConsumerHooks` 的策略（当前未做）。
- 本效果**不抬 `efficiency`**（引擎 `updateConsumption` 取所有 consumer 的最小值、且初值即 1，
  `efficiency` 恒 ≤ 1，抬不上去）。提速全部由 `BlockConsumerHooks.boostProgress` 向 `progress`
  追加「超出 1 倍的那一份」实现，故 `efficiency`/`optionalEfficiency` 与依赖 `efficiency > 0`
  的产出节流判定**保持原值**，超频不会顺带影响它们。
- 方块面板的**耗电量**显示读的是钩子镜像的原值 `usage`（故仍显示标称值），
  **电力条**显示的是供电满足度 `power.status`，均不受倍率影响。即超频在面板上不可见，
  只能通过电网负载与产出速率观察（掉血则直接可见）。
- 掉血会**摧毁**工厂：血量归零即正常爆炸，可能连带损毁同格其他建筑——这是「以寿命换产能」的预期代价。
  若不希望它停机，把 `OverclockBoost.instance.damageRates` 全部设为 0 即可（代码已判速率 ≤0 跳过）。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `speedScales` | float[] | `{1.5f, 2.0f, 4.0f}` | 各档生产效率倍率（索引 0 = 1 级），经**注入进度**实现（`efficiency` 恒 ≤ 1，不能用于提速） |
| `powerScales` | float[] | `{1.3f, 2.25f, 6.0f}` | 各档耗电倍率（索引 0 = 1 级） |
| `damageRates` | float[] | `{4f, 10f, 45f}` | 各档掉血**速率**（生命/秒，索引 0 = 1 级） |
| `damageInterval` | float | `1f` | 扣血周期（秒）：每隔这么久扣一次「速率 × 周期」点生命；≤0 关闭扣血 |
| `warmupThreshold` | float | `0.5f` | 去抖阈值：工厂 `warmup` 需达到该值才认为「确实在运转」。设为 0 即不去抖（只要 `efficiency > 0` 立即生效）；调大更稳但启停更迟滞 |

倍率与扣血字段均为 `OverclockBoost.instance` 上的 public 字段，实时读取，可直接改。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始实现：耗电工厂获得生产 +50% / 耗电 +75%（经 `BlockConsumerHooks`，与节能共用钩子）+ 每 3 秒扣 10 生命（注入式，`ObjectMap` 逐建筑计时，`remove()` 清理）；与节能互斥；图标 `StatusEffects.overdrive`、底色 `Pal.lightFlame` |
| a0.x | **改为 3 档制 + 掉血改为速率语义**：`speedScales {1.5, 2.0, 4.0}` / `powerScales {1.3, 2.25, 6.0}` / `damageRates {4, 10, 45}`（生命/秒），`damageInterval` 改为 1s（周期 × 速率 = 每次扣血量）。档位由 Provider 持有、System 按目标回查；`FactorSource` 改为接收档位参数，`description()` 逐档拼装（bundle `boost.overclock.level`） |
| a0.x | **修复「超频不加速」**：`efficiency` 恒 ≤ 1（`updateConsumption` 取非可选 consumer 最小值且初值为 1），故把倍率返回给 `SpeedTaxConsume` 对 >1 的档位<b>完全无效</b>——被物品/液体 consumer 的 1.0 取小顶掉（节能的 0.85 因小于 1 才正常）。改为 `BlockConsumerHooks.boostProgress`：向 `progress` 追加「超出 1 倍的那一份」（`progress` 归一化，`craft()` 内 `% 1` 结转，故不会溢出）。`SpeedTaxConsume.efficiency` 同时把倍率夹到 ≤ 1，使该限制在代码里显式可见 |
| a0.x | 互斥裁决沿用「优先级 + 效果 id 字典序」（恒为节能胜出），未采用「先放置者胜」 |
| a0.x | **修复「工厂未工作仍扣血」**：`shouldApply` 由仅判 `enabled` 改为 `enabled && efficiency > 0 && warmup >= warmupThreshold`——缺料/产物堵满/断电时不再掉血，也不再按超频倍率占用电网，徽记同步下线（`BoostOverlay` 读的 `active` 表由 `shouldApply` 驱动）。`efficiency` 的归零路径经 `Building.updateConsumption()` 字节码确认；去抖用引擎自带的 `warmup` 平滑量（双向各约 36 tick）而非自建计数器——`shouldApply` 须无副作用，且多 Provider 会重复调用。因超频是<b>增</b>耗电效果，撤销只会让电网更宽裕，不构成震荡环 |
