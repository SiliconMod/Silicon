package silicon.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.ScissorStack;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.util.Align;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.TextButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

/**
 * 常驻悬浮音乐控制条（默认屏幕右下角，可拖动到任意位置）。
 * - 收起态：单一音符图标按钮（Icon.music），点击展开为完整控制条。
 * - 展开态：播放/暂停、曲名、专辑作用域、进度条、下一曲、收起按钮。
 * 由于触发顺序，scene 切换（进/出游戏、菜单）会重建 scene 并清空 root，
 * 因此每帧检查条是否仍在当前 scene，脱落后自动重建。全局仅一个实例。
 * 位置在拖动后持久化到设置，跨会话记忆；按钮均用 iconfont / bundle 文字，避免 unicode 符号缺字。
 */
public class MusicBar {
    private static final String CFG_X = "musicbar.pos.x";
    private static final String CFG_Y = "musicbar.pos.y";
    private static final String CFG_COLLAPSED = "musicbar.collapsed";
    private static Table bar;
    private static boolean collapsed = true;

    private MusicBar() {}

    public static void init() {
        collapsed = Core.settings.getBool(CFG_COLLAPSED, true);
        Events.run(EventType.Trigger.update, () -> {
            if (Core.scene == null || Core.scene.root == null) {
                bar = null;
                return;
            }
            if (bar != null && !bar.isDescendantOf(Core.scene.root)) {
                // scene 未真正变化但条被其它 UI 从 root 摘除：移除遗留控件并置空，避免孤儿 → 重复新建出新实例
                bar.remove();
                bar = null;
            }
            // 仅在游戏中且有玩家实体时显示（避免主菜单 player==null 时误播 NPE）
            if (!mindustry.Vars.state.isGame() || mindustry.Vars.player == null || !MusicPlayer.isEnabled()) {
                if (bar != null) {
                    bar.remove();
                    bar = null;
                }
                return;
            }
            if (bar == null) {
                build();
            }
        });
    }

