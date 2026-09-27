package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.GlyphLayout;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.ui.Label;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.blocks.signal.SignalJammer;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalRelay.SignalRelayBuild;
import silicon.world.blocks.signal.SignalSource;
import silicon.world.blocks.signal.SignalSource.SignalSourceBuild;
import silicon.util.SatelliteManager;

/**
 * 信号覆盖显示：H 键查看信号源覆盖。
 * 无论设置开关如何，按住 H 键始终显示信号强度；
 * 设置开启「切换」时，按一下 H 键可切换显示/隐藏（按住仍优先显示）。
 * 进入显示模式时屏幕下方中间显示一行提示小字。
 * 缩放视角较小时（视野 &gt; 阈值）逐格以数字显示信号强度；
 * 缩放视角较大时以绿色显示信号范围（强度随距离变淡），无信号显示为灰色。
 */
public class SignalOverlay {
    /** 信号显示颜色渐变：高强度=深蓝，低强度=浅蓝（不同强度颜色差异明显） */
    public static final Color DEEP_BLUE = Color.valueOf("1e4fb0");
    public static final Color LIGHT_BLUE = Color.valueOf("9dc3ff");
    /** 信号源选中/放置预览的范围圆颜色（深蓝） */
    public static final Color SIGNAL_COLOR = Color.valueOf("3a6fe0");
    /** 无信号颜色（灰色） */
    public static final Color NO_SIGNAL_COLOR = Color.valueOf("9a9a9a");
    /** 信号源区分色板（备用：无编码时兜底；有编码时按 HSV 色相生成，颜色数量不限） */
    public static final Color[] SOURCE_COLORS = {
            Color.valueOf("e05555"), // 红
            Color.valueOf("e08a3a"), // 橙
            Color.valueOf("e0c43a"), // 黄
            Color.valueOf("9ec42a"), // 黄绿
            Color.valueOf("5fb04c"), // 绿
            Color.valueOf("2fbf8f"), // 青绿
            Color.valueOf("3ac0c0"), // 青
            Color.valueOf("3aa8e0"), // 天蓝
            Color.valueOf("4a6fe0"), // 蓝
            Color.valueOf("6a4ae0"), // 紫蓝
            Color.valueOf("8a4ae0"), // 紫
            Color.valueOf("bf4ae0"), // 品红
            Color.valueOf("e04a9a"), // 粉
            Color.valueOf("d07a4a"), // 棕
            Color.valueOf("9a9a9a"), // 灰
            Color.valueOf("c0c0c0"), // 银
    };
    /** 信号专属颜色缓存（编码 → Color），避免每帧分配 */
    private static final ObjectMap<String, Color> colorCache = new ObjectMap<>();
    /** 已分配的色相（度），用于为新信号选择与已有颜色差异最大的色相（保证颜色明显不同） */
    private static final Seq<Float> usedHues = new Seq<>();
    /** 色相使用次数：同色系（色相接近）时按次数交替亮度，进一步拉开区分 */
    private static final ObjectIntMap<Float> hueCount = new ObjectIntMap<>();
    /** 缩放阈值（相机视野宽度，像素）：视野宽于该值（缩小视角）显示蓝色范围，否则显示数字 */
    public static final float ZOOM_THRESHOLD_WIDTH = 600f;
    /** 预计算的强度数字字符串（0~MAX_STRENGTH），避免每帧分配 */
    private static final String[] NUMBER_STRINGS = new String[SignalSource.MAX_STRENGTH + 1];
    /** 逐格量字用的 GlyphLayout（静态复用，避免每格分配） */
    private static final GlyphLayout measure = new GlyphLayout();

    static {
        for (int i = 0; i < NUMBER_STRINGS.length; i++) {
            NUMBER_STRINGS[i] = String.valueOf(i);
        }
    }

