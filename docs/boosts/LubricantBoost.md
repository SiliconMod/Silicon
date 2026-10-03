# LubricantBoost

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `LubricantBoost` |
| 文件 | `src/silicon/util/boosts/LubricantBoost.java` |
| 包 | `silicon.util.boosts` |
| 类型 | `BuildingBoostSystem.Boost` 实现（注入式效果单元） |
| id | `lubricant` |
| 单例 | `LubricantBoost.instance`（静态块自动 `register` 进 System） |
| 提供方 | 润滑油注入器（`docs/blocks/LubricantInjector.md`） |

润滑油带来的**全部强化打包为一个效果单元**，内部含攻速、转角两个**子效果**：每个子效果的生效条件
独立判定，激活期间每帧调用 `apply` 时各自按需注入。

## 目标过滤

| 方法 | 行为 |
|------|------|
| `canTarget(Building)` | 仅炮塔（`target instanceof Turret.TurretBuild`）。由 System 在登记贡献前读取，不过滤则不登记、不进 `apply` |

## 子效果

### 攻速（+20%）

| 项 | 值 |
|----|----|
| 数值 | `firePotency = 0.2f`（+20% 射速） |
| 触发条件 | 炮塔 `isShooting()`（正在攻击）**且引擎本 tick 也会推进充能**（`canEngineReload`，见下） |
| 注入方式 | `reloadCounter += firePotency × edelta() × ammoRM` |

- `ammoRM` = 有弹药时 `peekAmmo().reloadMultiplier`，否则 `1`。
- 与**原版强化液同一加法池**（不是乘法），因此可与强化液叠加；原版超速（overdrive）对该池整体再乘。

> #### ⚠ 注入条件必须与引擎的充能守卫一致（这条曾被评审指出并修正）
>
> 引擎 `ReloadTurret.ReloadTurretBuild#handleReload` 的守卫是：
>
> ```java
> if(!reloadWhileCharging && charging()) return;
> if(reloadCounter >= reload)          return;
> updateReload();   // reloadCounter += delta * ammoReloadMultiplier * baseReloadSpeed
> ```
>
> 早前本注入**只看 `isShooting()`**，比引擎宽，后果有二：
>
> 1. **已充满的炮塔每 tick 仍被注入**。而 `updateShooting` 开火后是 `reloadCounter -= reload`、
>    **保留溢出量**——溢出不断累积，形成「蓄力 bursts」：炮塔可以先攒一整仓，缺弹期间照样每 tick 进账，
>    补弹瞬间连发多发。实际收益远高于文案写的 `+20%`。
> 2. **充能中的炮塔**（`charging()`，如激光/液流炮）也被推进，破坏 `charging()` 语义。
>
> 现由 `canEngineReload(t)` 逐条镜像上述守卫（`reloadWhileCharging` 在 `Turret` 上、`reload` 继承自
> `ReloadTurret`、`reloadCounter`/`charging()` 是建筑字段），非 `Turret` 派生的方块返回 `false`。
>
> > 另注：`baseReloadSpeed()` 是 `protected`，外部无法调用，故注入量用的是
> > `firePotency × edelta() × ammoRM` 而非引擎的 `delta() × ammoRM × baseReloadSpeed`。
> > 因此 `+20%` 是**相对 `baseReloadSpeed == 1` 的基准炮塔**成立的——装填速度更快的炮塔拿到的
> > 相对增益更低。这是已知取舍，非 bug。

### 转角（×2）

| 项 | 值 |
|----|----|
| 数值 | `rotationPotency = 1.0f`（引擎 1× + 本效果 1× = 总转角 **×2**，即 +100%） |
| 触发条件 | `hasAmmo() && shouldTurn()` |
| 注入方式 | 与引擎 `turnToTarget` 同式再推一格：`rotation = Angles.moveToward(rotation, des, rotationPotency × rotateSpeed × delta() × potentialEfficiency)` |

**目标角 `des` 必须与引擎同源**，否则两者互相抵消、等于没加。按引擎的三种控制模式分支：

