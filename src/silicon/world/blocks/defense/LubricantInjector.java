package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.liquid.LiquidBlock;
import silicon.util.BuildingBoostSystem;
import silicon.util.SiliconLog;
import silicon.util.SiliconTmp;
import silicon.util.boosts.LubricantBoost;

import static silicon.content.liquid.Liquids.lubricant;

/**
 * 润滑油注入器：2x2 支援方块（{@link BuildingBoostSystem.Provider}），消耗润滑油，把润滑油
 * 带来的全部强化作为一个 {@link LubricantBoost} 交予强化系统驱动：
 *
 * <ul>
 *   <li>攻速：攻击中的炮塔攻速 +20%（与强化液同加法池，固定充能点入池）；仅炮塔攻击时消耗润滑油；</li>
 *   <li>转角：存油期间紧贴炮塔转角速率 ×2（自动索敌/玩家控制同等生效）。</li>
 * </ul>
 *
 * <p>本方块只负责四件事：前置目标过滤（targets()：只把炮塔交给 System，非可用对象连 System 都不会进入）、
 * 锁油量 + 认领检查（canTarget：存油 且 本机独占认领该目标才提供）、按「本机独占认领且攻击中」的
 * 炮塔数扣油、声明自己提供润滑油 Boost。资格/队伍/互斥/撤销生命周期全由 System 兜底。
 *
 * <p><b>认领制</b>（落实「不叠加 + 唯生效者耗油」）：同一炮塔即使两台注入器紧贴，也只有认领者提供效果
 * 并扣油，另一台主动让出（不叠加，不会成倍放大/双倍油耗）。认领用<strong>确定性规则</strong>——紧贴同一炮塔的
 * 同队有油注入器中 (tileX, tileY) 字典序最小者胜出；tile 坐标客户端/服务器一致，故两端认领者相同、
 * 扣同一台机器的油，<strong>液体量不会分歧（多人安全）</strong>。
 *
 * <p>门禁（与原版语义一致）：只对同队炮塔/同队供给生效，队伍比较一律用 {@code ==}
 * （{@link mindustry.game.Team} 枚举单例，跨端一致，天然排除敌队/derelict），多人安全。
 */
public class LubricantInjector extends Block {

    /** 每个正在攻击的受惠炮塔的润滑油消耗（单位/秒） */
    public float consumePerTurret = 5f;

    // ========== 三层贴图 + 闪光层（命名沿用原版液体储罐 LiquidBlock 的约定）==========
    /** 底座贴图（打底，覆盖整块 2x2） */
    public TextureRegion bottomRegion;
    /** 顶盖贴图（主体，绘制在闪光层之下） */
    public TextureRegion topRegion;
    /** 闪光层贴图：绘制在 {@link #topRegion} <b>之上</b>，用加色混合叠加 */
    public TextureRegion lightRegion;

    /** 液体边缘内缩间距（绘制流动液面时用，与原版液体储罐/两用存储器一致为 0） */
    public float liquidPadding = 0f;

    /**
     * 闪光颜色：取模组润滑油的配色（{@code Liquids.lubricant} 的 {@code 8a5a2b} 深琥珀）。
     *
     * <p>两个要点：
     * <ul>
     *   <li>用 {@code cpy()} 而非直接引用 {@code Liquids.lubricant.color}——
     *       这是可被外部改写的实例字段，直接引用会让「改本方块闪光色」连带改掉全局液体颜色。</li>
     *   <li>alpha 压低到 0.35 而非默认 1：{@code Color.valueOf} 产出不透明色，
     *       而加色混合下满 alpha 会过曝成白团、盖掉顶盖细节，故留出上限。</li>
     * </ul>
     */
    public Color lightColor = Color.valueOf("8a5a2b").a(0.35f);

    /** 闪光强度的淡入淡出速度（越大越快），对齐原版爆破钻头 {@code warmup} 的平滑风格。 */
    public float glowSpeed = 0.08f;

    /** 本方块提供的效果单元列表：全方块共享一份只读 Seq，避免每帧每目标新建。 */
    private final Seq<BuildingBoostSystem.Boost> boostList = Seq.with(LubricantBoost.instance);

    public LubricantInjector(String name) {
        super(name);
        update = true;
        solid = true;
        hasLiquids = true;
        outputsLiquid = false;
        liquidCapacity = 300f;

        // 关键：本方块自绘三层贴图，必须每帧动态绘制。
        // 若被烘焙进区块缓存（drawCached），动态闪光只会在缓存重建时刷新一次，工作时不会闪烁；
        // 而对齐原版液体方块默认值（drawCached=false + drawDynamic=true）可保证只每帧动态画一次，
        // 不会「进缓存 + 每帧再画」导致半透明叠加过饱和。
        this.drawCached = false;
        this.drawDynamic = true;
    }

    @Override
    public void load() {
        super.load();

        // 三张贴图按 mod 约定命名（与原版 LiquidBlock 同口径：{name}-bottom / -top）。
        // 目录 assets/sprites/blocks/lubricant-injector/ 不参与 atlas 键名。
        bottomRegion = loadOrFallback("-bottom");
        topRegion = loadOrFallback("-top");
        lightRegion = loadOrFallback("-light");
    }

