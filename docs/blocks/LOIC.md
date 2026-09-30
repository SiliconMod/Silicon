# 近地轨道离子炮（LOIC · Low Orbit Ion Cannon）

> 第三种卫星类型（`SatelliteLauncher.TYPE_ION`）。发射链路见 [Satellite.md](Satellite.md) §4，
> 反制它的手段见 [AsatInterceptor.md](AsatInterceptor.md)。

## 1. 一句话

卫星单位自带的一门**对地武器**：60 秒冷却、只打地面建筑、落点大范围溅射（3000 中心伤害 / 8 格半径）。
发射到 LEO 后它自己飞、自己索敌、自己开火——玩家不需要也不应该手动操作它。

## 2. 为什么直接用单位武器

卫星就是 `Unit`，而 Mindustry 的单位武器（`Weapon`）本来就有我们需要的一切：

| 需要的能力 | 引擎已提供 |
|---|---|
| 冷却 | `Weapon.reload`（本例 3600 tick = 60 秒） |
| 索敌 | `Weapon.findTarget` → `Units.closestTarget(team, …)`，**第二个谓词就是建筑**（见下方源码） |
| 瞄准与开火 | `Weapon.update()` 每 tick 自跑，配合 `UnitComp.canShoot()` |
| 伤害与溅射 | `BulletType.damage` / `splashDamage` / `splashDamageRadius` |
| **联机同步** | 单位与武器状态由引擎同步，两端表现天然一致 |
| 冷却进度显示 | 武器挂点自带 `reload`/`heat`，可被绘制与统计读取 |

引擎里那条让"打建筑"成立的关键代码：

```java
// Weapon.findTarget（Weapon.java:447）
return Units.closestTarget(unit.team, x, y, range, 
    u -> u.checkTarget(air, ground),
    t -> ground && (unit.type.targetUnderBlocks || !t.block.underBullets));   // ← 建筑谓词
```

**过程记录**：这个功能最初被我实现成了一套自维护方案——`IonStrike` 全局冷却表 + 控制台「打击」按钮 +
自定义 `sat-ion` 网络包 + 自己写的溅射衰减。那是在重复造轮子，而且多出两个附带问题（客机上拿不到冷却进度、
多一份需要维护与测试的协议）。改成单位武器后，`IonStrike` 类、控制台的打击入口与两个网络包**全部删除**，
只剩一个 `Weapon` 定义。

## 3. 武器参数（`SatelliteUnits.ionWeapon()`）

| 项 | 值 | 说明 |
|---|---|---|
| 冷却 | **3600 tick = 60 秒** | `Weapon.reload` |
| 中心伤害 | **3000** | `BulletType.damage` |
| 溅射伤害 / 半径 | 3000 / **8 格**（64 px） | `splashDamage` / `splashDamageRadius`，边缘由引擎按距离衰减 |
| 碰撞掩码 | `collidesGround = true`、`collidesAir = false`、`collidesTiles = true` | 只打地面目标；不打空中单位 |
| 子弹 | `speed = 12`、`lifetime = 90` | 从轨道砸下：够快，但保留可见的坠落过程 |
| 命中/出膛特效 | `Fx.massiveExplosion` / `Fx.sparkShoot` | 落点必须"响" |
| 射界 | `shootCone = 360°`、`rotate = false` | 不转向表现，目标可能在任意方向 |

## 4. 为什么只有离子炮有武器

机型是**按轨道共享**的（信号卫星与测试卫星共用同一批 `satellite-leo/meo/geo/sso`），
把武器加在通用机型上会让所有 LEO 卫星都变成炮。所以离子炮有**独立机型** `satellite-loic`，
`SatelliteUnits.typeFor(orbit, type)` 在 `type == TYPE_ION` 时返回它。

挂载点不需要额外处理：`UnitComp` 在单位 `add()` 时会检查 `mounts().length != type.weapons.size`
并按需重建，因此在 `load()` 里给机型 `weapons.add(...)` 是安全的。

## 5. 为什么是自动开火而不是手动瞄准

卫星在第一阶段就被定成不可操控（`playerControllable = false`，逻辑处理器也控制不了）。
武器因此天然是"自动"的：**飞到哪里打到哪里**。想让离子炮轰某个基地，就得先把卫星送进那条轨道，
而 LEO 卫星在持续扫描移动——于是"什么时候能打"变成轨道位置的函数，而不是玩家点哪里。

若改成在地图上手动点坐标，等于把卫星变成遥控炮台，与第一阶段的定位冲突。

**它依然是隐身的**：挂了武器不影响"别人找它"——`targetable/hittable = false` 依旧生效，
原版与 mod 的任何炮塔都不会索敌它，子弹也会穿透。唯一能打它的仍是反卫星拦截塔
（索敌与伤害都绕过那两个旗标）。详见 [Satellite.md](Satellite.md) §3.1。

**用悬停预览判断打击时机**：把鼠标放到离子炮卫星上会显示它 ±100 秒的轨迹（见 [Satellite.md](Satellite.md) §5.1）。
由于武器自动索敌，**轨迹就是射程表**——轨迹线段压到哪些区域，就能预判它接下来 100 秒会轰哪里；
反过来，如果轨迹完全不经过敌方基地，那这一发就得等下一圈。这是这个武器唯一需要玩家判断的地方。

## 6. 平衡：它和反制端互为代价

| LOIC 一侧 | 量 |
|---|---|
| 生产 | 硅 8000 · 钍 3000 · 塑钢 2000 · 巨浪合金 2000（+ 冷冻液 2000） |
| 生产耗时 | **120 秒**（信号卫星的两倍） |
| 发射 | 轨道燃油（LEO 1000）+ 10000 缓冲电力 |
| 火力 | 3000 中心伤害 / 8 格，**60 秒一发** |
| 生存性 | **400 血，被反卫星拦截塔两发击落**，且 LEO 的锁定难度是 ×1.0（最好打） |

它是**最容易被反制**的那一档轨道，同时也只能沿 LEO 扫描线活动——想覆盖某个基地，就必须让它反复飞过那里。
于是攻防循环闭合：

```
LOIC 威胁地面  →  地面建定位器 + 拦截塔  →  拦截塔威胁卫星（LOIC 与对方的信号卫星）
```

## 7. 常量速查（`SatelliteUnits`）

| 常量 | 值 |
|---|---|
| `ION_DAMAGE` | 3000（中心值） |
| `ION_RADIUS_TILES` | 8（溅射半径，格） |
| `ION_COOLDOWN_TICKS` | 3600（= 60 秒） |
| 机型 | `SatelliteUnits.ionLeo`（内容名 `silicon-satellite-loic`，LEO 专用） |
