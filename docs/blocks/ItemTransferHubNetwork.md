# ItemTransferHubNetwork

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `ItemTransferHubNetwork` |
| 父类 | 无（独立工具类） |
| 分类 | - |
| 尺寸 | - |
| 血量 | - |

## 机制说明

### 核心机制

物品传输中枢网络管理器，负责管理多个hub之间的网络连接、拓扑重建和供需计算。

### 网络管理

1. **网络合并**: `merge()` 将两个网络合并，采用"大网络吸收小网络"策略
2. **网络重建**: `remove()` 移除hub后，使用DFS重建连通分量
3. **版本控制**: `version` 字段在拓扑变更时递增，用于缓存失效

### HubData 内部类

存储每个hub的本地数据：

| 字段 | 类型 | 说明 |
|------|------|------|
| `buildings` | Seq<Building> | 相邻的非hub建筑 |
| `hubs` | Seq<ItemTransferHubBuild> | 相邻的hub建筑 |
| `needs` | int[] | 每种物品的需求量 |
| `costs` | int[] | 每种物品的供给量 |

### 需求/供给计算 (update)

遍历所有linked buildings计算needs和costs：

1. **ItemTurret**: needs = 弹药容量 - 当前存储
2. **GenericCrafter**: 
   - needs = 每种消耗物品的(容量 - 当前)
   - costs = 输出物品满时的数量
3. **其他建筑**: 通用计算，检查acceptItem和容量
4. **CoreBlock**: 跳过（核心不参与 needs/costs 计算）

### 网络级开关

| 开关 | 默认值 | 说明 |
|------|--------|------|
| `enableDemandPull` | true | 按需拉取模式 |
| `enableSurplusPush` | true | 满产推送模式 |

### 网络合并策略

两个hub合并网络时，保留较大网络的对象（包括其 `enableDemandPull` 和 `enableSurplusPush` 设置），较小网络的 hub 被转移到较大网络中。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.1 | 初始创建 |
| a0.8.2.0 | HubData.update()通用化，添加网络开关 |
| a0.8.5.0 | 删除未使用的 Path/cache 死代码、修正合并策略文档 |
