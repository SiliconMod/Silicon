package silicon.world.blocks.satellite;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalSource;

/**
 * 卫星控制台（3×3）：卫星的发射与在轨管理终端。
 * 不存储燃料与电力——燃料（石油）与缓冲电力（10000）均由卫星发射中枢提供；
 * 卫星种类由卫星发射中枢选择。点击方块弹出界面，分两个页签：
 * 「发射」选信号与轨道后发射；「在轨管理」列出本队名册（编码/轨道/信道/门控与覆盖/进度，可定位）。
 * 轨道影响中枢燃油需求（LEO 1000 / MEO 2500 / GEO 5000 / SSO 8000）；
 * 信号卫星仅可发射到 LEO/MEO/GEO；SSO 供信号卫星以外的卫星类型使用（轨道划分不同卫星的功能）。
 */
public class SatelliteConsole extends Block {
    /** 卫星种类：信号卫星（与发射中枢保持一致） */
    public static final int TYPE_SIGNAL = 0;

    // —— 发射轨道 ——
    public static final int ORBIT_LEO = 0, ORBIT_MEO = 1, ORBIT_GEO = 2, ORBIT_SSO = 3;
    public static final int ORBIT_COUNT = 4;
    /** 各轨道所需石油（单位）：LEO 1000 / MEO 2500 / GEO 5000 / SSO 8000 */
    public static final int[] ORBIT_FUEL = {1000, 2500, 5000, 8000};
    /** 最大轨道需求（中枢储油上限按此设计） */
    public static final int ORBIT_MAX_FUEL = 8000;

    /**
     * 单台控制台在存档里代存的名册条数上限（写侧截断、读侧解析后丢弃超限项）。
     * 读写两侧必须用同一常量，且**读侧实际消费的字节数必须等于写侧写入的字节数**——
     * 详见 {@code SatelliteConsoleBuild.read()} 里关于 chunk 流对齐的说明。
     */
    public static final int ROSTER_MAX = 64;
    private static final String[] ORBIT_KEYS = {
            "block.silicon-satellite-console.orbit.leo",
            "block.silicon-satellite-console.orbit.meo",
            "block.silicon-satellite-console.orbit.geo",
            "block.silicon-satellite-console.orbit.sso"
    };

    /** 轨道燃油需求（越界 clamp 到 LEO） */
    public static int fuelFor(int orbit) {
        return ORBIT_FUEL[Math.max(0, Math.min(ORBIT_COUNT - 1, orbit))];
    }

    /** 轨道显示名（低地球轨道 (LEO) 等，bundle） */
    public static String orbitName(int orbit) {
        return Core.bundle.get(ORBIT_KEYS[Math.max(0, Math.min(ORBIT_COUNT - 1, orbit))]);
    }

    /** 卫星种类 × 轨道允许性：信号卫星限 LEO/MEO/GEO（SSO 不对信号卫星开放，供其他卫星类型使用） */
    public static boolean orbitAllowed(int type, int orbit) {
        return !(type == TYPE_SIGNAL && orbit == ORBIT_SSO);
    }

    /** 耗电（/秒，Mindustry 按 /60 tick 计）：100 电力/秒 */
    public static final float POWER_CONSUMPTION = 100f / 60f;

    public SatelliteConsole(String name) {
        super(name);
        buildType = SatelliteConsoleBuild::new;
        size = 3;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        // 需要供电：100 电力/秒（选中面板显示原版电力条）
        consumePower(POWER_CONSUMPTION);
        // 卫星所属信号走原版 configure 机制同步（服务器 tileConfig 权威下发，各端 selectedSignal 一致）；
        // 编码格式统一走 Signal.isValidCode（与信号源/中继器同一校验，畸形串不进缓存与 UI）
        config(String.class, (SatelliteConsoleBuild b, String value) -> {
            if (value == null || value.isEmpty()) {
                b.selectedSignal = null;
            } else if (silicon.world.meta.Signal.isValidCode(value)) {
                b.selectedSignal = value;
            }
        });
        // 发射轨道同步
        config(Integer.class, (SatelliteConsoleBuild b, Integer v) ->
                b.selectedOrbit = Math.max(ORBIT_LEO, Math.min(ORBIT_SSO, v == null ? ORBIT_LEO : v)));
    }

