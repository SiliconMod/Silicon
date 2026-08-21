package silicon.world.blocks.distribution;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.scene.style.TextureRegionDrawable;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.BufferItem;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Teamc;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.DirectionalItemBuffer;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.content;
import static mindustry.Vars.ui;

/**
 * 万向交叉器 (Universal Junction)
 * <p>
 * 1x1 物品交叉器：可为四个输入方向分别配置各输出方向的优先级 (0~4)，
 * 数值越大越优先输出，0 表示不输出；同优先级的方向轮流输出实现均分。
 * 方向满载时短暂等待重试，连续堵塞才降级到次高优先级，且降级后持续生效、
 * 恢复时自动切回。传输速率 50 物品/秒。
 * <p>
 * 方向约定：UI 中 0=上(北) 1=右(东) 2=下(南) 3=左(西)。
 * 注意游戏内 relativeTo()/Building.nearby() 使用角度编码 (0=东 1=北 2=西 3=南)，
 * 因此在收/发物品时分别做 3-rel 与 out^1 转换。
 * <p>
 * 交互：点击方块弹出"配置方向"按钮，点击按钮打开配置界面，
 * 选择输入方向后为四个输出方向分别拖动优先级滑块。
 */
public class UniversalJunction extends Block {
    /** 移动一个物品所需的 tick 数：60 / 1.2 = 50 物品/秒 */
    public float moveTime = 1.2f;
    /** 每个方向的缓冲容量 */
    public int capacity = 16;

    /** 方向图标（标准方位：0=上 1=右 2=下 3=左） */
    public static final TextureRegionDrawable[] dirIcons = {Icon.up, Icon.right, Icon.down, Icon.left};

    /** 方向名称（从 bundle 读取，支持多语言：universaljunction.dir0~3） */
    public static String dirName(int dir) {
        return Core.bundle.get("universaljunction.dir" + dir);
    }

    /** 角度编码（0=东 1=北 2=西 3=南，relativeTo 返回值）→ 标准方位（物品来源方向） */
    static int angleToSource(int angle) {
        return 3 - angle;
    }

    /** 标准方位 → 角度编码（Building.nearby 参数） */
    static int cardinalToAngle(int dir) {
        return dir ^ 1;
    }

    public UniversalJunction(String name) {
        super(name);
        update = true;
        solid = false;
        underBullets = true;
        group = BlockGroup.transportation;
        unloadable = false;
        floating = true;
        noUpdateDisabled = true;
        hasItems = false;
        size = 1;
        timers = 1;
        configurable = true;
        saveConfig = true;
        copyConfig = true;
        drawArrow = false;

        // 优先级通过 String 配置值同步（16 个逗号分隔的整数，[输入][输出] 顺序）
        config(String.class, (UniversalJunctionBuild b, String str) -> b.applyConfig(str));
    }

    @Override
    public boolean outputsItems() {
        return true;
    }

