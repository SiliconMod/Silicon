package silicon.audio;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Streams;
import arc.util.serialization.Json;
import mindustry.game.EventType;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

import static mindustry.Vars.player;

/**
 * 音乐播放器核心（本机播放 + 曲目库 + 本地缓存 + 启用开关 + 持久化）。
 * <p>
 * - 曲目库：内置原版音乐（INTERNAL）/ 网络 URL / 本地磁盘路径（LOCAL）
 * - 所有曲目统一解析为本地弧音频 {@link arc.files.Fi} 后经 {@link Sound#createStream} 播放，
 *   从而支持 {@code Sound.at} 的 3D 定位（声源随播放者移动、听者按距离远近衰减）。
 * - 「本地缓存优先复用」：URL / 二进制共享写入 cache/music/，同 cacheHash 直接复用不再走网络。
 * - 「是否启用」总开关（双生效：本机不能播 + 不接收/不听别人）。
 * <p>
 * 本机作为 owner 时，声源原点取玩家自身位置；多人远程声源见 {@link MusicNetwork}，可叠加多个。
 */
public class MusicPlayer {
    /** 循环模式：0=关闭 1=列表循环 2=单曲循环 3=随机（假随机，队列打乱不重播）4=单曲播完即停 5=随机（真随机，每次独立抽签，可连播同曲） */
    public static final int LOOP_OFF = 0, LOOP_LIST = 1, LOOP_ONE = 2, LOOP_SHUFFLE = 3, LOOP_ONE_STOP = 4, LOOP_RANDOM = 5;

    private static final String CFG_TRACKS = "musicplayer.tracks";
    private static final String CFG_VOLUME = "musicplayer.volume";
    private static final String CFG_PITCH = "musicplayer.pitch";
    private static final String CFG_LOOP = "musicplayer.loopmode";
    private static final String CFG_ENABLED = "musicplayer.enabled";
    private static final String CFG_SHARE = "musicplayer.share";
    private static final String CFG_LAST = "musicplayer.lastIndex";
    private static final String CFG_ALBUMS = "musicplayer.albums";
    private static final String CFG_ALBUM = "musicplayer.album";
    private static final String CFG_SPEED = "musicplayer.speed";
    private static final String CFG_AB_A = "musicplayer.ab.a";
    private static final String CFG_AB_B = "musicplayer.ab.b";
    private static final String CFG_REVERSE = "musicplayer.reverse";

    private static final String[] INTERNAL_KEYS = {
        "game1","game2","game3","game4","game5","game6","game7","game8","game9",
        "boss1","boss2","fine","editor","menu","land","launch"
    };

    private static final Pattern EXT_WHITELIST = Pattern.compile("(?i)\\.(ogg|mp3|wav|flac|m4a|wma|aac|opus)$");
    /** Soloud 内置解码器可解码的格式（stb_vorbis=ogg / dr_mp3=mp3 / stb_wav=wav）。flac/m4a/wma/aac/opus 只能做字节共享，不能在本机解码播放（会原生崩溃） */
    private static final Pattern DECODABLE_EXT = Pattern.compile("(?i)\\.(ogg|mp3|wav)$");
    /** 时长探测的文件大小上限（字节）：超过则跳过 Music.create 解码读取，避免大文件解码卡顿/占用过多内存 */
    private static final long LENGTH_PROBE_SIZE_LIMIT = 64L * 1024 * 1024;
    /** 读时长而不拷贝时允许的文件大小上限：Music.create(f) 只解 header 求长度、不整文件解码，
     *  故可放宽到 512MB（30–60 分钟常见码率音频常超 64MB，旧上限会误判成「不显示长度」）。
     *  真正的整文件磁盘拷贝（localAsciiCopy）仍受 LENGTH_PROBE_SIZE_LIMIT 限制。 */
    private static final long LENGTH_READ_SIZE_LIMIT = 512L * 1024 * 1024;
    /** 倍速最小值（对数滑杆 1/16–16x） */
    public static final float MIN_SPEED = 1f / 16f;
    /** 格式嗅探探测窗口（字节）：要能覆盖带大 ID3v2 tag（含封面，可达数百 KB）的 mp3，把帧同步找到 */
    private static final int PROBE_WINDOW = 1 * 1024 * 1024;
    private static final String CACHE_DIR = "music/";

    /**
     * 歌曲缓存根目录：一律放在游戏数据目录下的 cache 子目录（绝对定位），
     * 绝不用 Core.files.cache —— 后者 base 跟随进程 CWD，从 System32/C:\Windows 启动时
     * 会解析为 C:\Windows\cache\music\（不可写）导致「缓存写失败、内置曲提取失败、任何歌曲都无法播放」。
     */
    private static Fi cacheRoot() {
        Fi base = null;
        try { base = Core.settings.getDataDirectory(); } catch (Exception ignored) {}
        if (base == null) try { base = Core.files.local(""); } catch (Exception ignored) {}
        Fi dir = base == null ? Core.files.cache(CACHE_DIR) : base.child("cache").child(CACHE_DIR);
        try { dir.mkdirs(); } catch (Exception ignored) { }
        return dir;
    }

    /** 缓存目录下指定名称的文件（自动确保父目录存在） */
    public static Fi cacheFile(String name) {
        return cacheRoot().child(name);
    }
    /** 声场衰减参考半径（格）：>1200 基本听不见 */
    static final float FALLOFF_RADIUS = 1200f;

    private static final Seq<MusicTrack> tracks = new Seq<>();
    private static final Seq<Voice> voices = new Seq<>();
    private static final Json json = new Json();

    private static int current = -1;
    /** 「启用」总开关：控制网络（收发）。关时本地仍可播放，但不接收别人、也不广播给他人 */
    private static boolean enabled = true;
    /** 「播放给他人」独立开关：仅控制是否广播本机曲目给其他玩家；关时仍可收听别人（前提 enabled） */
    private static boolean shareEnabled = true;
    private static float volume = 1f;
    private static float pitch = 1f;
    /** 倍速（相对播放速率 0.1–16x）；与音高叠加为实际 Soloud 速率 pitch*speed */
    private static float speed = 1f;
    private static int loopMode = LOOP_LIST;

    /** A-B 区间两点（秒）；<0 表示未设置。两点按 min/max 取区间，可实现区间重复 */
    private static float abA = -1f;
    private static float abB = -1f;
    /** 倒放开关：开启时播放进度持续回退（每帧按 时间*速率 反向 seek），近似倒着听；到达开头自动停止 */
    private static boolean reverse = false;

    /** 专辑：一组曲目（存放曲目 cacheHash 引用），可按专辑整体播放 */
    public static final int MAX_ALBUM_NAME_LENGTH = 24;
    public static final int MAX_TRACK_NAME_LENGTH = 64;
    public static class Album {
        public String name;
        public Seq<String> hashes = new Seq<>();
        public Album() {}
        public Album(String name) { this.name = name.length() > MAX_ALBUM_NAME_LENGTH ? name.substring(0, MAX_ALBUM_NAME_LENGTH) : name; }
    }

    private static final Seq<Album> albums = new Seq<>();
    /** 当前激活的专辑名（null = 全部曲目）；决定「上一首/下一首」在专辑内切换并限定循环范围 */
    private static String activeAlbum = null;

    private static boolean playing = false;
    private static int localVoiceId = -1;
    private static float lastBlip = 0;
    /** 暂停时保存的进度（秒）；恢复播放时 seek 回该位置 */
    private static float pausedPosition = 0f;
    /** 暂停时保存的声源时长（秒）；暂停后声源被停止、trackLength() 无法再从 voice 读取，
     *  对「仅声源可知长度、曲目探测失败」的外部歌曲（如超长/不可探测）会回退 -1，导致暂停时进度显示变 0。
     *  暂停时暂存该长度，暂停期间 trackLength() 优先用它，恢复后清空。 */
    private static float pausedLength = -1f;
    /** 已解析文件绝对路径 → 时长（秒）缓存，避免反复用 Music.create 读取耗时 */
    private static final ObjectMap<String, Float> lengthCache = new ObjectMap<>();
    private static boolean autoAdvancing = false;
    /** 最近一次 seek 的游戏内时间（秒）；seek 后给 Soloud 一段恢复期，防止流式声源跳转瞬间被误判为「已播完」而跳歌 */
    private static float lastSeekAt = -1000f;
    /** 上一帧本地位置（秒），用于识别 LOOP_ONE 原生循环在曲末回绕到 0 的进度回退（区分于手动拖动 seek） */
    private static float lastPos = 0f;
    private static boolean initialized = false;
    /** 待应用的恢复进度（秒）；<0 表示无。resume 后不立即 idSeek（新流式声源可能未就绪，实测即时 seek 会原生崩溃），
     *  推迟到声源确认存活（isPlaying 且在 beginPlayback 后经过 0.3s）再应用 */
    private static float pendingResumeSeek = -1f;
    /** 恢复播放（pause→resume）给慢速加载的流式/外部声源延长的确认窗口截止时刻（秒）。
     *  暂停前已确认播放过的曲目，恢复后给比全新选曲更长的加载期，避免其尚未回到播放态就被
     *  「从未播放→停止」守卫误杀；窗口过期仍未恢复则停（不自动跳到内置/其它曲）。 */
    private static float resumeGraceUntil = -1f;
    /** 恢复播放的额外加载确认窗口时长（秒）：URL/流式外部声源回到播放态可能超过 0.5s 的常规 ADVANCE_DELAY */
    private static final float RESUME_GRACE = 3f;
    /** 暂停发生前是否确实在播放（记录给 resume 决定是否延长加载确认窗口） */
    private static boolean wasPlayingBeforePause = false;
    /** 倒放累积回退的目标位置（秒）：每次以它减去 step，再统一抽稀 seek。独立于 currentTime 驱动，
     *  避免读取-回写之间被流式解码器正向推进干扰（这是此前「完全不能倒放」的深层根因之一） */
    private static float lastReversePos = 0f;
    /** 上次倒放实际执行 idSeek 的时刻（秒），用于抽稀 */
    private static float lastReverseSeekAt = 0f;
    /** 倒放抽稀计数（每 N 帧才 seek 一次） */
    private static int reverseTick = 0;

    /** seek 结果校验：目标位置（秒）。Soloud 对部分流式外部声源（典型：mp3 流）的 idSeek 可能落到
     *  错误位置——跳到极高进度（随即「播完」被误判自然结束而自动跳下一首，表现为「外部曲拖进度跳到 game2」）
     *  或归零（表现为「暂停恢复后进度跳回开头」）。对每次实际下发的同步 seek 安排校验：
     *  0.35s 后读回真实位置，与目标差超过容差则再等 0.5s 复查，两次都不匹配判失败。 */
    private static float seekVerifyTarget = -1f;
    /** 下次 seek 校验检查时刻（秒）；<0 表示无待校验 seek */
    private static float seekVerifyAt = -1f;
    /** seek 校验连续不匹配次数（≥2 判失败） */
    private static int seekVerifyFails = 0;
    /** 当前声源 seek 已被判定不可靠（校验失败置位）：该曲禁用拖动/±10s，resume 不再尝试 seek，播完不再推进 */
    private static boolean seekUnreliable = false;

    /** 当前本机声源是否曾确认进入播放态（用于区分「自然播完可推进」与「新声源启动即失败」：
     *  后者不得静默跳到别的曲目（曾因误判把本地曲跳成内置曲），应停播并留日志 */
    private static boolean voiceEverPlayed = false;
    /** 随机循环（LOOP_SHUFFLE）的播放顺序（tracks 索引）；进入随机模式或曲目库/作用域变化时重建 */
    private static final Seq<Integer> shuffleOrder = new Seq<>();
    private static boolean shuffleDirty = true;