    public class SatelliteConsoleBuild extends Building {
        /** 卫星所属信号编码（4 位；null=无归属，全图信号保持蓝色） */
        public String selectedSignal = null;
        /** 发射轨道（默认 LEO） */
        public int selectedOrbit = ORBIT_LEO;
        /** 上次渲染的信号源列表签名（窗口实时刷新用） */
        private String lastSrcSignature = "";
        /** 窗口绑定状态缓存刷新节流（tick） */
        /** 打开界面期间绑定信息（中枢/控制台 1:1 配对）的刷新间隔：一行 30 tick ≈ 0.5s。
     *  原先 8 tick 会在每次刷新都跑一遍「遍历全部建筑 × 逐建筑跑信源/中继扫描」，大图下开销明显；
     *  绑定关系来自玩家操作，0.5s 的滞后完全看不出来。 */
    private static final int UI_REFRESH = 30;
        private int uiTick = 0;
        /** 绑定状态缓存：信号范围内唯一中枢（多台/未绑定时为 null） */
        private SatelliteLauncher.SatelliteLauncherBuild boundHub = null;
        private int hubCount = 0;
        private int consoleCount = 0;
        private boolean consoleInRange = false;
        /** SSO 轨道按钮（动态灰化用） */
        private TextButton ssoBtn = null;
        /** 上次收到 sat-launch 请求的时间（tick；速率限制用，不落存档） */
        public float lastLaunchRequest = Float.NEGATIVE_INFINITY;

        /**
         * 绑定信号自动失效：绑定的编码在本队已无任何存活信号源（源被拆掉/被打掉）时自动清除绑定，
         * 免得控制台一直挂着已经不存在的信号。判定用「编码是否还存在」而不是「是否在范围内」——
         * 源仍在但断电/超距/被干扰时保留绑定，交由绑定状态行红字提示。
         * 只在权威端判定，客机跟随 tileConfig 下发（避免网络延迟造成的误清）。
         */
        @Override
        public void updateTile() {
            if (selectedSignal == null || selectedSignal.isEmpty()) return;
            if (Vars.net.active() && !SatelliteManager.isAuthority()) return;
            if (!SignalChannel.hasLiveSource(team, selectedSignal)) {
                selectedSignal = null;
                configure("");
            }
        }

        /** 刷新绑定状态缓存（节流调用） */
        void refreshBinding() {
            consoleInRange = selectedSignal != null && !selectedSignal.isEmpty()
                    && SignalChannel.inSignalRange(team, selectedSignal, x, y);
            Seq<SatelliteLauncher.SatelliteLauncherBuild> hubs = SatelliteManager.hubsInSignal(team, selectedSignal);
            hubCount = hubs.size;
            boundHub = hubCount == 1 ? hubs.first() : null;
            consoleCount = SatelliteManager.consolesInSignal(team, selectedSignal);
            if (ssoBtn != null) ssoBtn.setDisabled(ssoBlocked());
        }

        /** 绑定状态错误键（null=绑定正常可发射） */
        String bindingKey() {
            if (!consoleInRange || hubCount == 0) return "block.silicon-satellite-console.nohub";
            if (hubCount > 1) return "block.silicon-satellite-console.multihub";
            if (consoleCount > 1) return "block.silicon-satellite-console.multiconsole";
            return null;
        }

        /** SSO 是否灰化：绑定的中枢选择了信号卫星（信号卫星不能发 SSO，选中即灰化，与生产进度无关） */
        boolean ssoBlocked() {
            return boundHub != null && boundHub.selectedType == TYPE_SIGNAL;
        }

        /** 发射卫星：本队可点发射。权威端（主机/单机）直接执行；纯客机发请求由主机执行并广播/反馈结果 */
        public void launch() {
            // 关闭（enabled=false，逻辑门/开关控制）：不能发射
            if (!enabled) {
                Vars.ui.showInfoToast(Core.bundle.get("block.silicon-satellite-console.disabled"), 3f);
                return;
            }
            // 纯客机（联网但非主机）：发射请求交给主机（sat-launch），主机校验后执行并广播状态、反馈失败原因
            if (Vars.net.active() && !SatelliteManager.isAuthority()) {
                Call.serverPacketReliable("sat-launch", tileX() + "," + tileY() + "|"
                        + (selectedSignal == null ? "" : selectedSignal) + "|" + selectedOrbit);
                return;
            }
            // 权威端：本地执行（建筑逻辑与卫星状态均在主机/单机计算）
            doLaunch(selectedSignal, selectedOrbit);
        }

