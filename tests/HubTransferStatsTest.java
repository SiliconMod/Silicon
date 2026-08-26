/**
 * 中枢单跳计费与吞吐统计行为测试（独立可编译，不参与模组构建）
 *
 * 用法：javac --release 17 -encoding UTF-8 HubTransferStatsTest.java && java HubTransferStatsTest
 *
 * 镜像 ItemTransferHubBuild 当前实现的关键算法（a0.11.21.0 口径）：<p>
 * 1. chargeOne：本枢写入计费平滑缓冲（smoothBuf），远端枢走 *Next 延迟一帧<br>
 * 2. 瞬时请求 = 最近 SMOOTH_TICKS(30) 帧计费均值——摊平 6Hz 批量突发，电网侧无耗电锋<br>
 * 3. 电力消耗按秒计算（60tick 滑动窗口）；运输速率为 10 秒滑动窗口——
 *    稳态流量下 耗电 ≈ 10 × 速率 仍然成立<br>
 * 4. 电力硬门控：status < POWER_OK 完全停止并进入冷却（60t）；冷却结束后先「探测」
 *    （发真实请求但不搬运），请求被足额交付后才恢复——无源电网永不空转白嫖<br>
 * 5. relayable 路径过滤：欠压/禁用中枢不可作为路径节点或端点<br>
 * 6. 停止态帧首折叠照常执行，但随后清零——禁用期不累积、恢复无尖峰
 *
 * 测试对象是镜像的纯逻辑模型，不依赖 Mindustry 运行时；修改中枢计费/统计代码后
 * 应同步核对本测试的镜像是否仍与实现一致。
 */
public class HubTransferStatsTest {
    static final float UNIT = 10f;
    static int total = 0, pass = 0;

    /** 极简整型滑动窗口：容量 cap 个 tick 桶，O(1) 均值。 */
    static class Window {
        final int[] buf; int head = 0, size = 0; long sum = 0;
        Window(int cap) { buf = new int[cap]; }
        void push(int v) {
            if (size == buf.length) { sum -= buf[head]; } else { size++; }
            buf[head] = v; sum += v;
            head = (head + 1) % buf.length;
        }
        float ratePerSecond() { return size == 0 ? 0f : sum / (size / 60f); }
    }

    /** 浮点滑动窗口：电力消耗用（60 tick = 每秒），记录每帧实际取电。 */
    static class PowerWindow {
        final float[] buf; int head = 0, size = 0; float sum = 0;
        PowerWindow(int cap) { buf = new float[cap]; }
        void push(float v) {
            if (size == buf.length) { sum -= buf[head]; } else { size++; }
            buf[head] = v; sum += v;
            head = (head + 1) % buf.length;
        }
        float perSecond() { return size == 0 ? 0f : sum / (size / 60f); }
    }

    /** 枢纽模型：逐行镜像 updateTile 帧首折叠/门控/平滑/统计 与 chargeOne。 */
    static class Hub {
        static final float POWER_OK = 0.999f;
        static final int COOLDOWN = 60;
        static final int SMOOTH = 30;
        static final float PROBE_DRAW = 10f;

        final String name;
        boolean enabled = true, hasPower = true;
        float status = 1f;

        float powerConsumed = 0f, powerConsumedNext = 0f;
        int transferCount = 0, transferCountNext = 0;

        final float[] smoothBuf = new float[SMOOTH]; int smoothIdx = 0;
        boolean probing = false, powerStarved = false; int starveCooldown = 0;

        final Window counts = new Window(600);
        final PowerWindow powerWin = new PowerWindow(60);
        float powerPerSecond = 0f, transferRate = 0f;
        int tick = 0;

        Hub(String name) { this.name = name; }

        float smoothSum() { float s = 0f; for (float v : smoothBuf) s += v; return s; }

        boolean relayable(Hub h) { return h.enabled && h.hasPower && h.status >= POWER_OK && !h.powerStarved; }

        /** 镜像 updateTile 帧首：推进平滑窗口并折叠延迟量。 */
        void beginFrame() {
            smoothIdx = (smoothIdx + 1) % SMOOTH; smoothBuf[smoothIdx] = 0f;
            smoothBuf[smoothIdx] += powerConsumedNext; powerConsumedNext = 0f;
            transferCount += transferCountNext; transferCountNext = 0;
            tick++;
        }

