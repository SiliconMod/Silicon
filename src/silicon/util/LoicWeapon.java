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
        // 构造即记录：mod 加载时就会写，用来确认诊断链路本身是活的
        // （若这一行都不出现，说明诊断代码没进 jar，问题在构建/安装而非游戏）
        diagLine("[constructed] " + name + " @ " + System.currentTimeMillis());
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
        // 诊断：确认武器更新确实被引擎调用（若此日志不出现，问题在 canShoot/武器挂载，而非索敌）
        if (debug && debugDue(unit.id)) {
            dbg(unit.id, "update", "ammo=" + f2(s.ammo) + " auto=" + s.autoFire + " sats=" + s.attackSats
                    + " canShoot=" + unit.canShoot() + " mountTarget=" + desc(mount.target)
                    + " reload=" + f2(mount.reload) + " mountShoot=" + mount.shoot
                    + " hadTarget=" + debugHadTarget.get(unit.id, Boolean.FALSE));
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
        // 诊断：索敌结果（target=null 说明候选集为空，需要看上面两个分支各自的过滤原因）
        if (debug) {
            int buildings = 0, satellites = 0;
            for (Building b : Groups.build) {
                if (b.team == Team.derelict) continue;
                if (selfOk == false && b.team == unit.team) continue;
                if (Mathf.dst(x, y, b.x, b.y) <= limit) buildings++;
            }
            for (Unit u : Groups.unit) {
                if (u == unit || !isLowOrbitSatellite(u)) continue;
                if (Mathf.dst(x, y, u.x, u.y) <= limit) satellites++;
            }
            boolean had = best != null;
            debugHadTarget.put(unit.id, had);
            dbg(unit.id, "findTarget", "picked=" + desc(best)
                    + " limit=" + f2(limit) + " range=" + f2(range)
                    + " air=" + air + " ground=" + ground
                    + " collidesAir=" + bullet.collidesAir + " collidesGround=" + bullet.collidesGround
                    + " inRangeBuildings=" + buildings + " inRangeSats=" + satellites
                    + " siblingsBuild=" + Groups.build.size() + " siblingsUnit=" + Groups.unit.size());
        }
        return best;
    }

    /**
     * 目标<b>失效判定</b>，与自定义索敌对称。
     * <p>
     * <b>语义务必看清</b>：引擎在 {@code Weapon.update()} 里的用法是
     * <pre>
     * if(mount.target != null &amp;&amp; checkTarget(unit, mount.target, mountX, mountY, bullet.range)){
     *     mount.target = null;   // 返回 true = 失效 = 清空目标
     * }
     * </pre>
     * 也就是说本方法返回 {@code true} 表示"该目标应被清除"。基类实现走
     * {@code Units.invalidateTarget}，会把沙盒己方建筑与 {@code targetable=false} 的卫星判为失效。
     * <p>
     * 这里改为按本武器的实际目标类型判断：**仍在射程内且类型合法就返回 false（保留）**，
     * 只有真的不可用（实体消失、被拆、越界、开关关闭）才返回 true。
     */
    @Override
    protected boolean checkTarget(Unit unit, Teamc target, float x, float y, float range) {
        boolean invalid = checkTargetRaw(unit, target, x, y, range);
        // 诊断：这一步返回 true 会导致 Weapon.update 立刻清空目标（此前语义写反就是在这里静默失败）
        if (debug && invalid) {
            dbg(unit.id, "checkTarget", "REJECTED " + desc(target) + " range=" + f2(range));
        }
        return invalid;
    }

    /** 失效判定的实际逻辑（与诊断分离，便于阅读） */
    private boolean checkTargetRaw(Unit unit, Teamc target, float x, float y, float range) {
        float limit = range + Math.abs(shootY);
        if (target instanceof Building b) {
            if (!b.isValid() || b.team == Team.derelict) return true;
            if (b.team == unit.team && !SatelliteManager.testSatelliteAvailable()) return true;
            if (!unit.type.targetUnderBlocks && b.block.underBullets) return true;
            return !b.within(x, y, limit + b.hitSize() / 2f);
        }
        if (target instanceof Unit u) {
            if (!u.isValid() || u.team == Team.derelict) return true;
            if (!isLowOrbitSatellite(u)) return true;
            if (!state(unit.id).attackSats) return true;
            if (u.team == unit.team && !SatelliteManager.testSatelliteAvailable()) return true;
            return !u.within(x, y, limit + u.hitSize() / 2f);
        }
        return true;
    }

    /** 开火：先扣弹药，"自动发射"关闭或弹夹为空都不发射 */
    @Override
    protected void shoot(Unit unit, WeaponMount mount, float shootX, float shootY, float rotation) {
        State s = state(unit.id);
        boolean blocked = !s.autoFire || s.ammo < 1f;
        // 诊断：进入 shoot 说明前面的门都过了；若这里被阻止，就是弹药/开关的问题
        dbg(unit.id, "shoot", "entered aim=(" + f0(shootX) + "," + f0(shootY) + ") rot=" + f0(rotation)
                + " ammo=" + f2(s.ammo) + " auto=" + s.autoFire
                + " blocked=" + blocked + " target=" + desc(mount.target));
        if (blocked) return;
        s.ammo -= 1f;
        super.shoot(unit, mount, shootX, shootY, rotation);
    }

    // ————— 诊断辅助 —————

    private static String f0(float v) { return String.valueOf((int) v); }
    private static String f2(float v) { return String.valueOf(Math.round(v * 100f) / 100f); }

    /** 目标的可读描述（诊断日志用） */
    private static String desc(Object t) {
        if (t == null) return "null";
        if (t instanceof Building b) return "Building(" + b.block.name + ",team=" + b.team + ",hp=" + (int) b.health + ")";
        if (t instanceof Unit u) return "Unit(" + u.type.name + ",id=" + u.id + ",team=" + u.team + ")";
        return t.getClass().getSimpleName();
    }

    /** 是否已就绪（弹药 ≥ 1 且开启自动发射）——UI 与调试用 */
    public static boolean ready(Unit unit) {
        State s = state(unit.id);
        return s.autoFire && s.ammo >= 1f;
    }

    // ————————————————— 诊断插桩 —————————————————
    //
    // 开火链路连续多次判断失误（hasAmmo 门控、沙盒放宽、checkTarget 语义），
    // 所以这里留下可开关的运行时日志：打开后能一次性区分
    //   「武器根本没被更新」/「更新了但索敌为空」/「有目标但被校验清掉」/「校验通过却没进 shoot」
    // 这四种完全不同的故障。默认关闭，避免刷屏。
    //
    // 输出**不走引擎日志**：last_log.txt 每次启动都会被清空重写，一次没进图的启动就会把
    // 上一次的现场冲掉（已实际踩到）。这里直接追加写独立文件，除非手工删除否则不会丢。

    /** 诊断开关（默认关）。设置界面可勾选，或控制台执行 `silicon.loic.debug = true` */
    public static boolean debug = false;

    /** 独立诊断文件（追加写，不经引擎日志系统，避免被 last_log.txt 覆盖） */
    private static final String DIAG_FILE = "silicon-loic-diag.log";

    /** 诊断输出节流（每颗卫星每 N tick 最多一条） */
    private static final int DEBUG_INTERVAL = 60;
    private static final ObjectMap<Integer, Integer> debugTick = new ObjectMap<>();

    /** 诊断是否已被启用过一次（用于只写一次"诊断已启动"标记） */
    private static boolean diagAnnounced = false;

    /** 该卫星本轮是否应当输出诊断（按 tick 节流；返回 true 时已累计计数） */
    private static boolean debugDue(int unitId) {
        int t = debugTick.get(unitId, 0) + 1;
        if (t >= DEBUG_INTERVAL) {
            debugTick.put(unitId, 0);
            return true;
        }
        debugTick.put(unitId, t);
        return false;
    }

    /**
     * 追加一行诊断到独立文件。
     * <p>
     * 用 {@code arc.Files} 的相对路径落在游戏数据目录（与 mods 同级），便于直接查看；
     * 写失败绝不影响游戏逻辑（整段包在 try 里，异常直接吞掉）。
     */
    private static void diagLine(String text) {
        try {
            // 用 Vars.dataDirectory（%APPDATA%\Mindustry）直接构造，避开 arc.Files 实例问题
            mindustry.Vars.dataDirectory.child(DIAG_FILE).writeString(text + "\n", true);
        } catch (Throwable ignored) {
            // 诊断写失败不影响游戏
        }
    }

    /** 统一诊断输出（只在 debug 打开时写文件） */
    private static void dbg(int unitId, String stage, String detail) {
        if (!debug) return;
        if (!diagAnnounced) {
            diagAnnounced = true;
            diagLine("=== LOIC 诊断已启用 " + System.currentTimeMillis() + " ===");
        }
        diagLine("[" + stage + "] unit=" + unitId + " " + detail);
    }

    /** 上一次诊断时看到的目标（用于区分"从未找到过目标"与"目标被中途清掉"） */
    private static final ObjectMap<Integer, Boolean> debugHadTarget = new ObjectMap<>();
}
