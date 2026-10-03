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
- `configurable`: true（支持按钮式切换配置界面）
- `rotate`: true
- `alwaysUnlocked`: true
- `group`: `BlockGroup.logic`（逻辑组，电力节点式批量交互）

## 机制说明

### 核心机制

可旋转的开关，控制前方建筑的启用/禁用状态。

### 特殊行为

1. **状态记录**: `placeEnded()` 记录前方建筑的初始启用状态到 `fE`
2. **单次更新**: 点击/配置界面切换 `fE` 后，对前方建筑做**一次** `enabled = fE` 状态更新，不再每 tick 持续覆盖——逻辑处理器等外部对前方建筑的修改不会被覆盖
3. **状态同步**: 每 tick 读取前方建筑实际 `enabled` 并同步到 `fE`（前方被外部修改时开关外观跟随）
4. **切换逻辑**: 点击时切换 `fE`，除非前方是另一个Switch
5. **选择绘制**: `drawSelect()` 用绿色/红色框显示前方建筑状态
6. **按钮式配置界面**: `buildConfiguration()` 显示切换式按钮（80×40），按一次切换状态（toggle样式，`.checked(fE)` 联动高亮）

### 配置参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `fE` | boolean | 前方建筑的启用状态 |

## 电力系统

- 无电力系统

## 物品处理

- 无物品处理

## 配置

- `config(Boolean.class)`: 设置前方建筑的启用状态（单次更新）
- `config()`: 返回当前状态

## 序列化

- 保存/读取 `fE` 字段

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
| a0.10.1 | `group` `projectors` → `logic`，归入逻辑组，电力节点式批量操作 |
| a0.12.1.0 | 新增按钮式切换配置界面（`configurable=true`，80×40 toggle 按钮） |
| （待定版） | 开关改为单次状态更新，不再持续覆盖外部逻辑（如逻辑处理器）；每 tick 同步前方建筑实际状态（#43） |