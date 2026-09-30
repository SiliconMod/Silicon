package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Posc;
import mindustry.gen.Unit;
import mindustry.ui.Styles;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteIntel;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SatelliteLocator;
import silicon.world.blocks.signal.SignalChannel;
import silicon.world.meta.Signal;

/**
 * 反卫星拦截塔（2×2 炮塔）：按*定位情报*打击敌方在轨卫星。
 * <p>
 * <b>信息依赖</b>：它的目标不来自"天上所有的敌星"，而只来自**自己绑定的信号编码**对应的
 * {@link SatelliteIntel} 情报——那份情报由 {@link SatelliteLocator} 探测并发布。因此：
 * 塔可以在没有目标的情况下长时间空转（炮塔转着、装填条满着，却打不出去），
 * 直到一座同编码的定位器把目标指出来。射程够远（48 格）不是问题，因为"打不打得到"变成了"看不看得见"。
 * <p>
 * <b>可用度 → 锁定时间</b>：塔以自己所在位置、自己绑定编码的**信号可用度**决定锁定速度——
 * 可用度由 {@link SignalChannel#usableAll}（H 覆盖与频谱面板的同一实现）给出，满值约 1 秒锁定，
 * 很低则要 6 秒。信号差的地方，炮口压不下来。
 * <p>
 * <b>不开子弹</b>：卫星 `hittable/targetable = false`（见 {@link silicon.content.SatelliteUnits}），
 * 引擎的伤害路径对它们失明，子弹会穿透。所以本方块借用 {@link Turret} 的外壳与节奏，
 * 命中结算走 `unit.damage()`——第一阶段预留的那个入口。为此必须覆写三处：
 * {@code findTarget}（目标来自情报，且引擎索敌本就看不到卫星）、
 * {@code validateTarget}（基类默认走 {@code Units.invalidateTarget}，对卫星一律判"失效"，不覆写会把刚拿到的目标立刻清掉）、
 * {@code shoot}（不发子弹，扣电 + 结算伤害）。
 */
public class AsatInterceptor extends Turret {
    /** 每发伤害。200 × 2 发 = 400，正好两发击落一颗卫星 */
    public float damagePerShot = 200f;
    /** 每发消耗的电力（从电网电池扣）。击落一颗 = 2 发 = 8 万电力，与发射一颗卫星的电费同量级 */
    public float powerPerShot = 40000f;
    /** 可用度满值时的锁定时间（tick） */
    public float lockTimeMin = 60f;
    /** 可用度极低时的锁定时间（tick） */
    public float lockTimeMax = 360f;
    /** 可用度归一化参考值：达到该可用度即按最快锁定 */
    public float qualityRef = 40f;

    public AsatInterceptor(String name) {
        super(name);
        // 引擎索敌全部关闭：卫星 targetable=false，Units.bestTarget/bestEnemy 永远看不到它们
        targetAir = false;
        targetGround = false;
        targetBlocks = false;
        // 射程拉大到 48 格：既然"看不看得见"由定位器决定，射程就不再是平衡杠杆，而是给布局留余地
        range = 48f;
        reload = 180f;      // 3 秒/发 → 两发 6 秒
        shootCone = 8f;
        rotateSpeed = 3f;
        cooldownTime = 90f;
        minWarmup = 0.75f;
        configurable = true;
        // 绑定一个信号编码：目标情报的来源（同编码的定位器在探测）
        config(String.class, (AsatInterceptorBuild b, String value) -> {
            if (!Signal.isValidCode(value)) return;
            b.signal = value;
            b.lockTimer = 0f;
        });
        configClear((AsatInterceptorBuild b) -> {
            b.signal = null;
            b.lockTimer = 0f;
        });
        consumePower(600f / 60f);
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(mindustry.world.meta.Stat.range, range, mindustry.world.meta.StatUnit.blocks);
        stats.add(mindustry.world.meta.Stat.damage, damagePerShot, mindustry.world.meta.StatUnit.perSecond);
    }

    public class AsatInterceptorBuild extends TurretBuild {
        /** 绑定的信号编码（null = 未绑定，此时没有目标来源） */
        public String signal;
        /** 已经锁定当前目标的时间（tick）；换目标或丢失即归零 */
        public float lockTimer = 0f;
        /** 缓存的信号可用度（0~1，节流更新） */
        private float quality = 0f;
        private int qualityTimer = 0;

        private final float[] effBuf = new float[6];
        private final Building[] srcBuf = new Building[6];
        private final String[] codeBuf = new String[6];

        /** 供电是否充足（power.status：0=无电，1=满电）——与信号源/中继器同一判据 */
        private boolean hasPower() {
            return power != null && power.status > 0.001f;
        }

        /** 本塔位置的信号可用度（0~1）。用 usableAll 的 scopeCode 形式取*本编码*的可用度，与 H 覆盖同一实现 */
        public float signalQuality() {
            if (signal == null || !hasPower()) return 0f;
            qualityTimer = 0;
            SignalChannel.usableAll(team, x, y, effBuf, srcBuf, null, codeBuf, signal);
            float best = 0f;
            for (int ch = 1; ch <= 5; ch++) {
                best = Math.max(best, effBuf[ch]);
            }
            return Mathf.clamp(best / qualityRef);
        }

