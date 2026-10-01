package silicon.util;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.game.Team;
import mindustry.gen.Unit;

/**
 * 卫星定位情报：地面**卫星定位器**探测到的敌方卫星快照，供**反卫星拦截塔**索取目标。
 * <p>
 * 这里只做"队伍内的共享"：定位器把全图探测结果按**队伍**发布，本队所有拦截塔自动可见——
 * 不需要编码配对，也不需要玩家连线。信号编码是信号源的东西（只有信号源能发射信号），
 * 定位器不参与信号系统。
 * <p>
 * 时效：定位器每个 tick 覆盖写入，读取端检查"最近是否被刷新过"（{@link #STALE_TICKS}），
 * 因此定位器一断电/一被拆，情报在几秒内自动过期，不需要显式的注销消息（拆除路径也顺手清理）。
 */
public class SatelliteIntel {
    /** 情报有效期（tick）：超过这个时长没被刷新就当作过期 */
    public static final int STALE_TICKS = 180;
    /** 单次快照的条目上限（防止极端情况下快照过大） */
    public static final int MAX_ENTRIES = 64;

    private static final ObjectMap<Team, ObjectMap<String, Snapshot>> map = new ObjectMap<>();
    private static final Seq<Unit> EMPTY = new Seq<>(0);

    public static class Snapshot {
        public final Seq<Unit> units = new Seq<>();
        /** 最近一次刷新时间（{@code Time.time}） */
        public float updatedAt;
    }

    /**
     * 发布/覆盖某队某编码的探测快照（由定位器每 tick 调用）。
     *
     * @param code  定位器绑定的信号编码（空值直接忽略：未绑定信号的定位器不发布任何情报）
     * @param units 本次探测到的敌方卫星（最多 {@link #MAX_ENTRIES} 条）
     * @param now   当前时间（{@code Time.time}），用于过期判定
     */
    public static void publish(Team team, String code, Seq<Unit> units, float now) {
        if (team == null || code == null || code.isEmpty()) return;
        ObjectMap<String, Snapshot> byCode = map.get(team);
        if (byCode == null) {
            byCode = new ObjectMap<>();
            map.put(team, byCode);
        }
        Snapshot s = byCode.get(code);
        if (s == null) {
            s = new Snapshot();
            byCode.put(code, s);
        }
        s.units.clear();
        int n = Math.min(units.size, MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            s.units.add(units.get(i));
        }
        s.updatedAt = now;
    }

    /**
     * 取某队某编码当前**仍有效**的定位快照；过期、未发布或未绑定编码则返回空。
     * 返回的是内部对象，调用方只读遍历，不要修改。
     */
    public static Seq<Unit> get(Team team, String code, float now) {
        if (team == null || code == null || code.isEmpty()) return EMPTY;
        ObjectMap<String, Snapshot> byCode = map.get(team);
        if (byCode == null) return EMPTY;
        Snapshot s = byCode.get(code);
        if (s == null || now - s.updatedAt > STALE_TICKS) return EMPTY;
        return s.units;
    }

    /** 定位器断电/被拆时清理该队该编码的条目（过期机制之外的一道保险） */
    public static void clearFrom(Team team, String code) {
        if (team == null) return;
        ObjectMap<String, Snapshot> byCode = map.get(team);
        if (byCode == null) return;
        if (code == null || code.isEmpty()) {
            map.remove(team);
            return;
        }
        byCode.remove(code);
        if (byCode.isEmpty()) map.remove(team);
    }

    /** 清理某队全部编码（换队/整体重置用） */
    public static void clearFrom(Team team) {
        if (team != null) map.remove(team);
    }

    /** 换图/读档时整体清空（由 SatelliteManager 的重置路径调用） */
    public static void clear() {
        map.clear();
    }
}
