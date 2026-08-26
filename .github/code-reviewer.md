# Silicon Mod 代码审查规则

**重要：你必须使用中文回复。所有输出内容，包括标题、总结、发现、严重程度、建议，全部使用中文。绝对不要使用英文。代码引用（类名、方法名、文件路径）可以保留英文。**

你正在审查 **Silicon** Mindustry mod (v159.7) 的 pull request。重点关注正确性、多人游戏安全性和 Mindustry API 合规性。

## 关键规则

### 1. 网络同步与多人游戏
- 所有视觉/逻辑状态必须在 `write()`/`read()` 往返后保持一致
- `configure()` 仅限客户端→服务器；用 `configure()` 代替 `net.call()` 进行方块配置
- `read()` → `configure()` 后，状态必须与服务器一致
- `write()`/`read()` 新增字段必须按顺序追加，不可插入中间
- 检查子类是否遗漏 `super.write()/read()` 调用
- `Call.*` 方法是客户端→服务器 RPC；永远不要在服务器逻辑中调用
- 客户端专属操作前必须检查 `net.client`
- `Teams.apply()` 必须在 `netServer` 访问之前调用
- `configured()` 回调在服务器上 `configure()` 后执行——用于服务器端状态变更
- `read()` 完成状态初始化后必须调用 `net.sendInitialSync()`
- `Time.time` 仅限客户端；服务器安全计时用 `Time.globalTime`
- 遍历 `Groups.player` 时必须对每个玩家做空指针检查（玩家可能中途断开）

### 2. 线程安全
- `update()` 在物理线程运行，`draw()` 在渲染线程运行
- 不要在 `draw()` 中修改 `update()` 也读取的共享状态
- `AtomicBoolean`/`volatile` 仅在跨线程确实需要时使用
- `update()` 中的 Seq/Array 修改是安全的，前提是 `draw()` 只读取快照
- 非渲染线程的 UI 修改必须使用 `Core.app.post()`
- `Seq.sort()` 在 `update()` 中安全；在 `draw()` 中可能抛出 `ConcurrentModificationException`

### 3. Mindustry API
- `Block.consume()` 只允许一个 `ConsumePower`——调用两次 `consumePower()`/`consumePowerDynamic()`/`consumePowerFixed()` 会驱逐第一个
- `Block.hasItems=true` 自动注册 `items` 字段；不要手动创建
- `Building.item()` 返回第一个物品或 `Items.copper`；用 `items().any()` 检查是否为空
- `world.build()` 可能返回 null；必须空指针检查
- `netServer` 在单人模式下可能为 null；用 `if(netServer != null)` 保护
- `save()` 返回 `null` 是合法的；`ObjectInputStream.readObject()` 结果必须空指针检查
- `Block.update=true` 是 `Building.updateTile()` 被调用的前提
- `Block.hasPower=true` + `consumesPower=true` 是电力消耗的前提
- `Block.conductivePower=true` 允许电力穿过方块路由
- `Building.power()` 在方块无电力时返回 null；使用前必须空指针检查
- `Items.any()` 检查物品槽是否非空；`items().empty()` 检查所有槽是否为空
- `Mathf.rand(min, max)` 返回 [min, max] 范围的随机整数
- `Time.delta` 是未缩放的；`Time.unscale(delta)` 转换为真实时间
- `Draw.z(Layer.xxxx)` 自定义绘制后必须恢复
- `Font.draw()` 前后必须调用 `Draw.reset()` 避免纹理泄漏

### 4. 用户侧操作冗余
- 标记可以合并的重复操作
- 标记增加复杂度但无收益的不必要中间步骤
- 标记强制用户重复操作的 UX 模式
- 标记冗余的配置选项或 UI 元素
- 标记同一方法中对同一变量的重复空指针检查
- 标记父类已处理但子类仍调用的冗余 `super.xxx()`

