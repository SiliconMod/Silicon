package silicon.content;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.UnitType;
import silicon.world.blocks.satellite.SatelliteConsole;
import silicon.util.OrbitSatelliteController;

/**
 * 卫星实体机型（按轨道一型，共 4 型）：卫星是真实引擎单位（UnitEntity），轨道运动由
 * OrbitSatelliteController 以"相位+时间的纯函数"驱动——控制器无状态，读档即续接。
 * <p>
 * 隔离旗标（全部为 v159.7 引擎现成字段，见各注释）：
 * - targetable/hittable = false：所有索敌查询（Units.java:156/304/326 的 targetable 过滤）
 *   与所有伤害路径（Damage.java 系列的 hittable 过滤）对卫星完全失明——地面单位无法攻击卫星；
 *   且 UnitComp.collides() 就是 hittable()（UnitComp.java:431-435），EntityCollisions.java:162
 *   的双向与门使卫星与任何实体（含其他卫星、空中单位）零碰撞事件——不推挤、不挡路。
 * - playerControllable = false：控制器永远走 aiController（UnitType.java:281），
 *   isCommandable() 恒假（非 CommandAI）→ 玩家框选/指挥无法改变其位置。
 * - logicControllable = false：逻辑处理器不可操控。
 * - allowedInPayloads = false：不可被 payload 方块装载搬运。
 * - drawMinimap = false：小地图不画（MinimapRenderer.java:158 过滤）——敌方小地图看不到卫星过境
 *   （代价：己方小地图也无点，由世界内轨道绘制补偿）。
 * - immunities = 全部状态效果：不受 EMP/减速等影响。
 * - 未来武器卫星（激光）的目标选择由控制器驱动并显式排除卫星类型，且 hittable=false 使任何
 *   流弹/激光扫过其他卫星时直接穿透——"不同轨道层卫星互不攻击"由代码保证并双重兜底。
 * <p>
 * 卫星的编码/信道/相位等自定义数据不随单位持久化（无自定义实体组件），由
 * SatelliteConsole 的存档块代存（见 SatelliteManager.restoreRecord）。
 * 唯一 scripted 伤害入口：直接 unit.damage()（ASAT 拦截塔等自定义逻辑使用）。
 */
public class SatelliteUnits {
    public static UnitType signalLeo, signalMeo, signalGeo, testSso;

    public static void load() {
        signalLeo = orbitSatellite("silicon-satellite-leo", SatelliteConsole.ORBIT_LEO);
        signalMeo = orbitSatellite("silicon-satellite-meo", SatelliteConsole.ORBIT_MEO);
        signalGeo = orbitSatellite("silicon-satellite-geo", SatelliteConsole.ORBIT_GEO);
        testSso = orbitSatellite("silicon-satellite-sso", SatelliteConsole.ORBIT_SSO);
    }

    /** 按轨道取机型 */
    public static UnitType typeFor(int orbit) {
        switch (orbit) {
            case SatelliteConsole.ORBIT_MEO: return signalMeo;
            case SatelliteConsole.ORBIT_GEO: return signalGeo;
            case SatelliteConsole.ORBIT_SSO: return testSso;
            default: return signalLeo;
        }
    }

    static UnitType orbitSatellite(String name, int orbit) {
        // 匿名子类:实例初始化块集中赋值,再覆写 draw(双花括号写法会把方法吞进 init 块,编译不过)
        return new UnitType(name) {
            {
                flying = true;
                hitSize = 7f;
                health = 400f; // 只能被 scripted 伤害（ASAT 拦截塔）击落，血量即拦截成本
                armor = 2f;
                speed = 0f; // 位置由控制器直接覆写，不使用自身速度
                crashDamageMultiplier = 0f; // 坠毁不砸地面
                createWreck = false;

                // —— 索敌/伤害/物理全隔离（详见类注释）——
                targetable = false;
                hittable = false;
                killable = true; // 保留 scripted 击落能力
                playerControllable = false;
                logicControllable = false;
                allowedInPayloads = false;
                drawMinimap = false;

                // 轨道控制器（按轨道携带周期/半径参数；无状态，读档经 type 工厂重建即续接）
                aiController = () -> new OrbitSatelliteController(orbit);

                // 免疫全部状态效果（含本 mod 的卫星 buff——buff 只上玩家单位，这里只是防御性兜底）
                Vars.content.statusEffects().each(effect -> immunities.add(effect));

                // 未来激光卫星的攻击面：只打地面、永不索敌空中（含卫星）——层间隔离在机型层再锁一道
                targetAir = false;
                targetGround = true;
            }

            @Override
            public void draw(Unit unit) {
                // 无贴图兜底：程序化卫星造型（队色环+核心+太阳能板线）；
                // 作者后续补 sprite（atlas 键 = 机型名）后自动切换为原版贴图绘制
                if (!region.found()) {
                    drawFallback(unit);
                    return;
                }
                super.draw(unit);
            }

            void drawFallback(Unit unit) {
                Draw.z(Layer.flyingUnit + 1f);
                Color tc = unit.team.color;
                float r = hitSize * 0.85f;
                // 太阳能板横线
                Lines.stroke(1.2f, tc.cpy().mul(0.7f));
                Lines.line(unit.x - r * 2f, unit.y, unit.x + r * 2f, unit.y);
                // 本体环
                Lines.stroke(1.5f, tc);
                Lines.circle(unit.x, unit.y, r);
                // 核心 + 遥测闪烁
                Draw.color(tc);
                Fill.circle(unit.x, unit.y, r * 0.45f);
                Fill.circle(unit.x, unit.y, r * 0.2f + (float) Math.abs(Mathf.sin(unit.id + Time.time / 40f)) * r * 0.15f);
                Draw.reset();
            }
        };
    }
}
