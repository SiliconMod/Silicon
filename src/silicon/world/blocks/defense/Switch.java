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
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.ui.Styles;
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
        // 可配置:点击经 configTapped→configure 切换,联机下由服务器权威处理
        // (原先 tapped() 只翻转本地字段,客户端的开关操作在服务器上从未发生)
        configurable = true;
        rotate = true;
        group = BlockGroup.logic;
        // config 只改开关自身状态;对 front 的传播由 updateTile 在两端统一执行
        // (#28 同队校验在 updateTile 内),两端状态自然收敛
        config(Boolean.class, (SwitchBuild building, Boolean on) -> building.fE = on);
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
            // #28 同队校验：不控制其它队伍建筑
            if (front() != null && front().team == team && front().enabled != fE) front().enabled = fE;
        }

        /** 点击方块=直接切换(与原版 SwitchBlock 同款):经 configure 走 tileConfig,
         *  客户端本地预测+服务器权威执行;返回 false 不打开配置面板。 */
        @Override
        public boolean configTapped() {
            if (front() != null && front().team == team && !(front() instanceof SwitchBuild)) {
                configure(!fE);
            }
            return false;
        }

        /** 面板按钮(仅当配置面板被外部打开时可见):同样走 configure。 */
        @Override
        public void buildConfiguration(Table table) {
            table.button(Core.bundle.get("block.silicon-switch.name"), Styles.flatTogglet, () -> configure(!fE))
                .size(80f, 40f).pad(4f);
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
