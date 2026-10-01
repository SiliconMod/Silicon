package silicon.util;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.game.Team;
import mindustry.gen.Unit;

/**
 * 卫星定位情报：地面**卫星定位器**探测到的敌方卫星快照，供**反卫星拦截塔**索取目标。
 * <p>
 * 连接方式与卫星控制台 / 发射中枢一致：**通过信号编码配对**。定位器把探测结果发布到它绑定的
 * 编码，拦截塔只读取自己绑定的编码 —— 不再按队伍自动共享。
 * <p>
 * <b>配对是 N:N</b>：
 * <ul>
 *   <li>多个定位器可发布到同一编码 —— 各自的探测结果会**合并去重**（不是互相覆盖）；</li>
 *   <li>一座塔可读取任意一个编码的情报；同一编码可被多座塔读取。</li>
 * </ul>
 * 因此这里按「编码 → 发布者 → 该发布者的快照」三级存储：发布是写入**自己那一格**，
 * 读取时把所有**未过期**发布者的结果聚合起来。若做成"一个编码一份快照"，
 * 两个定位器绑同一编码时后者会静默覆盖前者，表现为"其中一座定位器白建了"。
 * <p>
 * 时效：每个发布者独立计时，超过 {@link #STALE_TICKS} 未刷新即从聚合结果中剔除，
 * 因此定位器一断电/一被拆，它贡献的那部分情报在几秒内自动消失，不影响同编码的其它定位器。
 */
public class SatelliteIntel {
    /** 单个发布者的情报有效期（tick）：超过这个时长没被刷新就当作过期 */
    public static final int STALE_TICKS = 180;
    /** 聚合结果的条目上限（防止极端情况下快照过大） */
    public static final int MAX_ENTRIES = 64;

    /** team -> (编码 -> 该编码下的各发布者快照) */
    private static final ObjectMap<Team, ObjectMap<String, Group>> map = new ObjectMap<>();
    private static final Seq<Unit> EMPTY = new Seq<>(0);

    /** 一个编码下所有发布者的集合，以及聚合缓存 */
    public static class Group {
        /** 发布者 id -> 它本次探测到的目标 */
        final ObjectMap<Long, Seq<Unit>> byPublisher = new ObjectMap<>();
        /** 发布者 id -> 它最近一次刷新时间（{@code Time.time}） */
        final ObjectMap<Long, Float> updatedAt = new ObjectMap<>();
        /** 聚合结果（跨发布者合并去重后的视图） */
        final Seq<Unit> merged = new Seq<>();
        /** 聚合结果对应的时间戳（同一帧内不重复聚合） */
        float mergedAt = Float.NaN;

        /** 重新聚合：跳过过期发布者、按实体去重、截断到上限 */
        void rebuild(float now) {
            if (mergedAt == now) return;
            mergedAt = now;
            merged.clear();
            // 快照键再改表（ObjectMap 迭代中 remove 不安全）
            Seq<Long> dead = null;
            for (ObjectMap.Entry<Long, Seq<Unit>> e : byPublisher) {
                Float t = updatedAt.get(e.key);
                if (t == null || now - t > STALE_TICKS) {
                    if (dead == null) dead = new Seq<>();
                    dead.add(e.key);
                    continue;
                }
                for (Unit u : e.value) {
                    if (u == null || !u.isValid() || u.team == Team.derelict) continue;
                    if (merged.contains(u)) continue;
                    merged.add(u);
                    if (merged.size >= MAX_ENTRIES) break;
                }
                if (merged.size >= MAX_ENTRIES) break;
            }
            if (dead != null) {
                for (Long id : dead) {
                    byPublisher.remove(id);
                    updatedAt.remove(id);
                }
            }
        }
    }

    /**
     * 发布/覆盖**某个发布者**在某编码下的探测快照（由定位器每 tick 调用）。
     * <p>
     * 只写自己那一格：同编码的其它定位器不受影响，读取端会合并所有人的结果。
     *
     * @param code        定位器绑定的信号编码（空值直接忽略：未绑定信号的定位器不发布任何情报）
     * @param publisherId 发布者标识（方块 id）——同一编码下用来区分是谁的贡献
     * @param units       本次探测到的敌方卫星
     * @param now         当前时间（{@code Time.time}），用于过期判定
     */
    public static void publish(Team team, String code, long publisherId, Seq<Unit> units, float now) {
        if (team == null || code == null || code.isEmpty()) return;
        ObjectMap<String, Group> byCode = map.get(team);
        if (byCode == null) {
            byCode = new ObjectMap<>();
            map.put(team, byCode);
        }
        Group g = byCode.get(code);
        if (g == null) {
            g = new Group();
            byCode.put(code, g);
        }
        Seq<Unit> own = g.byPublisher.get(publisherId);
        if (own == null) {
            own = new Seq<>();
            g.byPublisher.put(publisherId, own);
        }
        own.clear();
        int n = Math.min(units.size, MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            own.add(units.get(i));
        }
        g.updatedAt.put(publisherId, now);
        g.mergedAt = Float.NaN; // 失效缓存：下一次 get 重新聚合
    }

    /**
     * 取某队某编码当前**仍有效**的定位快照（已合并该编码下所有未过期发布者的结果）。
     * 过期、未发布或未绑定编码则返回空。返回的是内部对象，调用方只读遍历，不要修改。
     */
    public static Seq<Unit> get(Team team, String code, float now) {
        if (team == null || code == null || code.isEmpty()) return EMPTY;
        ObjectMap<String, Group> byCode = map.get(team);
        if (byCode == null) return EMPTY;
        Group g = byCode.get(code);
        if (g == null) return EMPTY;
        g.rebuild(now);
        return g.merged;
    }

    /** 某个定位器断电/被拆：只撤掉它自己的贡献，同编码的其它定位器不受影响 */
    public static void clearPublisher(Team team, String code, long publisherId) {
        if (team == null || code == null || code.isEmpty()) return;
        ObjectMap<String, Group> byCode = map.get(team);
        if (byCode == null) return;
        Group g = byCode.get(code);
        if (g == null) return;
        g.byPublisher.remove(publisherId);
        g.updatedAt.remove(publisherId);
        g.mergedAt = Float.NaN;
        if (g.byPublisher.isEmpty()) byCode.remove(code);
        if (byCode.isEmpty()) map.remove(team);
    }

    /** 清理某队某编码的全部发布者 */
    public static void clearFrom(Team team, String code) {
        if (team == null) return;
        ObjectMap<String, Group> byCode = map.get(team);
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
