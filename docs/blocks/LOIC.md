# 近地轨道离子炮（LOIC · Low Orbit Ion Cannon）

> 第三种卫星类型（`SatelliteLauncher.TYPE_ION`）。发射链路见 [Satellite.md](Satellite.md) §4，
> 反制它的手段见 [AsatInterceptor.md](AsatInterceptor.md)。

## 1. 一句话

卫星单位自带的一门**对地/对星武器**：1 秒连射、**5 发弹夹**（每 30 秒回 1 发）、
单发 **10000 伤害 / 10 格溅射**；可打地面建筑，也可打**低轨卫星**（LEO/SSO）。
两个开关——**自动发射**与**对星**——在卫星控制台的在轨列表里按颗切换。

## 2. 为什么直接用单位武器

卫星就是 `Unit`，而 Mindustry 的单位武器（`Weapon`）已经有我们需要的大部分能力：

| 需要的能力 | 引擎已提供 |
|---|---|
| 开火节奏 | `Weapon.reload`（本例 60 tick = 1 秒） |
| 索敌 | `Weapon.findTarget` → `Units.closestTarget(team, …)`，**第二个谓词就是建筑**（`Weapon.java:447`） |
| 瞄准与开火 | `Weapon.update()` 每 tick 自跑，配合 `UnitComp.canShoot()` |
| 伤害与溅射 | `BulletType.damage` / `splashDamage` / `splashDamageRadius` |
| **联机同步** | 单位与武器状态由引擎同步，两端表现天然一致 |

**过程记录**：这个功能最初被实现成一套自维护方案——`IonStrike` 全局冷却表 + 控制台「打击」按钮 +
自定义 `sat-ion` 网络包 + 自己写的溅射衰减。那是在重复造轮子，而且多出两个附带问题
（客机上拿不到冷却进度、多一份需要维护与测试的协议）。改成单位武器后那一整套全部删除。

**但引擎没有的东西要自己补**：`Weapon` 没有"弹夹"概念（`reload` 只是开火间隔），也没有
"每颗卫星两个开关"的状态位。这两样由 `silicon.util.LoicWeapon`（`Weapon` 的子类）补上：

| 自己补的 | 怎么补 |
|---|---|
| 弹夹（5 发）与恢复（30 秒/发） | 按 unitId 的状态表；在 `update()` 里连续累积（`Time.delta / rechargeTicks`） |
| 两个开关 | 同一张状态表；`shoot()` 里检查 `autoFire` 与弹夹余量 |
| 「对星」开关的索敌 | **重写 `findTarget`**（而不是在 `super` 之后过滤）——后者只返回最近的一个目标，若最近的恰是卫星而开关关着，会连更远的建筑一起漏掉 |

状态按 unitId 记在内存里、不落存档：读档后弹药回满、开关回默认（都开）。
好处是不必在存档块与广播串里再塞字段；代价是"存档时刚好打空"读档后可以立刻再打。

## 3. 武器参数

| 项 | 值 | 说明 |
|---|---|---|
| 开火间隔 | **60 tick = 1 秒** | `Weapon.reload` |
| 弹夹容量 | **5 发** | `LoicWeapon.maxAmmo` |
| 弹药恢复 | **1800 tick = 30 秒 / 发** | `LoicWeapon.rechargeTicks`，连续累积 |
| 中心伤害 | **10000** | `BulletType.damage` |
| 溅射伤害 / 半径 | 10000 / **10 格**（80 px） | `splashDamage` / `splashDamageRadius`，边缘由引擎按距离衰减 |
| 碰撞掩码 | `collidesGround = true`、`collidesAir = true`、`collidesTiles = true` | 对地打建筑；对空只打**低轨卫星**（见下） |
| 索敌射程 | **40 格** | 必须显式设置——`BulletType.range` 未设时由 `speed × lifetime` 推算（会变成 135 格） |
| 子弹 | `speed = 12`、`lifetime = 90` | 从轨道砸下：够快，但保留可见的坠落过程 |
| 命中/出膛特效 | `Fx.massiveExplosion` / `Fx.sparkShoot` | 落点必须"响" |
| 射界 | `shootCone = 360°`、`rotate = false` | 不转向表现，目标可能在任意方向 |

**"低轨卫星"的范围**：`LEO` 与 `SSO`——两者同属低轨，只是倾角不同（见 [Satellite.md](Satellite.md) §2.1）。
MEO/GEO 不在内：对着中高轨开炮不符合"近地"的设定。

**"对星"开关打开 ≠ 兼职防空**：索敌分三类判——低轨卫星受开关控制，**其他空中单位一律不打**，
地面目标（建筑与地面单位）照常。这三条写在 `LoicWeapon.findTarget` 的谓词里（而不是在
`super.findTarget` 之后过滤，理由见上一节）。

**弹药恢复依赖引擎的武器更新**（改代码前必须知道的一条链路）：

```
WeaponsComp.update()（每单位每帧）        → mount.weapon.update(self(), mount)
Weapon.update() 第二行                   → boolean can = unit.canShoot();
UnitComp.canShoot()                      → !disarmed && !(type.canBoost && isFlying())
```

