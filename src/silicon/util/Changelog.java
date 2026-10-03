package silicon.util;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;

/**
 * 模组更新日志：每次启动必弹（供调试方便观察）。
 * <p>
 * - 存在比「上次见过版本」(seen) 更新的条目 → 弹新版本日志；
 * - 否则 → 弹上次见过的版本日志。
 * <p>
 * 每条以黄线分隔，从左到右：物品贴图 | 物品名字 | 改动内容（自动换行）。
 */
public class Changelog {
    private static float dialogScl() {
        return Mathf.clamp(Math.min(Math.max(Core.graphics.getWidth(), 1f) / 1920f,
                Math.max(Core.graphics.getHeight(), 1f) / 1080f), 0.5f, 1.6f);
    }

    public static class Entry {
        public final String version;
        public final String item;
        public final String desc;

        public Entry(String version, String item, String desc) {
            this.version = version;
            this.item = item;
            this.desc = desc;
        }
    }

    private static final Entry[] ENTRIES = {
        new Entry("a0.12.2.0", "universal-junction", "配置界面重构：拖拽换位、动态分组、预设与保存"),
        new Entry("a0.12.1.0", "switch", "开关按钮改为切换式（返回式→按下切换开/关）"),
        new Entry("a0.12.1.0", "power-protector", "恢复期保持电网连接，防止中枢网跳变"),
        new Entry("a0.12.1.0", "item-transfer-hub", "修复连接线存档重载后不渲染"),
        new Entry("a0.12.1.0", "dimension-anchor", "修复接收态释放物品按钮失效"),
    };

    private static final String SEEN_KEY = "silicon.changelog.seen";
    /** 方块图标缓存：避免每次弹窗对每个条目重复 Vars.content.blocks() 线性查找 */
    private static final ObjectMap<String, TextureRegion> iconCache = new ObjectMap<>();

    /**
     * 每次启动必弹：
     * - 存在比「上次见过版本」(seen) 更新的版本时 → 弹该新版本的改动内容；
     * - 否则（无新版本）→ 弹当前最新版本的改动内容。
     */
    public static void checkAndShow() {
        String current = UpdateChecker.currentVersion();
        if (current.isEmpty()) return;
        String seen = Core.settings.getString(SEEN_KEY, "");

        // 收集所有 ≤ 当前版本的条目（用于确定目标版本；保持 ENTRIES 旧→新顺序）
        Seq<Entry> valid = new Seq<>();
        for (Entry e : ENTRIES) {
            if (UpdateChecker.isNewer(e.version, current)) continue;
            boolean dup = false;
            for (Entry p : valid) {
                if (p.version.equals(e.version)) { dup = true; break; }
            }
            if (!dup) valid.add(e);
        }
        if (valid.isEmpty()) return;

        // 目标版本：优先取比 seen 新的最高版本；无则取当前最高版本
        String target = null;
        for (Entry e : valid) {
            if (seen.isEmpty() || UpdateChecker.isNewer(e.version, seen)) target = e.version;
        }
        if (target == null) target = valid.get(valid.size - 1).version;

        Seq<Entry> show = new Seq<>();
        for (Entry e : ENTRIES) {
            if (e.version.equals(target)) show.add(e);
        }
        if (show.isEmpty()) return;

        Core.settings.put(SEEN_KEY, target);
        Core.app.post(() -> showDialog(show));
    }

    private static void showDialog(Seq<Entry> entries) {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("changelog.title"));
        dialog.cont.top();

        float scl = dialogScl();

        Table list = new Table();
        list.top();

        String version = entries.first().version;
        list.add(version).color(Pal.accent).left().padBottom(6f);
        list.row();
        list.image(Tex.whitePane, Color.yellow).growX().height(2f).padBottom(4f);
        list.row();

        for (int i = 0; i < entries.size; i++) {
            Entry e = entries.get(i);

            list.table(row -> {
                // 优先取 mod 方块（silicon-<内部名>），再回退任何其后缀匹配的方块；结果按 item 名缓存
                TextureRegion icon = iconCache.get(e.item, () -> {
                    Block block = Vars.content.blocks().find(b -> b.name.equals("silicon-" + e.item));
                    if (block == null) block = Vars.content.blocks().find(b -> b.name.endsWith("-" + e.item));
                    return block != null ? block.uiIcon : null;
                });
                if (icon != null) {
                    row.image(icon).size(48f * scl).padRight(10f).padTop(2f);
                }
                String display = Core.bundle.getOrNull("block.silicon-" + e.item + ".name");
                row.add(display != null ? display : e.item).color(Pal.accent).left().padRight(12f);
                row.add(e.desc).growX().left().wrap().padBottom(4f);
            }).fillX().pad(4f);
            list.row();

            list.image(Tex.whitePane, Color.yellow).growX().height(2f).padBottom(4f);
            list.row();
        }

        float pw = Math.min(Core.graphics.getWidth() * 0.75f, 820f * scl);
        dialog.cont.pane(list).growX().width(pw).maxHeight(420f * scl).padTop(6f);
        dialog.buttons.button("@updatecheck.close", Icon.cancel, dialog::hide)
                .size(210f * scl, 60f * scl).pad(10f);
        dialog.closeOnBack();
        dialog.show();
    }
}
