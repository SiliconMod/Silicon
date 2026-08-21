/**
 * 万向交叉器路由算法测试（独立可编译，不参与模组构建）
 *
 * 用法：javac --release 17 PriorityRoutingTest.java && java PriorityRoutingTest
 *
 * 覆盖：
 * 1. pickOutput 持久降级逻辑（满载等待、超阈值降级、恢复探测切回、逐级降级、同优先级均分）
 * 2. pickInput 输入方向轮询（新方向轮流服务，避免高压方向饿死其他方向）
 *
 * 优先级值域与方块一致：0~4（0 = 不输出，4 最高）。
 * 满载阈值与方块一致：blockThreshold = 10。
 */
public class PriorityRoutingTest {
    static final int threshold = 10;

    public static void main(String[] args) {
        // ========== pickOutput：持久降级 ==========

        // 场景1: [4,3,0,0]，方向0间歇满载（满2空1）→ 全部走0（满载时等待），不降级
        System.out.println("场景1: 方向0间歇满载（满2空1）:");
        boolean[][] full1 = {
            {true,false,false,false},{true,false,false,false},{false,false,false,false},
            {true,false,false,false},{true,false,false,false},{false,false,false,false},
            {true,false,false,false},{true,false,false,false},{false,false,false,false}};
        runOutput(new int[]{4,3,0,0}, full1, null, "期望: -1 -1 0 -1 -1 0 -1 -1 0 (全部走0)");

        // 场景2: 方向0持续满载，方向1正常 → 前10次等待，之后持续走1（吞吐不崩坏）
        System.out.println("场景2: 方向0持续满载:");
        boolean[][] full2 = new boolean[15][4];
        for (boolean[] f : full2) f[0] = true;
        runOutput(new int[]{4,3,0,0}, full2, null, "期望: 前10次-1，之后 1 1 1 1 1");

        // 场景3: 同优先级均分
        System.out.println("场景3: 同优先级均分:");
        boolean[][] full3 = new boolean[8][4];
        runOutput(new int[]{4,4,0,0}, full3, null, "期望: 0 1 0 1 0 1 0 1");

        // 场景4: 方向0不可用 → 立即降级并持续走1
        System.out.println("场景4: 方向0不可用:");
        boolean[][] full4 = new boolean[5][4];
        boolean[][] avail4 = new boolean[5][4];
        for (boolean[] a : avail4) { a[0] = false; a[1] = true; a[2] = true; a[3] = true; }
        runOutput(new int[]{4,3,0,0}, full4, avail4, "期望: 1 1 1 1 1");

        // 场景5: 方向0持续满载10次后降级，然后方向0恢复 → 切回方向0
        System.out.println("场景5: 降级后方向0恢复:");
        boolean[][] full5 = new boolean[20][4];
        for (int i = 0; i < 13; i++) full5[i][0] = true;
        runOutput(new int[]{4,3,0,0}, full5, null, "期望: 前10次-1，11-13走1，14起切回0");

        // 场景6: 两级都满载，第三级正常 → 逐级降级
        System.out.println("场景6: 两级都满载(26次):");
        boolean[][] full6 = new boolean[26][4];
        for (boolean[] f : full6) { f[0] = true; f[1] = true; }
        runOutput(new int[]{4,3,2,0}, full6, null, "期望: 前20次-1(4组10+3组10)，之后 2 2 2 2 2 2");

        // ========== pickInput：输入方向轮询 ==========

        // 场景7: 四方向都有物品 → 轮流 0 1 2 3 0 1 2 3...（旧实现会一直服务最多的方向）
        System.out.println("场景7: 输入方向轮询（四方向有货）:");
        int[] counts = {3, 3, 3, 3};
        runInput(counts, "期望: 0 1 2 3 0 1 2 3 0 1 2 3");

        // 场景8: 方向0有10个、方向1有1个 → 0 1 0 0 0...（方向1不会被饿死）
        System.out.println("场景8: 高压方向0 + 低压方向1:");
        int[] counts2 = {10, 1, 0, 0};
        runInput(counts2, "期望: 0 1 0 0 0 0 0 0 0 0 0");

        // 场景9: 空方向跳过
        System.out.println("场景9: 仅方向2有货:");
        int[] counts3 = {0, 0, 5, 0};
        runInput(counts3, "期望: 2 2 2 2 2");
    }

