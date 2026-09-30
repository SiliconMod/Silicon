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
 * 它继承 {@link Turret} 以取得炮塔该有的一切——炮管与转向、装填条（`reload`）、
 * 射界判定（`shootCone`）、开火前的炮口对准（`shootWarmup`）、悬停面板的炮塔统计、逻辑处理器控制——
 * 但**不开子弹**：卫星单位的 `hittable/targetable = false`（见 {@link silicon.content.SatelliteUnits}），
 * 引擎的伤害路径（`Damage` 系列的 `hittable` 过滤）对它们完全失明，子弹会直接穿透。
 * 所以本方块只借用炮塔的"外壳与节奏"，命中结算走 `unit.damage()` 这条 scripted 路径——
 * 这正是第一阶段预留的那个入口（`health = 400f` 即拦截成本）。
 * <p>
 * 三个必须覆写的点，都在 {@link AsatInterceptorBuild} 里写明了原因：
 * {@code findTarget}（引擎索敌看不到卫星）、{@code validateTarget}（否则刚找到的目标会被判失效清掉）、
 * {@code shoot}（不发子弹，直接结算伤害）。
 */
public class AsatInterceptor extends Turret {
    /** 每发伤害。配合 {@code reload = 60}（1 秒一发）= 40 伤害/秒 → 卫星 400 血需 10 秒 */
    public float damagePerShot = 40f;

    public AsatInterceptor(String name) {
        super(name);
        // 引擎索敌全部关闭：卫星 targetable=false，Units.bestTarget/bestEnemy 永远看不到它们，
        // 目标改由 findTarget() 自己遍历 Groups.unit 决定
        targetAir = false;
        targetGround = false;
        targetBlocks = false;
        // 炮塔参数：射程 40 格（LEO 约 2.8 格/秒，一次过境在射程内停留十余秒 → 够打满 400 血）
        range = 40f;
        reload = 60f;
        shootCone = 8f;
        rotateSpeed = 3f;   // 大型设施，转向偏慢
        cooldownTime = 60f;
        // 后坐/热表现交给基类默认值；本方块没有弹道，shoot() 里自行播放命中特效
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

        /**
         * 覆写开火：不创建子弹（卫星 `hittable = false`，子弹打不中），直接结算 scripted 伤害。
         * 装填节奏、炮口对准、冷却都由基类照常驱动，所以这里只管"这一发打出去发生了什么"。
         * 伤害只在权威端施加：客机上再跑一遍只会造成两端血量不一致（血量随单位快照同步）。
         */
        @Override
        protected void shoot(BulletType type) {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return;
            if (SatelliteManager.isAuthority()) {
                boolean wasAlive = u.isValid();
                u.damage(damagePerShot);
                if (wasAlive && !u.isValid()) {
                    Call.sendMessage(Core.bundle.format("block.silicon-asat-interceptor.kill",
                            u.type.localizedName));
                }
            }
            // 命中表现：卫星本体很小（视觉尺寸与 hitSize 解耦），用一个明显的命中特效 + 炮口闪光
            Fx.hitBulletBig.at(u.x, u.y);
            Fx.sparkShoot.at(this.x + Mathf.cosDeg(rotation) * 14f, this.y + Mathf.sinDeg(rotation) * 14f, rotation);
        }
    }
}
