package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.HandCursorListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.BaseDrawable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.world.blocks.distribution.UniversalJunction;

import static mindustry.Vars.ui;

/**
 * 万向交叉器配置对话框（新 UI）。
 * <p>
 * 布局：每个输入方向一个白色面板区域，区域内：
 * - 最上方：输入方向标题（白字，水平居中）；
 * - 标题下方：可拖动生成的 0~4 个白色槽位框（优先级分级，越靠上权重越大：槽0=4, 槽1=3, 槽2=2, 槽3=1），
 *   空槽位自动移除，最多 4 个；
 * - 区域最下方：红色框（优先级 0，即不输出），初始放置 4 个输出方向按钮（2x2 宫格）。
 * <p>
 * 交互：按住黄色按钮拖动，松手放置——拖到空白处生成新白色槽位；拖到已有槽位则并入该槽位；
 * 拖回红色框则移回红框，所在槽位空时槽位自动消失。每次放置都会同步到 weights[输入][输出] 并写入配置。
 * <p>
 * 整框调序：按住白框左侧的灰色拖动手柄拖动整框换位，拖拽时：
 * - 白色拖拽影（含内部黄按钮）跟随鼠标移动；
 * - 灰色占位框在落点位置（两白框之间的空隙里）实时显示，水平位置跟随鼠标；
 * - 落点位置两旁的白色框自动让位，拉开空隙；
 * - 松手后灰色占位框消失，白框按落点重排。
 */
public class UniversalJunctionDialog extends BaseDialog {
    /** 单个输入区域最多白色槽位数 */
    private static final int MAX_SLOTS = 4;
    /** 红框（优先级 0 区域）高度基准值：无论内部有无按钮都保持不变、不消失 */
    private static final float RED_H = 140f;
    /** 方向按钮尺寸基准值：红框与白框内一致，保证按钮大小不变 */
    private static final float BTN_W = 140f;
    private static final float BTN_H = 100f;
    /** 方向按钮上的白字放大倍数（arc Label 默认 fontScale=1.0，统一放大便于辨识） */
    private static final float BTN_TEXT_SCALE = 1.5f;
    /** 方向按钮配色：亮黄色不透明内面 + 深棕金边框（自绘填充，不依赖半透明面板纹理） */
    private static final Color BTN_BORDER = Color.valueOf("8a5a00");
    private static final Color BTN_FACE = Color.valueOf("ffd500");
    /** 不透明实心填充（无纹理、随元素颜色着色，替代半透明面板纹理）：亮黄按钮不透明的关键 */
    private static final Drawable SOLID = new BaseDrawable() {
        @Override
        public void draw(float x, float y, float w, float h) {
            Fill.crect(x, y, w, h);
        }

        @Override
        public void draw(float x, float y, float originX, float originY, float w, float h, float scaleX, float scaleY, float rotation) {
            Fill.crect(x, y, w, h);
        }
    };
    /** 整框拖拽时灰色落点占位框的高度（基准值） */
    private static final float PLACE_H = 60f;
    /** 按钮拖到空白处时「新建槽位」长条灰框的高度：比按钮(y=52)高，避免内嵌按钮框与大框上下边缘重叠 */
    private static final float BTN_HINT_H = 100f;

    UniversalJunction.UniversalJunctionBuild build;
    /** 单个黄色按钮正在被拖动时置 true：抑制整框重排的灰色占位框，避免按钮拖拽与整框拖拽互相干扰 */
    boolean draggingButton = false;
    /** 四个输入方向的区域状态，供保存按钮一次性同步全部 */
    private final Seq<RegionState> allRegions = new Seq<>();

    public UniversalJunctionDialog(String title) {
        this(title, Core.scene.getStyle(DialogStyle.class));
    }

    public UniversalJunctionDialog(String title, DialogStyle style) {
        super(title, style);
        setFillParent(true);
        row();
        add(buttons).growX().name("universal-junction");
        shown(this::setup);
    }

    public UniversalJunctionDialog() {
        this("@universal-junction.title");
    }

    public void setup() {
        Log.info("[UJBUILD] rev=20261001B");
        allRegions.clear(); // shown → setup 可能多次调用：先清空，避免 re-show 时累积陈旧区域状态
        cont.table(grid -> {
            grid.margin(10f);
            for (int in = 0; in < 4; in++) {
                final int input = in;
                grid.table(Tex.whitePane, column -> {
                    column.top();
                    column.margin(14f);
                    RegionState rs = new RegionState(input, column);
                    allRegions.add(rs);

                    // 输入方向标题（白字，水平居中）
                    column.add(Core.bundle.get("universal-junction.dir" + input))
                            .growX().labelAlign(Align.center).padBottom(10f).row();

                    // 上方弹性空白：把白框层推到区域纵向居中
                    column.add().expandY().row();

                    // 槽位层：纵向居中于标题与红框之间
                    column.add(rs.slotLayer).growX().row();

                    // 下方弹性空白：与上方共同把白框层居中，同时把红框推到底部
                    column.add().expandY().row();

                    // 红框（优先级 0）：置于区域最下方，高度随按钮数量自动伸缩
                    Table redBox = new Table();
                    redBox.background(Tex.whitePane);
                    redBox.setColor(Color.red);
                    redBox.margin(10f);
                    rs.redBox = redBox;
                    column.add(redBox).growX().padTop(6f).row();

                    // 统一按建筑当前权重还原布局：w>0 → 放入对应白色槽位，w==0 → 放入红框。
                    // 刚放置（权重仍为默认值 2 均分）时视为「未配置」：全部放入红框，与用户直觉一致，
                    // 红框语义为优先级 0 = 不输出。
                    // 仅还原显示：不改写 weights，也不在此触发 configure，避免「打开面板即重置路由瞬态」。
                    boolean freshDefault = true;
                    for (int out = 0; out < 4; out++) {
                        if (build.weights[input][out] != 2) { freshDefault = false; break; }
                    }
                    for (int out = 0; out < 4; out++) {
                        Direction d = new Direction(rs, out);
                        int w = build.weights[input][out];
                        if (freshDefault) {
                            rs.redButtons.add(d);
                        } else if (w > 0) {
                            rs.placeInSlotByWeight(d, w);
                        } else {
                            rs.redButtons.add(d);
                        }
                    }
                    rs.rebuildRed();
                    rs.pruneEmptySlots();
                    rs.rebuildSlots();
                }).growX().grow().uniformX().pad(8f);
            }
        }).grow();

        buttons.defaults()
                .size(280f, 60f)
                .left()
                .margin(10f);
        buttons.button("@back", Icon.left, this::hide);
        buttons.button("@edit", Icon.edit, () -> {
            BaseDialog dialog = new BaseDialog("@edit");

            dialog.cont.pane(p -> p.table(Tex.button, t -> {
                t.defaults()
                        .size(280f, 60f)
                        .left()
                        .margin(10f);
                t.button("@clear", Icon.cancel, Styles.flatt, () -> {
                    ui.showConfirm("", () -> {
                        build.setAll(0);
                        cont.clearChildren();
                        buttons.clearChildren();
                        hide();
                        show();
                        invalidateHierarchy();
                    });
                    dialog.hide();
                }).row();
                t.button("@copy.clipboard", Icon.copy, Styles.flatt, () -> {
                    copyToClipboard();
                    dialog.hide();
                }).row();
                t.button("@load.clipboard", Icon.download, Styles.flatt, () -> {
                    loadFromClipboard();
                    dialog.hide();
                }).row();
            }));
            dialog.addCloseButton();
            dialog.show();
        });
    }

    public void copyToClipboard() {
        Core.app.setClipboardText(build.weightsString());
    }

    public void loadFromClipboard() {
        try {
            build.applyConfig(Core.app.getClipboardText());
        } catch (Throwable e) {
            ui.showException(e);
        }
    }

