package silicon.util;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Building;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.production.GenericCrafter;

/**
 * 建筑强化系统：统一管理强化器（{@link Provider}）给建筑提供 boost 的全过程。
 *
 * <p>接口契约（{@link Boost} / {@link Provider} / {@link BoostVisual}）全部收敛在本类，
 * 具体效果实现放在 {@code silicon.util.boosts} 包（实现 {@link Boost} 并在加载期
 * {@link #register(Boost)} 自动注册）。
 *
 * <p>职责划分：
 * <ul>
 *   <li><b>驱动</b>：强化器每帧调一次 {@link #updateBoosts(Provider)}，System 先把
 *       所有强化器对同一目标的意愿合并去重（记入持久认领表 {@code contributors}），再在每 tick
 *       统一裁决一次后应用/撤销——保证同一个目标、同一个效果 id，一 tick 最多调用一次
 *       {@link Boost#apply(Building)}；</li>
 *   <li><b>不叠加规则</b>：一个目标只能持有同一效果的一份（如同"两个一样的 buff 不会并存"）。
 *       即使多个强化器同时为一个目标提供同一效果，效果也只生效一份、不会成倍放大；</li>
 *   <li><b>资格与队伍</b>：目标过滤四层把关，任何效果生效前都过这几关，
 *       防止对非目标方块误用炮塔专用 API 造成崩溃：
 *       <ol>
 *         <li>Provider 前置过滤 {@link Provider#targets()}（如只把炮塔交给 System，非可用对象不进循环）；</li>
 *         <li>System 全局名单 {@link #boostableTypes}；</li>
 *         <li>同队 {@link #sameTeam(Building, Building)}；</li>
 *         <li>每个 Boost 自带的目标过滤 {@link Boost#canTarget(Building)}（由 System 读取执行）。
 *             任一关不过即不登记该效果的贡献。</li>
 *       </ol></li>
 *   <li><b>生命周期（持续由强化器控制）</b>：效果只要强化器还在提供（如仍存油）就持续生效；
 *       强化器停止提供/被拆除/目标失效时，System 自动撤销（{@link Boost#remove(Building)}），
 *       不留残留——强化器经 {@link #removeProvider(Provider)}（在其 onRemoved() 里调用）
 *       即时清理，或由每 tick 一次的清扫兜底；</li>
 *   <li><b>互斥裁决</b>：冲突关系声明在每个 {@link Boost} 实现里
 *       （{@link Boost#conflictsWith(String)} + {@link Boost#priority()}），
 *       裁决与执行由本系统统一完成：同目标上冲突效果按优先级（同级按 id 字典序）选胜者，
 *       败者保持挂起不生效，胜者消失后自动解除；</li>
 *   <li><b>视觉调度</b>：显示内容由 boost 经 {@link Boost#visual(Building)} 提供，
 *       本系统负责经可插拔的 {@link VisualRenderer} 调度 {@link #drawBoosts()} 绘制
 *       （renderer 尚未定型，先留扩展点）。</li>
 * </ul>
 *
 * <p>apply/remove 语义：激活期间（count>0 且未被挂起）每帧调用一次 {@link Boost#apply(Building)}
 * （注入式每帧累加；引用式需幂等，首次才真正改动字段）；条件失格后调用一次
 * {@link Boost#remove(Building)} 干净撤销。
 *
 * <p><b>多人兼容约定</b>：本系统状态（contributors/active/…）是纯 JVM 本地的，客户端与服务器各自
 * 独立驱动，不做任何网络同步——因为它只影响<b>本地模拟</b>（炮塔充能/转角等），
 * 而这些量在两端由相同输入算出相同结果。为此要求：
 * <ol>
 *   <li><b>队伍检查</b>：一律用 {@code ==} 比较 {@link mindustry.game.Team}（枚举单例，跨端一致，
 *       天然排除敌队/derelict），禁止用 {@code equals/ordinal} 之外的假设或本地玩家身份做判定；</li>
 *   <li><b>确定性</b>：任何影响<b>网络化状态</b>（如液体量、功率、库存）的决策都不得依赖
 *       「谁先跑 update()」或遍历顺序——必须由两端一致的量（tile 坐标、建筑 id、队伍、同步的液体量等）
 *       推导。参考实现：{@code LubricantInjector.owns()}（按 tile 坐标裁决唯一认领者）。</li>
 * </ol>
 */
public final class BuildingBoostSystem {

    /** 可视化渲染接入点（预留）：由渲染管线设置为真正的实现；未设置时视觉不绘制。 */
    public static VisualRenderer visualRenderer;

    /** 渲染接入接口：System 负责调度，具体画法尚未定型。 */
    public interface VisualRenderer {
        /**
         * 绘制某个目标身上的一个生效 boost 徽记。
         *
         * @param target 受惠目标（非空、已生效）
         * @param visual 该 boost 的视觉描述（非空）
         * @param index  该 boost 在目标身上的序号（0 起）——供渲染器把多个徽记依次排开
         */
        void render(Building target, BoostVisual visual, int index);
    }

    /** 可被强化的方块类型名单：只有命中的方块才允许被施加任何强化。默认仅放行炮塔。 */
    public static final Seq<Class<? extends Building>> boostableTypes = new Seq<>(Class.class);