    /**
     * 取分层贴图，缺失时回退主贴图并告警（与 {@code DualPurposeStorager} / 控制塔同款约定）。
     *
     * <p>{@code Core.atlas.find} 对缺失区域返回 error 占位图而非抛异常，
     * 直接用会让整块糊成缺图且毫无提示，故显式拦截。
     */
    private TextureRegion loadOrFallback(String suffix) {
        TextureRegion found = Core.atlas.find(name + suffix);
        if (!found.found()) {
            SiliconLog.warn("LubricantInjector '{}' missing {} texture, fallback to region", name, suffix);
            return region;
        }
        return found;
    }

    public class LubricantInjectorBuild extends Building implements BuildingBoostSystem.Provider {

        /** 液体「视为空」的阈值：与原版液体方块及两用存储器同口径（0.001）。 */
        private static final float LIQUID_THRESHOLD = 0.001f;

        /** 紧贴炮塔缓存：按 tick 重建并复用同一 Seq（零分配，供 targets() 与扣油共用）。 */
        private final Seq<Building> turretCache = new Seq<>();
        private double cacheTick = Double.MIN_VALUE;

        /**
         * 闪光强度（0~1）：工作时升向 1，停机时落回 0。
         *
         * <p>平滑逼近（{@code approachDelta}）而非直接置位——阶跃会让闪光在开关瞬间硬切，
         * 这与原版爆破钻头用 {@code warmup} 缓动是同一考虑。
         */
        public float glow = 0f;

        /**
         * 本帧是否确实在为至少一座炮塔提供强化（即「工作中」）。
         *
         * <p>由 {@link #update()} 每帧写入，是 {@link #glow} 的目标状态来源；
         * 绘制阶段只读 {@link #glow}、不做任何扫描，避免把逐帧成本压到渲染路径上。
         */
        public boolean active = false;

        @Override
        public Building building() {
            return this;
        }

        @Override
        public Seq<BuildingBoostSystem.Boost> boosts() {
            return boostList;
        }

        // 前置目标过滤：只把炮塔交给 System；非炮塔对象不尝试附 boost（不进 System 循环，避免无谓登记）
        @Override
        public Seq<Building> targets() {
            if (cacheTick != Vars.state.tick) {
                rebuildTurretCache();
            }
            return turretCache;
        }

        /** 按 tick 重建紧贴炮塔列表（仅扫一次 proximity，扣油阶段复用同一份结果）。 */
        private void rebuildTurretCache() {
            turretCache.clear();
            for (Building b : proximity) {
                if (b instanceof Turret.TurretBuild) {
                    turretCache.add(b);
                }
            }
            cacheTick = Vars.state.tick;
        }

        // 存油 + 本机是该目标的「确定性认领者」才提供（多人安全：认领者由 tile 坐标决定，不依赖 update 顺序）
        @Override
        public boolean canTarget(Building target) {
            return liquids.get(lubricant) > 0.001f && owns(target);
        }

        /**
         * 确定性认领：紧贴同一炮塔的多台注入器里，按 (tileX, tileY) 字典序最小者独占该炮塔的强化与油耗。
         *
         * <p><b>多人安全</b>：tile 坐标来自地图、客户端与服务器完全一致，因此两端算出同一个认领者，
         * 扣的也是同一台机器的油——不会因 update 顺序差异导致液体量分歧（order-based 认领会）。
         *
         * <p>候选须「同队 + 存油」；本机不满足则直接出局。持认领者油耗尽/失效后认领自然交接。
         */
        private boolean owns(Building target) {
            int myX = tileX(), myY = tileY();
            for (Building b : target.proximity) {
                // 跳过自己与非本方块
                if (b == this || !(b instanceof LubricantInjectorBuild other)) {
                    continue;
                }
                // 队伍用 ==（Team 枚举单例，跨端一致）——只与同队注入器竞争
                if (other.team != team) {
                    continue;
                }
                // 无油的注入器不参与竞争（不提供、也不该挡住本机）
                if (other.liquids.get(lubricant) <= 0.001f) {
                    continue;
                }
                int ox = other.tileX(), oy = other.tileY();
                if (ox < myX || (ox == myX && oy < myY)) {
                    return false; // 存在坐标更小的候选，本机让出
                }
            }
            return true;
        }

        // 拆除/摧毁时撤销自己提供的一切强化，避免贡献残留（System 立即重算受影响目标）
        @Override
        public void onRemoved() {
            super.onRemoved();
            removeProviderBoosts();
        }