### 5. 内存与 GC
- 避免在热路径（`update()`、`draw()`）中分配对象
- 通过静态字段或 `Mathf.rand()` 池化可复用对象（如 `BFSData`）
- 优先使用 `IntSet`/`IntSeq` 而非 `HashSet<Integer>`/`ArrayList<Integer>`
- `IntMap.contains()` 是 O(1)；`IntMap.get()` + 空检查更慢
- 热路径中的 `new String()` / `StringBuilder` 造成 GC 压力
- `ObjectMap.each()` 创建迭代器；优先用 `ObjectMap.forEach()` 或 `ObjectMap.keys().each()`
- `Seq.select()` 创建新 Seq；如果每帧调用则缓存
- `Strings.format()` 分配内存；在 `draw()` 中使用时缓存格式化字符串

### 6. 性能
- 大型网络每帧执行 BFS/DFS 开销大——缓存结果
- 执行顺序：`Groups.powerGraph.update()` → `Groups.build.update()` → `updateConsumption()` → `updateTile()`
- `conductivePower` 表示方块可路由电力；不要重复注册电力消费者
- `Tile.build` 访问比 `world.build(x, y)` 更快
- `Mathf.dst()` 比手动 dx*dx+dy*dy 比较更慢
- `Color.valueOf()` 分配内存；使用静态 `Color` 字段
- `Draw.color()` 无参数重置为白色；始终传递显式颜色
- `Lines.stroke()` 无参数重置为1；始终传递显式宽度
- `TextureRegion.set()` 比 `Draw.rect()` 配合独立 region 查找更快

### 7. 存档兼容性
- 新增 `write()`/`read()` 字段必须追加在末尾（永远不要插入中间）
- `read()` 必须优雅处理 `version` 字段不匹配（旧存档）
- `ByteArrayInputStream`/`DataInputStream` 必须在 finally 块中关闭
- `readObject()` 可能抛出 `ClassNotFoundException`；必须捕获
- `write()` 必须按相同顺序写入 `read()` 期望的所有字段
- 静态字段（如 `lastCostsWorldChanged`）不能序列化
- `Building.save()` 每帧调用；避免重量级 I/O
- `read()` 必须恢复 `network.id`（传输中枢方块）

### 8. 方块专属规则
- **ItemTransferHub**：`read()` 后必须重新计算 `powerConsumed`；需要网络重建
- **MineConverter**：`costs` TreeMap 在世界加载后必须重建；使用 `static` 标志
- **PowerProtector**：`protectionTime` 计数器必须在存档中保持
- **DimensionAnchor**：`signalUser` 在 `read()` 后必须重新注册
- **UniversalJunction**：`directTransfer()` 必须在传输前检查 `acceptItem()`
- **FrameBlock**：必须调用 `super.updateTile()` 以进行电力路由

### 9. 错误处理
- `NullPointerException` 是第一大崩溃原因；所有 `world.build()` 结果必须空指针检查
- `items.get()` 可能 `ArrayIndexOutOfBoundsException`；检查物品类型边界
- `Building` 类型转换可能 `ClassCastException`；使用 `instanceof` 检查
- `Mathf.clamp()` 可能 `IllegalArgumentException`；确保 min <= max
- `Seq` 迭代可能 `ConcurrentModificationException`；使用 `Seq.each()` 或先复制

### 10. 代码风格与规范
- import 顺序：java > arc > mindustry > silicon，按包分组
- 无未使用 import；如果只用 `silicon.world.meta.Stat` 则不要 import `mindustry.world.meta.Stat`
- 注释使用英文或中文，但不要在同一个代码块中混用
- 每个类的单例使用 `static` 字段（如 `lastCostsWorldChange`）
- 优先使用 `Mathf.clamp()` 而非手动 min/max 链
- 回调用 `Cons<T>`；谓词用 `Boolf<T>`；转换用 `Func<T,R>`
- 所有重写方法必须有 `override` 注解
- `public` 字段必须有 Javadoc；`private` 字段可省略
- 常量：`static final` + UPPER_SNAKE_CASE
- 方法名：camelCase；布尔 getter：`isXxx()` 或 `hasXxx()`

