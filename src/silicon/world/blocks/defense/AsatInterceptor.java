package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.world.Block;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteManager;

/**
 * 反卫星拦截塔（2×2）：锁定射程内的**敌方**在轨卫星，持续照射并直接造成 scripted 伤害。
 * <p>
 * 为什么是"照射"而不是炮弹：卫星单位的 {@code hittable/targetable = false}（见
 * {@link silicon.content.SatelliteUnits} 的类注释），子弹与所有常规伤害路径对它们完全失明——
 * 这是第一阶段就定下的设计：卫星只能被 {@code unit.damage()} 这类 scripted 伤害击落，
 * 血量 400 就是"拦截成本"。本方块正是那个入口的提供者。
 * <p>
 * 击落后的收尾不需要本类参与：引擎销毁单位 → {@code UnitDestroyEvent} →
 * {@link SatelliteManager#onUnitDestroyed} 名册除名并向同队客机广播。在轨列表的血量每帧直接读实体，
 * 因此那一行会自然消失。
 * <p>
 * 权威端：伤害只在 {@link SatelliteManager#isAuthority()} 时施加（客机上再跑一遍只会造成两端不一致）；
 * 血量本身随单位快照同步，客机无需额外协议即可看到掉血与击落。
 */
public class AsatInterceptor extends Block {
    /** 射程（格）。LEO 卫星约 2.8 格/秒横穿地图，射程决定"一次过境能否打满整条血" */
    public float rangeTiles = 40f;
    /** 每秒伤害：400 血 ÷ 40/s = 10 秒，一次穿越射程的过境足以击落 */
    public float damagePerSecond = 40f;
    /** 索敌节流（tick）：命中期间保持目标，不必每 tick 重扫全部单位 */
    public int retargetInterval = 10;

    /** 光束主色（静态常量：绘制每帧都要用，不能每帧解析字符串） */
    private static final Color BEAM_OUTER = Color.valueOf("ff5a3c");
    private static final Color BEAM_INNER = Color.valueOf("ffd2a8");

    public AsatInterceptor(String name) {
        super(name);
        buildType = AsatInterceptorBuild::new;
        size = 2;
        solid = true;
        destructible = true;
        update = true;
        configurable = false;
        // 开机即耗电（断电不工作）；数值与照射强度匹配，定位是中后期防御设施
        consumePower(300f / 60f);
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.range, rangeTiles, StatUnit.blocks);
        stats.add(Stat.damage, damagePerSecond, StatUnit.perSecond);
    }

    public class AsatInterceptorBuild extends Building {
        /** 当前锁定的卫星实体（null = 无目标） */
        public Unit target;
        /** 索敌节流计数 */
        private int retargetTimer = 0;
        /** 本帧是否正在照射（绘制用） */
        public boolean firing = false;

        /** 供电是否充足（power.status：0=无电，1=满电）——与信号源/中继器同一判据 */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        @Override
        public void updateTile() {
            firing = false;
            if (!enabled || !hasPower()) {
                target = null;
                return;
            }
            float range = rangeTiles * 8f;
            // 目标不再合用（被击落 / 飞离射程 / 换队）→ 立刻重新索敌，不等节流
            if (target != null
                    && (!target.isValid() || target.team == team || !target.within(x, y, range))) {
                target = null;
                retargetTimer = 0;
            }
            if (target == null && ++retargetTimer >= retargetInterval) {
                retargetTimer = 0;
                target = findTarget(range);
            }
            if (target == null) return;

            firing = true;
            // 只在权威端施加伤害：客机上跑一遍只会造成两端血量不一致
            if (SatelliteManager.isAuthority()) {
                boolean wasAlive = target.isValid();
                target.damage(damagePerSecond * delta() / 60f);
                if (wasAlive && !target.isValid()) {
                    Call.sendMessage(Core.bundle.format("block.silicon-asat-interceptor.kill",
                            target.type.localizedName));
                }
            }
        }

        /** 射程内最近的敌方卫星（类型不限：信号卫星与测试卫星都可能成为目标） */
        Unit findTarget(float range) {
            Unit best = null;
            float bestDst = Float.MAX_VALUE;
            for (Unit u : Groups.unit) {
                if (!(u.controller() instanceof OrbitSatelliteController)) continue;
                if (u.team == team || u.team == Team.derelict) continue;
                float dst = Mathf.dst(x, y, u.x, u.y);
                if (dst > range || dst >= bestDst) continue;
                bestDst = dst;
                best = u;
            }
            return best;
        }

        @Override
        public void draw() {
            super.draw();
            if (!firing || target == null || !target.isValid()) return;
            // 光束画在方块自身 draw() 里（与 SatelliteLauncher 的发射动画同一手法：绕开 Effect 管线，
            // 方块可见即特效可见）。抬 z 到方块层之上，画完立即复位，避免影响后续批次。
            float prevZ = Draw.z();
            Draw.z(Layer.block + 1f);
            float pulse = 0.55f + 0.45f * Mathf.absin(6f, 1f);
            Lines.stroke(2.4f, BEAM_OUTER.a(pulse));
            Lines.line(x, y, target.x, target.y);
            Lines.stroke(1f, BEAM_INNER.a(pulse));
            Lines.line(x, y, target.x, target.y);
            Lines.stroke(1.6f, BEAM_OUTER.a(0.8f));
            Lines.circle(target.x, target.y, 6f + 2f * pulse);
            Lines.stroke(1f);
            Draw.z(prevZ);
        }
    }
}
