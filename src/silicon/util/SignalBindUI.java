package silicon.util;

import arc.Core;
import arc.func.Cons;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.game.Team;
import mindustry.ui.Styles;
import silicon.world.blocks.signal.SignalSource;

/**
 * 信号绑定选择器：搜索框 + 编码按钮网格 + 清除。
 * <p>
 * 交互与卫星控制台的信号选择保持一致（同一套 {@link SignalSource} 列表与
 * {@link Styles#flatTogglet} 按钮），供**卫星定位器**与**反卫星拦截塔**共用——
 * 这两者原先按队伍自动共享情报，现在改为通过信号编码配对：
 * <ul>
 *   <li>定位器：把探测结果发布到它绑定的编码；</li>
 *   <li>拦截塔：只读取它绑定的编码的情报。</li>
 * </ul>
 * 两侧绑定同一编码即建立连接，一个定位器可以服务多座塔（传感器共享）。
 */
public class SignalBindUI {
    private static final int PER_ROW = 5;

    /**
     * 在 table 内构建完整选择器。
     *
     * @param current  提供当前绑定的编码（null = 未绑定）
     * @param onSelect 选中回调；收到 null 表示清除绑定
     */
    public static void build(Table table, Team team, Prov<String> current, Cons<String> onSelect) {
        Table grid = new Table();
        table.field("", text -> rebuild(grid, team, current.get(), onSelect, text.trim()))
                .width(240f).padTop(2f).get()
                .setMessageText(Core.bundle.get("block.silicon-signal-bind.search"));
        table.row();
        table.add(grid).pad(2f).row();
        table.button(Core.bundle.get("block.silicon-signal-bind.clear"), Styles.defaultt,
                () -> onSelect.get(null)).size(160f, 36f).pad(2f).row();
        rebuild(grid, team, current.get(), onSelect, "");
    }

    /** 重建编码按钮网格（按搜索串模糊过滤；无匹配显示提示） */
    static void rebuild(Table grid, Team team, String current, Cons<String> onSelect, String filter) {
        grid.clearChildren();
        grid.center();
        Seq<SignalSource.SignalSourceBuild> srcs = SignalSource.allSources(team);
        boolean any = false;
        // 允许未选择信号：默认 minCheckCount=1 会在 add() 时强制勾选第一个按钮
        ButtonGroup<TextButton> group = new ButtonGroup<>();
        group.setMinCheckCount(0);
        int count = 0;
        String needle = filter.toLowerCase();
        for (SignalSource.SignalSourceBuild sb : srcs) {
            String code = sb.signal == null ? "----" : sb.signal.name;
            if (!needle.isEmpty() && !code.toLowerCase().contains(needle)) continue;
            any = true;
            TextButton btn = new TextButton(code, Styles.flatTogglet);
            btn.setChecked(code.equals(current));
            btn.clicked(() -> {
                onSelect.get(code);
                rebuild(grid, team, code, onSelect, filter);
            });
            group.add(btn);
            grid.add(btn).size(88f, 40f).pad(1f);
            if (++count % PER_ROW == 0) grid.row();
        }
        if (!any) {
            grid.add(Core.bundle.get("block.silicon-signal-bind.none")).color(Color.lightGray).pad(2f);
        }
    }
}
