# PowerSource

## 基本信息
| 属性 | 值 |
|------|----|
| 类名 | `silicon.world.blocks.sandbox.PowerSource` |
| 父类 | `mindustry.world.blocks.sandbox.PowerSource` |
| 分类 | Category.power |
| 尺寸 | 1x1 |
| 血量 | 600 |
| 可见性 | BuildVisibility.sandboxOnly |

## 合成配方
| 材料 | 数量 |
|------|------|
| （沙盒模式，无配方） | — |

## Block 属性
- `hasItems`: false
- `hasPower`: true
- `consumesPower`: false
- `outputsPower`: true
- `conductivePower`: false
- `update`: true
- `solid`: true
- `configurable`: false
- `alwaysUnlocked`: true

## 机制说明

### 核心机制
无限电源，但产量会根据电网状态动态调整。

### 特殊行为
1. **PowerVoid 检测**：如果同一电网中存在 `PowerVoid`，本方块产量归 0
2. **多源共享**：同一电网中有 N 个 PowerSource 时，每个的产量 = `powerProduction / N / 60`
3. **动态计算**：每帧调用 `getPowerProduction()`，实时响应电网变化

## 电力系统
- 产出方式：`getPowerProduction()` 动态计算
- 产量：`Float.MAX_VALUE / 2`（配置值），实际被 N 个电源平分
- 公式：`powerProduction / 同电网PowerSource数量 / 60`

## 物品处理
- 无物品处理能力

## 序列化
- 继承父类序列化，无自定义字段

## 版本历史
| 版本 | 变更 |
|------|------|
| a0.8.x | 初始创建 |
