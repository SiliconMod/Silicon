package silicon.world.meta;

import arc.math.Mathf;

/**
 * 信号类：由信号源注册并在一定范围内广播，绑定所属队伍。
 * 拥有两个属性：信号名称（4 个字母或数字）与信号强度（0~99，标度上限见
 * {@link silicon.world.blocks.signal.SignalSource#MAX_STRENGTH}）。
 */
public class Signal {
    /** 信号名称：4 个字母或数字 */
    public final String name;
    /** 逐格合成用的身份串（"S" + 名称）：H 覆盖每帧数万格、每格都要用它做身份比较，
     *  必须在构造时算一次——原来的实现在热路径里现拼，每帧几十万次临时字符串。 */
    public final String id;
    /** 信号强度：源强度（0~99），在信号源覆盖半径内随距离对数衰减 */
    public int strength;

    public Signal(String name) {
        this(name, silicon.world.blocks.signal.SignalSource.MAX_STRENGTH);
    }

    public Signal(String name, int strength) {
        this.name = name;
        this.id = "S" + name;
        this.strength = Mathf.clamp(strength, 0, silicon.world.blocks.signal.SignalSource.MAX_STRENGTH);
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * 编码格式校验（**唯一定义**）：4 位大写字母或数字。
     * 信号源/中继器/卫星控制台的 configure 通道与读档都走它——这些字符串会作为 key 进入
     * {@code SignalChannel.liveSrcCache}、覆盖绘制的颜色缓存与各处 UI，不校验会让畸形输入长期驻留。
     */
    public static boolean isValidCode(String code) {
        if (code == null || code.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = code.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
