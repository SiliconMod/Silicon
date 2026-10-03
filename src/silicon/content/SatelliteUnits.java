package silicon.content;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.UnitType;
import mindustry.world.meta.Env;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteManager;
import silicon.world.blocks.satellite.SatelliteConsole;

/**
 * 卫星实体机型（按轨道一型，共 4 型）：卫星是真实引擎单位（UnitEntity），轨道运动由
 * OrbitSatelliteController 以"相位+时间的纯函数"驱动——控制器无状态，读档即续接。
 * <p>
 * 隔离旗标（全部为 v159.7 引擎现成字段，见各注释）：
 * - targetable/hittable = false：所有索敌查询（Units.java:156/304/326 的 targetable 过滤）
 *   与所有伤害路径（Damage.java 系列的 hittable 过滤）对卫星完全失明——地面单位无法攻击卫星，
 *   子弹直接穿透（UnitComp.collides() 就是 hittable()，只管命中事件，不管物理推挤）。
 * - physics = false：退出异步物理系统（PhysicsProcess.begin 的 type.physics 过滤）——
 *   单位间推挤由 layerFlying/layerGround 物理体实现，与 hittable 无关；不加此旗标卫星
 *   会被编入 flying 物理层与飞行单位互相推挤（实测过的坑）。
 * - playerControllable = true：可被玩家按 Ctrl 接管（原版 possess 流程；InputHandler.java:783 判定
 *   unit.isAI() && team 相同 && !dead && playerControllable()）。**两者缺一不可**——
 *   OrbitSatelliteController 继承 AIController，isAI() 才为真（UnitComp.java:494 是 instanceof 判定）。
 *   接管期 controller 被替换为 {@code Player} 本身（PlayerComp.java:328 的 unit.controller(this)），
 *   轨迹控制器不再被驱动，运动改由下方覆写的 {@code update(Unit)} 兜底（与 updateUnit 路径由
 *   {@code unit.getPlayer() != null} 互斥）；释放时 PlayerComp.java:319 的 resetController()
 *   经 UnitType.createController() 取回本控制器，轨迹从存档相位续接。
 *   applyMotion 每帧把 vel 归零，玩家输入改不动轨迹——能进去看，推不动它。
 * - logicControllable = false：逻辑处理器不可操控。
 * - allowedInPayloads = false：不可被 payload 方块装载搬运。
 * - drawMinimap = false：小地图不画（MinimapRenderer.java:158 过滤）——敌方小地图看不到卫星过境
 *   （代价：己方小地图也无点，由世界内轨道绘制补偿）。
 * - useUnitCap = false：不占用队伍单位上限，且永远不会触发超限击杀（UnitComp.java:596 的
 *   count() > cap() 分支；原版 eta 机甲/导弹机型同款处理）——卫星是环境实体，不该挤占军队编制。
 * - immunities = 全部状态效果：不受 EMP/减速等影响。
 * - hitSize = 24：右下角悬停信息面板的触发窗 = PlacementFragment.hovered() → Units.closestOverlap(5f)
 *   + 单位 hitbox/2（arc QuadTree 按 hitbox 相交），7px 时窗口仅约 8.5px 且卫星持续移动，鼠标几乎
 *   无法命中导致面板弹不出/不显示名称；24px 使窗口达约 17px。战斗语义不受影响——索敌/伤害/碰撞
 *   均被 targetable/hittable/collides 隔离，命中窗只服务鼠标悬停/拾取类查询。
 * - uiIcon/fullIcon = 程序化生成图标（loadIcon 覆写）：无 sprite 机型原版 loadIcon 会落到 error
 *   白方块，悬停面板/单位图鉴观感异常；生成 32×32 环+核+板图标替代。
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
        // 名字不带 mod 前缀：MappableContent 构造时会经 content.transformName 无条件加 "silicon-" 前缀
        // （与 Blocks 同一惯例）；带前缀传入会变成 silicon-silicon-*，导致 bundle/贴图键全部落空
        signalLeo = orbitSatellite("satellite-leo", SatelliteConsole.ORBIT_LEO);
        signalMeo = orbitSatellite("satellite-meo", SatelliteConsole.ORBIT_MEO);
        signalGeo = orbitSatellite("satellite-geo", SatelliteConsole.ORBIT_GEO);
        testSso = orbitSatellite("satellite-sso", SatelliteConsole.ORBIT_SSO);
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

    /** 程序化 UI 图标缓存（32×32：太阳能板横条 + 本体环 + 核心）——无 sprite 机型原版 loadIcon
     *  会把 uiIcon 落到 error 白方块，右下角悬停面板/单位图鉴观感异常；四个机型共用一个 */
    private static TextureRegion satelliteIcon;

    static TextureRegion satelliteIcon() {
        if (satelliteIcon != null) return satelliteIcon;
        Pixmap px = new Pixmap(32, 32);
        // 太阳能板横条（中段被本体环覆盖，两侧留出板翼）
        px.fillRect(2, 15, 28, 2, Color.gray.rgba());
        // 本体环 + 核心
        px.drawCircle(16, 16, 8, Color.white.rgba());
        px.fillCircle(16, 16, 4, Color.lightGray.rgba());
        Texture tex = new Texture(px);
        px.dispose();
        return satelliteIcon = new TextureRegion(tex);
    }

    static UnitType orbitSatellite(String name, int orbit) {
        // 匿名子类:实例初始化块集中赋值,再覆写 draw(双花括号写法会把方法吞进 init 块,编译不过)
        return new UnitType(name) {
            {
                flying = true;
                health = 400f; // 只能被 scripted 伤害（ASAT 拦截塔）击落，血量即拦截成本
                armor = 2f;
                speed = 0f; // 位置由控制器直接覆写，不使用自身速度
                crashDamageMultiplier = 0f; // 坠毁不砸地面
                createWreck = false;

                // —— 环境旗标：必须显式放行全部环境 ——
                // UnitType 默认 envEnabled = Env.terrestrial / envDisabled = Env.scorching（UnitType.java:51-53），
                // 于是卫星在焦土图（Erekir 全部地图：Planets.java:56）与太空图（Planets.java:182，envEnabled 不含
                // Env.space）会被引擎「环境处死」——该路径不看 hittable/targetable/killable/useUnitCap
                // （UnitComp.java:751-753 → Units.unitEnvDeath → dead + Call.unitDestroy），
                // 表现为发射后一帧卫星消失、UnitDestroyEvent 连名册一起删（覆盖/信号全无）。
                // 卫星是轨道实体，与地面环境无关：取 any/none（原版 assembly-drone 同写法，UnitTypes.java:4617-4618）。
                envEnabled = Env.any;
                envDisabled = Env.none;

                // —— 索敌/伤害/物理全隔离（详见类注释）——
                targetable = false;
                hittable = false;
                physics = false; // 退出异步物理系统（PhysicsProcess.begin 按 type.physics 过滤）：
                                 // 单位间推挤在 layerFlying 物理体间发生，与 hittable 无关——
                                 // 不加此旗标卫星会被编入 flying 物理层，与飞行单位互相推挤
                killable = true; // 保留 scripted 击落能力
                playerControllable = true; // 允许玩家按 Ctrl 接管（原版 possess）；接管期 controller 被换成
                                           // Player，由下方 update 覆写兜底；释放后 resetController 取回
                logicControllable = false;
                allowedInPayloads = false;
                drawMinimap = false;
                useUnitCap = false; // 不占队伍单位上限 + 免疫超限击杀（UnitComp.java:596）
                // 悬停信息面板触发窗 = 5 + hitSize/2（见类注释）；仅影响鼠标悬停/拾取，不影响战斗
                hitSize = 24f;

                // 轨道控制器（按轨道携带周期/半径参数；无状态，读档经 type 工厂重建即续接）
                //
                // 必须显式指定 controller，不能只设 aiController —— 这是踩过的坑：
                // UnitType.java:281 的默认 controller 工厂是
                //     u -> !playerControllable || (u.team.isAI() && !u.team.rules().rtsAi)
                //          ? aiController.get() : new CommandAI();
                // 三元的第二个分支里才用 aiController。playerControllable=true 且队伍是玩家时走第二支，
                // 引擎直接给 new CommandAI()，aiController 根本不会被调用 —— 卫星失去唯一的运动驱动源，
                // 读档与刚发射的都静止不动。
                // 显式指定后 controller 与 playerControllable 解耦：无论是否被玩家接管，卫星拿到的
                // 始终是这个控制器，轨迹连续（possess 期间也在走）。
                controller = u -> new OrbitSatelliteController(orbit);

                // 免疫全部状态效果（含本 mod 的卫星 buff——buff 只上玩家单位，这里只是防御性兜底）
                Vars.content.statusEffects().each(effect -> immunities.add(effect));

                // 未来激光卫星的攻击面：只打地面、永不索敌空中（含卫星）——层间隔离在机型层再锁一道
                targetAir = false;
                targetGround = true;
            }

            @Override
            public void update(Unit unit){
                // 玩家接管期间补位：possess 会把 controller 换成 Player 对象本身
                // （判据是 UnitComp.java:950 的 isPlayer() = controller instanceof Player），
                // 此后 UnitComp.java:839-841 调的是 Player.updateUnit()，轨迹控制器不再被驱动，
                // 卫星会原地冻结。UnitType.update 由 UnitComp.java:654 无条件每帧调用、与 controller
                // 无关，所以在权威端接着驱动轨道运动。
                // 判据 unit.getPlayer()（UnitComp.java:955）：只有真被接管时才补位——未接管时
                // controller 仍是 OrbitSatelliteController，updateUnit 已在驱动，这里再跑会双倍累加相位。
                // !net.client() 保证相位只在服务端累加一次，客机仍旧靠单位同步取位置（与未接管时同一模型）。
                if(!Vars.net.client() && unit.getPlayer() != null){
                    OrbitSatelliteController.applyMotion(unit, orbit);
                }
            }

            @Override
            public void load() {
                super.load();
                region = findUnitRegion(region, name);
            }

            @Override
            public void loadIcon() {
                super.loadIcon();
                // 原版 loadIcon 会把无 sprite 机型的图标指到 error 白方块——统一替换为程序化图标
                uiIcon = fullIcon = satelliteIcon();
            }

            @Override
            public void draw(Unit unit) {
                // 存在度：回绕进出场淡入淡出（与信号强度同一个系数，见 SatelliteManager.presence）
                float p = SatelliteManager.presenceOf(unit.id);
                if (p <= 0.004f) return;
                // 无贴图兜底：程序化卫星造型（队色环+核心+太阳能板线）；
                // 交付 sprites/units/<机型名>.png 后自动切换为贴图绘制（load() 里的兜底负责命名兼容）
                if (!region.found()) {
                    drawFallback(unit, p);
                } else {
                    Draw.color(1f, 1f, 1f, p);
                    Draw.rect(region, unit.x, unit.y, unit.rotation - 90f);
                    Draw.color();
                    // 贴图本身不含队伍信息：中心补一个队色点，多队同图时仍能分辨归属
                    Draw.color(unit.team.color, p);
                    Fill.circle(unit.x, unit.y, 1.6f);
                    Draw.reset();
                }
            }

            void drawFallback(Unit unit, float alpha) {
                // 视觉尺寸与 hitSize 解耦（hitSize=24 只为悬停窗口，造型保持小卫星观感）
                float r = 6.5f;
                Color tc = unit.team.color;
                // 太阳能板横线
                Lines.stroke(1.2f, tc.cpy().mul(0.7f).a(alpha));
                Lines.line(unit.x - r * 2f, unit.y, unit.x + r * 2f, unit.y);
                // 本体环：tc 是 Team.color 的**共享实例**，直接 a(alpha) 会把全队队色改淡且不恢复——必须 cpy
                Lines.stroke(1.5f, tc.cpy().a(alpha));
                Lines.circle(unit.x, unit.y, r);
                // 核心 + 遥测闪烁
                Draw.color(tc, alpha);
                Fill.circle(unit.x, unit.y, r * 0.45f);
                Fill.circle(unit.x, unit.y, r * 0.2f + (float) Math.abs(Mathf.sin(unit.id + Time.time / 40f)) * r * 0.15f);
                // 复位笔画宽度（Draw.reset 只复位颜色,Lines.stroke 是独立静态值,残留会影响后续 Lines 绘制）
                Lines.stroke(1f);
                Draw.reset();
            }
        };
    }

    /**
     * 贴图名兜底：Mindustry 用「内容名」找贴图，而 mod 内容名会被加上 {@code "<mod>-"} 前缀
     * （{@code MappableContent} → {@code ContentLoader.transformName}），于是
     * {@code sprites/units/satellite-leo.png} 与 {@code silicon-satellite-leo.png} 两种命名都要能命中。
     * <p>
     * {@code super.load()} 已试过带前缀的内容名；这里在它没找到时再剥掉第一段前缀试一次。
     * 两者都没有时返回的 region 其 {@code found()} 仍为 false，交给 {@code draw()} 的程序化兜底。
     */
    static TextureRegion findUnitRegion(TextureRegion current, String name) {
        if (current != null && current.found()) return current;
        int i = name.indexOf('-');
        return i > 0 ? Core.atlas.find(name.substring(i + 1)) : current;
    }
}
