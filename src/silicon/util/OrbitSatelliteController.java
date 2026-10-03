package silicon.util;

import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.AIController;
import mindustry.gen.Unit;
import silicon.world.blocks.satellite.SatelliteConsole;

/**
 * 卫星轨迹控制器：星下点位置是"存档相位（自累加）的纯函数"，每帧直接覆写、速度清零——
 * - 轨迹为拟真星下点模型（SatelliteManager.scanX/scanY）：
 *   LEO/MEO 沿经度东西向匀速回绕、纬度正弦摆动（真实 LEO 地面轨迹形态），
 *   SSO 沿纬度南北向回绕（极轨），GEO 定点悬停于发射方位角（地球静止）；
 *   波形按 (1+漂移比) 失谐推进 → 相邻两圈轨迹错开，轨迹族随时间铺满全图（含四角）。
 * - 相位自累加并存在名册里（存档字段），控制器本身无外部状态：读档后经 UnitType.controller
 *   工厂重建（轨道参数来自机型），从存档相位精确续接，不跳位（也不依赖 Time.time 是否被重置）；
 * - 位置覆写 + 零速度 ⇒ 物理推挤被即刻清除（叠加 hittable=false 的零碰撞对，双保险）；
 * - unit.rotation 取轨迹切线方向（解析导数），纯装饰；GEO 定点不更新朝向。
 * <p>
 * <b>为什么继承 AIController</b>：引擎的 possess 判定（InputHandler.java:783）要求
 * {@code unit.isAI()}，而 {@code isAI()} 的实现是 {@code controller instanceof AIController}
 * （UnitComp.java:494）。原先本类只 implement UnitController，isAI() 恒假、玩家按 Ctrl 点不进来。
 * 继承后 isAI() 为真，possess 流程放行。
 * <p>
 * {@link #updateUnit()} 覆写且**不调 super**：AIController 的实现会依次跑 updateVisuals /
 * updateTargeting / updateMovement（作战单位的索敌与移动），卫星的运动完全由轨迹函数决定，
 * 不需要那三步，调了反而会引入多余的朝向与目标逻辑。
 * <p>
 * 目标选择（武器卫星）：显式排除卫星类型是层间互不攻击的代码保证——当前机型无武器。
 */
public class OrbitSatelliteController extends AIController {
    /** 本机型对应的发射轨道（SatelliteConsole.ORBIT_*），决定轨迹形态与覆盖半径 */
    public final int orbit;

    /** 空 unit 告警节流计数器（见 {@link #applyMotion} 内的说明） */
    private static int nullUnitWarn;

    public OrbitSatelliteController(int orbit) {
        this.orbit = orbit;
    }

    // unit() / unit(Unit) 由父类 AIController 提供（AIController.java:439/447），不再自己持有引用

    @Override
    public void updateUnit() {
        // 不调 super，见类注释
        applyMotion(unit, orbit);
    }

    /**
     * 轨迹运动（静态方法，两个调用点共用同一套逻辑）：
     * <p>
     * ① **未被接管**：本控制器每帧走这里。引擎只在服务端/单机调用 controller.updateUnit()
     *    （UnitComp.java:859-860 的 {@code !net.client()} 守卫），客机靠单位同步 + 插值取位置。
     * <p>
     * ② **被玩家接管**：controller 已被替换为 {@code Player} 本身
     *    （PlayerComp.java:328 的 {@code unit.controller(this)}），本控制器**不再被驱动**，
     *    updateUnit() 不会被调用。接管期的轨迹由 SatelliteUnits 里覆写的
     *    {@code UnitType.update(Unit)} 调用本方法驱动（UnitComp.java:665 无条件每帧调用，
     *    早于同方法内 L860 的 controller.updateUnit()）。
     *    两条路径由 {@code unit.getPlayer() != null} 互斥，相位不会双倍累加。
     *    <p>
     *    释放时 PlayerComp.java:319 调 {@code resetController()} → {@code UnitType.createController()}
     *    → 本控制器的工厂，因此会回到①，从存档相位续接。
     * <p>
     * 位置是存档相位的纯函数；同时每帧清零速度——接管期间玩家输入（InputHandler 会对玩家单位
     * 调 moveAt 改 vel）会被立刻抹掉，所以看得见轨迹在走、但改不动它。
     */
    public static void applyMotion(Unit u, int orbit) {
        if (u == null) {
            // 不静默返回：controller 若被引擎以非常规路径还原，unit 可能为 null，
            // 表现为「卫星静止且无任何日志」——最难定位的一类症状。节流打一条便于取证。
            if ((nullUnitWarn++ % 300) == 0) {
                SiliconLog.info("sat-motion: controller has no unit assigned (orbit=" + orbit + ")");
            }
            return;
        }
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
