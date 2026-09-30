package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteIntel;
import silicon.util.SatelliteManager;
import silicon.world.meta.Signal;

/**
 * 卫星定位器（2×2）：在地面**探测敌方在轨卫星**，并把位置情报挂到自己的信号编码上，
 * 供反卫星拦截塔索取（见 {@link SatelliteIntel}）。
 * <p>
 * 为什么需要它：拦截塔打得远（48 格），但"看不见"天上的东西——卫星本身没有可索敌的属性
 * （`targetable = false`），塔要么靠一座定位器指路，要么根本没有目标。这是拦截一侧平衡里的"信息成本"：
 * 塔本身不便宜，定位器还要吃电、要编码配对、而且必须把探测范围铺到卫星的轨道带上。
 * <p>
 * 与塔的耦合方式沿用信号系统的习惯：放置时自动分配一个唯一编码（同信号源），塔在配置面板里选同一个编码即可配对。
 * 定位器断电、被拆、或探测范围内没有敌星时，情报在 {@link SatelliteIntel#STALE_TICKS} tick 内自动过期。
 */
public class SatelliteLocator extends Block {
    /** 探测半径（格）：必须覆盖到卫星的轨道带才有意义 */
    public float detectRadiusTiles = 80f;
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
        // 编码：与信号源同一套校验（4 位大写字母/数字）
        config(String.class, (SatelliteLocatorBuild b, String value) -> {
            if (!Signal.isValidCode(value)) return;
            if (b.signal == null || !value.equals(b.signal.name)) {
                SatelliteIntel.clearFrom(b.team, b.signal == null ? null : b.signal.name);
            }
            b.signal = new Signal(value);
        });
        configClear((SatelliteLocatorBuild b) -> {
            SatelliteIntel.clearFrom(b.team, b.signal == null ? null : b.signal.name);
            b.signal = null;
        });
        // 探测要耗电：断电即失去情报
        consumePower(400f / 60f);
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.range, detectRadiusTiles, StatUnit.blocks);
    }

    public class SatelliteLocatorBuild extends Building {
        /** 本定位器挂载的信号编码（拦截塔按同一编码来取情报） */
        public Signal signal;
        private int refreshTimer = 0;
        /** 本端上次探测到的敌星（绘制用：范围环 + 指向线） */
        public final Seq<Unit> detected = new Seq<>();
        /** 是否有情报输出（绘制/状态用） */
        public boolean active = false;

        @Override
        public void placed() {
            super.placed();
            if (!added) add();
            // 服务端生成唯一编码；客机等 MP 世界快照把编码带过来（与信号源同一策略）
            if (Vars.net.client()) return;
            if (signal == null) signal = new Signal(SignalSource.generateUniqueName());
        }

        @Override
        public void updateTile() {
            active = false;
            if (!enabled || !hasPower() || signal == null) {
                detected.clear();
                if (signal != null) SatelliteIntel.clearFrom(team, signal.name); // 断电/被关闭即撤稿
                return;
            }
            if (++refreshTimer < refreshInterval) return;
            refreshTimer = 0;
            float radius = detectRadiusTiles * 8f;
            detected.clear();
            for (Unit u : Groups.unit) {
                if (!(u.controller() instanceof OrbitSatelliteController)) continue;
                if (u.team == Team.derelict) continue;
                // 沙盒自测放宽：沙盒里没有第二个队，若只探测敌方则整条拦截链路无从验证。
                // 判据与"测试卫星仅沙盒可用"完全一致（SatelliteManager.testSatelliteAvailable），
                // 正式模式不含这段放宽——那里只探测敌方卫星。
                if (u.team == team && !SatelliteManager.testSatelliteAvailable()) continue;
                if (Mathf.dst(x, y, u.x, u.y) > radius) continue;
                detected.add(u);
            }
            // 只有权威端发布情报：客机算出来的位置没有意义，塔在客机侧也不结算伤害
            if (SatelliteManager.isAuthority()) {
                SatelliteIntel.publish(team, signal.name, detected, Time.time);
            }
            active = detected.size > 0;
        }

        /** 供电是否充足（power.status：0=无电，1=满电）——与信号源/中继器同一判据 */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            if (signal != null) SatelliteIntel.clearFrom(team, signal.name);
        }

        @Override
        public void onRemoved() {
            if (signal != null) SatelliteIntel.clearFrom(team, signal.name);
            super.onRemoved();
        }

        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> signal == null
                            ? Core.bundle.get("block.silicon-satellite-locator.code.none")
                            : Core.bundle.format("block.silicon-satellite-locator.code", signal.name))
                    .color(Color.lightGray).pad(4f).row();
            table.button(Core.bundle.get("block.silicon-satellite-locator.reset"), Styles.defaultt,
                    () -> configure(SignalSource.generateUniqueName())).size(200f, 40f).pad(4f).row();
        }

        @Override
        public void draw() {
            super.draw();
            if (!active) return;
            // 探测范围与已定位目标（画在方块层之上，与拦截塔的光束同一手法）
            float prevZ = Draw.z();
            Draw.z(Layer.block + 1f);
            Lines.stroke(1f, Color.valueOf("6fd8ff").a(0.3f));
            Lines.circle(x, y, detectRadiusTiles * 8f);
            Lines.stroke(1.2f, Color.valueOf("6fd8ff").a(0.65f));
            for (Unit u : detected) {
                Lines.line(x, y, u.x, u.y);
            }
            Lines.stroke(1f);
            Draw.z(prevZ);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.str(signal == null ? "" : signal.name);
        }

        /** 存档版本：1 = str(signal)（与信号源同构） */
        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            String name = read.str();
            signal = Signal.isValidCode(name) ? new Signal(name) : null;
        }
    }
}
