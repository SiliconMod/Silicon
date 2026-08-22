package silicon.world.blocks.signal;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.StatUnit;
import silicon.util.Signals;
import silicon.util.SignalUser;
import silicon.world.meta.Stat;

import static mindustry.Vars.content;

/**
 * DimensionAnchor - 维度锚点
 * A 3x3 item block. Clicking it opens a UI to pick a mode (send/receive) and a signal.
 * Only one receiving anchor is allowed per signal. In send mode it periodically tries to
 * send its whole inventory to the receiving anchor with the same signal, and aborts the
 * attempt (restarting the timer) if that is impossible.
 * The mode + signal are encoded into a single String config ("send:N3PO" / "receive:").
 */
public class DimensionAnchor extends Block{
    /** Interval between send attempts, in ticks (10 seconds). */
    public static final float sendInterval = 10f * 60f;
    /** Power consumed (per second, /60 convention) by a sending anchor. */
    public static final float sendPower = 1200f / 60f;
    /** Power consumed (per second, /60 convention) by a receiving anchor. */
    public static final float receivePower = 160f / 60f;

    /** Send status: still trying. */
    public static final int STATUS_TRYING = 0;
    /** Send status: last attempt failed. */
    public static final int STATUS_FAILED = 1;
    /** Send status: last attempt succeeded. */
    public static final int STATUS_SUCCESS = 2;

    public DimensionAnchor(String name){
        super(name);
        update = true;
        solid = true;
        destructible = true;
        breakable = true;
        hasItems = true;
        itemCapacity = 100;
        hasPower = true;
        // sending anchors draw lots of power to package+send (1200/s), receiving anchors draw a little (160/s).
        // the first argument makes the info page actually show the power consumption.
        // send mode with a signal draws 1200/s; otherwise (receive mode or no signal configured) draws 160/s.
        consumePowerDynamic(sendPower, (Building entity) -> entity instanceof DimensionAnchorBuild b ? (b.sendMode && b.signal != null ? sendPower : receivePower) : 0f);
        configurable = true;
        config(String.class, (building, value) -> {
            if(building instanceof DimensionAnchorBuild b){
                b.decode(value, true);
            }
        });
    }

    @Override
    public void setBars(){
        super.setBars();
        // send progress bar - shown only for send-mode anchors with a chosen signal
        addBar("send", (DimensionAnchorBuild b) -> {
            if(!b.sendMode || b.signal == null) return null;
            return new Bar(
                b::sendStatusText,
                () -> b.lastSendStatus == STATUS_FAILED ? Pal.remove : Pal.accent,
                () -> Mathf.clamp(b.sendTimer / sendInterval)
            );
        });
    }