        /** 镜像 updateTile 帧尾：门控 → 调度后请求计算 → 同窗统计。 */
        void endFrame() {
            if (!enabled || !hasPower || status < POWER_OK) {
                powerStarved = true; probing = false; starveCooldown = COOLDOWN;
                java.util.Arrays.fill(smoothBuf, 0f); powerConsumed = 0f;
            } else if (starveCooldown > 0) {
                powerStarved = true;
                if (--starveCooldown == 0) {
                    probing = true;
                    powerConsumed = Math.max(smoothSum() / SMOOTH, PROBE_DRAW);
                } else {
                    probing = false;
                    java.util.Arrays.fill(smoothBuf, 0f); powerConsumed = 0f;
                }
            } else {
                powerStarved = false; probing = false;
                // 调度先于请求计算：驱动器在 begin/end 之间经 chargeOne 注入本枢计费
                powerConsumed = smoothSum() / SMOOTH;
            }

            float actualPower = powerConsumed * (hasPower ? Math.min(status, 1f) : 0f);

            counts.push(transferCount); transferCount = 0;
            powerWin.push(actualPower);

            // 统计刷新不区分停止态：窗口推入零桶自然衰减（速率 10s 内平滑降零）
            if (tick % 10 == 0) {
                transferRate = counts.ratePerSecond();
                powerPerSecond = powerWin.perSecond();
            }
        }

        /** 无调度帧（远程折叠等被动场景）。 */
        void frame() { beginFrame(); endFrame(); }

        /** 单跳计费/计数：本枢写入平滑缓冲，远端枢写入延迟队列。 */
        void chargeOne(Hub h, int moved) {
            float share = UNIT * moved;
            if (h == this) { h.smoothBuf[h.smoothIdx] += share; h.transferCount += moved; }
            else { h.powerConsumedNext += share; h.transferCountNext += moved; }
        }

        /** 镜像 chargeBatch：路径逐枢各一跳 / 不可达时端点各一跳。 */
        void chargeBatch(Hub srcHub, Hub dstHub, int moved, Hub[] pathOrNull) {
            if (pathOrNull != null) {
                java.util.LinkedHashSet<Hub> seen = new java.util.LinkedHashSet<>();
                for (Hub h : pathOrNull) { if (seen.add(h)) chargeOne(h, moved); }
            } else {
                chargeOne(srcHub, moved); chargeOne(dstHub, moved);
            }
        }

        /** 镜像 bfsPath：邻居扩展按 relayable 过滤。 */
        static boolean pathExists(Hub src, Hub dst) {
            if (src == dst) return true;
            java.util.ArrayDeque<Hub> q = new java.util.ArrayDeque<>();
            java.util.HashSet<Hub> seen = new java.util.HashSet<>();
            seen.add(src); q.add(src);
            while (!q.isEmpty()) {
                Hub cur = q.poll();
                if (cur == dst) return true;
                for (Hub nb : cur.neighbors) {
                    if (!cur.relayable(nb)) continue; // 路径不经过欠压/禁用中枢
                    if (seen.add(nb)) q.add(nb);
                }
            }
            return false;
        }

        final java.util.List<Hub> neighbors = new java.util.ArrayList<>();
    }

