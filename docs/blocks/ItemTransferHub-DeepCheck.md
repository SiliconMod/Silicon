# 每轮修改结束深度检查再启动 — 固化流程（ItemTransferHub）

> 适用于 Silicon `ItemTransferHub` 相关修改。未通过不得执行 `run-hotreload.bat` 启动。

## 触发时机
每次修改 `src/silicon/world/blocks/distribution/ItemTransferHub*.java` 或 `build.gradle` 后、`deploy` 之前，以及 `deploy` 成功后、覆盖 `data/mods` 并重启之前。

## 固化步骤（按序执行，任一项 ❌ FAIL 即阻断启动）

| 步骤 | 检查项 | 锚点（当前代码） |
|------|--------|------------------|
| S1 | isFactory 委托 HubRouting | `HubRouting.isFactory(b)` |
| S2 | isFactory 含重构工厂 | HubRouting：`Reconstructor.ReconstructorBuild` |
| S3 | 白名单无「有物品栏即连」泛化 | HubRouting 不含 `if (other.items != null) return true` |
| S4 | 推送输入料保护门（静态配方判定） | `producer.block.consumesItem(item))` |
| S5 | 炮台伤害优先 | `ammoTypes.get(b).damage` |
| S6 | push 堵线触发 | `blocked = false` |
| S7 | 核心真实容量余量 + 未满即推 | `coreHasRoomFor(core, item)`、`probe.storageCapacity * surplusPushAt`、`directTransfer(producer, core, item, 10);` |
| S8 | 越界防护 | `item.id >= consumer.items.length()` |
| S9 | 电力硬门控（不足完全停止） | `power.status < POWER_OK`、`STARVE_COOLDOWN_TICKS`、`probing = true` |
| S10 | 调度节流 | `timer(0, 10)` |
| S11 | chargeOne 单跳计费 | `private void chargeOne(` |
| S12 | 延迟计费/计数并入 | `transferCount += transferCountNext`、`smoothBuf[smoothIdx] += powerConsumedNext` |
| S13 | 瞬时请求平滑 | `smoothSum() / SMOOTH_TICKS` |
| S14 | 耗电按秒(60t窗)/速率10s窗统计 | `powerSecondWindow.add(actualPower)` |
| S15 | 存档序列化 v1 | `write.i(network.id)` + `revision < 1` |
| S16 | 核心满回退仓库跨网 BFS | `寻找其它中枢直连的仓库` |
| S17 | 加载期防误删链接 | `world.isGenerating()` |
| S18 | BFS 池化复用 + 路径过滤欠压中枢 | `bfsInit`、`!relayable(` ≥6 处 |
| S19 | 端点归属枢校验 / 仓库落点自排除 | `!relayable(srcHub)`、`b == producer) continue` |
| S20 | 供源仅产出物——不抽工厂输入料 | `isProducer(b) && !b.block.consumesItem(item)` |
| S21 | 无兜底动用工厂输入库存 | ItemTransferHub 不含 `isInputStockOfFactory` |

## 自动化
`powershell -ExecutionPolicy Bypass -File scripts/hub-deep-check.ps1`
返回 26/26 PASS 且 BUILD 成功才允许覆盖游戏模组目录并重启。

## 人工复核
- 放置预览：拖中枢幽灵是否见淡蓝灰细线 + 方框；传送带等纯物流方块不出现可连提示
- 工厂供料：仓库有货时工厂被拉至容量；**重构工厂同样被供料**
- **工厂原料不被外抽**：网络内唯一持有某原料的工厂，其输入料不会被抽给其它消费者（炮台等从仓库/核心取）
- 贴面布局：仓库紧贴工厂时仍能正常供料
- 核心未满即推：矿机/工厂产物优先入核心（不足 75% 时仓库存量也回收至核心）；核心满才落仓库
- 仅仓库-中枢-窑炉布局：窑炉输入料只进不出（无乒乓空转），速率 ≈ 窑炉真实消耗折算，耗电 ≈ 10 × 速率
- 断电完全停转：切断电力后全部中枢停止搬运、路径绕开无电枢；来电后经冷却+探测恢复
- 核心满回退：核心对应物品全满时溢出流向仓库（含跨中枢连接的仓库）
- 跨枢统计：中转枢纽的传输速率与耗电均有读数，耗电 ≈ 10 × 该枢经手速率；电网侧消耗曲线平稳无跳变
- 炮台：多弹种时优先高 damage
