package silicon.world.blocks.container;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import mindustry.gen.Building;
import mindustry.type.Liquid;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.blocks.storage.StorageBlock;
import silicon.util.SiliconLog;

/**
 * 两用存储方块：可存储物品与单种液体。
 * 继承 StorageBlock，放置于核心旁可与核心连接并为核心扩容（同原版仓库）。
 * 同时通过 hasLiquids + dumpLiquid 提供液体存储与导管抽取能力。
 * 支持 bottomRegion/liquidRegion/topRegion 三层贴图，呈现与原版流体储罐一致的流动+颜色特效。
 */
@SuppressWarnings("SpellCheckingInspection")
public class DualPurposeStorager extends StorageBlock {

    // ========== 配置参数 ==========
    /** 液体边缘内缩间距（绘制流动液面时用，与原版 LiquidRouter 一致为 0） */
    public float liquidPadding = 0f;
    /** 储存罐底座贴图 */
    public TextureRegion bottomRegion;
    /** 流动液面贴图 */
    public TextureRegion liquidRegion;
    /** 储存罐顶盖贴图（中心挖空，露出液体） */
    public TextureRegion topRegion;

    public DualPurposeStorager(String name) {
        super(name);
        this.coreMerge = true;
        this.itemCapacity = 500;
        this.liquidCapacity = 900f;
        this.hasLiquids = true;
        this.outputsLiquid = true;
        this.update = true;
        this.displayFlow = false;
        // 关键：StorageBlock 默认 drawCached=true 且 drawDynamic=false，会导致 draw() 只在区块缓存时被调用，
        // 液体动态效果不会每帧重绘。而 drawCached 与 drawDynamic 同时为 true 时，方块会被画进缓存缓冲区
        // 又每帧再画一次，半透明液体双重混合导致过饱和（周围放/拆方块触发重新缓存时尤为明显）。
        // 因此对齐原版液体方块默认值：drawCached=false + drawDynamic=true，仅每帧动态绘制一次。
        this.drawCached = false;
        this.drawDynamic = true;
    }

    @Override
    public void load() {
        super.load();
        // 按 mod 约定加载储罐三贴图；缺失时回退到主贴图并打印警告
        this.bottomRegion = Core.atlas.find(name + "-bottom");
        this.liquidRegion = Core.atlas.find(name + "-liquid");
        this.topRegion = Core.atlas.find(name + "-top");
        if (!bottomRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -bottom texture, fallback to region", name);
            bottomRegion = region;
        }
        if (!liquidRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -liquid texture, fallback to region", name);
            liquidRegion = region;
        }
        if (!topRegion.found()) {
            SiliconLog.warn("DualPurposeStorager '{}' missing -top texture, fallback to region", name);
            topRegion = region;
        }
    }

    // ============================================================
    // 自定义建筑类 - 继承 StorageBuild 以便被原版核心识别并扩容
    // ============================================================
    public class DualPurposeStoragerBuild extends StorageBuild {

        private static final float LIQUID_THRESHOLD = 0.001f;

        /**
         * 单液体类型接受规则（纯逻辑，可独立测试，不依赖 Mindustry 运行时）。
         *
         * @param currentAmount 当前存量
         * @param currentType   当前液体类型标识（null 表示无液体）
         * @param incomingType  待注入液体类型标识
         * @param threshold     空判定阈值
         * @return 是否接受该类型（类型层面）
         */
        static boolean canAcceptLiquidType(float currentAmount, Object currentType, Object incomingType, float threshold) {
            if (incomingType == null) return false;
            // 完全为空时接受任意液体
            if (currentAmount <= threshold) return true;
            // 有存量：必须类型一致
            return currentType == incomingType;
        }

        /**
         * 注入时防混液检查（纯逻辑）。有存量且类型不一致时拒绝。
         */
        static boolean shouldRejectLiquid(float currentAmount, Object currentType, Object incomingType, float threshold) {
            return currentAmount > threshold && currentType != incomingType;
        }

        // ================================================================
        // 绘制：复刻原版液体储罐的三层绘制，用 drawTiledFrames 生成流动条纹+颜色效果
        // ================================================================

        @Override
        public void draw() {
            Draw.rect(DualPurposeStorager.this.bottomRegion, x, y);

            // 与原版 LiquidRouter 完全一致：drawTiledFrames 用 fluidFrames 动画帧画出条纹流动液面，
            // alpha 直接用填充比例（液体多则浓、少则淡），不额外做保底，保证与原版渐变一致。
            if (liquids.currentAmount() > LIQUID_THRESHOLD) {
                Liquid liq = liquids.current();
                if (liq != null) {
                    LiquidBlock.drawTiledFrames(size, x, y, DualPurposeStorager.this.liquidPadding, liq,
                            liquids.currentAmount() / liquidCapacity);
                }
            }

            Draw.color(Color.white);
            Draw.rect(DualPurposeStorager.this.topRegion, x, y);
        }

        // ========== 辅助方法（全部基于 liquids 模块，自动序列化/同步） ==========

        public boolean hasLiquid() {
            return liquids.current() != null && liquids.currentAmount() > LIQUID_THRESHOLD;
        }

        // ================================================================
        // 液体输入（只接受一种液体，由 liquids 模块管理）
        // ================================================================

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (source == this || liquid == null) return false;
            // 严格单液体约束（类型层面，复用纯逻辑规则）+ 容量检查
            if (!canAcceptLiquidType(liquids.currentAmount(), liquids.current(), liquid, LIQUID_THRESHOLD)) return false;
            return liquids.get(liquid) < liquidCapacity - LIQUID_THRESHOLD;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (liquid == null || amount <= 0) return;
            // 防混液：有存量且类型不一致则拒绝
            if (shouldRejectLiquid(liquids.currentAmount(), liquids.current(), liquid, LIQUID_THRESHOLD)) return;

            float remaining = liquidCapacity - liquids.get(liquid);
            float actualAmount = Math.min(amount, remaining);
            if (actualAmount > 0) {
                super.handleLiquid(source, liquid, actualAmount);
            }
        }

        // ================================================================
        // 核心：主动输出液体到相邻导管
        // ================================================================

        @Override
        public void updateTile() {
            super.updateTile();

            // 标准抽取接口：dumpLiquid 内部用 proximity 遍历真实相邻建筑，任意尺寸均可输出到导管
            if (hasLiquid()) {
                dumpLiquid(liquids.current());
            }
        }

        
    }
}