    @Override
    public void load() {
        super.load();
        // 自带贴图：assets/sprites/blocks/distribution/universal-junction.png
        // （四向箭头 + 中心青色智能中枢；TODO: 后续补充物品流向动画）
        region = Core.atlas.find(name);
        generatedIcons = new TextureRegion[]{region};
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.itemsMoved, 60f / moveTime, StatUnit.itemsSecond);
    }

    public class UniversalJunctionBuild extends Building {
        /** 优先级矩阵 [输入方向][输出方向]，0~4；0 = 不输出 */
        public final int[][] weights = new int[4][4];
        /** 全局默认输出优先级（应用到所有未单独覆盖的输入方向）；通过配置 String 尾部 4 值同步 */
        public int[] defaultRow = {2, 2, 2, 2};
        /** 四方向物品缓冲（下标为标准方位：0=上 1=右 2=下 3=左） */
        public final DirectionalItemBuffer buffer = new DirectionalItemBuffer(capacity);
        /** 各输入方向的轮询指针（同优先级方向轮流输出，实现均分） */
        public final int[] roundRobin = new int[4];
        /** 各输入方向的连续满载计数（用于延迟降级，避免高吞吐下传送带抖动） */
        public final int[] blockCount = new int[4];
        /** 各输入方向当前生效的最高优先级（降级时降低，探测到更高组恢复时回升） */
        public final int[] activePriority = new int[4];
        /** 连续满载多少 tick 后才降级到次高优先级 */
        public static final int blockThreshold = 10;
        /** 输入方向轮询指针（各输入方向轮流服务，避免高压方向饿死其他方向） */
        public int inputRobin;
        /** 配置节流间隔（秒）：拖动滑块期间合并多次改动为一次网络发送 */
        public float configInterval = 0.25f;
        /** 上次发送配置的时间 */
        public float lastConfigTime;
        /** 是否有待发送的配置改动 */
        public boolean configDirty;

        private static final int timerMove = 0;

        /** 默认所有方向优先级 2 */
        {
            setAll(2);
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel == -1) return false;
            int srcDir = angleToSource(rel);
            // 该输入方向的所有输出优先级均为 0（完全禁用）：拒绝接收，物品留在来源处
            for (int d = 0; d < 4; d++) {
                if (weights[srcDir][d] > 0) return buffer.accepts(srcDir);
            }
            return false;
        }

        @Override
        public int acceptStack(Item item, int amount, Teamc source) {
            return 0; // 不接收整叠物品，与原版 Junction 一致
        }

        @Override
        public void handleItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel != -1) {
                buffer.accept(angleToSource(rel), item);
            }
        }

        @Override
        public void updateTile() {
            if (timer(timerMove, moveTime)) {
                moveItem();
            }
            // 兜底补发：滑块松手后若还有未发送的配置改动，在窗口结束后发送
            if (configDirty && Time.time >= lastConfigTime + configInterval) {
                flushConfig();
            }
        }

        /** 每 moveTime 从输入方向轮流服务一个物品 */
        void moveItem() {
            int input = pickInput();
            if (input == -1) return;

            // 防止缓冲区索引溢出
            if (buffer.indexes[input] > capacity) buffer.indexes[input] = capacity;

            long l = buffer.buffers[input][0];
            Item item = content.item(BufferItem.item(l));
            if (item == null) return;

            int out = pickOutput(input, item);
            if (out == -1) return; // 所有输出均阻塞，物品留在缓冲中等待

            Building dest = nearby(cardinalToAngle(out));
            // 防御：pickOutput 已校验 dest 非空，此处仅防未来逻辑变更导致的 NPE
            if (dest == null) return;
            dest.handleItem(this, item);
            System.arraycopy(buffer.buffers[input], 1, buffer.buffers[input], 0, buffer.indexes[input] - 1);
            buffer.indexes[input]--;
        }

        /** 选择要服务的输入方向：从轮询指针开始找第一个有物品的方向（轮流服务，公平分配） */
        int pickInput() {
            for (int n = 0; n < 4; n++) {
                int i = (inputRobin + n) % 4;
                if (buffer.indexes[i] > 0) {
                    inputRobin = (i + 1) % 4;
                    return i;
                }
            }
            return -1;
        }

        /**
         * 按优先级选择输出方向，规则：
         * - 优先尝试当前生效的最高优先级组，同组方向轮流输出实现均分；
         * - 组内方向不可用（无建筑/异队）立即降级到次高优先级；
         * - 组内方向存在但暂时满载（如传送带逐 tick 移动）先等待重试，
         *   连续满载超过阈值才降级，避免高吞吐下物品在最高与次高方向之间抖动；
         * - 降级是持久的（activePriority 记录），但每次调用会探测更高组是否恢复，
         *   恢复即可切回，防止永久堵塞时次高方向吞吐崩坏。
         */
        int pickOutput(int input, Item item) {
            int cfgBest = 0;
            for (int d = 0; d < 4; d++) cfgBest = Math.max(cfgBest, weights[input][d]);
            if (cfgBest <= 0) return -1; // 该输入方向未配置任何输出

            // 初始或全部降级归零时，重置为配置的最高优先级，重新走降级流程
            if (activePriority[input] <= 0) activePriority[input] = cfgBest;

            // 探测：更高优先级组是否已恢复可接收 → 逐级切回（循环直至最高可接收组）
            while (activePriority[input] < cfgBest) {
                int higher = nextHigher(input, activePriority[input]);
                if (higher <= 0 || !groupUsable(input, higher, item)) break;
                activePriority[input] = higher;
            }

            int p = activePriority[input];
            if (p <= 0) return -1; // 所有优先级组均不可用，等待下个 tick 重试

            boolean anyDest = false; // 当前组内是否存在有效目标（哪怕暂时满载）
            for (int n = 0; n < 4; n++) {
                int d = (roundRobin[input] + n) % 4;
                if (weights[input][d] != p) continue;
                Building dest = nearby(cardinalToAngle(d));
                if (dest == null || dest.team != team) continue; // 不可用 → 组内下一个
                anyDest = true;
                if (dest.acceptItem(this, item)) {
                    blockCount[input] = 0; // 发送成功，清零阻塞计数
                    roundRobin[input] = (d + 1) % 4; // 推进轮询指针，下次从下一方向开始
                    return d;
                }
            }

            if (!anyDest) {
                // 组内全不可用（无建筑/异队）：立即持久降级
                blockCount[input] = 0;
                activePriority[input] = nextLower(input, p);
                return -1;
            }

            // 组内方向存在但全部暂时满载：等待重试，连续阻塞超过阈值才持久降级
            blockCount[input]++;
            if (blockCount[input] < blockThreshold) return -1;
            blockCount[input] = 0;
            activePriority[input] = nextLower(input, p);
            return -1;
        }

        /** 组内是否存在至少一个可接收该物品的方向 */
        boolean groupUsable(int input, int p, Item item) {
            for (int d = 0; d < 4; d++) {
                if (weights[input][d] != p) continue;
                Building dest = nearby(cardinalToAngle(d));
                if (dest != null && dest.team == team && dest.acceptItem(this, item)) return true;
            }
            return false;
        }

        /** 低于 p 的最高配置优先级；无则返回 0 */
        int nextLower(int input, int p) {
            int next = 0;
            for (int d = 0; d < 4; d++) {
                int w = weights[input][d];
                if (w < p && w > next) next = w;
            }
            return next;
        }

        /** 高于 p 的最低配置优先级；无则返回 0 */
        int nextHigher(int input, int p) {
            int next = 0;
            for (int d = 0; d < 4; d++) {
                int w = weights[input][d];
                if (w > p && (next == 0 || w < next)) next = w;
            }
            return next;
        }

        // ---------- 配置 ----------

        void setAll(int v) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = v;
            }
        }

        /** 仅设置指定输入方向的 4 个输出优先级（快捷按钮作用域：当前选中的输入方向） */
        void setAllFor(int in, int v) {
            for (int j = 0; j < 4; j++) weights[in][j] = v;
        }

        /** 该输入方向是否已单独配置（与全局默认行不同） */
        boolean isOverride(int in) {
            for (int j = 0; j < 4; j++) {
                if (weights[in][j] != defaultRow[j]) return true;
            }
            return false;
        }

        /** 恢复指定输入方向为全局默认（取消覆盖） */
        void resetToDefault(int in) {
            System.arraycopy(defaultRow, 0, weights[in], 0, 4);
        }

        // ---------- 冗余折叠判定（数值相同组折叠为文字，hover/tap 展开滑块） ----------

        /** 与 d 同值的最小方向序（组代表；上=0 最小） */
        int repOf(int[] data, int d) {
            int v = data[d];
            int best = d;
            for (int j = 0; j < 4; j++) {
                if (j < best && data[j] == v) best = j;
            }
            return best;
        }

        /** 该方向的值在 4 个输出中是否唯一 */
        boolean isUnique(int[] data, int d) {
            int v = data[d];
            for (int j = 0; j < 4; j++) {
                if (j != d && data[j] == v) return false;
            }
            return true;
        }

        /** 该方向是否为同值组的代表（组内最小方向序，显示滑块） */
        boolean isRepresentative(int[] data, int d) {
            return repOf(data, d) == d;
        }

        /** 折叠文字：0 组显示"禁用"，非 0 重复组显示"与{代表}平均输出" */
        String foldText(int[] data, int d) {
            int v = data[d];
            if (v == 0) return Core.bundle.get("universaljunction.disabled");
            return Core.bundle.format("universaljunction.evenWith", dirName(repOf(data, d)));
        }

        /**
         * 渲染一行输出配置：唯一值或同值组代表显示滑块；
         * 重复值折叠为文字（数值>0 显示"与X平均输出"，0 显示"禁用"），hover 或点击展开滑块。
         */
        void renderOutRow(Table row, int[] data, int out, boolean[] tapOpen, boolean[] hoverOpen, boolean[] sliderShown, java.util.function.IntConsumer onChanged) {
            row.clearChildren();
            boolean unique = isUnique(data, out);
            boolean rep = isRepresentative(data, out);
            boolean expanded = unique || rep || tapOpen[out] || hoverOpen[out];
            sliderShown[out] = expanded;
            if (expanded) {
                Label dirL = new Label(dirName(out) + " →");
                dirL.setColor(Color.white);
                dirL.clicked(() -> {
                    tapOpen[out] = !tapOpen[out]; // tap 方向标签可固定/取消固定
                    renderOutRow(row, data, out, tapOpen, hoverOpen, sliderShown, onChanged);
                });
                row.add(dirL).width(50f);
                Slider sl = new Slider(0f, 4f, 1f, false);
                sl.setValue(data[out]);
                Label val = new Label(String.valueOf((int) sl.getValue()));
                sl.changed(() -> {
                    int v = (int) sl.getValue();
                    data[out] = v;
                    val.setText(String.valueOf(v));
                    onChanged.accept(v);
                });
                row.add(sl).width(150f).padRight(6f);
                row.add(val).width(30f);
            } else {
                Label l = new Label("▾ " + foldText(data, out));
                l.setColor(Color.gray);
                l.clicked(() -> {
                    tapOpen[out] = !tapOpen[out];
                    renderOutRow(row, data, out, tapOpen, hoverOpen, sliderShown, onChanged);
                });
                row.add(l).left();
            }
        }

        /** 应用模板：设置全局默认行，并同步所有未覆盖的输入方向 */
        void applyTemplate(int[] row) {
            System.arraycopy(row, 0, defaultRow, 0, 4);
            for (int in = 0; in < 4; in++) {
                if (!isOverride(in)) {
                    System.arraycopy(row, 0, weights[in], 0, 4);
                }
            }
        }

        // ---------- 模板持久化（玩家全局偏好，存于游戏设置） ----------

        static final String templatesKey = "silicon-uj-templates";
        /** 内置模板：均分 / 全东 / 主东备西 / 南北直通（方向顺序 上右下左） */
        static final String[] builtinTemplateKeys = {"universaljunction.tpl.even", "universaljunction.tpl.east", "universaljunction.tpl.eastwest", "universaljunction.tpl.ns"};
        static final int[][] builtinTemplateRows = {{2, 2, 2, 2}, {0, 4, 0, 0}, {0, 4, 0, 2}, {4, 0, 4, 0}};

        /** 读取自定义模板表（LinkedHashMap：名称 → 行） */
        static java.util.Map<String, int[]> loadTemplates() {
            java.util.Map<String, int[]> map = new java.util.LinkedHashMap<>();
            String raw = (String) Core.settings.get(templatesKey, "");
            if (raw.isEmpty()) return map;
            for (String part : raw.split(";")) {
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (kv.length != 2) continue;
                String[] vals = kv[1].split(",");
                if (vals.length != 4) continue;
                try {
                    int[] row = new int[4];
                    for (int i = 0; i < 4; i++) row[i] = Mathf.clamp(Integer.parseInt(vals[i].trim()), 0, 4);
                    map.put(kv[0], row);
                } catch (NumberFormatException ignored) {
                }
            }
            return map;
        }

        /** 保存自定义模板 */
        static void saveTemplate(String name, int[] row) {
            java.util.Map<String, int[]> map = loadTemplates();
            // 过滤非法字符（分隔符）
            String clean = name.replace(":", "").replace(";", "").replace(",", "").trim();
            if (clean.isEmpty()) return;
            map.put(clean, row.clone());
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, int[]> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append(':');
                for (int i = 0; i < 4; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(e.getValue()[i]);
                }
            }
            Core.settings.put(templatesKey, sb.toString());
        }

        /** 模板下拉选项：内置名 + 自定义名 */
        static String[] templateNames() {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (String key : builtinTemplateKeys) list.add(Core.bundle.get(key));
            list.addAll(loadTemplates().keySet());
            return list.toArray(new String[0]);
        }

        /** 按名称查模板行；内置优先 */
        static int[] findTemplate(String name) {
            for (int i = 0; i < builtinTemplateKeys.length; i++) {
                if (Core.bundle.get(builtinTemplateKeys[i]).equals(name)) return builtinTemplateRows[i];
            }
            return loadTemplates().get(name);
        }

        /** 将优先级序列化为 20 个逗号分隔的整数（16 矩阵 + 4 全局默认行） */
        public String weightsString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if (i > 0 || j > 0) sb.append(',');
                    sb.append(weights[i][j]);
                }
            }
            for (int j = 0; j < 4; j++) {
                sb.append(',').append(defaultRow[j]);
            }
            return sb.toString();
        }

        /** 标记配置已改动：窗口内合并发送，超过窗口立即发送 */
        void markConfigDirty() {
            configDirty = true;
            if (Time.time >= lastConfigTime + configInterval) {
                flushConfig();
            }
        }

        /** 立即发送当前配置（若确有未发送改动） */
        void flushConfig() {
            if (!configDirty) return;
            configDirty = false;
            lastConfigTime = Time.time;
            configure(weightsString());
        }

        /** 解析配置字符串，非法时忽略；兼容旧版 16 值（无全局默认行）格式 */
        public void applyConfig(String str) {
            if (str == null) return;
            String[] parts = str.split(",");
            if (parts.length != 16 && parts.length != 20) return;
            try {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        weights[i][j] = Mathf.clamp(Integer.parseInt(parts[i * 4 + j].trim()), 0, 4);
                    }
                }
                if (parts.length == 20) {
                    for (int j = 0; j < 4; j++) {
                        defaultRow[j] = Mathf.clamp(Integer.parseInt(parts[16 + j].trim()), 0, 4);
                    }
                } else {
                    defaultRow = weights[0].clone(); // 旧格式：取第一行作全局默认
                }
            } catch (NumberFormatException e) {
                return; // 非法配置，忽略
            }
            // 配置变更后重置路由瞬态状态，避免沿用旧配置的降级/轮询状态
            for (int i = 0; i < 4; i++) {
                activePriority[i] = 0;
                blockCount[i] = 0;
                roundRobin[i] = 0;
            }
        }

        @Override
        public String config() {
            return weightsString();
        }

        // ---------- 配置界面 ----------

        /** 配置面板：模板一键应用 + 全局输出优先级 + 按方向覆盖（折叠高级层） */
        @Override
        public void buildConfiguration(Table table) {
            table.background(Styles.black6);
            table.margin(10f);

            final int[] selDir = {0};
            final boolean[] expanded = {false};
            Table globalTable = new Table();
            Table overrideTable = new Table();
            Table templatesTable = new Table();

            // 重建函数存于数组，避免 lambda 循环引用（r0=全局 r1=覆盖 r2=模板 r3=全量）
            final Runnable[] r = new Runnable[4];

            // 重建模板按钮行
            r[2] = () -> {
                templatesTable.clearChildren();
                for (String name : templateNames()) {
                    templatesTable.button(b -> b.add(name), () -> {
                        int[] row = findTemplate(name);
                        if (row != null) {
                            applyTemplate(row);
                            r[3].run();
                            flushConfig();
                        }
                    }).size(64f, 36f).pad(2f);
                }
            };

            // 重建覆盖层（展开时显示方向选择 + 该方向滑块 + 快捷按钮；数值相同方向折叠为文字）
            r[1] = () -> {
                overrideTable.clearChildren();
                if (!expanded[0]) return;
                final boolean[] tapOpen = new boolean[4];
                final boolean[] hoverOpen = new boolean[4];
                final float[] lastExit = new float[4];
                final boolean[] sliderShown = new boolean[4];
                overrideTable.table(inputs -> {
                    for (int d = 0; d < 4; d++) {
                        final int dir = d;
                        Button btn = inputs.button(b -> {
                            b.image(dirIcons[dir]).padRight(4f);
                            b.add(dirName(dir));
                        }, () -> {
                            selDir[0] = dir;
                            r[1].run(); // 重置折叠状态并重建
                            table.invalidateHierarchy();
                        }).size(72f, 36f).pad(3f).get();
                        btn.update(() -> btn.setChecked(selDir[0] == dir));
                    }
                }).padBottom(6f).row();

                final int in = selDir[0];
                overrideTable.add(Core.bundle.format("universaljunction.from", dirName(in))).color(Pal.accent).padBottom(4f).row();
                if (isOverride(in)) {
                    overrideTable.add(Core.bundle.get("universaljunction.overrideHint")).color(Color.gray).padBottom(4f).row();
                }
                for (int d = 0; d < 4; d++) {
                    final int out = d;
                    Table row = new Table();
                    // 行容器 hover：临时展开滑块；移出：延迟收回（防抖）；tap：固定展开
                    row.hovered(() -> {
                        hoverOpen[out] = true;
                        renderOutRow(row, weights[in], out, tapOpen, hoverOpen, sliderShown, v -> {
                            weights[in][out] = v;
                            markConfigDirty();
                        });
                    });
                    row.exited(() -> {
                        hoverOpen[out] = false;
                        lastExit[out] = Time.time;
                    });
                    row.update(() -> {
                        if (sliderShown[out] && !hoverOpen[out] && !tapOpen[out] && Time.time - lastExit[out] > 0.2f) {
                            sliderShown[out] = false;
                            renderOutRow(row, weights[in], out, tapOpen, hoverOpen, sliderShown, v -> {
                                weights[in][out] = v;
                                markConfigDirty();
                            });
                        }
                    });
                    overrideTable.add(row).padBottom(2f).row();
                    renderOutRow(row, weights[in], out, tapOpen, hoverOpen, sliderShown, v -> {
                        weights[in][out] = v;
                        markConfigDirty();
                    });
                }
                overrideTable.table(quick -> {
                    quick.button(Core.bundle.get("universaljunction.even"), () -> {
                        setAllFor(selDir[0], 2);
                        r[1].run();
                        table.invalidateHierarchy();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                    quick.button(Core.bundle.get("universaljunction.clear"), () -> {
                        setAllFor(selDir[0], 0);
                        r[1].run();
                        table.invalidateHierarchy();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                    quick.button(Core.bundle.get("universaljunction.reset"), () -> {
                        resetToDefault(selDir[0]);
                        r[1].run();
                        table.invalidateHierarchy();
                        flushConfig();
                    }).size(96f, 32f).pad(3f);
                }).padTop(4f);
            };

            // 重建全局层（全局默认行 4 个输出；数值相同方向折叠为文字）
            r[0] = () -> {
                globalTable.clearChildren();
                final boolean[] gtap = new boolean[4];
                final boolean[] ghover = new boolean[4];
                final float[] glastExit = new float[4];
                final boolean[] gshown = new boolean[4];
                for (int d = 0; d < 4; d++) {
                    final int out = d;
                    Table row = new Table();
                    row.hovered(() -> {
                        ghover[out] = true;
                        renderOutRow(row, defaultRow, out, gtap, ghover, gshown, v -> {
                            // 应用到所有未单独覆盖的输入方向
                            for (int in = 0; in < 4; in++) {
                                if (!isOverride(in)) weights[in][out] = v;
                            }
                            markConfigDirty();
                        });
                    });
                    row.exited(() -> {
                        ghover[out] = false;
                        glastExit[out] = Time.time;
                    });
                    row.update(() -> {
                        if (gshown[out] && !ghover[out] && !gtap[out] && Time.time - glastExit[out] > 0.2f) {
                            gshown[out] = false;
                            renderOutRow(row, defaultRow, out, gtap, ghover, gshown, v -> {
                                for (int in = 0; in < 4; in++) {
                                    if (!isOverride(in)) weights[in][out] = v;
                                }
                                markConfigDirty();
                            });
                        }
                    });
                    globalTable.add(row).padBottom(2f).row();
                    renderOutRow(row, defaultRow, out, gtap, ghover, gshown, v -> {
                        for (int in = 0; in < 4; in++) {
                            if (!isOverride(in)) weights[in][out] = v;
                        }
                        markConfigDirty();
                    });
                }
            };

            // 全量重建
            r[3] = () -> {
                r[0].run();
                r[1].run();
                r[2].run();
                table.invalidateHierarchy();
            };

            // 模板行：横向滚动按钮（内置 + 自定义），右侧固定保存按钮
            table.table(top -> {
                ScrollPane pane = new ScrollPane(templatesTable);
                pane.setScrollingDisabled(false, true); // 仅水平滚动
                pane.setFadeScrollBars(false);
                top.add(pane).width(200f).height(40f).padRight(6f);
                top.button(Core.bundle.get("universaljunction.save"), () -> {
                    ui.showTextInput("", Core.bundle.get("universaljunction.saveTitle"), 12, "", text -> {
                        String name = text.trim();
                        if (!name.isEmpty()) {
                            saveTemplate(name, defaultRow);
                            r[2].run();
                            table.invalidateHierarchy();
                        }
                    });
                }).size(60f, 40f);
            }).padBottom(8f).row();

            // 全局输出优先级
            table.add(Core.bundle.get("universaljunction.global")).color(Pal.accent).padBottom(4f).row();
            table.add(globalTable).padBottom(6f).row();

            // 按方向覆盖（折叠开关）
            TextButton fold = new TextButton("", Styles.defaultt);
            fold.update(() -> fold.setText(Core.bundle.get(expanded[0] ? "universaljunction.collapse" : "universaljunction.expand")));
            fold.clicked(() -> {
                expanded[0] = !expanded[0];
                r[3].run();
            });
            table.add(fold).size(220f, 34f).padTop(2f).row();

            table.add(overrideTable).padTop(4f);

            r[3].run(); // 初始渲染
        }

        // ---------- 存档 ----------

        @Override
        public void write(Writes write) {
            super.write(write);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) write.s((short) weights[i][j]);
            }
            for (int j = 0; j < 4; j++) write.s((short) defaultRow[j]); // v2 起
            buffer.write(write);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = read.s();
            }
            if (revision >= 2) {
                for (int j = 0; j < 4; j++) defaultRow[j] = read.s();
            } else {
                defaultRow = weights[0].clone(); // 旧存档：取第一行作全局默认
            }
            buffer.read(read, revision == 0);
        }

        @Override
        public byte version() {
            return 2;
        }

        @Override
        public void draw() {
            Draw.rect(region, x, y);
        }
    }
}