package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
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

/**
 * 信号中继器：**每 tick 实时判定能否转发**——绑定编码的地面/卫星有效强度（SINR 折算后）>
 * {@link #FORWARD_THRESHOLD} 时才转发。地面链路走 {@link SignalChannel#groundEffAt}（底噪 + 同信道
 * 异编码 CCI + 邻信道泄漏 + 干扰器），因此干扰器/同信道异编码源可以把它压断；卫星链路走
 * {@link silicon.util.SatelliteManager#satelliteStrengthAt}（含上行门控）。级联由"已激活的同编码
 * 中继器本身也是发射机"自然产生（零衰减，仍受 SINR 约束）。转发时自身与信号源同模型广播（半径
 * 15 格、对数衰减 0~99），绑定放置队伍。
 * <p>注意：15 格是**原始**覆盖半径；SINR 阈值下实际可转发/可绑定的半径更小（净空单源约 12.5 格），
 * 覆盖显示与频谱给出的数值才是判定依据。
 */
public class SignalRelay extends Block {
    /** 中继器信号半径（格） */
    public static final float RADIUS = SignalSource.RADIUS;
    /** 转发阈值：地面/卫星链路的有效强度（SINR 折算后）都必须大于该值才转发——
     *  与覆盖显示、频谱"可用度"同一口径，阈值以下=压不过底噪+干扰（或弱到无法解调）。
     *  取值 = 标度上限 / 30（原 15 标度下的 0.5），保持相对语义不变 */
    public static final float FORWARD_THRESHOLD = 3.3f;

