# MineConverter

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `MineConverter` |
| 父类 | `FrameBlock` |
| 分类 | Category.crafting |
| 尺寸 | 3x3 |
| 血量 | 默认 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Graphite | 200 |
| Silicon | 250 |
| Thorium | 250 |
| Plastanium | 100 |

## Block 属性

- `hasItems`: true
- `hasPower`: true
- `consumesPower`: true（200/60）
- `outputsPower`: false
- `conductivePower`: false
- `update`: true
- `solid`: true
- `configurable`: true
- `sync`: true
- `drawArrow`: false
- `saveConfig`: true

## 机制说明

### 核心机制

矿物转换器，将世界矿物稀有度转化为生产价值。通过消耗矿物来生产选定的输出物品。

### 特殊行为

1. **世界成本计算（服务端权威 + 多人同步）**: `countWorldCosts()` 扫描整个世界地图，计算每种矿物的稀有度成本。成本表（`silicon.Vars.costs`）**只由服务端（房主/专用服务器）统一计算**，并通过自定义网络包 `silicon-mine-converter-costs` 广播给所有客户端；客户端直接使用收到的数据，不再各自从本地世界计算，保证房主与其他玩家的显示（选择列表、进度条）与机器行为完全一致。客户端每次地图加载后还会主动请求一次当前成本，覆盖广播竞态。
2. **新地图清理**: 进入新地图时（`WorldLoadEvent`）清除上一地图残留的矿物成本、选择列表、`scaled` 显示映射，并重置 `Block.lastConfig`，避免新放置的转换器继承上一地图选中的矿物。服务端随后按新地图重算并广播（即使地图无矿也会广播空结果，让客户端停止本地兜底）。
3. **消耗阶段**: 选择库存中最丰富且**本图存在矿点**的矿物，每周期消耗 1 单位，转换为 `mineValue`（本图没有矿点的矿物不会被消耗）
4. **制作阶段**: 将 `mineValue` 转换为 `craftValue`，达到目标成本后产出 1 个输出物品；**若目标矿物在本图已无矿点（成本为 0），机器停止产出**，避免凭空生成物品
5. **稀有度缩放**: 稀有矿物成本更低（更容易产出）

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `craftTime` | float | 60 | 制作一个输出物品的时间 |
| `consumeTime` | float | 60 | 消耗一个输入物品的时间 |
| `consumptionMultiples` | float | 0.1 | 制作成本附加百分比 |
| `scaled` | TreeMap | - | 稀有度缩放映射 |

## 电力系统

- **消耗方式**: `consumePower(200/60)` 静态消耗

## 物品处理

- **输入**: 从玩家配置选择的矿物
- **输出**: 玩家配置的输出物品
- **拒绝**: 当前选中的制作物品和其他 MineConverter 的物品

## 状态栏 (Bars)

1. **Health**: 生命值（super.setBars）
2. **Power**: 电力状态（super.setBars）
3. **Mining**: 消耗进度，显示当前矿物消耗进度
4. **Craft Progress**: 制作进度，格式 `已完成/需要`；未选择产物或**目标矿物成本无效（本图无矿点）时显示等待提示**（除零保护）

## 配置

- `ItemSelection.buildTable()`: 选择输出物品（6x6 网格）

## 序列化

- 版本: 2
- 保存字段: mineValue, craftValue, consumeProgress, warmup, craft(物品ID), consume(物品ID)
- 注意: lastChange 字段未序列化；世界成本在加载时由服务端重算并通过网络同步（客户端标记 `costsSynced`），单机或客户端尚未收到服务端数据时本地兜底重算

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
| a0.8.5.0 | 修复 super.updateTile() 缺失、添加 super.setBars() 恢复生命值/电力条、改进条格式为带标签的中文显示、修复 lastChange 类型(float→int)、修复 world cost 除零保护 |
| a0.8.6.0 | 修复多人游戏房主与其他玩家显示/行为不一致：世界成本改为服务端统一计算并广播同步，客户端不再各自本地计算；修复进入新地图残留上一地图选择（WorldLoadEvent 清除成本/选择列表/`lastConfig`，服务端重算并广播）；客户端加入时主动请求成本、服务端补发；进度条除零保护；目标矿物无矿点时停止产出；不再消耗本图无矿点的矿物 |
