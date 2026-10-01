package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Posc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteIntel;
import silicon.util.SatelliteManager;
import silicon.world.blocks.satellite.SatelliteConsole;
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

    /** 锁定/瞄准的显示色（暖橙，与定位器的青蓝形成对照） */
    public static final Color LOCK_COLOR = Color.valueOf("ff6a3c");
    /** 取得新目标时锁定标记的持续时长（tick；60 tick = 1 秒） */
    public float acquireTicks = 36f;
    /**
     * 各轨道相对 LEO 的**锁定难度**："轨道越高越难被锁定"。
     * <p>
     * 下标即 {@link SatelliteConsole#ORBIT_LEO}..{@link SatelliteConsole#ORBIT_SSO}：
     * LEO 1.0 / MEO 1.5 / GEO 2.5 / SSO 1.0（SSO 与 LEO 同属低轨，只是倾角不同，因此同档）。
     * <p>
     * 这个系数与覆盖半径恰好**成反比**：LEO 覆盖最小但最好打，GEO 一颗覆盖全图却最难被击落——
     * 于是"高轨道更强的覆盖"要用"更难保护"来换，两种选择各有代价。
     */
    public static final float[] ORBIT_LOCK_FACTOR = {1f, 1.5f, 2.5f, 1f};

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
        /** 最近一次"取得新目标"的时间（Time.time，单位 tick；绘制锁定标记用） */
        public float acquireAt = Float.NEGATIVE_INFINITY;
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
         * 本塔位置的信号可用度（0~1）：取该点 5 信道的**最高**可用度，不绑定任何编码——
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

        /** 当前目标的轨道锁定系数（无目标/非卫星时按 1 处理） */
        public float orbitLockFactor() {
            if (target instanceof Unit u && u.controller() instanceof OrbitSatelliteController c
                    && c.orbit >= 0 && c.orbit < ORBIT_LOCK_FACTOR.length) {
                return ORBIT_LOCK_FACTOR[c.orbit];
            }
            return 1f;
        }

        /** 当前所需锁定时间：可用度越高越快，再乘目标的轨道难度（越高轨道越难） */
        public float lockTime() {
            return (lockTimeMin + (lockTimeMax - lockTimeMin) * (1f - quality)) * orbitLockFactor();
        }

        /** 目标轨道短名：不需要了（面板不再显示诊断信息），保留此注释以免有人重新引入时忘了
         *  {@code SatelliteConsole.orbitKeyShort} 在它的内部类里、外部访问不到 */

        /** 情报里落在射程内的目标数（面板显示用） */
        public int inRange = 0;

        /** 锁定进度（0~1），绘制与面板共用 */
        public float lockProgress() {
            // 注意用 java.lang.Math.max：arc 的 Mathf.max 只有 int 重载
            return target == null ? 0f : Mathf.clamp(lockTimer / Math.max(lockTime(), 1f));
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
            int count = 0;
            for (Unit u : intel) {
                if (!u.isValid() || u.team == Team.derelict) continue;
                // 沙盒自测放宽（与定位器同一判据）：沙盒里没有第二个队，只打敌方则无法验证
                if (u.team == team && !SatelliteManager.testSatelliteAvailable()) continue;
                float dst = Mathf.dst(x, y, u.x, u.y);
                if (dst > range) continue;
                count++;
                if (dst < bestDst) {
                    bestDst = dst;
                    best = u;
                }
            }
            inRange = count;
            if (best != target) {
                lockTimer = 0f; // 换目标：重新锁定
                if (best != null) acquireAt = Time.time; // 新取得目标：播一次锁定标记
            }
            target = best;
            if (best != null) targetPosition(best);
        }

        /**
         * 覆写目标合法性：基类默认走 {@code Units.invalidateTarget(target, ...)}，对
         * `targetable = false` 的卫星一律判"失效"——只覆写 findTarget 的话，刚拿到的目标会被立刻清掉。
         * <p>
         * 这里额外要求目标**仍在本队情报里**：引擎每 tick 调本方法、但 `findTarget()` 只在
         * `timer(timerTarget, …)` 到点时跑（无目标用 targetInterval、有目标用 newTargetInterval，最长 40 tick）。
         * 若不在这里查情报，定位器撤稿（断电/被拆/停止上报）之后的那段时间里，塔仍会对着一个已经
         * "看不见"的目标继续锁定、甚至开火——与"信息依赖"的语义不符。
         * `SatelliteIntel.get` 是按 Team 的 O(1) 查表，每 tick 调用的开销可忽略。
         */
        @Override
        protected boolean validateTarget() {
            Posc t = target;
            if (!(t instanceof Unit u) || !u.isValid()) return false;
            if (u.team == Team.derelict) return false;
            if (u.team == team && !SatelliteManager.testSatelliteAvailable()) return false;
            if (!u.within(x, y, range())) return false;
            return SatelliteIntel.get(team, Time.time).contains(u);
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
            float wx = u.x, wy = u.y;
            u.damage(damagePerShot);
            if (wasAlive && !u.isValid()) {
                // 击落反馈：卫星本体很小、又在高空，两颗星体积的爆炸比默认命中特效更像"打下来了"
                Fx.explosion.at(wx, wy);
                Fx.sparkExplosion.at(wx, wy);
                Call.sendMessage(Core.bundle.format("block.silicon-asat-interceptor.kill",
                        u.type.localizedName));
            } else {
                Fx.hitBulletBig.at(wx, wy);
            }
            Fx.sparkShoot.at(this.x + Mathf.cosDeg(rotation) * 16f,
                    this.y + Mathf.sinDeg(rotation) * 16f, rotation);
        }

        /**
         * 锁定与瞄准的可视化：炮管本身会转向目标，这里补三样玩家真正需要看到的
         * —— 瞄准线、目标上的锁定进度环、塔自身的锁定进度环。
         * 没有这三样，玩家只能看到炮塔转着却不发射，无法判断卡在哪一道门上。
         */
        @Override
        public void draw() {
            super.draw();
            if (!enabled || !hasPower()) return;
            float prevZ = Draw.z();
            Draw.z(Layer.block + 1f);

            // 塔自身的锁定进度环（以方块中心为圆心，方块层之上）
            float prog = lockProgress();
            if (prog > 0f) {
                Lines.stroke(2.2f, LOCK_COLOR.a(0.9f));
                Lines.arc(x, y, size * 4f + 3f, prog, -90f);
                Lines.stroke(1f, LOCK_COLOR.a(0.25f));
                Lines.circle(x, y, size * 4f + 3f);
            }

            if (target instanceof Unit u && u.isValid()) {
                // 瞄准线：虚线更像"瞄准"而不是"已经打出去"
                Lines.stroke(1.2f, LOCK_COLOR.a(0.45f));
                Lines.dashLine(x, y, u.x, u.y, 10);
                // 目标上的锁定环：进度满了就变亮，提示"下一发就是它"
                Lines.stroke(2f, prog >= 1f ? LOCK_COLOR : LOCK_COLOR.a(0.7f));
                Lines.arc(u.x, u.y, 10f, prog, -90f);
                Lines.stroke(1f, LOCK_COLOR.a(0.35f));
                Lines.circle(u.x, u.y, 10f);
            }

            // 取得新目标时的锁定标记：目标处四角括号向内收缩淡出 + 塔身一圈扩散
            // —— 情报刚到手的那一刻应该看得见，否则"塔怎么突然开始转了"没有解释
            float ea = Time.time - acquireAt;
            if (ea >= 0f && ea < acquireTicks && target instanceof Unit tu && tu.isValid()) {
                float fa = ea / acquireTicks;
                float r = 22f - 10f * fa;
                Lines.stroke(2.2f * (1f - fa), LOCK_COLOR);
                Lines.line(tu.x - r, tu.y - r, tu.x - r * 0.55f, tu.y - r);
                Lines.line(tu.x - r, tu.y + r, tu.x - r * 0.55f, tu.y + r);
                Lines.line(tu.x + r, tu.y - r, tu.x + r * 0.55f, tu.y - r);
                Lines.line(tu.x + r, tu.y + r, tu.x + r * 0.55f, tu.y + r);
                Lines.stroke(2f * (1f - fa), LOCK_COLOR.a(0.6f * (1f - fa)));
                Lines.circle(x, y, fa * size * 14f);
            }

            Lines.stroke(1f);
            Draw.z(prevZ);
        }

        /**
         * 配置面板：只给**状态数值**，不写解释（与官方面板风格一致）。
         * 这三行覆盖了塔唯二会卡住的地方：有没有目标、电量够不够一发。
         * 另外两处静默门控（缺电时引擎把 efficiency 置 0，连 shootWarmup 都不增长）从数值上也能看出来。
         */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.label(() -> {
                int total = SatelliteIntel.get(team, Time.time).size;
                return Core.bundle.format("block.silicon-asat-interceptor.ui.intel", total, inRange);
            }).color(Color.lightGray).pad(3f).row();
            table.label(() -> {
                int stored = power == null || power.graph == null ? 0 : (int) power.graph.getBatteryStored();
                int need = (int) powerPerShot;
                // 颜色写在 bundle 里（Label 没有 Prov<Color> 重载，不能在 .color() 里按状态切换）
                return canAffordShot()
                        ? Core.bundle.format("block.silicon-asat-interceptor.ui.power", stored, need)
                        : Core.bundle.format("block.silicon-asat-interceptor.ui.powerLow", stored, need);
            }).color(Color.lightGray).pad(3f).row();
            table.label(() -> target instanceof Unit u && u.isValid()
                            ? Core.bundle.format("block.silicon-asat-interceptor.ui.locking",
                                    (int) (lockProgress() * 100f))
                            : Core.bundle.get("block.silicon-asat-interceptor.ui.none"))
                    .color(Color.lightGray).pad(3f).row();
        }
    }
}