        /** 权威端发射执行 + 结果提示（仅控制台本地直发路径；主机的 sat-launch 包处理器走
         *  SatelliteManager.launch 并自行回发 sat-result，不经过这里） */
        public void doLaunch(String signalName, int orbit) {
            int result = SatelliteManager.launch(team, signalName, orbit, x, y);
            String key;
            switch (result) {
                case SatelliteManager.LAUNCH_OK: key = "block.silicon-satellite-console.success"; break;
                case SatelliteManager.LAUNCH_NO_READY: key = "block.silicon-satellite-console.noready"; break;
                case SatelliteManager.LAUNCH_NO_FUEL: key = "block.silicon-satellite-console.nofuel"; break;
                case SatelliteManager.LAUNCH_NO_POWER: key = "block.silicon-satellite-console.nopower"; break;
                case SatelliteManager.LAUNCH_ORBIT_FORBIDDEN: key = "block.silicon-satellite-console.orbitForbidden"; break;
                case SatelliteManager.LAUNCH_NO_HUB: key = "block.silicon-satellite-console.nohub"; break;
                case SatelliteManager.LAUNCH_MULTI_HUB: key = "block.silicon-satellite-console.multihub"; break;
                case SatelliteManager.LAUNCH_MULTI_CONSOLE: key = "block.silicon-satellite-console.multiconsole"; break;
                case SatelliteManager.LAUNCH_TEST_SANDBOX: key = "block.silicon-satellite-console.sandboxOnly"; break;
                default: key = "block.silicon-satellite-console.fail"; break;
            }
            if (result == SatelliteManager.LAUNCH_OK) {
                Vars.ui.showInfoToast(Core.bundle.format(key, SatelliteManager.launchedCount(team)), 3f);
            } else {
                Vars.ui.showInfoToast(Core.bundle.get(key), 3f);
            }
        }

        /** 选中时的小面板：仅一个"打开界面"按钮，点击后打开可拖动窗口 */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.button(Core.bundle.get("block.silicon-satellite-console.open"), Styles.defaultt, () -> {
                // 隐藏原版小面板（此时 showConfig 已完成，hide 动画正常生效），再打开可拖动窗口
                if (Vars.control != null && Vars.control.input != null) {
                    Vars.control.input.config.hideConfig();
                }
                openDialog();
            }).size(160f, 48f).pad(4f);
        }

        // —— 界面页签（发射 / 在轨管理，两个页面分开）——

        /** 当前是否停在「在轨管理」页（false = 发射页）。按方块持久化在实例上，重开窗口保持上次的页 */
        private boolean rosterTab = false;
        /** 当前内容容器（pane 提供的 Table），页签切换时对它重建 */
        private Table contentTable;
        /** 在轨列表行数节流检查（行数变化才重建整页，行内文本由 Prov 每帧求值） */
        private int rosterTick = 0;
        private int rosterRows = -1;

