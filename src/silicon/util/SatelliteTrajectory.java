package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import silicon.content.SatelliteUnits;
import silicon.world.blocks.satellite.SatelliteConsole;

/**
 * 卫星轨迹预览：鼠标指向某颗在轨卫星时，画出它 **±50 秒**的星下点轨迹。
 * <p>
 * 为什么是精确的：位置是**相位的纯函数**——`scanU(r)` 直接返回存档字段 `phase`，
 * 而 {@link OrbitSatelliteController} 每帧按 `phase += delta / 周期` 自累加。
 * 所以"t 秒后的位置"就是推进同一个相位再取 `scanXAt/scanYAt`，不需要模拟推演、也不依赖全局时钟。
 * <p>
 * 两段用不同颜色区分：**过去**（冷色、半透明）与**未来**（暖色、高亮）。轨迹的透明度还会乘上该处的
 * `presence`（存在度），于是回绕边界附近自然淡出——这也解释了"卫星为什么会在那里消失/出现"。
 * <p>
 * <b>边界处理</b>：采样点一律裁剪到地图矩形内，跨越回绕轴的相邻点直接断开（否则会连出一条横穿全图的
 * 长直线）；同时按轴分别判断回绕，避免正弦轴被误当成回绕轴而画出斜线。
 * <p>
 * 定点轨道（GEO）没有轨迹可画，只在悬停时标记当前位置。
 */
public class SatelliteTrajectory {
    /** 预览的时间半窗（秒）：向前向后各这么多 */
    public static final float PREVIEW_SECONDS = 50f;
    /** 采样步长（秒）：越小越平滑，代价是每段更多次位置计算 */
    public static final float SAMPLE_STEP = 1f;
    /** 悬停判定容差（世界像素，叠加在卫星视觉尺寸之上） */
    public static final float HOVER_PAD = 6f;

    /** 过去段（冷色，与定位器的青蓝同族） */
    public static final Color PAST_COLOR = Color.valueOf("6fd8ff");
    /** 未来段（暖色，与拦截塔的锁定色同族） */
    public static final Color FUTURE_COLOR = Color.valueOf("ffb35c");
    /** 覆盖圆（与信号覆盖同一色系：它表示"这颗卫星此刻能罩到哪"） */
    public static final Color RANGE_COLOR = Color.valueOf("9dc3ff");

    /** 当前悬停的卫星（每帧刷新；null = 鼠标没指向卫星） */
    private static Unit hoveredUnit = null;

    /** 当前悬停的卫星，供其他系统查询（例如想"聚焦到这颗"的逻辑） */
    public static Unit hovered() {
        return hoveredUnit;
    }

    public static void init() {
        Events.run(EventType.Trigger.drawOver, SatelliteTrajectory::update);
    }

    static void update() {
        hoveredUnit = pick();
        if (hoveredUnit == null) return;
        SatelliteManager.SatelliteRecord r = SatelliteManager.recordOf(hoveredUnit.id);
        if (r == null) return;
        drawTrajectory(r);
    }

    /** 鼠标下最近的卫星（判定窗：视觉尺寸的一半 + 少量容差） */
    static Unit pick() {
        if (Vars.state == null || Vars.state.isMenu() || Vars.world == null) return null;
        Vec2 m = Core.input.mouseWorld();
        Unit best = null;
        float bestDst = Float.MAX_VALUE;
        for (Unit u : Groups.unit) {
            if (!(u.controller() instanceof OrbitSatelliteController)) continue;
            float pad = u.type.hitSize / 2f + HOVER_PAD;
            float dst = Mathf.dst(m.x, m.y, u.x, u.y);
            if (dst > pad || dst >= bestDst) continue;
            bestDst = dst;
            best = u;
        }
        return best;
    }

    /** 攻击范围色（LOIC 的武器射程；与信号覆盖的冷色区分开） */
    public static final Color ATTACK_COLOR = Color.valueOf("ff5a4a");

