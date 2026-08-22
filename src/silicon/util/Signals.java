package silicon.util;

import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import silicon.world.blocks.signal.SignalSource;

/**
 * Central, team-aware helpers for the signal system.
 *
 * <p>Signals are provided by {@link SignalSource} blocks and are scoped to a {@link Team}:
 * each team has its own independent set of signals, so different teams may use the same
 * signal string without interfering. Blocks that consume a signal implement {@link SignalUser}
 * so they are automatically found and counted here.
 *
 * <p>All methods scan the live world ({@link Groups#build}) so results are always up to date
 * and stay correct in multiplayer and after loading saves.
 *
 * <p>Typical use from a future block:
 * <pre>
 * // is the signal "A1G4" currently active (a powered source exists) for my team?
 * boolean on = Signals.isActive("A1G4", team);
 *
 * // list every signal my team currently has available:
 * Seq&lt;String&gt; all = Signals.signalsFor(team);
 *
 * // who else is using my signal (any block implementing SignalUser)?
 * Seq&lt;SignalUser&gt; users = Signals.users("A1G4", team);
 * </pre>
 */
public class Signals{

    /** @return all signal sources that provide this signal for the given team (any power state). */
    public static Seq<SignalSource.SignalSourceBuild> sources(String signal, Team team){
        Seq<SignalSource.SignalSourceBuild> out = new Seq<>();
        if(signal == null || team == null) return out;
        for(Building b : Groups.build){
            if(b instanceof SignalSource.SignalSourceBuild sb && sb.team == team && signal.equals(sb.signal)){
                out.add(sb);
            }
        }
        return out;
    }

    /** @return all signal sources belonging to the given team. */
    public static Seq<SignalSource.SignalSourceBuild> sources(Team team){
        Seq<SignalSource.SignalSourceBuild> out = new Seq<>();
        if(team == null) return out;
        for(Building b : Groups.build){
            if(b instanceof SignalSource.SignalSourceBuild sb && sb.team == team){
                out.add(sb);
            }
        }
        return out;
    }

    /** @return the first source providing this signal for the team, or null if none. */
    public static @Nullable SignalSource.SignalSourceBuild source(String signal, Team team){
        if(signal == null || team == null) return null;
        for(Building b : Groups.build){
            if(b instanceof SignalSource.SignalSourceBuild sb && sb.team == team && signal.equals(sb.signal)){
                return sb;
            }
        }
        return null;
    }

    /** @return the first powered source providing this signal for the team, or null if none. */
    public static @Nullable SignalSource.SignalSourceBuild activeSource(String signal, Team team){
        if(signal == null || team == null) return null;
        for(Building b : Groups.build){
            if(b instanceof SignalSource.SignalSourceBuild sb && sb.team == team && signal.equals(sb.signal) && sb.isActive()){
                return sb;
            }
        }
        return null;
    }

    /** @return whether a powered signal source currently provides this signal for the team. */
    public static boolean isActive(String signal, Team team){
        return activeSource(signal, team) != null;
    }

    /**
     * @return all distinct signals currently available to the given team.
     * A signal is available as soon as a source with that signal exists (power is not required).
     */
    public static Seq<String> signalsFor(Team team){
        Seq<String> out = new Seq<>();
        if(team == null) return out;
        for(Building b : Groups.build){
            if(b instanceof SignalSource.SignalSourceBuild sb && sb.team == team && sb.signal != null && !out.contains(sb.signal)){
                out.add(sb.signal);
            }
        }
        return out;
    }

    /**
     * @return every {@link SignalUser} on the given team that is bound to this signal.
     * New signal-consuming blocks just need to implement {@link SignalUser}.
     */
    public static Seq<SignalUser> users(String signal, Team team){
        Seq<SignalUser> out = new Seq<>();
        if(signal == null || team == null) return out;
        for(Building b : Groups.build){
            if(b instanceof SignalUser user && b.team == team && signal.equals(user.signal())){
                out.add(user);
            }
        }
        return out;
    }

    /** @return how many {@link SignalUser}s on the given team are bound to this signal. */
    public static int countUsers(String signal, Team team){
        return users(signal, team).size;
    }
}
