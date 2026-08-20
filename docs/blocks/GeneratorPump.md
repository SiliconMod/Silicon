# GeneratorPump

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `GeneratorPump` |
| 父类 | `LiquidBlock` |
| 分类 | Category.power |
| 尺寸 | 3x3 |
| 血量 | 默认 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 60 |
| Lead | 30 |
| Metaglass | 15 |
| Graphite | 40 |
| Titanium | 45 |
| Thorium | 6 |
| Silicon | 40 |

## Block 属性

- `hasItems`: false
- `hasPower`: true
- `consumesPower`: true
- `outputsPower`: true
- `conductivePower`: true
- `hasLiquids`: true
- `update`: true
- `solid`: true
- `configurable`: false
- `alwaysUnlocked`: true

## 机制说明

### 核心机制

GeneratorPump 是一个结合液体泵送和电力生产的复合方块。从环境中泵取液体并产生电力。

### 特殊行为

1. **液体泵送**: 扫描相邻地块，泵取指定液体（默认water）
2. **动态电力**: 产出功率根据泵送状态动态调整
3. **可选消耗**: 支持物品/液体过滤器和增压液体
4. **环境检测**: `onProximityUpdate()` 扫描链接地块的液体类型

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `powerProduction` | float | 345/60 | 每tick产出功率 |
| `powerConsumption` | float | 43/60 | 每tick消耗功率 |
| `pumpAmount` | float | 0.22 | 每tick每格泵送量 |
| `canPumpLiquids` | Seq | [water] | 可泵送液体列表 |
| `consumeTime` | float | 300 | 消耗物品间隔(tick) |
| `warmupSpeed` | float | 0.05 | 预热速度 |
| `generateEffect` | Effect | - | 生成特效 |
| `generateEffectRange` | float | 3 | 特效范围 |

## 电力系统

- **消耗方式**: `consPower(0)` 无电网消耗，`powerConsumption` 从产出公式中扣除
- **产出方式**: `getPowerProduction` 动态产出
- **公式**: `(lerp(0, powerProduction, optionalEfficiency) - powerConsumption * (泵送中)) * productionEfficiency`

## 物品处理

- `hasItems`: false
- 无物品存储

## 液体处理

- **输入**: 从环境泵取液体
- **输出**: 泵送的液体存入内部储液槽
- **容量**: 90f
- **压力**: 1f

## 序列化

- 版本: 1
- 保存字段: warmup, efficiencyMultiplier, productionEfficiency, consTimer, totalProgress, liquidDrop, amount

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
