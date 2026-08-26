package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import silicon.util.SignalOverlay;
import silicon.world.meta.Signal;

/**
 * 信号源：放置后注册一个信号（名称 4 个字母或数字，绑定放置队伍）。
 * 在半径 15 格的圆内广播信号，强度随距离线性衰减（最大 15，最小 0）。
 * 按 H 键可查看信号覆盖（缩放视角较小时逐格显示强度数字，较大时显示绿色范围）。
 */
public class SignalSource extends Block {
    /** 信号覆盖半径（格） */
    public static final float RADIUS = 15f;
    /** 信号最大强度 */
    public static final int MAX_STRENGTH = 15;
    /** 信号名称长度 */
    public static final int NAME_LENGTH = 4;

    public SignalSource(String name) {
        super(name);
        // 手动指定建筑类（Mindustry 的反射自动检测对非静态内部类不可靠）
        buildType = SignalSourceBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        // 必须 update=true：Groups.build（活动建筑组）只包含需要更新的建筑，否则放置后无法被查询/绘制
        update = true;
        configurable = true;
        // 用于客户端同步信号名（服务器通过 tileConfig 下发）
        config(String.class, (SignalSourceBuild b, String value) -> {
            if (value != null && !value.isEmpty()) {
                b.signal = new Signal(value);
            }
        });
    }

    /**
     * 以 (cx, cy) 为信号源中心、指定世界坐标 (wx, wy) 处的信号强度（世界坐标为像素，1 格 = 8px）。
     * 覆盖半径外（无信号区域）强度为 0；覆盖内按正态分布（高斯）衰减：
     * 中心最强（15），随距离按 exp(-d²/2σ²) 衰减，边缘趋近 0。
     * 通用方法：信号源与信号中继器共用。
     */
    public static float strengthAt(float cx, float cy, float wx, float wy) {
        float dist = Mathf.dst(wx, wy, cx, cy) / 8f; // 像素 → 格
        if (dist > RADIUS) return 0f; // 无信号区域强度为 0
        // 正态分布衰减：σ = 6 格（过渡平缓），半径 15 格处强度趋近 0
        float sigma = 6f;
        float gaussian = (float) Math.exp(-(dist * dist) / (2f * sigma * sigma));
        return MAX_STRENGTH * gaussian;
    }

    /**
     * 每队信号源缓存（建筑放置/拆除/加载时标记失效重建，避免每帧遍历 Groups.build）。
     */
    private static final ObjectMap<Team, Seq<SignalSourceBuild>> sourceCache = new ObjectMap<>();
    private static boolean dirty = true;

    /** 标记缓存失效（建筑增删时调用） */
    public static void markDirty() {
        dirty = true;
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        sourceCache.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb) {
                sourceCache.get(sb.team, Seq::new).add(sb);
            }
        }
    }

    /** 收集某队伍的所有信号源（走缓存） */
    public static Seq<SignalSourceBuild> allSources(Team team) {
        rebuildCache();
        return sourceCache.get(team, new Seq<>());
    }

    /** 生成一个未被使用的 4 字符信号名（大写字母 A-Z + 数字 0-9） */
    public static String generateUniqueName() {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 200; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NAME_LENGTH; i++) {
                sb.append(chars.charAt(Mathf.random(chars.length() - 1)));
            }
            String candidate = sb.toString();
            if (!isNameUsed(candidate)) return candidate;
        }
        return "ZZZZ";
    }

    static boolean isNameUsed(String name) {
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb && sb.signal != null && name.equals(sb.signal.name)) {
                return true;
            }
        }
        return false;
    }

    /** 放置预览（拖拽放置时）显示信号覆盖范围，同原版电力节点（x/y 为格坐标，转像素） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8 + 4f, y * 8 + 4f, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalSourceBuild extends Building {
        /** 本源注册的信号（null 表示未生成/未同步） */
        public Signal signal;

        @Override
        public void placed() {
            super.placed();
            // 部分放置路径不会自动调用 add()（建筑不在活动组），手动补偿加入
            if (!added) {
                add();
            }
            // 服务器端生成唯一信号；客户端等待 tileConfig 同步
            if (Vars.net.client()) return;
            signal = new Signal(generateUniqueName());
            if (Vars.net.server()) {
                Call.tileConfig(null, this, signal.name);
            }
        }

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            markDirty();
            // 加载存档后向客户端重新同步（自定义字段不随实体系统同步）
            if (signal != null && Vars.net.server()) {
                Call.tileConfig(null, this, signal.name);
            }
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            markDirty();
        }

        /** 本源在指定世界坐标处的信号强度（0~15；无信号时为 0） */
        public float strengthAt(float wx, float wy) {
            if (signal == null) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy);
        }

        /** 选中时显示信号覆盖范围（填充圆 + 圆环，类似电力节点；半径为像素，15 格 = 120px） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            Draw.color(SignalOverlay.SIGNAL_COLOR, 0.07f);
            Fill.poly(x, y, 64, RADIUS * 8f);
            Draw.color(SignalOverlay.SIGNAL_COLOR, signal == null ? 0.3f : 0.7f);
            Lines.stroke(2f);
            Lines.circle(x, y, RADIUS * 8f);
            Draw.reset();
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(signal == null ? "" : signal.name);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, read.b());
            String name = read.str();
            signal = name.isEmpty() ? null : new Signal(name);
        }
    }
}
