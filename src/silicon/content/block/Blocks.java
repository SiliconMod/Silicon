package silicon.content.block;

import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;
import silicon.world.blocks.container.DualPurposeStorager;
import silicon.world.blocks.defense.LubricantInjector;
import silicon.world.blocks.defense.Switch;
import silicon.world.blocks.distribution.ItemTransferHub;
import silicon.world.blocks.effect.EfficiencyControlTower;
import silicon.world.blocks.distribution.Junction;
import silicon.world.blocks.distribution.UniversalJunction;
import silicon.world.blocks.power.GeneratorPump;
import silicon.world.blocks.power.PowerProtector;
import silicon.world.blocks.power.RollGenerator;
import silicon.world.blocks.production.MineConverter;
import silicon.world.blocks.production.PetroleumRefinery;
import silicon.world.blocks.sandbox.MessageTest;
import silicon.world.blocks.sandbox.PowerSource;
import silicon.world.blocks.satellite.SatelliteConsole;
import silicon.world.blocks.satellite.SatelliteLauncher;
import silicon.world.blocks.signal.DimensionAnchor;
import silicon.world.blocks.signal.SignalDetector;
import silicon.world.blocks.signal.SignalJammer;
import silicon.world.blocks.signal.SignalRelay;
import silicon.world.blocks.signal.SignalSource;

import static mindustry.type.ItemStack.with;

public class Blocks {
    public static Block powerGeneratorPump, dualPurposeJunction, dualPurposeStorager,
            rollGenerator, powerProtector, powerSource, mineConverter, theSwitch, itemTransferHub,
            dimensionAnchor, signalSource, universalJunction, signalRelay, messageTest, petroleumRefinery,
            lubricantInjector, efficiencyControlTower,
            signalJammer, satelliteLauncher, satelliteConsole, signalDetector;

