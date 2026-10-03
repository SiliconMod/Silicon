package silicon.util.boosts;

import arc.Core;
import arc.math.Angles;
import mindustry.gen.Building;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.ReloadTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import silicon.util.BuildingBoostSystem;

/**
 * 润滑油综合强化效果（注入式 Boost）：润滑油带给炮塔的全部强化打包为一个效果单元，内部
 * 包含两个子效果——每个子效果的生效条件独立判定，激活期间每帧调用 apply 时各自按需注入：
 *
 * <ul>
 *   <li><b>攻速</b>：炮塔 {@code isShooting()} <b>且引擎本 tick 也会推进充能</b>时，按强化液方式注入
 *       充能点数 {@code reloadCounter += firePotency × edelta() × ammoReloadMultiplier}（与强化液同加法池）;
 *       充能守卫见 {@link #canEngineReload}——必须与引擎 {@code handleReload} 一致，否则已充满的炮塔
 *       会累积溢出量、形成补弹瞬间连发的蓄力 bursts；</li>
 *   <li><b>转角</b>：炮塔 {@code hasAmmo() && shouldTurn()} 时，与引擎 turnToTarget 同式再推一格，
 *       目标角随引擎分支同源（玩家控制=unit 瞄准角、逻辑控制=logic 写入的 targetPos、
 *       自动索敌=targetPosition(target) 预测点），否则与引擎目标不一致会互相抵消。</li>
 * </ul>
 *
 * <p>两个子效果均注入式：条件消失即自动失效，无残留、无需 remove 还原。
 */
public class LubricantBoost implements BuildingBoostSystem.Boost {

    /** 单例：注册进 System 供注入器引用。 */
    public static final LubricantBoost instance = new LubricantBoost();

    static {
        BuildingBoostSystem.register(instance);
    }

    /** 攻速加成：每 tick 注入的固定充能点数（参考基准 efficiency=timeScale=ammoRM=1 时即 +20% 射速）。 */
    public float firePotency = 0.2f;

    /** 转角速率加成倍率：1.0 = 引擎 1× + 本效果 1× = 总转角 ×2（+100%）。 */
    public float rotationPotency = 1.0f;

    @Override
    public String id() {
        return "lubricant";
    }

    @Override
    public String name() {
        return Core.bundle.get("boost.lubricant.name", "Lubricant");
    }

    @Override
    public String description() {
        // 描述需与实际生效值一致：转角 ×2（+100%）、攻速 +20%
        return Core.bundle.get("boost.lubricant.desc", "+100% rotation speed; +20% attack speed");
    }

    // 目标过滤：只作用于炮塔。System 登记前读取本方法，非炮塔目标不登记贡献、不会进 apply。
    @Override
    public boolean canTarget(Building target) {
        return target instanceof Turret.TurretBuild;
    }

    @Override
    public boolean shouldApply(Building target) {
        // 任一子效果具备生效条件即保持激活；具体注入逐项按需进行
        return target instanceof Turret.TurretBuild t
                && (t.isShooting() || (t.hasAmmo() && t.shouldTurn()));
    }

    @Override
    public void apply(Building target) {
        if (!(target instanceof Turret.TurretBuild t)) {
            return;
        }

        boolean shooting = t.isShooting();
        boolean hasAmmo = t.hasAmmo();

        // —— 攻速子效果：仅攻击中的炮塔 ——
        // 注入条件必须与引擎的充能守卫一致，否则会把炮塔推进到「引擎自己不会推进」的状态：
        //   handleReload(){ if(!reloadWhileCharging && charging()) return;
        //                   if(reloadCounter >= reload) return; updateReload(); }
        // 少了后一条时，已充满的炮塔每 tick 仍被注入，而 updateShooting 减去 reload 后**保留溢出**，
        // 溢出量会累积成「蓄力 bursts」——补弹瞬间连发，实际收益远高于文案写的 +20%。
        // 少了前一条时，充能中的炮塔（激光/液流）也会被推进，破坏 charging() 语义。
        if (shooting && canEngineReload(t)) {
            float ammoRM = hasAmmo ? t.peekAmmo().reloadMultiplier : 1f;
            t.reloadCounter += firePotency * t.edelta() * ammoRM;
        }

        // —— 转角子效果：有弹药且允许转身时 ——
        if (hasAmmo && t.shouldTurn()) {
            // 本帧无可瞄准角（目标无效/未锁定）则跳过，不干扰引擎
            float des;
            if (t.controlled()) {
                des = Angles.angle(t.x, t.y, t.unit.aimX(), t.unit.aimY());
            } else if (t.logicControlled()) {
                des = t.angleTo(t.targetPos);
            } else if (t.target != null) {
                t.targetPosition(t.target);
                des = t.angleTo(t.targetPos);
            } else {
                return;
            }
            t.rotation = Angles.moveToward(t.rotation, des,
                    rotationPotency * ((BaseTurret) t.block).rotateSpeed * t.delta() * t.potentialEfficiency);
        }
    }

    @Override
    public void remove(Building target) {
        // 注入式：条件消失即自动失效，无需还原
    }

    /**
     * 引擎本 tick 是否会推进充能——逐条镜像 {@code ReloadTurret.ReloadTurretBuild#handleReload} 的守卫：
     * <pre>
     * if(!reloadWhileCharging &amp;&amp; charging()) return;
     * if(reloadCounter &gt;= reload)          return;
     * </pre>
     *
     * <p>{@code reloadWhileCharging} 声明在 {@link Turret} 上（{@code reload} 继承自
     * {@link ReloadTurret}），{@code reloadCounter} / {@code charging()} 是建筑字段；
     * 这里按引擎同样的取值来源读取。非 {@link Turret} 派生（无充能概念）返回 false。
     */
    private static boolean canEngineReload(Turret.TurretBuild t){
        if(!(t.block instanceof Turret block)) return false;
        return (block.reloadWhileCharging || !t.charging()) && t.reloadCounter < block.reload;
    }

    @Override
    public BuildingBoostSystem.BoostVisual visual(Building target) {
        return LubricantVisual.instance;
    }

    /** 视觉描述：强化徽记（统一图标，底板用渲染器默认色）。 */
    private static class LubricantVisual implements BuildingBoostSystem.BoostVisual{
        static final LubricantVisual instance = new LubricantVisual();

        @Override
        public arc.graphics.g2d.TextureRegion icon(Building target){
            return BuildingBoostSystem.badgeIcon();   // 统一图标，见 BuildingBoostSystem.badgeIcon()
        }

        // 底板色交由渲染器默认（Pal.accent 绿），此处不覆写
    }
}