package silicon.world.blocks.production;

import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.LiquidStack;
import mindustry.world.blocks.production.GenericCrafter;

import static mindustry.content.Items.pyratite;
import static mindustry.content.Liquids.hydrogen;
import static mindustry.content.Liquids.oil;
import static silicon.content.liquid.Liquids.lubricant;

/**
 * 石油炼化厂：2x2 工厂方块。
 * 配方：25 石油 + 50 氢气（每秒，输入液体按 amount×edelta 每 tick 连续扣除）-> 润滑油 10/s + 硫 0.2/s，
 * 周期 5s（300 ticks），功耗 240/s。
 *
 * <p>产物行为完全沿用原版 GenericCrafter：craft 时经 offload() 产出（含 produced() 生产统计），
 * 无接受者时落入自身内部库存，再由 dumpOutputs() 定时排向近邻；库存满时 shouldConsume() 容量闸门停产，
 * 不会凭空消失也不会白烧原料。本类不覆写 craft()/offload()，避免重复引擎循环。
 *
 * <p>刻意偏离原版（防敌方偷取/灌料，属有意玩法设计，同样作用于中立 derelict 队与沙盒液体源）：
 * <ul>
 *   <li>canDump() / canDumpLiquid()：产物只排向己方建筑（原版 offload() 与 dumpLiquid() 均会调用这两个钩子）。</li>
 *   <li>acceptLiquid()：只接受己方供给的原料液体（原版允许任何队伍直接灌入）。</li>
 * </ul>
 */
public class PetroleumRefinery extends GenericCrafter {

    public PetroleumRefinery(String name) {
        super(name);

        // 配方：25 石油/s + 50 氢气/s -> 10 润滑油/s + 0.2 硫/s，周期 5s（300 ticks），功耗 240/s
        craftTime = 300f;
        // 输入（75/s）与输出（润滑油 10/s）共用同一液体池，容量给足以免输出一堵就触发容量闸门走走停停
        liquidCapacity = 160f;

        outputItem = new ItemStack(pyratite, 1);
        // 注意：GenericCrafter 液体产出按每 tick amount×edelta 直接结算，与 craftTime 无关；10/s => 10/60 每 tick
        outputLiquid = new LiquidStack(lubricant, 10f / 60f);

        consumeLiquid(oil, 25f / 60f);
        consumeLiquid(hydrogen, 50f / 60f);

        consumePower(240f / 60f);
    }

    public class PetroleumRefineryBuild extends GenericCrafterBuild {
        // 多人/跨队保护：物品与液体只允许排向本方建筑，防止敌方管道/传送带旁路取走产物（见类注释，刻意偏离原版）
        @Override
        public boolean canDump(Building to, Item item) {
            return to.team == team && super.canDump(to, item);
        }

        @Override
        public boolean canDumpLiquid(Building to, Liquid liquid) {
            return to.team == team && super.canDumpLiquid(to, liquid);
        }

        // 输入液体同样只接受本方供给，避免敌方向炼化厂灌入原料干扰生产
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return source.team == team && super.acceptLiquid(source, liquid);
        }
    }
}
