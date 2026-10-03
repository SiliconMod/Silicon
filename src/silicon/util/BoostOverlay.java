package silicon.util;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;

/**
 * 强化信息按钮：把「生效中的强化」在目标方块左下角渲染为一个<b>可点击按钮</b>，
 * 点击后把该目标的强化详情投递到消息面板（{@link MessageSystem}），10 秒后自动消失。
 *
 * <p><b>按钮</b>：固定 0.5 格（4×4px）大小，锚在 footprint 左下角那一格的<b>内侧</b>（紧贴角、不越出方块）。
 * 外观为游戏风格：半透明底色 + 原版图标 + 亮色描边。
 * <p><b>按光标距离动态淡入（分段）</b>：不透明度取「光标到按钮中心距离」的分段线性值——
 * {@code ≤ 2 格}取上限 <b>80%</b>；{@code 2 ~ 10 格}线性衰减到 0；
 * 超出 <b>10 格</b>则<b>整颗按钮与图标都不渲染</b>（也不可点击）。
 * 只要目标身上有任意一个生效的 boost 就会绘制（多个效果合并为一个按钮）。
 *
 * <p><b>点击行为</b>：向消息面板投递一条 10s 时限消息——
 * 标题「{@code {方块名称}中生效的Boost}」，内容逐行列出「{@code 强化名称-强化效果}」。
 * 文案经 bundle 归类管理（见 {@code 建筑强化系统} 分区），标题用 {@code {0}} 占位符注入方块名。
 *
 * <p>其它约定：只绘制本地玩家同队的方块（多人下防止透视对手强化）、屏幕外目标跳过
 * （视野矩形每帧只算一次）。命中测试复用上一帧记录的按钮世界矩形，{@code Hit} 按索引复用、无每帧分配。
 * 接入方式：{@link #init()}（客户端加载时调用一次）把自身注册进 System 的
 * {@link BuildingBoostSystem#visualRenderer}，并挂载渲染与输入钩子。
 */
public class BoostOverlay implements BuildingBoostSystem.VisualRenderer {

    /** 一格边长（像素，Mindustry 1 tile = 8px） */
    private static final float tile = 8f;
    /** 按钮边长：0.5 格 */
    private static final float size = tile * 0.5f;
    /** 图标占按钮比例（其余留白） */
    private static final float iconRatio = 0.8f;
    /** 按钮底色（含 alpha，与消息气泡同色） */
    private static final Color buttonColor = Color.valueOf("99cc3366");
    /**
     * 按钮不透明度上限：光标进入 {@link #nearDistance} 内即取该值（80%），
     * 之后在 {@code [nearDistance, fadeDistance]} 上线性衰减到 0。
     * 整个按钮（白边 + 底板 + 图标）统一按此透明度绘制。
     */
    private static final float maxAlpha = 0.8f;
    /**
     * 满不透明度距离（世界像素）：光标到按钮中心的距离 ≤ 该值时，透明度恒为 {@link #maxAlpha}。
     */
    private static final float nearDistance = tile * 2f;
    /**
     * 淡出距离（世界像素）：光标到按钮中心的距离超过该值时<b>直接不渲染</b>按钮与图标
     * （也不可点击）。该距离内，透明度分段：{@code [0, nearDistance]} 取 {@link #maxAlpha}，
     * {@code [nearDistance, fadeDistance]} 线性降到 0。
     */
    private static final float fadeDistance = tile * 10f;
    /** 描边宽度（白边，内嵌法实现） */
    private static final float borderWidth = 0.4f;
    /** 消息显示时限（秒） */
    private static final float messageLife = 10f;
    /** 标题本地化 key：{0} 为方块名称（以 [accent] 强调） */
    private static final String titleKey = "boost.info.title";
    /** 单条强化行的格式 key：{0}=强化名（青色）{1}=强化效果 */
    private static final String lineKey = "boost.info.line";
    /** 消息气泡色（同时用作消失时间覆盖层色） */
    private static final Color bubbleColor = Color.valueOf("99cc3366");
    /** 复用实例 */
    private static final BoostOverlay instance = new BoostOverlay();
    /** 视野裁剪矩形：每帧算一次，供本帧所有按钮复用 */
    private static final Rect view = new Rect();
    /** 上一帧绘制出的按钮命中区（世界坐标），供本帧点击命中测试 */
    private static final Seq<Hit> hits = new Seq<>();
    /** 本帧已记录的按钮数：命中区槽位游标（<b>与 boost 序号 index 无关</b>，每帧从 0 重新计数）。 */
    private static int hitCursor = 0;

