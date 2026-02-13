package easierMindustry.world.blocks;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.core.UI;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

/**
 * PowerProtector - A block that protects the power network when power drops to 0,
 * locks power >= 1, exits protection after 5 minutes or after 30 seconds of
 * continuous power growth, records spent power, and enters recovery mode after exiting.
 */
public class PowerProtector extends PowerGenerator {
    public float protectionTime = 5 * 60 * 60f; // 5 minutes in ticks (60 ticks per second)
    public float exitGrowthTime = 30 * 60f; // 30 seconds in ticks for continuous growth
    public float secondRecoveryRate = 0.001f; // 0.1% per second for recovery
    public float minProtectPower = 1f; // Minimum protected power level
    public float secondsTimer = 0;
    public float warmupSpeed = 0.1f;

    /**
     * Constructor for PowerProtector
     * Sets up basic properties for the block
     */
    public PowerProtector(String name) {
        super(name);
        // Basic properties setup
        update = true;           // Needs updating
        solid = true;            // Is solid
        consumesPower = true;
        outputsPower = true;     // Doesn't output power
        size = 2;                // Size of the block
        health = 600;            // Health points
        envEnabled = Env.any;    // Effective in any environment
        configurable = false;    // Not configurable
        saveConfig = false;      // Don't save configuration
        displayFlow = false;     // Don't display flow
        drawArrow = false;  // Don't draw arrow
        consumePowerDynamic((entity) -> ((PowerProtectorBuild) entity).tickRPower).optional(false, false);
//        consPower.update = true;
    }

    /**
     * Sets up statistics for the block
     */
    @Override
    public void setStats() {
        super.setStats();

        stats.add(Stat.powerUse, "Protects power network when below 0");
        stats.add(Stat.repairTime, protectionTime / (60 * 60), StatUnit.minutes); // Protection time in minutes
    }

    /**
     * Sets up status bars for the block
     */
    @Override
    public void setBars() {
        super.setBars();
        addBar("power", (PowerProtectorBuild entity) -> new Bar(() ->
                Core.bundle.format("bar.power1", entity.status == 1 ?
                        Strings.fixed(entity.getPowerProduction() * 60 * entity.timeScale(), 1) :
                        Strings.fixed(entity.tickRPower * 60 * entity.timeScale() * entity.efficiency, 1)),
                () -> Pal.powerBar,
                () -> entity.efficiency));

        // Add spent power bar
        addBar("spent-power", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.format("bar.spent-power", UI.formatAmount((long) (entity.totalSpentPower))),
                () -> Pal.powerBar,
                () -> entity.totalSpentPower > 0 ?
                        1f : 0f
        ));

