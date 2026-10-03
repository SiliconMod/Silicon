package silicon.world.blocks.sandbox;

import mindustry.gen.Building;
import mindustry.world.blocks.sandbox.PowerVoid;

/**
 * PowerSource - A sandbox power generator that produces unlimited power
 * This block overrides the default behavior to disable power production
 * when connected to a PowerVoid block
 */
public class PowerSource extends mindustry.world.blocks.sandbox.PowerSource {

    /**
     * Constructor for PowerSource block
     *
     * @param name The name identifier for this block
     */
    public PowerSource(String name) {
        super(name);
    }

    /**
     * Building class for PowerSource
     * Handles the actual power production logic
     */
    public class PowerSourceBuild extends mindustry.world.blocks.sandbox.PowerSource.PowerSourceBuild {

        /**
         * Gets the power production amount for this building
         * Returns 0 if connected to a PowerVoid, otherwise returns configured power production
         *
         * @return The power production amount in power units per tick
         */
        @Override
        public float getPowerProduction() {
            if (power == null || power.graph == null) return 0f;
            int i = 0;
            // 按有效 size 迭代：Seq 的 items 尾部含空槽（容量>size），for-each 会扫到 null → 有界下标 + null 守卫
            int n = power.graph.all.size;
            for (int k = 0; k < n; k++) {
                Building e = power.graph.all.items[k];
                if (e == null) continue;
                if (e.block instanceof PowerVoid) return 0f;
                if (e.block instanceof PowerSource) {
                    i++;
                }
            }
            // Return power production based on enabled state
            return enabled && i > 0 ? powerProduction / i / 60 : 0f;
        }
    }
}