    private static void build() {
        bar = new Table();
        // 悬浮条美观：深灰面板+8px圆角外边距，半透明但对比度更高，适配亮/暗地图
        bar.background(Styles.grayPanel);
        bar.margin(4f);

        if (collapsed) {
            // 收起态：播放中显示暂停、暂停中显示播放，颜色随状态高亮，悬停显示曲名
            ImageButton btn = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, Styles.cleari);
            btn.resizeImage(Scl.scl(26f));
            btn.update(() -> {
                btn.getImage().setDrawable(MusicPlayer.isPlaying() ? Icon.pause : Icon.play);
                btn.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            });
            btn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add(trackLabel())));
            bar.add(btn).size(Scl.scl(44f));
            makeDraggable(btn, () -> {
                collapsed = false;
                Core.settings.put(CFG_COLLAPSED, false);
                detach();
            });
            bar.pack();
        } else {
            // 展开态：紧凑图标控制排 + 曲名 + 进度条；拖动把手移动整条
            ImageButton grip = new ImageButton(Icon.move, Styles.flati);
            grip.resizeImage(Scl.scl(20f));
            grip.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("拖动移动")) );
            bar.add(grip).size(Scl.scl(32f)).pad(1f);
            makeDraggable(grip);

            iconBtn(bar, Icon.leftOpen, MusicPlayer::prev).pad(1f);

            // 快退（相对 -10s）
            iconBtn(bar, Icon.leftSmall, () -> MusicPlayer.seekRelative(-10f)).pad(1f);

            // 播放/暂停
            ImageButton play = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, Styles.flati);
            play.resizeImage(Scl.scl(22f));
            play.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            play.clicked(() -> {
                if (MusicPlayer.isPlaying()) MusicPlayer.pause(); else MusicPlayer.resume();
                // 点击即同步图标/颜色（不依赖下一帧 update 才切换）
                syncPlayButton(play);
            });
            // 每帧同步图标到当前播放态（兜底异步建源/暂停/停止路径，悬浮窗停止/开始按钮切换修复）
            playButtonFrameSync(play);
            bar.add(play).size(Scl.scl(40f)).pad(1f);

            // 快进（相对 +10s）
            iconBtn(bar, Icon.rightSmall, () -> MusicPlayer.seekRelative(10f)).pad(1f);

            iconBtn(bar, Icon.rightOpen, MusicPlayer::next).pad(1f);

            // 倍速快捷循环按钮（覆盖 1/16–16x 对数档的常用子集）：0.25 / 0.5 / 1 / 1.5 / 2 / 4 / 8；固定宽度完整显示
            final float[] speeds = {0.25f, 0.5f, 1f, 1.5f, 2f, 4f, 8f};
            TextButton speedBtn = new TextButton(speedLabel(), Styles.flatBordert);
            speedBtn.getLabel().setWrap(false);
            speedBtn.getLabel().setEllipsis(false);
            speedBtn.getLabel().setFontScale(Scl.scl(0.9f));
            speedBtn.setColor(Pal.accent);
            speedBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换倍速")));
            final String[] lastSpeed = {speedLabel()};
            speedBtn.update(() -> {
                String lbl = speedLabel();
                if (!lastSpeed[0].equals(lbl)) { lastSpeed[0] = lbl; speedBtn.setText(lbl); }
            });
            speedBtn.clicked(() -> {
                float cur = MusicPlayer.speed();
                float next = speeds[0];
                for (float s : speeds) if (s > cur + 0.01f) { next = s; break; }
                MusicPlayer.setSpeed(next);
                speedBtn.setText(speedLabel());
            });
            bar.add(speedBtn).width(Scl.scl(76f)).height(Scl.scl(30f)).pad(1f);

            // 专辑作用域切换按钮：点按在「全部曲目」与各专辑间轮换；长按/双击由设置页管理
            TextButton albumBtn = new TextButton(albumScopeLabel(), Styles.flatBordert);
            albumBtn.getLabel().setWrap(false);
            albumBtn.getLabel().setEllipsis(true);
            albumBtn.getLabel().setFontScale(Scl.scl(0.9f));
            albumBtn.setColor(Pal.accent);
            albumBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换专辑")));
            final String[] lastScope = {albumScopeLabel()};
            albumBtn.update(() -> {
                String lbl = albumScopeLabel();
                if (!lastScope[0].equals(lbl)) { lastScope[0] = lbl; albumBtn.setText(lbl); }
            });
            albumBtn.clicked(() -> {
                cycleAlbumScope();
                lastScope[0] = albumScopeLabel();
                albumBtn.setText(lastScope[0]);
            });
            bar.add(albumBtn).width(Scl.scl(112f)).height(Scl.scl(30f)).pad(1f);

            // 循环模式快捷按钮：点击在 6 种模式间循环。固定宽度（不等长文本切换不导致按钮忽大忽小/点小/换行），
            // 文案已改为等长的两字中文（关闭/列表/单曲/乱序/单停/随机），配合字号在固定格内完整显示不省略
            TextButton loopBtn = new TextButton(loopModeLabel(), Styles.flatBordert);
            loopBtn.getLabel().setWrap(false);
            loopBtn.getLabel().setEllipsis(false);
            loopBtn.getLabel().setFontScale(Scl.scl(0.9f));
            loopBtn.setColor(Pal.accent);
            loopBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换循环模式")));
            final String[] lastLoop = {loopModeLabel()};
            loopBtn.update(() -> {
                String lbl = loopModeLabel();
                if (!lastLoop[0].equals(lbl)) { lastLoop[0] = lbl; loopBtn.setText(lbl); }
            });
            loopBtn.clicked(() -> {
                MusicPlayer.cycleLoopMode();
                lastLoop[0] = loopModeLabel();
                loopBtn.setText(lastLoop[0]);
            });
            bar.add(loopBtn).width(Scl.scl(64f)).height(Scl.scl(30f)).pad(1f);

            // 设置按钮：打开音乐播放器设置页
            iconBtn(bar, Icon.settings, MusicPlayerDialog::open).pad(1f);

            // 收起（最小值化）
            iconBtn(bar, Icon.down, () -> { collapsed = true; Core.settings.put(CFG_COLLAPSED, true); detach(); }).pad(1f);

            bar.row();
            // 曲名 + 当前/总时长：内嵌横向 Table，growX 铺满整条固定宽度 → 长曲名在条内滚动裁剪、不拉长整条
            Table infoRow = new Table();
            MarqueeLabel track = new MarqueeLabel(trackLabel(), Styles.outlineLabel);
            track.setColor(Color.white);
            track.maxPref = Scl.scl(900f);
            track.clicked(() -> MusicPlayerDialog.open());
            final String[] lastTrack = {trackLabel()};
            track.update(() -> {
                String lbl = trackLabel();
                if (!lastTrack[0].equals(lbl)) { lastTrack[0] = lbl; track.setText(lbl); }
            });
            // 进度条需与时间标签共享拖动状态：创建时间标签前先创建滑杆实例，时间标签拖动时预览拖动位置
            final arc.scene.ui.Slider previewSlider = seekSlider();
            // 曲名 growX 占满剩余（MarqueeLabel 的 this.width 即剩余宽，超过才滚动）；右侧固定时长标签
            infoRow.add(track).growX().left();
            final arc.scene.ui.Label timeLbl = new arc.scene.ui.Label("0:00 / 0:00", Styles.outlineLabel);
            timeLbl.setColor(Color.white);
            timeLbl.setFontScale(Scl.scl(0.75f));
            timeLbl.update(() -> {
                float len = MusicPlayer.trackLength();
                float cur;
                if (previewSlider.isDragging() && len > 0f && len < 12f * 3600f) {
                    cur = previewSlider.getValue() * len;
                } else {
                    cur = MusicPlayer.currentTime();
                }
                String s = formatFloaterTime(cur, len);
                if (!s.equals(timeLbl.getText().toString())) timeLbl.setText(s);
            });
            infoRow.add(timeLbl).padLeft(8f).width(Scl.scl(96f)).right();
            // 行高给足（40f 提升可读性），配合 MarqueeLabel 垂直居中
            bar.add(infoRow).growX().pad(2f, 6f, 2f, 6f).colspan(11).left().height(Scl.scl(40f));

            bar.row();
            // 进度条（独立一行，加高并上下留白，避免滑杆圆钮越界遮挡上方曲名/按钮文字）
            bar.add(previewSlider).growX().height(Scl.scl(24f)).colspan(11).pad(3f, 6f, 3f, 6f);
            // 展开态多了一行 → 需要多 rebuild 一次，交给 update 的空重建逻辑
        }

        bar.pack();
        if (collapsed) {
            // 收起态固定窄宽
            bar.setSize(Scl.scl(44f), bar.getPrefHeight());
        } else {
            // 展开态固定整条宽度：曲名/进度行以 colspan 铺满，长曲名在此固定宽内滚动裁剪，
            // 不再随内容 pack 伸缩导致「歌名过长不循环而直接超出条右沿」
            bar.setSize(Scl.scl(600f), bar.getPrefHeight());
        }

        // 位置：优先记忆拖拽位置；首次使用则取默认右下角
        float x = Core.settings.has(CFG_X) ? Core.settings.getFloat(CFG_X) : Core.graphics.getWidth() - bar.getWidth() - Scl.scl(10f);
        float y = Core.settings.has(CFG_Y) ? Core.settings.getFloat(CFG_Y) : Scl.scl(16f);
        bar.setPosition(x, y);
        // 屏幕尺寸变化后夹取在可视范围内（重置位置后/窗口缩放后不至于把条夹到屏幕外导致长度观感异常）
        moveBar(0f, 0f);

        Core.scene.root.addChild(bar);
    }

    /** 紧凑图标按钮（悬浮条用）：返回并以 Cell.pad 收尾以便链式调整间距 */
    private static Cell iconBtn(Table parent, arc.scene.style.Drawable icon, Runnable action) {
        ImageButton b = new ImageButton(icon, Styles.cleari);
        b.resizeImage(Scl.scl(18f));
        b.clicked(action);
        return parent.add(b).size(Scl.scl(32f));
    }

    /** 同步播放/暂停按钮图标颜色到当前 isPlaying 状态（悬浮窗停止/开始按钮切换修复） */
    private static void syncPlayButton(ImageButton btn) {
        boolean p = MusicPlayer.isPlaying();
        if (btn.getImage() == null) return;
        btn.getImage().setDrawable(p ? Icon.pause : Icon.play);
        btn.getImage().setColor(p ? Pal.accent : Color.white);
    }

    /** 每帧把播放态同步到按钮图标（相比只在变化时更新，能兜底一切异步建源/暂停/停止路径） */
    private static void playButtonFrameSync(ImageButton btn) {
        btn.update(() -> syncPlayButton(btn));
    }

    /** 悬浮条/弹窗共用的可拖动进度条（内联 update+changed 监听，返回构造好的 Slider）；
     *  带 A-B 区间高亮带：进度条上半透明色带标出 [A,B] 区间，便于区间重复可视化。
     *  抖动修复：只在「非拖动」且「目标值与上次显示值差异超过阈值(±0.0005)」时才 setValue，
     *  避免每帧原地重设同一/近似值导致滑杆指针反复跳动。 */
    private static arc.scene.ui.Slider seekSlider() {
        arc.scene.ui.Slider seekBar = new AbSlider();
        seekBar.setDisabled(true);
        final boolean[] userSeek = {false};
        final float[] lastShown = {Float.NEGATIVE_INFINITY};
        seekBar.update(() -> {
            float len = MusicPlayer.trackLength();
            // 修复：未知长度(-1)或异常大值(>12h)均禁用拖动，防止外部歌曲进度跳到超高值
            boolean ok = len > 0f && len < 12f * 3600f;
            seekBar.setDisabled(!ok);
            if (ok && !userSeek[0] && !seekBar.isDragging()) {
                float target = MusicPlayer.currentTime() / len;
                if (Math.abs(target - lastShown[0]) > 0.0005f) {
                    userSeek[0] = true;
                    seekBar.setValue(target);
                    lastShown[0] = target;
                    userSeek[0] = false;
                }
            }
        });
        seekBar.changed(() -> {
            float len = MusicPlayer.trackLength();
            if (!userSeek[0] && len > 0f && len < 12f * 3600f) {
                userSeek[0] = true;
                MusicPlayer.seek(seekBar.getValue() * len);
                lastShown[0] = seekBar.getValue();
                userSeek[0] = false;
            }
        });
        return seekBar;
    }

    /** 带 A-B 区间高亮带的进度滑块：在轨道上叠一层半透明色带标出区间范围（无区间时不画） */
    static class AbSlider extends arc.scene.ui.Slider {
        AbSlider() {
            super(0f, 1f, 0.001f, false);
        }

        @Override
        public void draw() {
            if (MusicPlayer.hasAb()) {
                float len = MusicPlayer.trackLength();
                if (len > 0f) {
                    float lo = Math.min(MusicPlayer.abA(), MusicPlayer.abB()) / len;
                    float hi = Math.max(MusicPlayer.abA(), MusicPlayer.abB()) / len;
                    float w = this.x + this.width;
                    float x0 = this.x + Scl.scl(4f) + lo * (w - this.x - Scl.scl(8f));
                    float x1 = this.x + Scl.scl(4f) + hi * (w - this.x - Scl.scl(8f));
                    float mid = this.y + this.height / 2f;
                    float th = Math.max(Scl.scl(3f), Math.min(Scl.scl(6f), this.height * 0.5f));
                    Draw.color(Pal.accent, 0.55f);
                    Fill.crect(x0, mid - th / 2f, x1 - x0, th);
                    Draw.reset();
                }
            }
            super.draw();
        }
    }

    /**
     * 长曲名「循环显示」滚动标签：文本超出自身宽度时自动横向滚动（无缝回绕），
     * 超出则剪裁到自身区域；文本长度未超出时不滚动、行为等同普通 Label。
     * - maxPref（0=不限）：限制 getPrefWidth 的上限，使 cell 占满可用宽度又不把 Table 撑长，
     *   且只有文本真的超过「可用宽度」才开始滚动（解决「长度足够却仍滚动」的问题）。
     * - 「有空间还循环」修复：滚动判定与周期一律用「真实文本宽」realWidth()（super.getPrefWidth()），
     *   而不是被 maxPref 截断后的 getPrefWidth()——否则 maxPref<真宽时周期按截断值算，
     *   副本重叠/截断造成「名字不全」，且长名仍触发滚动。
     * - 滚动间隙加大，进入副本与离开副本之间存在干净空白，避免相邻被遮挡。
     */
    static class MarqueeLabel extends arc.scene.ui.Label {
        public float maxPref = 0f;
        private float scroll = 0f;
        private String lastKey = "";
        /** 滚动回绕间隙（36dp 平衡可读与紧凑） */
        private static final float GAP = 36f;

        MarqueeLabel(CharSequence text, LabelStyle style) {
            super(text, style);
            setWrap(false);
            setEllipsis(false);
            // 水平左对齐 + 垂直居中：在固定的行高内文本垂直居中显示，避免行高偏紧时文本上半部分被剪裁
            setAlignment(Align.left | Align.center);
        }

        @Override
        public float getPrefWidth() {
            float p = super.getPrefWidth();
            return maxPref > 0f ? Math.min(p, maxPref) : p;
        }

        /** 真实文本渲染宽（不经过 maxPref 截断），用于滚动周期/判定，保证长名完整循环 */
        private float realWidth() {
            return super.getPrefWidth();
        }

        private String textKey() {
            return this.text == null ? "" : this.text.toString();
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (Float.isNaN(delta) || Float.isInfinite(delta)) return;
            delta = Math.min(delta, 0.1f);
            if (Float.isNaN(scroll) || Float.isInfinite(scroll)) scroll = 0f;
            String key = textKey();
            if (!lastKey.equals(key)) { lastKey = key; scroll = 0f; }
            float cw = this.width;
            float gw = Scl.scl(8f);
            float real = realWidth();
            if (cw > Scl.scl(1f) && real > cw + gw) {
                // 真实文本溢出 → 以「真实文本宽 + 间隙」为周期持续滚动，实现无缝全名循环
                scroll = (scroll + delta * Scl.scl(36f)) % (real + Scl.scl(GAP));
            } else {
                scroll = 0f;
            }
        }

        @Override
        public void draw() {
            float cw = this.width;
            float tw = realWidth();
            if (cw <= Scl.scl(1f) || tw <= cw + Scl.scl(8f) || scroll == 0f) {
                super.draw();
                return;
            }
            float ox = this.x;
            float period = tw + Scl.scl(GAP);
            float ph = scroll % period;
            Draw.flush();
            boolean clipped = ScissorStack.push(new Rect(this.x, this.y, cw, this.height));
            try {
                this.x = ox - ph;
                super.draw();
                this.x = ox - ph + period;
                super.draw();
            } finally {
                this.x = ox;
                Draw.flush();
                if (clipped) ScissorStack.pop();
                Draw.flush();
            }
        }
    }

    /** 给元素挂拖动监听（用于收起态音符按钮：既能拖动也能单击展开）：
     *  - 用普通 addListener 并在 touchDown 返回 true 取得触摸焦点（而非 addCaptureListener），
     *    确保能可靠收到 touchDragged/touchUp，也不会干扰其它按钮的点击。
     *  - 拖动时移动整个 control bar（target 本身）。
     *  - 若最终未发生位移（单击），触发 onClick 回调（展开）。
     */
    private static void makeDraggable(Element target, Runnable onClick) {
        target.addListener(new InputListener() {
            float lastX, lastY;
            float startX, startY;
            boolean dragged;

            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                lastX = e.stageX;
                lastY = e.stageY;
                startX = e.stageX;
                startY = e.stageY;
                dragged = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer) {
                float dx = e.stageX - lastX, dy = e.stageY - lastY;
                if (dx != 0f || dy != 0f) dragged = true;
                moveBar(dx, dy);
                lastX = e.stageX;
                lastY = e.stageY;
            }

            @Override
            public void touchUp(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                if (bar == null) return;
                Core.settings.put(CFG_X, bar.x);
                Core.settings.put(CFG_Y, bar.y);
                // 未拖动（位移小于阈值）才视为单击
                if (!dragged && Math.abs(e.stageX - startX) < Scl.scl(5f) && Math.abs(e.stageY - startY) < Scl.scl(5f)) {
                    onClick.run();
                }
            }
        });
    }

    /** 给拖动把手挂拖动监听：touchDown 返回 true 取得焦点，移动整条并持久化位置 */
    private static void makeDraggable(Element target) {
        target.addListener(new InputListener() {
            float lastX, lastY;

            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                lastX = e.stageX;
                lastY = e.stageY;
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer) {
                moveBar(e.stageX - lastX, e.stageY - lastY);
                lastX = e.stageX;
                lastY = e.stageY;
            }

            @Override
            public void touchUp(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                if (bar == null) return;
                Core.settings.put(CFG_X, bar.x);
                Core.settings.put(CFG_Y, bar.y);
            }
        });
    }

    /** 移动控制条并夹取在屏幕内，保证至少部分可见、不会被拖出屏幕外 */
    private static void moveBar(float dx, float dy) {
        if (bar == null) return;
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        bar.x = Mathf.clamp(bar.x + dx, -bar.getWidth() + Scl.scl(20f), w - Scl.scl(20f));
        bar.y = Mathf.clamp(bar.y + dy, Scl.scl(16f), h - Scl.scl(16f));
    }

    /** 移除当前控制条：下次 update 依据 collapsed 重建 */
    private static void detach() {
        if (bar == null) return;
        bar.remove();
        bar = null;
    }

    /** 重置悬浮条位置到默认右下角（清除记忆位置设置） */
    public static void resetPosition() {
        Core.settings.remove(CFG_X);
        Core.settings.remove(CFG_Y);
        if (bar != null) {
            bar.remove();
            bar = null;
        }
    }

    private static String trackLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) {
            try { String v = Core.bundle.get("musicplayer.none"); return v != null && !v.contains("??") ? v : "none"; } catch (Exception e) { return "none"; }
        }
        String safe = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
        if (MusicPlayer.isPlaying()) return "[accent]> " + safe;
        return safe;
    }

    private static String albumScopeLabel() {
        String active = MusicPlayer.activeAlbum();
        if (active == null) {
            try { String v = Core.bundle.get("musicplayer.allAlbums"); return v != null && !v.contains("??") ? v : "all"; } catch (Exception e) { return "all"; }
        }
        // 问题3：不显示「专辑：」前缀，按钮直接显示专辑名，避免冗余前缀
        return active;
    }

    /** 悬浮条当前/总时长文本；未知时长显示 0:00 */
    private static String formatFloaterTime(float cur, float len) {
        if (Float.isNaN(cur) || Float.isInfinite(cur)) cur = 0f;
        if (Float.isNaN(len) || Float.isInfinite(len)) len = -1f;
        return MusicPlayer.formatTimeSimple(cur) + " / " + (len > 0f ? MusicPlayer.formatTimeSimple(len) : "--:--");
    }

    /** 悬浮条倍速按钮文字：当前倍速（整数倍省略小数，小数为两位） */
    private static String speedLabel() {
        float s = MusicPlayer.speed();
        if (Math.abs(s - 1f) < 0.001f) return "1x";
        if (Math.abs(s - Math.round(s)) < 0.001f) return Math.round(s) + "x";
        return String.format(java.util.Locale.US, "%.2fx", s);
    }

    /** 悬浮条循环按钮文字：当前循环模式（6 种中文文案，缺键回退） */
    private static String loopModeLabel() {
        try { String v = Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()); return v != null && !v.contains("??") ? v : ("loop" + MusicPlayer.loopMode()); } catch (Exception e) { return "loop" + MusicPlayer.loopMode(); }
    }

    /** 在「全部曲目」与各专辑间轮换激活专辑作用域（点按悬浮条专辑按钮） */
    private static void cycleAlbumScope() {
        arc.struct.Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        String active = MusicPlayer.activeAlbum();
        if (albums.size == 0) return;
        int idx = -1;
        if (active != null) {
            for (int i = 0; i < albums.size; i++) {
                if (active.equals(albums.get(i).name)) { idx = i; break; }
            }
        }
        // idx==-1（全部或无匹配）→ 切到第 0 个专辑；否则切到下一个，末尾切回「全部」
        String next = (idx == -1) ? albums.get(0).name : (idx + 1 < albums.size ? albums.get(idx + 1).name : null);
        MusicPlayer.setActiveAlbum(next);
    }
}