    /** 注册表：id → 效果单元，加载期由各 boost 自动注册。 */
    private static final ObjectMap<String, Boost> registry = new ObjectMap<>();

    /** 贡献者：目标 → (boost id → 当前希望其生效的强化器集合）。计数即集合人数，亦是撤销归属。 */
    private static final ObjectMap<Building, ObjectMap<String, ObjectSet<Provider>>> contributors = new ObjectMap<>();

    /** 互斥挂起：目标 → (败者 id → 胜者 id)。胜者仍占位期间败者保持不生效。 */
    private static final ObjectMap<Building, ObjectMap<String, String>> suppressed = new ObjectMap<>();

    /** 当前实际生效状态：目标 → (boost id → 是否生效)，用于对比做应用/撤销。 */
    private static final ObjectMap<Building, ObjectMap<String, Boolean>> active = new ObjectMap<>();

    /** 存活强化器集合：用于每帧清扫失效 provider（防穿漏）。 */
    private static final ObjectSet<Provider> providerSet = new ObjectSet<>();

    /** 冲洗缓冲：复用以避免每 tick 新建集合（先收集再处理，规避 keys() 边遍历边改）。 */
    private static final ObjectSet<Building> flushBuffer = new ObjectSet<>();
    /** removeProvider 受影响目标缓冲（复用，避免每次分配）。 */
    private static final Seq<Building> affectedBuffer = new Seq<>();
    /** 撤销/回收 id 暂存缓冲（复用；ObjectMap 边遍历边 remove 会抛并发修改异常，须先收集）。 */
    private static final Seq<String> idBuffer = new Seq<>();
    /** 清扫缓冲：失效目标 / 失效强化器（复用，避免每 tick 新建）。 */
    private static final ObjectSet<Building> badTargets = new ObjectSet<>();
    private static final Seq<Provider> badProviders = new Seq<>();

    /** 已冲洗本 tick 的标记（按世界 tick 归组，保证一帧只统一裁决一次）。 */
    private static double flushedTick = Double.MIN_VALUE;

    private BuildingBoostSystem() {
    }

    static {
        boostableTypes.add(Turret.TurretBuild.class);
        // 工厂：供「节能」等工厂类效果（见 EfficiencyControlTower / EnergySavingBoost）
        boostableTypes.add(GenericCrafter.GenericCrafterBuild.class);
    }

    /**
     * **世界级重置**：清空全部静态状态表。
     *
     * <p><b>换图/读档必须调用</b>（已挂在 {@code EventType.WorldLoadEvent} 上）。
     * 本类的四张表都以 {@link Building} 为键，而 {@code Building → Tile → World} 构成强引用链：
     * 不清理会把<b>整张旧地图</b>钉在内存里出不来。更糟的是「新图里没有强化器」时
     * {@link #flushFrame()} 根本不会被触发（它由强化器的 update 驱动），
     * 于是 {@link #sweepInvalid()} 也不会跑——旧建筑会继续被画上强化徽记。
     *
     * <p>注意：只清本类状态；各 {@link Boost} 自持的静态表（如超频的掉血计时）
     * 由其 {@link Boost#onWorldReset()} 自行清理，第三方实现不清理也不会残留建筑引用。
     */
    public static void reset() {
        contributors.clear();
        suppressed.clear();
        active.clear();
        providerSet.clear();
        flushBuffer.clear();
        affectedBuffer.clear();
        idBuffer.clear();
        badTargets.clear();
        badProviders.clear();
        // 让新世界的第一个 tick 重新触发一次 flushFrame（否则 flushedTick 仍是旧世界的值）
        flushedTick = Double.MIN_VALUE;
        for (Boost boost : registry.values()) {
            boost.onWorldReset();
        }
    }

    /**
     * 一次具体的建筑强化效果单元（一个「buff」的实现）。具体实现放
     * {@code silicon.util.boosts} 包，加载期 {@link #register(Boost)} 自动注册。
     *
     * <p>职责：功能的判定/实现全部写在这里；System 只负责驱动、资格、队伍、
     * 互斥、不叠加与应用/撤销，不感知效果细节。
     *
     * <p>{@link #apply(Building)} 两种语义（实现自选并保持），系统统一按
     * 「激活期间每帧一次 apply、失格时一次 remove」驱动，且**不叠加**：一个目标
     * 同一效果每帧至多被 apply 一次，无论多少强化器在提供：
     * <ul>
     *   <li><b>注入式</b>：apply 每帧把数值加进目标（如 {@code reloadCounter += ...}），
     *       remove 无需还原，条件消失即自动失效；</li>
     *   <li><b>引用式</b>：apply 需幂等（内部引用计数，首次才真正改动字段），
     *       remove 计数递减、归零才还原。</li>
     * </ul>
     *
     * <p>互斥声明与本效果优先级都在这里声明，具体裁决与执行交给 {@link BuildingBoostSystem}。
     */
    public interface Boost {

        /** 唯一 id：自动注册进 System 注册表、供互斥声明与日志引用。 */
        String id();

        /**
         * 显示名称（本地化）：用于强化面板等 UI 展示。
         * 默认取 {@link #id()}，实现应覆写为可读名称（建议走 bundle 归类管理）。
         */
        default String name() {
            return id();
        }