        /** 打开可拖动窗口 */
        void openDialog() {
            BaseDialog dialog = new BaseDialog(Core.bundle.get("block.silicon-satellite-console.title"));
            // 可拖动式窗口（原版对话框默认可拖标题栏移动）；不铺满全屏
            dialog.setFillParent(false);
            dialog.setMovable(true);
            // 尺寸按屏幕比例动态计算（大屏封顶 660×580，小屏按比例缩小；内容增加轨道区后调高上限）
            float w = Math.min(660f, Core.graphics.getWidth() * 0.6f);
            float h = Math.min(580f, Core.graphics.getHeight() * 0.82f);
            // 顶部页签：发射 / 在轨管理（两个页面分开，共用下面的内容容器）
            dialog.cont.table(tabs -> {
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                group.setMinCheckCount(0);
                TextButton launchBtn = new TextButton(
                        Core.bundle.get("block.silicon-satellite-console.tab.launch"), Styles.flatTogglet);
                TextButton rosterBtn = new TextButton(
                        Core.bundle.get("block.silicon-satellite-console.tab.roster"), Styles.flatTogglet);
                launchBtn.setChecked(!rosterTab);
                rosterBtn.setChecked(rosterTab);
                launchBtn.clicked(() -> switchTab(false, dialog));
                rosterBtn.clicked(() -> switchTab(true, dialog));
                group.add(launchBtn);
                group.add(rosterBtn);
                tabs.add(launchBtn).size(150f, 40f).pad(2f);
                tabs.add(rosterBtn).size(150f, 40f).pad(2f);
            }).padBottom(6f).row();
            dialog.cont.pane(content -> {
                contentTable = content;
                rebuildTab(content, dialog);
            }).width(w).height(h).pad(10f);
            dialog.buttons.button(Core.bundle.get("block.silicon-satellite-console.close"), Styles.defaultt, dialog::hide)
                    .size(120f, 40f).padTop(6f);
            dialog.show();
        }

        /** 切页：只重建内容容器（页签按钮的选中态由 ButtonGroup 自己维护） */
        void switchTab(boolean roster, BaseDialog dialog) {
            if (rosterTab == roster) return;
            rosterTab = roster;
            rosterRows = -1; // 强制下一次进入在轨页时重建
            if (contentTable != null) rebuildTab(contentTable, dialog);
        }

        /** 按当前页签重建内容 */
        void rebuildTab(Table content, BaseDialog dialog) {
            content.clearChildren();
            content.top();
            if (rosterTab) {
                rebuildRoster(content, dialog);
            } else {
                rebuildFull(content, dialog);
            }
        }

