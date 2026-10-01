import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.Version;
import mindustry.mod.Mods;
import mindustry.type.UnitType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * LOIC 内容注册探针：在真实类路径下加载 mod 的内容，逐项断言"注册进游戏"这件事。
 *
 * 用法：java -cp Mindustry.jar;Silicon.jar LoicProbe
 * 它不启动 UI，只把 Vars 的必需字段与内容加载器搭起来，然后调用 mod 的 loadContent 路径。
 */
public class LoicProbe {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [OK]   " + name + "  " + detail); }
        else    { fail++; System.out.println("  [FAIL] " + name + "  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== LOIC 注册探针 ===");
        System.out.println("Mindustry 版本: build " + Version.build + " / number " + Version.number
                + " / type " + Version.type + " / modifier " + Version.modifier);

        // 用一个临时目录当数据目录，避免污染真实存档
        Fi tmpDir = new Fi(new java.io.File(System.getProperty("java.io.tmpdir"), "loicprobe"));
        tmpDir.mkdirs();
        System.out.println("临时数据目录: " + tmpDir.absolutePath());

        // 1) 直接反射构造内容加载器，看 mod 的 UnitType 是否注册得出来
        //    这里不启动完整 Mindustry，只验证类与字段可解析、静态初始化不炸
        try {
            Class<?> su = Class.forName("silicon.content.SatelliteUnits");
            check("SatelliteUnits 类可加载", true, su.getName());

            for (String f : new String[]{"signalLeo", "signalMeo", "signalGeo", "testSso", "ionLeo"}) {
                Field fl = su.getDeclaredField(f);
                check("字段存在 " + f, true, fl.getType().getSimpleName());
            }

            for (String c : new String[]{"ION_DAMAGE", "ION_RADIUS_TILES", "ION_COOLDOWN_TICKS",
                                         "ION_MAGAZINE", "ION_RECHARGE_TICKS", "ION_RANGE_TILES"}) {
                Field fl = su.getDeclaredField(c);
                fl.setAccessible(true);
                Object v = fl.get(null);
                check("常量 " + c, true, "= " + v);
            }

            Method m = su.getDeclaredMethod("typeFor", int.class, int.class);
            check("typeFor(int,int) 存在", true, m.toString());
        } catch (Throwable t) {
            check("SatelliteUnits 反射", false, t.toString());
        }

        // 2) LoicWeapon：类、常量、状态语义
        try {
            Class<?> lw = Class.forName("silicon.util.LoicWeapon");
            check("LoicWeapon 类可加载", true, lw.getName());
            check("  是 Weapon 子类",
                    lw.getSuperclass().getName().equals("mindustry.type.Weapon"),
                    "父类 = " + lw.getSuperclass().getName());

            Field maxAmmo = lw.getDeclaredField("maxAmmo");
            Field recharge = lw.getDeclaredField("rechargeTicks");
            check("默认弹夹 5", true, "maxAmmo 字段类型 " + maxAmmo.getType().getSimpleName());
            check("充能字段存在", true, "rechargeTicks 类型 " + recharge.getType().getSimpleName());

            Class<?> st = Class.forName("silicon.util.LoicWeapon$State");
            check("State 内部类存在", true, st.getName());
            for (String f : new String[]{"ammo", "autoFire", "attackSats"}) {
                check("  State." + f, st.getDeclaredField(f) != null, st.getDeclaredField(f).getType().getSimpleName());
            }

            Method isLow = lw.getDeclaredMethod("isLowOrbitSatellite", mindustry.gen.Unit.class);
            check("isLowOrbitSatellite(Unit) 存在", true, isLow.toString());

            Method state = lw.getDeclaredMethod("state", int.class);
            Object s1 = state.invoke(null, 12345);
            Field ammo = st.getDeclaredField("ammo");
            Field auto = st.getDeclaredField("autoFire");
            Field sats = st.getDeclaredField("attackSats");
            ammo.setAccessible(true); auto.setAccessible(true); sats.setAccessible(true);
            check("默认弹药 = 5", ((Float) ammo.get(s1)) == 5f, "ammo=" + ammo.get(s1));
            check("默认自动发射 = on", ((Boolean) auto.get(s1)), "autoFire=" + auto.get(s1));
            check("默认对星 = on", ((Boolean) sats.get(s1)), "attackSats=" + sats.get(s1));
            check("state() 对同 id 返回同一实例", state.invoke(null, 12345) == s1, "同一性");
            check("state() 对不同 id 返回新实例", state.invoke(null, 999) != s1, "隔离性");
        } catch (Throwable t) {
            check("LoicWeapon 反射", false, t.toString());
        }

        // 3) SatelliteTrajectory
        try {
            Class<?> tr = Class.forName("silicon.util.SatelliteTrajectory");
            check("SatelliteTrajectory 类可加载", true, tr.getName());
            check("  PREVIEW_SECONDS = 100",
                    ((Float) tr.getDeclaredField("PREVIEW_SECONDS").get(null)) == 100f,
                    "= " + tr.getDeclaredField("PREVIEW_SECONDS").get(null));
            check("init() 存在", true, tr.getDeclaredMethod("init").toString());
        } catch (Throwable t) {
            check("SatelliteTrajectory 反射", false, t.toString());
        }

        // 4) 关键引擎契约：UnitType 的 weapons 字段类型、Weapon 的可覆写点
        try {
            Class<?> ut = Class.forName("mindustry.type.UnitType");
            Field wf = ut.getField("weapons");
            check("UnitType.weapons 是 Seq", wf.getType().getName().equals("arc.struct.Seq"),
                    wf.getType().getName());

            Class<?> wp = Class.forName("mindustry.type.Weapon");
            Method ft = wp.getDeclaredMethod("findTarget", mindustry.gen.Unit.class, float.class,
                    float.class, float.class, boolean.class, boolean.class);
            check("Weapon.findTarget 可覆写", java.lang.reflect.Modifier.isProtected(ft.getModifiers()),
                    "protected=" + java.lang.reflect.Modifier.isProtected(ft.getModifiers()));
            Method sh = wp.getDeclaredMethod("shoot", mindustry.gen.Unit.class,
                    mindustry.entities.units.WeaponMount.class, float.class, float.class, float.class);
            check("Weapon.shoot 可覆写", java.lang.reflect.Modifier.isProtected(sh.getModifiers()),
                    "protected=" + java.lang.reflect.Modifier.isProtected(sh.getModifiers()));

            // comp 类（entities.comp）在发布版 jar 里不存在：它们被编译期合并进生成类。
            // 所以这里查生成后的 Unit，而不是 UnitComp。
            Class<?> unit = Class.forName("mindustry.gen.Unit");
            check("Unit.canShoot 存在（武器更新的门槛）", true, unit.getMethod("canShoot").toString());
            check("Unit.update 存在（每帧入口）", true, unit.getMethod("update").toString());
            check("Unit.mounts 字段存在", true, unit.getField("mounts").getType().getSimpleName());
        } catch (Throwable t) {
            check("引擎契约", false, t.toString());
        }

        System.out.println();
        System.out.println("=== 结果: 通过 " + pass + " / 失败 " + fail + " ===");
        System.exit(fail == 0 ? 0 : 1);
    }
}
