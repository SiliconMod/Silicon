package silicon.world.blocks.signal;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import silicon.util.SignalOverlay;

/**
 * 信号中继器：位于信号覆盖范围（信号源或已激活中继器的 15 格内）时自动激活，
 * 激活后自身同样提供半径 15 格的信号，可级联延长信号覆盖。
 * 信号强度与信号源一致（正态分布衰减，0~15），绑定放置队伍。
 */
public class SignalRelay extends Block {
    /** 中继器信号半径（格） */
    public static final float RADIUS = SignalSource.RADIUS;

    public SignalRelay(String name) {
        super(name);
        // 手动指定建筑类
        buildType = SignalRelayBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        // 需要更新以检测激活状态
        update = true;
    }

    /**
     * 每队中继器缓存（建筑放置/拆除/加载时标记失效重建，避免每帧遍历 Groups.build）。
     */
    private static final ObjectMap<Team, Seq<SignalRelayBuild>> relayCache = new ObjectMap<>();
    private static boolean dirty = true;

    /** 标记缓存失效（建筑增删时调用） */
    public static void markDirty() {
        dirty = true;
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        relayCache.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalRelayBuild rb) {
                relayCache.get(rb.team, Seq::new).add(rb);
            }
        }
    }

    /** 收集某队伍的所有中继器（走缓存） */
    public static Seq<SignalRelayBuild> allRelays(Team team) {
        rebuildCache();
        return relayCache.get(team, new Seq<>());
    }

    /** 放置预览显示信号范围（同信号源） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8 + 4f, y * 8 + 4f, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalRelayBuild extends Building {
        /** 是否已激活（在信号覆盖范围内） */
        public boolean active = false;
        private int timer = 0;

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            SignalRelay.markDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            SignalRelay.markDirty();
        }

        @Override
        public void updateTile() {
            // 每 20 tick 检测一次激活状态（级联传播：逐级激活）
            if (++timer >= 20) {
                timer = 0;
                updateActive();
            }
        }

        void updateActive() {
            boolean newActive = false;
            // 被禁用（如开关控制）时不激活
            if (!enabled) {
                active = false;
                return;
            }
            // 遍历本队信号源缓存（不再每帧遍历 Groups.build）
            for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
                if (Mathf.dst(x, y, sb.x, sb.y) <= RADIUS * 8f) {
                    newActive = true;
                    break;
                }
            }
            // 附近有已激活的中继器（级联），走中继器缓存
            if (!newActive) {
                for (SignalRelayBuild rb : SignalRelay.allRelays(team)) {
                    if (rb == this || !rb.active) continue;
                    if (Mathf.dst(x, y, rb.x, rb.y) <= RADIUS * 8f) {
                        newActive = true;
                        break;
                    }
                }
            }
            active = newActive;
        }

        /** 本中继器在指定世界坐标处的信号强度（0~15，激活时） */
        public float strengthAt(float wx, float wy) {
            if (!active) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy);
        }

        /** 选中时显示信号范围（激活=深蓝，未激活=灰色） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            Draw.color(active ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, active ? 0.6f : 0.3f);
            Lines.stroke(2f);
            Lines.circle(x, y, RADIUS * 8f);
            Draw.reset();
        }

        /** 存档/网络同步 active 字段（host 上由 updateActive 重算，保证一致性） */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(active);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            active = read.bool();
        }
    }
}