    private static boolean inited = false;

    private BoostOverlay() {
    }

    /** 按钮命中区（世界坐标矩形 + 目标），按索引复用避免每帧分配。 */
    private static class Hit {
        Building target;
        final Rect rect = new Rect();
    }

    public static void init() {
        if (inited) return;
        inited = true;
        // 无头服务器跳过（无渲染循环与 UI）
        if (Vars.headless) return;
        BuildingBoostSystem.visualRenderer = instance;
        // 渲染阶段：重置命中区 → 算视野 → 调度 System 绘制
        Events.run(EventType.Trigger.draw, () -> {
            hits.clear();
            hitCursor = 0;
            Core.camera.bounds(view);
            BuildingBoostSystem.drawBoosts();
        });
        // 输入阶段：按钮点击 → 投递强化详情到消息面板
        Events.run(EventType.Trigger.update, BoostOverlay::checkInput);
    }

    @Override
    public void render(Building target, BuildingBoostSystem.BoostVisual visual, int index) {
        if (target == null) return;
        // 图标统一：优先用效果自带的，缺失则回落到全局统一图标（BuildingBoostSystem.badgeIcon），
        // 保证「只要有任意强化生效就一定能看到徽记」，不会因某个效果忘了给图标而不显示
        TextureRegion icon = visual == null ? null : visual.icon(target);
        if (icon == null) icon = BuildingBoostSystem.badgeIcon();
        if (icon == null) return;
        // 只显示己方（多人下防止透视对手强化）
        if (Vars.player != null && target.team != Vars.player.team()) return;
        // 视野裁剪：屏幕外目标跳过（矩形本帧已算好）
        if (!view.contains(target.x, target.y)) return;
        // 多个效果合并为一个按钮（详情在消息面板里），故只画第一个
        if (index > 0) return;

        // footprint 左下角向右上一格，再取该格中心 → 按钮落在左下角格内侧，不越出方块
        float blockSize = target.block.size * tile;
        float x = target.x - blockSize / 2f + size / 2f;
        float y = target.y - blockSize / 2f + size / 2f;

        // 按「光标到按钮中心距离」动态淡入（分段）：超出淡出距离直接不渲染（自然也不可点击）
        float dist = Mathf.dst(Core.input.mouseWorldX(), Core.input.mouseWorldY(), x, y);
        if (dist >= fadeDistance) {
            return;
        }
        // 近段（≤ nearDistance）取满不透明度；远段在其余距离上线性降到 0
        float alpha = dist <= nearDistance
            ? maxAlpha
            : maxAlpha * (1f - (dist - nearDistance) / (fadeDistance - nearDistance));

        // 记录命中区（点击测试用）：与视觉尺寸一致（4×4px），故可见即可点。
        // 槽位用本帧游标分配，**不能用 index**（index 是 boost 在目标身上的序号，恒为 0，
        // 会让所有按钮共用一个槽位互相覆盖，最终只剩最后一个按钮可点）。
        Hit hit = hit(hitCursor++);
        hit.target = target;
        hit.rect.set(x - size / 2f, y - size / 2f, size, size);

        Color tint = visual.color();
        float prevZ = Draw.z();
        try {
            // 浮于方块之上，避免被建筑贴图盖住
            Draw.z(Layer.overlayUI);
            // 底框：先铺一层白色矩形作为描边底（arc 的 rect 无描边参数，用内嵌法做边框）
            Draw.color(Color.white, alpha);
            Draw.rect(Core.atlas.white(), x, y, size, size);
            // 底板：统一色（#99cc3366，与消息气泡同色），内缩一圈留出白边
            float inner = size - borderWidth * 2f;
            Draw.color(tint == null ? buttonColor : tint, alpha);
            Draw.rect(Core.atlas.white(), x, y, inner, inner);
            // 图标本体（原版美术，不额外染色以保持原味）
            Draw.color(Color.white, alpha);
            Draw.rect(icon, x, y, inner * iconRatio, inner * iconRatio);
        } finally {
            Draw.reset();
            Draw.z(prevZ);
        }
    }

