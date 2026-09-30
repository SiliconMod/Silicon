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
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.SatelliteIntel;
import silicon.util.SatelliteManager;
import silicon.world.blocks.signal.SignalChannel;

/**
 * 反卫星拦截塔（2×2 炮塔）：打击 80 格内的敌方在轨卫星，目标来自**本队卫星定位器**的情报
 * （{@link SatelliteIntel}，全队共享，自动连接、无需配对）。
 * <p>
 * <b>它自己看不见卫星</b>：卫星单位 `targetable = false`，引擎索敌看不到它；`hittable = false`，
 * 常规伤害路径也碰不到它。所以目标只能来自外部情报——本队没有一座工作中的定位器时，
 * 这座炮塔只会转着炮管空等（面板上会直接说明原因）。
 * <p>
 * <b>四道门</b>，缺一不发：
 * <ol>
 *   <li>情报：本队定位器在工作（3 秒内刷新过）；</li>
 *   <li>射程：目标在 80 格内；</li>
 *   <li>锁定：持续瞄准 1~6 秒，用时由**所在位置的信号可用度**决定（可用度取该点 5 信道的最高值，
 *       与 H 覆盖/频谱面板同一实现）——信号差的地方炮口压不下来；</li>
 *   <li>电力：电池里 ≥ {@link #powerPerShot}。</li>
 * </ol>
 * <b>不开子弹</b>：卫星 `hittable/targetable = false`，子弹会直接穿透，所以借用 {@link Turret}
 * 的外壳与节奏（炮管转向、装填条、射界、预热、逻辑控制），命中结算走 `unit.damage()`。
 * 为此覆写 {@code findTarget}（目标来自情报）、{@code validateTarget}（基类默认判卫星"失效"）、
 * {@code shoot}（不发子弹，扣电 + 结算伤害）。
 */
public class AsatInterceptor extends Turret {
    /** 每发伤害。200 × 2 发 = 400，正好两发击落一颗 */
    public float damagePerShot = 200f;
    /** 每发消耗的电力（从电网电池扣）：击落一颗 = 2 发 = 8 万电力 */
    public float powerPerShot = 40000f;
    /** 可用度满值时的锁定时间（tick） */
    public float lockTimeMin = 60f;
    /** 可用度趋近 0 时的锁定时间（tick） */
    public float lockTimeMax = 360f;
    /** 可用度归一化参考值：达到该可用度即按最快锁定 */
    public float qualityRef = 40f;

    public AsatInterceptor(String name) {
        super(name);
        // 引擎索敌全部关闭：卫星 targetable=false，Units.bestTarget/bestEnemy 永远看不到它们
        targetAir = false;
        targetGround = false;
        targetBlocks = false;
        range = 80f;
        reload = 180f;      // 3 秒/发 → 两发 6 秒
        shootCone = 8f;
        rotateSpeed = 3f;
        cooldownTime = 90f;
        minWarmup = 0.75f;
        // 面板保留（只读状态），但不再需要玩家配任何东西：情报按队伍自动共享
        configurable = true;
        consumePower(600f / 60f);
    }

    public class AsatInterceptorBuild extends TurretBuild {
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

        /**
         * 本塔位置的信号可用度（0~1）：取该点 5 信道的**最高**可用度，不再绑定任何编码——
         * 信号编码是信号源的事，这里只问"这地方信号好不好"。
         */
        public float signalQuality() {
            qualityTimer = 0;
            if (!hasPower()) return 0f;
            SignalChannel.usableAll(team, x, y, effBuf, srcBuf, null, codeBuf, null);
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

        /** 本队当前可用的情报目标数（面板显示用） */
        public int intelCount() {
            return SatelliteIntel.get(team, Time.time).size;
        }

        /**
         * 覆写索敌：目标来自本队定位器的情报（自动连接，无需配对），再取射程内最近的一颗。
         * 换目标会清零锁定进度。
         */
        @Override
        protected void findTarget() {
            Seq<Unit> intel = SatelliteIntel.get(team, Time.time);
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
            if (u.team == team && !SatelliteManager.testSatelliteAvailable()) return false;
            return u.within(x, y, range());
        }

        /**
         * 覆写装填推进：两道门——① 必须在锁定（时长由信号可用度决定）；② 电量够一发。
         * 任一不满足就整塔待机：不攒装填、不空放。
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
                power.graph.useBatteries(powerPerShot);
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

        /** 面板（保留，只读）：情报来源状态 + 锁定用时 + 电量是否够一发 */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> {
                int n = intelCount();
                return n > 0
                        ? Core.bundle.format("block.silicon-asat-interceptor.intel", n)
                        : Core.bundle.get("block.silicon-asat-interceptor.intel.none");
            }).color(Color.lightGray).pad(4f).row();
            table.label(() -> Core.bundle.format("block.silicon-asat-interceptor.lock",
                            (int) (lockTime() / 60f * 10f) / 10f))
                    .color(Color.lightGray).pad(2f).row();
            table.label(() -> canAffordShot()
                            ? Core.bundle.get("block.silicon-asat-interceptor.power.ok")
                            : Core.bundle.get("block.silicon-asat-interceptor.power.low"))
                    .color(Color.lightGray).pad(2f).row();
        }
    }
}
