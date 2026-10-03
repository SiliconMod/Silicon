package silicon.util.boosts;

import arc.Core;
import arc.graphics.Color;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.world.Block;
import mindustry.world.blocks.production.GenericCrafter;
import silicon.util.BuildingBoostSystem;

/**
 * 节能（钩子式 Boost）：只作用于<b>消耗电力的工厂</b>，同时
 * <ul>
 *   <li><b>电力消耗 −20%</b>：经 {@link BlockConsumerHooks.ScaledConsumePower} 覆写
 *       {@code requestedPower(build)}，只降低该建筑自己的请求电量，不影响同类型其他建筑；</li>
 *   <li><b>生产速度 −10%</b>：经 {@link BlockConsumerHooks.SpeedTaxConsume} 把该建筑的
 *       {@code efficiency} 压到 0.9，生产进度随之等比变慢（与原版「降效率」同一机制）。</li>
 * </ul>
 *
 * <p><b>为什么用钩子而不是直接改字段</b>：{@code ConsumePower.usage} 与 {@code GenericCrafter.craftTime}
 * 都是<b>方块类型级</b>共享对象，直接改会连带影响该类型所有建筑（含敌方）→ 详见
 * {@link BlockConsumerHooks} 类注释。
 *
 * <p><b>生命周期</b>：钩子式——{@link #apply} 只负责把钩子装进方块（幂等、惰性，只装被命中的方块），
 * 真正的倍率由钩子在<b>每次被引擎查询时</b>回调 {@link BuildingBoostSystem#isActive(Building, String)}
 * 实时读取。故本效果撤销时无需任何还原动作，倍率自动回到 1.0（不残留）。
 */
public class EnergySavingBoost implements BuildingBoostSystem.Boost, BlockConsumerHooks.FactorSource{

    /** 单例：注册进 System 供 Provider 引用。 */
    public static final EnergySavingBoost instance = new EnergySavingBoost();

    static{
        BuildingBoostSystem.register(instance);
        // 注册为倍率来源：钩子每次被引擎查询时取「所有生效来源」倍率之积，故多效果可叠加
        BlockConsumerHooks.register(instance);
    }

    /**
     * 电力倍率档位表（索引 0 = 1 级）：0.80 = −20%、0.60 = −40%、0.30 = −70%。
     * 数值越小越省电，生产速率随之下降（见 {@link #speedScales}）。
     */
    public float[] powerScales = {0.80f, 0.60f, 0.30f};

    /**
     * 生产速率倍率档位表（索引 0 = 1 级）：0.85 = −15%、0.70 = −30%、0.50 = −50%。
     * 「生产效率」在本引擎中的实现即建筑 {@code efficiency}（见类注释）。
     */
    public float[] speedScales = {0.85f, 0.70f, 0.50f};

    @Override
    public String id(){
        return "energy_saving";
    }

    @Override
    public String name(){
        return Core.bundle.get("boost.energy_saving.name", "Energy Saving");
    }

    /** 某档的显示名（含档位号），如「2级节能」。 */
    public String name(int level){
        return Core.bundle.format("boost.energy_saving.levelName", Math.max(1, level));
    }

    // 目标上的显示名：带其实际生效档位，供消息/面板逐建筑展示
    @Override
    public String name(Building target){
        int level = BuildingBoostSystem.levelOf(target, id());
        return level > 0 ? name(level) : name();
    }

    // 目标上的显示描述：只给当前生效档的加成（不列全部三档）
    @Override
    public String description(Building target){
        int level = BuildingBoostSystem.levelOf(target, id());
        return level > 0 ? summary(level) : description();
    }

    @Override
    public String description(){
        // 描述须与实际生效值一致。此效果有 3 档，逐档列出（实际生效档由 System 按提供者决定）
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < powerScales.length; i++){
            if(i > 0) sb.append('\n');
            sb.append(Core.bundle.format("boost.energy_saving.line", name(i + 1), summary(i + 1)));
        }
        return sb.toString();
    }

    /**
     * 某一档的加成摘要（单行、不含档位号），供 Provider 的配置面板显示。
     * 与 {@link #description()} 共用同一份数值与文案，避免两处各写一遍而不同步。
     *
     * @param level 档位（自 1 起；越界夹到最近合法档）
     */
    public String summary(int level){
        // 逐表独立取档（levelValue），不用「一张表算出的下标去索引另一张表」：
        // 三张倍率表都是 public 可变字段，长度不一致时那种写法会抛 AIOOBE。
        return Core.bundle.format("boost.energy_saving.bonus",
            BuildingBoostSystem.percentText(BuildingBoostSystem.levelValue(powerScales, level) - 1f),
            BuildingBoostSystem.percentText(BuildingBoostSystem.levelValue(speedScales, level) - 1f));
    }

    // 目标过滤：耗电工厂。consPower != null 即「该方块类型登记了电力 consumer」（PowerGraph 汇总需求时只认它），
    // GenericCrafter 即工厂类（炉、熔炉、压机、粉碎机等及其子类 HeatCrafter / AttributeCrafter）。
    @Override
    public boolean canTarget(Building target){
        Block block = target.block;
        return block != null && block.consPower != null && block instanceof GenericCrafter;
    }

    // 生效条件：工厂已启用（被玩家关闭时节能无意义，撤销后倍率回到 1.0）。
    // 刻意不用 shouldConsume()/efficiency>0 等运行时状态，避免输出槽塞满时强化图标反复闪烁。
    @Override
    public boolean shouldApply(Building target){
        return target.enabled;
    }

    @Override
    public void apply(Building target){
        // 惰性安装：只给真正被命中的方块类型装钩子，未命中的方块完全不受影响
        BlockConsumerHooks.install(target.block);
    }

    @Override
    public void remove(Building target){
        // 钩子式：倍率由钩子实时读 System 生效状态，撤销后自动回到 1.0，无需还原
    }

    // 与「超频」互斥：两者是同一概念的相反档位，不应同时作用于一台工厂
    @Override
    public boolean conflictsWith(String otherId){
        return OverclockBoost.id_const.equals(otherId);
    }

    @Override
    public BuildingBoostSystem.BoostVisual visual(Building target){
        return EnergySavingVisual.instance;
    }

    // —— FactorSource：仅在本效果于该建筑上生效时被调用，故无需再判生效状态 ——
    // level 由钩子按建筑回查（Provider 持有档位，见 BuildingBoostSystem#levelOf）

    @Override
    public float powerFactor(Building build, int level){
        return BuildingBoostSystem.levelValue(powerScales, level);
    }

    @Override
    public float speedFactor(Building build, int level){
        return BuildingBoostSystem.levelValue(speedScales, level);
    }

    /**
     * 视觉描述：原版「减速」状态图标（slow）——与润滑油的 overclock 图标形成快/慢对照，
     * 底板取电量蓝（{@link Pal#powerLight}）以区别于润滑油的默认绿。
     */
    private static class EnergySavingVisual implements BuildingBoostSystem.BoostVisual{
        static final EnergySavingVisual instance = new EnergySavingVisual();

        @Override
        public arc.graphics.g2d.TextureRegion icon(Building target){
            return BuildingBoostSystem.badgeIcon();   // 统一图标，见 BuildingBoostSystem.badgeIcon()
        }

        @Override
        public Color color(){
            return Pal.powerLight;
        }
    }
}