        @Override
        public void update() {
            super.update();

            // 先接入 System：登记/更新本帧认领（资格、互斥、去重、apply/remove 均由 System 统一驱动）
            updateBoosts();

            // 再按「本机独占认领且攻击中」的己方炮塔数扣油（Time.delta≈1/60fps帧，除 60 折算为真实秒→5/s）；
            // 认领用与 canTarget 相同的确定性判定（owns）——与 System 登记口径一致，且客户端/服务器算出的
            // 认领者相同，扣的同一台机器的油，液体量不分歧（多人安全）。
            // 与攻速 Boost 的激活口径一致（isShooting）。无认领/无攻击时零消耗。
            int provided = 0;
            boolean hasOil = liquids.get(lubricant) > 0.001f;
            if (hasOil) {
                // 复用 targets() 的本 tick 缓存，不再重扫 proximity
                Seq<Building> turrets = targets();
                for (int i = 0, n = turrets.size; i < n; i++) {
                    Building b = turrets.get(i);
                    if (b instanceof Turret.TurretBuild t && t.isShooting()
                            && owns(b)
                            && BuildingBoostSystem.isProviderOf(b, this, LubricantBoost.instance.id())) {
                        provided++;
                    }
                }
                if (provided > 0) {
                    float held = liquids.get(lubricant);
                    float need = consumePerTurret * provided * Time.delta / 60f;
                    liquids.remove(lubricant, Math.min(need, held));
                }
            }

            // 闪光状态与扣油同源（同一遍循环的两个产物）：存油且确有炮塔在受惠才算「工作」。
            // 未工作时光强落回 0，故「没在起作用却一直闪」不会出现。
            active = hasOil && provided > 0;

            // 平滑逼近目标强度（0 或 1），避免开关瞬间硬切；与物理用 delta 而非帧数无关的常量。
            glow = Mathf.approachDelta(glow, active ? 1f : 0f, glowSpeed);
        }

        /**
         * 绘制：<b>四层</b>，自下而上依次为
         * ①{@code bottom} 底 → ②<b>原版液体动画</b> → ③{@code top} 顶盖 → ④{@code light} 闪光。
         *
         * <p>基准图 {@code lubricant-injector.png} 不单独绘制——它是 `bottom + top + light` 的合成产物，
         * 故四层画完即得基准图外观（液体层只在有油时出现，空罐时正好等于基准图）。
         *
         * <p><b>第 ② 层是原版液体动画</b>，与两用存储器/原版液体储罐同一入口
         * （{@link LiquidBlock#drawTiledFrames}）：用引擎的 {@code fluidFrames} 动画帧画出条纹流动的液面，
         * 并按液体自身的颜色着色、以填充比例作为 alpha（油多则浓、少则淡）。
         *
         * <p><b>画顶盖前必须重置颜色</b>：{@code drawTiledFrames} 内部经 {@code Drawf.liquid} 着色，
         * 会在 {@code Draw} 上<b>残留液体颜色</b>；不重置就会把顶盖也染成油色。
         * 这与两用存储器曾修复过的问题是同一处（其代码在画顶盖前有 {@code Draw.color(Color.white)}）。
         *
         * <p><b>闪光写法参照原版爆破钻头</b>（{@code BurstDrill} 的 glow 分支）：
         * 用 {@link Drawf#additive} 而非普通绘制——加色混合才能得到「发光」而非「贴一层颜色」的观感。
         * 该辅助方法内部已自行处理 {@code Draw.z}、颜色与 {@code Blending.additive} 的设置与复位，
         * 故这里不需要（也不应该）再手动调 {@code Draw.blend}。
         *
         * <p>强度 = 平滑后的 {@code glow} × 正弦脉动：前者保证开关有缓动，后者给出持续闪烁的「呼吸」感。
         * 两者相乘，故未工作（{@code glow → 0}）时脉动也被一并压到 0，无需额外分支。
         */
        @Override
        public void draw() {
            // ① 底座
            Draw.rect(LubricantInjector.this.bottomRegion, x, y);

            // ② 原版液体动画：有油才画，alpha 用填充比例（与原版液体储罐同口径，不做保底）
            float amount = liquids.currentAmount();
            Liquid current = liquids.current();
            if (current != null && amount > LIQUID_THRESHOLD) {
                LiquidBlock.drawTiledFrames(size, x, y, liquidPadding, current, amount / liquidCapacity);
            }

            // ③ 顶盖：先重置颜色，否则会被上一层残留的液体色染色
            Draw.color(Color.white);
            Draw.rect(LubricantInjector.this.topRegion, x, y);

            // ④ 闪光层：仅在贴图存在且确有一定强度时绘制，避免空转
            if (lightRegion.found() && glow > 0.001f) {
                // absin 给出 [0,1] 的平滑振荡（值域已由字节码确认），乘 glow 得到本帧整体强度。
                // 注意强度必须真正传给绘制——只算不用会让闪光恒定满亮、失去「闪烁」观感。
                float intensity = Mathf.absin(Time.time, 6f, 1f) * glow;
                if (intensity > 0.001f) {
                    // 用本仓库的临时色对象（SiliconTmp，与 Switch 同款），避免每帧新建 Color。
                    // lightColor 自身的 alpha 作为上限，再乘本帧强度。
                    Drawf.additive(lightRegion, SiliconTmp.c1.set(lightColor).a(lightColor.a * intensity), x, y);
                }
            }
        }

        // 只接受同队供给的润滑油，且留有余量
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return source.team == team && liquid == lubricant && liquids.get(lubricant) < liquidCapacity - 0.001f;
        }
    }
}