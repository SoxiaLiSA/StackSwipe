# AppSwitcherOverlay：物理与数学模型

iOS 风格应用切换器在 Jetpack Compose 中的数学建模与物理仿真全解析。

## 效果演示

<p align="center">
  <img src="docs/charts/demo_01.gif" width="200" alt="左边界橡皮筋效果"/>
  <img src="docs/charts/demo_02.gif" width="200" alt="右边界橡皮筋效果"/>
  <img src="docs/charts/demo_03.gif" width="200" alt="卡片切换效果"/>
</p>

<p align="center">
  <em>左边界过拉（扇形展开） | 右边界过拉（拉伸+Z轴下沉） | 卡片切换动画</em>
</p>

---

## 1. 状态空间

整个布局由单一连续标量 **scrollPos ∈ ℝ** 驱动。每张卡片的相对位置 `relPos(i) = i - clamp(scrollPos, 0, N-1)` 决定了其全部视觉属性：水平坐标、深度缩放、Z 轴层级、标题透明度、标题模糊度、暗色遮罩。

---

## 2. 非对称卡片定位

### 2.1 左侧：收敛几何级数

左侧卡片使用几何级数堆叠，级数收敛于有限极限。累计偏移量（深度 `d = -relPos`）：

```
offset(d) = basePeek × (1 - decay^d) / (1 - decay)
```

其中 `basePeek = cardWidth × 0.22`，`decay = 0.28`。

收敛极限 `offset(∞) = basePeek / (1 - decay) ≈ 0.306 × cardWidth`，即无论堆叠多少张卡片，左侧永远不会超出约 30.6% 的卡片宽度。实际上仅 ~2 张左卡肉眼可区分。

![几何级数收敛曲线](docs/charts/01_geometric_series.png)

### 2.2 右侧：幂律视差

右侧使用非线性幂函数 `x(i) = centerX + relPos^1.2 × rightSpacing`（`rightSpacing = cardWidth × 0.85`）。

`relPos^1.2` 在 `relPos=1`（静止位）处值不变（`1^1.2 = 1`），但导数为 1.2 — 右卡移动速度比线性快 **20%**。超过 `relPos=1` 后加速更快，产生 iOS 特有的视差感。

---

## 3. 深度缩放

![非对称缩放函数](docs/charts/02_depth_scale.png)

- 右侧线性爬升：`scale = min(0.98 + 0.02 × relPos, 1.0)`
- 左侧指数衰减：`scale = 0.96 + 0.02 × 0.50^(-relPos)`，半衰期 = 1 张卡
- 全量程仅 **2% 变化**（0.96–0.98→1.00），匹配 iOS 几乎不可察觉的尺寸差异

---

## 4. 标题可见性：梯形透明度 + 高斯模糊

```
titleAlpha = clamp(min(1 + relPos, 2 - relPos), 0, 1)
blurRadius = min(1 - titleAlpha, 0.5) × 20dp
```

![标题透明度与模糊](docs/charts/03_title_alpha_blur.png)

标题在 `relPos ∈ [0, 1]`（主卡和静止位右卡）完全清晰。向两侧滑出时同步淡出 + 高斯模糊（上限 10dp）。

暗色深度遮罩仅作用于左卡：`overlayAlpha = clamp(-relPos × 0.25, 0, 0.50)`。

---

## 5. 拖拽物理与渐进式摩擦

像素→索引转换：`delta_index = -delta_pixel / (cardWidth × 0.60)`。

### 5.1 渐进式摩擦模型（新）

旧方案使用固定的双层摩擦（基础 0.70 × 边缘 0.30 = 0.21），导致橡皮筋几乎拉不动。

新方案采用渐进式阻力：
```kotlin
val edgeFriction = 0.6f / (1f + overscrollAmount * 0.5f)
val totalFriction = baseFriction * edgeFriction  // 0.70 × edgeFriction
```

![渐进式摩擦](docs/charts/09_progressive_friction.png)

- 初始摩擦 0.42（旧方案的 2 倍），响应灵敏
- 越拉阻力越大，符合 iOS 物理直觉
- 摩擦系数从 0.42 渐减至 ~0.14

---

## 6. 弹性过拉：平滑阻尼与差异化位移

### 6.1 平滑阻尼函数（新）

旧方案使用 `sqrt(x)` 会导致动画末端跳动（x→0 时导数趋于无穷大）。

新方案采用平滑阻尼：
```kotlin
val dampedOverscroll = absOver / (1f + absOver * 0.8f)
```

![平滑阻尼函数](docs/charts/07_damped_overscroll.png)

- x=0 处导数=1（初始响应灵敏）
- 渐近线 y=1.25（有限极限）
- 全程连续可导，无跳动

### 6.2 差异化位移权重（更新）

过拉时各卡片位移不同，产生扇形展开/拉伸效果：

```kotlin
x_final(i) = x_base(i) - sign(overscroll) × dampedOverscroll × rightSpacing × weight(i)
```

![弹性过拉权重](docs/charts/04_overscroll_weights.png)

