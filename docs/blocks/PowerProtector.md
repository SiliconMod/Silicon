# PowerProtector

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `PowerProtector` |
| 父类 | `PowerGenerator` |
| 分类 | Category.power |
| 尺寸 | 2x2 |
| 血量 | 600 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 150 |
| Lead | 100 |
| Graphite | 80 |
| Silicon | 70 |
| Thorium | 50 |
| Plastanium | 40 |
| Phase Fabric | 20 |

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

电力保护器，用于在电网崩溃时保护电力系统。具有三状态机：

1. **Normal (status=0)**: 监控电网状态，当存储电力降至接近0且电力变化为负时进入保护模式
2. **Protection (status=1)**: 电网崩溃时激活，记录所有"已消耗"电力（电力赤字）。5分钟后或连续增长30秒后退出
3. **Recovery (status=-1)**: 使用"等额本金"还款方式逐步偿还消耗的电力，利率为0.1%/秒

### 特殊行为

- **Error检测**: 同一电网中存在相同类型的PowerProtector时，error=true，停止工作
- **PowerVoid检测**: 电网中存在PowerVoid时，直接返回不工作
- **拆除限制**: 仅在Normal状态下可拆除
- **放置限制**: 电网中已有PowerProtector时不允许放置
- **禁用行为**: 在保护/恢复模式下被禁用时，仅重置状态为Normal（不自动恢复enabled）

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `protectionTime` | float | 5 * 60 * 60f | 保护模式持续时间 |
| `exitGrowthTime` | float | 30 * 60f | 退出保护的增长时间 |
| `secondRecoveryRate` | float | 0.001f | 每秒恢复率 |
| `warmupSpeed` | float | 0.1f | 预热速度 |

## 电力系统

- **消耗方式**: `consumePowerDynamic` 动态消耗（恢复阶段消耗tickRPower）
- **产出方式**: `getPowerProduction` 返回 `tickPPower`（保护阶段产出）
- **公式**: 恢复阶段使用等额本金还款，利息=0.1%/秒

## 物品处理

- 无物品处理

## 状态栏 (Bars)

1. **Power Output**: 显示电力产出状态
2. **Spent Power**: 显示已消耗电力（累积赤字）
3. **Protection Status**: 显示当前状态（Normal/Protection/Recovery/Error）

## 序列化

- 版本: 8
- 保存字段: 状态机所有变量

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
| a0.8.5.0 | 修复禁用时自动恢复enabled的行为、日志改用SiliconLog |