        /** 当前所需的锁定时间：可用度越高越快 */
        public float lockTime() {
            return lockTimeMin + (lockTimeMax - lockTimeMin) * (1f - quality);
        }

        /**
         * 覆写索敌：目标只来自本编码的定位情报（{@link SatelliteIntel}），再取射程内最近的一颗。
         * 情报里已经是"敌方"了，这里仍按队复核一次。换目标会清零锁定进度。
         */
        @Override
        protected void findTarget() {
            if (signal == null) {
                target = null;
                return;
            }
            Seq<Unit> intel = SatelliteIntel.get(team, signal, Time.time);
            Unit best = null;
            float bestDst = Float.MAX_VALUE;
            float range = range();
            for (Unit u : intel) {
                if (!u.isValid() || u.team == Team.derelict) continue;
                // 沙盒自测放宽（与定位器同一判据）：沙盒里没有第二个队，只打敌方则无法验证
                if (u.team == team && !SatelliteManager.testSatelliteAvailable()) continue;
                float dst = Mathf.dst(x, y, u.x, u.y);
                if (dst > range || dst >= bestDst) continue;
                bestDst = dst;
                best = u;
            }
            if (best != target) lockTimer = 0f; // 换目标：重新锁定
            target = best;
            if (best != null) targetPosition(best);
        }

        /**
         * 覆写目标合法性：基类默认走 {@code Units.invalidateTarget(target, ...)}，对
         * `targetable = false` 的卫星一律判"失效"——只覆写 findTarget 的话，刚拿到的目标会被立刻清掉。
         */
        @Override
        protected boolean validateTarget() {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return false;
            if (u.team == Team.derelict) return false;
            // 沙盒自测放宽（与 findTarget 同一判据）
            if (u.team == team && !SatelliteManager.testSatelliteAvailable()) return false;
            return u.within(x, y, range());
        }

        /**
         * 覆写装填推进：这里是两道门——① 必须在**锁定**（时长由信号可用度决定）；② 电量必须够一发。
         * 任一不满足就整座塔待机：不攒装填、不空放。所以"电力 + 信号 + 定位器"三者缺一，
         * 这座炮塔就只是转着的摆设。
         */
        @Override
        protected void updateShooting() {
            if (++qualityTimer >= 15) {
                quality = signalQuality();
            }
            if (target == null) {
                lockTimer = 0f;
                return;
            }
            lockTimer += delta();
            if (lockTimer < lockTime()) return;
            if (!canAffordShot()) return;
            super.updateShooting();
        }

        /** 电量是否够打下一发 */
        public boolean canAffordShot() {
            return power != null && power.graph != null && power.graph.getBatteryStored() >= powerPerShot;
        }

        /**
         * 覆写开火：不创建子弹（卫星 `hittable = false`），直接扣电 + 结算 scripted 伤害。
         * 装填节奏、炮口对准、冷却都由基类照常驱动；伤害与扣电只在权威端执行。
         */
        @Override
        protected void shoot(BulletType type) {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return;
            if (!SatelliteManager.isAuthority()) {
                Fx.hitBulletBig.at(u.x, u.y); // 客机只补一次纯视觉反馈
                return;
            }
            if (power != null && power.graph != null) {
                power.graph.useBatteries(powerPerShot); // 每发电费从电池扣
            }
            boolean wasAlive = u.isValid();
            u.damage(damagePerShot);
            Fx.hitBulletBig.at(u.x, u.y);
            Fx.sparkShoot.at(this.x + Mathf.cosDeg(rotation) * 16f,
                    this.y + Mathf.sinDeg(rotation) * 16f, rotation);
            if (wasAlive && !u.isValid()) {
                Call.sendMessage(Core.bundle.format("block.silicon-asat-interceptor.kill",
                        u.type.localizedName));
            }
        }

        /** 配置面板：列出本队**正在工作的定位器**的编码供绑定（这就是"塔与定位器之间的通信"入口） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> signal == null
                            ? Core.bundle.get("block.silicon-asat-interceptor.code.none")
                            : Core.bundle.format("block.silicon-asat-interceptor.code", signal))
                    .color(Color.lightGray).pad(4f).row();
            table.label(() -> Core.bundle.format("block.silicon-asat-interceptor.lock",
                            (int) (lockTime() / 60f * 10f) / 10f))
                    .color(Color.lightGray).pad(2f).row();
            Seq<String> codes = new Seq<>();
            for (Building b : Groups.build) {
                if (b instanceof SatelliteLocator.SatelliteLocatorBuild lb && lb.team == team
                        && lb.signal != null && !codes.contains(lb.signal.name)) {
                    codes.add(lb.signal.name);
                }
            }
            if (codes.isEmpty()) {
                table.label(() -> Core.bundle.get("block.silicon-asat-interceptor.code.empty"))
                        .color(Color.lightGray).pad(6f).row();
                return;
            }
            Table grid = new Table();
            int i = 0;
            for (String code : codes) {
                grid.button(code, Styles.flatTogglet, () -> configure(code)).size(96f, 36f).pad(2f);
                if (++i % 4 == 0) grid.row();
            }
            table.add(grid).pad(4f).row();
        }
    }
}
