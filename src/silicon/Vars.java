package silicon;

import arc.func.Floatf;
import arc.struct.ObjectFloatMap;
import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.type.Item;


public class Vars {
    public static final String name = "Silicon";
    public static final Floatf<Building> powerChanged = (entity) -> entity.power != null && entity.power.graph != null ? entity.power.graph.getLastScaledPowerIn() - entity.power.graph.getLastScaledPowerOut() : 0f;
    public static final Floatf<Building> powerStored = (entity) -> entity.power != null && entity.power.graph != null ? entity.power.graph.getBatteryStored() : 0f;
    public static final Floatf<Building> powerCapacity = (entity) -> entity.power != null && entity.power.graph != null ? entity.power.graph.getTotalBatteryCapacity() : 0f;
    public static final ObjectFloatMap<Item> costs = new ObjectFloatMap<>();
    public static volatile Pause pause = new Pause("", true);

    public static int pauseMode = 0;
    public static Seq<String> pauseWhitelist = new Seq<>();

    public static class Pause {
        String time;
        boolean complete;

        Pause(String time, boolean complete) {
            this.time = time;
            this.complete = complete;
        }

        Pause(String time) {
            this(time, false);
        }
    }

}
