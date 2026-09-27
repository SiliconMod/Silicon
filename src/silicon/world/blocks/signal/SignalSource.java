package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
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
import mindustry.ui.Styles;
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
    /** 信号最大强度（中心原始强度；也是覆盖数字与强度条的标度上限） */
    public static final int MAX_STRENGTH = 99;
    /** 对数衰减的尺度（格）：raw = MAX·(1 − ln(1+d/σ)/ln(1+R/σ))，σ 越大核心区越平缓 */
    public static final float LOG_SIGMA = 3f;
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
        // 需要供电才能广播信号：150 电力/秒（Mindustry 功耗按 /60 tick 计）
        consumePower(150f / 60f);
        // 用于客户端同步信号名（服务器通过 tileConfig 下发）
        config(String.class, (SignalSourceBuild b, String value) -> {
            // tileConfig 双向通道同队客户端可发包:必须校验格式(4 位大写字母/数字, 统一走 Signal.isValidCode),
            // 否则超长/任意字符串会进入 SignalOverlay 颜色缓存与各处 UI
            if (!Signal.isValidCode(value)) return;
            b.signal = new Signal(value);
            // 客机编码经 tileConfig 到位会改变上行门控判定（placed 时 signal 还是 null，缓存可能已记 false）
            SignalChannel.invalidateLiveSources();
        });
        // 信道（1~5）
        config(Integer.class, (SignalSourceBuild b, Integer v) -> b.channel = Math.max(1, Math.min(SignalJammer.CHANNEL_MAX, v)));
    }

    /**
     * 以 (cx, cy) 为信号源中心、指定世界坐标 (wx, wy) 处的信号强度（世界坐标为像素，1 格 = 8px）。
     * 覆盖半径外（无信号区域）强度为 0；覆盖内按**对数距离衰减**：
     *
     * <pre>raw(d) = MAX_STRENGTH · ( 1 − ln(1 + d/σ) / ln(1 + R/σ) )      σ = {@link #LOG_SIGMA}（3 格）, R = {@link #RADIUS}（15 格）</pre>
     *
     * 中心满值（99）、半径 R 处精确归零（与旧高斯模型一样没有边缘悬崖），形状是"近场衰减快、远场衰减慢"的
     * 对数距离损耗（log-distance path loss）：1 格 83 / 3 格 61 / 5 格 45 / 7.5 格 30 / 10 格 18 / 12 格 10 / 13.5 格 4。
     * 通用方法：信号源、信号中继器、干扰器共用。
     */
    public static float strengthAt(float cx, float cy, float wx, float wy) {
        float dist = Mathf.dst(wx, wy, cx, cy) / 8f; // 像素 → 格
        if (dist >= RADIUS) return 0f; // 无信号区域强度为 0
        float scale = (float) Math.log(1.0 + RADIUS / LOG_SIGMA);
        float loss = (float) Math.log(1.0 + dist / LOG_SIGMA);
        return MAX_STRENGTH * (1f - loss / scale);
    }

    /**
     * 每队信号源缓存（建筑放置/拆除/加载时标记失效重建，避免每帧遍历 Groups.build）。
     */
    private static final ObjectMap<Team, Seq<SignalSourceBuild>> sourceCache = new ObjectMap<>();
    private static boolean dirty = true;

    /** 标记缓存失效（建筑增删时调用）；同步失效卫星上行门控缓存（SignalChannel.hasLiveSource） */
    public static void markDirty() {
        dirty = true;
        SignalChannel.invalidateLiveSources();
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        sourceCache.clear();
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb && !sb.removed) {
                sourceCache.get(sb.team, Seq::new).add(sb);
            }
        }
    }

    /** 收集某队伍的所有信号源（走缓存；get 的 Supplier 形式避免缓存未命中之外也分配） */
    public static Seq<SignalSourceBuild> allSources(Team team) {
        rebuildCache();
        Seq<SignalSourceBuild> list = sourceCache.get(team, Seq::new);
        // 自愈：缓存里不允许留下已拆除的源。拆除流程里 onRemoved() 早于建筑真正离开 Groups.build，
        // 那一刻若有代码查询源列表（中继器每 tick 的实时判定、频谱面板、H 覆盖、控制台面板），
        // 重建出来的缓存会把死源一起带回来并把 dirty 清掉，此后死源就永久留在列表里。
        // 这里按「是否仍是本格建筑」逐项剔除，保证任何一次查询之后缓存都不含死源
        // （每队信号源数量级很小，代价可忽略）。
        if (list.size > 0) {
            boolean pruned = false;
            for (int i = list.size - 1; i >= 0; i--) {
                SignalSourceBuild sb = list.get(i);
                if (sb.removed || !sb.isValid()) {
                    list.remove(i);
                    pruned = true;
                }
            }
            if (pruned) SignalChannel.invalidateLiveSources();
        }
        return list;
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
        // 兜底:随机 200 次未命中(理论仅当 36^4≈168 万空间近乎耗尽)时,
        // 一次性收集占用名后按字典序找第一个未用编码,避免固定返回 "ZZZZ" 造成重复编码。
        // 空间真耗尽(不可达)时维持旧兜底
        arc.struct.ObjectSet<String> used = new arc.struct.ObjectSet<>();
        for (Building b : Groups.build) {
            if (b instanceof SignalSourceBuild sb && sb.signal != null) {
                used.add(sb.signal.name);
            }
        }
        int base = chars.length();
        for (int n = 0; n < base * base * base * base; n++) {
            String c = new String(new char[]{
                    chars.charAt(n / (base * base * base) % base),
                    chars.charAt(n / (base * base) % base),
                    chars.charAt(n / base % base),
                    chars.charAt(n % base)});
            if (!used.contains(c)) return c;
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

    /** 放置预览（拖拽放置时）显示信号覆盖范围，同原版电力节点（x/y 为格坐标，转像素）：
     *  官方写法是 {@code x * 8f + offset}（见 PowerNode.drawPlace），**不加 4**——
     *  引擎里地格的坐标就是「索引 × 8」（建筑中心也在 8 的整数倍上） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8f + offset, y * 8f + offset, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalSourceBuild extends Building {
        /** 本源注册的信号（null 表示未生成/未同步） */
        public Signal signal;
        /** 信道（1~5，默认 1）：同信道信号互相隔离；被干扰器压制时失效 */
        public int channel = 1;
        /** 是否已进入拆除流程（onRemoved 置位）：拆除瞬间触发的缓存重建不得再把本源算进去 */
        public boolean removed;

        @Override
        public void placed() {
            super.placed();
            // 部分放置路径不会自动调用 add()（建筑不在活动组），手动补偿加入
            if (!added) {
                add();
            }
            // 服务器端生成唯一信号；客户端等待 tileConfig 同步（按队定向,不向敌队广播）
            if (Vars.net.client()) return;
            signal = new Signal(generateUniqueName());
            if (Vars.net.server()) {
                silicon.util.NetSync.sendTeamConfig(this, signal.name);
            }
        }

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            removed = false;
            markDirty();
            // 尽力向客户端重发信号名。注意:读档时 onProximityAdded 先于 read() 执行,
            // 此时 signal 还是 null,此处的重发实际不生效——真正的客机同步靠 MP 世界
            // 快照(自定义 write() 数据随 writeMap 下发),此调用仅覆盖运行期信号重建的边缘情形
            if (signal != null && Vars.net.server()) {
                silicon.util.NetSync.sendTeamConfig(this, signal.name);
            }
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            // onRemoved() 在建筑离开 Groups.build 之前调用：先把自己从缓存里摘掉并置 removed 标记，
            // 这样即使拆除瞬间有代码触发缓存重建，死源也不会回流（rebuildCache 里还会再挡一次）
            removed = true;
            for (Seq<SignalSourceBuild> list : sourceCache.values()) {
                list.remove(this, true);
            }
            markDirty();
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            // 夺取/换队：旧队的 (team, code) 缓存必须失效，否则旧队中继器/卫星
            // 依据残留缓存继续广播一个已不属于它的编码，直到下一次增删源才纠正
            markDirty();
        }

        /** 供电是否充足（power.status：0=无电，1=满电） */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        /** 本源在指定世界坐标处的原始信号强度（0~99；无信号、断电或被关闭（enabled=false）时为 0；干扰由 SignalChannel 统一计算） */
        public float strengthAt(float wx, float wy) {
            if (signal == null || !hasPower() || !enabled) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy);
        }

        /** 是否正在发射信号（与 strengthAt 的前置条件一致；频谱面板的占用计数以此为准，只数实际发射的源） */
        public boolean emitting() {
            return signal != null && hasPower() && enabled;
        }

        /** 配置面板（选择信道界面，灰底面板）：顶部显示本信号源编号，下方选信道 */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 编号跨满整行（避免挤占首列导致按钮间距不均），居中
                t.label(() -> Core.bundle.format("block.silicon-signal-source.code",
                        signal == null ? "----" : signal.name)).colspan(SignalJammer.CHANNEL_MAX).center().pad(2f);
                t.row();
                // 标题居中，原版黄色
                t.add(Core.bundle.get("block.silicon-signal-source.channel")).colspan(SignalJammer.CHANNEL_MAX).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> group = new arc.scene.ui.ButtonGroup<>();
                for (int i = 1; i <= SignalJammer.CHANNEL_MAX; i++) {
                    arc.scene.ui.TextButton btn = new arc.scene.ui.TextButton(String.valueOf(i), Styles.flatTogglet);
                    btn.setChecked(channel == i);
                    int ch = i;
                    btn.clicked(() -> configure(ch));
                    group.add(btn);
                    // growX 均分宿主列宽：按钮 1 贴紧左缘、按钮 5 贴紧右缘，整行铺满无空隙
                    t.add(btn).growX().height(40f).minWidth(44f).pad(1f);
                }
                t.row();
                // 信号频谱：本点 5 信道占用/干扰/可用强度（SINR 制的信道选择对策信息）
                silicon.ui.SignalSpectrum.buildSection(t, this, () -> channel);
            }).pad(4f);
        }

        /** 选中时显示信号覆盖范围（供电=深蓝，断电=灰色；填充圆 + 圆环，类似电力节点；半径为像素，15 格 = 120px） */
        @Override
        public void drawSelect() {
            super.drawSelect();
            boolean on = hasPower();
            Draw.color(on ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, on ? 0.07f : 0.04f);
            Fill.poly(x, y, 64, RADIUS * 8f);
            Draw.color(on ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, signal == null || !on ? 0.3f : 0.7f);
            Lines.stroke(2f);
            Lines.circle(x, y, RADIUS * 8f);
            Draw.reset();
        }

        /** 存档版本：1 = str(signal) + i(channel)；覆写 version() 使读档时 revision 正确（否则 channel 不读取） */
        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(signal == null ? "" : signal.name);
            write.i(channel);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            String name = read.str();
            // 存档里的编码同样校验（旧档/损坏档里的畸形串不进入缓存与 UI）
            signal = Signal.isValidCode(name) ? new Signal(name) : null;
            if (revision >= 1) {
                channel = read.i();
            }
        }
    }
}