    /** 信号专属颜色：色相动态分配——新编码选择与所有已用颜色色相距离最大、且与所在区块背景色相差异大的色相
     *  （避开背景相近色，优先补色方向；bgHue=-1 表示背景无彩/未知，不限制）。
     *  信号间区分：色相最远点（主）；信号极多导致色相被迫接近（同色系）时，按色相使用次数交替亮度（0.75/0.55/0.95）
     *  与地图区分：有彩背景按色相避开 ±45°，无彩背景靠固定亮度（0.75）天然对比。结果缓存复用 */
    public static Color signalColor(String code, float bgHue) {
        Color cached = colorCache.get(code);
        if (cached != null) return cached;
        float hue;
        float minDist; // 与已用色相的最小距离（用于判断是否同色系）
        if (usedHues.isEmpty()) {
            // 第一个：取编码哈希色相，若与背景色相近则偏移到补色方向
            int h0 = code.hashCode() & 0x7fffffff;
            hue = h0 % 360f;
            if (bgHue >= 0f && hueDist(hue, bgHue) < 45f) {
                hue = (bgHue + 180f) % 360f;
            }
            minDist = 360f;
        } else {
            // 贪心最远点：遍历候选色相（5° 步进），剔除与背景色相相近的候选，
            // 选与已用色相环距离最小者最大化的候选（颜色间必然明显可辨）
            float bestHue = 0f, bestMin = -1f;
            for (float cand = 0f; cand < 360f; cand += 5f) {
                if (bgHue >= 0f && hueDist(cand, bgHue) < 45f) continue; // 与背景太近，跳过
                float curMin = 360f;
                for (int i = 0; i < usedHues.size; i++) {
                    float d = hueDist(cand, usedHues.get(i));
                    if (d < curMin) curMin = d;
                }
                if (curMin > bestMin) {
                    bestMin = curMin;
                    bestHue = cand;
                }
            }
            hue = bestHue;
            minDist = bestMin;
        }
        usedHues.add(hue);
        // 亮度：默认 75（0~100，arc HSVtoRGB 的 value 为百分比）；同色系（色相最小距离 < 30°）时
        // 按该色相使用次数交替 100/40/75（亮/暗/标准），明暗差异明显
        int count = hueCount.get(hue, 0);
        float value = 75f;
        if (minDist < 30f) {
            int m = count % 3;
            value = m == 1 ? 40f : (m == 2 ? 100f : 75f);
        }
        hueCount.put(hue, count + 1);
        // 饱和度 85%（0~100）；亮度按上取值——注意 arc HSVtoRGB 的 s/v 是 0~100 百分比（0~1 会导致近黑）
        Color c = Color.HSVtoRGB(hue, 85f, value);
        colorCache.put(code, c);
        return c;
    }

    /** 色相环最短距离（0~180） */
    static float hueDist(float a, float b) {
        float d = Math.abs(a - b);
        return d > 180f ? 360f - d : d;
    }