        /**
         * 简要强化描述（本地化）：一句话说明本效果带来的收益，如
         * 「+100% 旋转速度；+20% 攻击速度」。用于强化面板展示，<b>每个 Boost 必须给出</b>。
         * 实现建议走 bundle 归类管理；数值应与实际生效值一致。
         *
         * <p>注意：<b>「全部档位」还是「当前档位」由实现自行决定</b>——本方法拿不到目标，
         * 故多档位效果通常返回全部档位作参考，而 UI 展示应改用
         * {@link #description(Building)}（只给当前生效的那一档）。
         */
        String description();

        /**
         * 目标上的显示名称（本地化）：默认同 {@link #name()}。
         * <b>多档位效果应覆写</b>为「名称 + 档位」（如「2级超频」），
         * 供消息/面板逐建筑展示——本方法拿得到目标，故能报出该建筑实际生效的档位。
         */
        default String name(Building target) {
            return name();
        }

        /**
         * 目标上的显示描述（本地化）：默认同 {@link #description()}。
         * <b>多档位效果应覆写</b>为<b>仅当前生效档</b>的加成（如「+100% 生产效率，+125% 电力消耗，-10 生命/秒」），
         * 避免把三档全列出来。档位经 {@link #levelOf(Building, String)} 取得。
         */
        default String description(Building target) {
            return description();
        }

        /**
         * 目标过滤：System 在登记贡献前读取本方法，控制本效果能否作用于该目标
         * （如只接受炮塔 {@code target instanceof Turret.TurretBuild}）。
         * 前置类型校验放这里，具体效果不必在 {@link #shouldApply(Building)}/{@link #apply(Building)}
         * 里反复判型。默认放行。
         */
        default boolean canTarget(Building target) {
            return true;
        }

        /** 触发条件：当前时刻 target 是否应保持本强化（System 每帧询问，需无副作用）。 */
        boolean shouldApply(Building target);

        /** 生效：激活期间每帧调用一次（同目标同效果每帧至多一次，不叠加）；实现需符合注入式/引用式约定。 */
        void apply(Building target);

    /** 撤销：失格 / 被互斥顶替 / 目标或强化器失效时由 System 调用，必须可还原、不得残留。 */
    void remove(Building target);

    /**
     * 换图/读档时由 {@link #reset()} 调用一次，用于清理本效果自持的静态状态。
     *
     * <p><b>默认空实现</b>：绝大多数效果无状态（注入式随条件自动失效），无需处理。
     * 只有自持静态表的效果才需要覆写——如超频的「每建筑掉血计时」表，
     * 否则换图后残留旧世界的建筑键（内存泄漏 + 掉血速率错配）。
     *
     * <p>实现约定：只清自己的状态，<b>不要</b>调用 System 的表（{@link #reset()} 已在清）。
     */
    default void onWorldReset() {
    }

        /** 互斥优先级：与其它效果的冲突裁决用，数值大者胜。默认 0。 */
        default int priority() {
            return 0;
        }

        /** 互斥声明：与 {@code otherId} 是否冲突。冲突关系写在各实现里。 */
        default boolean conflictsWith(String otherId) {
            return false;
        }

        /**
         * 视觉描述（可空，预留）：告诉 System「本强化目前应向玩家显示什么」。
         * 渲染细节尚未定型，返回 {@link BoostVisual} 描述对象即可；绘制由 System 调度。
         */
        default BoostVisual visual(Building target) {
            return null;
        }
    }

    /**
     * 强化器（Boost Provider）mixin 接口：由提供 buff 的建筑 Build 类实现
     * （如润滑油注入器），向 System 声明提供哪些效果、作用于哪些目标。
     *
     * <p>Build 类在 update() 末尾调用一次 {@link #updateBoosts()}，System 即完成
     * 本 tick 贡献登记 → 统一裁决 → 应用/撤销（含跨强化器的去重合并）。
     * 强化器被拆除/摧毁时，须在 Build 的 onRemoved() 里调用 {@link #removeProviderBoosts()}，
     * 让 System 立即撤销它提供的一切强化（避免残留）。
     */
    public interface Provider {

        /** 自身建筑实体（System 以此取队伍/位置/团队资格）。 */
        Building building();

        /** 候选目标集合（默认取紧贴 proximity；范围型强化器可自行覆盖）。
         *  <p>返回 {@link Seq} 以便 System 走下标遍历（零迭代器分配）；实现方宜复用同一 Seq 实例，
         *  避免每帧新建。 */
        default Seq<Building> targets() {
            return building().proximity;
        }

        /** 本强化器提供的效果单元列表（可来自 System 注册表按 id 引用，也可直接持有实例）。
         *  <p>返回 {@link Seq} 以便零分配遍历；实现方宜复用/缓存该 Seq。 */
        Seq<Boost> boosts();

