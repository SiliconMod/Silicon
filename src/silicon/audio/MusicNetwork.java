package silicon.audio;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Http;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static mindustry.Vars.net;
import static mindustry.Vars.netServer;

/**
 * 音乐播放器多人网络层：让其他玩家按距离远近听到本机播放的音乐。
 * <p>
 * 链路：播放者(owner) 上报服务端 → 服务端转发给所有客户端 → 各客户端按「自己到 owner 的距离」
 * 在本地 3D 定位播放同一曲目。多个 owner 可叠加多个声源。
 * <p>
 * 曲目资源分发：
 * - 内置原版音乐：所有人本地都有，仅广播元数据即可播放
 * - URL 曲目：广播 URL，接收端用 Http 下载到本地缓存后播放（本地缓存优先复用）
 * - 本地路径曲目：播放者读文件字节，二进制分块广播，接收端重组写缓存后播放
 *
 * 协议（自定义包）：
 * - "mp-sync" (String, 可靠)：op=play/pause/resume/stop/next + 曲目元数据
 * - "mp-meta"(String, 可靠)：本地路径曲目的二进制分块元数据
 * - "mp-chunk"(Binary, 可靠)：二进制分块（头部含 hash+总块数+块索引）
 * - "mp-pos"(String, 不可靠)：owner 世界坐标
 */
public class MusicNetwork {
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final int HEADER_LEN = 16 + 4 + 4;
    private static final String MSG_SYNC = "mp-sync";
    private static final String MSG_META = "mp-meta";
    private static final String MSG_CHUNK = "mp-chunk";
    private static final String MSG_POS = "mp-pos";

    /** ownerUuid → 其本地路径曲目分块接收状态 */
    private static final ObjectMap<String, ChunkRecv> recv = new ObjectMap<>();
    /** ownerUuid → 播放曲目 hash（mp-pos 定位用） */
    private static final ObjectMap<String, String> ownerHash = new ObjectMap<>();
    /** ownerUuid → 最近已知坐标 */
    private static final ObjectMap<String, float[]> ownerPos = new ObjectMap<>();
    /** hash → 该 URL 下载完成后的待执行回调；命中即表示该 hash 正在下载中（去重，防止对同一 URL 并发多次 Http） */
    private static final ObjectMap<String, arc.struct.Seq<Runnable>> pendingDownloads = new ObjectMap<>();

    private static float lastPosTick = 0;
    private static boolean initialized = false;

    /** 本地路径曲目分块发送状态：流式读盘（复用单块缓冲，不整文件读入内存），帧速分片，避免一次灌爆可靠包队列 */
    private static Fi pendingFi;
    private static java.io.InputStream pendingStream;
    private static final byte[] chunkBuf = new byte[CHUNK_SIZE];
    private static long pendingTotal;
    private static String pendingHash;
    private static int pendingIdx;
    private static int pendingChunks;
    /** 每 tick 最多发送的分块数 */
    private static final int CHUNKS_PER_TICK = 12;

    private MusicNetwork() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        // netClient / netServer 在 mod init 阶段可能尚未创建：
        // 延迟到对应端加载完成事件注册处理器，确保已就绪且只注册一次。
        Events.on(EventType.ClientLoadEvent.class, e -> registerClientHandlers());
        Events.on(EventType.ServerLoadEvent.class, e -> registerServerHandlers());

        // 玩家离开时清理其声源与坐标，避免脱离后残留播放
        Events.on(EventType.PlayerLeave.class, e -> {
            String uuid = e.player == null ? null : e.player.uuid();
            if (uuid == null || uuid.isEmpty()) return;
            ownerPos.remove(uuid);
            recv.remove(uuid);
            ownerHash.remove(uuid); // 防离开玩家的在途下载/分块收齐后仍按旧挂账建立声源
            MusicPlayer.stopRemoteVoice(uuid);
        });