    public void show(UniversalJunction.UniversalJunctionBuild build) {
        this.build = build;
        show();
    }

    // ============================================================
    // 单个输入区域的状态：维护按钮在红框/各槽位中的归属
    // ============================================================

    public class RegionState {
        final int input;
        final Table column;
        final Table slotLayer;                 // 槽位层（从上堆叠）
        final Seq<SlotBox> slotBoxes = new Seq<>();   // 每个白框（含拖动手柄+按钮区）
        final Seq<Seq<Direction>> slotContents = new Seq<>(); // 每个白框内的按钮
        final Seq<Direction> redButtons = new Seq<>(); // 红框内的按钮
        Table redBox;

        RegionState(int input, Table column) {
            this.input = input;
            this.column = column;
            this.slotLayer = new Table();
        }

        /** 重建红框布局：按钮竖向排列，高度随按钮数量自动伸缩 */
        void rebuildRed() {
            redBox.clearChildren();
            redBox.margin(0f).marginLeft(8f).marginRight(8f).marginTop(12f).marginBottom(12f);
            for (int i = 0; i < redButtons.size; i++) {
                redBox.add(redButtons.get(i)).growX().height(BTN_H).pad(4f).row();
            }
            redBox.invalidateHierarchy();
        }

        /** 重建槽位层（按 slotBoxes 顺序从上往下堆叠，按钮竖向排列，框间留 40 间距） */
        void rebuildSlots() {
            slotLayer.clearChildren();
            for (int i = 0; i < slotBoxes.size; i++) {
                int n = slotContents.get(i).size;
                // 若该框正显示灰色占位（拖拽悬停中），多算一个按钮高度使白框实时扩大
                if (slotBoxes.get(i).previewInsert >= 0) n++;
                float h = n * (BTN_H + 8f) + 14f;
                slotLayer.add(slotBoxes.get(i)).growX().height(h).padBottom(40f).row();
            }
            slotLayer.invalidateHierarchy();
        }

        /** 移除全部空白槽位（无按钮即消失） */
        void pruneEmptySlots() {
            for (int i = slotBoxes.size - 1; i >= 0; i--) {
                if (slotContents.get(i).size == 0) {
                    slotBoxes.remove(i);
                    slotContents.remove(i);
                }
            }
            rebuildSlots();
        }

        /** 由当前布局同步 weights[input][out]：槽 i 权重 = 4-i，红框权重 = 0，并写入配置 */
        void syncWeights() {
            for (int i = 0; i < slotContents.size; i++) {
                int w = MAX_SLOTS - i; // 槽0=4, 槽1=3, ...
                for (Direction d : slotContents.get(i)) {
                    build.weights[input][d.dir] = w;
                }
            }
            for (Direction d : redButtons) {
                build.weights[input][d.dir] = 0;
            }
            build.configure(build.weightsString());
        }

        /** 按钮当前所在：红框 offset=-1；槽位 i 返回其索引 */
        int locate(Direction d) {
            for (int i = 0; i < slotContents.size; i++) {
                if (slotContents.get(i).contains(d)) return i;
            }
            return -1;
        }

        /** 从当前归属处移除按钮，并立即重排来源白框（删除后剩余按钮重新居中） */
        void removeFrom(Direction d) {
            int i = locate(d);
            if (i >= 0) {
                slotContents.get(i).remove(d);
                rebuildSlotContents(i);
            }
            if (redButtons.contains(d)) {
                redButtons.remove(d);
                rebuildRed(); // 红框按钮被拖出：立即重排红框，高度随数量伸缩
            }
        }

        /** 放入指定槽位 i */
        void placeInSlot(Direction d, int i) {
            removeFrom(d);
            slotContents.get(i).add(d);
            rebuildSlotContents(i);
            pruneEmptySlots();
            syncWeights();
        }

        /** 放入指定槽位 i，在该槽内按钮序列的指定位置插入（竖向排序） */
        void placeIntoSlot(Direction d, int i, int insert) {
            int tLoc = locate(d);
            removeFrom(d);
            if (slotBoxes.size == 0) {
                placeInRed(d);
                return;
            }
            int cur = slotContents.get(i).size;
            if (tLoc == i) {
                // 来源与目标同槽：removeFrom 已移除 d，落点相对「移除后」的列表计算
                if (insert > cur) insert = cur;
            }
            insert = Mathf.clamp(insert, 0, slotContents.get(i).size);
            slotContents.get(i).insert(insert, d);
            rebuildSlotContents(i);
            pruneEmptySlots();
            syncWeights();
        }

