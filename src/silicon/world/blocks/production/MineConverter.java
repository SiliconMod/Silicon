package silicon.world.blocks.production;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.EnumSet;
import arc.struct.ObjectFloatMap;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Sounds;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.world.blocks.ItemSelection;
import mindustry.world.blocks.production.Drill;
import mindustry.world.meta.BlockFlag;
import mindustry.world.meta.Stat;
import mindustry.world.meta.Stats;
import silicon.util.SiliconLog;
import silicon.world.blocks.FrameBlock;
import silicon.world.meta.StatValues;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.TreeMap;

import static mindustry.Vars.*;
import static mindustry.content.Blocks.blastDrill;
import static silicon.Vars.costs;

public class MineConverter extends FrameBlock {
    public float craftTime = 60;
    public float consumeTime = 60;
    public float consumptionMultiples = 0.1f;
    private static long lastCostsWorldChange = -1;
    private static boolean costsDirty = true;
    TreeMap<Float, Item> scaled = new TreeMap<>((o1, o2) -> {
        if (Objects.equals(o1, o2)) return 0;
        return o1 > o2 ? 1 : -1;
    });

    // The world costs must be identical on every machine, otherwise the host and the other
    // players show different bars/selection lists and even simulate the machine differently.
    // They are therefore computed once on the server (which has the authoritative world) and
    // broadcast to every client; clients only use the received data.
    private static MineConverter instance;
    private static boolean costsSynced = false; // true once authoritative costs arrived from the server
    private static boolean networkingInitialized, clientHandlerRegistered, serverHandlerRegistered;

    public static final String costsPacket = "silicon-mine-converter-costs";
    public static final String costsRequestPacket = "silicon-mine-converter-costs-request";

    public MineConverter(String name) {
        super(name);
        configurable = true;
        update = true;
        solid = true;
        hasItems = true;
        ambientSound = Sounds.loopMachine;
        sync = true;
        ambientSoundVolume = 0.03f;
        flags = EnumSet.of(BlockFlag.factory);
        drawArrow = false;
        saveConfig = true;
        selectionRows = 6;
        selectionColumns = 6;

        instance = this;

        // A new map must never inherit the previous map's mineral costs or the item the player
        // last picked (Block.lastConfig would otherwise be applied to newly placed converters).
        // The server immediately recomputes and pushes the fresh data to every client.
        Events.on(EventType.WorldLoadEvent.class, e -> onWorldLoad());

        config(Item.class, (MineConverterBuild b, Item item) -> {
            b.craft = item;
            if (b.consume == item) b.consume = null;
        });
        configClear((MineConverterBuild b) -> b.craft = null);
    }

