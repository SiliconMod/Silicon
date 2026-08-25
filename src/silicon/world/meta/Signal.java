package silicon.world.meta;

import arc.math.Mathf;

/**
 * 信号类：由信号源注册并在一定范围内广播，绑定所属队伍。
 * 拥有两个属性：信号名称（4 个字母或数字）与信号强度（0~15）。
 */
public class Signal {
    /** 信号名称：4 个字母或数字 */
    public final String name;
    /** 信号强度：源强度（0~15），在信号源覆盖半径内随距离衰减 */
    public int strength;

    public Signal(String name) {
        this(name, 15);
    }

    public Signal(String name, int strength) {
        this.name = name;
        this.strength = Mathf.clamp(strength, 0, 15);
    }

    @Override
    public String toString() {
        return name;
    }
}