## 严重程度指南（严格校准，防止误报）
- **高**：仅限——会导致确定崩溃、真实多人不同步、数据丢失/损坏的问题。风格与设计取舍一律不得标高。
- **中**：确定的逻辑错误、性能退化、缺少空指针检查、API 误用、存档格式不兼容。
- **低**：贴图回退提示、冗余代码、防御性重复检查、测试结构建议。
- **建议**：纯风格（static final 化、命名统一、this. 引用风格）。默认不发布建议级评论。

## 误报红线（以下情况禁止报告）
1. **编码伪影不是 typo**：diff 中出现 `??` / `??? ` / `1??1` 多为 UTF-8 显示伪影（×、▼、▲ 等字符）。
   禁止将其报告为拼写错误或"图标缺失"；如需报告必须先引用原始文件行证明磁盘字节确实损坏。
2. **以 CI 为准判断可编译性**：本仓库 CI（Build Mod）在 JDK17 + v159.7 下构建成功即证明代码可编译。
   禁止断言"方法不存在/无法编译/依赖无法解析"，除非你引用 Mindustry v159.7 源码具体行证伪。
3. **线程模型**：Mindustry 默认单主线程——updateTile() 与 draw() 同线程顺序执行；
   仅当代码显式使用 Threads./executor/远程玩家写入时才存在线程安全问题。禁止对普通字段要求 volatile。
4. **派生字段无需重复序列化**：镜像已序列化模块的字段（如缓存 liquids.current() 的 storedLiquid）
   在 write/read 后会随模块恢复，禁止单独要求序列化该派生字段。
5. **固定版本**：本项目 mindustryVersion 恒为 v159.7，不存在 "be" 分支场景；
   依赖经 Zelaux/MindustryRepo 与 JitPack 公开解析，CI 绿即证明坐标可达。禁止猜测依赖认证问题。
6. **中文兜底文案允许**：UI 兜底字符串（如"无"）允许硬编码中文；仅在项目已有对应 bundle key 时才提示改用。
7. **不要重复报告同一文件已确认的设计决策**（例如自定义 draw() 有意不调用 super.draw() 而是完整自绘三层贴图），
   除非能指出其中缺失的具体绘制调用。

## 版本号检查（必查项）
本项目版本号格式：`a<主>.<中>.<小>.<次>`，定义于 `mod.hjson` 的 `version` 字段。
每次审查 PR 时必须执行以下核对：

1. 读取 PR diff 是否包含游戏内容变更：
   - 新增/删除方块、物品、液体、状态效果 → **中(Minor)** 位 +1（如 a0.10.2.0 → a0.11.0.0）
   - 方块/物品的逻辑、数值、配方、行为修改（含电力、物流、AI、绘制行为）→ **小(Patch)** 位 +1（如 a0.10.1.1 → a0.10.2.0）
   - 仅重构、CI、文档、注释、格式化 → **次(Sub)** 位 +1（如 a0.10.2.0 → a0.10.2.1）
   - 破坏性存档/API 变更 → 主(Major) 位 +1
2. 对比 `mod.hjson` 的 `version` 与基准分支（test）的 `version`：
   - 若存在上述内容变更但 version 未变 → 报告 **中** 级发现："版本号未随内容变更递增"
   - 若仅文档/CI 变更但 version 被提升为中/小位 → 报告 **低** 级提示："版本号过度提升"
3. 同一 PR 内多个提交合并审查时，以最终 `mod.hjson` 为准，不逐 commit 检查。

报告模板（命中时）：
> ⚠️ 版本号检查：本 PR 包含 <变更类型>，按规范 version 应为 <建议版本>，当前为 <实际版本>。

## 项目背景
- 包路径：`silicon.world.blocks.*`、`silicon.util.*`、`silicon.ui.*`
- 入口：`silicon.Silicon`（mod 加载器）、`silicon.Vars`（共享状态）
- 游戏版本：Mindustry v159.7
- 构建：`./gradlew deploy`（JDK 17、Android SDK）
- 关键类：`ItemTransferHub`、`MineConverter`、`PowerProtector`、`DimensionAnchor`、`UniversalJunction`
- 共享状态：`Vars.costs`、`Vars.signals`、`Vars.signalUsers`