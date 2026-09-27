package silicon.world.blocks.signal;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.util.Align;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;
import mindustry.world.meta.BuildVisibility;
import silicon.util.SignalOverlay;

/**
 * 单格信号探针（**调试方块**，仅沙盒可见）：与信号源完全同源，只把 {@link #radius} 压到 0.5 格——
 * 覆盖判定用 {@code dist >= radius} 归零，所以只有探针自己那一格的中心（dist = 0）在覆盖内，
 * 四邻格中心距离 1 格 &gt; 0.5 格，强度为 0。于是按住 H 后**全图只会有这一格显示数字**，
 * 用来核对"H 覆盖的数字是否正落在格子中心"。
 *
 * <p>自评读数：方块上方常驻一行 {@code code=XXXX ch1 r0.5 ON raw99}（编码 / 信道 / 半径 /
 * 开关 / 本格原始强度）——不依赖 H 覆盖即可判断探针自身状态，"为什么没信号"一眼可判。
 * 选中/放置预览画的是**真实覆盖**（本格 8×8 边框 + 格心十字），H 的数字应落在十字中心。
 *
 * <p>不耗电：父类构造里 {@code consumePower(150/60)} 加进 {@code consumeBuilder} 的消费者
 * 会在 {@code Block.init()} 阶段重建 {@code consPower} 并置 {@code hasPower=true}——只在构造期
 * 置 {@code consPower = null} 会被覆盖回去（信息面板显示耗电、无电画断电图标）。这里用
 * {@code removeConsumers} 直接清空 builder，init() 不再生成任何消费者。发射判定（strengthAt/
 * emitting）也覆写掉父类的"必须有电"闸门，放下即用。
 *
 * <p>放置后自动生成随机 4 位编码（与信号源同一逻辑，{@code placed()}），无需手动设置。
 * 与 {@code message-test} 一样属于随仓库维护的调试方块；不需要时删掉本类 + Blocks 里一行注册即可。
 */
public class SignalProbe extends SignalSource {

    /** 逐字度量（静态复用） */
    private static final GlyphLayout measure = new GlyphLayout();
    /** {@link SignalChannel#usableAll} 的出参缓冲（静态复用，与 H 覆盖同一函数同一口径） */
    private static final float[] probeEff = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final mindustry.gen.Building[] probeSrc = new mindustry.gen.Building[SignalJammer.CHANNEL_MAX + 1];
    private static final String[] probeCode = new String[SignalJammer.CHANNEL_MAX + 1];

    public SignalProbe(String name) {
        super(name);
        radius = 0.5f;              // 恰好覆盖自己那一格（中心 dist=0 有效，四邻 1 格 >= 0.5 无效）
        // 彻底移除电力依赖：清空 consumeBuilder，Block.init() 就不会重建 consPower/hasPower
        removeConsumers(cons -> true);
        buildType = SignalProbeBuild::new;
        buildVisibility = BuildVisibility.sandboxOnly;
        enableDrawStatus = false;   // 不画任何状态图标（断电/禁用之类）
    }

    /** 放置预览：真实覆盖 = 本格 8×8 边框（父类画 15 格圆，对 0.5 格半径纯属误导） */
    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        float px = x * 8f, py = y * 8f;
        Draw.color(valid ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, 0.8f);
        Lines.stroke(1.5f);
        Lines.line(px, py, px + 8f, py);
        Lines.line(px, py + 8f, px + 8f, py + 8f);
        Lines.line(px, py, px, py + 8f);
        Lines.line(px + 8f, py, px + 8f, py + 8f);
        Lines.stroke(1f);
        Draw.reset();
    }

    public class SignalProbeBuild extends SignalSourceBuild {

        /** 与父类唯一差别：去掉"必须有电"闸门（探针不接电网），其余口径完全一致 */
        @Override
        public float strengthAt(float wx, float wy) {
            if (signal == null || !enabled) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy, radius());
        }

        @Override
        public boolean emitting() {
            return signal != null && enabled;
        }

        /** 选中显示：真实覆盖（本格边框 + 格心十字）——对齐测试参照物，H 数字应落在十字中心 */
        @Override
        public void drawSelect() {
            Draw.color(emitting() ? SignalOverlay.SIGNAL_COLOR : SignalOverlay.NO_SIGNAL_COLOR, 0.9f);
            Lines.stroke(1.5f);
            Lines.line(x - 4f, y - 4f, x + 4f, y - 4f);
            Lines.line(x - 4f, y + 4f, x + 4f, y + 4f);
            Lines.line(x - 4f, y - 4f, x - 4f, y + 4f);
            Lines.line(x + 4f, y - 4f, x + 4f, y + 4f);
            Lines.line(x - 2.5f, y, x + 2.5f, y);
            Lines.line(x, y - 2.5f, x, y + 2.5f);
            Lines.stroke(1f);
            Draw.reset();
        }

        /** 常驻自评读数（画在方块上方，不依赖 H 覆盖）：
         *  编码 / 信道 / 半径 / 开关 / 本格原始强度 / **H 覆盖同一函数在本格的结果** / 与最近采样点的偏移。
         *  <p>{@code cell} 走 {@link silicon.world.blocks.signal.SignalChannel#usableAll}——就是 H 覆盖逐格
         *  调用的那个函数；{@code off} 是「本格中心」与「覆盖层采样点」（世界坐标 ≡ 4 (mod 8)）的差，
         *  单位是格。若 off 不是 0，说明采样网格与建筑中心错位（半径 0.5 格的探针会因此整格采不到）。 */
        @Override
        public void draw() {
            super.draw();
            SignalChannel.usableAll(team, x, y, probeEff, probeSrc, null, probeCode, null);
            float best = 0f;
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) best = Math.max(best, probeEff[ch]);
            float sx = Mathf.round((x - 4f) / 8f) * 8f + 4f;
            float sy = Mathf.round((y - 4f) / 8f) * 8f + 4f;
            String txt = (signal == null ? "code=----" : "code=" + signal.name)
                    + " ch" + channel
                    + " r" + radius()
                    + (emitting() ? " ON" : " OFF")
                    + " raw" + Mathf.round(strengthAt(x, y))
                    + " cell" + Mathf.round(best)
                    + " off" + arc.util.Strings.fixed((x - sx) / 8f, 2)
                    + "," + arc.util.Strings.fixed((y - sy) / 8f, 2);
            float prevZ = Draw.z();
            Draw.z(Layer.effect);
            float oldScale = Fonts.def.getData().scaleX;
            Color oldColor = Fonts.def.getColor().cpy();
            Fonts.def.getData().setScale(0.25f);
            measure.setText(Fonts.def, txt);
            Fonts.def.setColor(1f, 1f, 1f, 0.95f);
            Fonts.def.draw(txt, x - measure.width * 0.5f, y + 13f, Align.left);
            // 红色小圈 = 最近的覆盖层采样点（与贴图准星/中心绿点比对，肉眼也能看出错位）
            Lines.stroke(1f);
            Draw.color(Color.scarlet, 0.95f);
            Lines.circle(sx, sy, 2.5f);
            Fonts.def.getData().setScale(oldScale);
            Fonts.def.setColor(oldColor);
            Draw.z(prevZ);
            Draw.reset();
        }
    }
}