    static void drawTrajectory(SatelliteManager.SatelliteRecord r) {
        int orbit = r.orbit;
        if (Vars.world == null || Vars.world.unitWidth() <= 0 || Vars.world.unitHeight() <= 0) return;

        float x0 = SatelliteManager.scanX(r), y0 = SatelliteManager.scanY(r);

        float prevZ = Draw.z();
        Draw.z(Layer.overlayUI + 1f);

        boolean loic = hoveredUnit != null && hoveredUnit.type == SatelliteUnits.ionLeo;

        if (orbit != SatelliteConsole.ORBIT_GEO) {
            // 每秒推进的相位：period 的单位是 tick，60 tick = 1 秒
            float duPerSecond = 60f / SatelliteManager.orbitPeriod(orbit);
            drawSegment(orbit, r, -PREVIEW_SECONDS, 0f, duPerSecond, PAST_COLOR, 0.5f);
            drawSegment(orbit, r, 0f, PREVIEW_SECONDS, duPerSecond, FUTURE_COLOR, 0.95f);

            // 覆盖圆：这颗卫星此刻能罩到哪。信号强度本身由 H 覆盖负责（按住/切换 H 键），
            // 这里只画几何范围，让"轨迹经过哪里"与"覆盖到哪里"能在同一屏上看清。
            // GEO 的覆盖是全图（对角线），画出来会糊满屏幕，因此跳过；
            // LOIC 是武器卫星、不提供信号覆盖（见 SatelliteManager.satelliteRawAt），同样跳过。
            if (!loic) {
                Lines.stroke(1.4f, RANGE_COLOR.a(0.5f));
                Lines.circle(x0, y0, SatelliteManager.coverageRadius(orbit));
            }
        }

        // 攻击范围：只对挂武器的机型（LOIC）画。这是**武器索敌射程**，与信号覆盖是两回事。
        if (loic) {
            float attackRange = SatelliteUnits.ION_RANGE_TILES * 8f;
            Lines.stroke(2f, ATTACK_COLOR.a(0.85f));
            Lines.circle(x0, y0, attackRange);
            // 内侧一圈淡描，让边界在亮色地形上也看得清
            Lines.stroke(1f, ATTACK_COLOR.a(0.35f));
            Lines.circle(x0, y0, attackRange - 3f);
        }

        // 当前位置标记（定点轨道也画，它至少告诉玩家"这颗在这儿"）
        Lines.stroke(2.2f, Color.white);
        Lines.circle(x0, y0, 7f);
        Lines.stroke(1f);

        Draw.z(prevZ);
    }

    /**
     * 画一段轨迹。[fromSec, toSec] 之间按 {@link #SAMPLE_STEP} 采样，
     * 相邻两点跨越**回绕轴**时断开，越出地图矩形的点直接丢弃（边界裁剪）。
     */
    static void drawSegment(int orbit, SatelliteManager.SatelliteRecord r,
                            float fromSec, float toSec, float duPerSecond, Color color, float alpha) {
        float u0 = SatelliteManager.scanU(r);
        float w = Vars.world.unitWidth(), h = Vars.world.unitHeight();
        // 回绕轴：LEO/MEO 走 X，SSO 走 Y；另一轴是正弦摆动，不存在回绕
        boolean wrapX = orbit != SatelliteConsole.ORBIT_SSO;
        float wrapLimit = (wrapX ? w : h) * 0.5f;

        float prevX = Float.NaN, prevY = Float.NaN;
        for (float t = fromSec; t <= toSec + 0.0001f; t += SAMPLE_STEP) {
            float u = u0 + t * duPerSecond;
            float x = SatelliteManager.scanXAt(orbit, u);
            float y = SatelliteManager.scanYAt(orbit, u);
            // 回绕附近的存在度：轨迹在那里自然淡出，与卫星本体的淡入淡出一致
            float fade = SatelliteManager.presence(orbit, u);
            // 边界裁剪：越出地图矩形的采样点不画（回绕轴允许贴边，但不出界）
            boolean inside = x >= 0f && x <= w && y >= 0f && y <= h;
            boolean contiguous = !Float.isNaN(prevX)
                    && (!wrapX || Math.abs(x - prevX) < wrapLimit);
            if (inside && contiguous && fade > 0.02f) {
                Lines.stroke(2f, color.a(alpha * fade));
                Lines.line(prevX, prevY, x, y);
            }
            // 出界后重置链头，避免用一条长线跨过缺口
            if (inside) {
                prevX = x;
                prevY = y;
            } else {
                prevX = Float.NaN;
                prevY = Float.NaN;
            }
        }
    }
}