    static void check(boolean cond, String msg) {
        total++;
        if (cond) { pass++; System.out.println("PASS " + msg); }
        else { System.out.println("FAIL " + msg); }
    }
    static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    public static void main(String[] args) {
        // ========== 场景1：同枢直转计费进平滑缓冲，请求摊平不跳变且守恒 ==========
        Hub a = new Hub("A");
        a.beginFrame(); a.chargeOne(a, 10); a.endFrame();   // 单批 100 电费
        check(near(a.powerConsumed, 100f / Hub.SMOOTH, 0.01f), "场景1 单批100计费摊平为请求 100/30");
        float integrated = a.powerConsumed;
        for (int i = 0; i < 59; i++) { a.frame(); integrated += a.powerConsumed; }
        check(near(integrated, 100f, 0.5f), "场景1 请求积分总量 = 计费总额100（摊平不失守恒）");

        // ========== 场景2：跨枢延迟计费并入远端枢平滑缓冲 ==========
        Hub b = new Hub("B");
        a = new Hub("A");
        a.chargeOne(b, 10);                        // 远端一跳写 Next
        b.frame();
        check(near(b.powerConsumed, 100f / Hub.SMOOTH, 0.01f), "场景2 B 下一帧并入计费并摊平");
        check(b.transferCount == 0 && b.counts.sum == 10, "场景2 计数已入滑动窗口桶");

        // ========== 场景3：稳态流量下耗电 ≈ 10 × 速率（耗电按秒、速率按 10 秒）==========
        Hub relay = new Hub("R"), src = new Hub("S");
        for (int f = -99; f <= 600; f++) {         // 先预热 100 帧排空启动瞬态，再计量
            if (((f % 10) + 10) % 10 == 0) {       // 每 10 帧 S 发起 10 件途经 R
                src.beginFrame();
                src.chargeOne(src, 10);
                src.chargeOne(relay, 10);          // 远端一跳写延迟队列
                src.endFrame();
            } else {
                src.frame();
            }
            relay.frame();
        }
        check(near(relay.transferRate, 60f, 0.5f), "场景3 中转枢速率 = 60件/秒");
        check(near(relay.powerPerSecond, 600f, 6f), "场景3 中转枢耗电 = 600/s");
        check(near(relay.powerPerSecond, 10f * relay.transferRate, 8f), "场景3 耗电:速率严格 10:1（同窗）");

        // ========== 场景4：瞬时请求平稳性（旧实现单帧尖峰100，现≤10+探测下限）==========
        Hub g = new Hub("G");
        float maxReq = 0f;
        for (int f = 0; f < 700; f++) {            // ≥10s：让速率的 600t 窗口也饱和
            if (f % 10 == 0) {
                g.beginFrame(); g.chargeOne(g, 10); g.endFrame();   // 每帧一批 100 电费
            } else {
                g.frame();
            }
            maxReq = Math.max(maxReq, g.powerConsumed);
        }
        check(maxReq <= 10.5f, "场景4 瞬时请求峰值 ≤ 单帧摊平值（" + maxReq + "）");
        check(near(g.powerPerSecond, 10f * g.transferRate, 8f), "场景4 稳态下耗电 ≈ 10×速率");

        // ========== 场景5：连续帧折叠无膨胀（赋值语义镜像：槽位推进即清出）==========
        Hub m = new Hub("M");
        for (int f = 0; f < 200; f++) {
            m.powerConsumedNext += 100f;           // 每帧都有远端计费写入
            m.transferCountNext += 10;
            m.frame();
        }
        check(near(m.powerConsumed, 100f, 0.001f), "场景5 稳态帧请求恒为单帧量（100），不随时间膨胀");
        check(m.transferCount == 0, "场景5 帧后计数已入窗口桶并清零");

        // ========== 场景6：欠压硬切断 → 冷却 → 探测 → 恢复（有源电网）==========
        Hub d = new Hub("D");
        d.beginFrame(); d.chargeOne(d, 10); d.endFrame();   // 先正常工作一帧
        d.status = 0.5f; d.frame();                // 欠压 → 完全停止 + 进入冷却
        check(d.powerStarved && d.starveCooldown == 60 && d.powerConsumed == 0f, "场景6a 欠压即停转并进入冷却60t");
        long countsBefore = d.counts.sum;
        d.status = 1f;                             // 电网回血
        for (int i = 0; i < 59; i++) d.frame();    // 冷却期：无请求、无吞吐增量
        check(near(d.powerConsumed, 0f, 0.0001f), "场景6b 冷却期请求为零");
        check(d.counts.sum == countsBefore, "场景6b 冷却期零搬运");
        d.frame();                                  // 冷却结束帧：发出探测请求
        check(near(d.powerConsumed, 10f, 0.001f) && d.powerStarved, "场景6c 冷却结束发探测请求且不搬运");
        d.frame();                                  // 探测被足额交付 → 恢复
        check(!d.powerStarved && !d.probing, "场景6d 探测通过恢复正常工作");

        // ========== 场景7：无源电网（探测永远不被交付）永不恢复搬运 ==========
        Hub e = new Hub("E");
        e.beginFrame(); e.chargeOne(e, 10); e.endFrame();
        e.status = 0f; e.frame();                  // 彻底断电
        boolean everOperated = false;
        for (int i = 0; i < 1200; i++) {           // 模拟 20 秒“来电又不足”的抖动供电
            e.status = (i % 13 == 0) ? 1f : 0.4f;  // status 读数在零请求帧会被置满（镜像原版行为）
            if (e.powerConsumed > 0.001f) {
                // 有请求的帧，电网无法足额交付 → 下一帧必然跌破阈值
                e.status = 0.3f;
            }
            e.frame();
            if (!e.powerStarved) everOperated = true;
        }
        check(!everOperated, "场景7 无源电网探测永不通过，始终完全停止");

        // ========== 场景8：禁用期间被路过——不累积、恢复无尖峰 ==========
        Hub off = new Hub("OFF");
        off.enabled = false;
        for (int f = 0; f < 120; f++) {
            off.powerConsumedNext += 100f;
            off.transferCountNext += 10;
            off.frame();
        }
        check(off.powerConsumed == 0f && off.powerWin.sum == 0f, "场景8a 禁用期请求与取电为零");
        check(off.powerConsumedNext == 0f && off.transferCountNext == 0, "场景8b 延迟队列每帧清空不累积");
        off.enabled = true;
        float spikeMax = 0f;
        for (int f = 0; f < 70; f++) {             // 经冷却+探测恢复，全程无历史尖峰
            off.frame();
            spikeMax = Math.max(spikeMax, f < 61 ? off.powerConsumed : 0f);
        }
        check(!off.powerStarved, "场景8c 重新启用后经冷却+探测恢复");
        check(spikeMax <= 10.5f, "场景8d 恢复过程请求峰值受探测量约束");

        // ========== 场景9：路径过滤——欠压中枢不可作为中转节点 ==========
        Hub x = new Hub("X"), y = new Hub("Y"), z = new Hub("Z");
        x.neighbors.add(y); y.neighbors.add(z);
        check(Hub.pathExists(x, z), "场景9a 健康拓扑可达");
        y.status = 0.5f; y.frame();
        check(y.powerStarved, "场景9b 欠压枢进入停止态");
        check(!Hub.pathExists(x, z), "场景9c 路径选择跳过欠压中枢（X→Z 不可达）");
        // 不可达兜底：端点各记一跳，第三方不买单
        x.chargeBatch(x, z, 4, null);
        check(near(x.smoothSum(), UNIT * 4f, 0.001f) && x.transferCount == 4, "场景9d 端点 X 记自己一跳");
        check(z.transferCountNext == 4, "场景9e 端点 Z 记延迟一跳");
        check(y.smoothSum() == 0f && y.powerConsumedNext == 0f, "场景9f 无关枢纽不被计费");

        // ========== 场景10：断电帧请求与显示归零 ==========
        Hub p = new Hub("P");
        p.status = 0f; p.frame();
        check(p.powerConsumed == 0f && p.powerPerSecond == 0f, "场景10 断电帧请求归零、秒级显示归零");

        // ========== 场景11：断电后运输速率随 10s 窗口平滑衰减（而非瞬间清零）==========
        Hub w = new Hub("W");
        for (int f = 1; f <= 600; f++) {
            if (f % 10 == 0) { w.beginFrame(); w.chargeOne(w, 10); w.endFrame(); } else { w.frame(); }
        }
        float rateFull = w.transferRate;              // 满载 ≈ 60/s
        w.status = 0f;
        for (int k = 1; k <= 300; k++) w.frame();     // 断电 5 秒
        float rateMid = w.transferRate;
        check(rateFull > 55f, "场景11a 满载速率 ≈ 60/s（" + rateFull + "）");
        check(near(rateMid, rateFull / 2f, 6f), "场景11b 断电 5 秒后速率平滑过半（" + rateMid + "）");
        for (int k = 301; k <= 595; k++) w.frame();   // 断电约 10 秒
        float rateEnd = w.transferRate;
        check(rateMid > rateEnd && rateEnd < 3f, "场景11c 断电 10 秒后速率平滑降至零附近（" + rateEnd + "）");

        System.out.println("== 结果: " + pass + "/" + total + " 通过 ==");
        if (pass != total) System.exit(1);
    }
}
