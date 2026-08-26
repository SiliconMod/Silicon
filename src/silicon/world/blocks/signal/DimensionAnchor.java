package silicon.world.blocks.signal;

import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

/**
 * 已废弃的维度锚点（兼容存根）。
 * 旧版信号系统（信号源 + 维度锚点）已移除，但直接删除该方块会破坏包含它的旧存档
 * （Mindustry 存档按方块 ID 存储，删除会使其后所有方块 ID 前移、内容错位）。
 * 因此保留本存根以维持原注册位置与方块 ID：
 * 无任何功能、不出现在建造菜单（BuildVisibility.hidden），仅可拆除以清理旧存档残留。
 */
public class DimensionAnchor extends Block {
    public DimensionAnchor(String name) {
        super(name);
        // 无功能：不更新、不可配置
        update = false;
        solid = true;
        destructible = true;
        breakable = true;
        // 隐藏于建造菜单（旧存档加载仍正常）
        buildVisibility = BuildVisibility.hidden;
    }
}
