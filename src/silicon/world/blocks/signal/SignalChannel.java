package silicon.world.blocks.signal;

import arc.math.Mathf;
import arc.struct.ObjectMap;
import mindustry.game.Team;
import mindustry.gen.Building;
import silicon.util.SatelliteManager;

/**
 * 信道信号统一计算（SINR 比值制）：
 * - 环境底噪/热噪声（含噪声系数）：固定底噪 N0，参与信噪比分母
 * - 同信道干扰（CCI）：最强源为目标，其余同信道源强度之和为干扰
 * - 邻信道干扰（ACI）：其他信道源强度 × ACIR 泄漏系数
 * - 同信道/全信道干扰器：干扰强度（与信号同模型衰减，0~99 标度）直接叠加；邻信道泄漏（ACIR_jam）
 * - 有效强度 = 信号功率 × 质量因子(SINR)：SINR = best / (N0 + 干扰总和)
 *   SINR ≤ 1（功率压不过噪声+干扰）→ 无信号；SINR ≥ SINR_REF → 满质量；中间平滑。
 *   干扰压的是信噪比（比值），不是从幅度扣功率——强信号天然抗弱干扰。
 */
public class SignalChannel {
    /** 底噪（强度域 0~99，含噪声系数；SINR 分母的固定项）——保持与原 15 标度相同的相对高度（MAX/30） */
    public static final float NOISE_FLOOR = 3.3f;
    /** SINR 参考阈值：SINR ≥ 此值（≈5.4dB）满质量——校准使 GEO 单星 3.48 / LEO 单星 7.92，
     *  与旧线性制的激活阈值（>0.5）与边缘绑定行为对齐 */
    public static final float SINR_REF = 3.5f;

    /** SINR → 质量因子：sinr ≤ 1 → 0（无信号）；sinr ≥ {@link #SINR_REF} → 1（满质量）；中间线性平滑。
     *  有效强度 = 信号功率 × 质量因子；eff > 0 ⟺ 信号功率 > 底噪+干扰（物理可检测的唯一定义） */
    public static float sinrQuality(float signal, float interference) {
        if (signal <= 0f) return 0f;
        float sinr = signal / Math.max(interference, 1e-4f);
        float q = (sinr - 1f) / (SINR_REF - 1f);
        return Math.max(0f, Math.min(1f, q));
    }

    /** 邻信道泄漏系数 ACIR：Δch=0 → 1（本信道），1 → 0.25，2 → 0.08，≥3 → 0（忽略） */
    public static float acir(int dch) {
        int d = Math.abs(dch);
        if (d == 0) return 1f;
        if (d == 1) return 0.25f;
        if (d == 2) return 0.08f;
        return 0f;
    }

    /** 干扰器邻信道泄漏系数（比发射器略大）：Δch=1 → 0.4，2 → 0.12，≥3 → 0 */
    public static float acirJam(int dch) {
        int d = Math.abs(dch);
        if (d == 0) return 1f;
        if (d == 1) return 0.4f;
        if (d == 2) return 0.12f;
        return 0f;
    }

    /**
     * (wx,wy) 处是否处于指定信号 name 的"信号范围"内。
     * 同一编码视为同一信号：在轨卫星的星下点覆盖圆（未被同信道干扰完全压制）、
     * 信号源自身覆盖、同编码激活中继器的级联延伸，三者广播的有效范围取并集。
     * 供卫星控制台 ↔ 卫星发射中枢绑定判定（控制台与中枢必须同处该信号范围内）。
     * 卫星层有上行门控（{@link #hasLiveSource}）：该编码的地面源全部消失后，
     * 在轨卫星停止广播该编码——删源即断链，重放同编码源即恢复。
     */
    public static boolean inSignalRange(Team team, String name, float wx, float wy) {
        if (name == null || name.isEmpty()) return false;
        // 在轨卫星：编码匹配的卫星，其星下点覆盖圆含该点且有效强度 > 0（同信道干扰可打断卫星绑定）。
        // 上行门控：编码无存活地面源时卫星层整体静默（卫星只是地面信号的转发者）
        if (hasLiveSource(team, name)) {
            for (SatelliteManager.SatelliteRecord r : SatelliteManager.satellites(team)) {
                if (r.code != null && name.equals(r.code) && SatelliteManager.satelliteEffAt(r, wx, wy) > 0f) {
                    return true;
                }
            }
        }
        return inGroundSignalRange(team, name, wx, wy);
    }

