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
import silicon.world.blocks.satellite.SatelliteConsole;
import silicon.util.OrbitSatelliteController;
import silicon.util.SatelliteManager;

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
 * - playerControllable = false：控制器永远走 aiController（UnitType.java:281），
 *   isCommandable() 恒假（非 CommandAI）→ 玩家框选/指挥无法改变其位置。
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
    /** 近地轨道离子炮（LOIC）专用机型：唯一挂武器的卫星，只在 LEO 使用 */
    public static UnitType ionLeo;

    public static void load() {
        // 名字不带 mod 前缀：MappableContent 构造时会经 content.transformName 无条件加 "silicon-" 前缀
        // （与 Blocks 同一惯例）；带前缀传入会变成 silicon-silicon-*，导致 bundle/贴图键全部落空
        signalLeo = orbitSatellite("satellite-leo", SatelliteConsole.ORBIT_LEO);
        signalMeo = orbitSatellite("satellite-meo", SatelliteConsole.ORBIT_MEO);
        signalGeo = orbitSatellite("satellite-geo", SatelliteConsole.ORBIT_GEO);
        testSso = orbitSatellite("satellite-sso", SatelliteConsole.ORBIT_SSO);
        // 离子炮走**单位武器**：冷却、索敌、瞄准、开火与联机同步全部由引擎的 Weapon 处理，
        // 不需要另维护冷却表或自定义网络包（引擎的单位武器会自带建筑谓词，见 Weapon.findTarget）
        //
        // 但武器更新本身有个前提（核过源码，改这里之前先看）：
        //   WeaponsComp.update()（每单位每帧）→ mount.weapon.update(self(), mount)
        //   → Weapon.update() 里 `boolean can = unit.canShoot();`，而 can=false 时它是**整体 return**的
        //   → UnitComp.canShoot() = !disarmed && !(type.canBoost && isFlying())
        // 卫星是 flying=true 的单位，本行机型全都没设 canBoost（默认 false），所以 can 恒为 true、武器会更新，
        // LoicWeapon 的弹药恢复也在其中。**不要让卫星类型变成 canBoost**，也不要依赖「用状态效果停火」——
        // 那两件事都会让 can=false，武器更新被整个跳过（表现为弹药永不恢复、也不再开火）。
        // 需要停火请走控制台的「自动」开关（它只影响 LoicWeapon.shoot，不碰引擎这层）。
        ionLeo = orbitSatellite("satellite-loic", SatelliteConsole.ORBIT_LEO);
        ionLeo.weapons.add(ionWeapon());
    }

    /**
     * 离子炮武器：**1 秒连射**、**5 发弹夹**、每 30 秒回 1 发；对地打建筑，也可打**低轨卫星**。
     * <p>
     * 索敌、弹药与两个开关（自动发射 / 攻击卫星）都在 {@link silicon.util.LoicWeapon} 里：
     * 引擎的武器没有"弹夹"概念（`reload` 只是开火间隔），所以那里用一张按 unitId 的状态表补上，
     * 并**重写** `findTarget` —— 不能在 `super.findTarget` 之后过滤，否则最近的若恰好是卫星且
     * "攻击卫星"关闭，会连更远的建筑一起漏掉。
     */
    /** 单次打击的中心伤害（溅射边缘由引擎按距离衰减） */
    public static final float ION_DAMAGE = 10000f;
    /** 溅射半径（格） */
    public static final float ION_RADIUS_TILES = 10f;
    /**
     * 武器索敌射程（格）——<b>必须显式设置</b>：`Weapon` 用 `bullet.range` 索敌，而它未设置时由
     * `speed × lifetime` 推算（12 × 90 = 1080px ≈ 135 格），会让卫星在半个地图外就开火。
     * 这里取 250×250 图上 LEO 的星下点覆盖半径 40 格，使"打击范围 = 覆盖范围"成立。
     * （覆盖半径随图幅缩放而武器射程是常量：大图上覆盖更大、打击却仍限 40 格，属于可接受的近似。）
     */
    public static final float ION_RANGE_TILES = 40f;
    /** 开火间隔（tick）：1 秒 */
    public static final float ION_COOLDOWN_TICKS = 60f;
    /** 弹夹容量 */
    public static final float ION_MAGAZINE = 5f;
    /** 每恢复 1 发所需时间（tick）：30 秒 */
    public static final float ION_RECHARGE_TICKS = 30f * 60f;

    static silicon.util.LoicWeapon ionWeapon() {
        mindustry.entities.bullet.BulletType shot = new mindustry.entities.bullet.BulletType() {{
            damage = ION_DAMAGE;
            splashDamage = ION_DAMAGE;
            splashDamageRadius = ION_RADIUS_TILES * 8f;
            collidesGround = true;   // 打地面目标（建筑）
            collidesAir = true;      // 也要能打低轨卫星（引擎的单位索敌靠 isFlying 放行）
            collidesTiles = true;
            speed = 12f;             // 从轨道砸下：够快，但保留可见的坠落过程
            lifetime = 90f;
            range = ION_RANGE_TILES * 8f;   // 索敌射程：不显式设置会被 speed×lifetime 推成 135 格
            hitEffect = mindustry.content.Fx.massiveExplosion;
            despawnEffect = mindustry.content.Fx.none;
            shootEffect = mindustry.content.Fx.sparkShoot;
        }};
        return new silicon.util.LoicWeapon("silicon-ion-cannon") {{
            reload = ION_COOLDOWN_TICKS;
            maxAmmo = ION_MAGAZINE;
            rechargeTicks = ION_RECHARGE_TICKS;
            bullet = shot;
            rotate = false;    // 不需要转向表现（伤害直接落在目标上）
            mirror = false;
            shootCone = 360f;  // 不限制射界：目标可能在任意方向
            x = 0f;
            y = 0f;
        }};
    }

    /**
     * 按轨道与种类取机型。
     * <p>
     * 离子炮卫星必须有独立机型：机型是**按轨道共享**的（信号卫星与测试卫星共用同一批），
     * 把武器加在通用机型上会让所有 LEO 卫星都变成炮。
     */
    public static UnitType typeFor(int orbit, int type) {
        if (type == silicon.world.blocks.satellite.SatelliteLauncher.TYPE_ION) return ionLeo;
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
                playerControllable = false;
                logicControllable = false;
                allowedInPayloads = false;
                drawMinimap = false;
                useUnitCap = false; // 不占队伍单位上限 + 免疫超限击杀（UnitComp.java:596）
                // 悬停信息面板触发窗 = 5 + hitSize/2（见类注释）；仅影响鼠标悬停/拾取，不影响战斗
                hitSize = 24f;

                // 轨道控制器（按轨道携带周期/半径参数；无状态，读档经 type 工厂重建即续接）
                aiController = () -> new OrbitSatelliteController(orbit);

                // 免疫全部状态效果（含本 mod 的卫星 buff——buff 只上玩家单位，这里只是防御性兜底）
                Vars.content.statusEffects().each(effect -> immunities.add(effect));

                // 未来激光卫星的攻击面：只打地面、永不索敌空中（含卫星）——层间隔离在机型层再锁一道
                targetAir = false;
                targetGround = true;
            }

            @Override
            public void load() {
                super.load();
                // 贴图名兜底：Mindustry 用「内容名」找贴图，而 mod 内容名会被加上 "<mod>-" 前缀
                // （MappableContent → ContentLoader.transformName），于是 sprites/units/satellite-leo.png
                // 与 silicon-satellite-leo.png 两种命名都要能命中。super.load() 已试过带前缀的内容名，
                // 这里再剥掉前缀试一次；两者都没有时 region.found() == false，交给 draw() 的程序化兜底。
                if (!region.found()) {
                    int i = name.indexOf('-');
                    if (i > 0) region = Core.atlas.find(name.substring(i + 1));
                }
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
}