    public SignalRelay(String name) {
        super(name);
        // 手动指定建筑类
        buildType = SignalRelayBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        // 需要更新以检测激活状态
        update = true;
        // 需要供电才能工作：50 电力/秒（Mindustry 功耗按 /60 tick 计）
        consumePower(50f / 60f);
        // 可配置：绑定信号源编号（空串=清除绑定；非空必须是合法 4 位编码——tileConfig 是双向通道，
        // 同队客户端可发包，不校验会让任意长字符串进入 liveSrcCache 与按钮文本）
        configurable = true;
        config(String.class, (SignalRelayBuild b, String value) -> {
            if (value == null || value.isEmpty()) {
                b.selectedSource = null;
            } else if (silicon.world.meta.Signal.isValidCode(value)) {
                b.selectedSource = value;
            }
        });
        // active 状态同步（服务器在激活状态变化时下发；客机应用后 H 覆盖可显示级联段）。
        // 客机伪造的 Boolean 会在下一 tick 的 updateActive 被本地重算覆盖，天然自愈。
        config(Boolean.class, (SignalRelayBuild b, Boolean v) -> {
            if (v != null) b.active = v;
        });
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
            if (b instanceof SignalRelayBuild rb && !rb.removed) {
                relayCache.get(rb.team, Seq::new).add(rb);
            }
        }
    }

    /** 收集某队伍的所有中继器（走缓存） */
    public static Seq<SignalRelayBuild> allRelays(Team team) {
        rebuildCache();
        Seq<SignalRelayBuild> list = relayCache.get(team, new Seq<>());
        // 自愈：同 SignalSource.allSources——拆除流程中 onRemoved() 早于建筑离开 Groups.build，
        // 那一瞬间触发的重建会把死中继器带回缓存，之后 H 覆盖会一直画出不存在的级联段。
        if (list.size > 0) {
            for (int i = list.size - 1; i >= 0; i--) {
                SignalRelayBuild rb = list.get(i);
                if (rb.removed || !rb.isValid()) {
                    list.remove(i);
                }
            }
        }
        return list;
    }

    /** 放置预览显示信号范围（同信号源） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        Draw.color(SignalOverlay.SIGNAL_COLOR, 0.5f);
        Drawf.circles(x * 8f + offset, y * 8f + offset, RADIUS * 8f);
        Draw.reset();
    }

    public class SignalRelayBuild extends Building {
        /** 是否已激活（在所选信号源覆盖范围内） */
        public boolean active = false;
        /** 绑定的信号源编号（4 位；null/空=未绑定，不发射） */
        public String selectedSource = null;
        /** 中继信道（兼容字段：未绑定时用；绑定后信道跟随所选信号源） */
        public int channel = 1;
        /** 上一 tick 实测入站强度：地面（SINR 有效值）与卫星——面板/悬停信息实时显示，跨端本地计算 */
        public float inGroundEff = 0f, inSatEff = 0f;
        /** 上次渲染的信号源列表签名（配置面板实时刷新用） */
        private String lastSrcSignature = "";
        /** 配置面板源按钮组（选中态实时同步用；面板关闭后无引用也无妨） */
        private arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> srcBtnGroup = null;
        /** 是否已进入拆除流程（onRemoved 置位）：拆除瞬间触发的缓存重建不得再把本中继器算进去 */
        public boolean removed;

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            removed = false;
            SignalRelay.markDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            removed = true;
            for (Seq<SignalRelayBuild> list : relayCache.values()) {
                list.remove(this, true);
            }
            SignalRelay.markDirty();
        }

        @Override
        public void updateTile() {
            // 绑定信号自动失效：绑定的编码在本队已无任何存活信号源（源被拆掉/被打掉）时清除绑定。
            // 编码是随机生成的，源拆掉后无法再建出同名源，留着绑定只会永久显示"信号不足"。
            // 判定用「编码是否还存在」而非「是否在范围内」——源仍在但断电/超距/被干扰时保留绑定。
            // 只在权威端判定，客机跟随 tileConfig 下发（避免网络延迟造成的误清）。
            if (selectedSource != null && !selectedSource.isEmpty()
                    && (!Vars.net.active() || silicon.util.SatelliteManager.isAuthority())
                    && !SignalChannel.hasLiveSource(team, selectedSource)) {
                selectedSource = null;
                configure("");
            }
            // 实时检测能否转发：每 tick 按 SINR 有效强度重算——干扰器开关、信号源断电/禁用、
            // 绑定变更、级联上下游变化都在同一 tick 生效。
            // 远处空闲中继由 SignalChannel.groundEffAt 的廉价前置短路（只做距离衰减，不跑完整批算），
            // 只有"确有本编码发射机覆盖本点"或"需要判断是否掉线"的中继才付全量代价。
            updateActive();
        }

        /** 供电是否充足（power.status：0=无电，1=满电） */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        /** 查找绑定的信号源（按编号） */
        public SignalSource.SignalSourceBuild findSource() {
            if (selectedSource == null || selectedSource.isEmpty()) return null;
            for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
                if (sb.signal != null && selectedSource.equals(sb.signal.name)) return sb;
            }
            return null;
        }

        /** 发射信道：绑定信号源后与其保持一致；绑定卫星编码时用在轨卫星发射时固化的信道
         *  （源被拆不影响卫星信道的固化值；但卫星广播有上行门控——编码无存活地面源时卫星静默，
         *  本中继器也会随之去活，此方法仅在激活状态下被广播路径调用）；未绑定用自身 channel */
        public int signalChannel() {
            SignalSource.SignalSourceBuild src = findSource();
            if (src != null) return src.channel;
            // 卫星中继：绑定编码有在轨卫星 → 用其发射时固化的信道（ SatelliteManager 名册）
            for (silicon.util.SatelliteManager.SatelliteRecord r : silicon.util.SatelliteManager.satellites(team)) {
                if (r.code != null && r.code.equals(selectedSource) && r.channel >= 1) return r.channel;
            }
            return channel;
        }

        /**
         * 实时重算"能否转发"，结果写入 {@link #active}（并记录入站强度供面板显示）。
         * <p>地面链路：{@link SignalChannel#groundEffAt}（SINR：底噪 + 同信道异编码 CCI + 邻信道泄漏 +
         * 干扰器）> {@link #FORWARD_THRESHOLD} 才转发——干扰器/异编码同信道源把有效强度压到阈值以下即断链，
         * 与卫星链路同一阈值。级联由"已激活的同编码中继器本身也是发射机"自然产生，不再需要几何距离特判。
         * <p>卫星链路：{@link silicon.util.SatelliteManager#satelliteStrengthAt} > 阈值（上行门控在内：
         * 编码无存活地面源时卫星静默）。
         */
        void updateActive() {
            boolean newActive = false;
            inGroundEff = 0f;
            inSatEff = 0f;
            // 被禁用（如开关控制）或断电时不激活；未绑定编码不转发
            if (enabled && hasPower() && selectedSource != null && !selectedSource.isEmpty()) {
                inGroundEff = SignalChannel.groundEffAt(team, selectedSource, x, y);
                if (inGroundEff > FORWARD_THRESHOLD) {
                    newActive = true;
                } else {
                    inSatEff = silicon.util.SatelliteManager.satelliteStrengthAt(team, selectedSource, x, y);
                    if (inSatEff > FORWARD_THRESHOLD) newActive = true;
                }
            }
            if (newActive != active) {
                active = newActive;
                SignalRelay.markDirty();
                // 激活状态变化 → 服务器下发到客机（active 是 mod 自定义字段，不随实体网络同步；
                // 按队定向,敌队客户端不再收到我方中继器激活时机）
                if (Vars.net.server()) silicon.util.NetSync.sendTeamConfig(this, active);
            }
        }

        /** 未转发的原因键（面板/悬停实时状态行用；null = 正在转发） */
        String forwardStatusKey() {
            if (active) return null;
            if (!enabled) return "block.silicon-signal-relay.status.off";
            if (!hasPower()) return "block.silicon-signal-relay.status.noPower";
            if (selectedSource == null || selectedSource.isEmpty()) return "block.silicon-signal-relay.nobind";
            return "block.silicon-signal-relay.status.weak";
        }

        /** 实时转发状态文本（配置面板与悬停信息共用；数据来自上一 tick 的实测入站强度） */
        String statusText() {
            String key = forwardStatusKey();
            String state = key == null ? Core.bundle.get("block.silicon-signal-relay.active")
                    : "[lightgray]" + Core.bundle.get(key) + "[]";
            return Core.bundle.format("block.silicon-signal-relay.status.line", state,
                    arc.util.Strings.fixed(inGroundEff, 1), arc.util.Strings.fixed(inSatEff, 1),
                    arc.util.Strings.fixed(FORWARD_THRESHOLD, 1));
        }

        /** 模糊匹配：query 的字符按顺序出现在 code 中（子序列匹配，忽略大小写）；空 query 匹配一切 */
        static boolean fuzzyMatch(String code, String query) {
            int qi = 0;
            for (int i = 0; i < code.length() && qi < query.length(); i++) {
                if (Character.toUpperCase(code.charAt(i)) == Character.toUpperCase(query.charAt(qi))) qi++;
            }
            return qi == query.length();
        }

        /** 重建源按钮区（按搜索模糊过滤；无匹配显示提示） */
        void rebuildSourceButtons(Table srcTable, String filter) {
            srcTable.clearChildren();
            srcTable.center();
            Seq<SignalSource.SignalSourceBuild> srcs = SignalSource.allSources(team);
            boolean any = false;
            arc.scene.ui.ButtonGroup<arc.scene.ui.TextButton> group = new arc.scene.ui.ButtonGroup<>();
            group.setMinCheckCount(0); // 允许全不选（默认 1 会在 add() 时强制勾选第一个按钮，且无法取消）
            int perRow = 5, count = 0;
            for (SignalSource.SignalSourceBuild sb : srcs) {
                String code = sb.signal == null ? "----" : sb.signal.name;
                if (!filter.isEmpty() && !fuzzyMatch(code, filter)) continue;
                any = true;
                arc.scene.ui.TextButton btn = new arc.scene.ui.TextButton(code, Styles.flatTogglet);
                btn.setChecked(code.equals(selectedSource));
                btn.clicked(() -> configure(code));
                group.add(btn);
                srcTable.add(btn).size(88f, 40f).pad(1f);
                if (++count % perRow == 0) srcTable.row();
            }
            srcBtnGroup = group;
            if (!any) {
                srcTable.add(Core.bundle.get("block.silicon-signal-relay.search.none"))
                        .color(arc.graphics.Color.lightGray).pad(2f);
            }
        }

        /** 信号源列表签名（数量 + 编号集合），用于检测列表变化 */
        String sourceSignature() {
            StringBuilder sb = new StringBuilder();
            Seq<SignalSource.SignalSourceBuild> srcs = SignalSource.allSources(team);
            sb.append(srcs.size).append(':');
            for (SignalSource.SignalSourceBuild s : srcs) {
                sb.append(s.signal == null ? "----" : s.signal.name).append(',');
            }
            return sb.toString();
        }

        /** 配置面板（与信号源面板风格一致：顶部当前编号 + 居中黄色标题 + 搜索 + 按钮行；多信号源时按钮区限高滚轮翻页） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 顶部：当前转发编号（跨满整行居中，与信号源编号显示风格一致）
                t.label(() -> Core.bundle.format("block.silicon-signal-relay.source.current",
                        selectedSource == null || selectedSource.isEmpty() ? Core.bundle.get("block.silicon-signal-relay.nobind") : selectedSource))
                        .colspan(SignalJammer.CHANNEL_MAX).center().pad(2f);
                t.row();
                // 实时转发状态行：每帧读取上一 tick 实测的入站强度（地面 SINR 有效值 / 卫星），
                // 未转发时给出原因（关闭/无电/未绑定/信号不足）——"干扰能不能压断这条链路"的直接显示
                t.label(() -> statusText()).colspan(SignalJammer.CHANNEL_MAX).center().pad(2f);
                t.row();
                // 标题居中，原版黄色（跨满整行，避免挤占首列导致按钮间距不均）
                t.add(Core.bundle.get("block.silicon-signal-relay.source")).colspan(SignalJammer.CHANNEL_MAX).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                // 源按钮区（先声明，供搜索框回调引用）：ScrollPane 限制高度，每行 5 个换行，按钮网格居中
                Table srcTable = new Table();
                // 搜索框（标题下方）：按编号过滤信号源
                arc.scene.ui.TextField search = t.field("", text -> rebuildSourceButtons(srcTable, text.trim()))
                        .colspan(SignalJammer.CHANNEL_MAX).width(280f).padTop(2f).get();
                search.setMessageText(Core.bundle.get("block.silicon-signal-relay.search"));
                search.setMaxLength(4);
                t.row();
                arc.scene.ui.ScrollPane pane = new arc.scene.ui.ScrollPane(srcTable, Styles.noBarPane);
                pane.setScrollingDisabled(true, false); // 禁水平滚动，允许垂直滚轮翻页
                // 跨满整行（与标题同宽），限高
                t.add(pane).height(160f).colspan(SignalJammer.CHANNEL_MAX).growX().padTop(2f);
                t.row();
                // 清除按钮（跨满整行居中，与标题/按钮对齐）
                t.button(Core.bundle.get("block.silicon-signal-relay.source.clear"), Styles.defaultt,
                        () -> configure("")).colspan(SignalJammer.CHANNEL_MAX).center().size(88f, 40f).padTop(2f);
                t.row();
                // 信号频谱：本点 5 信道占用/干扰/可用强度（当前转发信道行高亮）
                silicon.ui.SignalSpectrum.buildSection(t, this, () -> signalChannel());
                // 实时刷新：信号源列表变化（增删/编号变更）时重建按钮区（保持搜索过滤；点击不受影响）
                lastSrcSignature = "";
                pane.update(() -> {
                    String sig = sourceSignature();
                    if (!sig.equals(lastSrcSignature)) {
                        lastSrcSignature = sig;
                        rebuildSourceButtons(srcTable, search.getText().trim());
                    }
                    // 选中态实时同步：清除绑定/外部 configure 变更不触发重建，这里让按钮高亮始终
                    // 跟随 selectedSource（未绑定=全部不亮，杜绝"未绑定却残留选中黄框"）
                    if (srcBtnGroup != null) {
                        for (arc.scene.ui.TextButton b : srcBtnGroup.getButtons()) {
                            b.setChecked(selectedSource != null && selectedSource.contentEquals(b.getText()));
                        }
                    }
                });
                // 初始填充全部信号源
                rebuildSourceButtons(srcTable, "");
            }).pad(4f);
        }

        /** 本中继器在指定世界坐标处的原始信号强度（0~99，激活时；干扰由 SignalChannel 统一计算） */
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

        // 悬停信息面板不再追加自定义行：只保留原版 bar（生命/电力）。
        // 绑定编号与实时转发状态仍在配置面板里显示（statusText），悬停面板保持与原版方块一致。

        /** 存档版本：2 = bool(active) + i(channel) + str(selectedSource)；覆写 version() 使读档时绑定/信道不丢失 */
        @Override
        public byte version() {
            return 2;
        }

        /** 存档/网络同步 active 字段（host 上由 updateActive 重算，保证一致性） */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(active);
            write.i(channel);
            write.str(selectedSource == null ? "" : selectedSource);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            active = read.bool();
            if (revision >= 1) {
                channel = read.i();
            }
            if (revision >= 2) {
                String s = read.str();
                selectedSource = s.isEmpty() ? null : s;
            }
        }
    }
}
