package silicon.world.blocks.defense;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BlockGroup;
import silicon.util.SiliconTmp;

public class Switch extends Block {
    public Switch(String name) {
        super(name);
        update = true;
        solid = true;
        configurable = false;
        rotate = true;
        group = BlockGroup.projectors;
        config(Boolean.class, (building, enabled) -> {
            if (building.front() != null) building.front().enabled = !enabled;
        });
    }

    @Override
    public void placeEnded(Tile tile, @Nullable Unit builder, int rotation, @Nullable Object config) {
        if (tile.build instanceof SwitchBuild build && build.front() != null) {
            build.fE = build.front().enabled;
        }
    }

    public class SwitchBuild extends Building {
        boolean fE;
        @Override
        public void drawSelect() {
            super.drawSelect();
            if (front() == null || (front() instanceof SwitchBuild)) return;
            Drawf.selected(front(), SiliconTmp.c1.set(front().enabled ? Color.green : Color.red).a(Mathf.absin(4f, 1f)));

        }

        @Override
        public void updateTile() {
            super.updateTile();
            if (front() != null && front().enabled != fE) front().enabled = fE;
        }

        @Override
        public void tapped() {
            if (front() != null && !(front() instanceof SwitchBuild)) fE = !fE;
        }

        /**
         * Writes building data to save a file
         *
         * @param write The writer object
         */
        @Override
        public void write(Writes write) {
            super.write(write);
            write.bool(fE);
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
            fE = read.bool();
        }

        @Override
        public Boolean config() {
            return front() != null && front().enabled;
        }
    }
}
