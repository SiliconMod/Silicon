package silicon.world.blocks.signal;

import mindustry.world.meta.BuildVisibility;

/**
 * 单格信号探针（**调试方块**，仅沙盒可见）：与信号源完全同源，只把 {@link #radius} 压到 0.5 格——
 * 覆盖判定用 {@code dist >= radius} 归零，所以只有探针自己那一格的中心（dist = 0）在覆盖内，
 * 四邻格中心距离 1 格 &gt; 0.5 格，强度为 0。于是按住 H 后**全图只会有这一格显示数字**，
 * 用来核对"H 覆盖的数字是否正落在格子中心"（对齐/锚点问题一眼可见，不受地形与其它信号干扰）。
 *
 * <p>用法：沙盒建造菜单 → 效果 → 信号探针（调试）→ 放置 → 点开设置一个 4 位编码 → 按住 H。
 * 该格应显示 99（中心满值），方块贴图自带十字准星，数字中心应与十字中心重合。
 *
 * <p>不耗电（{@code consPower = null}，并覆写掉父类里"必须有电才发射"的闸门）、无材料需求。
 * 与 {@code message-test} 一样属于随仓库维护的调试方块；不需要时删掉本类 + Blocks 里一行注册即可。
 */
public class SignalProbe extends SignalSource {

    public SignalProbe(String name) {
        super(name);
        radius = 0.5f;              // 恰好覆盖自己那一格（中心 dist=0 有效，四邻 1 格 >= 0.5 无效）
        consPower = null;           // 清掉父类的 150/s 功耗：纯测量用，不强依赖电网
        buildType = SignalProbeBuild::new;
        buildVisibility = BuildVisibility.sandboxOnly;
    }

    public class SignalProbeBuild extends SignalSourceBuild {
        /** 与父类唯一差别：去掉"必须有电"闸门（探针不接电网），其余口径完全一致 */
        @Override
        public float strengthAt(float wx, float wy) {
            if (signal == null || !enabled) return 0f;
            return SignalSource.strengthAt(x, y, wx, wy, radius());
        }

        @Override
        public boolean emitting() {
            return signal != null && enabled;
        }
    }
}
