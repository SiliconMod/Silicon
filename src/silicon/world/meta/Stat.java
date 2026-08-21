package silicon.world.meta;

import mindustry.world.meta.StatCat;

public class Stat extends mindustry.world.meta.Stat {
    public static final Stat
            itemsScaled = new Stat("items-scaled", StatCat.items),
            recivePower = new Stat("recive-power", StatCat.power),
            signalLength = new Stat("signal-length", StatCat.general);


    public Stat(String name) {
        super(name);
    }

    public Stat(String name, StatCat category) {
        super(name, category);
    }
}
