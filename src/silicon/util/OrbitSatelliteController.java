package silicon.util;

import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.UnitController;
import mindustry.gen.Unit;
import silicon.world.blocks.satellite.SatelliteConsole;

/**
 * 卫星轨迹控制器：星下点位置是"存档相位（自累加）的纯函数"，每帧直接覆写、速度清零——
 * - 轨迹为拟真星下点模型（SatelliteManager.scanX/scanY）：
 *   LEO/MEO 沿经度东西向匀速回绕、纬度正弦摆动（真实 LEO 地面轨迹形态），
 *   SSO 沿纬度南北向回绕（极轨），GEO 定点悬停于发射方位角（地球静止）；
 *   波形按 (1+漂移比) 失谐推进 → 相邻两圈轨迹错开，轨迹族随时间铺满全图（含四角）。
 * - 相位自累加并存在名册里（存档字段），控制器本身无外部状态：读档后经 UnitType.aiController
 *   工厂重建（轨道参数来自机型），从存档相位精确续接，不跳位（也不依赖 Time.time 是否被重置）；
 * - 只在服务端/单机执行：引擎对客机不调用 controller.updateUnit()（UnitComp.java:838-841），
 *   客机由单位同步 + 插值得到位置；
 * - 位置覆写 + 零速度 ⇒ 物理推挤被即刻清除（叠加 hittable=false 的零碰撞对，双保险）；
 * - unit.rotation 取轨迹切线方向（解析导数），纯装饰；GEO 定点不更新朝向。
 * <p>
 * UnitController 是接口（UnitController.java:6，只有 unit()/unit(Unit)/updateUnit() 等），
 * 因此本类自行持有 unit 引用并覆写 updateUnit()（UnitComp.java:840 每帧调用）。
 * <p>
 * 目标选择（未来武器卫星）：显式排除卫星类型是层间互不攻击的代码保证——
 * 当前机型无武器，此控制器只做轨迹运动。
 */
public class OrbitSatelliteController implements UnitController {
    /** 本机型对应的发射轨道（SatelliteConsole.ORBIT_*），决定轨迹形态与覆盖半径 */
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
        // 名册未就绪（读档时序/旧档名册丢失）：本帧悬停，节流触发全局对账补建记录后恢复运动
        SatelliteManager.SatelliteRecord rec = SatelliteManager.recordOf(u.id);
        if (rec == null) {
            SatelliteManager.reconcileMissing();
            return;
        }

        // 星下点轨迹（纯函数，输入只有存档相位）：GEO 定点悬停，其余沿主轴回绕 + 正弦摆动。
        // 相位是自累加的存档字段（+= delta/周期），因此位置与 Time.time 这类全局时钟无关——
        // 引擎读档不会重置 Time.time（arc Time.clear() 只清 runs），若用「相位 + 全局时钟/周期」
        // 结算，同一次会话内读档会多绕「存档时运行时长/周期」圈（实测踩坑）。
        if (orbit != SatelliteConsole.ORBIT_GEO) {
            rec.phase += Time.delta / SatelliteManager.orbitPeriod(orbit);
        }
        u.set(SatelliteManager.scanX(rec), SatelliteManager.scanY(rec));
        u.vel.set(0f, 0f);
        if (orbit == SatelliteConsole.ORBIT_GEO) return; // 定点：朝向不更新
        // 切线方向（装饰）：星下点轨迹的解析导数（EW：dx 恒定、dy 余弦；SSO 对偶）
        float T = SatelliteManager.orbitPeriod(orbit);
        float wave = Mathf.PI2 * (1f + SatelliteManager.SCAN_DRIFT) * SatelliteManager.scanU(rec);
        float dx, dy;
        if (orbit == SatelliteConsole.ORBIT_SSO) {
            dx = (Vars.world.unitWidth() / 2f - SatelliteManager.SCAN_MARGIN) * Mathf.PI2
                    * (1f + SatelliteManager.SCAN_DRIFT) / T * Mathf.cos(wave);
            dy = Vars.world.unitHeight() / T;
        } else {
            dx = Vars.world.unitWidth() / T;
            dy = (Vars.world.unitHeight() / 2f - SatelliteManager.SCAN_MARGIN) * Mathf.PI2
                    * (1f + SatelliteManager.SCAN_DRIFT) / T * Mathf.cos(wave);
        }
        u.rotation = Mathf.atan2(dy, dx) * 180f / Mathf.pi;
    }
}
