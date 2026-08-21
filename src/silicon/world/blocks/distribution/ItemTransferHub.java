package silicon.world.blocks.distribution;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.game.Team;
import mindustry.core.Renderer;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import silicon.world.blocks.production.MineConverter;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BlockGroup;

import static mindustry.Vars.content;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

public class ItemTransferHub extends Block {
    public float connectionRange = 20f;
    public int maxConnections = 50;

    public ItemTransferHub(String name) {
        super(name);
        hasItems = false;
        hasPower = true;
        consumesPower = true;
        outputsPower = false;
        conductivePower = true;
        consumePowerDynamic(entity -> ((ItemTransferHubBuild) entity).powerConsumed);
        consumePowerBuffered(50f);
        solid = true;
        update = true;
        size = 3;
        timers = 4;
        configurable = true;
        group = BlockGroup.transportation;

        config(Integer.class, (ItemTransferHubBuild entity, Integer pos) -> {
            Building other = world.build(pos);
            if (other == null || !other.isValid() || other == entity) return;

            if (entity.links.contains(pos)) {
                entity.links.removeValue(pos);
                if (other instanceof ItemTransferHubBuild otherHub) {
                    otherHub.links.removeValue(entity.pos());
                    rebuildData(otherHub);
                }
                rebuildData(entity);
            } else {
                if (entity.links.size >= maxConnections) return;
                if (!linkValid(entity, other)) return;
                entity.links.addUnique(pos);
                if (other instanceof ItemTransferHubBuild otherHub) {
                    if (!otherHub.links.contains(entity.pos()) && otherHub.links.size < maxConnections) {
                        otherHub.links.addUnique(entity.pos());
                    }
                    rebuildData(otherHub);
                }
                rebuildData(entity);
            }
        });
    }

    private static boolean shouldConnect(Building other) {
        if (other == null) return false;
        Block b = other.block;
        if (b instanceof CoreBlock) return true;
        if (b instanceof StorageBlock) return true;
        if (b instanceof GenericCrafter) return true;
        if (b instanceof MineConverter) return true;
        if (b instanceof Drill) return true;
        if (b instanceof ItemTurret) return true;
        if (b instanceof ItemTransferHub) return true;
        return false;
    }

    public static boolean linkValid(Building tile, Building link) {
        if (tile == link || link == null) return false;
        if (!(tile.block instanceof ItemTransferHub)) return false;
        if (tile.team != link.team) return false;
        if (!shouldConnect(link)) return false;
        float range = ((ItemTransferHub) tile.block).connectionRange * tilesize;
        float dist = Mathf.dst(tile.x, tile.y, link.x, link.y);
        return dist <= range;
    }

    private static void rebuildData(ItemTransferHubBuild hub) {
        hub.data.clear();
        hub.links.each(pos -> {
            Building b = world.build(pos);
            if (b == null || !b.isValid() || b == hub) return;
            if (b instanceof ItemTransferHubBuild otherHub) {
                if (!hub.data.hubs.contains(otherHub)) hub.data.add(otherHub);
            } else if (shouldConnect(b)) {
                if (!hub.data.buildings.contains(b)) hub.data.add(b);
            }
        });
    }

