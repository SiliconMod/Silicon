package silicon.util;

import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * 联机同步辅助:按队定向 tileConfig 下发。
 * <p>
 * 服务器用 {@code Call.tileConfig(null, build, value)} 广播会把内容发给所有玩家——
 * 敌队客户端因此能持续收到我方中枢的电量/进度等运行数据(免费战场情报)。
 * 本工具只发给同队客户端;主机自身的本地执行(Loc.both 语义)照常发生,
 * 由各处理器的服务端守卫保证幂等无害。
 * 与 {@code Call.tileConfig(player, ...)} 定向语义相同(玩家加入补发已在用)。
 */
public class NetSync {
    private NetSync() {
    }

    /** 把建筑配置下发到该建筑所属队伍的所有客户端(主机本地同步执行,幂等) */
    public static void sendTeamConfig(Building build, Object value) {
        // 索引循环而非 for-each：arc 的 Seq 用 for-each 会分配迭代器，而本方法在信号源/中继器
        // 状态变化时会被调用（一帧内可能多次）。这里不维护「队伍 → 玩家」缓存：调用点是按需触发的
        // 变更路径而非每帧热路径，玩家数也少，O(M) 扫描足够，加缓存反而不值当维护失效逻辑。
        for (int i = 0; i < Groups.player.size(); i++) {
            Player p = Groups.player.index(i);
            if (p == null) continue; // 防御性空判:玩家断开清理间隙不抛 NPE
            if (p.team() == build.team) {
                Call.tileConfig(p, build, value);
            }
        }
    }
}