        // Add protection status bar
        addBar("protection", (PowerProtectorBuild entity) -> new Bar(
                () -> entity.isInRecoveryMode() ? Core.bundle.get("block.easier-mindustry-power-protector.recovery") :
                        entity.error ? Core.bundle.get("block.easier-mindustry-power-protector.error") :
                                entity.isInProtectionMode() ? Core.bundle.get("block.easier-mindustry-power-protector.protection") :
                                        Core.bundle.get("block.easier-mindustry-power-protector.normal"),
                () -> entity.isInRecoveryMode() ? Color.orange : entity.error ? Color.red :
                        entity.isInProtectionMode() ? Color.green : Color.white,
                () -> 1f)
        );
        addBar("1", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(entity.block.consPower.requestedPower(entity)), () -> Color.red, () -> 1f));
        addBar("2", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(entity.shouldConsume()), () -> Color.white, () -> 1f));
        addBar("3", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(entity.efficiency), () -> Color.white, () -> 1f));
        addBar("4", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(entity.tickRPower + entity.tickPPower), () -> Color.white, () -> 1f));
        addBar("5", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(entity.block.consPower.usage), () -> Color.white, () -> 1f));
        addBar("6", (PowerProtectorBuild entity) ->
                new Bar(() -> String.valueOf(
                        Mathf.zero(entity.block.consPower.requestedPower(entity)) ? 0f : entity.block.consPower.usage * (entity.shouldConsume() ? entity.efficiency * entity.timeScale() : 0f) * 60f)
                        , () -> Color.white, () -> 1f));
    }

    @Override
    public boolean canBreak(Tile tile) {
        return ((PowerProtectorBuild) tile.build).status == 0;
    }

    /**
     * Internal building class for PowerProtector
     */
    public class PowerProtectorBuild extends GeneratorBuild {
        private byte status = 0;
        private boolean error = false;
        //        private boolean inProtection = false;
//        private boolean inRecovery = false;
        private float protectionTimer = 0f;
        /**
         * Timer for continuous power growth
         */
        private float growthTimer = 0f;
        /**
         * Total power that has been spent/deducted
         */
        private double totalSpentPower = 0f;
        //        private float currentSpentPower = 0f;// Current amount of spent power during recovery
        private float tickPPower = 0f;
        private float lastTickPPower = 0f;
        private double rPowerPrincipal = 0f;
        private float tickRPower = 0f;
        private float lastTickRPower = 0f;

        /**
         * Updates the tile every frame
         */
        @Override
        public void updateTile() {
            if (!enabled) return;
            secondsTimer += Time.delta / 60f;
            float powerStored = power.graph.getBatteryStored();
            float powerCapacity = power.graph.getBatteryCapacity();

            for (Building e : power.graph.all) {
                if (e == this) {
                    error = true;
                    return;
                }
                error = false;
            }

            // Check if we should enter protection mode (when power is 0 or negative)
            if (status == 0 && powerStored <= Mathf.FLOAT_ROUNDING_ERROR &&
                    power.graph.all.items.length > 0 && power.graph.getPowerBalance() < 0f && powerCapacity > 0 && !error) {
                enterProtectionMode();
            }

            // Handle protection mode
            if (status == 1) {
                handleProtectionMode(powerStored);

                if (protectionTimer >= protectionTime || growthTimer >= exitGrowthTime
                        || totalSpentPower >= Float.MAX_VALUE || power.graph.all.items.length == 0 || powerCapacity == 0 || error) {
                    exitProtectionMode(); // Exit protection mode
                }
            } else if (status == -1) {
                handleRecoveryMode();

                // Exit recovery mode when time is up or all spent power is consumed
                if (totalSpentPower <= 0) {
                    status = 0;
                    totalSpentPower = 0f;
                    tickRPower = 0f;
                }
            }

            // Track power changes for exit condition
//            if (inProtectionMode && powerStored >= power.graph.getLastPowerStored()) {
//                powerGrowthTimer += Time.delta;
//            }
//            } else if (inProtectionMode) {
//                powerGrowthTimer = 0f; // Reset if power isn't growing
//            }
        }

        /**
         * Enters protection mode
         */
        private void enterProtectionMode() {
            status = 1;
            protectionTimer = 0f;
            growthTimer = 0f;
            // Record current spent power as recovery baseline
//            currentSpentPower = totalSpentPower;
            Log.info("Power Protector entered protection mode.");
        }

//        public boolean shouldConsumePower() {
//            return shouldConsumePower = inRecoveryMode;
//        }

        /**
         * Handles protection mode logic
         */
        private void handleProtectionMode(float powerStored) {
            lastTickPPower = tickPPower;
            if (status == 1 && power.graph.getPowerBalance() > 0f) {
                growthTimer += Time.delta;
            } else {
                protectionTimer += Time.delta;
                growthTimer = 0f;
            }

//            if (powerStored <= Math.min(pPowerStored, power.graph.getBatteryCapacity())) {
            tickPPower = Math.max(-(power.graph.getPowerBalance() - lastTickPPower) - powerStored, Mathf.FLOAT_ROUNDING_ERROR);

            totalSpentPower = Mathf.clamp(tickPPower + totalSpentPower, 0f, Float.MAX_VALUE);
//            } else {
//                tickPPower = 0;
//            }
//            if (secondsTimer >= 1f) {
//                secondsTimer -= 1f;
//                if (power.graph.getPowerBalance() < pPowerStored * 2) {
//                    pPowerStored = Mathf.clamp(pPowerStored * 2, minProtectPower, Float.MAX_VALUE);
//                } else if (power.graph.getPowerBalance() > tickPPower / 2 || powerStored > pPowerStored) {
//                    pPowerStored = Mathf.clamp(pPowerStored / 2, minProtectPower, Float.MAX_VALUE);
//                }
//            }
            // Exit conditions for protection mode:
            // 1. After 5 minutes have passed
            // 2. After 30 seconds of continuous power growth

        }

        /**
         * Handles recovery mode logic
         */
        private void handleRecoveryMode() {

            // In recovery mode, consume spent power using equal principal method at 1% per second
            if (totalSpentPower > 0) {
                // Calculate equal principal amount to consume per second
                updateRTick();
            }
        }

        private void exitProtectionMode() {
            growthTimer = 0f;
            tickPPower = 0f;
            // Enter recovery mode for the same duration as protection time
//            inRecoveryMode = true;
//            recoveryTimer = 0f;
//            recoveryPeriod = protectionTimer; // Same duration as a protection period


            status = -1;
            // The Recovery period is the same as the protection period
            /*
              Recovery period for power growth
             */
            // How long the recovery period should last
            float rTime = protectionTimer;
            protectionTimer = 0f;
            rPowerPrincipal = totalSpentPower / rTime; // Convert ticks to seconds
        }

        /**
         * Calculates the amount of power to recover per tick
         */
        private void updateRTick() {
            if (status != -1) {
                tickRPower = 0f;
                return;
            }
            lastTickRPower = tickRPower; // Store the last tick's recovery amount
            // Reduce the current spent power by the recovery amount
            totalSpentPower -= lastTickRPower * efficiency;
            float powerStored = power.graph.getBatteryStored();
            float powerChanged = power.graph.getPowerBalance();

            // Also add interest at 1% per second of remaining spent power
            double interestPerSecond = totalSpentPower * secondRecoveryRate / 60;// 0.1% per second

            double interestPerTick = interestPerSecond / 60;
            totalSpentPower += interestPerTick;
            double dP = rPowerPrincipal + interestPerTick;
            if (dP < Float.MAX_VALUE) {
//                tickRPower = Mathf.lerp(lastTickRPower, (float) Mathf.clamp(powerStored / 2 + powerChanged + lastTickRPower,
//                        Math.max(Mathf.FLOAT_ROUNDING_ERROR, dP), Float.MAX_VALUE), warmup());
                tickRPower = (float) Mathf.clamp(powerStored / 2 + powerChanged + lastTickRPower,
                        Math.max(0f, dP), Float.MAX_VALUE);

            } else {
//                tickRPower = Mathf.lerp(lastTickRPower, Float.MAX_VALUE, warmup());
                tickRPower = Float.MAX_VALUE;
            }
        }

        /**
         * Checks if the protector is in protection mode
         */
        public boolean isInProtectionMode() {
            return status == 1;
        }

        /**
         * Checks if the protector is in recovery mode
         */
        public boolean isInRecoveryMode() {
            return status == -1;
        }


        @Override
        public float getPowerProduction() {
            // Return current power generation
            return tickPPower;
        }

        @Override
        public float warmup() {
            return warmupSpeed;
        }

        @Override
        public byte version() {
            return 7;
        }

        /**
         * Provides sensor access to power network data
         */
        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.powerNetStored) return power.graph.getBatteryStored();
            if (sensor == LAccess.powerNetCapacity) return power.graph.getBatteryCapacity();
            if (sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
            return super.sense(sensor);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.b(status);
            write.f(protectionTimer);
            write.f(growthTimer);
            write.d(totalSpentPower);
            write.f(tickPPower);
            write.f(lastTickPPower);
            write.d(rPowerPrincipal);
            write.f(tickRPower);
            write.f(lastTickRPower);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            status = read.b();
            protectionTimer = read.f();
            growthTimer = read.f();
            totalSpentPower = read.d();
            tickPPower = read.f();
            lastTickPPower = read.f();
            rPowerPrincipal = read.d();
            tickRPower = read.f();
            lastTickRPower = read.f();
        }


//        @Override
//        public boolean shouldConsume() {
//            return true;
//        }
//
//        @Override
//        public boolean consumeTriggerValid() {
//            return true;
//        }
//
//        @Override
//        public boolean canConsume() {
//            return true;
//        }
//
//        public boolean productionValid() {
//            return true;
//        }
    }
}