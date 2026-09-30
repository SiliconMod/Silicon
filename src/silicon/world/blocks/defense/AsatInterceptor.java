package silicon.world.blocks.defense;

import arc.Core;
import arc.math.Mathf;
import mindustry.content.Fx;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Posc;
import mindustry.gen.Unit;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteManager;

/**
 * 反卫星拦截塔（2×2 炮塔）：锁定射程内的**敌方**在轨卫星，按装填节奏逐发打击，每发造成 scripted 伤害。
 * <p>
 * 它继承 {@link Turret} 以取得炮塔该有的一切——炮管与转向、装填条（`reload`）、射界（`shootCone`）、
 * 开火前的炮口对准（`shootWarmup`）、悬停面板的炮塔统计、逻辑处理器控制——但**不开子弹**：
 * 卫星单位的 `hittable/targetable = false`（见 {@link silicon.content.SatelliteUnits}），
 * 引擎的伤害路径对它们完全失明，子弹会直接穿透。所以本方块借用炮塔的"外壳与节奏"，
 * 命中结算走 `unit.damage()` 这条 scripted 路径——第一阶段预留的那个入口（`health = 400f`）。
 * <p>
 * <b>平衡取向</b>：卫星是昂贵的一次性投入（发射中枢要 5000 铜 / 5000 硅 / 1250 塑钢 / 1250 巨浪，
 * 生产耗电 5000/秒 × 60 秒 + 轨道燃油 + 10000 缓冲电力），所以拦截手段**不能便宜**，
 * 否则卫星玩法会被一座塔废掉。本方块用三重成本把"拦截"变成需要决策的投入：
 * <ol>
 *   <li><b>每发电力</b>（{@link #powerPerShot}，从电网电池扣）：击落一颗 400 血卫星需要 10 发，
 *       即数万电力的一次性支出，规模与发射一颗卫星的电费同量级；</li>
 *   <li><b>常驻耗电</b>：待机也在烧电，守一个点是有代价的；</li>
 *   <li><b>射程只有 24 格</b>：一颗卫星扫过全图，一座塔只能守住一小片空域——要保护整片基地区域
 *       就得复制多座，成本随之线性上升。</li>
 * </ol>
 * 电力不足时炮塔**不推进装填**（见 {@link AsatInterceptorBuild#updateShooting()}）：它不会空放，
 * 而是等电池攒够再打。
 */
public class AsatInterceptor extends Turret {
    /** 每发伤害。配合 {@code reload = 60}（1 秒一发）= 40 伤害/秒 → 卫星 400 血需 10 秒 */
    public float damagePerShot = 40f;
    /** 每发消耗的电力（从电网电池扣）。击落一颗卫星 = 10 发，即 10 倍于本值的一次性支出 */
    public float powerPerShot = 8000f;

    public AsatInterceptor(String name) {
        super(name);
        // 引擎索敌全部关闭：卫星 targetable=false，Units.bestTarget/bestEnemy 永远看不到它们，
        // 目标改由 findTarget() 自己遍历 Groups.unit 决定
        targetAir = false;
        targetGround = false;
        targetBlocks = false;
        // 射程：只守一小片空域。一颗卫星扫过全图，想覆盖基地就得复制多座——这是主要的平衡杠杆
        range = 24f;
        reload = 60f;
        shootCone = 8f;
        rotateSpeed = 3f;   // 大型设施，转向偏慢
        cooldownTime = 60f;
        minWarmup = 0.75f;  // 开火前炮口必须基本对准：掠过的卫星不会一进射程就挨打
        // 常驻耗电：待机也在烧电
        consumePower(600f / 60f);
    }

    public class AsatInterceptorBuild extends TurretBuild {

        /**
         * 覆写索敌：卫星不在引擎的目标集合里（`targetable = false`），必须自己遍历单位表。
         * 取射程内**最近的敌方**卫星，类型不限；己方与中立（derelict）不入选。
         */
        @Override
        protected void findTarget() {
            Unit best = null;
            float bestDst = Float.MAX_VALUE;
            float range = range();
            for (Unit u : Groups.unit) {
                if (!(u.controller() instanceof OrbitSatelliteController)) continue;
                if (u.team == team || u.team == Team.derelict) continue;
                float dst = Mathf.dst(x, y, u.x, u.y);
                if (dst > range || dst >= bestDst) continue;
                bestDst = dst;
                best = u;
            }
            target = best;
            if (best != null) targetPosition(best); // 供基类的炮口对准使用
        }

        /**
         * 覆写目标合法性：基类默认走 {@code Units.invalidateTarget(target, ...)}，而该判定对
         * `targetable = false` 的卫星一律返回"失效"——只覆写 findTarget 的话，找到的目标会被这里立刻清掉。
         * 卫星的合法性由我们自己定义：还活着、仍是敌队、仍在射程内。
         */
        @Override
        protected boolean validateTarget() {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return false;
            if (u.team == team || u.team == Team.derelict) return false;
            return u.within(x, y, range());
        }

        /** 电量是否够打下一发（不够则整座塔待机：不推进装填、也不开火） */
        public boolean canAffordShot() {
            return power != null && power.graph != null && power.graph.getBatteryStored() >= powerPerShot;
        }

        /**
         * 覆写装填推进：电量不足时直接返回 —— 炮塔既不攒装填也不空放，只是待机。
         * 这样"电力"就真的成了弹药：没有电力基建就打不动卫星，而不是"打出去但没效果"。
         */
        @Override
        protected void updateShooting() {
            if (!canAffordShot()) return;
            super.updateShooting();
        }

        /**
         * 覆写开火：不创建子弹（卫星 `hittable = false`，子弹打不中），直接扣电 + 结算 scripted 伤害。
         * 装填节奏、炮口对准、冷却都由基类照常驱动，所以这里只管"这一发打出去发生了什么"。
         * 伤害与扣电都只在权威端执行：客机上再跑一遍会造成两端电量/血量不一致。
         */
        @Override
        protected void shoot(BulletType type) {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return;
            if (!SatelliteManager.isAuthority()) {
                // 客机只播表现（特效由服务端的血量变化自然触发不了，这里补一次纯视觉反馈）
                Fx.hitBulletBig.at(u.x, u.y);
                return;
            }
            if (power != null && power.graph != null) {
                power.graph.useBatteries(powerPerShot); // 每发电费从电池扣
            }
            boolean wasAlive = u.isValid();
            u.damage(damagePerShot);
            Fx.hitBulletBig.at(u.x, u.y);
            Fx.sparkShoot.at(this.x + Mathf.cosDeg(rotation) * 16f,
                    this.y + Mathf.sinDeg(rotation) * 16f, rotation);
            if (wasAlive && !u.isValid()) {
                Call.sendMessage(Core.bundle.format("block.silicon-asat-interceptor.kill",
                        u.type.localizedName));
            }
        }
    }
}
