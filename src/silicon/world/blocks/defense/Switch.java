package silicon.world.blocks.defense;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.scene.ui.layout.Table;
import arc.util.Eachable;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BlockGroup;
import silicon.util.SiliconTmp;

import static mindustry.Vars.player;

public class Switch extends Block {
    TextureRegion state[];

    public Switch(String name) {
        super(name);
        update = true;
        solid = true;
        sync = true; // 操纵另一端 enabled 的控制块：写读档后 fE 同步（同原版 SwitchBlock）
//        configurable = true; // 可配置：支持按钮式切换
        rotate = true;
        group = BlockGroup.logic;
        config(Boolean.class, (building, enabled) -> {
            Building front = building.front();
            // #28 只允许控制同队建筑
            if (front == null || front.team != building.team || front instanceof SwitchBuild) return;
            // #43 单次状态更新（不持续覆盖），并按该次设置刷新 switch 记忆状态
            front.enabled = enabled;
            if (building instanceof SwitchBuild sb) sb.fE = enabled;
        });
        state = new TextureRegion[2];
    }

    @Override
    public void load() {
        super.load();
        state[0] = Core.atlas.find(name + "-off");
        state[1] = Core.atlas.find(name + "-on");
//        state[0].flip(true,true);
//        state[1].flip(true,true);
        region = state[0];
    }

    @Override
    public void drawDefaultPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        int trns = size / 2 + 1;
        Building front = Vars.world.build(plan.tile().x + Geometry.d4(plan.rotation).x * trns, plan.tile().y + Geometry.d4(plan.rotation).y * trns);
        float a = Draw.getColorAlpha();
        Draw.rect(front != null && front.enabled ? state[1] : state[0], plan.drawx(), plan.drawy(), !rotate || !rotateDraw ? 0 : plan.rotation * 90 + 90);
        if(plan.worldContext && player != null && teamRegion != null && teamRegion.found()){
            if(teamRegions[player.team().id] == teamRegion) Draw.color(player.team().color, a);
            Draw.rect(teamRegions[player.team().id], plan.drawx(), plan.drawy());
            Draw.color(1f, 1f, 1f, a);
        }

        drawPlanConfig(plan, list);
    }

    @Override
    public void placeEnded(Tile tile, @Nullable Unit builder, int rotation, @Nullable Object config) {
        if (tile.build instanceof SwitchBuild build && build.front() != null
            && build.front().team == build.team) { // #28 同队校验
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
        public void draw() {
            super.draw();
            Draw.rect(fE ? state[1] : state[0], x, y, this.drawrot() + 90);
        }



        @Override
        public void updateTile() {
            super.updateTile();
            // #43 同步：前方建筑被外部（逻辑处理器等）修改时，跟随其实际状态
            Building f = front();
            if (f != null && f.team == team) {
                if (f.enabled != fE) fE = f.enabled;
            }
        }

        @Override
        public void tapped() {
            // #28 同队校验：单次切换，不持续覆盖外部逻辑
            if (front() != null && front().team == team && !(front() instanceof SwitchBuild)) {
                fE = !fE;
                configure(fE);
            }
        }

//        /**
//         * 切换式按钮配置界面：按一次切换 front 建筑启用状态并持续保持。
//         * 按钮尺寸 80×40（与原版开关按钮一致）。
//         */
        @Override
        public void buildConfiguration(Table table) {
//            table.button(Core.bundle.get("block.silicon-switch.name"), Styles.flatTogglet, () -> {
            Building front = front();
            if (front != null && front.team == team && !(front instanceof SwitchBuild)) {
                fE = !fE;
                configure(fE);
            }
//            }).checked(fE).size(80f, 40f).pad(4f);
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
            return fE;
        }
    }
}
