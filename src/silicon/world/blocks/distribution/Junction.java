package silicon.world.blocks.distribution;

import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.BufferItem;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.type.Item;
import mindustry.world.DirectionalItemBuffer;
import mindustry.world.blocks.liquid.LiquidJunction;
import mindustry.world.meta.BlockGroup;

import static mindustry.Vars.content;

/**
 * Junction - A transportation block that allows items to pass through
 * from one direction to another without collision
 * Items travel through the junction with a configurable speed
 */
public class Junction extends LiquidJunction {
    /**
     * Frames taken for an item to travel through this junction
     */
    public float speed = 26;
    /**
     * Maximum number of items that can be stored in the buffer
     */
    public int capacity = 6;

    /**
     * Constructor for Junction block
     *
     * @param name The name identifier for this block
     */
    public Junction(String name) {
        super(name);
        update = true;
        solid = false;
        underBullets = true;
        group = BlockGroup.transportation;
        unloadable = false;
        floating = true;
        noUpdateDisabled = true;
    }

    /**
     * Indicates that this block outputs items
     *
     * @return true since junctions output items
     */
    @Override
    public boolean outputsItems() {
        return true;
    }

    /**
     * Building class for Junction
     * Manages item buffering and transportation logic
     */
    public class JunctionBuild extends LiquidJunctionBuild {
        /** Buffer that stores items for each direction */
        public DirectionalItemBuffer buffer = new DirectionalItemBuffer(capacity);

        /**
         * Accepts item stacks - returns 0 as junctions don't stack items
         * @param item The item type
         * @param amount The amount of items
         * @param source The source building
         * @return 0 as junctions don't accept stacked items
         */
        @Override
        public int acceptStack(Item item, int amount, Teamc source) {
            return 0;
        }

        /**
         * Updates the junction tile each frame
         * Processes items in the buffer and moves them to destination
         */
        @Override
        public void updateTile() {

            for (int i = 0; i < 4; i++) {
                if (buffer.indexes[i] > 0) {
                    // Ensure buffer index doesn't exceed capacity
                    if (buffer.indexes[i] > capacity) buffer.indexes[i] = capacity;
                    long l = buffer.buffers[i][0];
                    float time = BufferItem.time(l);

                    // Check if item has waited long enough to move
                    if (Time.time >= time + speed / timeScale || Time.time < time) {

                        Item item = content.item(BufferItem.item(l));
                        Building dest = nearby(i);

                        // Skip blocks that don't want the item, keep waiting until they do
                        if (item == null || dest == null || !dest.acceptItem(this, item) || dest.team != team) {
                            continue;
                        }

                    // Transfer item to destination and shift buffer
                    dest.handleItem(this, item);
                    System.arraycopy(buffer.buffers[i], 1, buffer.buffers[i], 0, buffer.indexes[i] - 1);
                        buffer.indexes[i]--;
                    }
                }
            }
        }

        /**
         * Handles incoming items from a source building
         * @param source The source building sending the item
         * @param item The item being sent
         */
        @Override
        public void handleItem(Building source, Item item) {
            int relative = source.relativeTo(tile);
            buffer.accept(relative, item);
        }

        /**
         * Determines if this junction can accept an item from a source
         * @param source The source building
         * @param item The item to accept
         * @return true if the item can be accepted, false otherwise
         */
        @Override
        public boolean acceptItem(Building source, Item item) {
            int relative = source.relativeTo(tile);

            if (relative == -1 || !buffer.accepts(relative)) return false;
            Building to = nearby(relative);
            return to != null && to.team == team;
        }

        /**
         * Gets the save version for this building
         * @return The version number
         */
        @Override
        public byte version() {
            return 1;
        }

        /**
         * Writes building data to save file
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            buffer.write(write);
        }

        /**
         * Reads building data from save file
         * @param read The reader object
         * @param revision The save revision
         */
        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            buffer.read(read, revision == 0);
        }
    }
}
