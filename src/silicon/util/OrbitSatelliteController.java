package silicon.util;

import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.UnitController;
import mindustry.gen.Unit;

/**
 * 卫星轨道控制器：位置是"相位 + 时间的纯函数"，每帧直接覆写、速度清零——
 * - 无状态：读档后经 UnitType.aiController 工厂重建（轨道参数来自机型），卫星从存档相位续接，不跳位；
 * - 服务端/客户端/无头端同式计算，确定性一致（引擎单位同步做兜底校正）；
 * - 位置覆写 + 零速度 ⇒ 物理推挤被即刻清除（叠加 hittable=false 的零碰撞对，双保险）；
 * - unit.rotation 取轨道切线方向，纯装饰。
 * <p>
 * UnitController 是接口（UnitController.java:6，只有 unit()/unit(Unit)/updateUnit() 等），
 * 因此本类自行持有 unit 引用并覆写 updateUnit()（UnitComp.java:840 每帧调用）。
 * <p>
 * 目标选择（未来武器卫星）：显式排除卫星类型是层间互不攻击的代码保证——
 * 当前机型无武器，此控制器只做轨道运动。
 */
public class OrbitSatelliteController implements UnitController {
    /** 本机型对应的发射轨道（SatelliteConsole.ORBIT_*），决定轨道半径与周期 */
    public final int orbit;
    private Unit unit;

    public OrbitSatelliteController(int orbit) {
        this.orbit = orbit;
    }

    @Override
    public Unit unit() {
        return unit;
    }

    @Override
    public void unit(Unit unit) {
        this.unit = unit;
    }

    @Override
    public void updateUnit() {
        Unit u = unit;
        if (u == null) return;
        // 名册未就绪（读档窗口/极端时序）：原地悬停，等 onWorldLoaded 对账补建记录
        SatelliteManager.SatelliteRecord rec = SatelliteManager.recordOf(u.id);
        if (rec == null) return;

        float cx = Vars.world.unitWidth() / 2f, cy = Vars.world.unitHeight() / 2f;
        float pathR = SatelliteManager.orbitPathRadius(orbit, cx, cy);
        float ang = SatelliteManager.currentAngle(rec);
        u.set(cx + Mathf.cos(ang) * pathR, cy + Mathf.sin(ang) * pathR);
        u.vel.set(0f, 0f);
        u.rotation = ang * 180f / Mathf.pi + 90f; // 切线方向（纯装饰）
    }
}
