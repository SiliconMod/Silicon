package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalJammer;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;

/**
 * 信号频谱面板（信号源 / 信号中继器 / 信号检测器配置界面共享组件）：
 * 在方块所在点位对 5 条信道各渲染一行——信道色块 | 占用计数（地面源/中继 + 卫星 + 干扰器） |
 * 本点干扰功率 I（SINR 分母去掉底噪） | 该点可用有效强度条（SINR 折算后，含卫星层 RSS 合成）。
 * <p>
 * <b>卫星层</b>：绑定信道的在轨卫星（channel ≥ 1）按信道各自 SINR 折算（satelliteEffAt，底噪在
 * 质量因子内）后按非相干功率合成（√(Σeᵢ²)），再与地面强度 RSS 功率合成——与 H 覆盖层模型一致
 * （两次合成是同一个 √(Σ·²) 运算，可结合）；未绑定卫星（channel < 1）不进信道视图（仅覆盖显示）。
 * <p>
 * <b>布局防重叠（实测踩坑）</b>：BlockConfigFragment 在面板打开时按空标签 pack 一次定宽，节流刷新
 * 填入文本后外层不会重新加宽——列宽必须固定且按最宽文本预留，标签一律左对齐（居中文本溢出会向
 * 两侧渗透）；整段频谱放进单个嵌套表并对宿主声明 minWidth，杜绝宿主列宽挤压。
 * <p>
 * 性能：5 tick（约 12 Hz）节流刷新；标签 setText / Bar 值每节流周期更新，无逐帧字符串分配。
 * 静态缓冲复用（同一时刻只有一个配置面板打开；面板关闭后 update 链随场景移除自动停止）。
 */
public class SignalSpectrum {
    /** 信道标识色（1~5）：面板视觉分组用，与覆盖绘制无关 */
    private static final Color[] CH_COLORS = {
            Color.valueOf("e05555"), // 1 红
            Color.valueOf("e0c43a"), // 2 黄
            Color.valueOf("5fb04c"), // 3 绿
            Color.valueOf("3aa8e0"), // 4 蓝
            Color.valueOf("8a4ae0"), // 5 紫
    };

    /** 当前信道号的颜色（高亮当前行） */
    private static final Color curColor = Pal.accent;

    /** 固定列宽（按最宽实测文本预留：中文字形约 20px/字——"12源/12扰"≈96px，"信道"≈36px，杜绝渗透） */
    private static final float W_CHIP = 10f, W_CH = 36f, W_OCC = 96f, W_ITF = 60f, W_BAR_MIN = 78f;
    /** 频谱区总最小宽（各列 + 间距），宿主 minWidth 用 */
    private static final float SECTION_MIN = W_CHIP + 4f + W_CH + W_OCC + W_ITF + W_BAR_MIN + 12f;