    /** 声源结束检测的静默阈值（秒）：自然播完后等待该时长再推进下一首，避免新声源流式加载未就绪时重复推进 */
    private static final float ADVANCE_DELAY = 0.5f;

    /** 单个活跃声源句柄（本机 owner 或远程，可叠加）。远程按 ownerUuid 区分归属 */
    static class Voice {
        String ownerUuid;      // 远程播放者 uuid；本机本地播放时为 null
        String hash;
        boolean isLocalOwner;
        int voiceId;
        float lastX, lastY;
        float createdAt;       // 创建时刻（秒），用于判断远程声源是否已足够久可安全清理
        /** 主动被暂停（owner 暂停播放 / 本机暂停全部远程声源）。暂停中的远程声源不得被当「自然播完」清理，
         *  否则 owner 暂停后恢复（resumeRemoteVoice 只是 setPaused(false)）声源早已被销毁 → 恢复无声。 */
        boolean paused;
        Sound sound;           // 对应 createStream 的 Sound，停止/清理时需 dispose 释放原生 Soloud 源
    }

    /** 停止并释放一个声源的播放与原生 Sound 句柄 */
    private static void disposeVoice(Voice v) {
        if (v == null) return;
        if (v.voiceId >= 0) {
            try {
                Core.audio.stop(v.voiceId);
            } catch (Exception ignored) {
            }
            v.voiceId = -1;
        }
        if (v.sound != null) {
            try {
                v.sound.dispose();
            } catch (Exception ignored) {
            }
            v.sound = null;
        }
    }

    /** 远程播放者 owner → 其当前播放曲目 hash（mp-pos 到达时定位声源用） */
    private static final ObjectMap<String, String> ownerHash = new ObjectMap<>();

    static String ownerPlayingHash(String ownerUuid) {
        return ownerHash.get(ownerUuid);
    }

    private MusicPlayer() {}

    // ------------------------------------------------------------------
    // 初始化 / 持久化
    // ------------------------------------------------------------------

    public static void init() {
        if (initialized) return;
        initialized = true;

        enabled = Core.settings.getBool(CFG_ENABLED, true);
        shareEnabled = Core.settings.getBool(CFG_SHARE, true);
        volume = clamp(Core.settings.getFloat(CFG_VOLUME, 1f), 0f, 10f);
        Core.settings.put(CFG_VOLUME, volume);
        // pitch 已无 UI 入口（旧的 0.1–10x 音高功能已移除，只保留倍速 speed）。
        // 历史版本曾把 pitch 持久化为 10（与旧 0.1-10x 范围上限一致），会让实际速率 pitch*speed 变成 10x
        // →「播放速度过快，0.13x 才是正常」。此处统一把 pitch 复位为 1（实际速率只由 speed 决定）。
        float savedPitch = Core.settings.getFloat(CFG_PITCH, 1f);
        pitch = 1f;
        Core.settings.put(CFG_PITCH, 1f);
        speed = clamp(Core.settings.getFloat(CFG_SPEED, 1f), MIN_SPEED, 16f);
        // 若持久化 pitch 是非 1 的异常值（旧音高残留，如 10x），说明 speed 很可能也被用户调成了补偿值
        // （speed≈0.13 来抵消 pitch=10）。修复 pitch 后把 speed 一并复位为 1，恢复「1x=正常速度」。
        if (savedPitch > 2f || savedPitch < 0.25f) speed = 1f;
        Core.settings.put(CFG_SPEED, speed);
        loopMode = Math.max(0, Math.min(5, Core.settings.getInt(CFG_LOOP, LOOP_LIST)));
        Core.settings.put(CFG_LOOP, loopMode);
        current = Core.settings.getInt(CFG_LAST, -1);
        abA = Core.settings.getFloat(CFG_AB_A, -1f);
        abB = Core.settings.getFloat(CFG_AB_B, -1f);
        // 异常 A-B 值（>12h 或 NaN）视为未设置，避免区间逻辑误触发
        if (abA < -0.5f || abA > 12f * 3600f || Float.isNaN(abA) || Float.isInfinite(abA)) abA = -1f;
        if (abB < -0.5f || abB > 12f * 3600f || Float.isNaN(abB) || Float.isInfinite(abB)) abB = -1f;
        Core.settings.put(CFG_AB_A, abA);
        Core.settings.put(CFG_AB_B, abB);
        reverse = Core.settings.getBool(CFG_REVERSE, false);
        loadTracks();
        loadAlbums();
        String alb = Core.settings.getString(CFG_ALBUM, "");
        activeAlbum = (alb == null || alb.isEmpty()) ? null : alb;
        // 持久化专辑若已被删除则回退到全部，避免悬浮条显示幽灵专辑名且 next/prev 失效
        if (activeAlbum != null && !existsAlbum(activeAlbum)) activeAlbum = null;
        // current 越界（如曲目已删除）时回退到 -1，避免 currentTrack 越界；空库时保留 -1
        if (current < -1 || (tracks.size > 0 && current >= tracks.size)) {
            current = -1;
            Core.settings.put(CFG_LAST, current);
        }

        Events.run(EventType.Trigger.update, MusicPlayer::update);
    }

