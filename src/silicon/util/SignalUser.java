package silicon.util;

/**
 * Implemented by any building that can be bound to a signal.
 *
 * <p>Once a block implements this interface, the {@link Signals} helper automatically finds
 * and counts it when querying who uses a given signal — no other registration is needed.
 *
 * <p>Example: a future block that consumes a signal just needs
 * {@code public class MyBuild extends Building implements SignalUser} plus
 * {@code public String signal(){ return mySignal; }}.
 */
public interface SignalUser{
    /** @return the signal this block is bound to, or null if it has none. */
    String signal();
}
