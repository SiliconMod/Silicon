# Switch

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `Switch` |
| 父类 | `Block` |
| 分类 | Category.effect |
| 尺寸 | 1x1 |
| 血量 | 默认 |

## 合成配方

| 材料 | 数量 |
|------|------|
| Graphite | 100 |
| Silicon | 100 |
| Thorium | 100 |
| Plastanium | 100 |

## Block 属性

- `hasItems`: false
- `hasPower`: false
- `consumesPower`: false
- `outputsPower`: false
- `conductivePower`: false
- `update`: true
- `solid`: true
- `configurable`: false（使用 tapped() 切换，非配置模式）
- `rotate`: true
- `alwaysUnlocked`: true
- `group`: `BlockGroup.logic`（逻辑组，电力节点式批量交互）

## 机制说明

### 核心机制

可旋转的开关，控制前方建筑的启用/禁用状态。

### 特殊行为

1. **状态记录**: `placeEnded()` 记录前方建筑的初始启用状态到 `fE`
2. **状态强制**: 每tick强制设置 `front().enabled = fE`
3. **切换逻辑**: 点击时切换 `fE`，除非前方是另一个Switch
4. **选择绘制**: `drawSelect()` 用绿色/红色框显示前方建筑状态

### 配置参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `fE` | boolean | 前方建筑的启用状态 |

## 电力系统

- 无电力系统

## 物品处理

- 无物品处理

## 配置

- `config(Boolean.class)`: 切换前方建筑的启用状态
- `config()`: 返回当前状态

## 序列化

- 保存/读取 `fE` 字段

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
| a0.10.1 | `group` `projectors` → `logic`，归入逻辑组，电力节点式批量操作 |