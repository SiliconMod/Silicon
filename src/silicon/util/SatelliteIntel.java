package silicon.util;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.gen.Unit;
import mindustry.game.Team;

/**
 * 卫星定位情报：地面**卫星定位器**探测到的敌方卫星快照，供**反卫星拦截塔**索取目标。
 * <p>
 * 塔与定位器之间没有直连，也不需要在同一片区域：它们靠**信号编码**耦合——定位器把自己绑定的编码
 * 与其探测结果一起发布，塔则按自己绑定的编码来查。于是"能不能打"取决于**信息**：
 * 没有定位器（或定位器断电、被拆、探测范围内没有目标、塔绑了别的编码），塔就是一座没有目标的炮塔。
 * <p>
 * 时效：定位器每个 tick 覆盖写入自己的条目，读取端检查"最近是否被更新过"（{@link #STALE_TICKS}），
 * 因此定位器一断电/一被拆，情报在几秒内自动过期，不需要显式的注销消息（拆除路径也顺手清理，见 {@link #clearFrom}）。
 */
public class SatelliteIntel {
    /** 情报有效期（tick）：超过这个时长没被刷新就当作过期（定位器断电/被拆后自动失效） */
    public static final int STALE_TICKS = 180;
    /** 单个定位器的情报条目上限（防止极端情况下快照过大） */
    public static final int MAX_ENTRIES = 32;

    /** key = 队伍 id + "|" + 编码 */
    private static final ObjectMap<String, Snapshot> map = new ObjectMap<>();

    public static class Snapshot {
        public final Seq<Unit> units = new Seq<>();
        /** 最近一次刷新时的全局 tick（{@code Time.time} 的整数近似由调用方提供） */
        public float updatedAt;
    }

    static String key(Team team, String code) {
        return team.id + "|" + code;
    }

    /**
     * 发布/覆盖某队某编码的定位快照（由定位器每 tick 调用）。
     *
     * @param units 本次探测到的敌方卫星（按序写入，最多 {@link #MAX_ENTRIES} 条）
     * @param now   当前时间（{@code Time.time}），用于过期判定
     */
    public static void publish(Team team, String code, Seq<Unit> units, float now) {
        if (team == null || code == null || code.isEmpty()) return;
        Snapshot s = map.get(key(team, code));
        if (s == null) {
            s = new Snapshot();
            map.put(key(team, code), s);
        }
        s.units.clear();
        int n = Math.min(units.size, MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            s.units.add(units.get(i));
        }
        s.updatedAt = now;
    }

    /**
     * 取某队某编码当前**仍有效**的定位快照；过期或从未发布则返回空。
     * 返回的 Seq 是内部对象，调用方只读遍历，不要修改。
     */
    public static Seq<Unit> get(Team team, String code, float now) {
        if (team == null || code == null || code.isEmpty()) return EMPTY;
        Snapshot s = map.get(key(team, code));
        if (s == null || now - s.updatedAt > STALE_TICKS) return EMPTY;
        return s.units;
    }

    private static final Seq<Unit> EMPTY = new Seq<>(0);

    /** 定位器被拆除时清理自己的条目（过期机制之外的一道保险，避免短期内读到已被拆掉的定位器的数据） */
    public static void clearFrom(Team team, String code) {
        if (team == null || code == null) return;
        map.remove(key(team, code));
    }

    /** 换图/读档时整体清空（由 SatelliteManager 的重置路径调用） */
    public static void clear() {
        map.clear();
    }
}
