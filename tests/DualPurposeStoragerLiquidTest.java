/**
 * DualPurposeStorager 单液体约束规则测试（独立可编译，不参与模组构建）
 *
 * 用法：javac --release 17 -encoding UTF-8 DualPurposeStoragerLiquidTest.java && java DualPurposeStoragerLiquidTest
 *
 * 覆盖（与 DualPurposeStoragerBuild 中 static 方法逻辑一致）：<p>
 * 1. canAcceptLiquidType：空罐接受任意液体；有存量时仅接受同类型，拒绝异类型<br>
 * 2. shouldRejectLiquid：有存量且类型不一致时拒绝注入（防混液）
 *
 * 测试对象是抽出的纯静态规则，不依赖 Mindustry 运行时（Building/LiquidModule）。
 */
public class DualPurposeStoragerLiquidTest {
    static final float threshold = 0.001f;
    // 液体类型用字符串 ID 表示，模拟 Liquid 引用比较
    static final String WATER = "water";
    static final String SLAG = "slag";

    public static void main(String[] args) {
        int total = 0, pass = 0;

        // ========== canAcceptLiquidType：空罐接受任意 ==========
        total++; if (canAcceptLiquidType(0f, null, WATER, threshold)) pass++; else fail(1, "空罐应接受任意液体");
        total++; if (canAcceptLiquidType(0f, null, SLAG, threshold)) pass++; else fail(2, "空罐应接受任意液体");

        // ========== canAcceptLiquidType：有存量，同类型接受 ==========
        total++; if (canAcceptLiquidType(500f, WATER, WATER, threshold)) pass++; else fail(3, "同类型应接受");
        total++; if (canAcceptLiquidType(900f, SLAG, SLAG, threshold)) pass++; else fail(4, "同类型应接受");

        // ========== canAcceptLiquidType：有存量，异类型拒绝 ==========
        total++; if (!canAcceptLiquidType(500f, WATER, SLAG, threshold)) pass++; else fail(5, "异类型应拒绝");
        total++; if (!canAcceptLiquidType(900f, SLAG, WATER, threshold)) pass++; else fail(6, "异类型应拒绝");

        // ========== canAcceptLiquidType：低于阈值视为空，接受任意 ==========
        total++; if (canAcceptLiquidType(0.0005f, WATER, SLAG, threshold)) pass++; else fail(7, "低于阈值应视为空容器");
        total++; if (canAcceptLiquidType(0.001f, WATER, WATER, threshold)) pass++; else fail(8, "等于阈值应视为空容器");

        // ========== shouldRejectLiquid：空罐不拒绝 ==========
        total++; if (!shouldRejectLiquid(0f, null, WATER, threshold)) pass++; else fail(9, "空罐不应拒绝");
        total++; if (!shouldRejectLiquid(0.0005f, WATER, SLAG, threshold)) pass++; else fail(10, "低于阈值不应拒绝");

        // ========== shouldRejectLiquid：有存量异类型拒绝，同类型不拒绝 ==========
        total++; if (shouldRejectLiquid(500f, WATER, SLAG, threshold)) pass++; else fail(11, "有存量异类型应拒绝");
        total++; if (!shouldRejectLiquid(500f, WATER, WATER, threshold)) pass++; else fail(12, "有存量同类型不应拒绝");

        System.out.println();
        System.out.println("== 结果: " + pass + "/" + total + " 通过 ==");
        if (pass != total) {
            System.out.println("有测试未通过");
            System.exit(1);
        } else {
            System.out.println("全部通过");
        }
    }

    // ---- 与 DualPurposeStoragerBuild 一致的纯逻辑规则 ----

    static boolean canAcceptLiquidType(float currentAmount, Object currentType, Object incomingType, float threshold) {
        if (incomingType == null) return false;
        if (currentAmount <= threshold) return true;
        return currentType == incomingType;
    }

    static boolean shouldRejectLiquid(float currentAmount, Object currentType, Object incomingType, float threshold) {
        return currentAmount > threshold && currentType != incomingType;
    }

    static void fail(int n, String msg) {
        System.out.println("  [FAIL] 用例" + n + ": " + msg);
    }
}