    /** RGB 颜色 → 色相（度，0~360；无彩/近灰返回 -1） */
    static float hueOf(Color c) {
        float r = c.r, g = c.g, b = c.b;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 0.01f) return -1f;
        float h;
        if (max == r) h = 60f * ((g - b) / (max - min));
        else if (max == g) h = 60f * (2f + (b - r) / (max - min));
        else h = 60f * (4f + (r - g) / (max - min));
        if (h < 0f) h += 360f;
        return h;
    }

    /** 世界坐标处地形区块颜色色相（基于地面 mapColor；无地面/无彩返回 -1） */
    static float groundHue(float wx, float wy) {
        mindustry.world.Tile t = Vars.world.tileWorld(wx, wy);
        if (t == null || t.floor() == null) return -1f;
        return hueOf(t.floor().mapColor);
    }

    /** 信号源颜色：基于信号编码 + 所在区块背景色相生成（同一信号源颜色稳定） */
    public static Color sourceColor(SignalSourceBuild sb) {
        if (sb.signal == null) return SOURCE_COLORS[0];
        return signalColor(sb.signal.name, groundHue(sb.x, sb.y));
    }

    /** 中继器颜色：基于世界坐标 + 该处区块背景色相生成（标识不同转发来源，稳定） */
    public static Color relayColor(SignalRelayBuild rb) {
        return signalColor("R" + ((int) rb.x * 7 + (int) rb.y * 13), groundHue(rb.x, rb.y));
    }

    /** 卫星覆盖颜色：按编码专属色（与所选信号源同色，编码相同命中同一缓存）；
     *  无编码（未绑定兜底记录）→ 浅蓝→深蓝强度渐变 */
    static Color satelliteColor(String code, float t, Color out) {
        if (code != null) {
            out.set(signalColor(code, -1f));
        } else {
            out.set(LIGHT_BLUE).lerp(DEEP_BLUE, t);
        }
        return out;
    }

    /** 覆盖中某建筑的信号颜色：中继器绑定信号源后与所选源同色（信号身份一致），未绑定用自身色 */
    static Color buildingColor(Building b) {
        if (b instanceof SignalSourceBuild sb) return sourceColor(sb);
        if (b instanceof SignalRelayBuild rb) {
            SignalSource.SignalSourceBuild src = rb.findSource();
            return src != null ? sourceColor(src) : relayColor(rb);
        }
        return LIGHT_BLUE;
    }

    private static boolean visible = false;
    private static boolean toggleVisible = false;
    private static boolean prevDown = false;
    /** 淡入淡出透明度（0~1，每帧向目标过渡） */
    private static float displayAlpha = 0f;
    /** 当前显示模式（true=范围，false=数字），用于切换时淡入淡出 */
    private static boolean lastRangeMode = false;
    /** 底部提示标签 */
    private static Label hintLabel;

    public static void init() {
        // 无头服务器跳过（无渲染循环/无 UI），避免访问 Vars.ui.hudGroup 崩溃
        if (Vars.headless) return;
        // 渲染循环最末段绘制（drawOver：全部世界渲染——方块/单位/子弹/特效——之后触发），
        // 覆盖层画在子弹与枪口加色光之上，避免射击时被冲淡
        Events.run(EventType.Trigger.drawOver, SignalOverlay::update);
        // 客户端加载完成后创建底部提示标签
        Events.on(EventType.ClientLoadEvent.class, e -> {
            // 模组重载等场景重复触发时先移除旧标签，避免泄漏
            if (hintLabel != null) hintLabel.remove();
            hintLabel = new Label(Core.bundle.get("signal.overlay.hint"), Styles.outlineLabel);
            hintLabel.setFontScale(0.7f);
            hintLabel.visible = false;
            Vars.ui.hudGroup.addChild(hintLabel);
        });
    }

    /** 世界加载时重置：清空颜色缓存/色相分配，避免跨世界累积（新地图重新分配颜色、缓存不泄漏） */
    public static void reset() {
        colorCache.clear();
        usedHues.clear();
        hueCount.clear();
        // 重置显示状态，避免上一个世界的 H 键切换状态残留
        visible = false;
        toggleVisible = false;
        prevDown = false;
        displayAlpha = 0f;
        // 显示模式一并复位,否则跨图首帧 rangeMode 与残留的 lastRangeMode 不一致
        // 会触发一次无意义的淡入重启
        lastRangeMode = false;
    }

    static void update() {
        // 先取局部引用再判空，避免 null 检查与 team() 调用之间玩家断线导致的空指针
        Player player = Vars.player;
        if (player == null) return;
        Team team = player.team();
        boolean toggleMode = Core.settings.getBool("signal.hkey.toggle", true);
        // H 键走官方 KeyBind 通道（Silicon.init 注册，默认 H，可在按键设置重绑定；
        // headless 下 bind 未注册/无输入源时恒为 false，覆盖层在专用服务器本就不绘制）
        boolean hold = silicon.Silicon.keySignalView != null && Core.input.keyDown(silicon.Silicon.keySignalView);
        // 无论设置开关如何，按住 H 始终显示信号强度
        if (toggleMode) {
            // 切换模式：按一下 H 翻转切换状态（按住优先显示）
            if (hold && !prevDown) toggleVisible = !toggleVisible;
            prevDown = hold;
            visible = hold || toggleVisible;
        } else {
            // 按住模式：按住显示，松开隐藏
            visible = hold;
        }
        // 淡入淡出：透明度每帧向目标过渡（约 6 帧完成）
        displayAlpha = Mathf.lerp(displayAlpha, visible ? 1f : 0f, 0.15f);
        // 当前查看的编码只解析一次/帧（鼠标悬停或配置面板打开的建筑），逐格复用
        String view = viewCode();
        if (displayAlpha > 0.01f) {
            drawOverlay(team, displayAlpha, view);
        }
        if (visible) {
            showHint(view);
        } else if (displayAlpha < 0.01f) {
            hideHint();
        }
    }

    /** 显示底部提示小字（屏幕下方中间）+ 当前聚合范围（查看的编码 / 自动） */
    static void showHint(String viewCode) {
        if (hintLabel == null) return;
        hintLabel.setText(Core.bundle.get("signal.overlay.hint") + (viewCode == null
                ? Core.bundle.get("signal.overlay.view.auto")
                : Core.bundle.format("signal.overlay.view.code", viewCode)));
        hintLabel.setPosition(Core.graphics.getWidth() / 2f - hintLabel.getPrefWidth() / 2f, 40f);
        hintLabel.visible = true;
    }

    static void hideHint() {
        if (hintLabel != null) hintLabel.visible = false;
    }

    static void drawOverlay(Team team, float alpha, String viewCode) {
        // 显式抬高绘制层级：drawOver 时点当前 z 不确定，锁定在 overlayUI 之上保证压过所有世界内容
        Draw.z(mindustry.graphics.Layer.overlayUI + 1f);
        // 视野宽（缩小视角）显示蓝色范围；视野窄（放大视角）显示数字
        boolean rangeMode = Core.camera.width >= ZOOM_THRESHOLD_WIDTH;
        // 模式切换时重新淡入（数字 ↔ 范围淡入淡出）
        if (rangeMode != lastRangeMode) {
            lastRangeMode = rangeMode;
            displayAlpha = 0f;
        }
        if (rangeMode) {
            // 范围模式：逐格合成绘制（地面信号源与在轨卫星同一模型，按同一编码聚合）
            drawRangeComposite(team, alpha, viewCode);
        } else {
            // 数字模式：逐格取该格最强编码的有效信号（含底噪/CCI/ACI/干扰器），每格只绘制一次
            drawNumbersOverlay(team, alpha, viewCode);
        }
        Draw.reset();
    }

    /** 每信道有效强度/最强源缓冲（静态复用） */
    private static final float[] effBuf = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] srcBuf = new Building[SignalJammer.CHANNEL_MAX + 1];
    /** 每信道结果所属编码缓冲（按编码视图 / 自动模式下该信道最强身份的编码） */
    private static final String[] codeBuf = new String[SignalJammer.CHANNEL_MAX + 1];
    /** 最强来源/最强卫星编码出参复用（渲染线程内串行使用,两处 draw 循环共享一份） */
    private static final Building[] bestSrcTmp = new Building[1];
    private static final String[] bestCodeTmp = new String[1];

    /**
     * 当前查看的编码（显示端按它聚合，与判定端口径一致）：
     * ① 配置面板打开的建筑（信号源=自身编码 / 中继=绑定编码 / 控制台=所选编码）；
     * ② 鼠标悬停的建筑（同上，便于随手核对某个中继的覆盖）；
     * ③ 都没有 → null = 自动模式（每格取最强的那**一个**编码，而不是跨编码求和）。
     */
    static String viewCode() {
        Building b = null;
        if (Vars.control != null && Vars.control.input != null && Vars.control.input.config != null
                && Vars.control.input.config.isShown()) {
            b = Vars.control.input.config.getSelected();
        }
        if (b == null && Vars.world != null) {
            arc.math.geom.Vec2 m = Core.input.mouseWorld();
            b = Vars.world.buildWorld(m.x, m.y);
        }
        // 只看本队建筑：悬停敌方信号源时不该切成对方的编码口径（本队没有该编码就显示自动）
        if (b != null && Vars.player != null && b.team != Vars.player.team()) b = null;
        return codeOf(b);
    }

    /** 建筑携带的编码：信号源=自身编码；中继=绑定编码；卫星控制台=所选编码；其余（含检测器）→ null */
    public static String codeOf(Building b) {
        if (b instanceof SignalSourceBuild sb) {
            return sb.signal == null ? null : sb.signal.name;
        }
        if (b instanceof SignalRelayBuild rb) {
            return (rb.selectedSource == null || rb.selectedSource.isEmpty()) ? null : rb.selectedSource;
        }
        if (b instanceof silicon.world.blocks.satellite.SatelliteConsole.SatelliteConsoleBuild cb) {
            return cb.selectedSignal;
        }
        return null;
    }

    /** 每格最大有效信号（**与频谱面板同一实现**：SignalChannel.usableAll，逐信道地面 SINR ⊕ 同编码卫星 RSS）。     *  返回该格最强信道的可用度、贡献来源建筑与归属编码——因此 H 上的数字必然等于频谱里最高的那一行。
     *  <p>viewCode 非 null 时只算该编码（悬停/配置面板打开的建筑）；为 null 时自动：逐信道取该信道地面最强编码，
     *  该信道没有地面信号才取该信道最强卫星编码，绝不跨编码求和。</p> */
    static float bestSignal(Team team, float wx, float wy, Building[] bestSrcOut, String[] bestCodeOut, String viewCode) {
        SignalChannel.usableAll(team, wx, wy, effBuf, srcBuf, null, codeBuf, viewCode);
        float best = 0f;
        Building bestSrc = null;
        String bestCode = null;
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            if (effBuf[ch] > best) {
                best = effBuf[ch];
                bestSrc = srcBuf[ch];
                bestCode = codeBuf[ch];
            }
        }
        bestSrcOut[0] = bestSrc;
        // 卫星层主导（或无地面信号）时用编码色；编码为 null（未绑定卫星）时绘制端走蓝色渐变
        bestCodeOut[0] = bestSrc == null ? bestCode : null;
        return best;
    }

    /** 本帧"可能有信号"的包围盒（本队源 / 已激活中继 / 在轨卫星覆盖圆的并集）——逐格绘制前的廉价裁剪。
     *  干扰器只会压制、不会产生信号，所以不参与包围盒。 */
    private static final Rect coverBounds = new Rect();

    static void computeCoverBounds(Team team) {
        float r = SignalSource.RADIUS * 8f;
        float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE;
        for (SignalSourceBuild sb : SignalSource.allSources(team)) {
            if (sb.signal == null) continue;
            float sr = sb.radius() * 8f; // 每方块半径（调试探针只有 0.5 格）
            minx = Math.min(minx, sb.x - sr);
            miny = Math.min(miny, sb.y - sr);
            maxx = Math.max(maxx, sb.x + sr);
            maxy = Math.max(maxy, sb.y + sr);
        }
        for (SignalRelayBuild rb : SignalRelay.allRelays(team)) {
            if (!rb.active) continue;
            minx = Math.min(minx, rb.x - r);
            miny = Math.min(miny, rb.y - r);
            maxx = Math.max(maxx, rb.x + r);
            maxy = Math.max(maxy, rb.y + r);
        }
        for (SatelliteManager.SatelliteRecord rec : SatelliteManager.satellites(team)) {
            Unit u = Groups.unit.getByID(rec.unitId);
            if (u == null) continue;
            float cr = SatelliteManager.coverageRadius(rec.orbit);
            minx = Math.min(minx, u.x - cr);
            miny = Math.min(miny, u.y - cr);
            maxx = Math.max(maxx, u.x + cr);
            maxy = Math.max(maxy, u.y + cr);
        }
        if (minx > maxx) {
            coverBounds.set(0f, 0f, 0f, 0f); // 本队没有任何发射机：整帧跳过
        } else {
            coverBounds.set(minx, miny, maxx - minx, maxy - miny);
        }
    }

    /** 数字模式：可见区域内逐格取各信道最大有效信号，每格只绘制一次（字号覆盖一格 8px）；颜色取最强来源的专属色 */
    static void drawNumbersOverlay(Team team, float alpha, String viewCode) {
        Rect view = Core.camera.bounds(Tmp.r1);
        int x0 = (int) (view.x / 8f) - 1, x1 = (int) ((view.x + view.width) / 8f) + 1;
        int y0 = (int) (view.y / 8f) - 1, y1 = (int) ((view.y + view.height) / 8f) + 1;
        float digitAlpha = Core.settings.getInt("signal.digitAlpha", 80) / 100f;
        // 保存字体原始颜色与比例，绘制后恢复（try-finally 保证异常时也恢复）
        Color oldFontColor = Fonts.def.getColor();
        float oldScale = Fonts.def.getData().scaleX;
        // 字号按显示屏大小动态变化（非相机缩放）：动态检测屏幕（物理显示）高度与当前游戏（逻辑）分辨率高度。
        // 全屏 2K（uiScale=1）时二者相等 → 字号 0.2（基准）；高 DPI（uiScale>1）时按 1/uiScale 缩小，物理观感稳定
        float screenH = Core.graphics.getBackBufferHeight();
        float scale = Mathf.clamp(0.2f * Core.graphics.getHeight() / (screenH <= 0f ? 1440f : screenH), 0.1f, 0.5f);
        Fonts.def.getData().setScale(scale);
        Building[] bestSrc = bestSrcTmp;
        String[] bestCode = bestCodeTmp;
        try {
            // 采样点 = 建筑所在网格：引擎里 1×1 建筑的 (x,y) 就是 8 的整数倍（tile 索引 × 8），
            // 多格建筑的中心同样落在 8 的整数倍上。旧代码用 gx*8 + 4 采样，整整偏了半格——普通信号源
            // 只是"数字向方块右上角偏移"，而半径 0.5 格的调试探针每个采样点距离都是 √2/2 ≈ 0.707 格 > 0.5，
            // 于是整格采不到、看起来像"不发射"（实测 off = -0.50,-0.50 定位到此）。采样与绘字都改用
            // gx*8（= tile 索引 × 8）后，数字才落在建筑/地格的格心上。
            // 对齐方式：**自己按度量算锚点**，不依赖 arc 的 Align 语义。
            // arc 的 Font.draw(str,x,y,align) 把 align 当 halign 用、纵向锚在**基线**上（arc 的 GlyphLayout
            // 那一层没有 valign 参数），所以 (x,y) 实际是"文字左下角"：只传 Align.center 会让数字整体跑到
            // 采样点的**右上角**。这里用 GlyphLayout 量出宽度、用 capHeight 当高度，
            // 把左下角锚点挪到 (采样点 − 尺寸/2)，数字视觉中心才落在格心上。
            float cell = 8f;
            final float digitH = Fonts.def.getCapHeight();
            computeCoverBounds(team); // 本帧覆盖包围盒：盒外格子直接跳过，避免每格跑完整 SINR 批算
            for (int gx = x0; gx <= x1; gx++) {
                for (int gy = y0; gy <= y1; gy++) {
                    float wx = gx * cell, wy = gy * cell; // 建筑网格点（引擎里 tile 索引 × 8）
                    if (!coverBounds.contains(wx, wy)) continue;
                    float s = bestSignal(team, wx, wy, bestSrc, bestCode, viewCode);
                    if (s <= 0f) continue;
                    int val = Mathf.round(s);
                    float t = s / SignalSource.MAX_STRENGTH;
                    // 颜色：最强来源的专属色（信号源/中继器不同色）；仅卫星信号时为最强贡献卫星的编码色（未绑定浅蓝→深蓝渐变）
                    if (bestSrc[0] != null) {
                        Tmp.c1.set(buildingColor(bestSrc[0]));
                    } else {
                        satelliteColor(bestCode[0], t, Tmp.c1);
                    }
                    Tmp.c1.a((0.6f + 0.4f * t) * digitAlpha * alpha);
                    // 复用预计算字符串避免分配
                    String num = NUMBER_STRINGS[Mathf.clamp(val, 0, SignalSource.MAX_STRENGTH)];
                    Fonts.def.setColor(Tmp.c1);
                    measure.setText(Fonts.def, num);
                    Fonts.def.draw(num,
                            wx - measure.width * 0.5f,
                            wy - digitH * 0.5f,
                            Align.left);
                }
            }
        } finally {
            // 恢复默认颜色与字号，避免影响其他字体渲染
            Fonts.def.setColor(oldFontColor);
            Fonts.def.getData().setScale(oldScale);
        }
    }

    /** 范围模式（逐格合成）：每格按"当前查看的编码"聚合地面与卫星（卫星层为非相干功率合成 √(Σeᵢ²)，
     *  与地面 RSS 合成 total = √(g² + s²)），用最强来源的专属颜色绘制（重叠/干扰区显示最强或空白）。
     *  卫星信号与地面信号源共用同一透明度公式 (0.45+0.35t)·rangeAlpha·alpha 与同一强度标度
     *  （t = 强度/MAX_STRENGTH），每格只绘制一次——卫星覆盖透明度与信号源完全一致 */
    static void drawRangeComposite(Team team, float alpha, String viewCode) {
        Rect view = Core.camera.bounds(Tmp.r1);
        float rpx = SignalSource.RADIUS * 8f;
        // 格子范围：视口外扩一个覆盖半径（源在视口外但覆盖进入视口）
        int x0 = (int) ((view.x - rpx) / 8f) - 1, x1 = (int) ((view.x + view.width + rpx) / 8f) + 1;
        int y0 = (int) ((view.y - rpx) / 8f) - 1, y1 = (int) ((view.y + view.height + rpx) / 8f) + 1;
        // 范围模式透明度（0~100，设置项）
        float rangeAlpha = Core.settings.getInt("signal.rangeAlpha", 45) / 100f;
        Building[] bestSrc = bestSrcTmp;
        String[] bestCode = bestCodeTmp;
        computeCoverBounds(team); // 盒外格子直接跳过
        for (int gx = x0; gx <= x1; gx++) {
            for (int gy = y0; gy <= y1; gy++) {
                // 采样点 = 建筑网格点（tile 索引 × 8，见 drawNumbersOverlay 里的说明）：
                // Fill.rect 以中心为锚，采样点与填充中心同一处，格网才与建筑/地格对齐
                float wx = gx * 8f, wy = gy * 8f;
                if (!coverBounds.contains(wx, wy)) continue;
                float s = bestSignal(team, wx, wy, bestSrc, bestCode, viewCode);
                if (s <= 0f) continue;
                float t = s / SignalSource.MAX_STRENGTH;
                // 最强来源的专属颜色（仅卫星时为最强贡献卫星的编码色，未绑定浅蓝），不透明度随强度
                if (bestSrc[0] != null) {
                    Draw.color(buildingColor(bestSrc[0]), (0.45f + 0.35f * t) * rangeAlpha * alpha);
                } else {
                    satelliteColor(bestCode[0], t, Tmp.c2);
                    Draw.color(Tmp.c2, (0.45f + 0.35f * t) * rangeAlpha * alpha);
                }
                Fill.rect(wx, wy, 8f, 8f);
            }
        }
    }
}
