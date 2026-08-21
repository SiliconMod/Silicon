package silicon.world.blocks.distribution;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Table;
import arc.scene.style.TextureRegionDrawable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.BufferItem;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.DirectionalItemBuffer;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

import static mindustry.Vars.content;

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

    /** 方向名称（标准方位：0=上 1=右 2=下 3=左） */
    public static final String[] dirNames = {"上", "右", "下", "左"};
    /** 方向图标 */
    public static final TextureRegionDrawable[] dirIcons = {Icon.up, Icon.right, Icon.down, Icon.left};

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
        // 复用原版交叉器的贴图
        TextureRegion j = Core.atlas.find("junction");
        region = j;
        generatedIcons = new TextureRegion[]{j};
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.speed, 60f / moveTime, StatUnit.itemsSecond);
    }

    public class UniversalJunctionBuild extends Building {
        /** 优先级矩阵 [输入方向][输出方向]，0~4；0 = 不输出 */
        public final int[][] weights = new int[4][4];
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

        private static final int timerMove = 0;

        /** 默认所有方向优先级 2 */
        {
            setAll(2);
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel == -1) return false;
            return buffer.accepts(3 - rel); // 角度编码 → 标准方位（物品来源方向）
        }

        @Override
        public void handleItem(Building source, Item item) {
            int rel = source.relativeTo(tile);
            if (rel != -1) {
                buffer.accept(3 - rel, item);
            }
        }

        @Override
        public void updateTile() {
            if (timer(timerMove, moveTime)) {
                moveItem();
            }
        }

        /** 每 moveTime 从缓冲最多的方向移动一个物品 */
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

            Building dest = nearby(out ^ 1); // 标准方位 → 角度编码
            dest.handleItem(this, item);
            System.arraycopy(buffer.buffers[input], 1, buffer.buffers[input], 0, buffer.indexes[input] - 1);
            buffer.indexes[input]--;
        }

        /** 选择缓冲物品最多的输入方向 */
        int pickInput() {
            int best = -1, bestCount = 0;
            for (int i = 0; i < 4; i++) {
                if (buffer.indexes[i] > bestCount) {
                    bestCount = buffer.indexes[i];
                    best = i;
                }
            }
            return best;
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
                Building dest = nearby(d ^ 1);
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
                Building dest = nearby(d ^ 1);
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

        /** 将优先级矩阵序列化为 16 个逗号分隔的整数 */
        public String weightsString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if (i > 0 || j > 0) sb.append(',');
                    sb.append(weights[i][j]);
                }
            }
            return sb.toString();
        }

        /** 解析配置字符串，非法时忽略 */
        public void applyConfig(String str) {
            if (str == null) return;
            String[] parts = str.split(",");
            if (parts.length != 16) return;
            try {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        weights[i][j] = Mathf.clamp(Integer.parseInt(parts[i * 4 + j].trim()), 0, 4);
                    }
                }
            } catch (NumberFormatException e) {
                // 忽略非法配置
            }
        }

        @Override
        public String config() {
            return weightsString();
        }

        // ---------- 配置界面 ----------

        @Override
        public void buildConfiguration(Table table) {
            table.button(b -> {
                b.image(Icon.settings).padRight(6f);
                b.add(Core.bundle.get("universaljunction.config"));
            }, this::openConfigDialog).size(200f, 46f);
        }

        /** 打开方向优先级配置对话框 */
        public void openConfigDialog() {
            Dialog dialog = new Dialog(Core.bundle.get("universaljunction.title"));
            // 固定尺寸并留足边距，避免按钮重叠
            dialog.setSize(560f, 520f);

            final int[] selDir = {0};
            Table outTable = new Table();

            Runnable rebuild = () -> {
                outTable.clearChildren();
                int in = selDir[0];
                outTable.add(Core.bundle.format("universaljunction.from", dirNames[in])).color(Pal.accent).padBottom(8f).row();
                for (int d = 0; d < 4; d++) {
                    final int out = d;
                    outTable.table(row -> {
                        row.add(dirNames[out] + " →").width(56f);
                        Slider sl = new Slider(0f, 4f, 1f, false);
                        sl.setValue(weights[in][out]);
                        Label val = new Label(String.valueOf((int) sl.getValue()));
                        sl.changed(() -> {
                            weights[in][out] = (int) sl.getValue();
                            val.setText(String.valueOf(weights[in][out]));
                            configure(weightsString());
                        });
                        row.add(sl).width(260f).padRight(10f);
                        row.add(val).width(44f);
                    }).padBottom(8f).row();
                }
                // 内容变化后强制对话框重新布局，防止溢出到按钮栏
                dialog.invalidateHierarchy();
            };

            Table t = dialog.cont;
            t.margin(14f);
            t.add(Core.bundle.get("universaljunction.hint")).color(Color.gray).padBottom(12f).row();

            // 输入方向选择
            t.table(inputs -> {
                for (int d = 0; d < 4; d++) {
                    final int dir = d;
                    Button btn = inputs.button(b -> {
                        b.image(dirIcons[dir]).padRight(6f);
                        b.add(dirNames[dir]);
                    }, () -> {
                        selDir[0] = dir;
                        rebuild.run();
                    }).size(108f, 44f).pad(4f).get();
                    btn.update(() -> btn.setChecked(selDir[0] == dir));
                }
            }).padBottom(10f).row();

            t.add(outTable).padTop(8f).padBottom(4f);

            // 底部按钮栏：快捷操作与完成按钮同一行，避免重叠
            dialog.buttons.button(Core.bundle.get("universaljunction.even"), () -> {
                setAll(2);
                rebuild.run();
                configure(weightsString());
            }).size(140f, 44f).pad(5f);
            dialog.buttons.button(Core.bundle.get("universaljunction.clear"), () -> {
                setAll(0);
                rebuild.run();
                configure(weightsString());
            }).size(140f, 44f).pad(5f);
            dialog.buttons.button(Core.bundle.get("universaljunction.done"), dialog::hide)
                .size(140f, 44f).pad(5f);

            rebuild.run(); // 初始即渲染完整内容，让对话框按完整高度布局
            dialog.show();
        }

        // ---------- 存档 ----------

        @Override
        public void write(Writes write) {
            super.write(write);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) write.s((short) weights[i][j]);
            }
            buffer.write(write);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) weights[i][j] = read.s();
            }
            buffer.read(read, revision == 0);
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void draw() {
            Draw.rect(region, x, y);
        }
    }
}