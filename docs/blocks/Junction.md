# Junction

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `Junction` |
| 父类 | `LiquidJunction` |
| 分类 | Category.liquid |
| 尺寸 | 1x1 |
| 血量 | 默认 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Graphite | 2 |
| Metaglass | 4 |
| Copper | 1 |

## Block 属性

- `hasItems`: false（使用 DirectionalItemBuffer，非标准物品容器）
- `hasPower`: false
- `consumesPower`: false
- `outputsPower`: false
- `conductivePower`: false
- `update`: true
- `solid`: false
- `underBullets`: true
- `configurable`: false
- `unloadable`: false
- `floating`: true
- `noUpdateDisabled`: true
- `alwaysUnlocked`: true

## 机制说明

### 核心机制

四向物品交叉路口。物品从一侧进入，从对侧离开，不会发生碰撞。

### 特殊行为

1. **方向缓冲**: 使用 `DirectionalItemBuffer`，为4个方向各维护独立缓冲区
2. **定时传输**: 物品在缓冲区中等待 `speed` tick后传输到对侧
3. **方向记录**: `handleItem()` 记录来源方向，用于确定目标方向
4. **圆形缓冲**: 使用循环缓冲区避免数组复制

### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `speed` | int | 26 | 物品穿越所需的tick数 |
| `capacity` | int | 6 | 每个方向最大缓冲物品数 |

## 电力系统

- 无电力系统

## 物品处理

- **输入**: 从任意方向接受物品
- **输出**: 传送到对侧方向
- **缓冲**: 每方向最多6个物品
- **延迟**: 26 tick穿越时间

## 序列化

- 版本: 1
- 保存字段: DirectionalItemBuffer

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
