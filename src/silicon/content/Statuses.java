package silicon.content;

import mindustry.type.StatusEffect;

/** 自定义状态效果（buff）。 */
public class Statuses {
    /** 卫星在轨 buff：仅作显示（图标+名称），无属性效果 */
    public static StatusEffect satelliteBuff;

    public static void load() {
        satelliteBuff = new StatusEffect("satellite-buff") {{
            // 无属性：仅作为发射卫星后的常驻显示
        }};
    }
}
