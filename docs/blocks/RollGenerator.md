# RollGenerator

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `RollGenerator` |
| 父类 | `PowerGenerator` |
| 分类 | Category.power |
| 尺寸 | 1x1 |
| 血量 | 800 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 40 |
| Lead | 24 |
| Graphite | 20 |
| Silicon | 16 |
| Thorium | 16 |
| Plastanium | 10 |

## Block 属性

- `hasItems`: false
- `hasPower`: true
- `consumesPower`: true
- `outputsPower`: true
- `conductivePower`: false
- `update`: true
- `solid`: true
- `configurable`: false
- `alwaysUnlocked`: true

## 机制说明

### 核心机制

复合利息发电机，根据电网现有存储电力的百分比动态产出电力。

### 特殊行为

1. **动态产出**: 每tick产出 `stored * 1%/60 + changed * 5%/60`
2. **自适应上限**: `maxPowerGeneration` 根据电网状态自动调整
   - 产出不足时缓慢上升
   - 产出过剩时缓慢下降
3. **PowerVoid检测**: 电网中有PowerVoid时产出为0
4. **负值处理**: 产出为负时转为消耗模式

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `powerStoredProductionPercentage` | float | 0.001 | 存储电力产出比例 |
| `powerChangedProductionPercentage` | float | 0.005 | 变化率产出比例 |
| `warmupSpeed` | float | 0.1 | 预热速度 |

## 电力系统

- **消耗方式**: `consumePowerDynamic` 动态消耗（当产出为负时）
- **产出方式**: `getPowerProduction` 动态产出
- **公式**: `roll = stored * 1%/60 + changed * 5%/60`
- **上限**: `min(roll, maxPowerGeneration)`

## 物品处理

- 无物品处理

## 状态栏 (Bars)

- 无自定义状态栏

## 传感器访问

- `powerNetStored`: 电网存储量
- `powerNetCapacity`: 电网容量

## 序列化

- 版本: 7
- 保存字段: currentPowerProduction, maxPowerGeneration, lastCurrentPowerProduction

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