        // 周期性上报本机坐标（若本机正在本地播放）
        Events.run(EventType.Trigger.update, MusicNetwork::tick);
    }

    private static boolean clientRegistered = false;

    private static void registerClientHandlers() {
        if (clientRegistered) return;
        mindustry.core.NetClient nc = mindustry.Vars.netClient;
        if (nc == null) return;
        clientRegistered = true;
        nc.addPacketHandler(MSG_SYNC, MusicNetwork::onSync);
        nc.addPacketHandler(MSG_META, MusicNetwork::onMeta);
        nc.addPacketHandler(MSG_POS, MusicNetwork::onPos);
        nc.addBinaryPacketHandler(MSG_CHUNK, MusicNetwork::onChunk);
    }

    private static boolean serverRegistered = false;

    private static void registerServerHandlers() {
        if (serverRegistered) return;
        if (netServer == null) return;
        serverRegistered = true;
        // 服务端收到客户端上报并转发给所有客户端
        netServer.addPacketHandler(MSG_SYNC, (p, data) -> {
            if (p == null) return;
            Call.clientPacketReliable(MSG_SYNC, data);
        });
        netServer.addPacketHandler(MSG_META, (p, data) -> {
            if (p == null) return;
            Call.clientPacketReliable(MSG_META, data);
        });
        netServer.addPacketHandler(MSG_POS, (p, data) -> {
            if (p == null) return;
            Call.clientPacketUnreliable(MSG_POS, data);
        });
        netServer.addBinaryPacketHandler(MSG_CHUNK, (p, bytes) -> {
            if (p == null || bytes == null) return;
            Call.clientBinaryPacketReliable(MSG_CHUNK, bytes);
        });
    }

    // ------------------------------------------------------------------
    // 发送侧（本机为播放者时触发）
    // ------------------------------------------------------------------

    /** 本机本地播放状态变化时由 MusicPlayer 回调：广播给其他玩家 */
    static void notifyLocalChanged(String op) {
        if (!net.active()) return;
        // 发送侧门控：canShare（enabled 且 shareEnabled）——enabled 关或「不播放给他人」时都不广播。
        // 例外："stop" 是关总开关/关共享时的显式停机信号，必须无条件送达（此时 canShare 已翻 false，
        // 若也被拦，对方会一直按旧声源播我的曲子直到自然播完）。
        if (!"stop".equals(op) && !MusicPlayer.canShare()) return;

        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null && !op.equals("stop")) return;

        String owner = ownerKey();
        switch (op) {
            case "play":
            case "next":
                emitSync(owner, op, t);
                if (t != null && t.isLocal()) {
                    // 本地路径曲目：二进制分块广播
                    sendLocalFile(owner, t);
                }
                break;
            case "pause":
            case "resume":
            case "stop":
                emitSyncSimple(owner, op);
                break;
        }
    }

    private static void emitSyncSimple(String owner, String op) {
        String payload = "{\"owner\":\"" + owner + "\",\"op\":\"" + op + "\"}";
        sendReliable(MSG_SYNC, payload);
    }

    private static void emitSync(String owner, String op, MusicTrack t) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"owner\":\"").append(owner)
          .append("\",\"op\":\"").append(op)
          .append("\",\"hash\":\"").append(t.cacheHash)
          .append("\",\"name\":\"").append(escape(t.name))
          .append("\",\"type\":").append(t.type);
        if (t.isUrl() && t.source != null) {
            sb.append(",\"url\":\"").append(escape(t.source)).append('"');
        } else if (t.isLocal() && t.source != null) {
            sb.append(",\"src\":\"").append(escape(t.source)).append('"');
        }
        // 附带 owner 当前坐标，接收端首帧即可准确定位（mp-pos 稍后到达以期精确）
        sb.append(",\"x\":").append(playerX()).append(",\"y\":").append(playerY());
        sb.append('}');
        sendReliable(MSG_SYNC, sb.toString());
    }

    /** 坐标上报（周期调用） */
    private static void tick() {
        if (!net.active()) return;
        // 帧速送出队列中的二进制分块（本地路径曲目）。
        // 修复（2026-09-03 rev8）：flushPendingChunks 此前在 canShare/isPlaying 守卫之前无条件执行，
        // 且 stop/pause/切曲不会 closePending —— 停止/暂停一首大的本地曲目后，剩余分块仍每帧继续发给远端
        // （远端继续拼装一首 owner 已不播放的文件）。改为与坐标上报同守卫：只有本机确实在播放且共享时才
        // flush，停止/暂停后残留队列停止发送；下一次 sendLocalFile 开头仍会 closePending 收尾旧流。
        if (!MusicPlayer.canShare() || !MusicPlayer.isPlaying()) return;
        flushPendingChunks();
        if (Time.time - lastPosTick < 2f) return;
        lastPosTick = Time.time;
        String owner = ownerKey();
        String hash = MusicPlayer.currentTrack() == null ? "" : MusicPlayer.currentTrack().cacheHash;
        ownerHash.put(owner, hash);
        sendUnreliable(MSG_POS, owner + "|" + hash + "|" + playerX() + "|" + playerY());
    }

    /** 本机作为播放者时，向服务端上报或直接广播 */
    private static void sendReliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketReliable(type, data);
        } else {
            Call.serverPacketReliable(type, data);
        }
    }

    private static void sendUnreliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketUnreliable(type, data);
        } else {
            Call.serverPacketUnreliable(type, data);
        }
    }

    private static void broadcastBinary(String type, byte[] data) {
        if (net.server()) {
            Call.clientBinaryPacketReliable(type, data);
        } else {
            Call.serverBinaryPacketReliable(type, data);
        }
    }

    /** 本地路径曲目：读取文件字节，分块广播 */
    private static void sendLocalFile(String owner, MusicTrack t) {
        Fi file = MusicPlayer.resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            Log.info("[SiliconMusic] local file missing: " + t.source);
            return;
        }
        try {
            closePending(); // 上一轮发送未完时先关掉旧流，避免文件描述符泄漏
            long total = file.length();
            int chunkCount = (int) ((total + CHUNK_SIZE - 1) / CHUNK_SIZE);
            String hash = t.cacheHash;

            // 先广播元数据
            StringBuilder meta = new StringBuilder();
            meta.append("{\"owner\":\"").append(owner)
                .append("\",\"hash\":\"").append(hash)
                .append("\",\"name\":\"").append(escape(t.name))
                .append("\",\"type\":2")
                .append(",\"total\":").append(total)
                .append(",\"chunks\":").append(chunkCount)
                .append(",\"ext\":\"").append(MusicPlayer.extensionFrom(t.source).replace(".", ""))
                .append('}');
            sendReliable(MSG_META, meta.toString());

            // 流式分块（每次只读一块进复用缓冲），由 tick() 每帧限量发出，避免大文件整读内存与包队列暴涨
            pendingFi = file;
            pendingTotal = total;
            pendingHash = hash;
            pendingIdx = 0;
            pendingChunks = chunkCount;
            flushPendingChunks();
            Log.info("[SiliconMusic] broadcast local file " + t.name + " (" + total + "B, " + chunkCount + " chunks)");
        } catch (Exception e) {
            Log.info("[SiliconMusic] local file read fail: " + e.getMessage());
        }
    }

    /** 每帧发出一批待发送分块，直到一次性发完；分块从磁盘按块读取，不整载入内存 */
    private static void flushPendingChunks() {
        if (pendingFi == null || pendingHash == null) return;
        try {
            if (pendingStream == null) pendingStream = pendingFi.read();
            int sent = 0;
            while (pendingIdx < pendingChunks && sent < CHUNKS_PER_TICK) {
                int off = (int) ((long) pendingIdx * CHUNK_SIZE);
                int len = pendingIdx == pendingChunks - 1 ? (int) (pendingTotal - (long) pendingIdx * CHUNK_SIZE) : CHUNK_SIZE;
                int got = 0;
                while (got < len) {
                    int n = pendingStream.read(chunkBuf, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                byte[] header;
                try {
                    header = buildHeader(pendingHash, pendingChunks, pendingIdx);
                } catch (IOException e) {
                    break;
                }
                byte[] payload = new byte[HEADER_LEN + got];
                System.arraycopy(header, 0, payload, 0, HEADER_LEN);
                System.arraycopy(chunkBuf, 0, payload, HEADER_LEN, got);
                broadcastBinary(MSG_CHUNK, payload);
                pendingIdx++;
                sent++;
            }
            if (pendingIdx >= pendingChunks) {
                closePending();
            }
        } catch (IOException e) {
            Log.info("[SiliconMusic] chunk read fail: " + e.getMessage());
        }
    }

    /** 关闭并复位分块发送状态（发送完成或世界重置时调用） */
    private static void closePending() {
        if (pendingStream != null) {
            try {
                pendingStream.close();
            } catch (IOException ignored) {
            }
            pendingStream = null;
        }
        pendingFi = null;
        pendingHash = null;
        pendingTotal = 0;
        pendingIdx = 0;
        pendingChunks = 0;
    }

    private static byte[] buildHeader(String hash, int chunkCount, int idx) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        byte[] hashBytes = new byte[16];
        byte[] src = hash.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(src, 0, hashBytes, 0, Math.min(16, src.length));
        dos.write(hashBytes);
        dos.writeInt(chunkCount);
        dos.writeInt(idx);
        dos.flush();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // 客户端接收侧
    // ------------------------------------------------------------------

    private static void onSync(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String op = extract(data, "op");
            if (owner == null || isSelf(owner)) return;

            String hash = extract(data, "hash");
            int type = parseInt(extract(data, "type"), -1);
            String name = extract(data, "name");
            String url = extract(data, "url");
            String src = extract(data, "src");
            // 首帧坐标（emitSync 附带）
            String sx = extract(data, "x");
            String sy = extract(data, "y");
            float ox = sx == null ? Float.NaN : parseFloatSafe(sx);
            float oy = sy == null ? Float.NaN : parseFloatSafe(sy);
            if (!Float.isNaN(ox) && !Float.isNaN(oy)) {
                ownerPos.put(owner, new float[]{ox, oy});
            }

            if (op.equals("stop")) {
                MusicPlayer.stopRemoteVoice(owner);
                ownerHash.remove(owner);
                recv.remove(owner);
                return;
            }
            if (op.equals("pause")) { MusicPlayer.pauseRemoteVoice(owner); return; }
            if (op.equals("resume")) { MusicPlayer.resumeRemoteVoice(owner); return; }

            // play / next
            if (owner == null || hash == null) return;
            ownerHash.put(owner, hash);
            MusicPlayer.stopRemoteVoice(owner); // 切换曲目时先停旧的

            if (type == MusicTrack.INTERNAL || MusicPlayer.hasCache(hash)) {
                // 可立即播放（内置或已有缓存）
                float[] pos = ownerPos.get(owner);
                MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
            } else if (type == MusicTrack.URL && url != null) {
                downloadAndPlay(owner, hash, url, name);
            } else if (type == MusicTrack.LOCAL) {
                // 等 mp-meta / mp-chunk；若本地已有同名曲目（本机也加过同源），可能已在 tracks 里
                if (MusicPlayer.trackByHash(hash) != null && MusicPlayer.hasCache(hash)) {
                    float[] pos = ownerPos.get(owner);
                    MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onSync err: " + e.getMessage());
        }
    }

    private static void downloadAndPlay(String owner, String hash, String url, String name) {
        // 保证已有曲目记录（接收方本地建立一条 URL 元数据，便于缓存查找）
        if (MusicPlayer.trackByHash(hash) == null) {
            int dup = MusicPlayer.indexOfHash(hash);
            if (dup < 0) MusicPlayer.addTrack(MusicTrack.URL, url, name);
        }
        if (MusicPlayer.hasCache(hash)) {
            playRemoteIfStillCurrent(owner, hash);
            return;
        }
        MusicPlayer.registerHashExt(hash, MusicPlayer.extensionFrom(url));
        // 同一 URL 已在下载中时仅登记回调（去重，避免并发多次 Http）；下载完成统一触发各自回调
        downloadHash(hash, url, () -> playRemoteIfStillCurrent(owner, hash));
    }

    /** 回调里检查 owner 是否仍在播放该 hash：避免下载完成/分块收齐时，owner 已 stop/切曲却仍建立声源 */
    private static void playRemoteIfStillCurrent(String owner, String hash) {
        if (!hash.equals(ownerHash.get(owner))) return;
        float[] pos = ownerPos.get(owner);
        MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
    }

    /** 本机播放 URL 曲目但尚未下载缓存时，先从网络下载到本地缓存，完成后回调 onDone（主线程）。 */
    static void fetchLocalThenPlay(MusicTrack t, Runnable onDone) {
        if (t == null || !t.isUrl() || t.source == null) return;
        // 登记真实扩展名：writeCacheBytes 才会写入 <hash>.<真实ext>，避免 mp3/wav 被写成 .ogg 而解码失败、反复重下
        MusicPlayer.registerHashExt(t.cacheHash, MusicPlayer.extensionFrom(t.source));
        if (MusicPlayer.hasCache(t.cacheHash)) {
            Core.app.post(onDone);
            return;
        }
        downloadHash(t.cacheHash, t.source, onDone);
    }

    /** 按 URL 下载到缓存并去重：同一 hash 已在下载中时只追加回调、不重复发起 Http；下载完成后统一在主线程触发所有回调。 */
    private static void downloadHash(String hash, String url, Runnable onDone) {
        if (MusicPlayer.hasCache(hash)) {
            Core.app.post(onDone);
            return;
        }
        arc.struct.Seq<Runnable> pend = pendingDownloads.get(hash);
        if (pend != null) {
            pend.add(onDone);
            return; // 已在下载中，去重：只登记回调，不重复发起请求
        }
        pend = new arc.struct.Seq<>();
        pend.add(onDone);
        pendingDownloads.put(hash, pend);
        Log.info("[SiliconMusic] downloading " + url);
        Http.get(url, res -> {
            byte[] bytes = res.getResult();
            if (bytes == null || bytes.length == 0) {
                Log.info("[SiliconMusic] download empty: " + url);
                Core.app.post(() -> pendingDownloads.remove(hash));
                return;
            }
            Core.app.post(() -> {
                boolean ok = MusicPlayer.writeCacheBytes(hash, bytes);
                arc.struct.Seq<Runnable> done = pendingDownloads.remove(hash);
                if (ok) {
                    if (done != null) {
                        for (Runnable r : done) r.run();
                    }
                    // 弹窗开着则刷新曲目行（时长/大小立即落位，无需重开弹窗）
                    silicon.ui.MusicPlayerDialog.refreshIfOpen();
                }
            });
        }, err -> {
            Log.info("[SiliconMusic] download fail: " + err.getMessage());
            // 失败时清掉排队回调（切回主线程操作 pendingDownloads，避免 Http 线程与主线程并发操作 ObjectMap）
            Core.app.post(() -> pendingDownloads.remove(hash));
        });
    }

    private static void onMeta(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String hash = extract(data, "hash");
            int chunks = parseInt(extract(data, "chunks"), 0);
            String ext = extract(data, "ext");
            if (owner == null || hash == null || chunks <= 0 || isSelf(owner)) return;
            ownerHash.put(owner, hash);
            if (ext != null && !ext.isEmpty()) MusicPlayer.registerHashExt(hash, ext);
            if (MusicPlayer.hasCache(hash)) return; // 已有缓存，无需接收分块
            // 建接收缓冲与缓存文件（先写占位）
            ChunkRecv r = new ChunkRecv();
            r.hash = hash;
            r.ext = ext;
            r.chunkCount = chunks;
            r.received = new boolean[chunks];
            r.total = chunks;
            recv.put(owner, r);
        } catch (Exception e) {
            Log.info("[SiliconMusic] onMeta err: " + e.getMessage());
        }
    }

    private static void onChunk(byte[] payload) {
        if (!MusicPlayer.canReceive()) return;
        if (payload == null || payload.length < HEADER_LEN) return;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(payload, 0, HEADER_LEN);
            DataInputStream dis = new DataInputStream(bis);
            byte[] hashBytes = new byte[16];
            dis.readFully(hashBytes);
            int chunkCount = dis.readInt();
            int idx = dis.readInt();
            String hash = new String(hashBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (hash == null || hash.isEmpty()) return;
            int dataLen = payload.length - HEADER_LEN;
            if (dataLen <= 0) return;

            ChunkRecv r = recvByHash(hash);
            if (r == null) {
                // 未收到 meta（乱序）：按头部信息重建
                r = new ChunkRecv();
                r.hash = hash;
                r.chunkCount = chunkCount;
                r.total = -1;
                r.received = new boolean[chunkCount];
                recv.put(hash, r);
            }
            if (idx < 0 || idx >= r.received.length || r.received[idx]) return;

            // 追加写暂存文件：reliable 包有序，按到达顺序 append；未收齐前不视为正式缓存
            Fi staging = MusicPlayer.stagingFile(hash);
            staging.parent().mkdirs();
            if (idx == 0 || !staging.exists()) staging.write(false).close(); // 首块/不存在时截断，避免旧残留
            try (java.io.OutputStream out = staging.write(true)) {
                out.write(payload, HEADER_LEN, dataLen);
            }
            r.received[idx] = true;
            r.receivedCount++;

            if (r.receivedCount >= r.received.length) {
                // 收齐 → 把暂存文件 moveTo 转正式缓存，再尝试按 owner 播放
                recvRemoveByHash(hash);
                MusicPlayer.finalizeCache(hash, r.ext);
                // 弹窗开着则刷新曲目行（本地文件共享收齐后时长/大小立即落位）
                silicon.ui.MusicPlayerDialog.refreshIfOpen();
                String owner = ownerOfHash(hash);
                if (owner != null && !isSelf(owner)) {
                    playRemoteIfStillCurrent(owner, hash);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onChunk err: " + e.getMessage());
        }
    }

    private static ChunkRecv recvByHash(String hash) {
        ChunkRecv direct = recv.get(hash);
        if (direct != null) return direct;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) return e.value;
        }
        return null;
    }

    private static String ownerOfHash(String hash) {
        for (ObjectMap.Entries<String, String> it = ownerHash.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, String> e = it.next();
            if (hash.equals(e.value)) return e.key;
        }
        String fallback = null;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (e.value != null && hash.equals(e.value.hash)) { fallback = e.key; break; }
        }
        return fallback;
    }

    private static void recvRemoveByHash(String hash) {
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) { it.remove(); }
        }
    }

    private static void onPos(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String[] parts = data.split("\\|");
            if (parts.length < 4) return;
            String owner = parts[0];
            String hash = parts[1];
            if (owner == null || owner.isEmpty() || hash == null || hash.isEmpty()) return;
            float x = Float.parseFloat(parts[2]);
            float y = Float.parseFloat(parts[3]);
            if (isSelf(owner)) return;
            ownerHash.put(owner, hash);
            ownerPos.put(owner, new float[]{x, y});
            MusicPlayer.updateRemotePosition(owner, x, y);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String ownerKey() {
        Player p = mindustry.Vars.player;
        if (p == null) return "none";
        return (p.uuid() != null && !p.uuid().isEmpty()) ? p.uuid() : ("id:" + p.id());
    }

    private static boolean isSelf(String owner) {
        return owner != null && owner.equals(ownerKey());
    }

    private static float playerX() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.x;
    }

    private static float playerY() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.y;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            String patNum = "\"" + key + "\":";
            int j = json.indexOf(patNum);
            if (j < 0) return null;
            int end = json.indexOf(',', j);
            if (end < 0) end = json.indexOf('}', j);
            if (end < 0) return null;
            return json.substring(j + patNum.length(), end).trim();
        }
        int start = i + pat.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && (end == 0 || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float parseFloatSafe(String s) {
        if (s == null) return Float.NaN;
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static class ChunkRecv {
        String hash;
        String ext;
        int chunkCount;
        int total;
        boolean[] received;
        int receivedCount;
    }

    /** 是否正在下载该 hash 的 URL 曲目（供 UI 显示“下载中”） */
    public static boolean isDownloading(String hash) {
        return hash != null && pendingDownloads.containsKey(hash);
    }

    /** 是否正在接收该 hash 的本地文件分块（供 UI 显示“接收中”） */
    public static boolean isReceiving(String hash) {
        if (hash == null) return false;
        if (recv.containsKey(hash)) return true;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) return true;
        }
        // 分块接收已开始但 recv 条目已被清理的极短窗口内，暂存文件仍存在也视为接收中
        try { if (MusicPlayer.stagingFile(hash).exists()) return true; } catch (Exception ignored) {}
        return false;
    }

    public static int receiveProgress(String hash) {
        if (hash == null) return -1;
        ChunkRecv r = recv.get(hash);
        if (r == null) r = recvByHash(hash);
        if (r == null || r.received == null || r.received.length == 0) return -1;
        return (int) (r.receivedCount * 100f / r.received.length);
    }

    /** 世界加载/地图切换时清空网络残局 */
    public static void reset() {
        recv.clear();
        ownerHash.clear();
        ownerPos.clear();
        pendingDownloads.clear();
        closePending();
        MusicPlayer.clearRemoteVoices();
        MusicPlayer.cleanupStagingFiles();
        // 本机仍在播放时，切换地图后重广播当前曲目，避免新地图玩家失去该声源
        MusicPlayer.reBroadcastIfPlaying();
    }
}