    private static final float[] effBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] intBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    @SuppressWarnings("unchecked")
    private static final Building[] srcBuf = new Building[SignalJammer.CHANNEL_MAX + 1];
    /** 每信道结果所属编码（由 SignalChannel.usableAll 填充；无信号时 null） */
    private static final String[] codeBuf = new String[SignalJammer.CHANNEL_MAX + 1];
    /** 每信道在轨卫星计数（占用列 = 信道拥挤度） */
    private static final int[] satCnt = new int[SignalJammer.CHANNEL_MAX + 1];
    /** 占用计数缓冲：在信道循环**外**一次算完（原先每个信道都要各遍历一遍全部源/中继/干扰器，5×全表） */
    private static final int[] srcCnt = new int[SignalJammer.CHANNEL_MAX + 1];
    private static final int[] jamCnt = new int[SignalJammer.CHANNEL_MAX + 1];
    private static final LabelRef[] occLabels = new LabelRef[SignalJammer.CHANNEL_MAX + 1];
    private static final LabelRef[] itfLabels = new LabelRef[SignalJammer.CHANNEL_MAX + 1];
    private static final LabelRef[] chLabels = new LabelRef[SignalJammer.CHANNEL_MAX + 1];

    /** 节流相位（面板打开期间递增；5 tick 一轮，见 update() 里的刷新块） */
    private static int tick;

    private SignalSpectrum() {
    }

    /**
     * 在配置面板表尾追加频谱区（自身占 4 列布局，调用方负责行距）。
     *
     * @param parent         配置面板根表（grayPanel 内容表）
     * @param at             频谱取样点所在建筑（取其坐标与队伍）
     * @param currentChannel 当前信道提供器（源=自身信道；中继=绑定源转发信道），当前行高亮用
     */
    public static void buildSection(Table parent, Building at, arc.func.Intp currentChannel) {
        // 整段频谱包进单个嵌套表：对宿主声明 minWidth 防挤压；内部 5 列扁平网格，
        // 表头与数据行共享同一列结构 → 对齐由结构保证
        Table spec = new Table();
        // 整段频谱铺一层不透明底色(与面板同色):覆盖任意列边界处的底层像素,杜绝竖线透出(实测踩坑)
        spec.setBackground(Styles.grayPanel);

        spec.add(Core.bundle.get("block.silicon-signal.spectrum.title"))
                .colspan(5).center().color(Pal.accent).padTop(6f).padBottom(1f);
        spec.row();
        // 聚合范围行：本面板的"可用强度"列按哪个口径算（源=自身编码 / 中继=绑定编码 / 检测器=逐信道最强编码）
        LabelRef scopeRef = new LabelRef();
        scopeRef.label = spec.add("").colspan(5).center().color(Color.lightGray).padBottom(2f).get();
        scopeRef.label.setAlignment(arc.util.Align.center);
        spec.row();
        // 表头（与数据行同列宽）：显式 Label 且 setAlignment(center)——单元格内居中 + 标签文本内
        // 居中双重保障（仅靠 Cell.center() 实测未生效，见用户反馈）
        spec.add().size(W_CHIP, W_CHIP).padRight(4f);
        Label hCh = new Label(Core.bundle.get("block.silicon-signal.spectrum.ch"));
        hCh.setAlignment(arc.util.Align.center);
        hCh.setColor(Color.gray);
        spec.add(hCh).width(W_CH).center().padTop(2f).padBottom(2f);
        Label hOcc = new Label(Core.bundle.get("block.silicon-signal.spectrum.occ"));
        hOcc.setAlignment(arc.util.Align.center);
        hOcc.setColor(Color.gray);
        spec.add(hOcc).width(W_OCC).center().padTop(2f).padBottom(2f);
        Label hItf = new Label(Core.bundle.get("block.silicon-signal.spectrum.itf"));
        hItf.setAlignment(arc.util.Align.center);
        hItf.setColor(Color.gray);
        spec.add(hItf).width(W_ITF).center().padTop(2f).padBottom(2f);
        Label hStr = new Label(Core.bundle.get("block.silicon-signal.spectrum.str"));
        hStr.setAlignment(arc.util.Align.center);
        hStr.setColor(Color.gray);
        spec.add(hStr).minWidth(W_BAR_MIN).growX().center().padTop(2f).padBottom(2f);
        spec.row();

        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            // 色板按 0 基索引（CH_COLORS 长 5），信道号 1 基——处处减一，勿直接用信道号索引
            // lambda 捕获要求实际最终变量：c（0基色板）与 ci（信道副本）均为每轮新建
            final int c = ch - 1;
            final int ci = ch;
            // 信道色块 + 号（当前信道行：号变高亮色；号码列内居中——cell.center 在该 arc 版不可靠，
            // 统一走 Label 内部 setAlignment 居中，与列头同机制）
            spec.add(new Image(Tex.whiteui)).color(CH_COLORS[c]).size(W_CHIP, W_CHIP).padRight(4f);
            LabelRef chL = new LabelRef();
            chL.label = spec.add(String.valueOf(ci)).width(W_CH).center().padTop(2f).padBottom(2f).get();
            chL.label.setAlignment(arc.util.Align.center);
            chL.label.setColor(Color.lightGray);
            chLabels[ch] = chL;
            // 占用计数（节流刷新；列内居中；去除水平 pad 防列间缝隙显线）
            LabelRef occ = new LabelRef();
            occ.label = spec.add("").width(W_OCC).center().padTop(2f).padBottom(2f).get();
            occ.label.setAlignment(arc.util.Align.center);
            occ.label.setColor(Color.lightGray);
            occLabels[ch] = occ;
            // 本点干扰功率 I（列内居中：截图实测左对齐与居中列头错位）
            LabelRef itf = new LabelRef();
            itf.label = spec.add("").width(W_ITF).center().padTop(2f).padBottom(2f).get();
            itf.label.setAlignment(arc.util.Align.center);
            itf.label.setColor(Color.lightGray);
            itfLabels[ch] = itf;
            // 有效强度条（Prov<CharSequence> 构造器：值标签逐帧渲染，无逐帧分配）
            spec.add(new Bar(
                    () -> fmtEff(effBuf[ci]),
                    () -> CH_COLORS[c],
                    () -> effBuf[ci] / (float) SignalSource.MAX_STRENGTH
            )).growX().minWidth(W_BAR_MIN).height(18f).pad(1f);
            spec.row();
        }

        // colspan 动态取宿主当前列数（信号源面板=5 个信道按钮列；不同宿主列数不同，
        // 固定 colspan 会只跨部分列、把宿主前几列撑宽导致按钮/标题错位）
        parent.add(spec).growX().minWidth(SECTION_MIN).colspan(parent.getColumns()).pad(1f);
        parent.row();

        parent.update(() -> {
            // 5 tick（约 12 Hz）刷新：卫星是移动的，节流太长会让面板数字明显落后于 H 覆盖（看起来两边"不一致"）
            tick = (tick + 1) % 5;
            if (tick != 0) return;
            // 建筑可能在面板打开期间被摧毁——失效后立即停止采样（面板由 BlockConfigFragment 隐藏）
            if (at == null || !at.isValid()) return;
            // 聚合范围 = 本面板所属编码：信号源=自身编码、中继=绑定编码、检测器=无（逐信道最强编码）。
            // 判定端（中继激活/控制台绑定）本来就是逐编码的，这里对齐后"可用强度"列就是该编码真实可用的值。
            String scope = silicon.util.SignalOverlay.codeOf(at);
            // 显示端唯一实现：地面 SINR ⊕ 同编码卫星 RSS 合成（与 H 覆盖完全同一函数——
            // 所以 H 上的数字必然等于这里最高的那一行，不会两处对不上）
            SignalChannel.usableAll(at.team, at.x, at.y, effBuf, srcBuf, intBuf, codeBuf, scope);
            // 占用计数（信道拥挤度）：全部编码的在轨卫星，逐信道计数
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) satCnt[ch] = 0;
            for (SatelliteManager.SatelliteRecord r : SatelliteManager.satellites(at.team)) {
                if (r.code != null && !SignalChannel.hasLiveSource(at.team, r.code)) continue; // 上行门控
                int rc = r.channel;
                if (rc < 1 || rc > SignalJammer.CHANNEL_MAX) continue; // 未绑定卫星不进信道视图
                if (SatelliteManager.satelliteEffAt(r, at.x, at.y) > 0f) satCnt[rc]++;
            }
            int cur = currentChannel.get();
            scopeRef.label.setText(scope == null
                    ? Core.bundle.get("block.silicon-signal.spectrum.scope.auto")
                    : Core.bundle.format("block.silicon-signal.spectrum.scope.code", scope));
            // 占用计数与实际发射条件一致（signal/供电/enabled），断电或关闭的源不计入；
            // 占用是"信道拥挤度"（含所有编码与其他队伍的干扰器），与按编码的强度列口径不同。
            // 计数在信道循环外一次算完：原先每信道都要遍历一遍全部源/中继/干扰器（5×全表），
            // 且 signalChannel() 会对每台中继重复求值 5 次（其内部还会扫全部源与卫星名册）。
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) { srcCnt[ch] = 0; jamCnt[ch] = 0; }
            for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(at.team)) {
                if (sb.emitting() && sb.channel >= 1 && sb.channel <= SignalJammer.CHANNEL_MAX) srcCnt[sb.channel]++;
            }
            for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(at.team)) {
                if (!rb.active) continue;
                int rc = rb.signalChannel();
                if (rc >= 1 && rc <= SignalJammer.CHANNEL_MAX) srcCnt[rc]++;
            }
            for (SignalJammer.SignalJammerBuild jb : SignalJammer.allJammers()) {
                if (!jb.enabled) continue;
                if (jb.jamChannel == SignalJammer.ALL) {
                    for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) jamCnt[ch]++;
                } else if (jb.jamChannel >= 1 && jb.jamChannel <= SignalJammer.CHANNEL_MAX) {
                    jamCnt[jb.jamChannel]++;
                }
            }
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
                // 卫星计入占用：与地面源一样占用信道带宽
                int src = srcCnt[ch] + satCnt[ch];
                int jam = jamCnt[ch];
                // 强度列：usableAll 已把卫星层按同一编码 RSS 合成进 effBuf（H 覆盖用的是同一个函数，
                // 所以 H 上的数字 = 这里最高的一行，两边不会再出现不一致）
                occLabels[ch].label.setText(Core.bundle.format("block.silicon-signal.spectrum.src", src, jam));
                // 注意两列口径不同（有意）：本"占用"列只数「直接打在该信道」的干扰器，
                // 而下面"干扰"列来自 effectiveAll 的 jamA——它含邻信道泄漏（ACIR）与全信道干扰器。
                // 因此一台只打信道 2 的干扰器会在信道 1 的干扰列显示非 0、占用列显示 0。
                // I 标签必须预格式化：bundle.format 吃原始 float 会渲染全精度小数
                itfLabels[ch].label.setText(Core.bundle.format("block.silicon-signal.spectrum.i",
                        fmtEff(intBuf[ch] - SignalChannel.NOISE_FLOOR)));
                // 当前行高亮：信道号变色（行底色方案受嵌套布局挤压影响，弃用）
                chLabels[ch].label.setColor(ch == cur ? curColor : Color.lightGray);
            }
        });
    }

    /** 有效强度格式化（1 位小数；0 显示为 "--" 更直观） */
    private static String fmtEff(float v) {
        return v <= 0.01f ? "--" : arc.util.Strings.fixed(v, 1);
    }

    /** 标签句柄（行构建时捕获，节流刷新时 setText） */
    private static class LabelRef {
        arc.scene.ui.Label label;
    }
}