        /** 强化器自身的附加目标过滤（如朝向/距离），默认全放行；队伍与名单由 System 统一判。
         *  <p><b>多人注意</b>：本方法在客户端与服务器都会执行且结果必须一致——不得依赖 update 顺序
         *  或本地状态，否则两端提供集合不同会造成分歧。 */
        /**
         * 本机当前是否为<b>该效果</b>提供（默认恒为是）。
         *
         * <p><b>逐效果开关的正确位置</b>：Provider 若有「模式 / 档位 / 状态」等条件
         * （如同一台方块在不同模式下提供<b>不同</b>的效果），必须用本方法逐个效果表达，
         * <b>不能</b>靠「boosts() 只返回当前模式那一个效果」——见文档「⚠ Provider 作者必读」：
         * System 只遍历 {@link #boosts()} 里列出的效果，没列出的效果其旧贡献<b>永远不会被撤销</b>。
         *
         * <p>返回 false 时 System 走正规的「意愿为 false → 撤销该效果」路径，
         * 效果会立即被 {@link Boost#remove(Building)} 撤销。
         */
        default boolean provides(Boost boost) {
            return true;
        }

        /**
         * 强化器本帧对某目标的可用门槛（在队伍/名单之外，Provider 自身的粗筛，如「本机是否开机」）。
         * 逐效果的开关请用 {@link #provides(Boost)}。
         *
         * <p>System 在登记前读取本方法，故返回 false 的目标不登记贡献、不会进
         * {@link #apply(Building)}。默认全放行。
         */
        default boolean canTarget(Building target) {
            return true;
        }

        /**
         * 本机为<b>该效果</b>提供的<b>档位</b>（默认 0 = 无档位/不适用）。
         *
         * <p>供「同一效果有多个强度档」的场景（如效率控制塔的 1~3 级节能/超频）：
         * 效果实现通常是<b>单例</b>，无法把档位存成实例字段（多台塔会互相覆盖），
         * 故档位由 Provider 持有，System 按目标查出其提供者后再向其索取。
         *
         * <p>取值约定：<b>正数</b>表示有效档位（自 1 起），0 表示不提供此效果。
         * 同一目标有多个提供者时，{@link #levelOf(Building, String)} 取<b>建筑 id 最小</b>者的档位，
         * 与互斥裁决同口径（确定性、两端一致）。
         */
        default int levelOf(Boost boost) {
            return 0;
        }

        /** update() 里调用即可接入 System 的驱动循环。 */
        default void updateBoosts() {
            BuildingBoostSystem.updateBoosts(this);
        }

        /** 方块被拆除/摧毁时调用（写在 Build.onRemoved() 里）：让 System 撤销本强化器提供的一切强化。 */
        default void removeProviderBoosts() {
            removeProvider(this);
        }
    }

    /**
     * 视觉描述（可空，预留扩展点）：boost 通过它「告诉 System 显示什么」。
     * 调试阶段由 {@link BoostOverlay} 在目标方块**左下角**绘制 {@link #icon(Building)} 徽记；
     * 只要目标身上有任意一个生效的 boost 就显示（多个则沿底边依次排开）。
     * {@link #type()} 供渲染管线分发（未定型，先用类名自由扩展）。
     */
    public interface BoostVisual {

        /** 视觉类型标识，供 System 渲染管线分发（未定型，先用类名自由扩展）。 */
        default String type() {
            return getClass().getSimpleName();
        }

        /** 强化图标：建议直接取用原版图集资源以贴合游戏风格（如 {@code StatusEffects.xx.uiIcon}）。
         *  null 则该效果不绘制图标。 */
        default TextureRegion icon(Building target) {
            return null;
        }

        /** 徽记配色（背景/描边用，null 用渲染器默认色）。 */
        default Color color() {
            return null;
        }
    }

    /** 向规则名单追加一种可被强化的方块类型（供子类/配置在加载期登记）。 */
    public static void addBoostable(Class<? extends Building> type) {
        boostableTypes.add(type);
    }

    /** 规则名单检查：b 是名单中任一类型的实例才可被强化（null 恒为否）。 */
    public static boolean isBoostable(Building b) {
        if (b == null) {
            return false;
        }
        for (Class<? extends Building> type : boostableTypes) {
            if (type.isInstance(b)) {
                return true;
            }
        }
        return false;
    }

    /** 己方判定：多人下 Team 为跨端一致的枚举单例，== 即网络安全同队判，天然排除敌队/derelict。 */
    public static boolean sameTeam(Building a, Building b) {
        return a != null && b != null && a.team == b.team;
    }

    /** 用 id 从注册表取效果单元（未注册返回 null）。 */
    public static Boost get(String id) {
        return registry.get(id);
    }

    /** 自动注册：把一种效果单元登记进系统（重复 id 覆盖）。加载期调用。 */
    public static void register(Boost boost) {
        registry.put(boost.id(), boost);
    }

    /**
     * 查询目标上当前生效的效果单元列表（按显示顺序无关，null 安全）。
     * 供强化面板等 UI 展示：可取 {@link Boost#name()} / {@link Boost#description()} / {@link Boost#visual(Building)}。
     *
     * <p>注意：每次调用会新建 Seq，仅供点击/打开面板等低频路径使用，勿放进每帧循环。
     */
    public static Seq<Boost> activeBoosts(Building target) {
        Seq<Boost> out = new Seq<>();
        ObjectMap<String, Boolean> map = active.get(target);
        if (map != null) {
            for (String id : map.keys()) {
                if (!Boolean.TRUE.equals(map.get(id))) {
                    continue;
                }
                Boost boost = registry.get(id);
                if (boost != null) {
                    out.add(boost);
                }
            }
        }
        return out;
    }

