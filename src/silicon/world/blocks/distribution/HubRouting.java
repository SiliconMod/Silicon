package silicon.world.blocks.distribution;

import arc.math.geom.Intersector;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Tmp;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import silicon.world.blocks.production.MineConverter;
import mindustry.world.blocks.defense.turrets.ItemTurret;

import static mindustry.Vars.content;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * 中枢网络路由工具（纯静态，无状态）。
 * 职责：连接白名单 / 链接有效性 / 整体占位判定 / BFS 最近供源与核心 / 同网判定。
 */
public class HubRouting {

    private HubRouting() {}

    /** 方块类型 → 是否消耗物品 的缓存（consumesItem 扫描较重，按类型缓存一次）。 */
    private static final ObjectMap<Block, Boolean> itemConsumerCache = new ObjectMap<>();

    // ── 连接白名单 ────────────────────────────────────────────────

    /**
     * 可被中枢连接的建筑类型：
     * 存储（核心/仓库/容器）+ 生产（矿机/工厂/钻头）+ 有物品消耗的建筑（工厂/炮台/单位生产线）。
     * 纯物流方块（传送带、路由器、交叉器等无消耗类型）不参与连接。
     */
    public static boolean shouldConnect(Building other) {
        // 中枢自身 hasItems=false 无物品栏，必须先于 items 守卫判定，
        // 否则中枢之间将无法互连（a0.11.8.0 回归）。
        // 注意：中枢是 Block 子类，须通过 other.block 判型而非直接 instanceof。
        if (other != null && other.block instanceof ItemTransferHub) return true;
        if (other == null || other.items == null) return false;
        Block b = other.block;
        // 存储：核心 / 仓库 / 容器
        if (b instanceof CoreBlock) return true;
        if (b instanceof StorageBlock) {
            // 已与核心合并（linkedCore 由核心侧反向写入）→ 本身就是核心的一部分，
            // 以此为最可靠判据；几何邻近扫描作为合并发生前的快速拦截
            if (((StorageBlock.StorageBuild) other).linkedCore != null) return false;
            for (int x = other.tile.x - 1; x <= other.tile.x + other.block.size; x++) {
                for (int y = other.tile.y - 1; y <= other.tile.y + other.block.size; y++) {
                    Building nb = world.build(x, y);
                    if (nb != null && nb != other && nb.block instanceof CoreBlock) return false;
                }
            }
            return true;
        }
        // 生产 / 消耗白名单
        if (b instanceof GenericCrafter || b instanceof MineConverter || b instanceof Drill) return true;
        if (b instanceof ItemTurret) return true;
        // 兵工厂 / 重构工厂等单位生产建筑
        if (b instanceof mindustry.world.blocks.units.UnitFactory) return true;
        if (b instanceof mindustry.world.blocks.units.Reconstructor) return true;
        if (b instanceof ItemTransferHub) return true;
        // 泛化：注册了物品消耗的方块自动接入；传送带等纯物流方块（无消耗）在此被排除
        return consumesItems(b);
    }

    /**
     * 方块级可连判定（shouldConnect(Building) 的放置预览版）：
     * 在还没有建筑的幽灵/计划上提前判断该方块是否属于中枢自动接入白名单。
     * 判定口径与建筑版一致（存储 + 生产 + 物品消耗泛化），仅少了
     * linkedCore 等运行时状态——那些由建造完成事件按实际情况裁决。
     */
    public static boolean shouldConnectBlock(Block b) {
        if (b instanceof ItemTransferHub) return true;
        if (b == null || !b.hasItems) return false;
        if (b instanceof CoreBlock || b instanceof StorageBlock) return true;
        if (b instanceof GenericCrafter || b instanceof MineConverter || b instanceof Drill) return true;
        if (b instanceof ItemTurret) return true;
        if (b instanceof mindustry.world.blocks.units.UnitFactory
            || b instanceof mindustry.world.blocks.units.Reconstructor) return true;
        return consumesItems(b);
    }

    /** 方块类型是否有物品消耗（itemFilter 任一为真），按 Block 缓存。 */
    public static boolean consumesItems(Block b) {
        Boolean cached = itemConsumerCache.get(b);
        if (cached != null) return cached;
        boolean found = false;
        if (b.hasItems) {
            for (int i = 0; i < content.items().size; i++) {
                Item it = content.item(i);
                if (it != null && b.consumesItem(it)) {
                    found = true;
                    break;
                }
            }
        }
        itemConsumerCache.put(b, found);
        return found;
    }

    /** 整体占位检测：中枢范围圆与目标建筑矩形相交即有效（非中心点距离）。 */
    public static boolean linkValid(Building tile, Building link) {
        if (tile == link || link == null) return false;
        if (!(tile.block instanceof ItemTransferHub)) return false;
        if (tile.team != link.team) return false;
        if (!shouldConnect(link)) return false;
        float range = ((ItemTransferHub) tile.block).connectionRange * tilesize;
        return Intersector.overlaps(Tmp.cr1.set(tile.x, tile.y, range),
            Tmp.r1.setCentered(link.x, link.y, link.block.size * tilesize, link.block.size * tilesize));
    }

    // ── 角色判定 ────────────────────────────────────────────────

    /** 需喂料的消费者：炮台(0) > 工厂(1)；仓储为 2。 */
    public static int consumerPriority(Building b) {
        if (b instanceof ItemTurret.ItemTurretBuild) return 0;
        if (isFactory(b)) return 1;
        return 2;
    }

    public static boolean isFactory(Building b) {
        // 泛化：任何注册了物品消耗的建筑（含超速投影器/穹顶等【可选增幅消耗】）
        // 都是合法供料目标——itemFilter 由 ConsumeItems.apply 在 init 时统一写入
        if (consumesItems(b.block)) return true;
        return b instanceof GenericCrafter.GenericCrafterBuild
            || b instanceof MineConverter.MineConverterBuild
            || b instanceof Drill.DrillBuild
            || b instanceof ItemTurret.ItemTurretBuild
            || b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild
            || b instanceof mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
    }

    /** 可被拉取的产出源：矿机 + 工厂。 */
    public static boolean isProducer(Building b) {
        return b instanceof Drill.DrillBuild
            || b instanceof GenericCrafter.GenericCrafterBuild
            || b instanceof MineConverter.MineConverterBuild;
    }
}