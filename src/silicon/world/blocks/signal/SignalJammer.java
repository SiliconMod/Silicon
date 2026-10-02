package silicon.world.blocks.signal;

import arc.Core;
import arc.math.Mathf;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.game.Team;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.meta.Stat;

/**
 * 信号干扰器（1×1）：在指定信道（1~5，或全信道 ALL）发射压制噪声。
 * 干扰强度与信号强度**同一模型**（{@link SignalSource#strengthAt}：半径 15 格、对数衰减 0~99），按 SINR 比值制进入信噪比分母：
 * 干扰抬高 I（底噪 + 同信道 CCI + 邻信道 ACIR + 干扰器功率），目标信号质量因子随之下降；
 * SINR ≤ 1（功率压不过底噪+干扰）时该处无信号（H 覆盖中不显示）。
 * 多台干扰器在同一信道的功率**叠加**（{@link SignalChannel#jammerAt}），单台不再各算各的最大值。
 */
public class SignalJammer extends Block {
    /** 全信道模式值 */
    public static final int ALL = -1;
    /** 信道范围（1~5，共 5 个信道） */
    public static final int CHANNEL_MAX = 5;

    public SignalJammer(String name) {
        super(name);
        buildType = SignalJammerBuild::new;
        size = 1;
        solid = true;
        destructible = true;
        update = true;
        configurable = true;
        // 下界必须是「全信道(-1)」，否则 0 会漏进来：信道 0 不在 1~5 里，占用计数（SignalSpectrum 按
        // jamChannel == ch 统计）永远看不到它，而 addJammer 会按"信道 0"把 40%/12% 泄漏给信道 1/2
        // ——静默压制却无法从面板诊断。这里把 -1 之外的值一律夹到 [1, CHANNEL_MAX]。
        config(Integer.class, (SignalJammerBuild b, Integer v) ->
                b.jamChannel = (v == ALL) ? ALL : Mathf.clamp(v, 1, CHANNEL_MAX));
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.powerRange, SignalSource.RADIUS + " tiles");
    }

    /** 干扰器缓存（全局：干扰信道是跨队共享频段——任何队伍的干扰器都压制范围内的同信道信号，不分敌我） */
    private static final Seq<SignalJammerBuild> jammerList = new Seq<>();
    private static boolean dirty = true;

    public static void markDirty() {
        dirty = true;
    }

    static void rebuildCache() {
        if (!dirty) return;
        dirty = false;
        jammerList.clear();
        for (Building b : Groups.build) {
            // removed 过滤：onRemoved 早于建筑离开 Groups.build（引擎里 onRemoved 是 Tile 处理旧建筑的
            // 第一步），重建若恰好落在那个窗口内，死干扰器会被写进缓存。
            if (b instanceof SignalJammerBuild jb && !jb.removed) {
                jammerList.add(jb);
            }
        }
    }

    /** 全部干扰器（走缓存，跨队伍）。顺带自愈剔除已拆除的（与 SignalSource.allSources 同款防护） */
    public static Seq<SignalJammerBuild> allJammers() {
        rebuildCache();
        for (int i = jammerList.size - 1; i >= 0; i--) {
            if (jammerList.get(i).removed) jammerList.remove(i);
        }
        return jammerList;
    }

    /** 位置 (wx,wy) 处、指定信道受到的干扰总和（0~99 量级；同信道 + 邻信道泄漏，多台干扰器叠加；
     *  关闭的干扰器不干扰；不分队伍——敌方干扰器同样压制我方该信道信号）。
     *  实现委托 {@link SignalChannel#jammerAt}：与地面层（effectiveAll）**同一算法**，
     *  避免卫星层取最强、地面层求和的旧口径分叉。 */
    public static float strengthAt(int channel, float wx, float wy) {
        return SignalChannel.jammerAt(channel, wx, wy);
    }

    public class SignalJammerBuild extends Building {
        /** 干扰信道（1~5，-1=全信道） */
        public int jamChannel = ALL;
        /** 是否已进入拆除流程（onRemoved 置位）：拆除瞬间重建缓存不得再把本干扰器算进去 */
        public boolean removed;

        @Override
        public void onProximityAdded() {
            super.onProximityAdded();
            removed = false;
            SignalJammer.markDirty();
        }

        @Override
        public void onRemoved() {
            super.onRemoved();
            removed = true;
            jammerList.remove(this, true);
            SignalJammer.markDirty();
        }

        @Override
        public void changeTeam(Team next) {
            super.changeTeam(next);
            // 夺取/换队：干扰信道归属变化，立即失效缓存（与增删同理）
            SignalJammer.markDirty();
        }

        /** 配置面板：信道选择（1~5 + 全信道，灰底面板） */
        @Override
        public void buildConfiguration(Table table) {
            table.clearChildren();
            table.top();
            table.table(Styles.grayPanel, t -> {
                t.top();
                // 标题跨满整行（含全信道共 6 个按钮）、居中、原版黄色（避免挤占首列导致按钮间距不均）
                t.add(Core.bundle.get("block.silicon-signal-jammer.channel")).colspan(CHANNEL_MAX + 1).center()
                        .color(mindustry.graphics.Pal.accent).pad(2f);
                t.row();
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                TextButton allBtn = new TextButton(Core.bundle.get("block.silicon-signal-jammer.all"), Styles.flatTogglet);
                allBtn.setChecked(jamChannel == ALL);
                allBtn.clicked(() -> configure(ALL));
                group.add(allBtn);
                t.add(allBtn).size(68f, 40f).pad(1f);
                for (int i = 1; i <= CHANNEL_MAX; i++) {
                    TextButton btn = new TextButton(String.valueOf(i), Styles.flatTogglet);
                    btn.setChecked(jamChannel == i);
                    int ch = i;
                    btn.clicked(() -> configure(ch));
                    group.add(btn);
                    t.add(btn).size(44f, 40f).pad(1f);
                }
            }).pad(4f);
        }

        /** 选中显示：干扰信道 */
        @Override
        public void display(Table table) {
            super.display(table);
            table.row();
            table.label(() -> Core.bundle.format("block.silicon-signal-jammer.channel.current",
                    jamChannel == ALL ? Core.bundle.get("block.silicon-signal-jammer.all") : String.valueOf(jamChannel))).pad(2f);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(jamChannel);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            // 越界值会让该干扰器永远匹配不到任何信道（静默失效）——读档时夹取到合法范围；
            // 0 同样不合法（见 config 的说明），一律归到信道 1 而不是留在 1~5 之外
            int v = read.i();
            jamChannel = (v == ALL) ? ALL : Mathf.clamp(v, 1, CHANNEL_MAX);
        }
    }
}