    /** 目标上是否还有生效的强化（零分配，供每帧轮询类逻辑使用）。 */
    public static boolean hasActiveBoosts(Building target) {
        ObjectMap<String, Boolean> map = active.get(target);
        if (map == null) {
            return false;
        }
        for (String id : map.keys()) {
            if (Boolean.TRUE.equals(map.get(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定效果当前是否在目标上生效（零分配，O(1)）。
     *
     * <p>供「引擎钩子」在<b>被引擎回调时</b>查询自身倍率用：钩子不能缓存每建筑状态
     * （缓存会在两端产生不同步窗口），只能在每次被查询时回到 System 问一次。
     *
     * <p>注意：读的是本 tick 冲洗后的状态，若钩子所在子系统（电力图 / consumer 结算）的更新
     * 早于本 System 冲洗，则该 tick 读到的是上一 tick 的状态——对倍率类效果只是 1 tick 滞后，不影响正确性。
     *
     * @param boostId 效果 id（见 {@link Boost#id()}）
     */
    public static boolean isActive(Building target, String boostId){
        ObjectMap<String, Boolean> map = active.get(target);
        return map != null && Boolean.TRUE.equals(map.get(boostId));
    }

    /**
     * 查询该效果在目标上的<b>档位</b>（0 = 无有效提供者/效果不适用）。
     *
     * <p>供「引擎钩子」与效果自身取当前档位：效果实现是<b>单例</b>，档位不能存成实例字段
     * （多台 Provider 会互相覆盖），必须按目标回查其提供者。
     *
     * <p>多个提供者时取<b>格坐标 (tileX, tileY) 字典序最小</b>者的档位——与
     * {@code LubricantInjector#owns()} 同一口径：格坐标由地图派生，<b>客户端与服务器必然一致</b>，
     * 且与 update 顺序无关。<b>刻意不用建筑 {@code id}</b>：它由各端的 {@code EntityGroup.nextId()}
     * 本地分配、只靠实体状态快照同步，属「未验证两端一致」的量，不适合做裁决键。
     *
     * <p>零分配。返回值仅在对应效果当前生效时才有意义（调用方通常先判 {@link #isActive}）。
     *
     * @param boostId 效果 id（见 {@link Boost#id()}）
     */
    public static int levelOf(Building target, String boostId) {
        Boost boost = registry.get(boostId);
        if (boost == null) {
            return 0;
        }
        ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
        ObjectSet<Provider> set = tmap == null ? null : tmap.get(boostId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        Provider best = null;
        int bestX = Integer.MAX_VALUE, bestY = Integer.MAX_VALUE;
        for (Provider provider : set) {
            Building b = provider.building();
            if (b == null) {
                continue;
            }
            // 字典序比较 (tileX, tileY)：两端一致，且不依赖 Provider 的遍历/登记顺序
            int px = b.tileX(), py = b.tileY();
            if (px < bestX || (px == bestX && py < bestY)) {
                bestX = px;
                bestY = py;
                best = provider;
            }
        }
        return best == null ? 0 : best.levelOf(boost);
    }

    // —— 档位化效果的共用工具（倍率表按档位索引、展示文案）——

    /**
     * 档位（自 1 起）→ 倍率表下标，越界时落到最近的合法档。
     *
     * <p>供「同一效果多强度档」的 {@link Boost} 查自己的倍率表。返回值保证落在
     * {@code [0, length-1]}，故<b>调用方无需判空</b>：档位为 0（无提供者/不适用）
     * 或表被配错时表现为「按 1 级生效」——属可接受的降级，好过数组越界崩游戏。
     */
    public static int levelIndex(int level, int length) {
        if (length <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(length - 1, level - 1));
    }

    /**
     * 按档位取倍率表中的值：等价于 {@code table[levelIndex(level, table.length)]}，
     * 但<b>表为空时回落到 1f（原样、不改变任何量）</b>，不会抛数组越界。
     *
     * <p>倍率表是各效果上的 <b>public 可变字段</b>，别的 mod（或本 mod 后续改动）有可能把它置空。
     * 效果失效不该让游戏崩在这种地方，故把「表空了」当作「本效果不改变任何量」处理。
     *
     * @param table 倍率表（索引 0 = 1 级），可为 null / 空
     * @param level 档位（自 1 起），越界自动夹到最近合法档
     */
    public static float levelValue(float[] table, int level) {
        if (table == null || table.length == 0) {
            return 1f;
        }
        return table[levelIndex(level, table.length)];
    }

    /**
     * 倍率差 → 百分比文本，<b>符号与数值一致</b>：{@code 0.5f} → {@code "+50%"}，
     * {@code -0.2f} → {@code "-20%"}，{@code 0f} → {@code "0%"}。
     *
     * <p><b>调用方必须传入带符号的差值</b>（{@code scale - 1f}）：节能是负的（0.8× → -20%），
     * 超频是正的（1.5× → +50%）。本方法只负责如实加符号，<b>不反转</b>——早期版本把「正数」当作
     * 「节省量」渲染成负号，导致节能恰好正确、超频却把 +50% 印成 -50%，故改为统一的有符号口径。
     *
     * <p>用于强化详情/配置面板的加成文案，避免各效果各写一遍格式化。
     */
    public static String percentText(float ratio) {
        int v = Math.round(ratio * 100f);
        String sign = v > 0 ? "+" : v < 0 ? "-" : "";
        return sign + Math.abs(v) + "%";
    }

    // —— 强化徽记 ——

    /**
     * 强化徽记的<b>统一图标</b>（{@link TextureRegion}）。
     *
     * <p><b>全项目只有这一张</b>：徽记表达的是「这座建筑身上有强化生效」，
     * <b>不是</b>「生效的是哪一个效果」——具体是哪个效果、哪一档，由点击徽记后的消息面板逐行列出。
     * 徽记若每个效果各用一张图标，同一座建筑上会出现语义重叠的多种标识
     * （且原版状态图标与「建筑被强化」并无一一对应关系），反而增加噪音。
     *
     * <p>要换图标<b>只改这一处</b>即可全局生效（各效果的 {@link Boost#visual(Building)} 都从这里取，
     * {@link BoostOverlay} 在其返回 null 时也回落到这里）。
     *
     * <p>当前用原版「超频」（overclock）状态图标——它本身就是游戏里表示「获得增益」的通用符号，
     * 且为游戏自带美术，风格与 UI 统一。
     */
    public static TextureRegion badgeIcon() {
        return StatusEffects.overclock.uiIcon;
    }

    /**
     * 提供归属查询（供强化器决定是否耗资源）：本机当前是否已登记为该目标该效果的有效提供者。
     *
     * <p><b>多人注意</b>：本判定读的是 {@code contributors}（由两端一致的输入推导），故两端结果相同；
     * 可安全用于决定液体等网络化资源的扣减。相对地，<b>不要</b>用「谁先跑 update」这类顺序依赖的规则
     * 决定耗资源——那会因两端 update 顺序不同造成液体分歧（desync）。需要「唯一归属」时，请用
     * tile 坐标/建筑 id 等两端一致的量裁决（参考 {@code LubricantInjector#owns}）。
     */
    public static boolean isProviderOf(Building target, Provider provider, String boostId) {
        ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
        if (tmap == null) {
            return false;
        }
        ObjectSet<Provider> set = tmap.get(boostId);
        return set != null && set.contains(provider);
    }

    /** 每帧驱动入口：强化器在 update() 里调用。按世界 tick 分组，一帧只统一裁决一次。 */
    public static void updateBoosts(Provider provider) {
        Building building = provider.building();
        if (building == null || !building.isValid()) {
            removeProvider(provider);
            return;
        }

        // 新 tick 首次调用时，把上一 tick 汇总好的意愿整体裁决并应用/撤销
        if (flushedTick != Vars.state.tick) {
            flushFrame();
            flushedTick = Vars.state.tick;
        }

        providerSet.add(provider);

        Seq<Building> targets = provider.targets();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (Building target : targets) {
            if (target == null || target == building || !target.isValid()) {
                continue;
            }
            // 先登记本强化器对目标各效果的本 tick 意愿（0/1，跨强化器去重累计）
            collectContributions(provider, target,
                    sameTeam(building, target) && isBoostable(target) && provider.canTarget(target));
        }
    }

    /** 撤销强化器：清掉它的全部贡献并即时重算受影响目标（防止贡献残留、效果失控）。 */
    public static void removeProvider(Provider provider) {
        if (provider == null) {
            return;
        }
        providerSet.remove(provider);
        removeProviderContributions(provider);
    }

    // —— 内部：贡献登记 ——

    /**
     * 登记/撤销本强化器对某目标各效果的意愿。
     *
     * <p><b>零分配</b>：仅在强化器真正「加入/退出」某效果集合时才创建对应内层容器；
     * 稳态（已在集合内、意愿不变）不新建任何对象。空集合即时回收，避免状态表膨胀。
     */
    private static void collectContributions(Provider provider, Building target, boolean eligible) {
        ObjectMap<String, ObjectSet<Provider>> targetContributors = contributors.get(target);

        Seq<Boost> boosts = provider.boosts();
        if (boosts == null) {
            return; // 第三方 Provider 返回 null：当作「本机不提供任何效果」，不炸
        }
        // 注：arc 的 Seq 在本引擎里 size 是 public 字段（无 size() 方法），故用 boosts.size
        for (int i = 0, n = boosts.size; i < n; i++) {
            Boost boost = boosts.get(i);
            String id = boost.id();
            // 生效意愿 = 公共关(队伍/名单/Provider.canTarget) && Provider 逐效果开关(模式等)
            //          && Boost 自身目标过滤 && 触发条件
            boolean want = eligible && provider.provides(boost) && boost.canTarget(target)
                    && boost.shouldApply(target);

            ObjectSet<Provider> set = targetContributors == null ? null : targetContributors.get(id);
            if (want) {
                if (set == null) {
                    if (targetContributors == null) {
                        targetContributors = contributors.get(target, ObjectMap::new);
                    }
                    set = targetContributors.get(id, ObjectSet::new);
                }
                set.add(provider);
            } else if (set != null && set.remove(provider)) {
                if (set.isEmpty()) {
                    // 该效果已无人提供 → 连空集合一并回收
                    targetContributors.remove(id);
                    if (targetContributors.isEmpty()) {
                        // 目标已无任何贡献 → 回收条目，并置空局部引用，
                        // 使后续 boost 仍能重新建表（不能 return，否则会漏掉后面的效果）
                        contributors.remove(target);
                        targetContributors = null;
                    }
                }
            }
            // 意愿恒由 contributors 的集合人数表达（无独立计数），无需额外记账
        }
    }

    // —— 内部：每 tick 统一冲洗（裁决 + 应用/撤销） ——

    /**
     * 每 tick 一次的统一冲洗。
     *
     * <p>「本 tick 应生效集合」直接由 {@link #contributors} 推导——冲洗发生在本 tick 首个强化器
     * 登记<b>之前</b>，此时 contributors 恰好仍是上一 tick 登记完的最终状态，故无需额外 pending 快照
     * （省掉每目标每 tick 的内层 Map 分配与计数维护）。
     */
    private static void flushFrame() {
        // 先收集后处理：keys() 迭代器依赖内部数组，而下面会增删状态表
        flushBuffer.clear();
        for (Building target : contributors.keys()) {
            flushBuffer.add(target);
        }
        for (Building target : active.keys()) {
            flushBuffer.add(target); // 覆盖「曾生效但已无贡献」→ 需走撤销
        }

        for (Building target : flushBuffer) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            resolveMutex(target, tmap);
            reconcile(target, tmap, true); // 每 tick 唯一的 apply 路径
        }

        sweepInvalid();
    }

    /** 取某效果当前的提供者人数（无提供者记 0）。 */
    private static int count(ObjectMap<String, ObjectSet<Provider>> tmap, String id) {
        if (tmap == null) {
            return 0;
        }
        ObjectSet<Provider> set = tmap.get(id);
        return set == null ? 0 : set.size;
    }

    /**
     * 互斥裁决：目标上冲突的效果按优先级（同级 id 字典序）选胜者，败者挂起；
     * 挂起表每次裁决按本帧结论重建——胜者仍在则败者继续挂起，胜者消失则败者自然参选。
     *
     * <p>排序键只有 {@code priority()} 与效果 id 两项，<b>刻意不引入建筑 id / 放置顺序</b>：
     * 字典序是纯函数，两端必然算出同一结果，且与玩家操作时序无关（不会因「谁先放」而改变结果）。
     */
    private static void resolveMutex(Building target, ObjectMap<String, ObjectSet<Provider>> tmap) {
        // 快速路径：至多一个效果时不可能冲突（绝大多数场景），直接清挂起表
        if (tmap == null || tmap.size <= 1) {
            suppressed.remove(target);
            return;
        }

        ObjectMap<String, String> next = new ObjectMap<>();

        Seq<String> ids = new Seq<>();
        for (String id : tmap.keys()) {
            if (count(tmap, id) > 0) {
                ids.add(id);
            }
        }
        if (ids.size <= 1) {
            suppressed.remove(target);
            return;
        }

        // 高优先级优先，同级按 id 字典序。
        // 刻意只用「纯函数」做裁决键（优先级 + 效果 id）：两端必然算出同一结果，
        // 且与放置顺序/玩家操作时序无关。
        ids.sort((a, b) -> {
            int c = Integer.compare(priorityOf(b), priorityOf(a));
            return c != 0 ? c : a.compareTo(b);
        });

        Seq<String> keep = new Seq<>();
        for (String id : ids) {
            boolean conflict = false;
            for (String kept : keep) {
                Boost b = registry.get(id);
                Boost k = registry.get(kept);
                if (b != null && k != null && (b.conflictsWith(kept) || k.conflictsWith(id))) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                next.put(id, keep.first());
            } else {
                keep.add(id);
            }
        }

        if (next.isEmpty()) {
            suppressed.remove(target);
        } else {
            suppressed.put(target, next);
        }
    }

    /**
     * 应用/撤销：以「有人提供且未被挂起」为应生效集合——应生效的<b>每 tick 调用一次 apply</b>
     * （保证注入式逐帧累加、不叠加），不再应生效的调用一次 remove。
     * 生效状态表<b>原地更新</b>，不每 tick 新建快照对象。
     *
     * @param allowApply 是否允许本次调用执行 apply。<b>只有每 tick 一次的 {@link #flushFrame()} 传 true</b>；
     *                   其余即时重算路径（{@link #removeProviderContributions}）必须传 false，
     *                   否则同 tick 内会对同一目标重复 apply（注入式效果被叠加两次 =
     *                   转角/充能/进度翻倍，且可反复触发刷量），违反本类的「一 tick 至多一次」契约。
     */
    private static void reconcile(Building target, ObjectMap<String, ObjectSet<Provider>> tmap, boolean allowApply) {
        ObjectMap<String, String> targetSuppressed = suppressed.get(target);
        ObjectMap<String, Boolean> state = active.get(target);

        // 1) 应用：应生效集合逐个 apply（仅每 tick 一次的驱动路径执行）
        if (allowApply && tmap != null) {
            for (String id : tmap.keys()) {
                if (count(tmap, id) <= 0) continue;
                if (targetSuppressed != null && targetSuppressed.containsKey(id)) continue;
                Boost boost = registry.get(id);
                if (boost == null) continue;
                boost.apply(target);
                if (state == null) {
                    state = new ObjectMap<>();
                    active.put(target, state);
                }
                if (!Boolean.TRUE.equals(state.get(id))) {
                    state.put(id, true);
                }
            }
        }

        // 2) 撤销：已不满足「有人提供且未被挂起」的（先收集再删——keys() 边遍历边 remove 会抛并发修改）
        if (state != null) {
            idBuffer.clear();
            for (String id : state.keys()) {
                if (count(tmap, id) > 0 && (targetSuppressed == null || !targetSuppressed.containsKey(id))) {
                    continue; // 仍生效
                }
                idBuffer.add(id);
            }
            for (String id : idBuffer) {
                Boost boost = registry.get(id);
                if (boost != null) {
                    boost.remove(target);
                }
                state.remove(id);
            }
            idBuffer.clear();
            if (state.isEmpty()) {
                active.remove(target);
            }
        }
    }

    // —— 内部：强化器/目标生命周期清理 ——

    private static void removeProviderContributions(Provider provider) {
        // 全程「先收集后删除」：contributors/tmap 的 keys() 迭代期间不得修改其自身结构
        affectedBuffer.clear();
        for (Building target : contributors.keys()) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            boolean changed = false;
            idBuffer.clear();
            for (String id : tmap.keys()) {
                ObjectSet<Provider> set = tmap.get(id);
                if (set.remove(provider)) {
                    changed = true;
                    if (set.isEmpty()) {
                        idBuffer.add(id); // 延后回收空集合
                    }
                }
            }
            for (String id : idBuffer) {
                tmap.remove(id);
            }
            idBuffer.clear();
            if (changed) {
                affectedBuffer.add(target);
            }
        }

        for (Building target : affectedBuffer) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            if (tmap != null && tmap.isEmpty()) {
                contributors.remove(target); // 该目标已无任何贡献，回收条目
            }
            resolveMutex(target, tmap);
            // 不 apply：这是「即时撤销」路径，本 tick 的 apply 已由 flushFrame 统一做过一次。
            // 若这里再 apply 一次，同一目标同一 tick 会被 apply 两次（注入式效果翻倍）。
            reconcile(target, tmap, false);
        }
        affectedBuffer.clear();
    }

    /** 清扫已失效目标/强化器：杜绝被拆除的建筑在状态表里残留（每 tick 一次，随 flushFrame 触发）。 */
    private static void sweepInvalid() {
        // 先收集再删除：ObjectMap 的 keys() 迭代器依赖内部数组，
        // 边遍历边 remove 会抛并发修改异常/漏扫，故与强化器侧同样先收集后处理。
        // 两个容器为静态复用，避免每 tick 新建。
        badTargets.clear();
        collectInvalid(active.keys(), badTargets);
        collectInvalid(contributors.keys(), badTargets);
        collectInvalid(suppressed.keys(), badTargets);
        for (Building target : badTargets) {
            removeBuilding(target);
        }
        badTargets.clear();

        badProviders.clear();
        for (Provider provider : providerSet) {
            Building pb = provider.building();
            if (pb == null || !pb.isValid()) {
                badProviders.add(provider);
            }
        }
        for (Provider provider : badProviders) {
            removeProvider(provider);
        }
        badProviders.clear();
    }

    private static void collectInvalid(Iterable<Building> keys, ObjectSet<Building> out) {
        for (Building target : keys) {
            if (target == null || !target.isValid()) {
                out.add(target);
            }
        }
    }

    /** 目标失效/拆除：清掉它的一切状态，并撤销仍生效的效果。 */
    private static void removeBuilding(Building target) {
        ObjectMap<String, Boolean> map = active.get(target);
        if (map != null) {
            // 先收集再回调：boost.remove() 是第三方代码，若它反过来动本类状态表，
            // 边遍历 map.keys() 回调会抛并发修改异常。与本类其余清理路径保持同一口径。
            idBuffer.clear();
            for (String id : map.keys()) {
                if (Boolean.TRUE.equals(map.get(id))) {
                    idBuffer.add(id);
                }
            }
            for (String id : idBuffer) {
                Boost boost = registry.get(id);
                if (boost != null) {
                    boost.remove(target);
                }
            }
            idBuffer.clear();
        }
        active.remove(target);
        contributors.remove(target);
        suppressed.remove(target);
    }

    /**
     * 视觉调度：渲染管线在每帧绘制阶段调用；内容由各 boost 的 {@link Boost#visual(Building)} 提供。
     * 只要目标身上有任意一个生效的 boost 就会回调一次渲染（序号 index 供多个徽记排开）。
     */
    public static void drawBoosts() {
        if (visualRenderer == null) {
            return;
        }
        for (Building target : active.keys()) {
            ObjectMap<String, Boolean> map = active.get(target);
            if (map == null) {
                continue;
            }
            int index = 0;
            for (String id : map.keys()) {
                if (!Boolean.TRUE.equals(map.get(id))) {
                    continue;
                }
                Boost boost = registry.get(id);
                if (boost == null) {
                    continue;
                }
                BoostVisual visual = boost.visual(target);
                if (visual != null) {
                    visualRenderer.render(target, visual, index++);
                }
            }
        }
    }

    private static int priorityOf(String id) {
        Boost boost = registry.get(id);
        return boost == null ? 0 : boost.priority();
    }
}