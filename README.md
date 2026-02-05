<p align="center">
  <img src="docs/charts/demo_03.gif" width="280" alt="StackSwipe Demo"/>
</p>

<h1 align="center">StackSwipe</h1>

<p align="center">
  <strong>Pixel-Perfect iOS App Switcher for Jetpack Compose</strong>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#demo">Demo</a> •
  <a href="#installation">Installation</a> •
  <a href="#usage">Usage</a> •
  <a href="#physics">Physics</a> •
  <a href="#license">License</a> •
  <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/API-21%2B-brightgreen.svg" alt="API"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9+-purple.svg" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202024-blue.svg" alt="Compose"/>
  <img src="https://img.shields.io/badge/License-MIT-orange.svg" alt="License"/>
</p>

---

## Why StackSwipe?

Most Android app switcher implementations feel "off" — laggy, robotic, or just plain wrong. **StackSwipe** recreates the buttery-smooth iOS App Switcher experience with mathematically precise physics:

- **Geometric Series Stacking** — Cards stack with natural depth convergence
- **Power-Law Parallax** — Right cards move 20% faster for that signature iOS feel
- **Rubber Band Overscroll** — Progressive resistance with differential card movement
- **Z-Axis Depth Sink** — Cards compress in 3D space when pulling past edges
- **Spring-Based Momentum** — Critically damped springs for zero-bounce settling

**No dependencies. Pure Compose. ~600 lines of code.**

---

## Demo

<p align="center">
  <img src="docs/charts/demo_02.gif" width="220" alt="Left edge rubber band"/>
  <img src="docs/charts/demo_01.gif" width="220" alt="Right edge with Z-sink"/>
  <img src="docs/charts/demo_03.gif" width="220" alt="Card switching"/>
</p>

<p align="center">
  <em>Left Edge Overscroll (Fan Out) • Right Edge Overscroll (Z-Sink) • Card Transitions</em>
</p>

---

## Features

| Feature | Description |
|---------|-------------|
| **iOS-Accurate Layout** | Asymmetric card positioning with geometric series on left, parallax on right |
| **Rubber Band Physics** | Smooth overscroll with per-card differential movement |
| **Z-Axis Depth** | Cards sink into the screen when overscrolling right edge |
| **Title Blur** | Gaussian blur on titles as cards move to stack |
| **Dark Overlay** | Depth shadows on stacked cards |
| **Spring Animations** | Critically damped springs for natural momentum |
| **Card ↔ Fullscreen** | Seamless expand/shrink transitions with bezier easing |
| **Screenshot Capture** | Built-in GraphicsLayer-based screenshot store |
| **Performance Optimized** | Off-screen culling, derived state, shape hoisting |

---

## Installation

Add the source files to your project:

```
app/src/main/java/your/package/appswitcher/
├── Models.kt              # NavRoute, BackStackEntry, AppSwitcherState
├── ScreenshotStore.kt     # Screenshot capture & storage
├── AppSwitcherCard.kt     # Individual card component
├── AppSwitcherOverlay.kt  # Main switcher overlay (~600 LOC)
└── AppSwitcherDemoScreen.kt  # Demo implementation
```

---

## Usage

### Basic Setup

```kotlin
@Composable
fun MyAppSwitcher() {
    val entries = remember {
        listOf(
            BackStackEntry(0, NavRoute.Page("Home")),
            BackStackEntry(1, NavRoute.Page("Settings")),
            BackStackEntry(2, NavRoute.Page("Profile"))
        )
    }

    val screenshotStore = remember { ScreenshotStore() }
    var selectedIndex by remember { mutableIntStateOf(2) }
    var isVisible by remember { mutableStateOf(true) }

    AppSwitcherOverlay(
        backStack = entries,
        screenshotStore = screenshotStore,
        state = AppSwitcherState(isVisible = true, selectedIndex = selectedIndex),
        onCardClick = { index -> selectedIndex = index },
        onSelectedIndexChange = { index -> selectedIndex = index },
        onDismiss = { isVisible = false }
    )
}
```

### With Real Screenshots

```kotlin
// Wrap your pages with ScreenshotCapture
ScreenshotCapture(
    key = entry.screenshotKey,
    screenshotStore = screenshotStore
) {
    YourPageContent()
}

// Capture before showing switcher
LaunchedEffect(showSwitcher) {
    if (showSwitcher) {
        screenshotStore.captureAll()
    }
}
```

---

## Physics

> *"The difference between good and great UI is in the math."*

### Card Positioning

**Left Side — Geometric Series:**
```
offset(d) = basePeek × (1 - decay^d) / (1 - decay)
```
Cards converge to a finite limit (~30% card width). Only 2-3 cards are visually distinguishable.

**Right Side — Power Law Parallax:**
```
x(i) = centerX + relPos^1.2 × rightSpacing
```
The 1.2 exponent makes right cards move 20% faster than linear, creating iOS's signature depth feel.

![Card Positioning](docs/charts/01_geometric_series.png)

### Rubber Band Overscroll

**Smooth Damping (no jumps):**
```kotlin
dampedOverscroll = |overscroll| / (1 + |overscroll| × 0.8)
```

**Differential Card Movement:**
- Left edge: `weight = 0.72 + 0.55 × d/(d+0.6)` — fan out effect
- Right edge: `weight = 0.65 / (d+1)` — rightmost card moves most

![Overscroll Weights](docs/charts/04_overscroll_weights.png)

### Z-Axis Depth Sink

When overscrolling past the right edge, cards shrink with depth:

```kotlin
sinkWeight = 0.3 + 0.7 × d/(d+0.8)
scale = 1 - dampedOverscroll × 0.15 × sinkWeight
```

![Z-Axis Sink](docs/charts/08_z_sink_scale.png)

### Spring Animation

```kotlin
spring(
    dampingRatio = 1.0,    // Critical damping = no bounce
    stiffness = 80f        // Low stiffness = slow, floaty settle
)
```

![Spring Damping](docs/charts/05_spring_damping.png)

---

## Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Card Width | 66% screen | Main card size |
| Card Corner | 30dp | iOS-style radius |
| Left Peek | 22% card | Visible strip of stacked cards |
| Left Decay | 0.28 | Geometric series ratio |
| Right Spacing | 85% card | Gap between right cards |
| Parallax Exponent | 1.2 | Right card speed boost |
| Base Friction | 0.70 | Normal drag damping |
| Edge Friction | 0.6/(1+0.5x) | Progressive overscroll resistance |
| Spring Damping | 1.0 | Critical (no bounce) |
| Spring Stiffness | 80 | Slow, floaty settle |
| Animation Duration | 400ms | Card ↔ fullscreen |
| Bezier Easing | (0.17, 0.84, 0.44, 1.0) | iOS-style curve |

---

## Performance

- **Off-screen culling**: Only 3-5 cards rendered regardless of backstack size
- **`derivedStateOf`**: Drag vs animation state isolation
- **Shape hoisting**: `RoundedCornerShape` created once
- **Stable keys**: Efficient Compose node reuse

---

## License

MIT License. Use it, modify it, ship it.

---

<p align="center">
  <strong>Built with math, physics, and way too much attention to detail.</strong>
</p>

<p align="center">
  <sub>If iOS can do it, Compose can do it better.</sub>
</p>