    private static Hit hit(int i) {
        while (hits.size <= i) {
            hits.add(new Hit());
        }
        return hits.get(i);
    }

    // —— 点击交互：投递强化详情到消息面板 ——

    private static void checkInput() {
        if (Vars.state == null || !Vars.state.isGame()) return;
        if (!Core.input.keyTap(KeyCode.mouseLeft)) return;
        // 光标悬在 UI 元素上时不响应：本钩子跑在输入阶段（Trigger.update），
        // **早于 Scene 消费这次点击**，因此此刻还没人「吃掉」它——
        // 不加这道门禁，在建造菜单/消息面板上点到光标下的徽记矩形仍会投递消息。
        // 用无参 hasMouse()（= getHoverElement() != null，取上一帧绘制时的悬停元素）；
        // 不可用 hasMouse(x, y)：那个重载走 Scene.hit(x, y)，要求的是 **控件局部坐标**，
        // 传世界坐标会得到无意义的结果。
        if (Core.scene.hasMouse()) return;
        float wx = Core.input.mouseWorldX(), wy = Core.input.mouseWorldY();
        for (Hit hit : hits) {
            if (!hit.rect.contains(wx, wy)) continue;
            // 命中区是上一帧记录的（建筑静止故世界坐标稳定）；投递前再校验目标仍然有效且仍有强化，
            // 避免点空或点到已失效的残留条目。
            // 注意：失效条目必须 continue 而非 return —— 否则一个恰好盖住光标的失效条目
            // 会吞掉这次点击，其后真正命中的强化按钮再也点不到（多个徽记密集时尤其明显）。
            Building target = hit.target;
            if (target == null || !target.isValid() || !BuildingBoostSystem.hasActiveBoosts(target)) {
                continue;
            }
            postBoostInfo(target);
            return;
        }
    }

    /**
     * 把目标的强化详情投递到消息面板：标题「[accent]{方块名}[]中生效的Boost」，
     * 内容逐行「[cyan]{强化名}[]：{强化效果}」，气泡色 #99cc3366，10 秒后消失。
     */
    private static void postBoostInfo(Building target) {
        if (target == null || target.block == null) return;
        Seq<BuildingBoostSystem.Boost> boosts = BuildingBoostSystem.activeBoosts(target);
        if (boosts.isEmpty()) return;

        // 内容：每个生效 boost 一行，行格式由 bundle 管理（名称青色 + 全角冒号分隔）
        // 用 name(target)/description(target) 而非无参版：多档位效果（节能/超频）据此
        // 只报「名称+档位」与「该档的加成」，不会把三档全列出来
        StringBuilder content = new StringBuilder();
        for (BuildingBoostSystem.Boost boost : boosts) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(Core.bundle.format(lineKey, boost.name(target), boost.description(target)));
        }

        // 标题用本地化 key + {0} 占位符注入方块名（方块名以 [accent] 强调）
        // 图标用该建筑自身的贴图（uiIcon），便于一眼看出是哪个方块上的强化
        MessageSystem.instance.post(MessageSystem.info("", content.toString(), messageLife)
                .titleKey(titleKey)
                .var(target.block.localizedName)
                .background(bubbleColor)
                .icon(blockIcon(target))
                .local()); // 仅本地：只有点击者自己看到，不广播给其他玩家
    }

    /** 取建筑图标（uiIcon）作为消息图标；缺失时回退默认信息图标。 */
    private static Drawable blockIcon(Building target) {
        TextureRegion region = target.block != null ? target.block.uiIcon : null;
        if (region == null) {
            return Icon.info;
        }
        // 显式染白：uiIcon 自带描边/底色，避免被消息面板配色二次染色导致偏色
        return new TextureRegionDrawable(region).tint(Color.white);
    }
}
