package silicon.audio;

/**
 * 音乐曲目数据模型。
 * 三种来源：
 * - INTERNAL: 游戏内置原版音乐（Musics.*），所有玩家本地都有，共享只广播元数据
 * - URL:     网络地址，播放/共享需下载到本地缓存
 * - LOCAL:   播放者本地磁盘路径，共享需通过二进制分块广播字节
 * <p>
 * cacheHash 为内容唯一标识（SHA-256），用于本地缓存文件命名与去重，
 * 也是「本地缓存优先复用」的关键：同 hash 直接命中本地缓存，不再走网络。
 */
public class MusicTrack {
    public static final int INTERNAL = 0;
    public static final int URL = 1;
    public static final int LOCAL = 2;

    /** 曲目类型（INTERNAL/URL/LOCAL） */
    public int type;
    /** 展示名 */
    public String name;
    /** URL 或本地路径（INTERNAL 类型存内置音乐 key，如 "game1"） */
    public String source;
    /** 内容 SHA-256 前 16 位十六进制，用于缓存文件命名与去重 */
    public String cacheHash;
    /** 客户端展示用曲目类型标签 key（供 bundle 翻译） */
    public String typeKey;

    public MusicTrack() {}

    public MusicTrack(int type, String name, String source, String cacheHash, String typeKey) {
        this.type = type;
        this.name = name;
        this.source = source;
        this.cacheHash = cacheHash;
        this.typeKey = typeKey;
    }

    public boolean isInternal() {
        return type == INTERNAL;
    }

    public boolean isUrl() {
        return type == URL;
    }

    public boolean isLocal() {
        return type == LOCAL;
    }

    @Override
    public String toString() {
        return "MusicTrack{" + name + ", type=" + type + ", source=" + source + ", hash=" + cacheHash + "}";
    }
}