**左边界过拉（扇形展开）：**
```kotlin
weight = 0.72 + 0.55 × d/(d+0.6)
```
- 边缘卡 (d=0): weight=0.72，移动较少
- 远端卡 (d=1): weight=1.06，移动更多
- 效果：卡片组"扇形展开"

**右边界过拉（拉伸效果）：**
```kotlin
weight = 0.65 / (d+1)
```
- 最右卡 (d=0): weight=0.65，移动最多
- 左侧卡 (d=1): weight=0.33，移动较少
- 效果：最右卡被"拉出"，其他卡原地

### 6.3 Z 轴下沉效果（新）

右边界过拉时，所有可见卡片产生 Z 轴下沉（scale 缩小），最右卡缩小少，左侧卡缩小更多：

```kotlin
val sinkWeight = 0.3f + 0.7f * (d / (d + 0.8f))
val scale = 1f - dampedOverscroll * 0.15f * sinkWeight
```

![Z轴下沉效果](docs/charts/08_z_sink_scale.png)

- 最右卡 (d=0): sinkWeight=0.30，缩小到 ~0.97
- 左侧卡 (d=2): sinkWeight=0.80，缩小到 ~0.87
- 产生"最右卡被拉出，后面卡片被压下去"的层次感

---

## 7. 惯性滚动

手指释放后，速度投影确定目标：`target = round(scrollPos + velocity × 0.25)`。仅 25% 动能转为位移，防止过冲。

弹簧动画 `Spring(dampingRatio = 1.0, stiffness = 80)` 驱动减速：

![弹簧阻尼模式对比](docs/charts/05_spring_damping.png)

临界阻尼（ζ = 1.0）= 最快收敛且无振荡。低刚度 80（vs 默认 1500）使减速过程缓慢飘停，匹配 iOS 质感。

---

## 8. 卡片 ↔ 全屏过渡

两种动画（打开 Shrink-in / 关闭 Expand-out）共享统一进度 `p ∈ [0, 1]`，线性插值宽度、高度、位置、圆角（30dp → 0dp）。

![贝塞尔缓动曲线](docs/charts/06_bezier_easing.png)

`CubicBezierEasing(0.17, 0.84, 0.44, 1.0)` — 温和启动、快速达峰、长尾减速、无过冲。动画时长 400ms。

---

## 9. Z 轴层级 & 性能优化

**Z 轴**：卡片按索引递增（右侧始终在上），过渡遮罩在最上层，手势层 `z = Float.MAX_VALUE`。

**性能优化**：
- 离屏裁剪：20 张卡片中通常仅 3–5 张需要渲染
- `derivedStateOf`：拖拽时忽略动画状态变化，反之亦然
- 形状常量提升：`RoundedCornerShape(30.dp)` 提升为顶层 `val`
- `key(stableKey)` 组合键：高效复用和重排 Compose 节点

---

## 参数总览

| 参数 | 值 | 物理含义 |
|------|------|------|
| 卡片宽度 | 屏幕宽 × 66% | 主卡尺寸 |
| 卡片圆角 | 30 dp | iOS 风格圆角 |
| 左侧 basePeek | cardWidth × 22% | 第一张左卡可见条宽 |
| 左侧 decay | 0.28 | 几何级数比率（~2 张可见） |
| 右侧间距 | cardWidth × 85% | 右卡基础距离 |
| 右侧视差指数 | 1.2 | 右卡比主卡快 20% |
| 主卡缩放 | 0.98 | 主卡略小于右卡 |
| 右卡缩放 | 1.00 | 全尺寸 |
| 左卡最小缩放 | 0.96 | 最深左卡的缩放极限 |
| 左卡缩放衰减 | 0.50 | 半衰期 = 1 张卡 |
| 暗色遮罩 | 0.25/relPos，上限 0.50 | 左卡阴影深度 |
| 拖拽灵敏度 | cardWidth × 60% | 拖多远 = 滑 1 张卡 |
| 基础摩擦 | 0.70 | 手指利用率 70% |
| 边缘摩擦（新） | 0.6/(1+0.5×overscroll) | 渐进式阻力 |
| 平滑阻尼系数 | 0.8 | dampedOverscroll 衰减率 |
| 惯性投影 | 0.25 | 速度→距离转化率 |
| 弹簧阻尼比 | 1.0 | 临界阻尼（无振荡） |
| 弹簧刚度 | 80 | 缓慢飘停 |
| 动画时长 | 400 ms | Shrink / Expand |
| 贝塞尔曲线 | (0.17, 0.84, 0.44, 1.0) | iOS 风格缓动 |
| 标题模糊上限 | 10 dp | 最大高斯模糊半径 |
| 背景不透明度 | 0.92 | 切换器遮罩亮度 |
| 左过拉权重（新） | 0.72 + 0.55×d/(d+0.6) | 扇形展开 |
| 右过拉权重（新） | 0.65/(d+1) | 拉伸效果 |
| Z轴下沉权重（新） | 0.3 + 0.7×d/(d+0.8) | 右过拉深度感 |
| Z轴下沉系数（新） | 0.15 | 最大缩小幅度 |