| 控制模式 | 判定 | `des` 取值 |
|----------|------|-----------|
| 玩家控制 | `controlled()`（= `unit.isPlayer()`） | `Angles.angle(x, y, unit.aimX(), unit.aimY())`（鼠标瞄准） |
| 逻辑控制 | `logicControlled()`（= `logicControlTime > 0`） | `angleTo(targetPos)`（logic 写入的目标点） |
| 自动索敌 | 其余且 `target != null` | 先 `targetPosition(target)` 再 `angleTo(targetPos)`（引擎预测点） |
| 无可用角 | 三者皆不满足 | 跳过本子效果，不干扰引擎 |

> 引擎侧 rotation 仅在 `turnToTarget`（moveToward）、updateTile 的 NaN 兜底、read/readSync 处被写入；
> `des = angleTo(targetPos)` 恒成立，`shouldTurn() = moveWhileCharging || !charging()`（≈恒真）。

## 生命周期语义

- **注入式**：`apply` 每 tick 把数值累加进目标，`remove` 无需还原（条件消失即自动失效，无残留）。
- 生效期间 System **每 tick 调用一次 `apply`**（注入式需要逐帧累加），且**不叠加**——
  同一目标同一 id 每 tick 至多一次，无论多少注入器在提供。
- `shouldApply`：两个子效果**任一**具备条件即保持激活（`isShooting() || (hasAmmo() && shouldTurn())`），
  逐项注入由 `apply` 内部再判。

## 展示信息

| 方法 | 内容 | 来源 |
|------|------|------|
| `name()` | `润滑油` / `Lubricant` | bundle `boost.lubricant.name` |
| `description()` | `+100% 旋转速度；+20% 攻击速度` | bundle `boost.lubricant.desc` |
| `visual(Building)` | **统一图标** `BuildingBoostSystem.badgeIcon()` | 强化徽记（底板色 null → 渲染器默认） |
| `color()` | 不覆写 | 渲染器默认色 |

> 按钮**如何绘制**（位置/尺寸/按光标距离淡入/点击命中）与点击后**向消息面板投递什么格式**，
> 由 `BuildingBoostSystem` 与 `silicon.util.BoostOverlay` 负责，见 `docs/utils/BuildingBoostSystem.md`。

`description()` 的数值**必须与实际生效值一致**（转角 ×2 → +100%，攻速 +20%），
面板直接展示该字符串，调 `firePotency`/`rotationPotency` 时需同步文案。

## 多人安全

- 效果只改**纯本地模拟**量（炮塔 `reloadCounter` 与转角），与原版强化液同机制，两端各自模拟、无需网络同步。
- 是否生效取决于**同步状态**：注入器存油量（网络化液体）、炮塔是否在开火/有无弹药，故两端结果一致。
- 唯一提供者（认领者）由注入器用 **tile 坐标**确定性裁决，不由本 Boost 决定，详见注入器文档。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `firePotency` | float | `0.2f` | 每 tick 注入的固定充能点数（efficiency=timeScale=ammoRM=1 时即 +20% 射速） |
| `rotationPotency` | float | `1.0f` | 转角速率加成倍率（1.0 = 引擎 1× + 本效果 1× = ×2） |

两者均为 `LubricantBoost.instance` 上的 public 字段，可直接改：
`LubricantBoost.instance.firePotency = 0.3f;`

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始实现：攻速 +20%（强化液同池加法）、转角 ×2（目标角与引擎三分支同源）、`canTarget` 限炮塔、`visual` 用原版 overclock 状态图标、`name`/`description` 走 bundle |
| a0.x | **攻速注入条件对齐引擎**（外部评审 rt334）：新增 `canEngineReload(t)` 镜像 `ReloadTurret#handleReload` 的 `if(!reloadWhileCharging && charging()) return; if(reloadCounter >= reload) return;`——原先只看 `isShooting()`，会让**已充满**的炮塔每 tick 仍被注入，而 `updateShooting` 保留溢出量，累积成「补弹瞬间连发」的蓄力 bursts（实际收益远超文案 `+20%`），且会推进 `charging()` 中的炮塔。附带记录 `baseReloadSpeed()` 为 `protected` 不可调用，故 `+20%` 以 `baseReloadSpeed == 1` 为基准 |