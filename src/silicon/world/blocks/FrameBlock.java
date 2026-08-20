package silicon.world.blocks;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.world.Block;

public class FrameBlock extends Block {
    public int frame, frameTime;
    public TextureRegion[] frames;

    public FrameBlock(String name) {
        super(name);
    }

    @Override
    public void load() {
        super.load();
        frames = new TextureRegion[frame];
        region = Core.atlas.find(name + "-0");
        for (int i = 0; i < frame; i++) {
            frames[i] = Core.atlas.find(name + "-" + i);
        }

    }

    public class FrameBuild extends Building {

        @Override
        public void draw() {
            super.draw();
            if (frame <= 0) return;
            int idx = ((int) (Time.time * frameTime / 60f)) % frame;
            Draw.rect(frames[idx], x, y);
        }
    }
}
