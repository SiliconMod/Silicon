package silicon.util;

import arc.math.Mathf;
import arc.struct.IntFloatMap;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;

/**
 * 近地轨道离子炮（LOIC）的对地打击：充能冷却、目标选择与结算。
 * <p>
 * <b>为什么是"命令"而不是"手动瞄准"</b>：卫星在第一阶段就被定成不可操控（`playerControllable = false`，
 * 逻辑处理器也控制不了）。所以玩家只决定**何时**开火，落点由卫星自己在其**星下点覆盖范围内**挑选——
 * 取射程内最近的敌方建筑。这既保住了"卫星不是遥控飞机"的定位，也让"打哪里"变成轨道位置的自然结果：
 * 想让离子炮轰某个基地，就得先把卫星送到它上空。
 * <p>
 * <b>只打建筑</b>：不伤单位、更不伤友军。伤害按距离衰减（中心满伤、边缘趋零），落点偏移不会让伤害凭空消失。
 * <p>
 * <b>冷却不落存档</b>：{@code readyAt} 按 unitId 记在内存里，读档后视为**已就绪**。这样最不容易出岔子
 * （读档时序、名册重建、unitId 复用都不影响它）；代价只是"存档前刚好打完一炮"可以在读档后立刻再打一次。
 */
public class IonStrike {
    /** 冷却时长（tick）：60 秒 */
    public static final float COOLDOWN_TICKS = 60f * 60f;
    /** 单次打击的中心伤害（按距离线性衰减到边缘的 0） */
    public static final float DAMAGE = 3000f;
    /** 打击半径（格）：落点周围这个范围内的敌方建筑受损 */
    public static final float RADIUS_TILES = 8f;

    /** 结果码（控制台提示与网络回执共用） */
    public static final int RESULT_OK = 0;
    public static final int RESULT_COOLDOWN = 1;
    public static final int RESULT_NO_TARGET = 2;

    /** unitId → 下次可开火时间（{@code Time.time}） */
    private static final IntFloatMap readyAt = new IntFloatMap();

    /** 该卫星是否已充能完毕（就绪） */
    public static boolean ready(int unitId, float now) {
        return now >= readyAt.get(unitId, Float.NEGATIVE_INFINITY);
    }

    /** 距离就绪还有多少秒（0 = 已就绪），面板显示用 */
    public static float readyInSeconds(int unitId, float now) {
        float at = readyAt.get(unitId, Float.NEGATIVE_INFINITY);
        return at <= now ? 0f : (at - now) / 60f;
    }

    /**
     * 在卫星星下点附近挑一个敌方建筑：射程内**最近**的一个。
     *
     * @param radius 打击半径（世界像素）
     * @return 目标建筑，没有则 null
     */
    public static Building findTarget(Team team, float x, float y, float radius) {
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for (Building b : Groups.build) {
            if (b.team == team || b.team == Team.derelict) continue;
            float dst = Mathf.dst(x, y, b.x, b.y);
            if (dst > radius || dst >= bestDst) continue;
            bestDst = dst;
            best = b;
        }
        return best;
    }

    /**
     * 执行一次打击（**只在权威端调用**）。伤害与冷却都改在权威端；特效是世界层的，
     * 两端各自播放不会冲突（客机由建筑血量变化自然看到结果）。
     *
     * @return {@link #RESULT_OK} / {@link #RESULT_COOLDOWN} / {@link #RESULT_NO_TARGET}
     */
    public static int strike(Team team, SatelliteManager.SatelliteRecord r, float satelliteX, float satelliteY) {
        float now = Time.time;
        if (!ready(r.unitId, now)) return RESULT_COOLDOWN;

        // 落点取该卫星当前星下点的覆盖范围（LEO 40 格 @250×250 图）
        float radius = SatelliteManager.coverageRadius(r.orbit);
        Building target = findTarget(team, satelliteX, satelliteY, radius);
        if (target == null) return RESULT_NO_TARGET;

        readyAt.put(r.unitId, now + COOLDOWN_TICKS);

        float bx = target.x, by = target.y;
        float hit = RADIUS_TILES * 8f;
        // 按距离线性衰减：中心满伤、边缘趋零。只伤敌方建筑，不碰单位与友军。
        for (Building b : Groups.build) {
            if (b.team == team || b.team == Team.derelict) continue;
            float d = Mathf.dst(bx, by, b.x, b.y);
            if (d > hit) continue;
            b.damage(DAMAGE * (1f - d / hit));
        }

        // 视觉：卫星处象征性发火 + 落点的大爆炸（离子炮的关键反馈都在落点）
        Fx.sparkShoot.at(satelliteX, satelliteY, Mathf.random(360f));
        Fx.massiveExplosion.at(bx, by);
        Fx.flakExplosionBig.at(bx, by);
        Fx.explosion.at(bx, by);
        return RESULT_OK;
    }

    /** 换图/读档时清空（与 SatelliteIntel 同步，见 SatelliteManager.reset） */
    public static void clear() {
        readyAt.clear();
    }
}