    private static void loadAlbums() {
        albums.clear();
        String raw = Core.settings.getString(CFG_ALBUMS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                Album[] arr = json.fromJson(Album[].class, raw);
                if (arr != null) {
                    for (Album a : arr) if (a != null && a.name != null && !a.name.trim().isEmpty()) {
                        if (a.hashes == null) a.hashes = new Seq<>();
                        // 清理空 hash
                        Seq<String> clean = new Seq<>();
                        for (String h : a.hashes) if (h != null && !h.isEmpty()) clean.add(h);
                        a.hashes = clean;
                        albums.add(a);
                    }
                }
            } catch (Exception e) {
                albums.clear();
            }
        }
    }

    private static void saveAlbums() {
        Core.settings.put(CFG_ALBUMS, json.toJson(albums, Album[].class, Album.class));
    }

    private static void loadTracks() {
        tracks.clear();
        String raw = Core.settings.getString(CFG_TRACKS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                MusicTrack[] arr = json.fromJson(MusicTrack[].class, raw);
                if (arr != null) {
                    for (MusicTrack t : arr) if (t != null && t.cacheHash != null) tracks.add(t);
                }
            } catch (Exception e) {
                tracks.clear();
            }
        }
    }

    private static void saveTracks() {
        Core.settings.put(CFG_TRACKS, json.toJson(tracks, MusicTrack[].class, MusicTrack.class));
    }

    // ------------------------------------------------------------------
    // 专辑（曲目分组）
    // ------------------------------------------------------------------

    /** 全部专辑（含未命名曲目的默认组返回 null → 表示全部曲目） */
    public static Seq<Album> albums() {
        return albums;
    }

    public static Album album(int index) {
        return (index >= 0 && index < albums.size) ? albums.get(index) : null;
    }

    public static void addAlbum(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim();
        if (n.length() > MAX_ALBUM_NAME_LENGTH) n = n.substring(0, MAX_ALBUM_NAME_LENGTH);
        for (Album a : albums) if (a.name.equalsIgnoreCase(n)) return;
        albums.add(new Album(n));
        saveAlbums();
        shuffleDirty = true;
    }

    public static void removeAlbum(int index) {
        if (index < 0 || index >= albums.size) return;
        albums.remove(index);
        saveAlbums();
        if (activeAlbum != null && !existsAlbum(activeAlbum)) {
            activeAlbum = null;
            Core.settings.put(CFG_ALBUM, "");
        }
        shuffleDirty = true;
    }

    private static boolean existsAlbum(String name) {
        for (Album a : albums) if (name.equals(a.name)) return true;
        return false;
    }

    public static void addToAlbum(int albumIndex, int trackIndex) {
        Album a = album(albumIndex);
        if (a == null || trackIndex < 0 || trackIndex >= tracks.size) return;
        if (a.hashes == null) a.hashes = new Seq<>();
        a.hashes.addAll(tracks.get(trackIndex).cacheHash);
        saveAlbums();
        shuffleDirty = true;
    }

    /** 按专辑名把某曲目 hash 加入专辑（不存在该专辑名则创建）。用于「当前筛选下导入新曲自动归入当前专辑」 */
    public static void addTrackHashToAlbum(String albumName, String hash) {
        if (albumName == null || albumName.trim().isEmpty() || hash == null || hash.isEmpty()) return;
        Album target = null;
        for (Album a : albums) {
            if (albumName.equals(a.name)) { target = a; break; }
        }
        if (target == null) {
            target = new Album(albumName.trim());
            albums.add(target);
        }
        if (target.hashes == null) target.hashes = new Seq<>();
        if (!target.hashes.contains(hash)) {
            target.hashes.add(hash);
            saveAlbums();
            shuffleDirty = true;
        }
    }

    public static void removeFromAlbum(int albumIndex, int trackIndex) {
        Album a = album(albumIndex);
        if (a == null || trackIndex < 0 || trackIndex >= tracks.size || a.hashes == null) return;
        a.hashes.remove(tracks.get(trackIndex).cacheHash);
        saveAlbums();
        shuffleDirty = true;
    }

    /** 当前激活专辑名（null = 全部曲目） */
    public static String activeAlbum() {
        return activeAlbum;
    }

    public static void setActiveAlbum(String name) {
        activeAlbum = (name == null || name.isEmpty()) ? null : name;
        Core.settings.put(CFG_ALBUM, activeAlbum == null ? "" : activeAlbum);
        shuffleDirty = true; // 作用域变化 → 随机播放顺序需重建
        // 当前曲目不再属于激活专辑 → 跳到该专辑第一首（若有），否则停止
        MusicTrack cur = currentTrack();
        if (activeAlbum != null && cur != null && !albumContainsByName(activeAlbum, cur.cacheHash)) {
            int first = firstTrackOfAlbum(activeAlbum);
            if (first >= 0) play(first);
            else stop();
        }
    }

    /** 指定专辑内曲目当前顺序（按曲目库序）的索引列表 */
    public static int[] albumTrackIndices(String albumName) {
        Seq<Integer> out = new Seq<>();
        for (int i = 0; i < tracks.size; i++) {
            if (albumName != null && !albumContainsByName(albumName, tracks.get(i).cacheHash)) continue;
            out.add(i);
        }
        int[] res = new int[out.size];
        for (int i = 0; i < out.size; i++) res[i] = out.get(i);
        return res;
    }

    private static int firstTrackOfAlbum(String albumName) {
        int[] ind = albumTrackIndices(albumName);
        return ind.length > 0 ? ind[0] : -1;
    }

    private static boolean albumContainsByName(String albumName, String hash) {
        for (Album a : albums) {
            if (!albumName.equals(a.name)) continue;
            if (a.hashes.contains(hash)) return true;
        }
        return false;
    }

    /** 激活专辑的首/末曲索引；null 专辑（全部曲目）返还 true */
    private static int[] currentScope() {
        if (activeAlbum == null) return null;
        return albumTrackIndices(activeAlbum);
    }

    // ------------------------------------------------------------------
    // 启用开关（双生效）
    // ------------------------------------------------------------------

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置「启用」总开关。语义（2026-09）：
     * 关闭时本地仍可正常播放自己的曲目，但不接收别人（听不到别人）、也不广播给他人（别人听不到你）；
     * 开启时恢复收听与（受 shareEnabled 约束的）广播。
     */
    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        // 先记录「此刻正共享」再翻转开关——翻转后 canShare() 会因 enabled 变化而变 false，
        // 若翻转后再取 willSharing 就永远是 false，导致「关总开关却未广播 stop」
        boolean shutOffSharing = enabled && playing && canShare();
        enabled = value;
        Core.settings.put(CFG_ENABLED, enabled);
        if (!enabled) {
            // 关闭：停止收听所有远程声源，并通知其他玩家停止播放本机曲目；
            // 本机本地播放不停止（enabled 关时仍可本地播）。
            clearRemoteVoices();
            if (shutOffSharing) bcast("stop");
        }
    }

    /** 网络接收侧入口：关闭时调用方可直接丢弃（enabled 关 → 听不到别人） */
    public static boolean canReceive() {
        return enabled;
    }

    /** 是否开启了「播放给他人」（独立于 enabled 的广播开关） */
    public static boolean isShareEnabled() {
        return shareEnabled;
    }

    /** 设置「播放给他人」开关：关时不广播本机曲目给其他玩家，但（enabled 时）仍可收听别人 */
    public static void setShareEnabled(boolean value) {
        if (shareEnabled == value) return;
        shareEnabled = value;
        Core.settings.put(CFG_SHARE, shareEnabled);
        // 关闭共享 → 通知其他玩家停止播放本机曲目（否则对方仍按旧声源播我上一条）
        if (!shareEnabled && playing) bcast("stop");
    }

    /** 是否可广播本机曲目给他人（enabled 且 shareEnabled 同时为真） */
    public static boolean canShare() {
        return enabled && shareEnabled;
    }

    // ------------------------------------------------------------------
    // 周期更新
    // ------------------------------------------------------------------

    private static void update() {
        if (!initialized || player == null) return;
        // 注意：不再用 enabled 门控整个 update —— enabled 关时本地仍可正常播放（见 setEnabled 注释），
        // 本机声源推进/暂停/倒放/音量刷新必须始终运行；enabled 只影响网络的收发（canReceive/canShare）。
        // 音乐独立于游戏暂停（Fix 9）：声源已挂 musicBus，ESC 暂停仅暂停 soundBus 不影响音乐，
        // 故不再做 pausedByGame 冻结 —— 进度实时跟随，游戏暂停时音乐照常播放与推进。
        tickLocal();
        refreshVolumes();
    }

    private static void tickLocal() {
        if (!playing || localVoiceId < 0) return;
        // 音乐独立于游戏暂停（Fix 9）：声源挂 musicBus，ESC 暂停不影响播放，
        // 故不再对游戏暂停短路——进度与推进逻辑正常执行。
        // 恢复播放的延迟 seek：新声源确认存活（isPlaying 且距建源 ≥0.3s）才应用 seek。
        // 必须 isPlaying 才 apply：soloud 对未就绪声源 idSeek 会原生崩溃（见 seek() 铁律注释）。
        // 外部流式/URL 声源缓冲中存的 pending，会在声源转为真正播放态后自动应用（安全可等的延迟）。
        if (pendingResumeSeek >= 0f && Core.audio.isPlaying(localVoiceId) && Time.time - lastBlip >= 0.3f) {
            float s = pendingResumeSeek;
            pendingResumeSeek = -1f;
            seek(s);
        }
        // seek 结果校验（修外部曲「拖进度跳到极高进度/归零→跳 game2/恢复进度归零」）：
        // 对同步下发的 idSeek，在 0.35s 后读回声源真实位置比对目标；不匹配再等 0.5s 复查一次，
        // 两次都不匹配判「该声源 seek 不可靠」→ 立即停播并提示，绝不留在错误位置继续播。
        if (seekVerifyAt > 0f && Time.time >= seekVerifyAt && localVoiceId >= 0 && !reverse) {
            if (Core.audio.isPlaying(localVoiceId)) {
                float pos = SoloudBridge.getPosition(localVoiceId);
                float tol = 3f + Math.abs(seekVerifyTarget) * 0.05f;
                if (!Float.isNaN(pos) && !Float.isInfinite(pos) && Math.abs(pos - seekVerifyTarget) <= tol) {
                    // 校验通过：清空校验状态
                    seekVerifyAt = -1f;
                    seekVerifyTarget = -1f;
                } else {
                    seekVerifyFails++;
                    if (seekVerifyFails >= 2) {
                        Log.warn("[SiliconMusic] seek verify failed (target=" + seekVerifyTarget + "s, pos=" + pos + "s) - stream seek unreliable, stopping");
                        seekUnreliable = true;
                        seekVerifyAt = -1f;
                        seekVerifyTarget = -1f;
                        pendingResumeSeek = -1f;
                        stopLocal();
                        bcast("stop");
                        toast("musicplayer.seekFail", null);
                    } else {
                        seekVerifyAt = Time.time + 0.5f; // 解码器可能仍在缓冲/回同步，给 0.5s 复查
                    }
                }
            } else {
                // 声源尚未回到播放态（外部流缓冲中）：推迟复查；若 seek 已过去 1s 仍未播放态，
                // 交由下方「播完检测」的新增守卫处理（seek 落到末尾之外的典型表现）
                seekVerifyAt = Time.time + 0.5f;
            }
        }
        // 倒放：进度按「帧时长 × 实际速率」反向回退（近似倒着播放）；回退到开头则停播（保留开关状态）。
        // 修复（2026-09-03 rev2）：此前倒放分支开头有 `!Core.audio.isPlaying(id)` 守卫——Soloud 流式声源
        // 在 idSeek 跳转瞬间会短暂返回非播放态，导致每帧都被跳过，从未真正 seek →「完全不能倒放」。
        // 现去掉 isPlaying 守卫（倒放期间声源必然已处于本机播放态），只保留 localVoiceId 判空；
        // 并对 seek 抽稀（每 2 帧 seek 一次 + 最小 25ms 推进），给流式解码器稳定时间，避免连续 seek
        // 互相覆盖/与解码器打架导致跳不动。lp 为上一次实际 seek 的位置，持续累积回退。
        if (reverse) {
            if (localVoiceId < 0) return;
            // 修复（2026-09-03 rev3）：beginPlayback/stopLocal 会把 lastReversePos 重置为 0，
            // 而「倒放开启状态下 暂停→恢复」时 resume 会 seek 回暂停位置（如 45s），
            // 若仍以 0 为起点，第一步 target=0-step<0 就会立即 stopLocal → 恢复即停，无法继续倒着播。
            // 这里在 lastReversePos 尚未初始化到本声源真实起点（≤0，即刚建源/刚恢复）时，
            // 用 currentTime()（本帧若为 resume 已被 pendingResumeSeek 应用，返回目标位置）作起点，
            // 让倒放从实际位置继续回退；新播起点为 0 时仍符合「到开头自动停止」语义。
            if (lastReversePos <= 0f) lastReversePos = currentTime();
            // 修复（倒放速度）：Time.delta 单位是 tick（60 tick/s，60fps 下每帧 ≈1.0），而声源位置/进度单位是
            // 秒——此前直接把 Time.delta 当秒用，倒放以约 60 倍速回退（「倒放速度快」根因）。换算 /60 才是
            // 真实 1x 速率；下限 1/120s 仅防御异常帧计时（正常 60fps 为 1/60s，不触发）。
            float step = Math.max(1f / 120f, Time.delta / 60f);
            float target = lastReversePos - step;
            lastReversePos = target;
            lastSeekAt = Time.time;
            // 抽稀：间隔至少 2 帧且 25ms 才真正 idSeek，避免每帧 seek 与流式解码互相干扰
            if (Time.time - lastReverseSeekAt >= 0.025f && ++reverseTick >= 2) {
                reverseTick = 0;
                lastReverseSeekAt = Time.time;
                if (target <= 0f) {
                    stopLocal();
                    bcast("stop");
                } else if (localVoiceId >= 0) {
                    SoloudBridge.seek(localVoiceId, Math.max(0f, target));
                }
            }
            return;
        }
        // A-B 区间循环：进度到达 B 点（hi）后回转到 A 点（lo），实现区间重复
        if (hasAb()) {
            float pos = currentTime();
            float lo = Math.min(abA, abB);
            float hi = Math.max(abA, abB);
            if (pos >= hi) {
                seek(lo);
            } else if (Time.time - lastSeekAt >= 1.0f && lastPos - pos > 0.5f) {
                // LOOP_ONE 原生循环在曲末无痕回绕到 0：位置相对上帧明显回退，且非手动拖动后刚发生，
                // 说明整曲被原生层重启，把播放无缝拉回区间起点，避免每圈先播一段 [0,lo) 再进区间
                seek(lo);
            }
            lastPos = currentTime();
        }
        // 仍在播放（含暂停态，暂停时 Soloud 的 id 仍有效）→ 复位推进守卫
        if (Core.audio.isPlaying(localVoiceId)) {
            autoAdvancing = false;
            voiceEverPlayed = true;
            return;
        }
        // 声音已结束（非循环播完或已 stop）
        if (autoAdvancing) return; // 上一条刚触发推进，等新声源就绪，避免重复推进/跳曲
        if (Time.time - lastSeekAt < 1.0f) return; // seek 后声源可能瞬时未就绪，误判已播完会跳歌
        // seek 校验未完成且声源在 seek 后 2.5s 内就结束——典型为「不可靠 seek 落到曲末之外」立即播完：
        // 判定该声源 seek 不可靠并停播，绝不自动推进下一首（「外部曲拖进度跳到 game2」根因）。
        // 正常拖动到曲末附近时校验会在 0.35s 内通过（还剩 ≥0.5s 尾音），不会进入此分支。
        if (seekVerifyAt > 0f && Time.time - lastSeekAt < 2.5f) {
            Log.warn("[SiliconMusic] voice ended right after seek with verify pending - stream seek unreliable, stopped without advance");
            seekUnreliable = true;
            seekVerifyAt = -1f;
            seekVerifyTarget = -1f;
            stopLocal();
            toast("musicplayer.seekFail", null);
            return;
        }
        if (Time.time - lastBlip < ADVANCE_DELAY) return; // 新声源流式加载未就绪的静默窗口
        if (resumeGraceUntil > Time.time) return; // 恢复播放（pause→resume）的延长加载确认窗口：慢速 URL/流式外部声源
        if (!voiceEverPlayed) {
            // 静默窗口过后仍从未进入播放态 → 启动即失败（解码不支持/缓存损坏/文件缺失）。
            // 不自动跳到别的曲目（此前会静默推进成「内置歌曲」），停播并留日志便于排查。
            MusicTrack cur = currentTrack();
            Log.warn("Playback failed to start, stopped without auto-skip: " + (cur == null ? "?" : cur.name));
            resumeGraceUntil = -1f;
            stopLocal();
            return;
        }
        autoAdvancing = true;
        lastBlip = Time.time;
        autoAdvance();
    }

    private static void autoAdvance() {
        switch (loopMode) {
            case LOOP_ONE:
                stopLocal();
                beginPlayback(current);
                if (playing) bcast("play");
                break;
            case LOOP_LIST:
            case LOOP_SHUFFLE:
            case LOOP_RANDOM:
                if (advanceSafely(1)) bcast("next");
                break;
            default: // LOOP_OFF / LOOP_ONE_STOP：自然播完即停
                stopLocal();
                bcast("stop");
        }
    }

    private static boolean advanceSafely(int delta) {
        // 激活专辑时只在专辑内切换；否则全曲目库切换
        int[] scope = currentScope();
        int size = (scope == null) ? tracks.size : scope.length;
        if (size == 0) return false;
        // 当前曲目在当前作用域内的位置；若不在（如新增曲目/换专辑）则从头
        int pos = 0;
        if (current >= 0) {
            if (scope == null) pos = current;
            else {
                for (int i = 0; i < scope.length; i++) if (scope[i] == current) { pos = i; break; }
            }
        }
        pausedPosition = 0f;
        pausedLength = -1f;
        clearAb();
        stopLocal();
        int nextPos;
        if (loopMode == LOOP_RANDOM) {
            // 真随机：每次独立抽签；作用域多于 1 首时避免与当前曲连续相同
            if (size == 1) {
                nextPos = (scope == null) ? 0 : scope[0];
            } else {
                int r = arc.math.Mathf.random(size - 1);
                int pick = (scope == null) ? r : scope[r];
                if (current >= 0 && pick == current) {
                    r = (r + 1) % size;
                    pick = (scope == null) ? r : scope[r];
                }
                nextPos = pick;
            }
        } else if (loopMode == LOOP_SHUFFLE) {
            int[] order = ensureShuffleOrder();
            size = order.length;
            if (size == 0) return false;
            int cur = -1;
            for (int i = 0; i < order.length; i++) if (order[i] == current) { cur = i; break; }
            int nxt = cur < 0 ? (delta > 0 ? 0 : order.length - 1) : ((cur + delta) % size + size) % size;
            nextPos = order[nxt];
        } else {
            int nxt = ((pos + delta) % size + size) % size;
            nextPos = (scope == null) ? nxt : scope[nxt];
        }
        MusicTrack from = currentTrack();
        current = nextPos;
        Core.settings.put(CFG_LAST, current);
        beginPlayback(current);
        MusicTrack to = currentTrack();
        if (from != to) {
            Log.info("Track transition: " + (from == null ? "?" : from.name) + " -\u003e " + (to == null ? "?" : to.name));
        }
        return playing;
    }

    /** 随机播放顺序（当前作用域内索引）；dirty 或尺寸不符时重建（打乱）。返回数组便于遍历 */
    private static int[] ensureShuffleOrder() {
        int[] scope = currentScope();
        int size = (scope == null) ? tracks.size : scope.length;
        if (shuffleDirty || shuffleOrder.size != size) {
            java.util.ArrayList<Integer> tmp = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) tmp.add(scope == null ? i : scope[i]);
            java.util.Collections.shuffle(tmp);
            shuffleOrder.clear();
            shuffleOrder.addAll(tmp);
            shuffleDirty = false;
        }
        int[] out = new int[shuffleOrder.size];
        for (int i = 0; i < out.length; i++) out[i] = shuffleOrder.get(i);
        return out;
    }

    /** 各声源按播放者当前位置刷新音量（距离衰减），并清理已自然播完的远程声源 */
    private static void refreshVolumes() {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (v.voiceId < 0) { disposeVoice(v); voices.remove(i); continue; }
            // 远程非循环声源自然播完后 Soloud 会释放 id → 清理（含释放原生 Sound），防止 voices 无限累积。
            // 注意：主动暂停的远程声源（v.paused）isPlaying 也为 false，但不得在此清理——否则
            // owner 暂停后恢复（仅 setPaused(false)）声源已被销毁，恢复无声。故用 v.paused 区分「自然播完」与「被暂停」；
            // 暂停态超过 5 分钟仍未恢复则视为僵死清理，避免无限常驻。
            if (!v.isLocalOwner && !Core.audio.isPlaying(v.voiceId) && Time.time - v.createdAt > 2f
                    && (!v.paused || Time.time - v.createdAt > 300f)) {
                disposeVoice(v);
                voices.remove(i);
                continue;
            }
            // 本机声源原点跟随玩家（自己永远在声源处 → 恒 0 位移全音量）；
            // 远程声源原点固定在 owner 位置，听者按「自己到 owner」衰减。
            if (v.isLocalOwner) {
                v.lastX = player.x;
                v.lastY = player.y;
            }
            // 本机恒在声源处 → 全音量；远程才做距离衰减。修复：本机音量不再乘以 0.12 的 BASE_VOLUME，避免几乎听不见。
            float vol = v.isLocalOwner ? effectiveVolume() : calcListenVolume(v.lastX - player.x, v.lastY - player.y);
            float pan = calcListenPan(v.lastX);
            // set(voiceId, pan, volume) 同时更新左右声像与音量（arc 中第2参为 pan、第3参为 volume；
            // 反向传入会把 pan 当 volume，声源在屏幕中央(pan≈0)时音量≈0 → 听不到声音）
            Core.audio.set(v.voiceId, pan, vol);
        }
    }

    /** 监听者在 (dx,dy) 相对播放者位移处应听到的音量。远程在 0 距离时与本机同响度（effectiveVolume），随距离线性衰减；
     *  修复：此前 `volume*BASE_VOLUME(0.12)*factor` 使远程在贴脸时也只有本机 12% 音量，1000%(10x) 最高仅 1.2，远达不到界面承诺的“真的千倍”。 */
    static float calcListenVolume(float dx, float dy) {
        if (Float.isNaN(dx) || Float.isNaN(dy) || Float.isInfinite(dx) || Float.isInfinite(dy)) return 0f;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (Float.isNaN(dist) || Float.isInfinite(dist)) return 0f;
        float factor = clamp(1f - dist / FALLOFF_RADIUS, 0f, 1f);
        return clamp(effectiveVolume() * factor, 0f, MAX_EFF_VOL);
    }

    /** 按声源在世界 x 相对监听视角（相机）的水平偏移计算左右声像（-0.9 左 … 0.9 右） */
    static float calcListenPan(float wx) {
        if (Core.camera == null) return 0f;
        float half = Core.camera.width / 2f;
        if (half <= 0f) return 0f;
        return clamp((wx - Core.camera.position.x) / half, -0.9f, 0.9f);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // 曲目库
    // ------------------------------------------------------------------

    public static Seq<MusicTrack> tracks() {
        return tracks;
    }

    /** 内置曲目 key 列表（供 UI 内置曲目选择器使用） */
    public static String[] internalKeys() {
        return INTERNAL_KEYS.clone();
    }

    public static int currentIndex() {
        return current;
    }

    public static MusicTrack currentTrack() {
        return (current >= 0 && current < tracks.size) ? tracks.get(current) : null;
    }

    public static MusicTrack trackAt(int index) {
        return (index >= 0 && index < tracks.size) ? tracks.get(index) : null;
    }

    /** 确保内置曲目存在（ClientLoadEvent 时调用，保证 Musics.* 已 load）；逐 key 补齐，删除单首内置后也能恢复 */
    public static void ensureInternalTracks() {
        boolean added = false;
        for (String key : INTERNAL_KEYS) {
            String h = "int-" + key;
            if (indexOfHash(h) < 0) {
                tracks.add(new MusicTrack(MusicTrack.INTERNAL, key, key, h, "musicplayer.type.internal"));
                added = true;
            }
        }
        if (added) saveTracks();
    }

    static int indexOfHash(String hash) {
        for (int i = 0; i < tracks.size; i++) {
            if (hash != null && hash.equals(tracks.get(i).cacheHash)) return i;
        }
        return -1;
    }

    public static MusicTrack trackByHash(String hash) {
        int i = indexOfHash(hash);
        return i >= 0 ? tracks.get(i) : null;
    }

    /** 添加自定义曲目（URL 或本地路径）；成功返回曲目，失败/重复返回对应曲目或 null */
    public static MusicTrack addTrack(int type, String source, String name) {
        if (source == null || source.trim().isEmpty()) return null;
        if (type != MusicTrack.URL && type != MusicTrack.LOCAL) return null;
        String src = source.trim();
        // 修复（2026-09-03 rev9）：EXT_WHITELIST 只在「本地文件路径」上强制要求音频扩展名。
        // 此前对所有来源无条件 `EXT_WHITELIST.find(src)`，导致无扩展名/带 query 的 http(s) 直播直链
        // （如 https://host/stream?token=...）连添加都被拒，整套 sniffExt/resolveExt 内容嗅探形同虚设。
        // URL 的真实容器格式本就在下载后按文件头嗅探（sniffExt），故 URL 仅校验 scheme，不再要求扩展名。
        if (type == MusicTrack.LOCAL) {
            if (!EXT_WHITELIST.matcher(src).find()) return null;
        } else if (!(src.startsWith("http://") || src.startsWith("https://"))) {
            return null;
        }
        String hash = Strings.bytesToHex(sha256(src)).substring(0, 16);
        int dup = indexOfHash(hash);
        if (dup >= 0) {
            MusicTrack existed = tracks.get(dup);
            // 重复添加同一源但提供新显示名时，更新名称并持久化，避免“改名不生效”误导
            if (name != null && !name.isEmpty() && !name.equals(existed.name)) {
                existed.name = name;
                saveTracks();
                // 弹窗若开着，刷新列表以显示新名称
                try { silicon.ui.MusicPlayerDialog.refreshIfOpen(); } catch (Exception ignored) {}
            }
            return existed;
        }
        String clean = src;
        int qq = clean.indexOf('?'); if (qq >= 0) clean = clean.substring(0, qq);
        int hh = clean.indexOf('#'); if (hh >= 0) clean = clean.substring(0, hh);
        String displayName = (name != null && !name.isEmpty()) ? name
                : (clean.contains("/") || clean.contains("\\") ? clean.substring(Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\')) + 1) : clean);
        if (displayName.length() > MAX_TRACK_NAME_LENGTH) displayName = displayName.substring(0, MAX_TRACK_NAME_LENGTH);
        MusicTrack t = new MusicTrack(type, displayName, src, hash,
                type == MusicTrack.URL ? "musicplayer.type.url" : "musicplayer.type.local");
        tracks.add(t);
        saveTracks();
        shuffleDirty = true; // 曲目库变化 → 随机播放顺序需重建
        return t;
    }

    /** SHA-256 摘要（16 字节 → 32 hex 字符）供缓存 hash 使用 */
    private static byte[] sha256(String src) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[16];
        }
    }

    public static void removeTrack(int index) {
        if (index < 0 || index >= tracks.size) return;
        String hash = tracks.get(index).cacheHash;
        if (current == index) {
            stopLocal();
            pausedPosition = 0f;
            pausedLength = -1f;
        }
        if (current > index) current--;
        tracks.remove(index);
        if (current >= tracks.size) current = -1;
        Core.settings.put(CFG_LAST, current);
        saveTracks();
        // 同步清理专辑中对该曲目的引用，避免孤悬 hash
        for (Album a : albums) if (a.hashes != null) a.hashes.remove(hash);
        saveAlbums();
        shuffleDirty = true; // 曲目库变化 → 随机播放顺序需重建
    }

    // ------------------------------------------------------------------
    // 本地播放（播放器控制按钮针对本机 owner 声源）
    // ------------------------------------------------------------------

    public static boolean isPlaying() {
        return playing;
    }

    public static int currentVoiceId() {
        return localVoiceId;
    }

    public static void play(int index) {
        if (index < 0 || index >= tracks.size) return;
        // 不再 gated by enabled：enabled 只控制网络收发（见 setEnabled），本地播放随时可用
        pausedPosition = 0f;
        pausedLength = -1f;
        clearAb();
        // 修复：先设 current 再 stopLocal——stopLocal 可能触发 autoAdvance，若 current 仍是旧值
        // 会跳到「旧current+1」即下一首（如点外部歌曲却跳到 game2）。提前设新 current 防止错跳。
        current = index;
        Core.settings.put(CFG_LAST, current);
        stopLocal();
        wasPlayingBeforePause = false;
        resumeGraceUntil = -1f;
        autoAdvancing = false; // 清除推进守卫，防止残留状态影响新曲播放
        beginPlayback(current);
        if (playing) bcast("play");
    }

    public static void play() {
        if (current >= 0) play(current);
    }

    private static void beginPlayback(int index) {
        MusicTrack t = tracks.get(index);
        // 新声源重置 seek 可靠性判定（换曲/重播给新的机会）
        seekUnreliable = false;
        seekVerifyAt = -1f;
        seekVerifyTarget = -1f;
        seekVerifyFails = 0;
        Fi file = resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            if (t != null && t.isUrl()) {
                // 本机 URL 曲目尚未下载：先下载到缓存，完成后在主线程重播
                final int target = index;
                MusicNetwork.fetchLocalThenPlay(t, () -> {
                    if (current == target && !playing) {
                        beginPlayback(target);
                        if (playing) bcast("play");
                    }
                });
                return;
            }
            SiliconLog.log("Cannot resolve " + (t == null ? "?" : t.name) + " to a local file");
            // UI 反馈：此前失败完全静默，用户以为「播放按钮没反应/坏了」（问题6b/15）
            toast("musicplayer.cannotPlay", t == null ? "?" : t.name);
            return;
        }
        // flac/m4a/wma/aac/opus 等 Soloud 无内置解码器：以「最终可播放文件」的扩展名判定（缓存文件已按真实内容头落盘），
        // 直接创建声源会原生崩溃 → 阻止并在日志说明（仅排除本机解码，仍可分享字节给他人）
        if (t.isUrl() || t.isLocal()) {
            if (!isDecodablePath(file.absolutePath())) {
                Log.warn("Blocked play of undecodable " + t.name + " (Soloud only decodes ogg/mp3/wav)");
                playing = false;
                localVoiceId = -1;
                // UI 反馈：不可解码格式此前静默无反应（问题15「不能播放」无任何提示）
                toast("musicplayer.undecodable", t.name);
                return;
            }
        }
        // 内置曲目是 jar 打包资源，无真实磁盘路径，Soloud 无法流式读取 → 先提取为真实缓存文件
        if (t.isInternal()) {
            file = extractInternalToRealFile(file, t.source);
            if (file == null) {
                SiliconLog.log("Cannot extract internal track " + t.name);
                return;
            }
        }
        Sound snd = null;
        try {
            if (!isAsciiPath(file.absolutePath())) {
                SiliconLog.log("Block playback of non-ASCII path: " + file.name());
                return;
            }
            snd = Sound.createStream(file);
            // 修复（2026-09-03）：把音乐声源挂到 musicBus 而非默认 soundBus——游戏 ESC 暂停时
            // SoundControl 只 setPaused(soundBus)，音乐挂 musicBus 即可在暂停菜单下继续发声，
            // 实现「音乐完全独立于游戏暂停」（需求 Fix 9）。
            int id = snd.play(effectiveVolume(), pitch * speed, 0f, false, false, Core.audio.musicBus);
            Core.audio.setLooping(id, loopMode == LOOP_ONE);
            localVoiceId = id;
            playing = true;
            voiceEverPlayed = false; // 新声源尚未确认进入播放态前不推进
            lastBlip = Time.time;
            lastPos = 0f; // 新播放从 0 起算，避免上一首的残留位置触发 A-B 回绕误判
            lastReversePos = 0f; // 新播放重置倒放累积位置
            lastReverseSeekAt = 0f;
            reverseTick = 0;
            unregisterLocalVoice();
            Voice v = new Voice();
            v.hash = t.cacheHash;
            v.isLocalOwner = true;
            v.voiceId = id;
            v.sound = snd;
            v.lastX = player.x;
            v.lastY = player.y;
            v.createdAt = Time.time;
            voices.add(v);
        } catch (Exception e) {
            // 播放失败时释放刚创建的原生 Sound，避免泄漏
            if (snd != null) {
                try {
                    snd.dispose();
                } catch (Exception ignored) {
                }
            }
            SiliconLog.log("Failed to play " + t.name + ": " + e.getMessage());
            playing = false;
            localVoiceId = -1;
            toast("musicplayer.playFail", t.name);
        }
    }

    private static void unregisterLocalVoice() {
        for (int i = voices.size - 1; i >= 0; i--) {
            if (voices.get(i).isLocalOwner) {
                disposeVoice(voices.get(i));
                voices.remove(i);
            }
        }
    }

    public static void toggle() {
        if (playing) pause();
        else resume();
    }

    public static void pause() {
        if (!playing) return;
        // 记录「暂停前确实在播放」，供 resume 延长慢速外部声源的加载确认窗口
        wasPlayingBeforePause = true;
        if (localVoiceId >= 0) pausedPosition = currentTime();
        // 缓存暂停时声源长度：暂停后声源被 stop，trackLength() 无法再从 voice 读取，
        // 对「仅声源可知长度、曲目探测失败」的外部歌曲会回退 -1 → 暂停时进度显示变 0
        float cl = trackLength();
        pausedLength = cl > 0f ? cl : -1f;
        // 暂停本地声源（同时暂停所有远程声源：暂停=全部静音，避免「暂停了还在播别人的」）
        stopLocal();
        setAllRemotePaused(true);
        bcast("pause");
    }

    public static void resume() {
        if (playing) return;
        // 不再 gated by enabled：enabled 只控制网络收发，本地恢复播放随时可用
        // 从未选曲（current=-1，如刚打开游戏直接按播放）时从第一首开始，而非静默无响应
        if (current < 0 && tracks.size > 0) current = 0;
        if (current >= 0) {
            float seekTo = pausedPosition;
            pausedPosition = 0f;
            pausedLength = -1f;
            // 暂停前曲目已在播放（暂停经由 pause() 记录过进度）→ 恢复给更长的加载确认窗口，
            // 防止慢速的 URL/流式外部声源尚未回到播放态就被「从未播放→停止」守卫误杀（表现为
            // 「外部暂停再播放立即停止/跳走」）。窗口过期仍未恢复则停（不自动跳内置/其它曲）。
            if (wasPlayingBeforePause) resumeGraceUntil = Time.time + RESUME_GRACE;
            beginPlayback(current);
            if (playing && seekTo > 0.05f && !seekUnreliable) {
                // 恢复播放通常要 seek 到暂停位置，但刚创建的新流式声源可能尚未就绪；
                // 实测此时立刻 idSeek 会在原生 arc64.dll 崩溃（Soloud 内部锁断言，见 hs_err_pid*）。
                // 推迟到声源确认存活后应用（tickLocal 内 pendingResumeSeek 处理）。
                // seek 不可靠的声源不尝试恢复进度（校验已判失败，seek 会落到错误位置）。
                deferSeek(seekTo);
            }
            wasPlayingBeforePause = false;
            // 恢复本地播放的同时，恢复其它被暂停的远程声源（与 pause() 对称）
            if (playing) {
                setAllRemotePaused(false);
                bcast(seekTo > 0.05f ? "resume" : "play");
            }
        }
    }

    /** 暂停/恢复所有远程声源（本地静音切换，用于「暂停=全部不响」） */
    private static void setAllRemotePaused(boolean paused) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && v.voiceId >= 0) {
                v.paused = paused;
                try {
                    Core.audio.setPaused(v.voiceId, paused);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 记录待应用的恢复进度并夹取在曲末前 0.5s 内（秒）；由 tickLocal 在声源确认存活后应用。
     *  统一通道：resume() 与游戏暂停恢复都用它，避免对"刚建/可能重建"的新声源同步 idSeek 触发原生崩溃 */
    private static void deferSeek(float seconds) {
        float len = trackLength();
        pendingResumeSeek = Math.min(seconds, len > 0f ? Math.max(0f, len - 0.5f) : seconds);
    }

    public static void stopLocal() {
        pendingResumeSeek = -1f;
        resumeGraceUntil = -1f;
        voiceEverPlayed = false;
        lastReversePos = 0f;
        lastReverseSeekAt = 0f;
        reverseTick = 0;
        // 清理 seek 校验状态（停播后无需再校验旧 seek）
        seekVerifyAt = -1f;
        seekVerifyTarget = -1f;
        seekVerifyFails = 0;
        if (localVoiceId >= 0) {
            Core.audio.stop(localVoiceId);
            localVoiceId = -1;
        }
        playing = false;
        unregisterLocalVoice();
    }

    /** 公开停止：停止本地并广播给其他玩家 */
    public static void stop() {
        pausedPosition = 0f;
        pausedLength = -1f;
        stopLocal();
        bcast("stop");
    }

    public static void setVolume(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = 1f;
        volume = clamp(v, 0f, 10f); // 0–1000%（对数滑杆输入；本值直接作为 Soloud 音量倍数）
        Core.settings.put(CFG_VOLUME, volume);
        // 统一交给 refreshVolumes 按本地/远程口径应用（含 pan），避免重复 native 调用
        refreshVolumes();
    }

    public static float volume() {
        return volume;
    }

    /**
     * 应用到 Soloud 的实际音量倍数（0..MAX_EFF_VOL）。
     * UI 允许滑到 0–1000%（volume 存原始 0..10），100% 即 1.0 倍属正常响度。
     * 用户明确要求「1000% 真的放大一千倍」（即 10x 增益，对应 1000% = 10 倍），
     * 故把应用上限放宽到 10.0，让 100%→1.0、1000%→10.0 的比例完整生效，不被削波封顶。
     * 若个别声源实际爆音，可在此单独收紧（应用端已含 clamp 兜底）。
     */
    static float effectiveVolume() {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) return 1f;
        return clamp(volume, 0f, MAX_EFF_VOL);
    }
    /** 应用音量上限：100% = 1.0，1000% = 10.0（用户要求真实的千倍放大） */
    private static final float MAX_EFF_VOL = 10.0f;

    public static void setPitch(float p) {
        if (Float.isNaN(p) || Float.isInfinite(p)) p = 1f;
        pitch = clamp(p, 0.1f, 10f);
        Core.settings.put(CFG_PITCH, pitch);
        applyRate();
    }

    public static float pitch() {
        return pitch;
    }

    /** 当前倍速 */
    public static float speed() {
        return speed;
    }

    /** 设置倍速（1/16–16x）；与音高叠加，实际速率 = pitch * speed */
    public static void setSpeed(float s) {
        if (Float.isNaN(s) || Float.isInfinite(s)) s = 1f;
        speed = clamp(s, MIN_SPEED, 16f);
        Core.settings.put(CFG_SPEED, speed);
        applyRate();
    }

    /** 把「音高 * 倍速」的实际速率应用到本机声源；远程声源保持原速，不随本机倍速联动（否则调本机倍速会让别人的音乐也变速） */
    private static void applyRate() {
        float eff = pitch * speed;
        if (localVoiceId >= 0) Core.audio.setPitch(localVoiceId, eff);
        for (Voice v : voices) {
            if (v.isLocalOwner && v.voiceId >= 0) Core.audio.setPitch(v.voiceId, eff);
        }
    }

    // ------------------------------------------------------------------
    // A-B 区间（区间重复）
    // ------------------------------------------------------------------

    public static float abA() {
        return abA;
    }

    public static float abB() {
        return abB;
    }

    /** 是否已设置有效 A-B 区间（两点均已设且相距 > 0.3s） */
    public static boolean hasAb() {
        return abA >= 0f && abB >= 0f && Math.abs(abB - abA) > 0.3f;
    }

    /** 设 A 点（秒）；B 已设为更早位置时不强制顺序，由 hasAb 用 min/max 处理 */
    public static void setAbA(float seconds) {
        abA = seconds < 0f ? -1f : seconds;
        Core.settings.put(CFG_AB_A, abA);
    }

    /** 设 B 点（秒） */
    public static void setAbB(float seconds) {
        abB = seconds < 0f ? -1f : seconds;
        Core.settings.put(CFG_AB_B, abB);
    }

    /** 清除 A-B 区间 */
    public static void clearAb() {
        abA = -1f;
        abB = -1f;
        Core.settings.put(CFG_AB_A, -1f);
        Core.settings.put(CFG_AB_B, -1f);
    }

    /** 切换 A-B 区间：无区间则设置，有则清除（悬浮条/设置按钮快捷开关） */
    public static void toggleAb() {
        if (hasAb()) clearAb();
        else {
            if (!playing || localVoiceId < 0) return;
            float pos = currentTime();
            // 已设 A 未设 B → 设 B
            if (abA >= 0f && abB < 0f) setAbB(pos);
            else setAbA(pos); // 未设或已设 B 未设 A → 重新设 A
        }
    }

    // ------------------------------------------------------------------
    // 播放进度 / 拖动选进度
    // ------------------------------------------------------------------

    /** 当前播放进度（秒）；本地未播放返回 0。音乐独立于游戏暂停（Fix 9）：声源挂 musicBus，
     *  游戏暂停不再冻结进度，直接读声源实时位置 */
    public static float currentTime() {
        // 恢复播放时 pending seek 尚未应用到声源（新流式源需确认存活后才 idSeek）：
        // 此刻 raw 位置还是 0，直接返回会让人看到「进度条先跳到开头再跳回来」。
        // 在 seek 应用前这段时间，按「将要定位到的位置」上报，避免进度条瞬间归零。
        if (pendingResumeSeek >= 0f) return Math.max(0f, pendingResumeSeek);
        if (localVoiceId >= 0) {
            float p = SoloudBridge.getPosition(localVoiceId);
            if (Float.isNaN(p) || Float.isInfinite(p)) return pausedPosition > 0f ? pausedPosition : 0f;
            return Math.max(0f, p);
        }
        // 播放器暂停后 localVoiceId=-1，返回保存的进度
        return pausedPosition > 0f ? pausedPosition : 0f;
    }

    /** 秒 → m:ss 格式化（供悬浮条/时长显示复用） */
    public static String formatTimeSimple(float sec) {
        if (Float.isNaN(sec) || Float.isInfinite(sec) || sec < 0f) sec = 0f;
        int total = (int) sec;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    /** 当前本地曲目总时长（秒）；未知/未播放返回 -1 */
    public static float trackLength() {
        // 时长优先以「曲目可解析文件」探测（trackLengthOf 走文件/缓存读取，对已落盘的 ogg/mp3/wav 可靠），
        // 防止流式声源 getLength() 返回异常大的值 → seek 时被 clamp 到高进度（问题4「外部跳到超高进度」）。
        // 声源 getLength 仅在文件探测失败时兜底，并对「异常巨大的流式长度」（直播/无限流往往返回超大或 -1）
        // 一律排除 —— 只有有限且有界（≤ 12h）的长度才可用于 seek clamp，否则视为「不限制」，由 seek 端
        // 用当前位置夹取，避免把进度条/seek 基座设到假的天文数字。
        if (current >= 0) {
            float len = trackLengthOf(tracks.get(current));
            if (len > 0f) return len;
        }
        if (localVoiceId >= 0) {
            for (Voice v : voices) {
                if (v.isLocalOwner && v.sound != null) {
                    float l = v.sound.getLength();
                    // 只接受「明确有限且 ≥0」的有界长度；Soloud 对无限流式源可能返回很大/负值，均排除
                    // 修复：上限从 6h 放宽到 12h，覆盖超长 DJ mix/有声书等合理音频；仍拒绝流式/直播源的假大值
                    if (l > 0f && l < 12f * 3600f && !Float.isInfinite(l) && !Float.isNaN(l)) return l;
                }
            }
        }
        // 暂停态：声源已 stop、localVoiceId=-1，无法从 voice 读长度；
        // 若暂停时暂存了长度（防「仅声源可知长度」的外部歌曲探测 -1 导致进度显示变 0）优先使用
        if (!Float.isNaN(pausedLength) && !Float.isInfinite(pausedLength) && pausedLength > 0f) return pausedLength;
        return -1f;
    }

    /** 指定曲目时长（秒）；未知返回 -1。本地曲目优先读原始文件（避免无谓的全文件异步缓存拷贝造成卡顿），
     *  非 ASCII 路径读取失败时回退到已有 ASCII 缓存（若无则不拷贝，返回 -1，播放后将填充）。结果按路径缓存。 */
    public static float trackLengthOf(MusicTrack t) {
        if (t == null) return -1f;
        // 内部曲目：必须提取为真实磁盘文件才能读取时长
        if (t.isInternal()) {
            Fi f = extractInternalToRealFile(resolveToPlayableFile(t), t.source);
            return f == null ? -1f : readLengthFrom(f);
        }
        if (t.isUrl()) {
            Fi f = resolveToPlayableFile(t);
            return f == null ? -1f : readLengthFrom(f);
        }
        // 本地曲目：优先读原始文件（长度/大小都不需要拷贝）；路径 ASCII 也满足。
        // 非 ASCII 原始路径读不出时长时，若无 ASCII 缓存则顺手做一次复制（限大小内），
        // 让无中文路径也能立即显示时长（播放时仍会复用该缓存副本）
        Fi orig = originalLocalFile(t);
        float len = orig == null ? -1f : readLengthFrom(orig);
        if (len <= 0f) {
            Fi cached = cacheFileOf(t);
            if (cached != null && cached.exists()) {
                len = readLengthFrom(cached);
            } else if (orig != null && orig.exists()
                    && orig.isDirectory() == false
                    && orig.length() > 0 && orig.length() <= LENGTH_PROBE_SIZE_LIMIT) {
                Fi safe = localAsciiCopy(t, orig);
                if (safe != null && safe.exists()) len = readLengthFrom(safe);
            }
        }
        return len;
    }

    /** 从指定文件读取时长（秒）；失败返回 -1，结果按文件路径缓存。
     *  注意：Soloud 的 Music.create 对含非 ASCII（如中文）路径会原生 fopen 失败并可能使音频内部锁不一致 → 直接跳过，避免崩溃 */
    private static float readLengthFrom(Fi f) {
        if (f == null || !f.exists()) return -1f;
        String key = f.absolutePath();
        if (!isAsciiPath(key)) return -1f; // 非 ASCII 路径不读（防 Soloud 内部锁崩溃），由 ASCII 缓存补齐
        if (!isDecodablePath(key)) return -1f; // flac/m4a/wma/aac/opus 无 Soloud 解码器，探测会原生失败 → 跳过
        if (f.length() > LENGTH_READ_SIZE_LIMIT) {
            Log.warn("[SiliconMusic] file too large for length probe: " + f.name() + " (" + f.length() + " bytes)");
            return -1f;
        }
        Float cached = lengthCache.get(key);
        if (cached != null) return cached;
        float len = -1f;
        try {
            arc.audio.Music m = arc.audio.Music.create(f);
            try {
                len = m.getLength();
            } finally {
                m.dispose();
            }
        } catch (Exception e) {
            Log.warn("[SiliconMusic] length probe failed for " + f.name() + ": " + e.getMessage());
        }
        if (len <= 0f) {
            Log.warn("[SiliconMusic] length probe returned invalid value for " + f.name() + ": " + len);
        }
        if (lengthCache.size > 256) lengthCache.clear();
        lengthCache.put(key, len);
        return len;
    }

    /** 路径扩展名是否可由 Soloud 内置解码器解码（ogg/mp3/wav） */
    private static boolean isDecodablePath(String path) {
        if (path == null) return false;
        path = path.trim();
        return DECODABLE_EXT.matcher(path).find();
    }

    /** 路径是否全为 ASCII（可安全交给 Soloud 原生 fopen） */
    private static boolean isAsciiPath(String path) {
        if (path == null) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c > 0x7f) return false;
        }
        return true;
    }

    /** 指定曲目本地文件大小（字节）；不可用时返回 -1。本地曲目直接读原始文件，不触发缓存拷贝 */
    public static long trackSizeOf(MusicTrack t) {
        if (t == null) return -1L;
        if (t.isInternal()) {
            Fi f = extractInternalToRealFile(resolveToPlayableFile(t), t.source);
            return f != null && f.exists() ? f.length() : -1L;
        }
        if (t.isLocal()) {
            Fi orig = originalLocalFile(t);
            if (orig != null && orig.exists()) return orig.length();
            Fi c = cacheFileOf(t);
            return c != null && c.exists() ? c.length() : -1L;
        }
        Fi f = resolveToPlayableFile(t);
        return f != null && f.exists() ? f.length() : -1L;
    }

    /** 解析本地曲目的原始源文件（不做任何缓存拷贝）；不存在或为目录返回 null */
    public static Fi originalLocalFile(MusicTrack t) {
        if (t == null || !t.isLocal() || t.source == null) return null;
        try {
            Fi abs = Core.files.absolute(t.source);
            if (abs != null && abs.exists() && !abs.isDirectory()) return abs;
            Fi loc = Core.files.local(t.source);
            if (loc != null && loc.exists() && !loc.isDirectory()) return loc;
        } catch (Exception ignored) {}
        return null;
    }

    /** 相对当前进度增减（秒）：暂停态/游戏暂停态改保存进度，播放态 seek 并夹取在范围内 */
    public static void seekRelative(float delta) {
        if (Float.isNaN(delta) || Float.isInfinite(delta)) return;
        float len = trackLength();
        float pos = currentTime() + delta;
        if (len > 0f && len < 12f * 3600f) {
            pos = arc.math.Mathf.clamp(pos, 0f, Math.max(0f, len - 0.5f));
        } else {
            pos = Math.max(0f, pos);
        }
        seek(pos);
    }

    /** 拖动到指定进度（秒）：播放中直接 seek 流式声源；播放器暂停时只改保存进度（恢复后从该处继续） */
    public static void seek(float seconds) {
        if (Float.isNaN(seconds) || Float.isInfinite(seconds)) seconds = 0f;
        if (seconds < 0f) seconds = 0f;
        // 该声源 seek 已被判不可靠（此前校验失败）：忽略一切拖动/快进快退，避免反复落入错误位置
        if (seekUnreliable) return;
        // 夹取在轨道末尾前 0.5 秒内，防止 seek 到末尾导致流立即结束触发跳歌
        float len = trackLength();
        // 修复：对「真实时长未知(-1)」或「异常大值(>12h)」的外部歌曲，不按假长度夹取，
        // 仅限 0..当前进度+30s（保守上限），防止 seek 到天文数字进度
        if (len > 0f && len < 12f * 3600f) {
            seconds = Math.min(seconds, Math.max(0f, len - 0.5f));
        } else {
            // 未知/不可靠长度：保守限制，只允许向后最多 30 秒
            float cur = currentTime();
            seconds = Math.min(seconds, Math.max(0f, cur + 30f));
        }
        pausedPosition = seconds;
        lastSeekAt = Time.time;
        // 倒放中手动 seek（进度条拖动/快进快退）后，同步倒放累积位置到目标点——
        // 否则 tickLocal 倒放分支仍从旧 lastReversePos 回退，刚跳到的位置立即被反向拉回去（拖了白拖）。
        if (reverse) lastReversePos = Math.max(0f, seconds);
        // 铁律：soloud 对「未确认存活」的声源同步 idSeek 会在原生 arc64.dll 崩溃（断言 !mInsideAudioThreadMutex，
        // 见 hs_err 栈 idSeek→SoloudBridge.seek←MusicPlayer.seek←UI 滑杆 changed；AGENTS「对刚创建的新声源
        //  立刻 idSeek 会原生进程崩溃」）。isPlaying 是 soloud 判定声源真正就绪/可 seek 的唯一可靠信号——
        //  只有 isPlaying 且距建源 ≥0.3s 才同步 seek；否则整体走 deferSeek，由 tickLocal 在声源 isPlaying
        //  后延迟应用。外部流式声源缓冲中拖进度：先 defer，声源一旦真播放即自动应用（延迟但安全，不会崩）。
        if (localVoiceId >= 0) {
            if (Core.audio.isPlaying(localVoiceId) && Time.time - lastBlip >= 0.3f) {
                SoloudBridge.seek(localVoiceId, seconds);
                // 对实际下发的同步 seek 安排结果校验（倒放分支直接操作声源不走这里，倒放时不校验）
                if (!reverse) scheduleSeekVerify(seconds);
            } else {
                deferSeek(seconds);
            }
        }
    }

    /** 记录待校验的 seek：0.35s 后由 tickLocal 读回真实位置比对（两次不匹配判不可靠） */
    private static void scheduleSeekVerify(float target) {
        seekVerifyTarget = target;
        seekVerifyAt = Time.time + 0.35f;
        seekVerifyFails = 0;
    }

    /** 当前声源的进度定位（seek）是否已被判定不可靠（UI 据此禁用拖动/快进快退） */
    public static boolean isSeekUnreliable() {
        return seekUnreliable;
    }

    /** 游戏内 UI 提示（音频层无场景依赖，仅在客户端且 UI 就绪时弹出；失败静默） */
    private static void toast(String bundleKey, String arg) {
        try {
            if (mindustry.Vars.ui == null) return;
            String msg;
            if (arg != null) msg = Core.bundle.format(bundleKey, arg);
            else {
                msg = Core.bundle.get(bundleKey);
                if (msg == null || msg.contains("??")) msg = bundleKey;
            }
            mindustry.Vars.ui.hudfrag.showToast(msg);
        } catch (Exception ignored) {
        }
    }

    public static void setLoopMode(int mode) {
        loopMode = mode;
        Core.settings.put(CFG_LOOP, loopMode);
        if (localVoiceId >= 0) Core.audio.setLooping(localVoiceId, loopMode == LOOP_ONE);
        if (loopMode == LOOP_SHUFFLE) {
            shuffleDirty = true; // 进入随机模式时重建顺序（含当前曲目首次进入的起点）
        } else {
            shuffleOrder.clear();
        }
    }

    public static int loopMode() {
        return loopMode;
    }

    public static void cycleLoopMode() {
        setLoopMode((loopMode + 1) % 6);
    }

    public static boolean isReverse() {
        return reverse;
    }

    /** 切换倒放：开启后进度持续回退；关闭恢复正向。切换不影响当前播放 */
    public static void toggleReverse() {
        reverse = !reverse;
        Core.settings.put(CFG_REVERSE, reverse);
        if (reverse) {
            // 从当前真实进度开始往回退；清空上次的抽稀状态
            lastReversePos = currentTime();
            lastReverseSeekAt = 0f;
            reverseTick = 0;
            lastBlip = Time.time;
            lastSeekAt = Time.time;
        }
    }

    public static void next() {
        if (!enabled || tracks.size == 0) return;
        if (advanceSafely(1)) bcast("next");
    }

    public static void prev() {
        if (!enabled || tracks.size == 0) return;
        if (advanceSafely(-1)) bcast("next");
    }

    /** 广播本机播放状态变化给其他玩家（经 MusicNetwork） */
    private static void bcast(String op) {
        if (!initialized) return;
        MusicNetwork.notifyLocalChanged(op);
    }

    /** 地图切换后若本机仍在播放，重广播当前曲目给新地图玩家（供 MusicNetwork.reset 调用），防远端失去声源 */
    static void reBroadcastIfPlaying() {
        if (playing && canShare() && current >= 0) {
            bcast("play");
        }
    }

    // ------------------------------------------------------------------
    // 曲目解析为可播放 Fi（本地缓存优先复用）
    // ------------------------------------------------------------------

    /** 解析为可播放的本地 Fi：内置→jar 内 music/；URL/LOCAL→命中缓存用缓存，否则返回 null 由网络层补齐 */
    public static Fi resolveToPlayableFile(MusicTrack t) {
        if (t == null) return null;
        if (t.isInternal()) {
            arc.audio.Music m = internalMusic(t.source);
            return m == null ? null : m.file;
        }
        Fi cached = cacheFileOf(t);
        if (cached != null && cached.exists()) return cached;
        if (t.isLocal()) {
            Fi src = null;
            Fi abs = Core.files.absolute(t.source);
            if (abs.exists()) src = abs;
            else {
                Fi loc = Core.files.local(t.source);
                if (loc.exists()) src = loc;
            }
            if (src == null) return null;
            // Soloud 原生 fopen 无法读取含中文/非 ASCII 字符的路径（Windows fopen 用 ANSI 编码），
            // 也依赖扩展名解码。统一把本地文件复制到 ASCII 安全的缓存路径 <hash>.<真实ext> 后再播放。
            Fi safe = localAsciiCopy(t, src);
            return safe; // 不再 fallback 到 src（非 ASCII 路径），由播放端 isAsciiPath 守卫兜底
        }
        return null; // URL 未缓存由网络层下载
    }

    /** 把本地文件内容复制到 ASCII 安全的缓存路径（<hash>.<真实ext>），供 Soloud 流式读取；失败返回 null */
    private static Fi localAsciiCopy(MusicTrack t, Fi src) {
        try {
            // 读文件头嗅探真实容器格式（本地文件扩展名可能与内容不符 → 按内容落盘才能被 Soloud 识别解码）
            String ext = extensionFrom(t.source);
            try (java.io.InputStream in = src.read()) {
                // 探测窗口放大到 1MB，覆盖带大 ID3v2 tag（封面等可达数百 KB）的 mp3 —— 之前只读 16/4096 字节，tag 内找不到帧同步会误 null
                byte[] probe = new byte[PROBE_WINDOW];
                int n = in.read(probe);
                if (n > 8) {
                    byte[] h = new byte[n];
                    System.arraycopy(probe, 0, h, 0, n);
                    String sn = sniffExt(h);
                    if (sn != null) ext = sn;
                }
            }
            String e = ext == null ? ".ogg" : ext;
            evictHashVariants(t.cacheHash, e);
            Fi out = cacheFileForHash(t.cacheHash, e);
            if (out.exists()) {
                // 本地文件内容可能已更新但路径 hash 不变（hash 取路径字符串），若源文件大小与缓存不一致则视为过期，删旧后重拷
                try { if (src.length() == out.length()) return out; else out.delete(); } catch (Exception ignored) { return out; }
            }
            out.parent().mkdirs();
            // 修复：改流式拷贝，不再 `out.write(src.read(), false)` 把整文件读入内存——
            // 大本地文件（≥50MB）在播放路径经 resolveToPlayableFile→localAsciiCopy 时整读会 OOM。
            // Streams.copy 内部用复用缓冲逐块写入，稳占内存且大文件也能放。
            try (InputStream in = src.read(); OutputStream os = out.write(false)) {
                Streams.copy(in, os);
            }
            hashExt.put(t.cacheHash, e);
            return out.exists() ? out : null;
        } catch (Exception e) {
            SiliconLog.log("Local ascii-copy fail " + t.name + ": " + e.getMessage());
            return null;
        }
    }

    private static arc.audio.Music internalMusic(String key) {
        try {
            java.lang.reflect.Field f = mindustry.gen.Musics.class.getField(key);
            return (arc.audio.Music) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 jar 打包的内置音频资源提取为真实磁盘缓存文件（Soloud 只能按磁盘路径流式读取）。
     * 以 source 键缓存，仅首次提取一次；返回可直接 createStream 的真实 Fi，失败返回 null。
     */
    private static Fi extractInternalToRealFile(Fi jarFile, String key) {
        if (jarFile == null) return null;
        try {
            String name = jarFile.name();
            String ext = (name != null && name.contains("."))
                    ? name.substring(name.lastIndexOf('.')) : ".ogg";
            Fi out = cacheFile("int-" + key + ext);
            if (!out.exists()) {
                byte[] data = jarFile.readBytes();
                if (data == null || data.length == 0) return null;
                out.writeBytes(data, false);
            }
            return out.exists() ? out : null;
        } catch (Exception e) {
            SiliconLog.log("Extract internal " + key + " fail: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 本地缓存（文件名统一 `<hash>.<真实扩展名>`，扩展名从源/协议得出，
    // 避免 mp3/wav 被 Soloud 按 .ogg 错误解码）
    // ------------------------------------------------------------------

    /** hash → 真实扩展名（供网络接收侧在分块落盘/查询时还原文件名） */
    private static final ObjectMap<String, String> hashExt = new ObjectMap<>();

    public static Fi cacheFileOf(MusicTrack t) {
        // 优先按已登记/已扫描出的真实扩展名找缓存：URL 无扩展名或下载内容是另种格式时，
        // 落盘文件扩展名可能与 t.source 推导的不同（如 URL 无扩展名、内容实为 mp3），必须用 resolveExt 对齐
        String e = resolveExt(t.cacheHash);
        return cacheFile(t.cacheHash + (e != null ? e : extensionFrom(t.source)));
    }

    /** 登记某 hash 的真实扩展名（网络接收侧写/查缓存前调用） */
    public static void registerHashExt(String hash, String ext) {
        if (hash == null || hash.isEmpty()) return;
        hashExt.put(hash, normalizeExt(ext));
    }

    public static boolean hasCache(String hash) {
        String ext = resolveExt(hash);
        return ext != null && cacheFile(hash + ext).exists();
    }

    public static boolean hasCache(String hash, String ext) {
        String e = normalizeExt(ext);
        return cacheFile(hash + e).exists();
    }

    public static Fi cacheFileForHash(String hash) {
        String ext = resolveExt(hash);
        if (ext == null) return null;
        return cacheFile(hash + ext);
    }

    public static Fi cacheFileForHash(String hash, String ext) {
        return cacheFile(hash + normalizeExt(ext));
    }

    /** 分块接收暂存文件（未收齐前不视为正式缓存，防止半截文件被当作有效缓存） */
    public static Fi stagingFile(String hash) {
        return cacheFile(hash + ".part");
    }

    /** 分块全部收齐后：把暂存文件重命名为正式缓存 `<hash>.<ext>` 并登记扩展名（先按内容头修正扩展名） */
    public static boolean finalizeCache(String hash, String ext) {
        try {
            String e = (ext == null || ext.isEmpty()) ? resolveExt(hash) : normalizeExt(ext);
            Fi staging = stagingFile(hash);
            if (staging == null || !staging.exists()) return false;
            // 嗅探收集到的文件内容头，与实际容器格式不一致则落到正确扩展名（如 owner 广播的 ext 与真实格式不符）
            String sn = sniffFileExt(staging);
            if (sn != null) e = sn;
            evictHashVariants(hash, e);
            Fi finalFile = cacheFileForHash(hash, e);
            staging.moveTo(finalFile);
            hashExt.put(hash, e);
            return finalFile.exists();
        } catch (Exception ex) {
            SiliconLog.log("Cache finalize fail " + hash + ": " + ex.getMessage());
            return false;
        }
    }

    /** 读取文件头嗅探扩展名（供暂存收齐/本地复制用），失败返回 null */
    private static String sniffFileExt(Fi f) {
        try (java.io.InputStream in = f.read()) {
            byte[] head = new byte[PROBE_WINDOW];
            int n = in.read(head);
            if (n < 12) return null;
            byte[] h = new byte[n];
            System.arraycopy(head, 0, h, 0, n);
            return sniffExt(h);
        } catch (Exception e) {
            return null;
        }
    }

    /** 清理残留的未完成分块暂存文件（世界切换时无进行中的传输，避免孤儿 .part 长期堆积） */
    public static void cleanupStagingFiles() {
        try {
            Fi dir = cacheRoot();
            if (dir != null && dir.isDirectory()) {
                for (Fi f : dir.list()) {
                    if (f != null && "part".equalsIgnoreCase(f.extension())) f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 解析 hash 对应缓存文件的真实扩展名：优先扫缓存目录 `<hash>.*`（磁盘事实，可跨重启命中），
     *  其次用已登记扩展名，最后兜底 .ogg */
    private static String resolveExt(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        // 磁盘扫描优先：若缓存目录存在 <hash>.<真实ext>，以磁盘为准（URL 内容格式可能与 URL 扩展名不一致）
        try {
            Fi dir = cacheRoot();
            if (dir != null && dir.isDirectory()) {
                for (Fi f : dir.list()) {
                    if (f != null && "part".equalsIgnoreCase(f.extension())) continue; // 跳过未完成的分块暂存文件
                    if (f != null && f.nameWithoutExtension().equals(hash)) {
                        String e = f.extension();
                        String ext = (e == null || e.isEmpty()) ? ".ogg" : ("." + e.toLowerCase());
                        hashExt.put(hash, ext);
                        return ext;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String known = hashExt.get(hash);
        if (known != null) return known;
        hashExt.put(hash, ".ogg");
        return ".ogg";
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isEmpty()) return ".ogg";
        return ext.charAt(0) == '.' ? ext.toLowerCase() : "." + ext.toLowerCase();
    }

    public static String extensionFrom(String source) {
        source = source == null ? "" : source;
        int q = source.indexOf('?');
        if (q >= 0) source = source.substring(0, q);
        int h = source.indexOf('#');
        if (h >= 0) source = source.substring(0, h);
        int dot = source.lastIndexOf('.');
        if (dot >= 0) {
            String e = source.substring(dot).toLowerCase();
            if (e.equals(".mp3")) return ".mp3";
            if (e.equals(".wav")) return ".wav";
            if (e.equals(".flac")) return ".flac";
            if (e.equals(".m4a")) return ".m4a";
            if (e.equals(".wma")) return ".wma";
            if (e.equals(".aac")) return ".aac";
            if (e.equals(".opus")) return ".opus";
        }
        return ".ogg";
    }

    /** 写缓存（URL 下载 / 二进制共享落地统一走这里）；先嗅探真实格式修正扩展名，再按真实扩展名命名 */
    static boolean writeCacheBytes(String hash, byte[] data) {
        String ext = hashExt.get(hash, ".ogg");
        return writeCacheBytes(hash, ext, data);
    }

    static boolean writeCacheBytes(String hash, String ext, byte[] data) {
        if (hash == null || hash.isEmpty() || data == null || data.length == 0) return false;
        try {
            // 按文件内容轮廓嗅探真实格式：URL/流式链接缺少扩展名或内容与后缀不符时，落到正确扩展名才能被 Soloud 解码
            String detected = sniffExt(data);
            if (detected != null) ext = detected;
            String e = ext == null ? ".ogg" : ext;
            evictHashVariants(hash, e);
            Fi file = cacheFileForHash(hash, e);
            try (OutputStream out = file.write(false)) {
                out.write(data);
            }
            hashExt.put(hash, e);
            return true;
        } catch (Exception e) {
            SiliconLog.log("Cache write fail " + hash + ": " + e.getMessage());
            return false;
        }
    }

    /** 删除该 hash 下除 keepExt 以外的旧缓存变体（<hash>.mp3/.ogg 并存会令扫描取到过期文件） */
    private static void evictHashVariants(String hash, String keepExt) {
        try {
            Fi dir = cacheRoot();
            if (dir == null || !dir.isDirectory()) return;
            for (Fi f : dir.list()) {
                if (f == null || !hash.equals(f.nameWithoutExtension())) continue;
                if ("part".equalsIgnoreCase(f.extension())) continue;
                if (keepExt != null && keepExt.equalsIgnoreCase("." + f.extension())) continue;
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** 按文件头嗅探真实容器格式：取到 Soloud 可解码或已知格式的扩展名；无法识别返回 null。
     *  覆盖：OggS / RIFF-WAVE / fLaC / ftyp(m4a) / ID3v1 / ID3v2(可跳过任意大 tag 找 MPEG 帧同步) / 裸 MPEG */
    private static String sniffExt(byte[] head) {
        if (head == null || head.length < 12) return null;
        if (startsWith(head, "OggS")) return ".ogg";
        if (startsWith(head, "fLaC")) return ".flac";
        if (startsWith(head, 4, "ftyp")) return ".m4a";
        if (startsWith(head, "RIFF") && head.length >= 12 && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
            return ".wav";
        }
        // ID3v2：同步字 "ID3" + 版本 + 标志 + 4 字节同步安全大小（7 位/ byte），按它跳过整段 tag 再在音频区找帧同步
        if (startsWith(head, "ID3") && head.length >= 10) {
            int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            int off = 10 + size;
            if (findMpegFrame(head, Math.min(off, head.length))) return ".mp3";
            return null;
        }
        // 兜底：整个可控段内找裸 MPEG 帧同步（0xFF 高3位111）
        if (findMpegFrame(head, 0)) return ".mp3";
        return null;
    }

    /** 在 head 内从 start 起找 MPEG 帧同步（0xFF 后高 3 位全 1）；找到返回 true。
     *  扫描上限取探测窗口，足以穿透大 ID3v2 tag */
    private static boolean findMpegFrame(byte[] head, int start) {
        int n = Math.min(head.length, PROBE_WINDOW);
        for (int i = start; i + 1 < n; i++) {
            if ((head[i] & 0xFF) == 0xFF && (head[i + 1] & 0xE0) == 0xE0) return true;
        }
        return false;
    }

    private static boolean startsWith(byte[] b, String s) {
        if (b.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((char) (b[i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] b, int off, String s) {
        if (b.length < off + s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((char) (b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 远程声源（由 MusicNetwork 调用，可叠加多个；按 ownerUuid 区分归属）
    // ------------------------------------------------------------------

    /** 播一个远程声源。调用方需保证本地已可解析（内置/已缓存/已下载）。 */
    static void playRemoteVoice(String ownerUuid, String hash, float ownerX, float ownerY) {
        if (!enabled) return;
        // 同 owner 已在播则仅刷新位置
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                v.lastX = ownerX;
                v.lastY = ownerY;
                v.paused = false;
                float vv = (player == null) ? effectiveVolume() : calcListenVolume(ownerX - player.x, ownerY - player.y);
                Core.audio.setVolume(v.voiceId, vv);
                Core.audio.setPaused(v.voiceId, false);
                return;
            }
        }
        MusicTrack t = trackByHash(hash);
        Fi file = null;
        if (t != null) {
            file = resolveToPlayableFile(t);
        }
        // 接收端可能没有对应曲目记录（如本地文件二进制共享），但缓存已存在 → 直接按缓存播
        if ((t == null || file == null || !file.exists()) && hasCache(hash)) {
            file = cacheFileForHash(hash);
        }
        if (file == null || !file.exists()) {
            SiliconLog.log("Remote play: no local file for " + hash);
            return; // 尚未下载/尚未拿到二进制，等下载完成后由网络层再次调用
        }
        if (t != null && t.isInternal()) {
            file = extractInternalToRealFile(file, t.source);
            if (file == null) {
                SiliconLog.log("Remote play: cannot extract internal " + hash);
                return;
            }
        }
        Sound snd = null;
        try {
            if (!isAsciiPath(file.absolutePath())) {
                SiliconLog.log("Block remote play of non-ASCII path: " + file.name());
                return;
            }
            if (!isDecodablePath(file.absolutePath())) {
                SiliconLog.log("Block remote play of undecodable " + file.name());
                return;
            }
            snd = Sound.createStream(file);
            Voice v = new Voice();
            v.ownerUuid = ownerUuid;
            v.hash = hash;
            v.isLocalOwner = false;
            v.sound = snd;
            float vol = (player == null) ? effectiveVolume() : calcListenVolume(ownerX - player.x, ownerY - player.y);
            int id = snd.play(vol, 1f, 0f, false, false, Core.audio.musicBus);
            v.voiceId = id;
            Core.audio.setLooping(id, false);
            v.lastX = ownerX;
            v.lastY = ownerY;
            v.createdAt = Time.time;
            voices.add(v);
            ownerHash.put(ownerUuid, hash);
        } catch (Exception e) {
            // play/createStream 失败时释放刚创建的原生 Sound，避免泄漏
            if (snd != null) {
                try {
                    snd.dispose();
                } catch (Exception ignored) {
                }
            }
            SiliconLog.log("Remote play fail: " + e.getMessage());
        }
    }

    /** 刷新某远程声源位置（收到 mp-pos 时调用） */
    static void updateRemotePosition(String ownerUuid, float x, float y) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid) && v.voiceId >= 0) {
                v.lastX = x;
                v.lastY = y;
                float vv = (player == null) ? effectiveVolume() : calcListenVolume(x - player.x, y - player.y);
                Core.audio.set(v.voiceId, calcListenPan(x), vv);
                return;
            }
        }
    }

    /** 暂停某远程声源（收到 mp-sync pause 时调用） */
    static void pauseRemoteVoice(String ownerUuid) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid) && v.voiceId >= 0) {
                v.paused = true;
                Core.audio.setPaused(v.voiceId, true);
            }
        }
    }

    /** 继续某远程声源（收到 mp-sync resume 时调用） */
    static void resumeRemoteVoice(String ownerUuid) {
        for (Voice v : voices) {
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid) && v.voiceId >= 0) {
                v.paused = false;
                Core.audio.setPaused(v.voiceId, false);
            }
        }
    }

    /** 停止某远程声源（收到 mp-sync stop/next 时调用） */
    static void stopRemoteVoice(String ownerUuid) {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.isLocalOwner && ownerUuid.equals(v.ownerUuid)) {
                disposeVoice(v);
                voices.remove(i);
            }
        }
        ownerHash.remove(ownerUuid);
    }

    public static void clearRemoteVoices() {
        for (int i = voices.size - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (!v.isLocalOwner) {
                disposeVoice(v);
                voices.remove(i);
            }
        }
        ownerHash.clear();
    }

    private static final class SiliconLog {
        static void log(String msg) {
            Log.info("[SiliconMusic] " + msg);
        }
    }

    /**
     * 通过反射调用 arc.audio.Soloud 的包私有 native 方法读取/定位流式播放进度。
     * arc 未对 Sound.createStream 暴露 seek/position 公共 API，而 arc.audio.Music 提供；
     * 这里保留 Sound 流式播放（与远程 mp-pos 广播的 voiceId 模型一致）的前提下，
     * 仅读取/写入本地 voice 的进度，避免为 seek 重构整套播放模型。
     */
    private static final class SoloudBridge {
        private static final java.lang.reflect.Method GET_POS;
        private static final java.lang.reflect.Method SEEK;

        static {
            java.lang.reflect.Method gp = null, sk = null;
            try {
                Class<?> c = Class.forName("arc.audio.Soloud");
                gp = c.getDeclaredMethod("idPosition", int.class);
                gp.setAccessible(true);
                sk = c.getDeclaredMethod("idSeek", int.class, float.class);
                sk.setAccessible(true);
            } catch (Throwable ignored) {
            }
            GET_POS = gp;
            SEEK = sk;
        }

        static float getPosition(int voiceId) {
            try {
                if (GET_POS != null) return (Float) GET_POS.invoke(null, voiceId);
            } catch (Throwable ignored) {
            }
            return 0f;
        }

        static void seek(int voiceId, float seconds) {
            try {
                if (SEEK != null) SEEK.invoke(null, voiceId, seconds);
            } catch (Throwable ignored) {
            }
        }
    }
}
