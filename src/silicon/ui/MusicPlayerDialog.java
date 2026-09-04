package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.audio.MusicNetwork;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

import static mindustry.Vars.ui;

/**
 * 音乐播放器弹窗：完整的播放控制 + 曲目库管理。
 * 入口：设置菜单「音乐播放器」按钮、快捷键、悬浮条展开。
 */
public class MusicPlayerDialog extends BaseDialog {
    private static MusicPlayerDialog instance;

    private Table trackTable;
    /** 当前专辑筛选（null = 全部曲目） */
    private String filterAlbum = null;
    private String filterText = "";

    public static void open() {
        if (instance == null || !instance.isShown() || instance.getScene() != Core.scene) {
            instance = new MusicPlayerDialog();
        }
        instance.show();
        instance.rebuild();
    }

    /** 下载完成/分块收齐后刷新曲目信息（时长/大小）显示：弹窗开着则重建曲目行，无需重开弹窗 */
    public static void refreshIfOpen() {
        if (instance != null && instance.isShown() && instance.getScene() == Core.scene) {
            instance.rebuildRows();
        }
    }

    private MusicPlayerDialog() {
        super(Core.bundle.get("musicplayer.title"));
        closeOnBack();
    }

    private void rebuild() {
        cont.clearChildren();
        cont.top();

        // —— 顶部「现在播放」面板（实时刷新：状态/曲名随播放变化自动更新） ——
        cont.table(now -> {
            now.background(Styles.grayPanel);
            now.margin(12f);
            now.defaults().top();

            // 左侧：状态图标圆盘
            final arc.scene.ui.Image[] disc = new arc.scene.ui.Image[1];
            now.table(d -> {
                d.background(Styles.grayPanelDark);
                d.margin(9f);
                disc[0] = new arc.scene.ui.Image(MusicPlayer.isPlaying() ? Icon.pause : Icon.play);
                disc[0].setSize(Scl.scl(24f));
                disc[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                d.add(disc[0]).size(Scl.scl(24f)).color(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
            }).padRight(14f);

            // 右侧：状态标题 + 曲名
            final arc.scene.ui.Label[] stateLbl = new arc.scene.ui.Label[1];
            final MusicBar.MarqueeLabel[] nameLbl = new MusicBar.MarqueeLabel[1];
            now.table(info -> {
                info.defaults().left();
                stateLbl[0] = new arc.scene.ui.Label(MusicPlayer.isPlaying()
                                ? Core.bundle.get("musicplayer.playing")
                                : Core.bundle.get("musicplayer.play"),
                        Styles.outlineLabel);
                stateLbl[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                info.add(stateLbl[0]);
                info.row();
                nameLbl[0] = new MusicBar.MarqueeLabel(nowPlayingLabel(), Styles.outlineLabel);
                nameLbl[0].setColor(MusicPlayer.isPlaying() ? Color.white : Color.lightGray);
                // 自适应宽度：占满「现在播放」面板剩余空间；maxPref 是滚动触发的上限（需 > 实际可用宽，
                // 否则 growX 后 this.width>pref 永不滚动），文本真正超过可用宽才开始循环显示。
                // 给足高度 + MarqueeLabel 内部垂直居中，避免名称上半部分被裁切
                nameLbl[0].maxPref = Scl.scl(900f);
                info.add(nameLbl[0]).growX().height(Scl.scl(36f)).padRight(6f);
            }).growX();
            // 每帧刷新状态与曲名（悬浮条/自动推进切换曲目时这里也跟着变）；仅内容变化时 setText 避免反复重排
            final String[] lastNow = {""};
            now.update(() -> {
                boolean playing = MusicPlayer.isPlaying();
                String key = (playing ? "P" : "S") + "|" + nowPlayingLabel();
                if (key.equals(lastNow[0])) return;
                lastNow[0] = key;
                disc[0].setDrawable(playing ? Icon.pause : Icon.play);
                disc[0].setColor(playing ? Pal.accent : Color.lightGray);
                stateLbl[0].setText(playing ? Core.bundle.get("musicplayer.playing") : Core.bundle.get("musicplayer.play"));
                stateLbl[0].setColor(playing ? Pal.accent : Color.lightGray);
                nameLbl[0].setText(nowPlayingLabel());
                nameLbl[0].setColor(playing ? Color.white : Color.lightGray);
            });
        }).growX().padBottom(8f).row();

        // —— 播放进度条（可拖动选进度） ——
        cont.table(seek -> {
            seek.background(Styles.grayPanelDark);
            seek.margin(4f, 8f, 4f, 8f);
            seek.defaults().left();
            final arc.scene.ui.Label time = new arc.scene.ui.Label("0:00 / 0:00", Styles.outlineLabel);
            time.setColor(Color.white);
            seek.add(time).growX();
            Slider seekBar = new MusicBar.AbSlider();
            seekBar.setDisabled(true);
            final boolean[] userSeek = {false};
            final float[] lastShown = {Float.NEGATIVE_INFINITY};
            seekBar.update(() -> {
                float len = MusicPlayer.trackLength();
                // 修复：未知长度(-1)或异常大值(>12h)均禁用拖动，防止外部歌曲进度跳到超高值
                boolean hasLen = len > 0f && len < 12f * 3600f;
                // 已知时长即可拖动（播放/暂停皆可）；拖动中不刷新值避免回跳摇动，拖动时时间标签预览拖动位置
                seekBar.setDisabled(!hasLen);
                if (hasLen) {
                    float cur = seekBar.isDragging() ? seekBar.getValue() * len : MusicPlayer.currentTime();
                    time.setText(formatTime(cur, len));
                    // 抖动修复：非拖动且与上次显示值差异超过阈值才重设，避免每帧原地重设导致指针抖动
                    if (!userSeek[0] && !seekBar.isDragging()) {
                        float target = cur / len;
                        if (Math.abs(target - lastShown[0]) > 0.0005f) {
                            userSeek[0] = true;
                            seekBar.setValue(target);
                            lastShown[0] = target;
                            userSeek[0] = false;
                        }
                    } else if (seekBar.isDragging()) {
                        lastShown[0] = seekBar.getValue();
                    }
                } else {
                    // 未知/超大时长：显示当前进度 / --:--，与悬浮条文案一致，避免 0:00/0:00 误导
                    time.setText(formatTime(MusicPlayer.currentTime(), len));
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
            seek.add(seekBar).growX().padLeft(10f);
        }).growX().padBottom(4f).row();

        // —— 主控制条：上一首 / 快退 / 播放暂停 / 快进 / 下一首（图标按钮，高度统一） ——
        cont.table(ctrl -> {
            ctrl.button(Icon.leftOpen, Styles.flati, MusicPlayer::prev)
                    .growX().height(Scl.scl(48f)).padRight(2f);
            ctrl.button(Icon.leftSmall, Styles.flati, () -> MusicPlayer.seekRelative(-10f))
                    .growX().height(Scl.scl(48f)).pad(2f);
            ImageButton pp = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, Styles.flati);
            pp.resizeImage(Scl.scl(26f));
            pp.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            // 修复（2026-09-03 rev5）：主播放/暂停按钮图标此前只在「点击 togglePlay→rebuild」时刷新，
            // 若播放态经自动推进/倒放回开头停/远端状态变化等非点击路径改变，图标会滞留旧状态。
            // 与悬浮条 playButtonFrameSync 一致，每帧同步到真实播放态，彻底根治图标滞旧。
            pp.update(() -> {
                boolean p = MusicPlayer.isPlaying();
                pp.getImage().setDrawable(p ? Icon.pause : Icon.play);
                pp.getImage().setColor(p ? Pal.accent : Color.white);
            });
            pp.clicked(this::togglePlay);
            ctrl.add(pp).growX().height(Scl.scl(48f)).pad(2f);
            ctrl.button(Icon.rightSmall, Styles.flati, () -> MusicPlayer.seekRelative(10f))
                    .growX().height(Scl.scl(48f)).pad(2f);
            ctrl.button(Icon.rightOpen, Styles.flati, MusicPlayer::next)
                    .growX().height(Scl.scl(48f)).padLeft(2f);
        }).growX().padTop(2f).row();

        // —— 专辑筛选栏 ——
        cont.table(albumsFilter -> {
            albumsFilter.background(Styles.grayPanel);
            albumsFilter.margin(3f, 6f, 3f, 6f);
            // 「全部曲目」按钮
            TextButton all = new TextButton(Core.bundle.get("musicplayer.allAlbums"), Styles.flatBordert);
            all.getLabel().setWrap(false);
            all.update(() -> all.setColor(filterAlbum == null ? Pal.accent : Color.lightGray));
            all.clicked(() -> { filterAlbum = null; rebuildRows(); });
            albumsFilter.add(all).width(Scl.scl(100f)).height(Scl.scl(30f)).pad(1f);
            Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
            for (int i = 0; i < albums.size; i++) {
                MusicPlayer.Album a = albums.get(i);
                final String name = a.name;
                TextButton b = new TextButton(a.name, Styles.flatBordert);
                b.getLabel().setWrap(false);
                b.getLabel().setEllipsis(true);
                b.update(() -> b.setColor(name.equals(filterAlbum) ? Pal.accent : Color.white));
                b.clicked(() -> { filterAlbum = name; rebuildRows(); });
                albumsFilter.add(b).width(Scl.scl(96f)).height(Scl.scl(30f)).pad(1f);
            }
            // 新专辑按钮
            albumsFilter.add().growX();
            albumsFilter.button(Icon.add, Styles.cleari, this::newAlbumDialog).size(Scl.scl(30f)).padLeft(4f);
            // 删除当前筛选专辑按钮（仅 filterAlbum 非空时有效）
            ImageButton delAlbum = new ImageButton(Icon.trash, Styles.cleari);
            delAlbum.resizeImage(Scl.scl(15f));
            delAlbum.clicked(() -> deleteCurrentAlbum());
            albumsFilter.add(delAlbum).size(Scl.scl(30f)).padLeft(2f);
        }).growX().padTop(6f).row();

        // —— 音量 / 倍速（两个并排面板合为一行；2026-09-03 移除独立音高面板：Soloud 无独立音调控制、
        //    变速不变调需引入 ffmpeg，故只保留一个「倍速」控制，变速即变调，自然声学） ——
        cont.table(analog -> {
            analog.defaults().pad(2f);
            // 音量面板（0–1000%，100% 在条中间、两侧刻度不同：左半 0–100%、右半 100–1000%；
            // 指数映射到内部增益 0–10x，100%＝0dB＝增益1）
            analog.table(vp -> {
                vp.background(Styles.grayPanel);
                vp.margin(6f);
                vp.defaults().pad(2f);
                arc.scene.ui.Image volIcon = new arc.scene.ui.Image(Icon.chat);
                volIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("音量 0-1000%")));
                vp.add(volIcon).size(Scl.scl(14f)).padRight(2f);
                vp.add(Core.bundle.get("musicplayer.volume")).left().width(Scl.scl(42f)).padRight(2f);
                Slider vol = new Slider(0f, 1000f, 1f, false);
                vol.setValue(pctToVol(gainToPct(MusicPlayer.volume())));
                final arc.scene.ui.Label volVal = new arc.scene.ui.Label(pctText(gainToPct(MusicPlayer.volume())), Styles.outlineLabel);
                volVal.setColor(Color.white);
                vol.update(() -> {
                    float target = pctToVol(gainToPct(MusicPlayer.volume()));
                    if (!vol.isDragging() && Math.abs(vol.getValue() - target) > 1f) {
                        vol.setValue(target);
                    }
                    volVal.setText(pctText(volToPct(vol.getValue())));
                });
                vol.changed(() -> MusicPlayer.setVolume(pctToGain(volToPct(vol.getValue()))));
                vp.add(vol).growX().width(Scl.scl(170f));
                vp.add(volVal).width(Scl.scl(62f)).right().padLeft(4f);
            }).growX();
            // 倍速面板
            analog.table(sp -> {
                sp.background(Styles.grayPanel);
                sp.margin(6f);
                sp.defaults().pad(2f);
                arc.scene.ui.Image spdIcon = new arc.scene.ui.Image(Icon.rightSmall);
                spdIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("倍速 1/16-16x")));
                sp.add(spdIcon).size(Scl.scl(14f)).padRight(2f);
                sp.add(Core.bundle.get("musicplayer.speed")).left().width(Scl.scl(38f)).padRight(2f);
                Slider spd = new Slider(0f, 1f, 0.001f, false);
                spd.setValue(speedToCursor(MusicPlayer.speed()));
                final arc.scene.ui.Label spdVal = new arc.scene.ui.Label(formatSpeed(MusicPlayer.speed()), Styles.outlineLabel);
                spdVal.setColor(Color.white);
                spd.update(() -> {
                    if (!spd.isDragging() && Math.abs(cursorToSpeed(spd.getValue()) - MusicPlayer.speed()) > 0.001f) {
                        spd.setValue(speedToCursor(MusicPlayer.speed()));
                    }
                    spdVal.setText(formatSpeed(cursorToSpeed(spd.getValue())));
                });
                spd.changed(() -> MusicPlayer.setSpeed(cursorToSpeed(spd.getValue())));
                sp.add(spd).growX().width(Scl.scl(120f));
                sp.add(spdVal).width(Scl.scl(64f)).right().padLeft(4f);
            }).growX();
        }).growX().padTop(2f).row();

        // —— A-B 区间（区间重复）：设 A / 设 B / 清除 + 区间状态与范围 ——
        cont.table(abRow -> {
            abRow.background(Styles.grayPanel);
            abRow.margin(5f, 8f, 5f, 8f);
            abRow.defaults().pad(2f);
            arc.scene.ui.Image abIcon = new arc.scene.ui.Image(Icon.book);
            abIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("A-B 区间重复")));
            abRow.add(abIcon).size(Scl.scl(14f)).padRight(2f);
            abRow.add(Core.bundle.get("musicplayer.ab")).left().width(Scl.scl(76f));
            final arc.scene.ui.Label abStatus = new arc.scene.ui.Label(abStatusText(), Styles.outlineLabel);
            abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
            abStatus.update(() -> {
                abStatus.setText(abStatusText());
                abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
            });
            abRow.add(abStatus).growX().left().padLeft(2f);
            abRow.button(Core.bundle.get("musicplayer.abSetA"), Styles.flatBordert, () -> {
                MusicPlayer.setAbA(MusicPlayer.currentTime());
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
            abRow.button(Core.bundle.get("musicplayer.abSetB"), Styles.flatBordert, () -> {
                MusicPlayer.setAbB(MusicPlayer.currentTime());
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
            abRow.button(Core.bundle.get("musicplayer.abClear"), Styles.flatBordert, () -> {
                MusicPlayer.clearAb();
                rebuild();
            }).height(Scl.scl(32f)).width(Scl.scl(86f));
        }).growX().padTop(2f).row();

        // —— 底部：循环模式 / 倒放 / 停止 / 添加曲目（图标点缀提升可识别性） ——
        cont.table(bottom -> {
            arc.scene.ui.Image botIcon = new arc.scene.ui.Image(Icon.music);
            botIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("播放控制")));
            bottom.add(botIcon).size(Scl.scl(14f)).padRight(4f);
            TextButton loop = new TextButton(loopModeText(), Styles.flatBordert);
            loop.getLabel().setWrap(false);
            loop.getLabel().setEllipsis(false);
            loop.getLabel().setFontScale(Scl.scl(0.9f));
            loop.clicked(() -> {
                MusicPlayer.cycleLoopMode();
                loop.setText(loopModeText());
            });
            // 固定宽高（不等长文本切换不导致按钮忽大忽小；flatBordert 内边距随字体字号/内容伸缩，
            // 显式固定高度根治「点击后里行按钮抖动」）；文案等长中文，完整显示不省略
            bottom.add(loop).width(Scl.scl(84f)).height(Scl.scl(34f)).pad(2f);

            TextButton rev = new TextButton(Core.bundle.get("musicplayer.reverse"), Styles.flatBordert);
            rev.getLabel().setWrap(false);
            rev.getLabel().setFontScale(Scl.scl(0.9f));
            final TextButton revF = rev;
            revF.update(() -> revF.getLabel().setColor(MusicPlayer.isReverse() ? Pal.accent : Color.white));
            revF.clicked(() -> MusicPlayer.toggleReverse());
            bottom.add(rev).width(Scl.scl(84f)).height(Scl.scl(34f)).pad(2f);

            // 停止/添加曲目也显式固定宽度，与上述按钮一致——不用 growX()（会随 rebuild/pref 波动导致抖动）
            bottom.button(Core.bundle.get("musicplayer.stop"), Styles.flatBordert, MusicPlayer::stop)
                    .width(Scl.scl(96f)).height(Scl.scl(34f)).pad(2f);
            bottom.button(Core.bundle.get("musicplayer.addTrack"), Styles.flatBordert, this::showAddDialog)
                    .width(Scl.scl(112f)).height(Scl.scl(34f)).pad(2f);
        }).growX().padTop(2f).row();

        // —— 更多设置：播放给他人 + 启用开关 + 悬浮条复位（紧凑面板，尽量缩小留白） ——
        cont.table(more -> {
            more.background(Styles.grayPanel);
            more.margin(4f, 8f, 4f, 8f);
            more.defaults().pad(2f);
            arc.scene.ui.Image shareIcon = new arc.scene.ui.Image(Icon.chat);
            shareIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("共享给他人")));
            more.add(shareIcon).size(Scl.scl(12f)).padRight(2f);
            CheckBox share = new CheckBox(Core.bundle.get("musicplayer.share"));
            share.setChecked(MusicPlayer.isShareEnabled());
            share.changed(() -> MusicPlayer.setShareEnabled(share.isChecked()));
            more.add(share).left().growX();
            arc.scene.ui.Image enIcon = new arc.scene.ui.Image(Icon.ok);
            enIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("总开关")));
            more.add(enIcon).size(Scl.scl(12f)).padRight(2f);
            CheckBox enable = new CheckBox(Core.bundle.get("musicplayer.enable"));
            enable.setChecked(MusicPlayer.isEnabled());
            enable.changed(() -> MusicPlayer.setEnabled(enable.isChecked()));
            more.add(enable).left().growX();
            TextButton reset = new TextButton(Core.bundle.get("musicplayer.resetPos"), Styles.flatBordert);
            reset.getLabel().setWrap(false);
            reset.getLabel().setFontScale(Scl.scl(0.9f));
            reset.clicked(() -> MusicBar.resetPosition());
            more.add(reset).height(Scl.scl(34f)).width(Scl.scl(150f)).right();
        }).growX().padTop(2f).row();

        // —— 曲目列表（置于底部并 growY 填满剩余高度，消除设置界面下方空白） ——
        trackTable = new Table();
        trackTable.top();
        trackTable.defaults().fillX().padBottom(2f);
        rebuildRows();

        cont.table(s -> {
            TextField search = new TextField(filterText);
            String hint = "搜索曲名";
            try { String v = Core.bundle.get("musicplayer.search"); if (v != null && !v.contains("??")) hint = v; } catch (Exception ignored) {}
            search.setMessageText(hint);
            search.changed(() -> { filterText = search.getText(); rebuildRows(); });
            s.image(Icon.zoom).size(Scl.scl(14f)).padRight(4f);
            s.add(search).growX().height(Scl.scl(32f));
            if (!filterText.isEmpty()) {
                s.button(Icon.cancel, Styles.cleari, () -> { filterText = ""; rebuild(); }).size(Scl.scl(28f)).padLeft(4f);
            }
        }).growX().padTop(4f).padBottom(2f).row();
        cont.table(h -> {
            h.image(Icon.book).size(Scl.scl(12f)).padRight(4f);
            h.add("[gray]曲目列表 (" + MusicPlayer.tracks().size + ")[]").left();
        }).left().padBottom(2f).row();
        ScrollPane pane = new ScrollPane(trackTable, Styles.defaultPane);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).growX().growY().minHeight(Scl.scl(140f)).padTop(2f).row();
    }

    private String nowPlayingLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) {
            try { String v = Core.bundle.get("musicplayer.none"); return v != null && !v.contains("??") ? v : "none"; } catch (Exception e) { return "none"; }
        }
        return t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
    }

    /** 秒 → m:ss 格式 */
    private static String formatTime(float sec, float len) {
        return fmt(sec) + " / " + (len > 0f ? fmt(len) : "--:--");
    }

    private static String fmt(float sec) {
        if (Float.isNaN(sec) || Float.isInfinite(sec) || sec < 0f) sec = 0f;
        int total = (int) sec;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    private static String fmt1(float sec) {
        if (Float.isNaN(sec) || Float.isInfinite(sec) || sec < 0f) sec = 0f;
        int m = (int) (sec / 60);
        float s = sec % 60f;
        return m + ":" + (s < 10f ? "0" : "") + String.format(java.util.Locale.US, "%.1f", s);
    }

    /** A-B 区间状态文字：未设置显示「未设置」；两点已设但间距未达 hasAb 阈值时显示「过短未生效」；正常显示区间与开关态（带1位小数便于精确区分，缺键回退） */
    private static String abStatusText() {
        try {
            if (MusicPlayer.hasAb()) {
                float lo = Math.min(MusicPlayer.abA(), MusicPlayer.abB());
                float hi = Math.max(MusicPlayer.abA(), MusicPlayer.abB());
                String on = Core.bundle.get("musicplayer.abOn");
                return (on.contains("??") ? "A-B" : on) + "  " + fmt1(lo) + " - " + fmt1(hi);
            }
            if (MusicPlayer.abA() >= 0f && MusicPlayer.abB() >= 0f) {
                String s = Core.bundle.get("musicplayer.abTooShort");
                return s.contains("??") ? "too short" : s;
            }
            String u = Core.bundle.get("musicplayer.abUnset");
            return u.contains("??") ? "unset" : u;
        } catch (Exception e) { return MusicPlayer.hasAb() ? "A-B" : "unset"; }
    }

    // 倍速对数映射（1/16–16x）：speed = (1/16) * 256^cursor，256 = 16/(1/16)
    private static final float LOG_MIN = MusicPlayer.MIN_SPEED;
    private static final float LOG_RATIO = 16f / MusicPlayer.MIN_SPEED;

    private static float speedToCursor(float speed) {
        speed = Math.max(LOG_MIN, Math.min(16f, speed));
        return (float) (Math.log(speed / LOG_MIN) / Math.log(LOG_RATIO));
    }

    private static float cursorToSpeed(float cursor) {
        cursor = Math.max(0f, Math.min(1f, cursor));
        return (float) (LOG_MIN * Math.pow(LOG_RATIO, cursor));
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - 1f) < 0.001f) return "1x";
        if (Math.abs(s - Math.round(s)) < 0.001f) return Math.round(s) + "x";
        return String.format(java.util.Locale.US, "%.2fx", s);
    }

    private void togglePlay() {
        if (MusicPlayer.isPlaying()) {
            MusicPlayer.pause();
        } else {
            MusicPlayer.resume();
        }
        rebuild();
    }

    private void rebuildRows() {
        if (trackTable == null) return;
        trackTable.clearChildren();
        Seq<MusicTrack> tracks = MusicPlayer.tracks();
        if (tracks.size == 0) {
            trackTable.add(Core.bundle.get("musicplayer.empty")).color(Color.lightGray).pad(10f);
            return;
        }
        int current = MusicPlayer.currentIndex();
        // 过滤：仅显示当前激活专辑内的曲目（filterAlbum != null 时）
        int count = 0;
        String ft = filterText == null ? "" : filterText.trim().toLowerCase();
        for (int i = 0; i < tracks.size; i++) {
            MusicTrack t = tracks.get(i);
            if (filterAlbum != null && !isInAlbum(filterAlbum, t.cacheHash)) continue;
            if (!ft.isEmpty() && (t.name == null || !t.name.toLowerCase().contains(ft))) continue;
            count++;
            int idx = i;
            boolean isCurrent = current == i;
            Table row = new Table();
            if (isCurrent) row.background(Styles.grayPanel);
            else if (count % 2 == 0) row.background(Styles.grayPanelDark);
            row.defaults().pad(2f);
            // 曲名（滚动循环显示 + 固定宽）→ 长名自动滚动、不撑宽按钮破坏对齐与行结构
            String safeName = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
            MusicBar.MarqueeLabel name = new MusicBar.MarqueeLabel(
                    (isCurrent ? "[accent]> " : "") + safeName, Styles.outlineLabel);
            name.setColor(isCurrent ? Pal.accent : Color.white);
            name.clicked(() -> { MusicPlayer.play(idx); rebuildRows(); });
            row.add(name).width(Scl.scl(250f)).height(Scl.scl(38f)).growX().padRight(10f);
            // 类型标签独立固定宽列，右对齐 —— 与曲名分离，列宽稳定不致长名挤压
            row.add("[gray](" + Core.bundle.get(t.typeKey) + ")").
                    width(Scl.scl(116f)).right().color(Color.gray);
            // 音频信息：时长 / 文件大小分列固定宽右对齐，各行严格对齐（不再混排进同一 Label 造成参差）
            arc.scene.ui.Label timeLbl = new arc.scene.ui.Label(trackTimeText(t), Styles.outlineLabel);
            timeLbl.setColor(Color.gray);
            row.add(timeLbl).width(Scl.scl(52f)).right();
            arc.scene.ui.Label sizeLbl = new arc.scene.ui.Label(trackSizeText(t), Styles.outlineLabel);
            sizeLbl.setColor(Color.gray);
            row.add(sizeLbl).width(Scl.scl(66f)).right().padLeft(6f);
            // 专辑归属按钮：点击弹出「加入/移出专辑」菜单
            ImageButton albumBtn = new ImageButton(Icon.folder, Styles.cleari);
            albumBtn.resizeImage(Scl.scl(16f));
            albumBtn.addListener(new Tooltip(tbl -> tbl.background(Styles.black6).margin(4f).add("专辑")));
            albumBtn.clicked(() -> albumAssignDialog(idx));
            row.add(albumBtn).size(Scl.scl(32f)).padLeft(4f);
            ImageButton del = new ImageButton(Icon.trash, Styles.cleari);
            del.resizeImage(Scl.scl(18f));
            del.addListener(new Tooltip(tbl -> tbl.background(Styles.black6).margin(4f).add("删除")));
            del.clicked(() -> removeTrack(idx));
            row.add(del).size(Scl.scl(36f)).padLeft(4f);
            trackTable.add(row).growX().row();
        }
        if (count == 0) {
            String msg = !ft.isEmpty() ? "无匹配: " + ft : Core.bundle.get("musicplayer.albumEmpty");
            trackTable.add(msg).color(Color.lightGray).pad(10f);
        }
    }

    /** 判断某曲目 hash 是否属于指定专辑 */
    private static boolean isInAlbum(String albumName, String hash) {
        for (MusicPlayer.Album a : MusicPlayer.albums()) {
            if (albumName.equals(a.name) && a.hashes.contains(hash)) return true;
        }
        return false;
    }

    /** 专辑归属菜单：把当前曲目加入/移出某个专辑 */
    private void albumAssignDialog(int trackIndex) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addToAlbum"));
        Table list = new Table();
        list.top();
        MusicTrack t = MusicPlayer.trackAt(trackIndex);
        if (t == null) return;
        list.add(Core.bundle.get("musicplayer.playing") + ": [accent]" + t.name + "[]").left().pad(4f).row();
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        if (albums.size == 0) {
            list.add(Core.bundle.get("musicplayer.noAlbum")).color(Color.lightGray).pad(6f).row();
        }
        for (int i = 0; i < albums.size; i++) {
            MusicPlayer.Album a = albums.get(i);
            final int ai = i;
            boolean inAlbum = a.hashes.contains(t.cacheHash);
            String label = (inAlbum ? "[accent]✓ [/]" : "  ") + a.name;
            TextButton b = new TextButton(label, Styles.flatBordert);
            b.clicked(() -> {
                if (a.hashes.contains(t.cacheHash)) MusicPlayer.removeFromAlbum(ai, trackIndex);
                else MusicPlayer.addToAlbum(ai, trackIndex);
                dlg.hide();
                rebuildRows();
            });
            list.add(b).growX().height(Scl.scl(34f)).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(Scl.scl(220f));
        dlg.buttons.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, dlg::hide).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 删除当前正在筛选的专辑（仅 filterAlbum 非空时可删） */
    private void deleteCurrentAlbum() {
        if (filterAlbum == null) return;
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        for (int i = 0; i < albums.size; i++) {
            if (filterAlbum.equals(albums.get(i).name)) {
                MusicPlayer.removeAlbum(i);
                break;
            }
        }
        // 若正在播放的曲目原来在删除专辑内且当前专辑作用域是它，会由 removeAlbum 复位 activeAlbum；
        // 这里把筛选也复位到全部
        filterAlbum = null;
        rebuild();
    }

    /** 新建专辑弹窗：输入名称创建 */
    private void newAlbumDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.newAlbum"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.albumName"));
        dlg.cont.add(field).growX().pad(10f).row();
        dlg.cont.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            String name = field.getText().trim();
            if (name.length() > MusicPlayer.MAX_ALBUM_NAME_LENGTH) name = name.substring(0, MusicPlayer.MAX_ALBUM_NAME_LENGTH);
            if (!name.isEmpty()) {
                MusicPlayer.addAlbum(name);
                filterAlbum = name;
                rebuild();
            }
            dlg.hide();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    private void removeTrack(int idx) {
        MusicTrack t = MusicPlayer.trackAt(idx);
        String name = t == null ? "" : t.name;
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.confirm"));
        String q = "确定删除?";
        try { String v = Core.bundle.get("musicplayer.deleteConfirm"); if (v != null && !v.contains("??")) q = v; } catch (Exception ignored) {}
        dlg.cont.add(q + "\n[accent]" + (name.replace("[", "[[").replace("]", "]]")) + "[]").pad(10f).row();
        dlg.buttons.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            MusicPlayer.removeTrack(idx);
            rebuildRows();
            dlg.hide();
        }).width(Scl.scl(100f)).height(Scl.scl(36f));
        String cancel = "取消";
        try { String v = Core.bundle.get("universal-junction.cancel"); if (v != null && !v.contains("??")) cancel = v; } catch (Exception ignored) {}
        dlg.buttons.button(cancel, Styles.flatBordert, dlg::hide).width(Scl.scl(100f)).height(Scl.scl(36f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 批量导入完成后的信息确认弹窗：逐条列出文件名/大小/时长；
     *  @param afterClose 关闭确认弹窗后执行（如本地导入重新弹出导入界面继续追加） */
    private void showImportResult(Seq<MusicTrack> added, Runnable afterClose) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.importResult"));
        dlg.cont.table(list -> {
            list.top();
            list.defaults().pad(1f);
            for (MusicTrack t : added) {
                String info = trackInfoLabel(t);
                String safe = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
                String line = "[accent]>[/] " + safe + (info.isEmpty() ? "" : "  [gray]" + info + "[]");
                list.add(new arc.scene.ui.Label(line, Styles.defaultLabel)).growX().left().row();
            }
        }).grow().pad(10f);
        dlg.buttons.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            dlg.hide();
            if (afterClose != null) afterClose.run();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 在「当前筛选专辑」下导入的新曲自动归入该专辑（导入完成即出现在当前列表） */
    private void autoAddToCurrentAlbum(MusicTrack t) {
        if (filterAlbum == null || t == null) return;
        MusicPlayer.addTrackHashToAlbum(filterAlbum, t.cacheHash);
    }

    private void showAddDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addTitle"));
        dlg.cont.table(t -> {
            addIconButton(t, Icon.book, "musicplayer.addInternal", () -> {
                dlg.hide();
                showInternalPicker();
            });
            addIconButton(t, Icon.link, "musicplayer.addUrl", () -> {
                dlg.hide();
                showSourceInput(MusicTrack.URL);
            });
            addIconButton(t, Icon.file, "musicplayer.addLocal", () -> {
                dlg.hide();
                showSourceInput(MusicTrack.LOCAL);
            });
            if (filterAlbum != null) {
                String safe = filterAlbum.replace("[", "[[").replace("]", "]]");
                t.add("[gray]" + Core.bundle.get("musicplayer.importToAlbum") + ": [accent]" + safe + "[]")
                        .growX().padTop(6f);
            }
        }).pad(10f);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 导入界面用的图标+文字按钮（本 arc 的 TextButton 无「图标+文案」构造器，用表内 Image+TextButton 拼装） */
    private static void addIconButton(Table parent, arc.scene.style.Drawable icon, String bundleKey, Runnable action) {
        Table row = new Table();
        arc.scene.ui.Image img = new arc.scene.ui.Image(icon);
        img.setColor(Color.lightGray);
        row.add(img).size(Scl.scl(24f)).padRight(8f);
        TextButton b = new TextButton(Core.bundle.get(bundleKey), Styles.flatBordert);
        b.getLabel().setWrap(false);
        b.clicked(action);
        row.add(b).width(Scl.scl(216f)).height(Scl.scl(48f));
        parent.add(row).pad(3f).row();
    }

    private void showInternalPicker() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addInternal"));
        Table list = new Table();
        list.top();
        String[] keys = MusicPlayer.internalKeys();
        for (String k : keys) {
            String label;
            try { String v = Core.bundle.get("music." + k); label = (v != null && !v.contains("??")) ? v : k; } catch (Exception e) { label = k; }
            final String key = k;
            list.button(label, Styles.flatBordert, () -> {
                MusicTrack t = MusicPlayer.trackByHash("int-" + key);
                if (t != null) {
                    int idx = MusicPlayer.tracks().indexOf(t);
                    if (idx >= 0) MusicPlayer.play(idx);
                }
                dlg.hide();
                rebuild();
            }).growX().height(Scl.scl(38f)).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(Scl.scl(260f));
        dlg.closeOnBack();
        dlg.show();
    }

    private void showSourceInput(int type) {
        if (type == MusicTrack.LOCAL) {
            // 本地文件：唤起系统文件选择框（支持多选；导入完成后弹出信息确认）
            mindustry.ui.FileChooser.FileChooserParams params = new mindustry.ui.FileChooser.FileChooserParams();
            params.open = true;
            params.extensions = new String[]{"ogg", "mp3", "wav", "flac", "m4a", "wma", "aac", "opus"};
            params.title = Core.bundle.get("musicplayer.addLocal");
            params.submitMulti(files -> {
                // 桌面 Platform 用后台 daemon 线程回调，UI 更新与 tracks 改动必须切回主线程
                final arc.struct.Seq<arc.files.Fi> picked = new arc.struct.Seq<>(files);
                Core.app.post(() -> {
                    Seq<MusicTrack> added = new Seq<>();
                    for (arc.files.Fi f : picked) {
                        String src = f.absolutePath();
                        if (src == null || src.isEmpty()) continue;
                        MusicTrack t = MusicPlayer.addTrack(MusicTrack.LOCAL, src, f.name());
                        if (t != null) {
                            added.add(t);
                            autoAddToCurrentAlbum(t);
                        }
                    }
                    if (added.size > 0) {
                        this.rebuild();
                        // 本地导入不退出导入界面：确认结果后重新弹出导入界面，便于继续追加
                        showImportResult(added, this::showAddDialog);
                    } else {
                        showAddDialog();
                    }
                });
            });
            return;
        }
        // URL：文本输入框
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addUrl"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.placeholderUrl"));
        dlg.cont.add(field).growX().pad(10f).row();
        dlg.cont.add(Core.bundle.get("musicplayer.urlHint"));
        dlg.cont.row();
        // 无效输入提示（初始隐藏，输入不合法时显示）
        final arc.scene.ui.Label err = new arc.scene.ui.Label(Core.bundle.get("musicplayer.invalid"), Styles.defaultLabel);
        err.setColor(Color.scarlet);
        err.visible = false;
        dlg.cont.add(err).growX().padTop(2f).row();
        // 当前筛选专辑提示：导入后自动归入
        if (filterAlbum != null) {
            String safe2 = filterAlbum.replace("[", "[[").replace("]", "]]");
            dlg.cont.add("[gray]" + Core.bundle.get("musicplayer.importToAlbum") + ": [accent]" + safe2 + "[]")
                    .growX().padTop(2f).row();
        }
        dlg.cont.button(Core.bundle.get("musicplayer.confirm"), Styles.flatBordert, () -> {
            String src = field.getText().trim();
            if (src.isEmpty()) { dlg.hide(); return; }
            String name = null;
            int slash = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
            if (slash >= 0 && slash < src.length() - 1) {
                name = src.substring(slash + 1);
                int q = name.indexOf('?'); if (q >= 0) name = name.substring(0, q);
                int h = name.indexOf('#'); if (h >= 0) name = name.substring(0, h);
                if (name.length() > MusicPlayer.MAX_TRACK_NAME_LENGTH) name = name.substring(0, MusicPlayer.MAX_TRACK_NAME_LENGTH);
                if (name.isEmpty()) name = null;
            }
            MusicTrack t = MusicPlayer.addTrack(MusicTrack.URL, src, name);
            if (t == null) {
                // 非法来源（无匹配扩展名等）：提示并保持弹窗，不静默关闭
                err.visible = true;
                return;
            }
            autoAddToCurrentAlbum(t);
            dlg.hide();
            rebuild();
        }).width(Scl.scl(120f)).height(Scl.scl(40f));
        dlg.closeOnBack();
        dlg.show();
    }

    /** 循环模式按钮文案（6 种，缺键回退） */
    private static String loopModeText() {
        try { String v = Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()); return v != null && !v.contains("??") ? v : ("loop" + MusicPlayer.loopMode()); } catch (Exception e) { return "loop" + MusicPlayer.loopMode(); }
    }

    // 音量滑杆映射（0–1000%）：100% 位于滑杆正中间，两侧刻度不同。
    // 滑杆值 x（0–1000）：
    //   左半 x∈[0,500] → 百分比 0–100%（压缩到左半）
    //   右半 x∈[500,1000] → 百分比 100–1000%（铺满右半）
    // 百分比 → 内部增益 g：
    //   0–100%：g=(P/100)^2  （0→0，100%→1，二次曲线低端更细腻）
    //   100–1000%：g=10^((P-100)/900)（100%→1，1000%→10，即 +20dB，真的放大千倍音量的 10x）
    // 即默认 100% 落在滑杆中点（x=500）。
    private static float volToPct(float x) {
        if (x <= 500f) return x * 100f / 500f;         // 0..100
        return 100f + (x - 500f) / 500f * 900f;        // 100..1000
    }

    private static float pctToVol(float pct) {
        if (pct <= 100f) return pct * 500f / 100f;     // 0..500
        return 500f + (pct - 100f) / 900f * 500f;      // 500..1000
    }

    private static float pctToGain(float pct) {
        if (pct <= 0f) return 0f;
        if (pct <= 100f) return (float) java.lang.Math.pow(pct / 100.0, 2.0);
        return (float) java.lang.Math.pow(10.0, (pct - 100.0) / 900.0);
    }

    private static float gainToPct(float g) {
        if (g <= 0f) return 0f;
        if (g <= 1f) return (float) (100.0 * java.lang.Math.sqrt(g));
        return (float) (100.0 + 900.0 * java.lang.Math.log10(g));
    }

    private static String pctText(float pct) {
        return Math.round(pct) + "%";
    }

    /** 曲目时长文本（未知显示占位符，避免各行宽度闪跳）。当前播放曲若正播且声源长度已知，优先用 trackLength()（含声源实时长度），
     *  避免超限大文件（>64MB 探针受限）在列表里一直显示 --:--，而悬浮条已能显示真实时长的不一致。
     *  外部音频下载/分块接收中时显示“下载中/接收中”而非 --:--，便于区分“未知”与“进行中”。 */
    private static String trackTimeText(MusicTrack t) {
        if (t != null) {
            if (MusicNetwork.isDownloading(t.cacheHash)) return "下载中";
            if (MusicNetwork.isReceiving(t.cacheHash)) {
                int p = MusicNetwork.receiveProgress(t.cacheHash);
                return p >= 0 ? "接收中" + p + "%" : "接收中";
            }
        }
        float len = -1f;
        MusicTrack cur = MusicPlayer.currentTrack();
        if (cur != null && t != null && cur.cacheHash.equals(t.cacheHash)) {
            float curLen = MusicPlayer.trackLength();
            if (curLen > 0f) len = curLen;
        }
        if (len <= 0f) len = MusicPlayer.trackLengthOf(t);
        if (len <= 0f) return "--:--";
        int total = (int) len;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    /** 曲目文件大小文本（未知显示占位符） */
    private static String trackSizeText(MusicTrack t) {
        long size = MusicPlayer.trackSizeOf(t);
        if (size <= 0) return "--";
        if (size > 1048576) return String.format(java.util.Locale.US, "%.1fM", size / 1048576.0);
        if (size > 1024) return String.format(java.util.Locale.US, "%.0fK", size / 1024.0);
        return size + "B";
    }

    /** 曲目信息标签：时长 + 文件大小（导入结果对话框合并成一行展示用） */
    private static String trackInfoLabel(MusicTrack t) {
        String time = trackTimeText(t);
        String size = trackSizeText(t);
        return size.equals("--") ? time : time + "  " + size;
    }
}