`can == false` 时 `Weapon.update()` 是**整体返回**的——不只是不开火，连 `mount.reload` 的推进
和我们覆写的弹药恢复都被跳过。卫星是 `flying = true` 的单位，`orbitSatellite` 没设 `canBoost`
（默认 false），所以 `can` 恒为真、一切正常。**因此：不要让卫星类型变成 `canBoost`，也不要用
"施加状态效果"的方式给卫星停火**——两者都会让 `can=false`，表现为"弹药永不恢复、也不再开火"。
要停火请用控制台的「自动」开关（它只影响 `LoicWeapon.shoot`，不碰引擎这一层）。

## 4. 两个开关（在卫星控制台里）

在轨列表的 LOIC 行会多出三样东西：**弹药**（`弹 3/5`）与两个 toggle——**自动**（自动发射）与**对星**
（是否攻击低轨卫星）。

| 开关 | 关闭后的行为 |
|---|---|
| 自动 | 完全停火（弹药照常恢复） |
| 对星 | 索敌时把低轨卫星从候选集里排除——普通建筑照打 |

权威语义在**主机**：纯客机点击时通过 `sat-loic` 把**期望的新状态**发给主机，本地先乐观更新，
再被 `sat-loic-result` 回执覆盖（因此两台客机同时操作时以主机为准）。服务端校验与 `sat-launch` 同级
（坐标→控制台→队伍→enabled），并额外要求"该 unitId 确实是本队的离子炮卫星"，避免任意客户端
改别人卫星的武器状态。按钮状态每帧回读，所以主机上的改动也会反映到客机界面上。

## 5. 为什么是自动索敌而不是手动瞄准

卫星在第一阶段就被定成不可操控（`playerControllable = false`，逻辑处理器也控制不了）。
武器因此天然是"自动"的：**飞到哪里打到哪里**。想让离子炮轰某个基地，就得先把卫星送进那条轨道，
而 LEO 卫星在持续扫描移动——于是"什么时候能打"变成轨道位置的函数，而不是玩家点哪里。

若改成在地图上手动点坐标，等于把卫星变成遥控炮台，与第一阶段的定位冲突。
玩家能干预的是**开关**（打不打、打不打星），而不是"瞄哪"。

**用悬停预览判断时机**：把鼠标放到离子炮卫星上会显示它 ±100 秒的轨迹（见 [Satellite.md](Satellite.md) §5.1）。
由于武器自动索敌，**轨迹就是射程表**——轨迹压到哪些区域，就能预判它接下来 100 秒会轰哪里；
完全不经过敌方基地，那就得等下一圈。

**它依然是隐身的**：挂了武器不改变"别人找它"——`targetable/hittable = false` 依旧生效，
原版与 mod 的任何炮塔都不会索敌它，子弹也会穿透。唯一能打它的仍是反卫星拦截塔
（索敌与伤害都绕过那两个旗标）。详见 [Satellite.md](Satellite.md) §3.1。

## 6. 平衡

| LOIC 一侧 | 量 |
|---|---|
| 生产 | 硅 8000 · 钍 3000 · 塑钢 2000 · 巨浪合金 2000（+ 冷冻液 2000） |
| 生产耗时 | **120 秒**（信号卫星的两倍） |
| 发射 | 轨道燃油（LEO 1000）+ 10000 缓冲电力 |
| 爆发 | 5 发连射 = **5 秒内 50000 伤害** |
| 持续输出 | 1 发 / 30 秒 ≈ **333 伤害/秒** |
| 生存性 | **400 血，被反卫星拦截塔两发击落**，且 LEO 的锁定难度是 ×1.0（最好打） |

**注意这段的数值抬升**：改成长弹夹之前是"3000 伤害 / 60 秒"（≈50 伤害/秒），现在是 ≈333 伤害/秒
——**约 6.7 倍**，而且爆发期更集中。这是按需求指定的数值（1 秒冷却 + 5 发弹夹 + 30 秒回 1 发 + 10000 伤害），
若实战中发现过强，最容易调的三个旋钮是：

1. `ION_DAMAGE`（单发伤害，最直接）；
2. `ION_RECHARGE_TICKS`（弹药恢复速度，决定持续输出上限）；
3. `ION_MAGAZINE`（爆发窗口长度）。

它是**最容易被反制**的那一档轨道，且只能沿 LEO 扫描线活动，所以攻防循环仍然是闭合的：

```
LOIC 威胁地面  →  地面建定位器 + 拦截塔  →  拦截塔威胁卫星（LOIC 与对方的信号卫星）
```

## 7. 常量速查

| 常量 | 值 |
|---|---|
| `SatelliteUnits.ION_DAMAGE` | 10000（中心值） |
| `SatelliteUnits.ION_RADIUS_TILES` | 10（溅射半径，格） |
| `SatelliteUnits.ION_COOLDOWN_TICKS` | 60（= 1 秒） |
| `SatelliteUnits.ION_MAGAZINE` | 5（弹夹） |
| `SatelliteUnits.ION_RECHARGE_TICKS` | 1800（= 30 秒 / 发） |
| `SatelliteUnits.ION_RANGE_TILES` | 40（索敌射程） |
| 机型 | `SatelliteUnits.ionLeo`（内容名 `silicon-satellite-loic`，LEO 专用） |
| 状态类 | `silicon.util.LoicWeapon.State`（ammo / autoFire / attackSats） |
