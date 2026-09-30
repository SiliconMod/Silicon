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

        @Override
        public void updateTile() {
            boolean on = enabled && hasPower() && reporting;
            if (!on) {
                if (detected.size > 0) detected.clear();
                if (SatelliteManager.isAuthority()) SatelliteIntel.clearFrom(team); // 断电/被关闭/停止上报即撤稿
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
            // 只有权威端发布情报：客机算出来的位置没有意义，塔在客机侧也不结算伤害
            if (SatelliteManager.isAuthority()) {
                SatelliteIntel.publish(team, detected, Time.time);
            }
        }

        /** 供电是否充足（power.status：0=无电，1=满电）——与信号源/中继器同一判据 */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            if (SatelliteManager.isAuthority()) SatelliteIntel.clearFrom(team);
        }

        @Override
        public void onRemoved() {
            if (SatelliteManager.isAuthority()) SatelliteIntel.clearFrom(team);
            super.onRemoved();
        }

        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> Core.bundle.format("block.silicon-satellite-locator.status",
                            detected.size, reporting ? Core.bundle.get("block.silicon-satellite-locator.on")
                                    : Core.bundle.get("block.silicon-satellite-locator.off")))
                    .color(Color.lightGray).pad(4f).colspan(2).row();
            table.label(() -> hasPower() ? Core.bundle.get("block.silicon-satellite-locator.link.ok")
                            : Core.bundle.get("block.silicon-satellite-locator.link.nopower"))
                    .color(Color.lightGray).pad(2f).colspan(2).row();
            table.button(Core.bundle.get(reporting
                            ? "block.silicon-satellite-locator.report.off"
                            : "block.silicon-satellite-locator.report.on"),
                    mindustry.ui.Styles.defaultt, () -> configure(!reporting)).size(220f, 40f).pad(4f).colspan(2).row();
        }

        @Override
        public void draw() {
            super.draw();
            if (!hasPower() || !reporting || detected.size == 0) return;
            // 指向已定位目标的连线（画在方块层之上，与拦截塔的光束同一手法）
            float prevZ = Draw.z();
            Draw.z(Layer.block + 1f);
            Lines.stroke(1.2f, Color.valueOf("6fd8ff").a(0.6f));
            for (Unit u : detected) {
                Lines.line(x, y, u.x, u.y);
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