        /** 计算落点(stage坐标)应插入的槽位索引：与每个槽中心精确比较（stage y-up，越大越靠上） */
        int insertIndexFor(float sx, float sy) {
            for (int i = 0; i < slotBoxes.size; i++) {
                Vec2 v = slotBoxes.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f));
                float centerY = v.y + slotBoxes.get(i).getHeight() / 2f;
                if (sy > centerY) return i; // 落点在该槽中心上方 → 插入到该槽位置（其前）
            }
            return slotBoxes.size; // 落点在所有槽中心之下 → 追加末尾
        }

        /** 槽 i 的高度：与 rebuildSlots 完全一致（按钮竖向排列，每个按钮 BTN_H+8） */
        float slotHeight(int i) {
            int n = slotContents.get(i).size;
            return n * (BTN_H + 8f) + 14f;
        }

        /** 槽 i 高度（悬停占位 +1、拖出收缩 -1，与实时布局一致） */
        float slotHeightWithPreview(int i) {
            int n = slotContents.get(i).size;
            if (slotBoxes.get(i).previewInsert >= 0) n++;
            if (slotBoxes.get(i).vacating) n--;
            if (n < 0) n = 0;
            return n * (BTN_H + 8f) + 14f;
        }

        /**
         * 只调整单个白框在槽位层中的高度（灰色占位增/删时让白框实时扩大/复原）。
         * 只改对应 Cell 的高度并触发重排，不 clearChildren slotLayer——
         * 避免把仍持有触摸焦点的来源白框从场景中 detach，从而引发 arc 的合成 touchUp
         * 把松手前的拖拽提前放置（「一离开白框就放置」）或 NPE。
         */
        void setSlotHeight(int i) {
            slotLayer.getCell(slotBoxes.get(i)).height(slotHeightWithPreview(i));
            slotLayer.invalidateHierarchy();
        }

        /** 清除整框拖拽预览设定的目标坐标（松手/取消时调用，让白框回到正常布局排位） */
        void clearDragPreview() {
            for (int i = 0; i < slotBoxes.size; i++) {
                SlotBox b = slotBoxes.get(i);
                b.dragPreviewX = Float.NaN;
                b.dragPreviewY = Float.NaN;
            }
        }

        /** 生成新槽位（插入到两框之间）并放入按钮 */
        void createSlotFor(Direction d, float sx, float sy) {
            removeFrom(d); // 先移除按钮并重排来源框视觉
            pruneEmptySlots(); // 立即清掉腾空的来源框，让索引基于剔除后的列表
            if (slotBoxes.size >= MAX_SLOTS) {
                placeInRed(d); // 仍无空位则退回红框
                return;
            }
            int insert = insertIndexFor(sx, sy);
            SlotBox box = new SlotBox(this);
            Seq<Direction> contents = new Seq<>();
            contents.add(d);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 放入红框 */
        void placeInRed(Direction d) {
            removeFrom(d);
            redButtons.add(d);
            rebuildRed();
            pruneEmptySlots();
            syncWeights();
        }

        /** 按权重放入对应槽位（初始化时用）：w=4→槽0, 3→槽1, 2→槽2, 1→槽3；槽不存在则创建 */
        void placeInSlotByWeight(Direction d, int w) {
            int idx = MAX_SLOTS - w;
            if (idx < 0 || idx >= MAX_SLOTS) {
                placeInRed(d);
                return;
            }
            while (slotBoxes.size <= idx) {
                slotBoxes.add(new SlotBox(this));
                slotContents.add(new Seq<Direction>());
            }
            removeFrom(d);
            slotContents.get(idx).add(d);
            rebuildSlotContents(idx);
        }

        /** 重建某个槽位的内部按钮布局：按钮组整体水平居中（用两侧expandX空白吸收剩余宽度） */
        void rebuildSlotContents(int i) {
            SlotBox box = slotBoxes.get(i);
            box.rebuildButtons(slotContents.get(i));
        }

        /** 整框换位：把 srcIdx 的白框整体移动到落点位置（拖动手柄触发）。
         * 落点索引由拖动预览（layoutDragPreview 的 curInsert）给出：去掉被拖框后直接插入该槽位，
         * 与预览灰框的观感一致。 */
        void reorderBox(int srcIdx, int insert) {
            SlotBox box = slotBoxes.remove(srcIdx);
            Seq<Direction> contents = slotContents.remove(srcIdx);
            insert = Mathf.clamp(insert, 0, slotBoxes.size);
            slotBoxes.insert(insert, box);
            slotContents.insert(insert, contents);
            rebuildSlotContents(insert);
            rebuildSlots();
            syncWeights();
        }

        /** 单个白框：左侧拖动手柄（整框排序），右侧按钮区（按钮可单独拖出/拖入） */
        class SlotBox extends Table {
            /** 按钮区：只有按钮会被重排 */
            final Table content = new Table();
            /** 拖拽悬停本框时，灰色占位按钮的插入位置（-1 表示不显示占位） */
            int previewInsert = -1;
            /** 整框拖拽预览设定的目标局部坐标（NaN=不干预，按布局正常排位）。
             * slotLayer 每帧 layout 会把子元素位置重置回 cell 位置，故须在 draw 前重新应用。 */
            float dragPreviewX = Float.NaN;
            float dragPreviewY = Float.NaN;

            @Override
            public void draw() {
                if (!Float.isNaN(dragPreviewY)) {
                    x = dragPreviewX;
                    y = dragPreviewY;
                }
                super.draw();
            }
            /** 拖拽中本框内某按钮被拖出：实时把该按钮的行折叠、框高收缩（空位排掉），拖回时恢复 */
            boolean vacating = false;

            SlotBox(RegionState rs) {
                // 白框底
                background(Tex.whitePane);
                setColor(Color.white);
                margin(2f);
                touchable = Touchable.enabled;

                addListener(new InputListener() {
                    private Table ghost; // 整框拖拽影（跟随指针的「白框+内部黄按钮」整体）
                    private Table placeGhost; // 灰色落点占位框（root 层）
                    private float downX, downY; // 按下时的指针(stage)坐标，用于判定是否开始拖动
                    private float ghostW, ghostH; // 拖拽影的真实尺寸（灰色落点框与它对齐）
                    private boolean dragging; // 是否已进入整框拖动
                    private int srcIdx; // 拖动开始时被拖框的索引
                    private float[] baseBottoms; // 基准布局下各白框底边的 stage y（拖动开始瞬刻快照）
                    private float baseTop; // 基准布局下最顶白框顶边的 stage y
                    private Vec2 slotBase; // slotLayer 原点(stage)坐标，用于把 stage 几何转局部坐标置位
                    private float selfH; // 被拖框的真实配额高度 slotHeight(srcIdx)（灰框占位用，勿用 ghostH：差了 14px）
                    private int lastDbgIns = -1; // [UJDBG] 上次整框预览插入索引（抑制重复日志）
                    private int dbgTick = 0; // [UJDBG] 诊断节流计数
                    private int curInsert = 0; // 当前插入索引（去掉被拖框后），供松手落点复用

                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (button == KeyCode.mouseMiddle) return false;
                        // 黄色按钮拖动进行中：不启动整框拖动
                        if (draggingButton) return false;
                        // 按在黄色 Direction 按钮（或其内部子元素）上时不启动整框拖动（按钮自带独立拖拽）
                        if (isDirectionTarget(event.targetActor)) return false;
                        downX = event.stageX;
                        downY = event.stageY;
                        return true;
                    }

                    /** 判断目标或其任意祖先是否为黄色 Direction 按钮 */
                    private boolean isDirectionTarget(Element e) {
                        while (e != null) {
                            if (e instanceof Direction) return true;
                            e = e.parent;
                        }
                        return false;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        // 黄色按钮拖动进行中：不做整框重排
                        if (draggingButton) return;

                        if (!dragging) {
                            // 指针移动超过阈值才判定为「拖动」
                            if (Math.abs(event.stageX - downX) + Math.abs(event.stageY - downY) < 8f) return;
                            srcIdx = rs.slotBoxes.indexOf(SlotBox.this);
                            if (srcIdx < 0) return;
                            // 快照基准堆叠几何（只记录坐标，不修改布局树：不改 Cell/不增删子元素，
                            // 避免 arc 对触摸焦点元素的 unfocus 重入 touchUp 杀死拖拽）
                            baseBottoms = new float[rs.slotBoxes.size];
                            for (int i = 0; i < rs.slotBoxes.size; i++) {
                                baseBottoms[i] = rs.slotBoxes.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                            }
                            baseTop = baseBottoms[0] + rs.slotHeight(0);
                            slotBase = rs.slotLayer.localToStageCoordinates(Tmp.v2.set(0f, 0f)).cpy();
                            selfH = rs.slotHeight(srcIdx);
                            StringBuilder sb0 = new StringBuilder();
                            for (int i = 0; i < rs.slotBoxes.size; i++) {
                                sb0.append("b").append(i).append('@').append((int) baseBottoms[i]).append('h').append((int) rs.slotHeight(i)).append(' ');
                            }
                            Log.info("[UJDBG] start baseTop=@ selfH=@ srcIdx=@ | @", (int) baseTop, (int) selfH, srcIdx, sb0);

                            Table gh = buildGhost();
                            gh.touchable = Touchable.disabled;
                            ghostW = gh.getWidth();
                            ghostH = gh.getHeight();
                            gh.setPosition(event.stageX - ghostW / 2f, event.stageY - ghostH / 2f);
                            Core.scene.root.addChild(gh);
                            ghost = gh;
                            SlotBox.this.visible = false;
                            dragging = true;
                        }

                        Table gh = ghost;
                        if (gh == null) return;
                        // 拖拽影水平限制在来源列内（否则会滑到相邻输入区域的白框上造成"重叠"）
                        float gw = gh.getWidth();
                        Vec2 cb = rs.column.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float minCx = cb.x + 10f + gw / 2f;
                        float maxCx = cb.x + rs.column.getWidth() - 10f - gw / 2f;
                        if (minCx > maxCx) { minCx = cb.x + rs.column.getWidth() / 2f; maxCx = minCx; }
                        float gcx = Mathf.clamp(event.stageX, minCx, maxCx);
                        gh.setPosition(gcx - gw / 2f, event.stageY - gh.getHeight() / 2f);
                        gh.toFront();
                        layoutDragPreview(event.stageX, event.stageY);
                        gh.toFront(); // 白色拖拽影始终在上层
                        if (placeGhost != null) placeGhost.toFront(); // 但灰色落点框压过拖拽影，确保可见
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        if (!dragging) return; // 单击（未拖动）：不换位、无痕迹
                        Log.info("[UJ] whole-drag end");
                        dragging = false;
                        float sx = event.stageX;
                        float sy = event.stageY;
                        if (ghost != null) {
                            ghost.remove();
                            ghost = null;
                        }
                        removePlaceGhost();
                        SlotBox.this.visible = true;
                        rs.clearDragPreview();
                        // 落点判定：直接采用拖动预览算出的插入索引（与灰框观感一致），
                        // reorderBox 内 rebuildSlots 的 clearChildren 不会触发 unfocus 重入
                        int src = rs.slotBoxes.indexOf(SlotBox.this);
                        Log.info("[UJDBG] drop sx=@ sy=@ src=@ ins=@", (int) sx, (int) sy, src, curInsert);
                        if (src >= 0) rs.reorderBox(src, curInsert);
                    }

                    /** 构造整框拖拽影：白框底 + 左侧灰手柄 + 内部黄按钮（与真实框同样式），可整体拖动 */
                    private Table buildGhost() {
                        int srcIdx = slotBoxes.indexOf(SlotBox.this);
                        Seq<Direction> contents = srcIdx >= 0 ? slotContents.get(srcIdx) : new Seq<>();

                        Table g = new Table();
                        g.background(Tex.whitePane);
                        g.setColor(1f, 1f, 1f, 0.85f);
                        g.touchable = Touchable.disabled;
                        g.margin(0f).marginLeft(8f).marginRight(8f);

                        for (int i = 0; i < contents.size; i++) {
                            Direction d = contents.get(i);
                            Table btn = new Table() {
                                @Override
                                public void draw() {
                                    float pad = 5f;
                                    Fill.dropShadow(x + width / 2f, y + height / 2f, width + pad, height + pad, 10f, 0.9f * parentAlpha);
                                    Draw.color(0, 0, 0, 0.3f * parentAlpha);
                                    Fill.crect(x, y, width, height);
                                    Draw.reset();
                                    super.draw();
                                }
                            };
                            btn.background(SOLID);
                            btn.setColor(BTN_BORDER);
                            btn.margin(0f);
                            btn.touchable = Touchable.disabled;
                            btn.table(SOLID, t -> {
                                t.color.set(BTN_FACE);
                                t.margin(6f);
                                t.touchable = Touchable.disabled;
                                t.add("@universal-junction.dir" + d.dir).style(Styles.outlineLabel).color(Color.white)
                                        .update(l -> l.setFontScale(BTN_TEXT_SCALE)).grow().labelAlign(Align.center);
                            }).grow().pad(2f);
                            g.add(btn).growX().height(BTN_H).pad(4f).row();
                        }

                        g.pack();
                        g.setSize(SlotBox.this.getWidth(), g.getPrefHeight());
                        return g;
                    }

                    /** 整框拖拽预览：镜像「黄色按钮拖到其他白框」的落点口径（drawBoxReflowPreview）——
                     * 不折叠被拖框（其 cell 留作空位 phantom），灰框(整框大小)插入到指针所指位置：
                     *  · 悬停来源空位区域(ins==phantIdx 或 +1)：灰框回到 source 空位，白框一律不动；
                     *  · 最顶之上(ins==0)：白框不动，灰框浮在最顶白框上方（留 GAP）；
                     *  · 最底之下(ins>=nAll)：白框不动，灰框紧贴最底白框下方（留 GAP）；
                     *  · 两框之间：其下各框整体下移 (GAP+selfH)，灰框占住让出的空位。
                     * 白框始终与灰框保持 GAP，绝不重叠；落点 curInsert 与松手 reorderBox 同口径。
                     * 白色拖拽影（跟随指针）保留。 */
                    private void layoutDragPreview(float sx, float sy) {
                        int nAll = rs.slotBoxes.size;
                        if (nAll == 0) return;
                        float GAP = 40f;
                        if (nAll == 1) {
                            // 仅一个白框：灰框停在自身 cell（拖动必落回原位）
                            curInsert = 0;
                            drawPlaceGhost(baseTop - selfH, sx);
                            return;
                        }
                        int phantIdx = srcIdx; // 被拖框：隐藏但占据其 cell 空间（phantom 空位）
                        // 实时堆叠：从当前堆叠顶(baseTop 快照)起向下依次排布（含 phantom）
                        float[] top = new float[nAll];
                        float y = baseTop;
                        for (int j = 0; j < nAll; j++) {
                            top[j] = y;
                            y -= rs.slotHeight(j) + GAP;
                        }
                        // 插入索引（含 phantom 共 nAll+1 位，0..nAll）
                        int ins = 0;
                        for (int j = 0; j < nAll; j++) {
                            float center = top[j] - rs.slotHeight(j) / 2f;
                            if (sy > center) { ins = j; break; }
                            ins = j + 1;
                        }
                        ins = Mathf.clamp(ins, 0, nAll);
                        if ((dbgTick++ % 25) == 0) {
                            StringBuilder sp = new StringBuilder();
                            for (int j = 0; j < nAll; j++) {
                                float ay = rs.slotBoxes.get(j).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                                sp.append('b').append(j).append('=').append((int) ay).append(' ');
                            }
                            Log.info("[UJDBG] pre ins=@ | @", ins, sp);
                        }
                        float grayTop; // 灰框顶(stage)
                        if (phantIdx >= 0 && (ins == phantIdx || ins == phantIdx + 1)) {
                            // 悬停来源空位：灰框回到 source 空位，白框一律不动
                            grayTop = top[phantIdx];
                        } else if (ins >= nAll) {
                            // 追加最下方：白框不动，灰框紧贴最底白框下方（留 GAP）
                            float lastBottom = top[nAll - 1] - rs.slotHeight(nAll - 1);
                            grayTop = lastBottom - GAP;
                        } else if (ins == 0) {
                            // 插到最顶之上：白框不动，灰框浮在最顶白框上方（留 GAP）
                            grayTop = top[0] + GAP + selfH;
                        } else {
                            // 两框之间：其下各框整体下移 (GAP+selfH)，灰框占住让出的空位
                            float upperBottom = top[ins - 1] - rs.slotHeight(ins - 1);
                            float shift = GAP + selfH;
                            for (int j = ins; j < nAll; j++) top[j] -= shift;
                            grayTop = upperBottom - GAP;
                        }
                        // 设定全部白框的预览目标坐标（含 hidden phantom，无害）：
                        // 不依赖 setPosition（slotLayer 每帧 layout 会把它重置回 cell 位置），
                        // 改由 SlotBox.draw 前重新应用，保证白框真正显示在让位后的位置。
                        for (int j = 0; j < nAll; j++) {
                            RegionState.SlotBox b = rs.slotBoxes.get(j);
                            float hh = rs.slotHeight(j);
                            b.dragPreviewX = b.x;
                            b.dragPreviewY = (top[j] - hh) - slotBase.y;
                        }
                        // 松手落点：去掉被拖框后的插入索引 = ins - (phantIdx < ins ? 1 : 0)
                        curInsert = Mathf.clamp(ins - (phantIdx >= 0 && phantIdx < ins ? 1 : 0), 0, nAll - 1);
                        float grayBottom = grayTop - selfH;
                        if (lastDbgIns != ins) {
                            lastDbgIns = ins;
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < nAll; j++) {
                                float ay = rs.slotBoxes.get(j).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                                sb.append('b').append(j).append('=').append((int) ay).append('/').append((int) top[j]).append(' ');
                            }
                            Log.info("[UJDBG] whole ins=@ src=@ grayBottom=@ | @", ins, srcIdx, (int) grayBottom, sb);
                        }
                        drawPlaceGhost(grayBottom, sx);
                    }

                    /** 绘制灰色落点占位框到最终落点位置（stage 坐标，水平跟随鼠标但限制在大白框列内） */
                    private void drawPlaceGhost(float bottomStageY, float sx) {
                        float boxW = ghostW > 0 ? ghostW : SlotBox.this.getWidth();
                        float ph = selfH > 0 ? selfH : PLACE_H;
                        Vec2 cb = rs.column.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float colLeft = cb.x;
                        float colRight = cb.x + rs.column.getWidth();
                        float marginX = 10f;
                        float minCx = Math.min(colLeft + marginX + boxW / 2f, colRight - marginX - boxW / 2f);
                        float maxCx = Math.max(colLeft + marginX + boxW / 2f, colRight - marginX - boxW / 2f);
                        float centerX = Mathf.clamp(sx, minCx, maxCx);

                        if (placeGhost != null && Math.abs(placeGhost.y - bottomStageY) < 1f
                                && Math.abs(placeGhost.x - (centerX - boxW / 2f)) < 1f) return;

                        if (placeGhost != null) placeGhost.remove();
                        placeGhost = new Table();
                        placeGhost.background(Tex.whitePane);
                        placeGhost.setColor(Color.gray);
                        placeGhost.setSize(boxW, ph);
                        placeGhost.touchable = Touchable.disabled;
                        placeGhost.setPosition(centerX - boxW / 2f, bottomStageY);
                        Core.scene.root.addChild(placeGhost);
                    }

                    private void removePlaceGhost() {
                        if (placeGhost != null) {
                            placeGhost.remove();
                            placeGhost = null;
                        }
                    }
                });

                // 布局：整框可拖动，内部按钮区
                content.touchable = Touchable.childrenOnly;
                add(content).grow();
            }

            /** 重建按钮区：按钮竖向排列，左右留小边距使按钮比白框略短；若 previewInsert>=0 则在对应位置插入灰色占位按钮 */
            void rebuildButtons(Seq<Direction> buttons) {
                // 重建前先取消本框及其按钮可能持有的触摸焦点，避免 detach 元素后下一事件派发到已离树的节点
                Core.scene.cancelTouchFocus(this);
                for (int i = 0; i < buttons.size; i++) Core.scene.cancelTouchFocus(buttons.get(i));
                content.clearChildren();
                content.margin(0f).marginLeft(8f).marginRight(8f);
                int placed = 0;
                for (int i = 0; i < buttons.size; i++) {
                    if (previewInsert == placed) {
                        content.add(placeholder()).growX().height(BTN_H).pad(4f).row();
                        placed++;
                    }
                    content.add(buttons.get(i)).growX().height(BTN_H).pad(4f).row();
                    placed++;
                }
                if (previewInsert >= placed) {
                    content.add(placeholder()).growX().height(BTN_H).pad(4f).row();
                }
                content.invalidateHierarchy();
                invalidateHierarchy();
            }

            /** 拖拽中把按钮 d 所在单元格折叠（高 0、无内边距）或恢复：让来源白框实时收缩、空位被排掉。
             * 只改 Cell，不 detach 按钮 → 不打断持触摸焦点的被拖按钮。 */
            void setVacating(Direction d, boolean v) {
                if (vacating == v) return;
                vacating = v;
                if (d != null) {
                    Seq<Cell> cells = content.getCells();
                    for (int i = 0; i < cells.size; i++) {
                        Cell c = cells.get(i);
                        if (c.get() == d) {
                            if (v) c.height(0f).pad(0f);
                            else c.height(BTN_H).pad(4f);
                            break;
                        }
                    }
                }
                content.invalidateHierarchy();
            }

            /** 灰色占位按钮：提示按钮即将放入本框的位置 */
            private Table placeholder() {
                Table ph = new Table();
                ph.background(Tex.whitePane);
                ph.setColor(0.5f, 0.5f, 0.5f, 0.9f);
                ph.touchable = Touchable.disabled;
                return ph;
            }
        }
    }

    // ============================================================
    // 黄色按钮：可按住拖动，松手按落点放置
    // ============================================================

    public class Direction extends Table {
        final RegionState rs;
        final int dir;
        Table ghost; // 拖拽中的浮动影子
        RegionState.SlotBox srcSlotBox; // 拖出唯一按钮时被隐藏的来源白框（松手时恢复）

        // —— 拖动预览用的基准几何快照（dragStart 时采集，仅 setPosition 手绘，不增删布局树）——
        float boxBaseTop;        // 基准布局下最顶白框顶边的 stage y
        boolean boxReflowActive; // 是否已手动重排过白框（空白/新建槽位预览）
        Vec2 slotBase;           // slotLayer 原点(stage)坐标，用于把 stage 几何转局部置位
        int srcBoxIdx = -1;      // 来源白框索引（拖拽开始时按钮所在框），-1=不在框内
        boolean inBoxReflowActive; // 是否正在对来源框内按钮做手动重排
        float[] srcBtnBaseBottoms; // 来源框内各按钮底边 stage y 的快照（拖拽开始时）

        public Direction(RegionState rs, int dir) {
            this.rs = rs;
            this.dir = dir;

            background(SOLID);
            setColor(BTN_FACE);
            margin(0f);
            touchable = Touchable.enabled;

            table(t -> {
                t.addListener(new HandCursorListener());
                t.margin(6f);
                t.touchable = Touchable.enabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).name("statement-name")
                        .color(Color.white).update(l -> l.setFontScale(BTN_TEXT_SCALE)).grow().labelAlign(Align.center);
            }).grow();

            row();

addListener(new InputListener() {
                private Table hint; // 灰色落点提示框（root 层，按钮大小一致）
                private int lastDbgRow = -1; // [UJDBG] 上次框内预览行（抑制重复日志）
                private int lastDbgBoxIns = -2; // [UJDBG] 上次新建槽位预览 ins（抑制重复日志）

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    if (event.targetActor instanceof Image) return false;
                    if (button == KeyCode.mouseMiddle) return false;
                    return dragStart(event);
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (ghost != null) {
                        ghost.setPosition(event.stageX - ghost.getWidth() / 2f,
                                event.stageY - ghost.getHeight() / 2f);
                    }
                    updateDragPreview(event.stageX, event.stageY);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    if (ghost == null) return;
                    float sx = event.stageX;
                    float sy = event.stageY;

                    // 恢复来源白框可见性（拖空时曾隐藏）
                    if (Direction.this.srcSlotBox != null) {
                        Direction.this.srcSlotBox.visible = true;
                        Direction.this.srcSlotBox = null;
                    }

                    // 还原一切预览重排（白框/框内按钮回基准位），保证后续落点判断基于自然几何
                    restoreBoxPristine();
                    resetInBoxReflow();

                    // 判定落点
                    RegionState.SlotBox targetSlot = findTargetSlot(sx, sy);
                    boolean inRed = inRect(rs.redBox, sx, sy);
                    removeHint();
                    clearGhost();
                    draggingButton = false;
                    visible = true;

                    if (targetSlot != null) {
                        // 落入白框：在目标框内按钮序列的插入位置放置
                        int targetIdx = rs.slotBoxes.indexOf(targetSlot);
                        if (targetIdx >= 0) {
                            // 计算目标框内插入位置
                            int btnInsert = computeButtonInsert(targetSlot, sy);
                            rs.placeIntoSlot(Direction.this, targetIdx, btnInsert);
                        }
                    } else if (inRed) {
                        rs.placeInRed(Direction.this);
                    } else {
                        // 松手仍落在来源框内：留在原框、按落点排序，不新建槽位（避免被当成放框外）
                        RegionState.SlotBox srcBox = currentSrcBox();
                        if (srcBox != null && inRect(srcBox, sx, sy)) {
                            int srcIdx = rs.slotBoxes.indexOf(srcBox);
                            int btnInsert = baseInsertForSource(sy);
                            rs.placeIntoSlot(Direction.this, srcIdx, btnInsert);
                        } else if (rs.slotBoxes.size < MAX_SLOTS) {
                            rs.createSlotFor(Direction.this, sx, sy);
                        } else {
                            int cur = rs.locate(Direction.this);
                            if (cur >= 0) rs.placeInSlot(Direction.this, cur);
                            else rs.placeInRed(Direction.this);
                        }
                    }
                    resetDragState();
                }

                /** 主拖拽预览入口：判定落点目标并绘制灰色提示框（按钮等大 + 推开相邻占位） */
                private void updateDragPreview(float sx, float sy) {
                    RegionState.SlotBox targetSlot = findTargetSlot(sx, sy);
                    if (targetSlot != null) {
                        // 悬停在（非来源）白框上方 → 在该框内按钮序列位置插入灰色占位（实时改变白框大小）
                        int btnInsert = computeButtonInsert(targetSlot, sy);
                        leaveReflowStates();
                        setSourceVacating(currentSrcBox(), true); // 按钮离开来源框 → 来源框实时收缩排掉空位
                        showBoxPlaceholder(targetSlot, btnInsert);
                        return;
                    }
                    // 未落在其它白框：若仍落在来源框内（多按钮框内移动）→ 手动重排框内按钮并绘制按钮等大占位。
                    // 若来源框已被隐藏成空框（srcSlotBox，唯一按钮被拖出），则不再把它当框内目标，
                    // 其区域按空白处理 → 落在其上下时应显示新建槽位预览而非灰掉。
                    RegionState.SlotBox srcBox = currentSrcBox();
                    if (srcBox != null && srcBox != srcSlotBox && inBaseBox(srcBoxIdx, sx, sy)) {
                        // 先还原白框/按钮基准几何：防止上一帧的空白区重排位移残留（框被推移而按钮画回原位）
                        // 造成框与按钮交错、灰框位置错乱、边界处频繁闪动。
                        restoreBoxPristine();
                        removeHint(); // 清除其它白框占位并复原布局
                        setSourceVacating(srcBox, false); // 回到来源框内 → 框恢复原大小
                        drawInBoxReflow(srcBox, baseInsertForSource(sy));
                        return;
                    }
                    boolean inRed = inRect(rs.redBox, sx, sy);
                    if (!inRed) {
                        // 离开所有白框/红框 → 清理框内/白框占位与重排，绘制「新建槽位」按钮等大占位并推开相邻白框
                        removeHint();
                        resetInBoxReflow();
                        setSourceVacating(srcBox, true); // 按钮在框外 → 来源框实时收缩排掉空位
                        drawBoxReflowPreview(sx, sy);
                        return;
                    }
                    // 落回红框：还原所有重排与占位（按钮将移入红框，来源框收缩）
                    restoreBoxPristine();
                    resetInBoxReflow();
                    setSourceVacating(srcBox, true);
                    removeHint();
                }

                /** 设置来源框的实时收缩状态（被拖按钮移出后收缩一格、移回后复原），并同步该框 cell 高度 */
                private void setSourceVacating(RegionState.SlotBox srcBox, boolean v) {
                    if (srcBox == null || srcBox == srcSlotBox) return;
                    if (srcBox.vacating == v) return;
                    int si = rs.slotBoxes.indexOf(srcBox);
                    if (si < 0) return;
                    srcBox.setVacating(Direction.this, v);
                    rs.setSlotHeight(si);
                }

                /** 离开框内重排/白框重排任一状态时统一还原（进入白框占位或落下前调用） */
                private void leaveReflowStates() {
                    restoreBoxPristine();
                    resetInBoxReflow();
                }

                /** 命中测试：指针落在哪个白框内（跳过来源框，避免重建来源框把持触摸焦点的按钮 detach 造成提前放置）。
                 * 用「基准堆叠几何」（boxBaseTop 向下推算）而非实时位置判定：空白区预览的重排会实时推移白框，
                 * 若按实时位置判定，被移进指针下的白框会把模式拉成框内占位，布局恢复后又弹回空白模式——来回抖动。
                 * 基准判定与松手时的落点口径一致，且不随预览位移反馈变化。 */
                private RegionState.SlotBox findTargetSlot(float sx, float sy) {
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox box = rs.slotBoxes.get(i);
                        if (box == srcSlotBox) continue; // 跳过隐藏的来源框（拖出唯一按钮时）
                        if (rs.slotContents.get(i).contains(Direction.this)) continue; // 跳过当前按钮所在框（多按钮框）
                        if (inBaseBox(i, sx, sy)) return box;
                    }
                    return null;
                }

                /** 按基准堆叠几何判定指针是否落在框 i 内（不随预览位移变化） */
                private boolean inBaseBox(int i, float sx, float sy) {
                    if (i < 0 || i >= rs.slotBoxes.size) return false;
                    float y = currentStackTop();
                    for (int k = 0; k < i; k++) {
                        y -= rs.slotHeightWithPreview(k) + 40f;
                    }
                    RegionState.SlotBox box = rs.slotBoxes.get(i);
                    Vec2 v = box.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                    return sx >= v.x && sx <= v.x + box.getWidth()
                            && sy >= y - rs.slotHeightWithPreview(i) && sy <= y;
                }

                /** 当前堆叠顶的 stage y：来源框收缩会改变 slotLayer 高度并使其重新居中，
                 * 拖拽开始时的 boxBaseTop 快照会过期，故实时从最顶白框读取。 */
                private float currentStackTop() {
                    if (rs.slotBoxes.size == 0) return 0f;
                    RegionState.SlotBox b0 = rs.slotBoxes.get(0);
                    return b0.localToStageCoordinates(Tmp.v2.set(0f, b0.getHeight())).y;
                }

                /** 当前被拖按钮所在的来源框（任意按钮数量），null 表示不在任何白框内 */
                private RegionState.SlotBox currentSrcBox() {
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        if (rs.slotContents.get(i).contains(Direction.this)) return rs.slotBoxes.get(i);
                    }
                    return null;
                }

                /** 基于「来源框基准布局」计算插入索引（使用 dragStart 快照的底边，不受框内重排位移影响，避免反馈抖动） */
                private int baseInsertForSource(float sy) {
                    if (srcBoxIdx < 0 || srcBoxIdx >= rs.slotContents.size) return 0;
                    Seq<Direction> contents = rs.slotContents.get(srcBoxIdx);
                    for (int i = 0; i < contents.size; i++) {
                        float bottom = (i < srcBtnBaseBottoms.length) ? srcBtnBaseBottoms[i]
                                : contents.get(i).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                        float centerY = bottom + BTN_H / 2f;
                        if (sy > centerY) return i;
                    }
                    return contents.size;
                }

                /** 计算按钮应插入目标白框内按钮序列的哪个位置（竖向比较中心 y） */
                private int computeButtonInsert(RegionState.SlotBox target, float sy) {
                    Seq<Direction> contents = null;
                    int tIdx = rs.slotBoxes.indexOf(target);
                    if (tIdx >= 0) contents = rs.slotContents.get(tIdx);
                    if (contents == null) return 0;
                    for (int i = 0; i < contents.size; i++) {
                        Direction d = contents.get(i);
                        Vec2 v = d.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float centerY = v.y + d.getHeight() / 2f;
                        if (sy > centerY) return i;
                    }
                    return contents.size;
                }

                /** 在目标白框内放置灰色占位按钮：重建该框按钮区（含占位），使白框实时扩大并让占位与按钮一起排序。
                 * 只改目标框内容与高度，绝不 clearChildren slotLayer，避免把持触摸焦点的来源框 detach 造成提前放置。 */
                private void showBoxPlaceholder(RegionState.SlotBox target, int insertIdx) {
                    if (target == srcSlotBox) { removeHint(); return; } // 同框不实时改布局
                    if (target.previewInsert == insertIdx) { clearRootHint(); return; }
                    // 清除其它白框的占位（逐框复原，不整层重建）
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        if (b == target) continue;
                        if (b.previewInsert != -1) {
                            b.previewInsert = -1;
                            rs.rebuildSlotContents(i);
                            rs.setSlotHeight(i);
                        }
                    }
                    clearRootHint();
                    target.previewInsert = insertIdx;
                    int tIdx = rs.slotBoxes.indexOf(target);
                    if (tIdx >= 0) {
                        rs.rebuildSlotContents(tIdx);
                        rs.setSlotHeight(tIdx);
                    }
                }

                /** 框内排序预览：把来源框里的其它按钮手动重排进「去掉被拖按钮后 + 一个按钮等大占位」的槽位，
                 * 占位与按钮一起排位、绝不重叠。不改布局树（避免 detach 把持触摸焦点的按钮）。
                 * @param insertIdx 相对全部按钮（含被拖按钮）的插入索引 */
                private void drawInBoxReflow(RegionState.SlotBox box, int insertIdx) {
                    int idx = rs.slotBoxes.indexOf(box);
                    Seq<Direction> contents = idx >= 0 ? rs.slotContents.get(idx) : null;
                    if (contents == null || contents.size == 0) { removeHint(); return; }
                    inBoxReflowActive = true;
                    int n = contents.size;
                    int srcBtn = contents.indexOf(Direction.this);
                    // 去掉被拖按钮后，占位之前的可见按钮数 = previewRow
                    int previewRow = insertIdx - (srcBtn < insertIdx ? 1 : 0);
                    previewRow = Mathf.clamp(previewRow, 0, n - 1);

                    // 槽位顶取基准布局（dragStart 快照），避免被本次重排位移反馈影响
                    float slotTop = (srcBtnBaseBottoms != null && srcBtnBaseBottoms.length > 0)
                            ? srcBtnBaseBottoms[0] + BTN_H
                            : contents.get(0).localToStageCoordinates(Tmp.v1.set(0f, contents.get(0).getHeight())).y;
                    float pitch = BTN_H + 8f;

                    // 其余（可见）按钮按原序填入除 previewRow 外的各槽位
                    int v = 0;
                    Table content = box.content;
                    for (int row = 0; row < n; row++) {
                        if (row == previewRow) {
                            // 该行显示灰色占位（按钮等大，水平固定对齐按钮列，仅随鼠标上下移动）
                            showHintBox(fixedHintCenterX(),
                                    slotTop - BTN_H - row * pitch, BTN_H, buttonWidth());
                            continue;
                        }
                        // 找到下一个可见（非被拖）按钮
                        while (v < n && contents.get(v) == Direction.this) v++;
                        if (v >= n) break;
                        Direction d = contents.get(v);
                        Vec2 l = content.stageToLocalCoordinates(Tmp.v1.set(0f, slotTop - BTN_H - row * pitch));
                        d.setPosition(d.x, l.y);
                        v++;
                    }
                    // 说明：被拖按钮自身保持 visible=false（占位 row 显示灰色框），stay 原位
                    if (ghost != null) ghost.toFront();
                    if (lastDbgRow != previewRow) {
                        lastDbgRow = previewRow;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < contents.size; i++) {
                            Direction dd = contents.get(i);
                            float ay = dd.localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                            sb.append(dd == Direction.this ? 'X' : (char) ('0' + i)).append('=').append((int) ay).append(' ');
                        }
                        Log.info("[UJDBG] inBox n=@ srcBtn=@ idx=@ row=@ slotTop=@ grayBottom=@ | @",
                                n, srcBtn, idx, previewRow, (int) slotTop, (int) (slotTop - BTN_H - previewRow * pitch), sb);
                    }
                }

                /** 白框序列的新建槽位预览：依据「实时/显示几何」手动把各白框重排并绘制灰色占位。
                 * 关键：几何口径与 inBaseBox / 落下时的 pruneEmptySlots+insertIndexFor 完全一致——
                 * 来源框被拖成空框（srcSlotBox）后仍占据其 cell 空间（视觉上就是两白框之间的空白/空槽位），
                 * 预览不对它做「折叠」：否则下方白框会整体上跳一个整框高度，灰框与指针错位并出现交错。
                 * 灰框画在 phantom 处 = drop 后新框的落点；两可见框之间才用下移扩隙并居中的方式。 */
                private void drawBoxReflowPreview(float sx, float sy) {
                    int nAll = rs.slotBoxes.size;
                    if (nAll == 0) {
                        Vec2 cb = rs.column.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                        float cx = cb.x + rs.column.getWidth() / 2f;
                        float cy = cb.y + rs.column.getHeight() / 2f;
                        showHintBox(cx, cy - BTN_H / 2f, BTN_H, buttonWidth());
                        return;
                    }
                    boxReflowActive = true;
                    float GAP = 40f;
                    int phantIdx = srcSlotBox != null ? rs.slotBoxes.indexOf(srcSlotBox) : -1;
                    // 各白框（含 phantom）在「实时堆叠」下的 top(stage)：从当前堆叠顶起向下依次排布
                    float[] top = new float[nAll];
                    float baseTop = currentStackTop();
                    float y = baseTop;
                    for (int j = 0; j < nAll; j++) {
                        top[j] = y;
                        y -= rs.slotHeightWithPreview(j) + GAP;
                    }
                    // 插入索引（含预览槽共 nAll+1 位，0..nAll）
                    int ins = 0;
                    for (int j = 0; j < nAll; j++) {
                        float center = top[j] - rs.slotHeightWithPreview(j) / 2f;
                        if (sy > center) { ins = j; break; }
                        ins = j + 1;
                    }
                    ins = Mathf.clamp(ins, 0, nAll);
                    float previewBottom;
                    if (phantIdx >= 0 && (ins == phantIdx || ins == phantIdx + 1)) {
                        // 悬停在来源空槽位区域：灰框画在 phantom 处（drop 后新框的落点），白框一律不动
                        float ph = rs.slotHeightWithPreview(phantIdx);
                        previewBottom = top[phantIdx] - (ph - BTN_H) / 2f - BTN_H;
                    } else if (ins >= nAll) {
                        // 追加到最下方：各框不动，灰色框紧贴最后一个白框下方（留标准框距 GAP）
                        float lastBottom = top[nAll - 1] - rs.slotHeightWithPreview(nAll - 1);
                        previewBottom = lastBottom - GAP - BTN_H;
                    } else if (ins == 0) {
                        // 插到最顶部之上：白框不动，灰色框浮在最顶白框上方（留标准框距 GAP）
                        previewBottom = baseTop + GAP;
                    } else {
                        // 两可见框之间：下方各框整体下移，使灰框上下各留一个「白/白」标准框距(GAP)。
                        // gray 顶 = upperBottom - GAP，底 = upperBottom - GAP - BTN_H；
                        // 下框原位于 upperBottom - GAP，让位后应位于 gray 底 - GAP = upperBottom - 2*GAP - BTN_H，
                        // 故下移量 = (2*GAP + BTN_H) - GAP = GAP + BTN_H。
                        // stage y 向上为增：top 减 shift 才是「向下推」（曾误写成 += 导致交错）。
                        float upperBottom = top[ins - 1] - rs.slotHeightWithPreview(ins - 1);
                        float shift = GAP + BTN_H;
                        for (int j = ins; j < nAll; j++) {
                            top[j] -= shift;
                        }
                        previewBottom = upperBottom - GAP - BTN_H;
                    }
                    placeVisByTop(top);
                    showHintBox(fixedHintCenterX(), previewBottom, BTN_H, buttonWidth());
                    if (lastDbgBoxIns != ins) {
                        lastDbgBoxIns = ins;
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < nAll; j++) {
                            float ay = rs.slotBoxes.get(j).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                            sb.append('b').append(j).append('=').append((int) ay).append(' ');
                        }
                        Log.info("[UJDBG] boxReflow nAll=@ phant=@ ins=@ previewBottom=@ baseTop=@ | @",
                                nAll, phantIdx, ins, (int) previewBottom, (int) boxBaseTop, sb);
                    }
                    if (ghost != null) ghost.toFront();
                }

                /** 按各白框基准 top(stage) 设定预览目标坐标（slotLayer 每帧 layout 会重置 setPosition，
                 * 故由 SlotBox.draw 前重新应用；含 hidden 的 phantom 框，设定无害） */
                private void placeVisByTop(float[] top) {
                    // 用当前 slotLayer 原点（来源框收缩后 slotLayer 会重新居中，拖拽快照 slotBase 会过期）
                    float curSlotY = rs.slotLayer.localToStageCoordinates(Tmp.v2.set(0f, 0f)).y;
                    for (int j = 0; j < rs.slotBoxes.size; j++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(j);
                        float hh = rs.slotHeightWithPreview(j);
                        b.dragPreviewX = b.x;
                        b.dragPreviewY = (top[j] - hh) - curSlotY;
                    }
                }

                /** 把白框还原到基准堆叠（清除预览目标，让布局重新接管；配合插入重排后的复原） */
                private void restoreBoxPristine() {
                    if (!boxReflowActive) return;
                    boxReflowActive = false;
                    rs.clearDragPreview();
                }

                /** 还原来源框内按钮到基准位置（离开框内移动或落下前调用） */
                private void resetInBoxReflow() {
                    if (!inBoxReflowActive) return;
                    inBoxReflowActive = false;
                    if (srcBtnBaseBottoms == null || srcBoxIdx < 0 || srcBoxIdx >= rs.slotBoxes.size) return;
                    RegionState.SlotBox box = rs.slotBoxes.get(srcBoxIdx);
                    Seq<Direction> c = rs.slotContents.get(srcBoxIdx);
                    if (c.size != srcBtnBaseBottoms.length) return;
                    for (int i = 0; i < c.size; i++) {
                        Direction d = c.get(i);
                        Vec2 l = box.content.stageToLocalCoordinates(
                                Tmp.v1.set(0f, srcBtnBaseBottoms[i]));
                        d.setPosition(d.x, l.y);
                    }
                }

                /** 灰色占位框的水平中心：固定对齐黄色按钮列（stage 坐标），只随鼠标上下移动，水平不动 */
                private float fixedHintCenterX() {
                    float x = Direction.this.localToStageCoordinates(Tmp.v1.set(0f, 0f)).x;
                    return x + Direction.this.getWidth() / 2f;
                }

                private float buttonWidth() {
                    float w = Direction.this.getWidth();
                    return w > 0 ? w : rs.column.getWidth() - 30f;
                }

                /** 绘制/更新按钮等大灰色占位框（stage 坐标） */
                private void showHintBox(float centerX, float bottomY, float h, float w) {
                    if (hint != null && Math.abs(hint.x - (centerX - w / 2f)) < 1f
                            && Math.abs(hint.y - bottomY) < 1f) return;
                    if (hint != null) hint.remove();
                    hint = new Table();
                    hint.background(Tex.whitePane);
                    hint.setColor(Color.gray);
                    hint.setSize(w, h);
                    hint.touchable = Touchable.disabled;
                    hint.setPosition(centerX - w / 2f, bottomY);
                    Core.scene.root.addChild(hint);
                    if (ghost != null) ghost.toFront();
                }

                private void clearRootHint() {
                    if (hint != null) {
                        hint.remove();
                        hint = null;
                    }
                }

                private void removeHint() {
                    clearRootHint();
                    // 清除所有白框的占位并重建（逐框复原，去掉占位恢复原大小，不整层重建）
                    for (int i = 0; i < rs.slotBoxes.size; i++) {
                        RegionState.SlotBox b = rs.slotBoxes.get(i);
                        if (b.previewInsert != -1) {
                            b.previewInsert = -1;
                            rs.rebuildSlotContents(i);
                            rs.setSlotHeight(i);
                        }
                    }
                }
            });
        }

        private boolean dragStart(InputEvent event) {
            if (build == null) return false;
            draggingButton = true;
            resetDragState();
            ghost = new Table();
            ghost.background(SOLID); // 不透明实心填充：tint 成边框色/亮黄
            ghost.setColor(BTN_BORDER);
            ghost.margin(0f);
            ghost.table(SOLID, t -> {
                t.color.set(BTN_FACE);
                t.margin(6f);
                t.touchable = Touchable.disabled;
                t.add("@universal-junction.dir" + dir).style(Styles.outlineLabel).color(Color.white)
                        .update(l -> l.setFontScale(BTN_TEXT_SCALE)).grow().labelAlign(Align.center);
            }).grow().pad(2f);
            ghost.setSize(getWidth(), getHeight());
            ghost.touchable = Touchable.disabled;
            ghost.setPosition(event.stageX - getWidth() / 2f, event.stageY - getHeight() / 2f);
            Core.scene.root.addChild(ghost);
            toFront();
            ghost.toFront();
            visible = false;
            // 拖出唯一按钮时隐藏来源白框（松手时恢复）
            for (int i = 0; i < rs.slotBoxes.size; i++) {
                if (rs.slotContents.get(i).contains(Direction.this) && rs.slotContents.get(i).size == 1) {
                    RegionState.SlotBox box = rs.slotBoxes.get(i);
                    box.visible = false;
                    Direction.this.srcSlotBox = box;
                    break;
                }
            }
            // 快照白框基准堆叠几何（空白/新建槽位预览用，与整框拖动同口径）
            if (rs.slotBoxes.size > 0) {
                boxBaseTop = rs.slotBoxes.get(0).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y
                        + rs.slotHeight(0);
                slotBase = rs.slotLayer.localToStageCoordinates(Tmp.v2.set(0f, 0f)).cpy();
            }
            // 快照来源框内按钮底边（框内排序预览用）
            for (int i = 0; i < rs.slotBoxes.size; i++) {
                if (rs.slotContents.get(i).contains(Direction.this)) {
                    srcBoxIdx = i;
                    Seq<Direction> c = rs.slotContents.get(i);
                    srcBtnBaseBottoms = new float[c.size];
                    for (int j = 0; j < c.size; j++) {
                        srcBtnBaseBottoms[j] = c.get(j).localToStageCoordinates(Tmp.v1.set(0f, 0f)).y;
                    }
                    break;
                }
            }
            return true;
        }

        /** 清空本轮拖拽的预览状态（新拖拽开始或拖拽结束时） */
        private void resetDragState() {
            boxReflowActive = false;
            inBoxReflowActive = false;
            srcBoxIdx = -1;
            srcBtnBaseBottoms = null;
            rs.clearDragPreview();
            // 清除所有白框的实时收缩标记（落点重排后按真实内容重建高度）
            for (int i = 0; i < rs.slotBoxes.size; i++) {
                RegionState.SlotBox b = rs.slotBoxes.get(i);
                if (b.vacating) {
                    b.setVacating(Direction.this, false);
                    rs.setSlotHeight(i);
                }
            }
        }

        private void clearGhost() {
            draggingButton = false;
            if (ghost != null) {
                ghost.remove();
                ghost = null;
            }
        }

        private boolean inRect(Table t, float sx, float sy) {
            if (t == null) return false;
            Vec2 v = t.localToStageCoordinates(Tmp.v1.set(0f, 0f));
            float x = v.x, y = v.y;
            return sx >= x && sx <= x + t.getWidth() && sy >= y && sy <= y + t.getHeight();
        }

        @Override
        public void draw() {
            float pad = 5f;
            Fill.dropShadow(x + width / 2f, y + height / 2f, width + pad, height + pad, 10f, 0.9f * parentAlpha);

            // 实心绘制（不透明，不受半透明面板纹理影响）：深棕金边框 + 亮黄色核心
            Draw.color(BTN_BORDER);
            Fill.crect(x, y, width, height);
            Draw.color(BTN_FACE);
            Fill.crect(x + 2f, y + 2f, width - 4f, height - 4f);
            Draw.reset();

            super.draw();
        }
    }
}
