# 反卫星拦截塔（ASAT Interceptor）

> 本文描述**击落卫星**的手段与其参数。卫星的运动与覆盖几何见 [Satellite.md](Satellite.md)；
> 信号侧的强度合成见 [Signal.md](Signal.md)。

## 1. 一句话

2×2 **炮塔**，自动锁定射程内**最近的敌方在轨卫星**，按装填节奏逐发打击，把卫星打下来。

## 2. 为什么是炮塔，却不开子弹

它继承 Mindustry 的 `Turret`，所以炮管转向、装填条、射界（`shootCone`）、开火前的炮口对准、
悬停面板的炮塔统计、逻辑处理器控制——这些炮塔该有的东西它都有。但它的开火**不创建子弹**：

卫星单位在第一阶段就被定成 `hittable = false` / `targetable = false`（见
`src/silicon/content/SatelliteUnits.java` 的类注释）：

- 所有索敌查询（`Units.bestTarget` / `Units.bestEnemy` 的 `targetable` 过滤）看不到卫星；
- 所有常规伤害路径（`Damage` 系列的 `hittable` 过滤）也打不到它，子弹直接穿透。

这是**有意**的：卫星不能被普通炮塔顺手打下来，否则任何覆盖敌基地的卫星都活不过一分钟。
代价是"击落卫星"必须由自定义逻辑完成——即 `unit.damage()` 这类 scripted 伤害，
血量 400 就是"拦截成本"。

因此这座炮塔借用的是炮塔的**外壳与节奏**，命中结算走 scripted 路径。实现上覆写了三个方法
（都在 `AsatInterceptor.AsatInterceptorBuild` 里、并写明了原因）：

| 覆写 | 为什么必须覆写 |
|---|---|
| `findTarget()` | 引擎索敌的候选集不含卫星（`targetable = false`），只能自己遍历单位表 |
| `validateTarget()` | 基类默认走 `Units.invalidateTarget`，对卫星一律判"失效"——不覆写的话，上一步刚找到的目标会被立刻清掉 |
| `shoot(BulletType)` | 不发子弹（卫星 `hittable = false`），直接对目标施加 `damagePerShot` 并播命中特效 |

## 3. 索敌与打击

| 项 | 值 | 说明 |
|---|---|---|
| 射程 | **40 格** | `Turret.range`。LEO 卫星约 2.8 格/秒横穿地图，一次过境在射程内停留十余秒 |
| 目标 | **最近的敌方卫星** | 不限类型（信号卫星 / 测试卫星都可以是目标）；己方与中立（derelict）卫星不受影响 |
| 射速 | **1 秒 / 发**（`reload = 60`） | 由炮塔基类驱动装填条与冷却 |
| 每发伤害 | **40** | 直接 `unit.damage()`，走引擎的 scripted 伤害路径 |
| 击落时间 | 10 秒 | 400 血 ÷ (40 × 1/s) |
| 转向 | `rotateSpeed = 3` | 大型设施，转向偏慢；开火前有炮口对准（`shootCone = 8°`） |
| 耗电 | 300/秒 | 断电或被逻辑门关闭即停火（`enabled && power.status > 0.001`） |
| 建造成本 | 铜 250 · 铅 180 · 硅 200 · 钍 80 · 钛 120 | 血量 900、护甲 4，定位是中后期防御设施 |

## 4. 击落后的收尾（不在本类里）

引擎销毁单位 → `UnitDestroyEvent` → `SatelliteManager.onUnitDestroyed`：

1. 从该队名册除名；
2. 向同队客机广播新的名册状态（`sat-state`）。

卫星控制台的在轨列表逐帧直接读实体血量，所以那一行会自然消失；该卫星编码的覆盖也随之消失
（若该编码还有地面源，中继器会退回按地面信号判定）。

## 5. 权威端与联机

- 伤害只在 `SatelliteManager.isAuthority()`（dedicated 服务器 / 主机 / 单机）时施加——客机上再跑一遍
  只会造成两端血量不一致；
- 血量本身随单位快照同步，因此客机**无需任何额外协议**即可看到掉血、光束与击落效果；
- 击落播报走 `Call.sendMessage`（全服一条短消息，不泄露发射方位置）。

## 6. 常量速查（`AsatInterceptor`）

| 常量 | 值 | 作用 |
|---|---|---|
| `range`（`Turret`） | 40 | 射程（格） |
| `reload`（`Turret`） | 60 | 装填 1 秒 / 发 |
| `damagePerShot` | 40 | 每发伤害；与 400 血共同决定 10 秒击落 |
| `shootCone`（`Turret`） | 8 | 开火所需炮口对准角度（度） |
| `rotateSpeed`（`Turret`） | 3 | 转向速度 |
| `targetAir/targetGround` | false | 关闭引擎索敌（卫星 `targetable = false`，改由 `findTarget()` 自己决定） |

## 7. 与信号玩法的关系

拦截塔不消耗、也不影响信号；但它提供了"**打断对方覆盖**"的手段：

- 打掉对方的某一编码卫星 → 该编码的卫星覆盖消失，只有地面源的那一片还能转发；
- 对方若要维持覆盖，需要重新发射（消耗中枢的石油与缓冲电力，见 README 的卫星章节）。

因此它把"卫星覆盖"从**一次性投入**变成了**需要保护的资产**，这是第二阶段的主要玩法增量。