    @Override
    public void setBars() {
        addBar("health", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("stat.health"),
                () -> Pal.health,
                () -> b.healthf()
        ).blink(Color.white));
        addBar("silicon-hub-power", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-power"),
                () -> Pal.powerBar,
                () -> b.power != null ? b.power.status : 0f
        ));
        addBar("silicon-hub-power-cost", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-power-cost", Strings.fixed(b.powerPerSecond, 1)),
                () -> Pal.accent,
                () -> Math.min(b.powerPerSecond / 100f, 1f)
        ));
        addBar("silicon-hub-connections", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-connections", b.links.size, maxConnections),
                () -> Pal.items,
                () -> (float) b.links.size / maxConnections
        ));
        addBar("silicon-hub-transfer-rate", (ItemTransferHubBuild b) -> new Bar(
                () -> Core.bundle.format("bar.silicon-hub-transfer-rate", b.transferCountPerSecond),
                () -> Pal.accent,
                () -> Math.min(b.transferCountPerSecond / 50f, 1f)
        ));
    }

    @Override
    public void drawPlace(int tx, int ty, int rotation, boolean valid) {
        super.drawPlace(tx, ty, rotation, valid);

        float range = connectionRange * tilesize;
        float cx = tx * tilesize + offset;
        float cy = ty * tilesize + offset;

        Drawf.dashCircle(cx, cy, range, Pal.accent);

        for (int ix = tx - (int) connectionRange; ix <= tx + (int) connectionRange; ix++) {
            for (int iy = ty - (int) connectionRange; iy <= ty + (int) connectionRange; iy++) {
                Building b = world.build(ix, iy);
                if (b != null && b.team == mindustry.Vars.player.team()) {
                    if (b instanceof ItemTransferHubBuild || shouldConnect(b)) {
                        float dist = Mathf.dst(cx, cy, b.x, b.y);
                        if (dist <= range) {
                            Drawf.square(b.x, b.y, b.block.size * tilesize / 2f + 2f, Pal.place);
                        }
                    }
                }
            }
        }

        Draw.reset();
    }

    public class ItemTransferHubBuild extends Building {
        public ItemTransferHubNetwork network = new ItemTransferHubNetwork();
        public ItemTransferHubNetwork.HubData data;
        public IntSeq links = new IntSeq();
        public float powerConsumed = 0f;
        public float powerPerSecond = 0f;
        private float powerAccumulator = 0f;
        private int transferCount = 0;
        private int transferCountPerSecond = 0;

        private final Seq<ItemTransferHubBuild> bfsQueue = new Seq<>();
        private final IntSeq bfsDists = new IntSeq();
        private final IntSet bfsVisited = new IntSet();

        public ItemTransferHubBuild() {
            super();
            data = new ItemTransferHubNetwork.HubData(new Seq<>());
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            return false;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            updateTopology();
        }

        @Override
        public void created() {
            super.created();
            updateTopology();
        }

        @Override
        public void placed() {
            super.placed();
            if (links.size > 0) return;

            int rangeTiles = (int) connectionRange;
            for (int ix = tile.x - rangeTiles; ix <= tile.x + rangeTiles; ix++) {
                for (int iy = tile.y - rangeTiles; iy <= tile.y + rangeTiles; iy++) {
                    Building b = world.build(ix, iy);
                    if (b != null && b != this && b.team == team && linkValid(this, b)
                            && !links.contains(b.pos()) && links.size < maxConnections) {
                        configure(b.pos());
                    }
                }
            }
        }

        private void updateTopology() {
            data.clear();
            links.each(pos -> {
                Building b = world.build(pos);
                if (b == null || !b.isValid() || b == this) return;
                if (b instanceof ItemTransferHubBuild hub) {
                    if (!data.hubs.contains(hub)) data.add(hub);
                } else if (shouldConnect(b)) {
                    if (!data.buildings.contains(b)) data.add(b);
                }
            });
        }

        @Override
        public void updateTile() {
            super.updateTile();
            if (power == null || power.status <= 0) return;
            if (!enabled) return;

            powerConsumed = 0f;
            transferCount = 0;

            if (network.enableDemandPull) pullOnDemand();
            if (network.enableSurplusPush) pushSurplusToCore();

            powerAccumulator += powerConsumed;

            if (timer(3, 60)) {
                powerPerSecond = powerAccumulator;
                powerAccumulator = 0f;
                transferCountPerSecond = transferCount;
            }
        }

        private void pullOnDemand() {
            for (Building consumer : data.buildings) {
                if (consumer.items == null || !consumer.isValid()) continue;

                for (int i = 0; i < content.items().size; i++) {
                    Item item = content.item(i);
                    if (item == null) continue;
                    if (consumer.items.get(item) >= consumer.block.itemCapacity) continue;

                    Building supplier = findNearestSupplier(consumer, item);
                    if (supplier != null) {
                        directTransfer(supplier, consumer, item);
                    }
                }
            }
        }

        private void pushSurplusToCore() {
            for (Building producer : data.buildings) {
                if (producer.items == null || producer.items.empty() || !producer.isValid()) continue;
                if (producer instanceof CoreBlock.CoreBuild) continue;

                for (int i = 0; i < producer.items.length(); i++) {
                    Item item = content.item(i);
                    if (item == null || producer.items.get(item) == 0) continue;
                    if (producer.items.get(item) < producer.block.itemCapacity * 0.9f) continue;

                    CoreBlock.CoreBuild core = findNearestCore(producer, item);
                    if (core != null) {
                        directTransfer(producer, core, item);
                    }
                }
            }
        }

        private void bfsInit() {
            bfsQueue.clear();
            bfsDists.clear();
            bfsVisited.clear();
            bfsVisited.add(id);
        }

        private Building findNearestSupplier(Building consumer, Item item) {
            for (Building b : data.buildings) {
                if (b == consumer || !b.isValid()) continue;
                if (b.items != null && b.items.get(item) > 0) {
                    if (consumer.acceptItem(b, item)) return b;
                }
            }

            bfsInit();
            for (ItemTransferHubBuild hub : data.hubs) {
                if (bfsVisited.add(hub.id)) {
                    bfsQueue.add(hub);
                    bfsDists.add(1);
                }
            }

            for (int idx = 0; idx < bfsQueue.size; idx++) {
                ItemTransferHubBuild hub = bfsQueue.get(idx);

                for (Building b : hub.data.buildings) {
                    if (b == consumer || !b.isValid()) continue;
                    if (b.items != null && b.items.get(item) > 0 && consumer.acceptItem(b, item)) {
                        return b;
                    }
                }

                for (ItemTransferHubBuild neighbor : hub.data.hubs) {
                    if (bfsVisited.add(neighbor.id)) {
                        bfsQueue.add(neighbor);
                        bfsDists.add(bfsDists.get(idx) + 1);
                    }
                }
            }

            return null;
        }

        private CoreBlock.CoreBuild findNearestCore(Building producer, Item item) {
            for (Building b : data.buildings) {
                if (b instanceof CoreBlock.CoreBuild core && b.isValid()) {
                    if (core.acceptItem(producer, item)) return core;
                }
            }

            bfsInit();
            for (ItemTransferHubBuild hub : data.hubs) {
                if (bfsVisited.add(hub.id)) {
                    bfsQueue.add(hub);
                    bfsDists.add(1);
                }
            }

            for (int idx = 0; idx < bfsQueue.size; idx++) {
                ItemTransferHubBuild hub = bfsQueue.get(idx);

                for (Building b : hub.data.buildings) {
                    if (b instanceof CoreBlock.CoreBuild core && b.isValid()) {
                        if (core.acceptItem(producer, item)) return core;
                    }
                }

                for (ItemTransferHubBuild neighbor : hub.data.hubs) {
                    if (bfsVisited.add(neighbor.id)) {
                        bfsQueue.add(neighbor);
                        bfsDists.add(bfsDists.get(idx) + 1);
                    }
                }
            }

            return null;
        }

        private boolean directTransfer(Building supplier, Building consumer, Item item) {
            if (power == null || power.status <= 0) return false;
            if (!consumer.acceptItem(supplier, item)) return false;

            consumer.handleItem(supplier, item);
            supplier.items.remove(item, 1);
            powerConsumed += 10f;
            transferCount++;
            return true;
        }

        @Override
        public void draw() {
            super.draw();

            if (Mathf.zero(Renderer.laserOpacity) || isPayload() || team == Team.derelict) return;

            Draw.z(Layer.power);

            Lines.stroke(2f);
            links.each(pos -> {
                Building other = world.build(pos);
                if (other == null || !other.isValid()) return;
                if (!linkValid(this, other)) return;

                if (other instanceof ItemTransferHubBuild && other.id >= id) return;

                float angle = Angles.angle(x, y, other.x, other.y);
                float cos = Mathf.cosDeg(angle);
                float sin = Mathf.sinDeg(angle);

                float len1 = block.size * tilesize / 2f;
                float len2 = other.block.size * tilesize / 2f;

                float x1 = x + cos * len1;
                float y1 = y + sin * len1;
                float x2 = other.x - cos * len2;
                float y2 = other.y - sin * len2;

                if (other instanceof ItemTransferHubBuild) {
                    Draw.color(Color.blue);
                    Lines.stroke(2f);
                    Lines.line(x1, y1, x2, y2, false);
                } else {
                    Drawf.dashLine(Color.blue, x1, y1, x2, y2, 8);
                }
            });
            Draw.reset();
        }

        @Override
        public void drawSelect() {
            super.drawSelect();

            Drawf.dashCircle(x, y, connectionRange * tilesize, Pal.accent);

            Draw.reset();
        }

        @Override
        public void drawConfigure() {
            super.drawConfigure();

            Drawf.circles(x, y, block.size * tilesize / 2f + 1f + Mathf.absin(Time.time, 4f, 1f));

            Drawf.circles(x, y, connectionRange * tilesize);

            int rangeTiles = (int) connectionRange;
            for (int ix = tile.x - rangeTiles - 2; ix <= tile.x + rangeTiles + 2; ix++) {
                for (int iy = tile.y - rangeTiles - 2; iy <= tile.y + rangeTiles + 2; iy++) {
                    Building link = world.build(ix, iy);
                    if (link == this || link == null) continue;
                    boolean linked = links.contains(link.pos());
                    if (linked && linkValid(this, link)) {
                        Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.place);
                    } else if (!linked && linkValid(this, link)) {
                        Drawf.square(link.x, link.y, link.block.size * tilesize / 2f + 1f, Pal.accent);
                    }
                }
            }

            Draw.reset();
        }

        @Override
        public boolean onConfigureBuildTapped(Building other) {
            if (linkValid(this, other)) {
                configure(other.pos());
                return false;
            }

            if (this == other) {
                if (links.size > 0) {
                    links.each(pos -> {
                        Building b = world.build(pos);
                        if (b instanceof ItemTransferHubBuild hub) {
                            hub.links.removeValue(this.pos());
                            rebuildData(hub);
                        }
                    });
                    links.clear();
                    rebuildData(this);
                } else {
                    int rangeTiles = (int) connectionRange;
                    for (int ix = tile.x - rangeTiles; ix <= tile.x + rangeTiles; ix++) {
                        for (int iy = tile.y - rangeTiles; iy <= tile.y + rangeTiles; iy++) {
                            Building b = world.build(ix, iy);
                            if (b != null && b != this && linkValid(this, b)
                                    && !links.contains(b.pos()) && links.size < maxConnections) {
                                configure(b.pos());
                            }
                        }
                    }
                }
                deselect();
                return false;
            }

            return true;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(network.id);
            write.s(links.size);
            for (int i = 0; i < links.size; i++) {
                write.i(links.get(i));
            }
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            network.id = read.i();
            // 兼容旧存档（<v1）：旧格式在 id 之后还有一个 network.version 字段，需跳过，
            // 否则后续 linkCount 会错位读到 version 值，导致链接数据损坏
            if (revision < 1) {
                read.i();
            }
            short linkCount = read.s();
            links.clear();
            for (int i = 0; i < linkCount; i++) {
                int pos = read.i();
                links.add(pos);
            }
            rebuildData(this);
        }

        @Override
        public byte version() {
            return 1;
        }
    }
}
