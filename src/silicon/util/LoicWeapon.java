package silicon.util;

import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.util.Time;
import mindustry.entities.units.WeaponMount;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Weapon;

/**
 * 离子炮武器（LOIC 专用）：弹夹 5 发、每 30 秒回 1 发、开火间隔 1 秒；可对地（建筑）与对**低轨卫星**开火。
 * <p>
 * 为什么需要 {@link Weapon} 的子类：引擎的武器没有"弹夹"概念（`reload` 只是开火间隔）。
 * 这里用同一个 `reload` 表达 1 秒连射间隔，另外用一张按 unitId 的状态表表达弹药与两个开关。
 * <p>
 * <b>索敌是重写的</b>（而不是在 `super.findTarget` 之后过滤）：后者只会返回"最近的那一个"，
 * 若最近的恰是卫星而"攻击卫星"关着，就会整个返回 null、连更远的建筑都打不到。这里直接给
 * `Units.closestTarget` 传带条件的谓词，让引擎在候选集层面就排除掉不该打的东西。
 * <p>
 * 状态（弹药 / 两个开关）按 unitId 记在内存里，不落存档：读档后弹药回满、开关回默认（都开）。
 * 这样做的好处是不需要在存档块与广播串里再塞字段；开关的权威端语义仍然成立——只有权威端开火。
 */
public class LoicWeapon extends Weapon {
    /** 弹夹容量 */
    public float maxAmmo = 5f;
    /** 每恢复 1 发所需时间（tick）：30 秒 */
    public float rechargeTicks = 30f * 60f;

    public LoicWeapon(String name) {
        super(name);
    }

    /** 单颗卫星的武器状态 */
    public static class State {
        /** 当前弹药（浮点：恢复是连续累积的） */
        public float ammo = 5f;
        /** 是否自动发射（关掉即完全停火） */
        public boolean autoFire = true;
        /** 是否攻击低轨卫星（关掉则只打地面建筑） */
        public boolean attackSats = true;
    }

    private static final ObjectMap<Integer, State> states = new ObjectMap<>();

    /** 取（必要时创建）某卫星的武器状态 */
    public static State state(int unitId) {
        State s = states.get(unitId);
        if (s == null) {
            s = new State();
            states.put(unitId, s);
        }
        return s;
    }

    /** 已知状态（不存在返回 null；UI 只读用，不要凭空创建） */
    public static State peekState(int unitId) {
        return states.get(unitId);
    }

    /** 换图/读档时清空（与 SatelliteIntel 同步，见 SatelliteManager.reset） */
    public static void clear() {
        states.clear();
    }

    /** 该单位是不是"低轨卫星"（LEO 或 SSO：SSO 与 LEO 同属低轨，只是倾角不同） */
    public static boolean isLowOrbitSatellite(Unit u) {
        if (!(u.controller() instanceof OrbitSatelliteController c)) return false;
        return c.orbit == silicon.world.blocks.satellite.SatelliteConsole.ORBIT_LEO
                || c.orbit == silicon.world.blocks.satellite.SatelliteConsole.ORBIT_SSO;
    }

    @Override
    public void update(Unit unit, WeaponMount mount) {
        super.update(unit, mount);
        // 弹药恢复：连续累积，30 秒恰好回满 1 发（Time.delta 的单位是 tick）
        State s = state(unit.id);
        if (s.ammo < maxAmmo) {
            s.ammo = Math.min(maxAmmo, s.ammo + Time.delta * unit.reloadMultiplier / rechargeTicks);
        }
    }

    /**
     * 索敌：自己遍历，而不是用引擎的 `Units.closestTarget`。两个理由：
     * <ul>
     *   <li>引擎那个只认**敌方**——沙盒里没有第二个队，只打敌方等于一炮都打不出去，
     *       与反卫星拦截塔/定位器"沙盒自测放宽"的判据不一致（同一份 {@code testSatelliteAvailable()}）；</li>
     *   <li>需要在候选集层面就排除"普通空中单位"：对星开关打开 ≠ 兼职防空。</li>
     * </ul>
     * 三类目标分开判：低轨卫星（受开关控制）/ 其他空中单位（一律不打）/ 地面目标（照常）。
     * <p>
     * 本方法由 `Weapon.update` 按 `retarget` 间隔调用（不是每帧），遍历成本可忽略。
     */
    @Override
    protected Teamc findTarget(Unit unit, float x, float y, float range, boolean air, boolean ground) {
        boolean sats = state(unit.id).attackSats;
        // 沙盒自测放宽：与 AsatInterceptor / SatelliteLocator 同一判据
        boolean selfOk = SatelliteManager.testSatelliteAvailable();
        float limit = range + Math.abs(shootY);
        float bestDst = Float.MAX_VALUE;
        Teamc best = null;

        // 地面建筑
        if (ground) {
            for (Building b : Groups.build) {
                if (b.team == Team.derelict || (b.team == unit.team && !selfOk)) continue;
                if (!unit.type.targetUnderBlocks && b.block.underBullets) continue;
                float d = Mathf.dst(x, y, b.x, b.y);
                if (d > limit || d >= bestDst) continue;
                bestDst = d;
                best = b;
            }
        }

        // 单位：只有低轨卫星可能入选（且"对星"开关必须打开）
        if (sats) {
            for (Unit u : Groups.unit) {
                if (u == unit || u.team == Team.derelict || (u.team == unit.team && !selfOk)) continue;
                if (!isLowOrbitSatellite(u) || !u.checkTarget(air, ground)) continue;
                float d = Mathf.dst(x, y, u.x, u.y);
                if (d > limit || d >= bestDst) continue;
                bestDst = d;
                best = u;
            }
        }
        return best;
    }

    /**
     * 与自定义 findTarget 对称的目标校验。
     * <p>
     * 不能沿用 Weapon.checkTarget：它内部调用 Units.invalidateTarget，会把沙盒己方建筑和
     * `targetable=false` 的卫星再次判为无效。结果就是 findTarget 明明找到了目标，Weapon.update
     * 下一步却把 target 清掉，shoot 永远不会执行。
     */
    @Override
    protected boolean checkTarget(Unit unit, Teamc target, float x, float y, float range) {
        if (target == null) return false;
        if (target instanceof Building b) {
            if (!b.isValid() || b.team == Team.derelict) return false;
            if (b.team == unit.team && !SatelliteManager.testSatelliteAvailable()) return false;
            return b.within(x, y, range + Math.abs(shootY) + b.hitSize() / 2f);
        }
        if (target instanceof Unit u) {
            if (!isLowOrbitSatellite(u)) return false;
            if (u.team == Team.derelict) return false;
            if (u.team == unit.team && !SatelliteManager.testSatelliteAvailable()) return false;
            if (!state(unit.id).attackSats) return false;
            return u.within(x, y, range + Math.abs(shootY) + u.hitSize() / 2f);
        }
        return false;
    }

    /** 开火：先扣弹药，"自动发射"关闭或弹夹为空都不发射 */
    @Override
    protected void shoot(Unit unit, WeaponMount mount, float shootX, float shootY, float rotation) {
        State s = state(unit.id);
        if (!s.autoFire || s.ammo < 1f) return;
        s.ammo -= 1f;
        super.shoot(unit, mount, shootX, shootY, rotation);
    }

    /** 是否已就绪（弹药 ≥ 1 且开启自动发射）——UI 与调试用 */
    public static boolean ready(Unit unit) {
        State s = state(unit.id);
        return s.autoFire && s.ammo >= 1f;
    }
}