        /**
         * 在轨管理页：本队名册概览 + 逐星状态（编码 / 轨道 / 信道 / 门控与覆盖 / 进度 / 定位）。
         * <p>行内文本一律走 {@code label(Prov)} 每帧求值，只有**行数变化**（新发射 / 被击落 / 读档换图）
         * 才重建整页——卫星的位置与覆盖每帧都在变，全量重建会有明显开销。
         */
        void rebuildRoster(Table table, BaseDialog dialog) {
            table.clearChildren();
            table.top();
            // 概览：在轨总数 + 待发射（按轨道分组计数在第二行）
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.roster.summary",
                    SatelliteManager.launchedCount(team), SatelliteManager.readyCount(team)))
                    .color(Color.lightGray).pad(2f).row();
            table.label(() -> {
                int[] n = new int[ORBIT_COUNT];
                for (SatelliteManager.SatelliteRecord r : SatelliteManager.satellites(team)) {
                    if (r.orbit >= 0 && r.orbit < ORBIT_COUNT) n[r.orbit]++;
                }
                return Core.bundle.format("block.silicon-satellite-console.roster.byOrbit",
                        n[ORBIT_LEO], n[ORBIT_MEO], n[ORBIT_GEO], n[ORBIT_SSO]);
            }).color(Color.lightGray).pad(2f).row();

            arc.struct.Seq<SatelliteManager.SatelliteRecord> list = SatelliteManager.satellites(team);
            if (list.isEmpty()) {
                table.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.empty"))
                        .color(Color.lightGray).pad(12f).row();
                return;
            }
            // 表头：与数据行**都显式左对齐**——arc 的 Table 默认把子表居中，表头与数据行总宽不同就会整列错位
            //（上一版就是这样：数据行末尾多一个按钮 → 表头整体右移 → "信道"那列下面是空的）
            table.row();
            Table head = new Table();
            head.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.code")).width(64f).left();
            head.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.type")).width(72f);
            head.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.orbit")).width(48f);
            head.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.channel")).width(40f);
            head.label(() -> Core.bundle.get("block.silicon-satellite-console.roster.health")).width(150f).left();
            table.add(head).left().pad(2f).row();
            for (SatelliteManager.SatelliteRecord r : list) {
                addRosterRow(table, r);
            }
            // 行数变化（发射 / 击落 / 读档）时重建整页；否则只让上面的 Prov 自己刷新
            rosterRows = list.size;
            table.update(() -> {
                if (!rosterTab || contentTable == null) return;
                if (++rosterTick < 15) return;
                rosterTick = 0;
                if (SatelliteManager.satellites(team).size != rosterRows) {
                    rebuildTab(contentTable, dialog);
                }
            });
        }

        /** 在轨列表的一行：列宽必须与表头逐列一致，且同样左对齐 */
        void addRosterRow(Table table, SatelliteManager.SatelliteRecord r) {
            table.row();
            Table row = new Table();
            row.label(() -> r.code == null
                            ? Core.bundle.get("block.silicon-satellite-console.nobind") : r.code)
                    .color(r.code == null ? Color.lightGray : Color.white).width(64f).left();
            row.label(() -> r.type == SatelliteLauncher.TYPE_TEST
                            ? Core.bundle.get("block.silicon-satellite-console.type.short.test")
                            : Core.bundle.get("block.silicon-satellite-console.type.short.signal"))
                    .color(Color.lightGray).width(72f);
            row.label(() -> orbitKeyShort(r.orbit)).width(48f);
            row.label(() -> r.channel >= 1 ? String.valueOf(r.channel) : "-").width(40f);
            // 血量：直接读实体，每帧求值 → 掉血立刻可见。实体不在名册里说明丢失；
            // 编码的地面源全没了则在血量后加「静默」后缀（卫星还在轨，但不广播了）
            row.label(() -> {
                mindustry.gen.Unit u = Groups.unit.getByID(r.unitId);
                if (u == null) return Core.bundle.get("block.silicon-satellite-console.roster.state.missing");
                String hp = (int) u.health + "/" + (int) u.maxHealth;
                return (r.code != null && !SignalChannel.hasLiveSource(team, r.code))
                        ? hp + " " + Core.bundle.get("block.silicon-satellite-console.roster.state.muted")
                        : hp;
            }).color(Color.lightGray).width(150f).left();
            table.add(row).left().pad(2f).row();
        }

        /** 卫星种类短名（信号卫星 / 测试卫星，bundle） */
        String typeShortName(int type) {
            return type == TYPE_SIGNAL
                    ? Core.bundle.get("block.silicon-satellite-console.type.short.signal")
                    : Core.bundle.get("block.silicon-satellite-console.type.short.test");
        }

        /** 名称行：绑定的中枢所准备发射/制造中的卫星（动态；未绑定或异常时显示 —） */
        void addSatelliteNameRow(Table table) {
            table.label(() -> {
                String r = Core.bundle.get("block.silicon-satellite-console.name.none");
                String m = Core.bundle.get("block.silicon-satellite-console.name.none");
                if (boundHub != null) {
                    if (boundHub.produced) r = typeShortName(boundHub.selectedType);
                    if (!boundHub.produced && boundHub.progress > 0f) m = typeShortName(boundHub.selectedType);
                }
                return Core.bundle.format("block.silicon-satellite-console.name.line", r, m);
            }).color(Color.lightGray).pad(2f);
        }

        /** 轨道选择按钮行（4 单选）；绑定的中枢选择信号卫星时 SSO 即灰化（信号卫星不能发 SSO） */
        void rebuildOrbitRow(Table table) {
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.orbit.current", orbitName(selectedOrbit)))
                    .color(arc.graphics.Color.lightGray).padTop(6f);
            table.row();
            table.label(() -> Core.bundle.get("block.silicon-satellite-console.orbit.title")).color(Color.lightGray).pad(2f);
            table.row();
            Table row = new Table();
            ButtonGroup<TextButton> group = new ButtonGroup<>();
            group.setMinCheckCount(0); // 允许全不选（默认 1 会在 add() 时强制勾选第一个按钮，且无法取消）
            ssoBtn = null;
            for (int o = ORBIT_LEO; o < ORBIT_COUNT; o++) {
                final int orbit = o;
                TextButton btn = new TextButton(orbitKeyShort(orbit), Styles.flatTogglet);
                btn.setChecked(selectedOrbit == orbit);
                btn.clicked(() -> {
                    selectedOrbit = orbit;
                    configure(orbit);
                });
                group.add(btn);
                if (orbit == ORBIT_SSO) ssoBtn = btn;
                row.add(btn).size(110f, 40f).pad(2f);
            }
            table.add(row).pad(2f);
        }

        /** 轨道按钮短标签（LEO 等） */
        static String orbitKeyShort(int orbit) {
            switch (orbit) {
                case ORBIT_LEO: return "LEO";
                case ORBIT_MEO: return "MEO";
                case ORBIT_GEO: return "GEO";
                default: return "SSO";
            }
        }

        /** 窗口内容：卫星名称 + 状态 + 当前信号 + 信号选择 + 轨道选择 + 绑定状态 + 发射 */
        void rebuildFull(Table table, BaseDialog dialog) {
            table.clearChildren();
            table.top();
            // 卫星名称行（绑定的中枢准备发射/制造中）——动态
            addSatelliteNameRow(table);
            table.row();
            // 状态（动态刷新）
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.status.ready",
                    SatelliteManager.readyCount(team))).color(Color.lightGray).pad(2f);
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.status.orbit",
                    SatelliteManager.launchedCount(team))).color(Color.lightGray).pad(2f);
            table.row();
            // 当前卫星所属信号（与中继器"当前编号"风格一致）
            table.label(() -> Core.bundle.format("block.silicon-satellite-console.signal.current",
                    selectedSignal == null || selectedSignal.isEmpty()
                            ? Core.bundle.get("block.silicon-satellite-console.nobind") : selectedSignal))
                    .pad(2f);
            table.row();
            // 信号选择区（参考信号中继器：搜索框模糊过滤 + 滚轮按钮网格 + 清除）
            Table srcTable = new Table();
            TextField search = table.field("", text -> rebuildSourceButtons(srcTable, text.trim()))
                    .width(280f).padTop(2f).get();
            search.setMessageText(Core.bundle.get("block.silicon-satellite-console.signal.search"));
            search.setMaxLength(4);
            table.row();
            ScrollPane pane = new ScrollPane(srcTable, Styles.noBarPane);
            pane.setScrollingDisabled(true, false); // 禁水平滚动，垂直滚轮翻页
            table.add(pane).height(130f).growX().padTop(2f);
            table.row();
            // 清除按钮
            table.button(Core.bundle.get("block.silicon-satellite-console.signal.clear"), Styles.defaultt, () -> {
                selectedSignal = null;
                configure("");
                rebuildSourceButtons(srcTable, search.getText().trim());
            }).size(88f, 40f).padTop(2f);
            // 轨道区
            rebuildOrbitRow(table);
            table.row();
            // 绑定状态行（未绑定/存在多个…时红字提示；正常显示已绑定）
            table.label(() -> {
                String k = bindingKey();
                String t = k == null ? Core.bundle.get("block.silicon-satellite-console.bound")
                        : Core.bundle.get(k);
                return k == null ? t : "[scarlet]" + t + "[]";
            }).pad(2f);
            table.row();
            // 发射按钮（状态/名称为动态 label，发射后自动刷新，无需重建窗口）
            table.button(Core.bundle.get("block.silicon-satellite-console.launch"), Styles.defaultt, this::launch)
                    .size(280f, 56f).padTop(8f);
            // 实时刷新：绑定状态缓存（节流）+ 信号源列表变化时重建按钮区（保持搜索过滤）
            lastSrcSignature = "";
            uiTick = 0;
            pane.update(() -> {
                if (++uiTick >= UI_REFRESH) {
                    uiTick = 0;
                    refreshBinding();
                }
                String sig = sourceSignature();
                if (!sig.equals(lastSrcSignature)) {
                    lastSrcSignature = sig;
                    rebuildSourceButtons(srcTable, search.getText().trim());
                }
            });
            refreshBinding();
            // 初始填充全部信号源
            rebuildSourceButtons(srcTable, "");
        }

        /** 模糊匹配：query 的字符按顺序出现在 code 中（子序列匹配，忽略大小写）；空 query 匹配一切（与中继器一致） */
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
            ButtonGroup<TextButton> group = new ButtonGroup<>();
            group.setMinCheckCount(0); // 允许未选择信号（默认 1 会在 add() 时强制勾选第一个按钮，清除后依然残留）
            int perRow = 5, count = 0;
            for (SignalSource.SignalSourceBuild sb : srcs) {
                String code = sb.signal == null ? "----" : sb.signal.name;
                if (!filter.isEmpty() && !fuzzyMatch(code, filter)) continue;
                any = true;
                TextButton btn = new TextButton(code, Styles.flatTogglet);
                btn.setChecked(code.equals(selectedSignal));
                // configure 走网络同步（服务器权威下发，各端一致）；乐观先设本地并刷新按钮选中态
                btn.clicked(() -> {
                    selectedSignal = code;
                    configure(code);
                    rebuildSourceButtons(srcTable, filter);
                });
                group.add(btn);
                srcTable.add(btn).size(88f, 40f).pad(1f);
                if (++count % perRow == 0) srcTable.row();
            }
            if (!any) {
                srcTable.add(Core.bundle.get("block.silicon-satellite-console.signal.none"))
                        .color(Color.lightGray).pad(2f);
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

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(selectedSignal == null ? "" : selectedSignal);
            write.i(selectedOrbit);
            // v2:追加本队卫星名册快照。卫星实体的编码/信道/相位无处随单位持久化（无自定义实体组件），
            // 由控制台代存——所有控制台写同一份全局快照，读侧按 unitId 去重并集，任一存活控制台即可恢复。
            // 相位在保存时推进到当前时刻（扫描进度 u，GEO 为定点方位角）：读档后 Time.time 归零，轨迹位置以存档进度续接，卫星不跳位
            // v3:每条追加 type（信号卫星/测试卫星）——类型与轨道正交，光靠 orbit 推不出来
            arc.struct.Seq<SatelliteManager.SatelliteRecord> list = SatelliteManager.satellites(team);
            // 条目数上限：与读侧的 ROSTER_MAX 必须是同一个常量（读侧还额外保证消费全部条目，见 read()）。
            int n = Math.min(list.size, ROSTER_MAX);
            write.i(n);
            for (int i = 0; i < n; i++) {
                SatelliteManager.SatelliteRecord r = list.get(i);
                write.i(r.unitId);
                write.i(r.channel);
                write.i(r.orbit);
                write.str(r.code == null ? "" : r.code);
                write.i(r.type);
                write.i(Float.floatToIntBits(SatelliteManager.phaseForSave(r)));
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            String s = read.str();
            selectedSignal = s.isEmpty() ? null : s;
            if (revision >= 1) {
                selectedOrbit = Math.max(ORBIT_LEO, Math.min(ORBIT_SSO, read.i()));
            }
            if (revision >= 2) {
                int n = Math.max(read.i(), 0);
                // 读侧必须把写入的 n 条**全部消费**，哪怕超过 ROSTER_MAX：
                // 引擎的 SaveFileReader.readChunk 是 `int len = readInt(); runner.accept(input, len); return len;`
                // ——读完 runner 直接返回，不做任何「按声明长度补齐」的对齐；每个 tile 的 chunk 长度前缀
                // 靠写读两侧字节数严格相等才能对齐。若这里只读 64 条就把剩余条目留在流里，后续所有 tile
                // 的 chunk 前缀都会被当数据读，整档解析错位（读档失败/存档损坏）。
                // 因此超限的条目**解析但不恢复**：流位置与写侧一致，名册最多恢复 ROSTER_MAX 条。
                int restored = 0;
                for (int i = 0; i < n; i++) {
                    int unitId = read.i();
                    int channel = read.i();
                    int orbit = read.i();
                    String code = read.str();
                    // v3 起在该位置追加 type；旧档（revision < 3）没有这个字段，按信号卫星处理。
                    // 顺序必须与 write 严格一致：unitId → channel → orbit → code → **type** → phase
                    int type = (revision >= 3) ? read.i() : TYPE_SIGNAL;
                    float phase = Float.intBitsToFloat(read.i());
                    if (restored >= ROSTER_MAX) continue;
                    restored++;
                    SatelliteManager.restoreRecord(team, unitId, channel, orbit, code, phase, type);
                }
            }
        }

        @Override
        public byte version() {
            return 3;
        }
    }
}