    // ---------- pickOutput 模拟 ----------

    static void runOutput(int[] w, boolean[][] fullSeq, boolean[][] availSeq, String expect) {
        int[] rr = {0};
        int[] bc = {0};
        int[] ap = {0};
        for (int i = 0; i < fullSeq.length; i++) {
            System.out.print(pickOutput(w, rr, bc, ap, fullSeq[i], availSeq) + " ");
        }
        System.out.println("  (" + expect + ")");
    }

    // 模拟 UniversalJunctionBuild.pickOutput（持久降级逻辑）
    static int pickOutput(int[] weights, int[] rr, int[] bc, int[] ap, boolean[] fullArr, boolean[][] availSeq) {
        int cfgBest = 0;
        for (int d = 0; d < 4; d++) cfgBest = Math.max(cfgBest, weights[d]);
        if (cfgBest <= 0) return -1;

        if (ap[0] <= 0) ap[0] = cfgBest;

        while (ap[0] < cfgBest) {
            int higher = nextHigher(weights, ap[0]);
            if (higher <= 0 || !groupUsable(weights, higher, fullArr, availSeq)) break;
            ap[0] = higher;
        }

        int p = ap[0];
        if (p <= 0) return -1;

        boolean anyDest = false;
        for (int n = 0; n < 4; n++) {
            int d = (rr[0] + n) % 4;
            if (weights[d] != p) continue;
            if (availSeq != null && !availSeq[0][d]) continue;
            anyDest = true;
            if (!fullArr[d]) {
                bc[0] = 0;
                rr[0] = (d + 1) % 4;
                return d;
            }
        }
        if (!anyDest) {
            bc[0] = 0;
            ap[0] = nextLower(weights, p);
            return -1;
        }
        bc[0]++;
        if (bc[0] < threshold) return -1;
        bc[0] = 0;
        ap[0] = nextLower(weights, p);
        return -1;
    }

    static boolean groupUsable(int[] weights, int p, boolean[] fullArr, boolean[][] availSeq) {
        for (int d = 0; d < 4; d++) {
            if (weights[d] != p) continue;
            if (availSeq != null && !availSeq[0][d]) continue;
            if (!fullArr[d]) return true;
        }
        return false;
    }

    static int nextLower(int[] weights, int p) {
        int next = 0;
        for (int d = 0; d < 4; d++) {
            int w = weights[d];
            if (w < p && w > next) next = w;
        }
        return next;
    }

    static int nextHigher(int[] weights, int p) {
        int next = 0;
        for (int d = 0; d < 4; d++) {
            int w = weights[d];
            if (w > p && (next == 0 || w < next)) next = w;
        }
        return next;
    }

    // ---------- pickInput 模拟 ----------

    static void runInput(int[] counts, String expect) {
        int robin = 0;
        for (int i = 0; i < 12; i++) {
            int in = pickInput(counts, robin);
            if (in == -1) {
                System.out.print(-1 + " "); // 所有方向已空
                continue;
            }
            robin = (in + 1) % 4;
            System.out.print(in + " ");
            counts[in]--;
        }
        System.out.println("  (" + expect + ")");
    }

    // 模拟 UniversalJunctionBuild.pickInput（输入方向轮询）
    static int pickInput(int[] counts, int robin) {
        for (int n = 0; n < 4; n++) {
            int i = (robin + n) % 4;
            if (counts[i] > 0) return i;
        }
        return -1;
    }
}