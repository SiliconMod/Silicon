package silicon.world.blocks.distribution;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;

import java.util.concurrent.atomic.AtomicBoolean;

public class ItemTransferHubNetwork {
    private static int total = 1;
    public int id;
    public Seq<ItemTransferHub.ItemTransferHubBuild> hubs = new Seq<>();
    public int version = 0;

    public boolean enableDemandPull = true;
    public boolean enableSurplusPush = true;

    public static void resetIdCounter() {
        total = 1;
    }

    public ItemTransferHubNetwork() {
        id = total++;
    }

    public ItemTransferHubNetwork(Seq<ItemTransferHub.ItemTransferHubBuild> hubs) {
        this();
        this.hubs.add(hubs);
    }

    public static Seq<ItemTransferHub.ItemTransferHubBuild> rebuilds(ItemTransferHub.ItemTransferHubBuild hub) {
        return rebuilds(hub, new Seq<>());
    }

    public static Seq<ItemTransferHub.ItemTransferHubBuild> rebuilds(ItemTransferHub.ItemTransferHubBuild hub, Seq<ItemTransferHub.ItemTransferHubBuild> complete) {
        complete.add(hub);
        for (ItemTransferHub.ItemTransferHubBuild other : hub.data.hubs) {
            if (!complete.contains(other)) rebuilds(other, complete);
        }
        return complete;
    }

    public ItemTransferHubNetwork merge(ItemTransferHubNetwork other) {
        if (hubs.size < other.hubs.size) {
            return other.merge(this);
        } else {
            hubs.addAll(other.hubs);
            other.clear();
            return this;
        }
    }

    public void remove(ItemTransferHub.ItemTransferHubBuild hub) {
        if (!hubs.contains(hub)) return;
        hubs.remove(hub);
        for (ItemTransferHub.ItemTransferHubBuild other : hub.data.hubs) {
            other.data.remove(hub);
        }
        Seq<ItemTransferHub.ItemTransferHubBuild> remaining = new Seq<>(hub.data.hubs);
        remaining.remove(hub);

        if (remaining.size >= 1) {

            ObjectMap<ItemTransferHub.ItemTransferHubBuild, Seq<ItemTransferHub.ItemTransferHubBuild>> rebuildss = new ObjectMap<>();

            rebuildss.put(remaining.first(), rebuilds(remaining.first()));

            for (ItemTransferHub.ItemTransferHubBuild other : hub.data.hubs) {
                AtomicBoolean found = new AtomicBoolean(false);
                rebuildss.each((key, value) -> {
                    found.set(found.get() | value.contains(other));
                });
                if (!found.get())
                    rebuildss.put(other, rebuilds(other));
            }
            rebuildss.each((key, value) -> {
                ItemTransferHubNetwork network = new ItemTransferHubNetwork(value);
                for (ItemTransferHub.ItemTransferHubBuild other : value) {
                    other.network = network;
                }
            });
        }
        hub.data.clear();
        clear();
    }


    public void clear() {
        hubs.clear();
    }

    public static class HubData {
        public final Seq<Building> buildings;
        public final Seq<ItemTransferHub.ItemTransferHubBuild> hubs = new Seq<>();
        public final int[] needs = new int[Vars.content.items().size];
        public final int[] costs = new int[Vars.content.items().size];

        public HubData(Seq<Building> buildings) {
            this.buildings = buildings;
        }

        public void add(Building building) {
            if (buildings.contains(building)) return;
            buildings.add(building);
        }

        public void add(ItemTransferHub.ItemTransferHubBuild hubBuild) {
            if (hubs.contains(hubBuild)) return;
            hubs.add(hubBuild);
        }

        public void remove(Building building) {
            if (!buildings.contains(building)) return;
            buildings.remove(building);
        }

        public void remove(ItemTransferHub.ItemTransferHubBuild hubBuild) {
            if (!hubs.contains(hubBuild)) return;
            hubs.remove(hubBuild);
        }

        public void clear() {
            buildings.clear();
            hubs.clear();
            for (int i = 0; i < Vars.content.items().size; i++) {
                needs[i] = 0;
                costs[i] = 0;
            }
        }

        public void update() {
            for (int i = 0; i < Vars.content.items().size; i++) {
                needs[i] = 0;
                costs[i] = 0;
            }
            for (Building building : buildings) {
                if (building instanceof CoreBlock.CoreBuild) continue;
                if (building.items == null) continue;

                if (building.block instanceof ItemTurret turret) {
                    turret.ammoTypes.keys().toSeq().each(item ->
                            needs[item.id] += turret.itemCapacity - building.items.get(item));
                    continue;
                }

                if (building.block instanceof GenericCrafter genericCrafter) {
                    for (Consume consumer : building.block.consumers) {
                        if (consumer instanceof ConsumeItems itemConsume)
                            for (ItemStack itemStack : itemConsume.items) {
                                needs[itemStack.item.id]
                                        += genericCrafter.itemCapacity - building.items.get(itemStack.item);
                            }
                    }
                    if (genericCrafter.outputItem != null) {
                        Item out = genericCrafter.outputItem.item;
                        if (building.items.get(out) >= building.block.itemCapacity) {
                            costs[out.id] += building.items.get(out);
                        }
                    }
                    continue;
                }

                for (int i = 0; i < Vars.content.items().size; i++) {
                    Item item = Vars.content.item(i);
                    if (item == null) continue;
                    if (!building.acceptItem(building, item)) continue;
                    int current = building.items.get(item);
                    int capacity = building.block.itemCapacity;
                    if (current < capacity) {
                        needs[item.id] += capacity - current;
                    }
                }
            }
        }

        public void updateBefore() {
            for (int i = 0; i < Vars.content.items().size; i++) {
                needs[i] = 0;
                costs[i] = 0;
            }
        }
    }
}