    /** Adds database/info page statistics. */
    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.sendInterval, sendInterval / 60f, StatUnit.seconds);
    }

    public class DimensionAnchorBuild extends Building implements SignalUser{
        /** true = send mode, false = receive mode. */
        public boolean sendMode = true;
        /** The linked signal, or null if not configured. */
        public String signal;
        /** UI-only flag: whether the signal list is expanded. */
        public boolean expanded;
        /** Status of the last send attempt (see STATUS_*). */
        public int lastSendStatus = STATUS_TRYING;
        private float sendTimer = 0f;

        @Override
        public Object config(){
            return encode();
        }

        /** Implements {@link SignalUser} so this block is found and counted by {@link Signals}. */
        @Override
        public String signal(){
            return signal;
        }

        /**
         * @return whether this anchor's power grid satisfies its full power demand
         * (power.status is the fraction of the requested power that can be supplied).
         * Does NOT check {@link #enabled} - callers decide whether being enabled matters.
         */
        boolean powered(){
            return power != null && power.status >= 0.999f;
        }

        String encode(){
            return (sendMode ? "send:" : "receive:") + (signal == null ? "" : signal);
        }

        void decode(String str, boolean validate){
            if(str == null) return;
            int idx = str.indexOf(':');
            if(idx < 0) return;
            boolean newMode = str.startsWith("send:");
            String s = idx + 1 < str.length() ? str.substring(idx + 1) : "";
            if(s.isEmpty()) s = null;

            // only one receiving anchor may exist per signal
            if(validate && !newMode && s != null && hasOtherReceiver(s)) return;

            sendMode = newMode;
            signal = s;
        }

        /** @return whether another receiving anchor (same team) already uses this signal. */
        boolean hasOtherReceiver(String sig){
            for(Building b : Groups.build){
                if(b instanceof DimensionAnchorBuild other && other != this && !other.sendMode && other.team == team && sig.equals(other.signal)){
                    return true;
                }
            }
            return false;
        }

        /**
         * Accept items from conveyors/players as long as there is free space.
         * This is required because the default acceptItem only returns true for blocks
         * with an item consumer (consumeItem), which this block does not have.
         */
        @Override
        public boolean acceptItem(Building source, Item item){
            // only accept from same-team sources (defensive, like vanilla storage blocks)
            return source.team == team && items != null && items.total() < block.itemCapacity;
        }

        @Override
        public void handleItem(Building source, Item item){
            if(items != null && items.total() < block.itemCapacity){
                items.add(item, 1);
            }
        }

        String sendStatusText(){
            return Core.bundle.get(switch(lastSendStatus){
                case STATUS_FAILED -> "block.silicon-dimension-anchor.send.failed";
                case STATUS_SUCCESS -> "block.silicon-dimension-anchor.send.success";
                default -> "block.silicon-dimension-anchor.send.trying";
            });
        }

        @Override
        public void updateTile(){
            // only charge (and eventually send) in send mode with a configured signal
            if(sendMode && signal != null && enabled){
                // charging requires power - without it the anchor cannot charge
                if(!powered()) return;

                sendTimer += Time.delta;
                if(sendTimer >= sendInterval){
                    sendTimer = 0f; // restart the charge cycle regardless of outcome
                    if(items != null && items.total() > 0){
                        lastSendStatus = trySend() ? STATUS_SUCCESS : STATUS_FAILED;
                    }
                }
            }
        }

        /**
         * Attempts to send the whole inventory to the single receiving anchor with the same signal.
         * Cancels (and reports failure) if there are multiple/no receivers, the receiver has no
         * power, or the receiver cannot fit the whole inventory.
         */
        boolean trySend(){
            if(items == null || items.total() <= 0) return false;

            // the bound signal must be active: a powered signal source with this signal must exist.
            if(findSignalSource(signal) == null){
                // clear the config only when the signal source itself is gone (e.g. removed from the map),
                // not when it merely has no power or some other reason caused this failure.
                if(signal != null && Signals.source(signal, team) == null){
                    signal = null;
                    configure(encode());
                }
                return false;
            }

            DimensionAnchorBuild target = null;
            int receivers = 0;
            for(Building b : Groups.build){
                if(b instanceof DimensionAnchorBuild other && other != this && !other.sendMode
                    && other.team == team && other.signal != null && other.signal.equals(signal)){
                    receivers++;
                    target = other;
                }
            }
            // must be exactly one receiving anchor
            if(receivers != 1 || target == null) return false;

            // receiving anchor must be enabled and have enough power to receive
            if(!target.enabled || !target.powered()) return false;

            // receiving anchor cannot fit the whole inventory
            if(target.items == null || target.block.itemCapacity - target.items.total() < items.total()) return false;

            // transfer everything
            for(Item item : content.items()){
                int amount = items.get(item);
                if(amount <= 0) continue;
                target.items.add(item, amount);
                items.remove(item, amount);
            }
            return true;
        }

        /** @return the powered signal source (same team) that currently owns this signal, or null if none. */
        SignalSource.SignalSourceBuild findSignalSource(String sig){
            return Signals.activeSource(sig, team);
        }

        /** @return the receiving anchor (same team) linked to this signal, or null. */
        DimensionAnchorBuild findReceiver(){
            if(signal == null) return null;
            for(Building b : Groups.build){
                if(b instanceof DimensionAnchorBuild other && other != this && !other.sendMode && other.team == team && signal.equals(other.signal)){
                    return other;
                }
            }
            return null;
        }

        /**
         * Draws the logistics link(s) for this anchor: end circles + line + travelling dot.
         * Works both when selecting a sending anchor (links to its receiver) and a
         * receiving anchor (links to all sending anchors using this signal).
         */
        @Override
        public void drawSelect(){
            super.drawSelect();
            if(signal == null) return;

            if(sendMode){
                // sending anchor: link to its receiver, dot travels sender -> receiver
                DimensionAnchorBuild target = findReceiver();
                if(target != null){
                    drawLink(this, target);
                }
            }else{
                // receiving anchor: link from each sending anchor (sender -> receiver)
                for(Building b : Groups.build){
                    if(b instanceof DimensionAnchorBuild other && other != this && other.sendMode && other.team == team && signal.equals(other.signal)){
                        drawLink(other, this);
                    }
                }
            }
        }

        /** Draws a logistics link from the sending anchor to the receiving anchor. */
        void drawLink(DimensionAnchorBuild sender, DimensionAnchorBuild receiver){
            Drawf.line(Pal.accent, sender.x, sender.y, receiver.x, receiver.y);

            // endpoint circles, same size as the travelling dot: sender blue, receiver red
            Draw.color(Color.sky);
            Fill.circle(sender.x, sender.y, 3f);
            Draw.color(Color.scarlet);
            Fill.circle(receiver.x, receiver.y, 3f);

            // small dot travelling from the sender to the receiver
            float dur = 60f; // one full travel per second
            float t = Mathf.mod(Time.time, dur) / dur;
            float cx = Mathf.lerp(sender.x, receiver.x, t);
            float cy = Mathf.lerp(sender.y, receiver.y, t);
            Draw.color(Color.sky);
            Fill.circle(cx, cy, 3f);
            Draw.reset();
        }

        @Override
        public void buildConfiguration(Table table){
            rebuild(table);
        }

        void rebuild(Table table){
            table.clearChildren();
            table.top();
            // semi-transparent black background (25% opacity) behind the whole config UI
            table.setBackground(new TextureRegionDrawable(Core.atlas.white()).tint(new Color(0f, 0f, 0f, 0.25f)));

            // mode buttons: send / receive
            table.table(mt -> {
                mt.left();
                mt.button(Core.bundle.get("block.silicon-dimension-anchor.send"), Styles.flatTogglet, () -> {
                    sendMode = true;
                    expanded = true;
                    configure(encode());
                    rebuild(table);
                }).checked(sendMode).size(110f, 44f).pad(3f);
                mt.button(Core.bundle.get("block.silicon-dimension-anchor.receive"), Styles.flatTogglet, () -> {
                    if(signal != null && hasOtherReceiver(signal)){
                        Vars.ui.showInfoToast(Core.bundle.get("block.silicon-dimension-anchor.hasreceiver"), 3f);
                    }else{
                        sendMode = false;
                        expanded = true;
                        configure(encode());
                    }
                    rebuild(table);
                }).checked(!sendMode).size(110f, 44f).pad(3f);
            }).left();
            table.row();

            // signal list, always shown; shows any signal that has a signal source on the
            // same team as this anchor (no power check), so other teams' signals are hidden
            {
                Seq<String> signals = Signals.signalsFor(team);

                if(signals.isEmpty()){
                    table.label(() -> Core.bundle.get("block.silicon-dimension-anchor.nosignals"))
                        .color(Color.gray).padTop(10f);
                }else{
                    // the currently selected signal is sorted to the top
                    signals.sort((a, b) -> {
                        boolean aSel = signal != null && signal.equals(a);
                        boolean bSel = signal != null && signal.equals(b);
                        if(aSel != bSel) return aSel ? -1 : 1;
                        return a.compareTo(b);
                    });

                    Table list = new Table();
                    list.top().left();
                    for(String s : signals){
                        list.button(b -> b.label(() -> s).left(), Styles.flatTogglet, () -> {
                            if(s.equals(signal)){
                                // clicking the already-selected signal clears the config
                                signal = null;
                                configure(encode());
                            }else if(!sendMode && hasOtherReceiver(s)){
                                Vars.ui.showInfoToast(Core.bundle.get("block.silicon-dimension-anchor.hasreceiver"), 3f);
                            }else{
                                signal = s;
                                configure(encode());
                            }
                            rebuild(table);
                        }).checked(s.equals(signal)).size(230f, 34f).pad(2f).left();
                        list.row();
                    }

                    // limited height with a permanent scroll bar, like vanilla select lists
                    ScrollPane pane = new ScrollPane(list);
                    pane.setScrollingDisabled(true, false);
                    pane.setFadeScrollBars(false);
                    table.add(pane).height(180f).width(240f).padTop(6f);
                }
            }
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.str(encode());
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            // trust the save when restoring; uniqueness is only enforced on new configs
            decode(read.str(), false);
        }
    }
}
