package silicon.world.blocks.power;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.*;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.core.Renderer;
import mindustry.core.UI;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.io.TypeIO;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import silicon.util.SiliconLog;

import java.util.concurrent.atomic.AtomicBoolean;

import static mindustry.Vars.world;
import static mindustry.content.Blocks.powerVoid;
import static silicon.Vars.*;

/**
 * PowerProtector - A block that protects the power network when power drops to 0,
 * locks power >= 1, exits protection after 5 minutes or after 30 seconds of
 * continuous power growth, records spent power, and enters recovery mode after exiting.
 */
public class PowerProtector extends PowerGenerator {
    /**
     * Protection time in ticks (5 minutes)
     */
    public float protectionTime = 5 * 60 * 60f;
    /**
     * Time required for continuous power growth to exit protection (30 seconds)
     */
    public float exitGrowthTime = 30 * 60f;
    /**
     * Recovery rate per second (0.1%)
     */
    public float secondRecoveryRate = 0.001f;
    /**
     * Speed of warmup animation transition
     */
    public float warmupSpeed = 0.1f;

    private static final Seq<Building> emptySeq = new Seq<>(0);

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
        outputsPower = true;     // Outputs power
        size = 2;                // Size of the block
        health = 600;            // Health points
        envEnabled = Env.any;    // Effective in any environment
        configurable = false;    // Not configurable
        saveConfig = false;      // Don't save configuration
        displayFlow = false;     // Don't display flow
        drawArrow = false;  // Don't draw arrow
        consumePowerDynamic((entity) -> ((PowerProtectorBuild) entity).tickRPower).optional(false, false);
    }

    /**
     * Sets up statistics for the block
     */
    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.repairTime, protectionTime / (60 * 60), StatUnit.minutes);
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
                () -> entity.productionEfficiency));

        addBar("spent-power", (PowerProtectorBuild entity) -> new Bar(
                () -> Core.bundle.format("bar.spent-power", UI.formatAmount((long) (entity.totalSpentPower))),
                () -> Pal.powerBar,
                () -> entity.totalSpentPower > 0 ? 1f : 0f
        ));

        addBar("protection", (PowerProtectorBuild entity) -> new Bar(
                () -> entity.isInRecoveryMode() ? Core.bundle.get("block.silicon-power-protector.recovery") :
                        entity.isError() ? Core.bundle.get("block.silicon-power-protector.error") :
                                entity.isInProtectionMode() ? Core.bundle.get("block.silicon-power-protector.protection") :
                                        Core.bundle.get("block.silicon-power-protector.normal"),
                () -> entity.isInRecoveryMode() ? Color.orange : entity.isError() ? Color.red :
                        entity.isInProtectionMode() ? Color.green : Color.white,
                () -> 1f)
        );
    }

    @Override
    public boolean canBreak(Tile tile) {
        return tile.build instanceof PowerProtectorBuild b && b.status == 0;
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        AtomicBoolean canPlace = new AtomicBoolean(true);
        PowerNode.getNodeLinks(tile, this, team, other -> {
            for (Building e : other.power.graph.consumers.items) {
                if (e instanceof PowerProtectorBuild) {
                    canPlace.set(false);
                    return;
                }
            }
        });
        BeamNode.getNodeLinks(tile, this, team, other -> {
            for (Building e : other.power.graph.consumers.items) {
                if (e instanceof PowerProtectorBuild) {
                    canPlace.set(false);
                    return;
                }
            }
        });
        for (Point2 p : Edges.getEdges(size)) {
            Tile t = tile.nearby(p);
            if (t != null && t.build != null && t.build.power != null && canPlace.get()) {
                for (Building e : t.build.power.graph.consumers.items) {
                    if (e instanceof PowerProtectorBuild) {
                        canPlace.set(false);
                    }
                }
            }
        }

        return canPlace.get();
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
    }

    /**
     * Internal building class for PowerProtector
     */
    public class PowerProtectorBuild extends GeneratorBuild {
        /**
         * Interval timer for periodic operations
         */
        private final Interval interval = new Interval();
        private byte status = 0;
        private float protectionTimer = 0f;
        private float growthTimer = 0f;
        private double totalSpentPower = 0f;
        private float tickPPower = 0f;
        private float lastTickPPower = 0f;
        private double rPowerPrincipal = 0f;
        private float tickRPower = 0f;
        private float lastTickRPower = 0f;
        private boolean error = false;
        private Building node = null;

        /**
         * Updates the tile every frame
         */
        @Override
        public void updateTile() {
            {
                if (!enabled && status == 0) return;
                if (!enabled && status != 0) { status = 0; }
                for (Building b : team.data().buildingTypes.get(block, emptySeq)) {
                    if (power.graph.all.contains(b) && b != self()) {
                        error = true;
                        return;
                    }
                }
                error = false;
                for (Building b : team.data().buildingTypes.get(powerVoid, emptySeq)) {
                    if (b.block instanceof PowerVoid && power.graph.all.contains(b)) return;
                }
            }

            // Check if we should enter protection mode (when power is 0 or negative)
            if (status == 0 && powerStored.get(self()) <= Mathf.FLOAT_ROUNDING_ERROR &&
                    power.graph.all.items.length > 0 && powerChanged.get(self()) < 0f && powerCapacity.get(self()) > 0 && !error) {
                enterProtectionMode();
            }

            // Handle protection mode
            if (status == 1) {
                handleProtectionMode();

                // Exit conditions for protection mode:
                // 1. After 5 minutes have passed
                // 2. After 30 seconds of continuous power growth
                if (protectionTimer >= protectionTime || growthTimer >= exitGrowthTime
                        || totalSpentPower >= Float.MAX_VALUE || power.graph.all.items.length == 0
                        || powerCapacity.get(self()) == 0 || error || Double.isNaN(totalSpentPower)) {
                    exitProtectionMode(); // Exit protection mode
                }
            } else if (status == -1) {
                handleRecoveryMode();
            }
        }

        /**
         * Enters protection mode
         */
        private void enterProtectionMode() {
            lastTickPPower = tickPPower = tickRPower = lastTickRPower = growthTimer = protectionTimer = 0f;
            status = 1;
            SiliconLog.info("Power Protector entered protection mode.");
        }

        private void handleProtectionMode() {
            protectionTimer += Time.delta;
            lastTickPPower = tickPPower;
            if (status == 1 && powerChanged.get(self()) > 0f) {
                growthTimer += Time.delta;
            } else {
                growthTimer = 0f;
            }

            tickPPower = Math.max(-(powerChanged.get(self()) - lastTickPPower) - powerStored.get(self()), 0f);

            totalSpentPower = Mathf.clamp(tickPPower + totalSpentPower, Mathf.FLOAT_ROUNDING_ERROR, Float.MAX_VALUE);
        }

        /**
         * Handles recovery mode logic
         */
        private void handleRecoveryMode() {


            // In recovery mode, consume spent power using equal principal method at 1% per second
            if (totalSpentPower > 0) {
                // Calculate equal principal amount to consume per second
                updateTick();
            }

            if (interval.get(60f) && (powerStored.get(self()) <= Mathf.FLOAT_ROUNDING_ERROR ||
                    powerChanged.get(self()) + tickRPower <= Mathf.FLOAT_ROUNDING_ERROR)) {
                lastTickRPower = 0;
                for (int i : power.links.items) {
                    if (world.build(i) != null && world.build(i) instanceof PowerNode.PowerNodeBuild p && p.power.links.contains(pos())) {
                        p.configureAny(pos());
                    }
                }
                getLink(team, other -> {
                    node = other;
                    other.power.links.addUnique(pos());
                    if (team == other.team) {
                        power.links.addUnique(other.pos());
                    }
                    power.graph.addGraph(other.power.graph);
                });
            }
            // Exit recovery mode when time is up or all spent power is consumed
            if (totalSpentPower <= 0 || Double.isNaN(totalSpentPower)) {
                status = 0;
                totalSpentPower = 0f;
                tickRPower = 0f;
                if (node != null) {
                    node.configureAny(pos());
                    node = null;
                }

            }
        }

        private void exitProtectionMode() {
            growthTimer = tickPPower = lastTickPPower = 0f;
            status = -1;
            // The Recovery period is the same as the protection period
            // How long the recovery period should last
            float rTime = Math.max(protectionTimer, 1f);
            protectionTimer = 0f;
            rPowerPrincipal = totalSpentPower / rTime; // Convert ticks to seconds
        }

        /**
         * Calculates the amount of power to recover per tick
         */
        private void updateTick() {
            if (status != -1) {
                tickRPower = 0f;
                return;
            }
            lastTickRPower = tickRPower * efficiency; // Store the last tick's recovery amount * efficiency
            // Reduce the current spent power by the recovery amount
            totalSpentPower -= lastTickRPower; // Reduce the current spent power by the recovery amount * efficiency


            // Also add interest at 1% per second of remaining spent power
            double interestPerSecond = totalSpentPower * secondRecoveryRate / 60;// 0.1% per second

            double interestPerTick = interestPerSecond / 60;
            totalSpentPower += interestPerTick;
            double dP = rPowerPrincipal + interestPerTick;
            if (dP < Float.MAX_VALUE) {
                tickRPower = (float) Mathf.clamp(powerStored.get(self()) / 2 + powerChanged.get(self()) + lastTickRPower,
                        Math.max(Mathf.FLOAT_ROUNDING_ERROR, dP), Float.MAX_VALUE);
            } else {
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

        public boolean isError() {
            return error;
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
            return 8;
        }

        @Override
        public void draw() {
            super.draw();

            if (Mathf.zero(Renderer.laserOpacity) || isPayload() || team == Team.derelict) return;

            Draw.z(Layer.power);
            setupColor(power.graph.getSatisfaction());

            if (node != null && team.data().buildings.contains(node)) {
                if (node instanceof PowerNode.PowerNodeBuild p)
                    ((PowerNode) p.block).drawLaser(x, y, node.x, node.y, size, node.block.size);
                if (node instanceof BeamNode.BeamNodeBuild p) {
                    ((BeamNode) p.block).drawLaser(x, y, node.x, node.y, size, node.block.size);

                }
            }


            Draw.reset();
        }

        protected void setupColor(float satisfaction) {
            Draw.color(Tmp.c1.set(Color.white).lerp(Pal.powerLight, (1f - satisfaction) * 0.86f + Mathf.absin(3f, 0.1f)).a(Renderer.laserOpacity));
        }


        /**
         * Provides sensor access to power network data
         */
        @Override
        public double sense(LAccess sensor) {
            if (sensor == LAccess.powerNetStored) return powerStored.get(self());
            if (sensor == LAccess.powerNetCapacity) return powerCapacity.get(self());
            if (sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
            return super.sense(sensor);
        }

        private void getLink(Team team, Cons<Building> others) {
            Boolf<Building> valid = other -> (powerCapacity.get(other) > Mathf.FLOAT_ROUNDING_ERROR &&
                    powerStored.get(other) > Mathf.FLOAT_ROUNDING_ERROR) ||
                    powerChanged.get(other) > Mathf.FLOAT_ROUNDING_ERROR;

            tempBuilds.clear();

            Seq<Building> buildings = team.data().buildings;
            if (buildings != null) {
                buildings.each(b -> b instanceof PowerNode.PowerNodeBuild p && p.power.links.size < ((PowerNode) p.block).maxNodes, tempBuilds::add);
                buildings.each(b -> b instanceof BeamNode.BeamNodeBuild p && p.power.links.size < ((PowerNode) p.block).maxNodes, tempBuilds::add);
            }

            tempBuilds.sort((a, b) -> {
                int type = -Boolean.compare(valid.get(a), valid.get(b));
                if (type != 0) return type;
                if (a.power.graph == b.power.graph) return 0;
                float pA = powerStored.get(a) + powerChanged.get(a) * 60f;
                float pB = powerStored.get(b) + powerChanged.get(b) * 60f;
                if (a.power.graph == power.graph) pA += lastTickRPower * 60f;
                if (b.power.graph == power.graph) pB += lastTickRPower * 60f;
                return -Float.compare(pA, pB);
            });

            if (tempBuilds.size > 0 && tempBuilds.first() instanceof PowerNode.PowerNodeBuild p) {
                others.get(p);
            }
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
            write.b(error ? 1 : 0);
            TypeIO.writeBuilding(write, node);
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
            error = read.b() == 1;
            node = TypeIO.readBuilding(read);
        }
    }
}