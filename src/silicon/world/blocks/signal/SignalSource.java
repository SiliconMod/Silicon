package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.StatUnit;
import silicon.util.Signals;
import silicon.util.SignalUser;
import silicon.world.meta.Stat;

/**
 * SignalSource - 信号源
 * Generates a unique random 4-character signal (uppercase A-Z and digits 0-9) when placed.
 * The signal is generated once on the server (placeEnded only runs on the server), synced to
 * all clients via the config mechanism, and persisted to saves.
 * The signal is removed again when the block is removed.
 * The signal is shown inside the vanilla HUD bar (e.g. "信号：A1G4").
 */
public class SignalSource extends Block{
    /** Allowed characters: uppercase letters and digits only, to avoid encoding issues. */
    public static final String SIGNAL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** Length of a signal. */
    public static final int SIGNAL_LENGTH = 4;
    /** Max attempts before giving up on finding an unused signal. */
    private static final int MAX_ATTEMPTS = 1000;

    /** Dark blue color used for the dashed logistics lines to all blocks using this signal. */
    public static final Color linkColor = Color.valueOf("5a63d8");

    /** All signals currently in use, so every placed block gets a unique one. */
    public static final ObjectSet<String> usedSignals = new ObjectSet<>();

    public SignalSource(String name){
        super(name);
        update = true; // needed so power consumption runs and the signal can actually be active
        solid = true;
        destructible = true;
        breakable = true;
        // A signal source needs power to emit its signal.
        hasPower = true;
        consumePower(60f / 60f);
        // clicking the block opens a small info UI, like a vanilla bridge's configure dialog
        configurable = true;
        config(String.class, (building, value) -> {
            if(building instanceof SignalSourceBuild b){
                b.signal = value;
                if(value != null) usedSignals.add(value);
            }
        });
    }

    /** Adds the vanilla health/power bars plus a signal bar that shows "信号：A1G4". */
    @Override
    public void setBars(){
        super.setBars();
        addBar("signal", (SignalSourceBuild b) -> new Bar(
            () -> b.signal == null
                ? Core.bundle.get("block.silicon-signal-source.nosignal")
                : Core.bundle.format("block.silicon-signal-source.signal", b.signal),
            () -> Pal.accent,
            () -> b.signal == null ? 0f : 1f
        ));
    }

    /** Adds database/info page statistics. */
    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.signalLength, String.valueOf(SIGNAL_LENGTH), StatUnit.none);
    }

    @Override
    public void placeEnded(Tile tile, @Nullable Unit builder, int rotation, @Nullable Object config){
        super.placeEnded(tile, builder, rotation, config);
        if(tile.build instanceof SignalSourceBuild b){
            // placeEnded only runs on the server, so generate here and sync to all clients.
            b.configureAny(generateUniqueSignal());
        }
    }

    /** @return a random 4-character signal (A-Z, 0-9) that is not already in use. */
    public static String generateUniqueSignal(){
        StringBuilder sb = new StringBuilder(SIGNAL_LENGTH);
        String candidate;
        int attempts = 0;
        do{
            sb.setLength(0);
            for(int i = 0; i < SIGNAL_LENGTH; i++){
                sb.append(SIGNAL_CHARS.charAt(Mathf.random(SIGNAL_CHARS.length() - 1)));
            }
            candidate = sb.toString();
        }while(usedSignals.contains(candidate) && ++attempts < MAX_ATTEMPTS);

        usedSignals.add(candidate);
        return candidate;
    }

    /**
     * Rebuilds the in-use signal set from all signal sources currently in the world.
     * Called on world load (after buildings have been read), because clearing it too early
     * would wipe the signals restored from the save.
     */
    public static void rebuildUsedSignals(){
        usedSignals.clear();
        for(Building b : Groups.build){
            if(b instanceof SignalSourceBuild sb && sb.signal != null){
                usedSignals.add(sb.signal);
            }
        }
    }

    public class SignalSourceBuild extends Building{
        public String signal;

        @Override
        public Object config(){
            return signal;
        }

        /**
         * @return whether the signal is currently active: a signal is set AND the source has power.
         * The signal is passive and only valid while the source is powered.
         */
        public boolean isActive(){
            return signal != null && power != null && power.status >= 0.999f;
        }

        /** @return how many machines on this team currently use this signal. */
        int countUsers(){
            return Signals.countUsers(signal, team);
        }

        /**
         * Small info dialog (like a vanilla bridge's configure dialog): shows the signal
         * and how many machines currently use it. Both texts are computed once when the
         * dialog opens, they do not refresh every frame.
         */
        @Override
        public void buildConfiguration(Table table){
            String signalText = signal == null
                ? Core.bundle.get("block.silicon-signal-source.nosignal")
                : Core.bundle.format("block.silicon-signal-source.signal", signal);
            // the user count is read exactly once, when the dialog is opened
            String countText = Core.bundle.format("block.silicon-signal-source.users", countUsers());

            table.top();
            table.table(main -> {
                main.defaults().pad(4f);
                main.label(() -> signalText).padTop(6f);
                main.row();
                main.label(() -> countText).color(Color.lightGray).padBottom(6f);
            });
        }

        /**
         * While the config dialog is open (i.e. only when clicked, never on hover), draws
         * dark blue dashed lines (vanilla bridge style) from this source to every block
         * that uses its signal.
         */
        @Override
        public void drawConfigure(){
            super.drawConfigure();
            if(signal == null) return;
            for(SignalUser user : Signals.users(signal, team)){
                if(user instanceof Building b){
                    Drawf.dashLine(linkColor, x, y, b.x, b.y);
                }
            }
        }

        /**
         * Re-syncs the signal to clients when this block enters the world (e.g. after loading a save).
         * Custom fields like {@link #signal} are not synced by the entity system, so clients would
         * otherwise never see it after a load and could not list the signal.
         */
        @Override
        public void onProximityAdded(){
            super.onProximityAdded();
            if(signal != null && Vars.net.server()){
                Call.tileConfig(null, this, signal);
            }
        }

        /** Releases the signal when this block is removed, so it can be reused later. */
        @Override
        public void onRemoved(){
            super.onRemoved();
            if(signal != null){
                usedSignals.remove(signal);
            }
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.str(signal);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            signal = read.str();
            if(signal != null) usedSignals.add(signal);
        }
    }
}
