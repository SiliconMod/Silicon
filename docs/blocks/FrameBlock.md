# FrameBlock

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `FrameBlock` |
| 父类 | `Block` |
| 分类 | 无（基类） |
| 尺寸 | 可配置 |
| 血量 | 默认 |

## Block 属性

- `hasItems`: false
- `hasPower`: false
- `update`: false
- `solid`: false

## 机制说明

### 核心机制

FrameBlock 是一个动画帧基类，为其他方块提供帧动画叠加层功能。

### 特殊行为

1. **帧加载**: 从 `{name}-0`, `{name}-1`, ... 加载序列帧纹理
2. **帧绘制**: 在方块正常绘制之上叠加动画帧
3. **帧速度**: 通过 `frameTime` 控制播放速度

### 配置参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `frame` | int | 动画帧数 |
| `frameTime` | int | 帧播放速度 |
| `frames` | TextureRegion[] | 加载的帧纹理数组 |

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.8.0 | 初始创建 |