    /** Registers the multiplayer hooks; safe to call from the mod's init(). */
    public static void initNetworking() {
        if (networkingInitialized) return;
        networkingInitialized = true;

        if (netServer != null) registerServerHandler();
        if (netClient != null) registerClientHandler();

        // client side: ask the server for the current world costs right after every world
        // load (this also covers the case where the server's broadcast raced ahead of us)
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (net.client()) {
                Call.serverBinaryPacketReliable(costsRequestPacket, new byte[0]);
            }
        });

        // players joining mid-game need the current costs immediately
        Events.on(EventType.PlayerJoin.class, e -> {
            if (net.server()) sendCostsTo(e.player);
        });
    }

    private static void registerServerHandler() {
        if (serverHandlerRegistered) return;
        serverHandlerRegistered = true;
        netServer.addBinaryPacketHandler(costsRequestPacket, (player, data) -> sendCostsTo(player));
    }

    private static void registerClientHandler() {
        if (clientHandlerRegistered) return;
        clientHandlerRegistered = true;
        netClient.addBinaryPacketHandler(costsPacket, MineConverter::applyCosts);
    }

    /** Called when a new world loads; discards everything derived from the previous map. */
    void onWorldLoad() {
        if (netServer != null) registerServerHandler(); // dedicated servers may not have netServer at mod init
        if (netClient != null) registerClientHandler(); // clients may not have netClient at mod init
        lastConfig = null; // forget the previous map's selected item
        clearCosts();
        costsSynced = false;
        if (net.server()) {
            // The server recomputes for the new map and pushes the fresh data (even an empty
            // result must be broadcast so clients stop using their local fallback).
            countWorldCosts();
            broadcastCosts();
            setStats();
        }
    }

    static void clearCosts() {
        if (instance != null) {
            for (Item i : costs.keys()) {
                instance.itemFilter[i.id] = false;
            }
            instance.scaled.clear();
        }
        costs.clear();
        lastCostsWorldChange = -1;
        costsDirty = true;
    }

    @Override
    public void setBars() {
        super.setBars();
        addBar("mine-progress", (MineConverterBuild b) -> new Bar(
                () -> Core.bundle.get("bar.mine-progress"),
                () -> Pal.accent,
                () -> b.consume != null ? Math.min(b.consumeProgress / consumeTime, 1f) : 0f)
        );
        addBar("craft-progress", (MineConverterBuild b) -> new Bar(
                () -> {
                    if (b.craft != null) {
                        float divisor = costs.get(b.craft, 0) * (1 + consumptionMultiples);
                        return divisor > 0 ? Core.bundle.format("bar.craft-progress",
                                Strings.fixed(b.craftValue / divisor, 1),
                                Strings.fixed(b.mineValue / divisor, 1)) : Core.bundle.get("bar.craft-progress.waiting");
                    }
                    return Core.bundle.get("bar.craft-progress.waiting");
                },
                () -> Pal.powerBar,
                () -> {
                    if (b.craft != null) {
                        float divisor = costs.get(b.craft, 0) * (1 + consumptionMultiples);
                        return divisor > 0 ? Math.min(b.craftValue / divisor, 1f) : 0f;
                    }
                    return 0f;
                })
        );
    }

    @Override
    public void setStats() {
        stats = new Stats();
        super.setStats();
        stats.add(Stat.productionTime, "1s");
        stats.add(silicon.world.meta.Stat.itemsScaled, StatValues.itemsScaled(false, scaled));
    }

    /** Recomputes the map's mineral costs from the current world; returns whether they changed. */
    public boolean countWorldCosts() {
        if (!costsDirty && lastCostsWorldChange == world.tileChanges) return false;
        costsDirty = false;
        lastCostsWorldChange = world.tileChanges;
        ObjectFloatMap<Item> oldCosts = new ObjectFloatMap<>(costs);
        for (Item i : oldCosts.keys()) {
            itemFilter[i.id] = false;
        }
        costs.clear();
        world.tiles.eachTile(tile -> {
            if (tile.drop() == null) return;
            costs.increment(tile.drop(), 0, 1);
        });
        ObjectFloatMap<Item> newCosts = new ObjectFloatMap<>();
        costs.each((o) -> {
            newCosts.put(o.key, 1e4f / o.value * ((Drill) blastDrill).getDrillTime(o.key));
        });
        costs.clear();
        newCosts.each((o) -> costs.put(o.key, o.value));
        for (Item i : costs.keys()) {
            itemFilter[i.id] = true;
        }
        rebuildScaled();
        boolean changed = !oldCosts.equals(costs);
        if (changed) SiliconLog.info("Recount the number of minerals");
        return changed;
    }

    /** Rebuilds the scaled display values (used by the stats screen) from the current costs. */
    void rebuildScaled() {
        scaled.clear();
        float max = 0;
        for (float i : costs.values().toSeq().toArray()) {
            if (i > max) max = i;
        }
        float finalMax = max;
        if (finalMax > 0) {
            costs.each((i) -> {
                float key = finalMax / i.value;
                if (!scaled.containsKey(key)) {
                    scaled.put(key, i.key);
                }
            });
        }
    }

    // ---------- multiplayer: world costs are computed on the server and broadcast ----------

    /** Serializes the current world costs into a small binary payload. */
    public static byte[] serializeCosts() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(costs.size);
            for (Item item : costs.keys()) {
                out.writeShort(item.id);
                out.writeFloat(costs.get(item, 0));
            }
        } catch (IOException e) {
            SiliconLog.info("Failed to serialize world costs: " + e);
        }
        return bytes.toByteArray();
    }

    /** Applies the authoritative world costs received from the server. */
    public static void applyCosts(byte[] data) {
        if (data == null || data.length < 4) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            if (n < 0 || n > 2048) return; // sanity check
            ObjectFloatMap<Item> map = new ObjectFloatMap<>();
            for (int i = 0; i < n; i++) {
                short id = in.readShort();
                float value = in.readFloat();
                if (id >= 0 && id < content.items().size) {
                    Item item = content.items().get(id);
                    if (item != null && value > 0) map.put(item, value);
                }
            }
            MineConverter mc = instance;
            for (Item i : costs.keys()) {
                if (mc != null) mc.itemFilter[i.id] = false;
            }
            costs.clear();
            map.each((entry) -> costs.put(entry.key, entry.value));
            if (mc != null) {
                for (Item i : costs.keys()) mc.itemFilter[i.id] = true;
                mc.rebuildScaled();
                mc.setStats();
            }
            costsSynced = true;
            costsDirty = false;
            lastCostsWorldChange = world.tileChanges;
        } catch (Exception e) {
            SiliconLog.info("Failed to apply world costs: " + e);
        }
    }

    /** Sends the current world costs to every connected client (server side only). */
    public static void broadcastCosts() {
        if (!net.server()) return;
        byte[] data = serializeCosts();
        for (Player p : Groups.player) {
            if (p.con != null) Call.clientBinaryPacketReliable(p.con, costsPacket, data);
        }
    }

    /** Sends the current world costs to one player (server side only). */
    public static void sendCostsTo(Player p) {
        if (!net.server() || p == null) return;
        if (costs.size == 0 && instance != null) {
            instance.countWorldCosts();
        }
        if (p.con != null) {
            Call.clientBinaryPacketReliable(p.con, costsPacket, serializeCosts());
        }
    }

    public class MineConverterBuild extends FrameBuild {
        public float mineValue = 0;
        public float consumeProgress = 0;
        public float craftValue = 0;
        public float warmup;
        public Item craft = null, consume = null;
        public int lastChange;

        @Override
        public void updateTile() {
            super.updateTile();
            if (!enabled) return;

            if (lastChange != world.tileChanges) {
                lastChange = world.tileChanges;
                if (net.server()) {
                    // authoritative: recompute on the server and push to every client
                    if (countWorldCosts()) {
                        broadcastCosts();
                        block.setStats();
                    }
                } else if (!costsSynced) {
                    // client that hasn't received server data yet: local fallback
                    if (countWorldCosts()) block.setStats();
                }
            }
            if (costs.size == 0 && (net.server() || !net.client() || !costsSynced)) {
                if (countWorldCosts()) block.setStats();
            }
            {
                if ((consumeProgress >= consumeTime || consume == null)) {
                    consumeProgress = 0;

                    if (consume == null || items.get(consume) == 0) {
                        consume = null;
                        for (int i = 0; i < items.length(); i++) {
                            Item item = content.item(i);
                            if (item != null && item != craft && items.get(i) != 0 && costs.get(item, 0) > 0
                                    && (consume == null || items.get(i) > items.get(consume)))
                                consume = item;
                        }
                    }
                    if (consume != null && items.get(consume) > 0) {
                        items.remove(consume, 1);
                    }
                }
                if (consume != null) {
                    consumeProgress += edelta();
                    float change = costs.get(consume, 0) * edelta() / consumeTime;
                    mineValue += change;
                }
            }
            {
                if (craft == null) return;
                float c = costs.get(craft, 0) * (1 + consumptionMultiples);
                if (c > 0) {
                    if (craftValue >= c && items.get(craft) < itemCapacity) {
                        craftValue -= c;
                        items.add(craft, 1);
                    } else if (items.get(craft) == itemCapacity) {
                        dump(craft);
                        return;
                    }
                    float del = Math.min(mineValue, c / craftTime * edelta());
                    mineValue -= del;
                    craftValue += del;
                }
            }
            dump(craft);
        }

        @Override
        public void buildConfiguration(Table table) {
            if (costs.size == 0 && (net.server() || !net.client() || !costsSynced)) {
                countWorldCosts();
            }
            Seq<Item> items = costs.size > 0 ? costs.keys().toSeq() : content.items().copy();
            ItemSelection.buildTable(MineConverter.this, table, items,
                    () -> craft, this::configure, selectionRows, selectionColumns);
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            return craft != item && !(source instanceof MineConverterBuild && source != self()) && super.acceptItem(source, item);
        }

        @Override
        public boolean shouldConsume() {
            return consume != null || craft != null;
        }

        @Override
        public Item config() {
            return craft;
        }

        @Override
        public byte version() {
            return 2;
        }

        @Override
        public void drawSelect() {
            super.drawSelect();
            drawItemSelection(craft);
        }


        /**
         * Writes building data to save a file
         *
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(mineValue);
            write.f(craftValue);
            write.f(consumeProgress);
            write.f(warmup);
            write.s(craft == null ? -1 : craft.id);
            write.s(consume == null ? -1 : consume.id);
        }

        /**
         * Reads building data from a save file
         *
         * @param read     The reader object
         * @param revision The save revision
         */
        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            mineValue = read.f();
            craftValue = read.f();
            consumeProgress = read.f();
            warmup = read.f();
            short craftId = read.s();
            craft = craftId >= 0 ? content.items().get(craftId) : null;
            short consumeId = read.s();
            consume = consumeId >= 0 ? content.items().get(consumeId) : null;
        }
    }
}
