package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.world.Block;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteIntel;
import silicon.util.SatelliteManager;

/**
 * 卫星定位器（2×2）：**全图**探测敌方在轨卫星，把结果发布给本队所有反卫星拦截塔
 * （见 {@link SatelliteIntel}）。
 * <p>
 * <b>它不发射信号。</b>信号编码是信号源的东西——只有信号源能发射信号并参与信道/干扰/中继那一整套系统。
 * 定位器只是探测设备：探测结果按**队伍**共享，本队的拦截塔自动可见，不需要玩家做任何配对。
 * <p>
 * 为什么需要它：拦截塔打得远（80 格），但"看不见"天上的东西——卫星 `targetable = false`，
 * 引擎索敌完全看不到它。塔的目标只能来自这份外部情报，所以"打不打得到"取决于"看不看得见"。
 * 全图探测意味着**一座定位器就能为全队提供目标**，代价是它本身很贵、且待机也在大量吃电。
 */
public class SatelliteLocator extends Block {
    /** 情报刷新节流（tick）：卫星移动快，但不必每 tick 重扫整个单位表 */
    public int refreshInterval = 10;
    /** 发现新目标时扫描脉冲的持续时长（tick；60 tick = 1 秒） */
    public float pingTicks = 45f;
    /** 扫描脉冲与连线的配色（冷色，与拦截塔的暖橙锁定色区分开） */
    public static final Color PING_COLOR = Color.valueOf("6fd8ff");

    public SatelliteLocator(String name) {
        super(name);
        buildType = SatelliteLocatorBuild::new;
        size = 2;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        // 唯一的"配置"是开关上报：关掉它，本队的拦截塔就会在几秒内失去目标
        config(Boolean.class, (SatelliteLocatorBuild b, Boolean v) -> b.reporting = v);
        configClear((SatelliteLocatorBuild b) -> b.reporting = true);
        // 全图探测不便宜：待机耗电很高，养一座是一笔持续开销
        consumePower(2000f / 60f);
    }

    public class SatelliteLocatorBuild extends Building {
        /** 是否向外发布情报（面板里可关；关掉后本队拦截塔在 3 秒内失去目标） */
        public boolean reporting = true;
        private int refreshTimer = 0;
        /** 本端上次探测到的敌星（绘制与面板用） */
        public final Seq<Unit> detected = new Seq<>();
        /** 上次探测到的目标数（检测"新发现"用） */
        private int lastCount = 0;
        /** 最近一次发现新目标的时间（Time.time，单位 tick；绘制扫描脉冲用） */
        public float pingAt = Float.NEGATIVE_INFINITY;

        @Override
        public void updateTile() {
            boolean on = enabled && hasPower() && reporting;
            if (!on) {
                if (detected.size > 0) detected.clear();
                lastCount = 0;
                SatelliteIntel.clearFrom(team); // 断电/被关闭/停止上报即撤稿
                return;
            }
            if (++refreshTimer < refreshInterval) return;
            refreshTimer = 0;
            // 全图探测：不过滤距离，只按队伍过滤（敌方；沙盒模式下连同己方，便于单机自测整条链路）
            detected.clear();
            for (Unit u : Groups.unit) {
                if (!(u.controller() instanceof OrbitSatelliteController)) continue;
                if (u.team == Team.derelict) continue;
                if (u.team == team && !SatelliteManager.testSatelliteAvailable()) continue;
                detected.add(u);
            }
            // **两端都发布**：客机侧的情报只服务**本地视觉**（塔的转向、锁定环、取得目标的特效），
            // 伤害与扣电仍被 isAuthority 挡在权威端。若只在权威端发布，联机时己方炮塔在客机屏幕上
            // 会是一副"不转向、不锁定、没有特效"的样子。
            SatelliteIntel.publish(team, detected, Time.time);
            // 新目标出现时打一发扫描脉冲（数量增加即视为"发现"；持续不变不再重放，避免刷屏）
            if (detected.size > lastCount) pingAt = Time.time;
            lastCount = detected.size;
        }

        /** 供电是否充足（power.status：0=无电，1=满电）——与信号源/中继器同一判据 */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            SatelliteIntel.clearFrom(team);
        }

        @Override
        public void onRemoved() {
            SatelliteIntel.clearFrom(team);
            super.onRemoved();
        }

        /** 配置面板：一行状态数值 + 上报开关（不给解释文字，与官方面板风格一致） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> Core.bundle.format("block.silicon-satellite-locator.ui.status",
                            detected.size, Core.bundle.get(reporting
                                    ? "block.silicon-satellite-locator.on"
                                    : "block.silicon-satellite-locator.off")))
                    .color(Color.lightGray).pad(4f).row();
            table.button(Core.bundle.get(reporting
                            ? "block.silicon-satellite-locator.report.off"
                            : "block.silicon-satellite-locator.report.on"),
                    mindustry.ui.Styles.defaultt, () -> configure(!reporting)).size(220f, 40f).pad(4f).row();
        }

        @Override
        public void draw() {
            super.draw();
            if (!hasPower() || !reporting) return;
            float prevZ = Draw.z();
            Draw.z(Layer.block + 1f);

            // 发现新目标时的扫描脉冲：从方块扩散到全图尺度后淡出（"雷达扫到东西了"）。
            // 全图探测没有"探测半径"字段，脉冲半径直接按地图长边取，保证视觉上扫过整张图。
            float el = Time.time - pingAt;
            if (el >= 0f && el < pingTicks) {
                float f = el / pingTicks;               // 0 → 1
                float pingR = (mindustry.Vars.world == null || mindustry.Vars.world.unitWidth() <= 0)
                        ? 600f
                        : Math.max(mindustry.Vars.world.unitWidth(), mindustry.Vars.world.unitHeight());
                Lines.stroke(2.4f * (1f - f), PING_COLOR.a(0.85f * (1f - f)));
                Lines.circle(x, y, f * pingR);
            }

            if (detected.size > 0) {
                // 指向已定位目标的连线（细、常驻，表示"情报在持续刷新"）
                Lines.stroke(1.2f, PING_COLOR.a(0.6f));
                for (Unit u : detected) {
                    Lines.line(x, y, u.x, u.y);
                }
            }

            Lines.stroke(1f);
            Draw.z(prevZ);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(reporting);
        }

        /** 存档版本：1 = bool(reporting) */
        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            reporting = read.bool();
        }
    }
}