    /**
     * 仅地面覆盖（信号源 + 激活中继器），卫星覆盖不参与——**按 SINR 判定**：
     * 该编码在 (wx,wy) 处的有效强度 > 0（功率压得过底噪 + 干扰）才算"在范围内"。
     * 供卫星发射的 1:1 配对计数（hubsInSignal/consolesInSignal）与卫星控制台绑定使用：
     * 卫星覆盖只解锁"远方指派"，配对仍约束地面基建布局——否则全图覆盖会把所有中枢算进同一
     * "范围"，多中枢队伍永远 MULTI_HUB，发射能力被自己的卫星锁死。
     */
    public static boolean inGroundSignalRange(Team team, String name, float wx, float wy) {
        return groundEffAt(team, name, wx, wy) > 0f;
    }

    /** 地面有效强度探测缓冲（与覆盖绘制的静态缓冲分开，避免互相覆盖） */
    private static final float[] probeEff = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] probeSrc = new Building[SignalJammer.CHANNEL_MAX + 1];

    /**
     * 指定编码**地面层**（信号源 + 已激活且绑定同编码的中继器）在 (wx,wy) 处的有效强度（SINR 比值制）：
     * 与 {@link #effectiveAll} 的按编码视图同一算法（信号 = 该编码最强的一路地面发射机，干扰 = 其余全部
     * 身份功率和 + 邻信道泄漏 + 干扰器），取各信道最大者。
     * <ul>
     *   <li>编码无存活地面源 → 0（与卫星上行门控同源：源全灭则该编码的地面链路不成立）；</li>
     *   <li>被禁用/断电的信号源功率为 0（{@link SignalSource.SignalSourceBuild#strengthAt}），
     *       已激活的中继器本身也是发射机 → 级联自然成立，不需要几何距离特判；</li>
     *   <li>中继器激活判定、卫星控制台绑定/配对、覆盖与频谱显示共用本方法或同一算法——
     *       所以"看得见的强度"就是"能否转发的强度"。</li>
     * </ul>
     */
    public static float groundEffAt(Team team, String code, float wx, float wy) {
        if (code == null || code.isEmpty() || team == null) return 0f;
        if (!hasLiveSource(team, code)) return 0f;
        // 廉价前置：该编码是否有任何地面发射机覆盖到本点（只做距离衰减，不算干扰）。
        // 没有 → 信号必为 0：远处空闲的中继器每 tick 调用时不必跑完整批算
        boolean inRange = false;
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            if (sb.signal != null && code.equals(sb.signal.name) && sb.strengthAt(wx, wy) > 0f) {
                inRange = true;
                break;
            }
        }
        if (!inRange) {
            for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(team)) {
                if (rb.active && code.equals(rb.selectedSource) && rb.strengthAt(wx, wy) > 0f) {
                    inRange = true;
                    break;
                }
            }
        }
        if (!inRange) return 0f;
        effectiveAll(team, wx, wy, probeEff, probeSrc, null, null, code);
        float best = 0f;
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            if (probeEff[ch] > best) best = probeEff[ch];
        }
        return best;
    }

    // —— 卫星上行门控：卫星广播编码 X 的前提是本队存在存活的地面信号源 X ——

    /** (team, code) → 是否存在存活地面源；SignalSource.markDirty 时整体失效（增删/世界加载/编码下发） */
    private static final ObjectMap<Team, ObjectMap<String, Boolean>> liveSrcCache = new ObjectMap<>();

    /** 失效上行门控缓存（信号源增删、世界加载、编码经 tileConfig 下发时调用） */
    public static void invalidateLiveSources() {
        liveSrcCache.clear();
    }

    /**
     * 指定编码当前是否存在本队存活的地面信号源（删除最后一个该编码源 → false）。
     * 卫星广播的上行门控：卫星只是地面信号的转发者，地面源全部消失后该编码卫星停止广播，
     * 绑定该编码的中继器随之去活；重放同编码源后自动恢复（名册不动，语义与"源被拆不影响卫星信道"的编码固化正交）。
     * 结果按 (team, code) 缓存，失效时机由 {@link silicon.world.blocks.signal.SignalSource#markDirty} 驱动。
     */
    public static boolean hasLiveSource(Team team, String code) {
        if (team == null || code == null || code.isEmpty()) return false;
        ObjectMap<String, Boolean> m = liveSrcCache.get(team);
        if (m == null) {
            m = new ObjectMap<>();
            liveSrcCache.put(team, m);
        }
        Boolean v = m.get(code);
        if (v != null) return v;
        boolean found = false;
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            if (sb.signal != null && code.equals(sb.signal.name)) {
                found = true;
                break;
            }
        }
        m.put(code, found);
        return found;
    }

    // —— 每信道批量计算（覆盖绘制用）：静态缓冲，一次遍历全部源按信道分摊 ——
    private static final float[] bestA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] bestSrcA = new Building[SignalJammer.CHANNEL_MAX + 1];
    private static final String[] bestIdA = new String[SignalJammer.CHANNEL_MAX + 1];
    /** 每信道最强身份对应的编码（"S"+编码 身份的第一个字符是 'S'；未绑定中继的 "R<pos>" 身份为 null） */
    private static final String[] bestCodeA = new String[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] otherA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] aciA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] jamA = new float[SignalJammer.CHANNEL_MAX + 1];
    /** 指定编码视图：该编码在本信道的最强功率 / 其余全部身份的功率和 / 该编码最强那一台的建筑 */
    private static final float[] viewA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final float[] viewOtherA = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final Building[] viewSrcA = new Building[SignalJammer.CHANNEL_MAX + 1];

    /**
     * 单台干扰器按「同信道 + 邻信道泄漏（ACIR_jam）」累加进 out[1..CHANNEL_MAX]。
     * 卫星层与地面层共用本方法，避免两层各写一份导致口径分叉（曾出现地面求和/卫星取最大的不一致）。
     */
    private static void addJammer(int jamChannel, float j, float[] out) {
        if (jamChannel == SignalJammer.ALL) {
            for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) out[ch] += j;
            return;
        }
        int c = jamChannel;
        out[c] += j;
        if (c > 1) out[c - 1] += j * acirJam(1);
        if (c < SignalJammer.CHANNEL_MAX) out[c + 1] += j * acirJam(1);
        if (c > 2) out[c - 2] += j * acirJam(2);
        if (c < SignalJammer.CHANNEL_MAX - 1) out[c + 2] += j * acirJam(2);
    }

    /** 单信道干扰查询的静态缓冲（同一时刻只被 {@link #jammerAt} 使用，不与 effectiveAll 的批量缓冲冲突） */
    private static final float[] jamTmp = new float[SignalJammer.CHANNEL_MAX + 1];

    /**
     * 指定信道在 (wx,wy) 处的干扰总和（干扰器功率求和 + 邻信道泄漏；含全信道干扰器、不分队伍）。
     * <b>与 {@link #effectiveAll} 的 jamA 同一算法</b>——卫星层（{@link silicon.util.SatelliteManager#satelliteEffAt}）
     * 走本方法，地面层走批量版，两层口径必须一致：多台干扰器叠加压制，而不是只取最强一台。
     */
    public static float jammerAt(int channel, float wx, float wy) {
        if (channel < 1 || channel > SignalJammer.CHANNEL_MAX) return 0f;
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) jamTmp[ch] = 0f;
        for (SignalJammer.SignalJammerBuild jb : SignalJammer.allJammers()) {
            if (!jb.enabled) continue; // 关闭（enabled=false）不发射干扰
            float j = SignalSource.strengthAt(jb.x, jb.y, wx, wy);
            if (j <= 0f) continue;
            addJammer(jb.jamChannel, j, jamTmp);
        }
        return jamTmp[channel];
    }

    /** 身份串 → 编码：源/中继的身份是 "S"+编码；"R<pos>"（未绑定中继）等无编码身份返回 null */
    static String codeOfId(String id) {
        return (id != null && id.length() > 1 && id.charAt(0) == 'S') ? id.substring(1) : null;
    }

    /** 将某源信号按信道分摊：本信道按身份计入 best/other，邻信道计入 ACI；
     *  viewId 非 null 时额外统计「该编码自身功率 / 其余身份功率和」（覆盖与频谱的按编码视图用） */
    private static void addSource(int ch, float s, String id, Building src, String viewId) {
        if (ch < 1 || ch > SignalJammer.CHANNEL_MAX) return;
        if (ch > 1) aciA[ch - 1] += s * acir(1);
        if (ch < SignalJammer.CHANNEL_MAX) aciA[ch + 1] += s * acir(1);
        if (ch > 2) aciA[ch - 2] += s * acir(2);
        if (ch < SignalJammer.CHANNEL_MAX - 1) aciA[ch + 2] += s * acir(2);
        // 本信道：同身份取最强不互扰，不同身份计 CCI
        if (id.equals(bestIdA[ch])) {
            if (s > bestA[ch]) {
                bestA[ch] = s;
                bestSrcA[ch] = src;
                bestCodeA[ch] = codeOfId(id);
            }
        } else if (s > bestA[ch]) {
            otherA[ch] += bestA[ch];
            bestA[ch] = s;
            bestIdA[ch] = id;
            bestSrcA[ch] = src;
            bestCodeA[ch] = codeOfId(id);
        } else {
            otherA[ch] += s;
        }
        // 指定编码视图：本编码取最强（同码多台视为同一路信号），其余身份（含当前最强那一路）
        // 全部计入该编码面对的 CCI——这样显示出的强度就是该编码在该点真实可用的强度
        if (viewId != null) {
            if (id.equals(viewId)) {
                if (s > viewA[ch]) {
                    viewA[ch] = s;
                    viewSrcA[ch] = src;
                }
            } else {
                viewOtherA[ch] += s;
            }
        }
    }

    /**
     * 批量计算位置 (wx,wy) 所有信道（1~5）的有效信号强度。
     * 一次遍历全部信号源/中继器/干扰器，按信道分摊（含底噪/CCI/ACI/干扰器），
     * 结果写入 effOut[1..5] 与 srcOut[1..5]（最强同信道源，用于颜色）。
     * 比逐信道调用 effective 快约 5 倍（覆盖绘制用）。
     */
    public static void effectiveAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut) {
        effectiveAll(team, wx, wy, effOut, srcOut, null, null, null);
    }

    /**
     * 同上，额外把每信道"底噪+干扰总和"（SINR 分母 I）写入 intOut[1..5]——
     * 频谱面板用它区分"本点干扰功率"与"可用有效强度"；传 null 等价于无干扰输出。
     */
    public static void effectiveAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut, float[] intOut) {
        effectiveAll(team, wx, wy, effOut, srcOut, intOut, null, null);
    }

    /**
     * 单点 5 信道的**最终可用度**（显示端唯一实现）：地面 SINR 有效强度 ⊕ 卫星层非相干功率合成。
     * H 覆盖、频谱面板（信号源/中继器/检测器）都调用它，所以 H 上显示的数字**必然**等于频谱里最高的那一行，
     * 不会出现"覆盖说 8、检测器说 12"这种两处各写一套算法导致的分叉。
     *
     * <p>卫星层口径（与判定端一致，细节见 {@code SatelliteManager}）：
     * <ul>
     *   <li>{@code scopeCode} 非 null（源/中继/控制台面板、鼠标悬停查看某个编码）→ 只叠该编码的卫星；</li>
     *   <li>{@code scopeCode} 为 null（检测器/自动）→ 逐信道跟随该信道地面最强编码；该信道没有地面信号时
     *       取该信道最强卫星编码（仍是单一编码；未绑定卫星给出 null 编码 → 调用方走蓝色渐变）。</li>
     * </ul>
     * 每格只算一个编码，跨编码求和会算出任何中继都拿不到的数值。
     *
     * @param effOut  出参：每信道最终可用度（含卫星合成）
     * @param srcOut  出参：每信道贡献来源建筑；卫星层主导（或该信道只有卫星）时为 null
     * @param codeOut 出参（**必须非 null**）：每信道最终归属编码（卫星主导时可为卫星编码；无信号为 null）
     * @param intOut  出参（可 null）：每信道 SINR 分母 I（地面部分，频谱"干扰"列用）
     */
    public static void usableAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut,
                                 float[] intOut, String[] codeOut, String scopeCode) {
        effectiveAll(team, wx, wy, effOut, srcOut, intOut, codeOut, scopeCode);
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) usableSatSq[ch] = 0f;
        if (scopeCode != null) {
            for (silicon.util.SatelliteManager.SatelliteRecord r : silicon.util.SatelliteManager.satellites(team)) {
                if (!scopeCode.equals(r.code)) continue;
                // 上行门控：与自动分支（下方 else）、判定端 satelliteStrengthAt、频谱占用计数同一口径。
                // 缺这一句会让「地面源被拆光后卫星停止广播」的语义只在判定端成立，而 H 覆盖/频谱仍按
                // scopeCode 把该编码卫星算进显示值——同屏两个数字打架。
                if (!hasLiveSource(team, r.code)) continue;
                int rc = r.channel;
                if (rc < 1 || rc > SignalJammer.CHANNEL_MAX) continue;
                float e = silicon.util.SatelliteManager.satelliteEffAt(r, wx, wy);
                if (e > 0f) usableSatSq[rc] += e * e;
            }
        } else {
            // 自动：只累加"与本信道地面最强编码同名"的卫星（该信道没有地面信号的情况在下面单独取最强卫星编码）
            for (silicon.util.SatelliteManager.SatelliteRecord r : silicon.util.SatelliteManager.satellites(team)) {
                int rc = r.channel;
                if (rc < 1 || rc > SignalJammer.CHANNEL_MAX) continue;
                if (r.code == null || codeOut[rc] == null || !codeOut[rc].equals(r.code)) continue;
                if (!hasLiveSource(team, r.code)) continue; // 上行门控
                float e = silicon.util.SatelliteManager.satelliteEffAt(r, wx, wy);
                if (e > 0f) usableSatSq[rc] += e * e;
            }
        }
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            float s;
            if (scopeCode != null || codeOut[ch] != null) {
                s = silicon.util.SatelliteManager.stackEff(usableSatSq[ch]);
            } else {
                usableSatCode[0] = null;
                s = silicon.util.SatelliteManager.bestSatelliteAt(team, wx, wy, usableSatCode, ch);
                if (s > 0f) codeOut[ch] = usableSatCode[0]; // 只有卫星：归属该信道最强卫星编码（未绑定 = null）
            }
            if (s <= 0f) continue;
            float g = effOut[ch];
            effOut[ch] = (float) Math.sqrt((double) g * g + (double) s * s);
            if (s > g) srcOut[ch] = null; // 卫星层主导：不画建筑专属色，调用方按 codeOut 上色
        }
    }

    /** {@link #usableAll} 的卫星层累加缓冲（单线程渲染/游戏线程内串行使用） */
    private static final float[] usableSatSq = new float[SignalJammer.CHANNEL_MAX + 1];
    private static final String[] usableSatCode = new String[1];

    /**
     * 全参数版：支持"按编码计算"。
     *
     * @param codeOut  出参（可 null）：每信道结果所属的编码——viewCode 为 null 时是该信道最强身份的编码
     *                 （未绑定中继等无编码身份为 null）；viewCode 非 null 时即 viewCode
     * @param viewCode 非 null/空时只算该编码：信号功率 = 该编码在本信道的最强一路（同码多台视为同一路），
     *                 干扰 = 其余全部身份功率和 + 邻信道泄漏 + 干扰器。
     *                 为 null 时沿用「本信道最强身份」语义（旧的全局视图）。
     *                 <p>判定端（中继激活/绑定）本来就是逐编码的（{@link #inSignalRange} 按名字匹配），
     *                 显示端用本参数对齐后，格子上的数字就是该编码真正可用的强度。
     */
    public static void effectiveAll(Team team, float wx, float wy, float[] effOut, Building[] srcOut,
                                    float[] intOut, String[] codeOut, String viewCode) {
        String viewId = (viewCode == null || viewCode.isEmpty()) ? null : "S" + viewCode;
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            bestA[ch] = 0f;
            bestSrcA[ch] = null;
            bestIdA[ch] = null;
            bestCodeA[ch] = null;
            otherA[ch] = 0f;
            aciA[ch] = 0f;
            jamA[ch] = 0f;
            viewA[ch] = 0f;
            viewOtherA[ch] = 0f;
            viewSrcA[ch] = null;
        }
        // 信号源
        for (SignalSource.SignalSourceBuild sb : SignalSource.allSources(team)) {
            float s = sb.strengthAt(wx, wy);
            if (s <= 0f) continue;
            addSource(sb.channel, s, "S" + sb.signal.name, sb, viewId);
        }
        // 激活中继器（级联源；发射信道与所选信号源一致）
        for (SignalRelay.SignalRelayBuild rb : SignalRelay.allRelays(team)) {
            if (!rb.active) continue;
            float s = rb.strengthAt(wx, wy);
            if (s <= 0f) continue;
            String id = (rb.selectedSource != null && !rb.selectedSource.isEmpty())
                    ? "S" + rb.selectedSource : "R" + ((int) rb.x * 7 + (int) rb.y * 13);
            addSource(rb.signalChannel(), s, id, rb, viewId);
        }
        // 干扰器（全局：敌方干扰器同样压制本信道；同信道 + 邻信道泄漏）
        // enabled 守卫与 SignalJammer.strengthAt 口径一致:关闭(enabled=false)不发射干扰——
        // 否则 H 覆盖中同一干扰器在信道层"仍在压制"、卫星层却已消失,自相矛盾
        // 累加逻辑统一走 addJammer（与卫星层的 jammerAt 共用同一份实现）
        for (SignalJammer.SignalJammerBuild jb : SignalJammer.allJammers()) {
            if (!jb.enabled) continue;
            float j = SignalSource.strengthAt(jb.x, jb.y, wx, wy);
            if (j <= 0f) continue;
            addJammer(jb.jamChannel, j, jamA);
        }
        // 每信道有效信号 = 信号功率 × 质量因子(SINR)：干扰压信噪比，不从幅度扣功率
        for (int ch = 1; ch <= SignalJammer.CHANNEL_MAX; ch++) {
            boolean byCode = viewId != null;
            float sig = byCode ? viewA[ch] : bestA[ch];
            float i = NOISE_FLOOR + (byCode ? viewOtherA[ch] : otherA[ch]) + aciA[ch] + jamA[ch];
            effOut[ch] = sig * sinrQuality(sig, i);
            srcOut[ch] = byCode ? viewSrcA[ch] : bestSrcA[ch];
            if (intOut != null) intOut[ch] = i;
            if (codeOut != null) codeOut[ch] = byCode ? viewCode : bestCodeA[ch];
        }
    }
}