    public static void load() {
        powerGeneratorPump = new GeneratorPump("power-generator-pump") {{
            hasItems = false;
            liquidPressure = 1f;
            pumpAmount = 0.22f;
            liquidCapacity = 90f;
            canPumpLiquids.add(Liquids.water);
            powerConsumption = 43f / 60;
            consumeLiquid(Liquids.water, 12.5f / 60).boost();
            powerProduction = 345f / 60;
            size = 3;
            destructible = true;
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 60, Items.lead, 30, Items.metaglass, 15, Items.graphite, 40,
                            Items.titanium, 45, Items.thorium, 6, Items.silicon, 40));
            alwaysUnlocked = true;
        }};
        dualPurposeJunction = new Junction("dual-purpose-junction") {{
            requirements(Category.liquid, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 2, Items.metaglass, 4, Items.copper, 1));
            alwaysUnlocked = true;
        }};
        dualPurposeStorager = new DualPurposeStorager("dual-purpose-storager") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.thorium, 100, Items.metaglass, 30, Items.titanium, 45, Items.plastanium, 10));
            alwaysUnlocked = true;
            size = 3;
            health = 600;
        }};

        // Compound interest generator - generates power based on 1% of existing stored power
        rollGenerator = new RollGenerator("roll-generator") {{
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 40, Items.lead, 24, Items.graphite, 20,
                            Items.silicon, 16, Items.thorium, 16, Items.plastanium, 10));
            alwaysUnlocked = true;
            size = 1;
            powerStoredProductionPercentage = 0.001f;
            powerChangedProductionPercentage = 0.005f;
        }};

        // Power protector - protects power network when below 0 and recovers spent power
        powerProtector = new PowerProtector("power-protector") {{
            requirements(Category.power, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 150, Items.lead, 100, Items.graphite, 80,
                            Items.silicon, 70, Items.thorium, 50, Items.plastanium, 40, Items.phaseFabric, 20));
            alwaysUnlocked = true;
            size = 2;
            health = 600;
        }};
        powerSource = new PowerSource("power-source") {{
            requirements(Category.power, BuildVisibility.sandboxOnly, with());
            alwaysUnlocked = true;
            size = 1;
            health = 600;
            powerProduction = Float.MAX_VALUE / 2;
        }};
        mineConverter = new MineConverter("mine-converter") {{
            requirements(Category.crafting, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 200, Items.silicon, 250, Items.thorium, 250, Items.plastanium, 100));
            consumePower(200f / 60);
            size = 3;
            frame = 18;
            frameTime = 8;
        }};
        theSwitch = new Switch("switch") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.graphite, 100, Items.silicon, 100, Items.thorium, 100, Items.plastanium, 100));
            alwaysUnlocked = true;
            update = true;
            solid = true;
        }};
        itemTransferHub = new ItemTransferHub("item-transfer-hub") {{
            requirements(Category.distribution, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 80, Items.lead, 40, Items.metaglass, 20,
                            Items.graphite, 30, Items.silicon, 25, Items.titanium, 15));
            alwaysUnlocked = true;
            size = 3;
        }};
        // 已废弃的维度锚点存根：不能删——存档头按内容名解析，名字找不到时引擎会把该位置的
        // 方块回退成 contentMap[0]（一个原版方块），比留着存根更糟。隐藏于建造菜单，无功能。
        dimensionAnchor = new DimensionAnchor("dimension-anchor") {{
            health = 600;
        }};
        signalSource = new SignalSource("signal-source") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 20, Items.lead, 10, Items.silicon, 15));
            alwaysUnlocked = true;
            size = 1;
            health = 120;
        }};
        universalJunction = new UniversalJunction("universal-junction") {{
            requirements(Category.distribution, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 15, Items.lead, 10, Items.graphite, 8, Items.silicon, 5));
            alwaysUnlocked = true;
            size = 1;
        }};
        // 信号中继器注册在最后：保证旧存档（含维度锚点/旧信号源/万能枢纽）的方块 ID 不被后续新增方块打乱
        signalRelay = new SignalRelay("signal-relay") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 15, Items.lead, 10, Items.silicon, 12));
            alwaysUnlocked = true;
            size = 1;
            health = 100;
        }};
        signalJammer = new SignalJammer("signal-jammer") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 30, Items.lead, 20, Items.silicon, 25, Items.thorium, 10));
            alwaysUnlocked = true;
            size = 1;
            health = 120;
        }};
        satelliteLauncher = new SatelliteLauncher("satellite-launcher") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 300, Items.lead, 200, Items.silicon, 250, Items.thorium, 100, Items.titanium, 150));
            alwaysUnlocked = true;
            size = 3;
            health = 1200;
        }};
        satelliteConsole = new SatelliteConsole("satellite-console") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 120, Items.lead, 80, Items.silicon, 100, Items.thorium, 40));
            alwaysUnlocked = true;
            size = 3;
            health = 400;
        }};
        // “消息测试”调试方块：位置与上游 test（PR #58）保持一致（satelliteConsole 之后），
        // 便于后续同步上游时减少改动。注意 Mindustry 存档按**内容名**映射（ContentLoader 头表），
        // 注册顺序本身不决定旧存档兼容；顺序真正影响的是各内容的 content id。
        messageTest = new MessageTest("message-test") {{
            requirements(Category.effect, BuildVisibility.sandboxOnly, with());
            alwaysUnlocked = true;
            size = 1;
            health = 60;
        }};
        // 石油炼化厂：2x2 工厂方块。配方（每秒）：25 石油 + 50 氢气 -> 10 润滑油 + 0.2 硫（周期 5s，功耗 240/s）
        petroleumRefinery = new PetroleumRefinery("petroleum-refinery") {{
            requirements(Category.crafting, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 100, Items.lead, 80, Items.graphite, 60, Items.silicon, 60));
            alwaysUnlocked = true;
            size = 2;
            health = 320;
        }};
        // 润滑油注入器：2x2 支援方块，耗润滑油使紧贴的己方炮塔攻速 +20%（与强化液加法叠加，攻击时按炮塔数消耗 5/s）、
        // 存油期间转角速率 ×2（+100%，自动索敌与玩家控制同等生效）。强化内容由 BuildingBoostSystem 驱动：
        // 效果封装为 Boost（silicon.util.boosts），资格/队伍/互斥/撤销由 System 兜底；规则名单
        // （BuildingBoostSystem.boostableTypes）默认仅放行炮塔，应用前先查队伍+名单，非炮塔方块不会被套用炮塔专用逻辑
        lubricantInjector = new LubricantInjector("lubricant-injector") {{
            requirements(Category.turret, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 120, Items.lead, 80, Items.silicon, 40));
            alwaysUnlocked = true;
            size = 2;
            health = 220;
        }};
        // 效率控制塔：3x3 支援方块，以塔为中心 15x15 格区域内，耗电的己方工厂附上「节能」强化
        // （电力 -20%、生产速度 -10%，两者由 MJ 的电力请求/建筑效率两个独立入口实现，互不干扰）。
        // 配置面板滑块切换「关闭 / 节能」，走标准 config 链路（联网全端一致 + 存盘持久化）。
        // 区域内可含多台塔：System 按「同一效果至多一份」处理，不会成倍叠加。
        efficiencyControlTower = new EfficiencyControlTower("efficiency-control-tower") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 200, Items.lead, 150, Items.silicon, 80));
            alwaysUnlocked = true;
            size = 3;
            health = 300;
        }};
        // 信号检测器：新方块一律追加在末尾，与上游注册序保持一致（纯测量设备，无游戏逻辑）。
        // 顺序不决定存档兼容（存档按内容名映射），但插队会移动其后所有方块的 content id。
        signalDetector = new SignalDetector("signal-detector") {{
            requirements(Category.effect, BuildVisibility.shown,
                    ItemStack.with(Items.copper, 5, Items.silicon, 4));
            alwaysUnlocked = true;
            size = 1;
            health = 60;
        }};

    }
}
