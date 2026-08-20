package silicon;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.TextField;
import arc.util.Time;
import mindustry.core.GameState;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.input.Binding;
import mindustry.mod.Mod;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.content.block.Blocks;
import silicon.content.item.Items;
import silicon.util.SiliconLog;
import silicon.world.blocks.production.MineConverter;

import static mindustry.Vars.*;


public class Silicon extends Mod {
    public static Mods.LoadedMod MOD;

    public Silicon() {
        Events.on(EventType.ClientLoadEvent.class, e -> {
            MOD = mods.getMod(Silicon.class);
            if (MOD != null) MOD.meta.subtitle = MOD.meta.version;
        });
    }

    @Override
    public void loadContent() {
        Items.load();
        Blocks.load();
        SiliconLog.info("Loading contents.");
    }

    @Override
    public void init() {
        MineConverter.initNetworking();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            ui.settings.addCategory("@settings.silicon.meta.category.name",
                    new TextureRegionDrawable(new TextureRegion(Silicon.MOD.iconTexture)), st -> {
                st.checkPref("pauseRequest", true);
                st.sliderPref("pauseMode", 0, 0, 2, 1,
                        i -> Core.bundle.get("setting.pauseMode.value." + i, String.valueOf(i)),
                        i -> {
                            Vars.pauseMode = i;
                            if (net.client()) Call.serverPacketReliable("pause-setmode", String.valueOf(i));
                        });
                st.row();
                st.button(Core.bundle.get("setting.pauseWhitelist.name"), Styles.flatBordert, Silicon::showWhitelistDialog).width(200f).padTop(6f);

                SiliconLog.info("Loading settings.");
            });
        });

        Events.on(EventType.ClientLoadEvent.class, e -> {
            netServer.addPacketHandler("pause", (p, time) -> {
                if (p.admin || p.name.equals(state.map.author())) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                    return;
                }

                if (Vars.pauseMode == 0) return;

                if (Vars.pauseMode == 1) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                    return;
                }

                if (Vars.pauseMode == 2 && Vars.pauseWhitelist.contains(p.name)) {
                    state.set(state.isPaused() ? GameState.State.playing : GameState.State.paused);
                    Call.clientPacketReliable(p.con, "paused", time);
                    SiliconLog.info(p.name + " pause");
                }
            });

            netServer.addPacketHandler("pause-setmode", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                try {
                    Vars.pauseMode = Integer.parseInt(data.trim());
                    if (Vars.pauseMode < 0 || Vars.pauseMode > 2) Vars.pauseMode = 0;
                } catch (NumberFormatException ignored) {}
            });

            netServer.addPacketHandler("pause-grant", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                String target = data.trim();
                if (target.isEmpty()) return;
                if (!Vars.pauseWhitelist.contains(target)) {
                    Vars.pauseWhitelist.add(target);
                }
            });

            netServer.addPacketHandler("pause-revoke", (p, data) -> {
                if (!p.admin && !p.name.equals(state.map.author())) return;
                String target = data.trim();
                Vars.pauseWhitelist.remove(target);
            });

            netClient.addPacketHandler("paused", (s) -> {
                Vars.pause.complete = true;
            });
        });

        Events.run(EventType.Trigger.update, () -> {
            if (!state.isGame()) return;
            if (net.client() && Core.settings.getBool("pauseRequest", true)) {
                if (Core.input.keyTap(Binding.pause)) {
                    String time = String.valueOf((long) Time.time);
                    Call.serverPacketReliable("pause", time);
                    Vars.pause = new Vars.Pause(time);
                } else if (!Vars.pause.complete && Time.time - Float.parseFloat(Vars.pause.time) > 60f) {
                    String time = String.valueOf((long) Time.time);
                    Call.serverPacketReliable("pause", time);
                    Vars.pause = new Vars.Pause(time);
                }
            }
        });

        Events.on(EventType.PlayerChatEvent.class, e -> {
            String msg = e.message;
            if (msg == null || !msg.startsWith("!pause")) return;
            handlePauseCommand(e.player, msg);
        });
    }

    public static void showWhitelistDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("hubWhitelist.title"));
        dialog.cont.top();

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            dialog.cont.clearChildren();
            dialog.cont.top();

            if (Vars.pauseWhitelist.isEmpty()) {
                dialog.cont.add(Core.bundle.get("hubWhitelist.empty")).color(Color.lightGray).pad(16f);
            } else {
                for (int i = 0; i < Vars.pauseWhitelist.size; i++) {
                    String name = Vars.pauseWhitelist.get(i);
                    dialog.cont.row();
                    dialog.cont.table(t -> {
                        t.add(name).growX().left();
                        t.button(Core.bundle.get("hubWhitelist.remove"), Styles.flatBordert, () -> {
                            Vars.pauseWhitelist.remove(name);
                            if (net.client()) Call.serverPacketReliable("pause-revoke", name);
                            rebuild[0].run();
                        }).padLeft(8f);
                    }).fillX().pad(4f).padLeft(8f).padRight(8f);
                }
            }

            dialog.cont.row();
            dialog.cont.table(t -> {
                TextField field = t.field("", text -> {}).growX().pad(8f).get();
                field.setMessageText(Core.bundle.get("hubWhitelist.placeholder"));
                t.button(Core.bundle.get("hubWhitelist.add"), Styles.flatBordert, () -> {
                    String input = field.getText().trim();
                    if (!input.isEmpty() && !Vars.pauseWhitelist.contains(input)) {
                        Vars.pauseWhitelist.add(input);
                        if (net.client()) Call.serverPacketReliable("pause-grant", input);
                        field.clearText();
                        rebuild[0].run();
                    }
                }).padLeft(8f);
            }).fillX().pad(8f);
        };

        rebuild[0].run();
        dialog.closeOnBack();
        dialog.show();
    }

    private void handlePauseCommand(Player p, String msg) {
        String[] parts = msg.split(" ");
        if (parts.length < 2) return;

        boolean isHost = p.admin || p.name.equals(state.map.author());

        switch (parts[1]) {
            case "on":
                if (!isHost) return;
                Vars.pauseMode = 1;
                Call.infoMessage(p.con, "[accent]Pause mode: Admins only");
                break;
            case "off":
                if (!isHost) return;
                Vars.pauseMode = 0;
                Call.infoMessage(p.con, "[accent]Pause mode: Off");
                break;
            case "custom":
                if (!isHost) return;
                Vars.pauseMode = 2;
                Call.infoMessage(p.con, "[accent]Pause mode: Custom whitelist");
                break;
            case "grant":
                if (!isHost || parts.length < 3) return;
                String grantTarget = parts[2];
                if (!Vars.pauseWhitelist.contains(grantTarget)) {
                    Vars.pauseWhitelist.add(grantTarget);
                }
                Call.infoMessage(p.con, "[accent]Granted pause to: " + grantTarget);
                break;
            case "revoke":
                if (!isHost || parts.length < 3) return;
                String revokeTarget = parts[2];
                Vars.pauseWhitelist.remove(revokeTarget);
                Call.infoMessage(p.con, "[accent]Revoked pause from: " + revokeTarget);
                break;
            case "list":
                if (!isHost) return;
                String list = Vars.pauseWhitelist.isEmpty() ? "(empty)" : Vars.pauseWhitelist.toString(", ");
                Call.infoMessage(p.con, "[accent]Whitelist: " + list);
                break;
        }
    }
}
