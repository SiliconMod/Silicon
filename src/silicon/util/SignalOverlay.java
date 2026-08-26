package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.ui.Label;
import arc.struct.Seq;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalRelay.SignalRelayBuild;
import silicon.world.blocks.signal.SignalSource;
import silicon.world.blocks.signal.SignalSource.SignalSourceBuild;

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
    /** 缩放阈值（相机视野宽度，像素）：视野宽于该值（缩小视角）显示蓝色范围，否则显示数字 */
    public static final float ZOOM_THRESHOLD_WIDTH = 600f;
    /** 预计算的强度数字字符串（0~15），避免每帧分配 */
    private static final String[] NUMBER_STRINGS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"};

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
        // 渲染循环方块层绘制后触发（每帧）
        Events.run(EventType.Trigger.draw, SignalOverlay::update);
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

    static void update() {
        // 先取局部引用再判空，避免 null 检查与 team() 调用之间玩家断线导致的空指针
        Player player = Vars.player;
        if (player == null) return;
        Team team = player.team();
        boolean toggleMode = Core.settings.getBool("signal.hkey.toggle", true);
        boolean hold = Core.input.keyDown(KeyCode.h);
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
        if (displayAlpha > 0.01f) {
            drawOverlay(team, displayAlpha);
        }
        if (visible) {
            showHint();
        } else if (displayAlpha < 0.01f) {
            hideHint();
        }
    }

    /** 显示底部提示小字（屏幕下方中间） */
    static void showHint() {
        if (hintLabel == null) return;
        hintLabel.setPosition(Core.graphics.getWidth() / 2f - hintLabel.getPrefWidth() / 2f, 40f);
        hintLabel.visible = true;
    }

    static void hideHint() {
        if (hintLabel != null) hintLabel.visible = false;
    }

    /** 信号源与激活中继器列表（静态复用，避免每帧分配） */
    private static final Seq<Building> sources = new Seq<>();

    static void drawOverlay(Team team, float alpha) {
        // 视野宽（缩小视角）显示蓝色范围；视野窄（放大视角）显示数字
        boolean rangeMode = Core.camera.width >= ZOOM_THRESHOLD_WIDTH;
        // 模式切换时重新淡入（数字 ↔ 范围淡入淡出）
        if (rangeMode != lastRangeMode) {
            lastRangeMode = rangeMode;
            displayAlpha = 0f;
        }
        // 收集所有信号源与已激活中继器（同队；静态列表复用，不产生分配）
        sources.clear();
        sources.addAll(SignalSource.allSources(team));
        for (SignalRelayBuild rb : SignalRelay.allRelays(team)) {
            if (rb.active) sources.add(rb);
        }
        if (sources.isEmpty()) return;
        // 视野裁剪：屏幕外（含信号半径外扩）的来源跳过，避免大量来源时每帧绘制全部
        Rect view = Core.camera.bounds(Tmp.r1).grow(SignalSource.RADIUS * 8f + 8f);
        // 每个源独立绘制其覆盖（O(n × r²)，避免每格再遍历全部源）
        for (Building b : sources) {
            if (!view.contains(b.x, b.y)) continue;
            if (rangeMode) {
                drawRange(b, alpha);
            } else {
                drawNumbers(b, alpha);
            }
        }
        Draw.reset();
    }

    /** 该源/中继器在 (wx, wy) 的信号强度（信号源无信号、中继器未激活时为 0） */
    static float sourceStrength(Building b, float wx, float wy) {
        if (b instanceof SignalSourceBuild sb) {
            return sb.signal == null ? 0f : SignalSource.strengthAt(b.x, b.y, wx, wy);
        }
        if (b instanceof SignalRelayBuild rb) {
            return rb.active ? SignalSource.strengthAt(b.x, b.y, wx, wy) : 0f;
        }
        return 0f;
    }

    /** 数字模式：在信号覆盖圆内每格绘制强度数字（强度高=深蓝，低=浅蓝渐变；透明度由设置调节） */
    static void drawNumbers(Building b, float alpha) {
        int r = (int) SignalSource.RADIUS;
        float radiusPx = SignalSource.RADIUS * 8f;
        // 数字模式透明度（0~100，设置项）
        float digitAlpha = Core.settings.getInt("signal.digitAlpha", 80) / 100f;
        // 保存字体原始颜色与比例，绘制后恢复（try-finally 保证异常时也恢复，避免污染全局字体状态）
        Color oldFontColor = Fonts.def.getColor();
        float oldScale = Fonts.def.getData().scaleX;
        Fonts.def.getData().setScale(0.2f);
        float radiusSq = radiusPx * radiusPx;
        try {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    float wx = b.x + dx * 8f, wy = b.y + dy * 8f; // 格子中心（像素）
                    float ddx = wx - b.x, ddy = wy - b.y;
                    if (ddx * ddx + ddy * ddy > radiusSq) continue; // 平方距离比较，避免 sqrt
                    float s = sourceStrength(b, wx, wy);
                    if (s <= 0) continue;
                    int val = Mathf.round(s);
                    float t = s / SignalSource.MAX_STRENGTH;
                    // 浅蓝 → 深蓝渐变（强度越高越深）
                    Color c = Tmp.c1.set(LIGHT_BLUE).lerp(DEEP_BLUE, t);
                    c.a((0.6f + 0.4f * t) * digitAlpha * alpha);
                    // 复用预计算字符串避免分配
                    Fonts.def.setColor(c);
                    Fonts.def.draw(NUMBER_STRINGS[val < 0 ? 0 : (val > 15 ? 15 : val)], wx - 1.2f, wy - 0.8f);
                }
            }
        } finally {
            // 恢复默认颜色与字号，避免影响其他字体渲染
            Fonts.def.setColor(oldFontColor);
            Fonts.def.getData().setScale(oldScale);
        }
    }

    /** 范围模式：半透明蓝色渐变填充信号覆盖圆（每格 8px，不挡方块），强度高=深蓝、低=浅蓝 */
    static void drawRange(Building b, float alpha) {
        int r = (int) SignalSource.RADIUS;
        float radiusPx = SignalSource.RADIUS * 8f;
        // 范围模式透明度（0~100，设置项）
        float rangeAlpha = Core.settings.getInt("signal.rangeAlpha", 45) / 100f;
        float radiusSq = radiusPx * radiusPx;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                float wx = b.x + dx * 8f, wy = b.y + dy * 8f; // 格子中心（像素）
                float ddx = wx - b.x, ddy = wy - b.y;
                if (ddx * ddx + ddy * ddy > radiusSq) continue; // 平方距离比较，避免 sqrt
                float s = sourceStrength(b, wx, wy);
                if (s <= 0) continue;
                float t = s / SignalSource.MAX_STRENGTH;
                // 浅蓝 → 深蓝渐变（强度越高越深）
                Tmp.c1.set(LIGHT_BLUE).lerp(DEEP_BLUE, t);
                Draw.color(Tmp.c1, (0.1f + 0.25f * t) * rangeAlpha * alpha);
                Fill.rect(wx, wy, 8f, 8f);
            }
        }
    }
}
