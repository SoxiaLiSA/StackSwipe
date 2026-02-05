package ceui.lisa.stackswipe.appswitcher

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

// iOS-style easing: gentle acceleration, long smooth deceleration.
// Approximates UIKit's default spring-like curve without overshoot.
private val iOSEasing = CubicBezierEasing(0.17f, 0.84f, 0.44f, 1.0f)

// Hoisted shape to avoid per-frame allocation
private val CardShape = RoundedCornerShape(30.dp)

/** Shared depth-scale calculation for a card at the given relative position. */
private fun depthScale(relPos: Float): Float {
    return if (relPos >= 0f) {
        // iOS: right cards are subtly larger than focused card
        // Focused (relPos=0) = 0.98, right cards ramp to 1.0
        val focusedScale = 0.98f
        (focusedScale + (1f - focusedScale) * relPos).coerceAtMost(1f)
    } else {
        // Left cards scale down very subtly (iOS barely scales them)
        val minScale = 0.96f
        val focusedScale = 0.98f
        val decay = 0.50f
        minScale + (focusedScale - minScale) * decay.pow(-relPos)
    }
}

@Composable
fun AppSwitcherOverlay(
    backStack: List<BackStackEntry>,
    screenshotStore: ScreenshotStore,
    state: AppSwitcherState,
    onCardClick: (Int) -> Unit,
    onSelectedIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Continuous scroll position in card-index space (e.g. 3.0 = card 3 centered).
    // Using a continuous float avoids the discrete selectedIndex snap that caused
    // surrounding cards to jump when their left/right spacing recalculated.
    var isDragging by remember { mutableStateOf(false) }
    var dragScrollPos by remember { mutableFloatStateOf(0f) }
    val animScrollPos = remember { Animatable(0f) }

    var flingJob by remember { mutableStateOf<Job?>(null) }

    // Card-to-fullscreen expansion animation state
    var expandingIndex by remember { mutableStateOf<Int?>(null) }
    val expandProgress = remember { Animatable(0f) }

    // Fullscreen-to-card shrink animation state (reverse of expand, plays on open)
    var isShrinking by remember { mutableStateOf(false) }
    val shrinkProgress = remember { Animatable(0f) }

    // Track whether the overlay has been initialized by LaunchedEffect.
    // Prevents a one-frame flash of cards before the shrink animation starts.
    var overlayReady by remember { mutableStateOf(false) }

    // derivedStateOf: only recomposes when the *active* source changes.
    // When dragging, ignore animScrollPos changes; when animating, ignore dragScrollPos.
    val scrollPos by remember {
        derivedStateOf { if (isDragging) dragScrollPos else animScrollPos.value }
    }

    LaunchedEffect(state.isVisible) {
        if (state.isVisible) {
            overlayReady = false
            val target = state.selectedIndex.toFloat()
            dragScrollPos = target
            animScrollPos.snapTo(target)
            isDragging = false
            expandingIndex = null
            expandProgress.snapTo(0f)
            shrinkProgress.snapTo(0f)
            overlayReady = true
            // Shrink-in animation: fullscreen → card position
            isShrinking = true
            shrinkProgress.animateTo(1f, tween(400, easing = iOSEasing))
            isShrinking = false
        } else {
            overlayReady = false
        }
    }

    // Animated dismiss: scroll back to the page that opened the switcher, then expand it.
    fun animatedDismiss() {
        if (isShrinking || expandingIndex != null) return
        val targetIndex = (backStack.size - 1).coerceAtLeast(0)
        flingJob?.cancel()
        flingJob = null
        coroutineScope.launch {
            // Scroll back to the original page if we're not already there
            val currentPos = if (isDragging) dragScrollPos else animScrollPos.value
            if (currentPos.roundToInt() != targetIndex) {
                isDragging = false
                animScrollPos.snapTo(currentPos)
                animScrollPos.animateTo(
                    targetValue = targetIndex.toFloat(),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            // Now expand the target card to fullscreen
            expandingIndex = targetIndex
            expandProgress.snapTo(0f)
            expandProgress.animateTo(1f, tween(400, easing = iOSEasing))
            onDismiss()
        }
    }

    BackHandler(enabled = state.isVisible) {
        animatedDismiss()
    }

    if (!state.isVisible) return

    // Block rendering until LaunchedEffect has initialised scroll position and
    // started the shrink animation.  Without this guard the first composition
    // frame would show cards at arbitrary positions before snapping into place.
    // Show the selected page's screenshot at fullscreen so the transition is seamless
    // (page content is already hidden via alpha=0 in MainActivity).
    if (!overlayReady) {
        val selectedEntry = backStack.getOrNull(state.selectedIndex)
        val screenshot = selectedEntry?.let { screenshotStore.getScreenshot(it.screenshotKey) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (screenshot != null) {
                Image(
                    bitmap = screenshot,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        return
    }

    // Unified overlay progress: 0 = card position, 1 = fullscreen.
    // Shrink (open): goes 1→0.  Expand (close): goes 0→1.
    val isAnimatingOverlay = isShrinking || expandingIndex != null
    val overlayVisualProgress = when {
        isShrinking -> 1f - shrinkProgress.value
        expandingIndex != null -> expandProgress.value
        else -> 0f
    }
    // During shrink (open): keep background fully opaque so no transparent gap
    // appears as the overlay card shrinks from fullscreen.
    // During expand (close): fade background out alongside the expanding card.
    val bgAlpha = when {
        expandingIndex != null -> 0.92f * (1f - expandProgress.value)
        else -> 0.92f
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
    ) {

        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val screenAspectRatio = screenHeightPx / screenWidthPx
        val cardWidthPx = screenWidthPx * 0.66f
        val cardHeightPx = cardWidthPx * screenAspectRatio
        val cardWidthDp = with(density) { cardWidthPx.toDp() }
        val cardHeightDp = with(density) { cardHeightPx.toDp() }
        // iOS-style asymmetric spacing:
        // Left cards use geometric-series peek (each card exposes decay× less
        // than the previous), converging like an infinite series.
        // Right cards are far apart so the selected card is nearly fully visible.
        // Spacing tuned so left and right cards move at similar visual rates.
        val leftBasePeek = cardWidthPx * 0.22f   // visible strip of first left card
        val leftDecay = 0.28f                     // fast convergence: only ~2 left cards visible
        val rightSpacingPx = cardWidthPx * 0.85f
        // Drag sensitivity: how many pixels of drag = 1 card scroll.
        // Larger value = need to drag further to switch one card.
        val dragSpacingPx = cardWidthPx * 0.60f
        val titleHeightPx = with(density) { 32.dp.toPx() }
        val centerX = screenWidthPx / 2f
        val centerY = screenHeightPx / 2f

        val maxScrollIndex = (backStack.size - 1).coerceAtLeast(0).toFloat()
        // Compute once, reuse in loop and overlay
        val clampedSp = scrollPos.coerceIn(0f, maxScrollIndex)

        // Position card based on continuous scrollPos — no discrete jumps.
        // Left side uses geometric series: offset(d) = basePeek*(1-decay^d)/(1-decay)
        // so the visible strips converge like 1, 0.45, 0.2, 0.09, …
        // Right side stays uniform. Overscroll uses rightSpacingPx for rubber-band.
        fun cardCenterX(index: Int, sp: Float = scrollPos): Float {
            val cSp = sp.coerceIn(0f, maxScrollIndex)
            val overscroll = sp - cSp // <0 past left, >0 past right
            val relPos = index.toFloat() - cSp
            val baseX = if (relPos <= 0f) {
                // Geometric series: total offset converges to basePeek/(1-decay)
                val d = -relPos
                val totalOffset = leftBasePeek * (1f - leftDecay.pow(d)) / (1f - leftDecay)
                centerX - totalOffset
            } else {
                // Non-linear: right cards move faster than the focused card (iOS parallax)
                centerX + relPos.pow(1.2f) * rightSpacingPx
            }
            // Elastic overscroll: differential movement creates stretch/compress feel.
            // Each card has different weight so they fan out / compress.
            val overscrollWeight = if (overscroll < 0f) {
                // Left edge (overscroll < 0): rightmost cards move more, leftmost move less
                // Edge card (d=0) weight=0.45, next card (d=1) weight=0.79, difference visible but both move
                val d = relPos.coerceAtLeast(0f)
                0.72f + 0.55f * (d / (d + 0.6f))
            } else if (overscroll > 0f) {
                // Right edge (overscroll > 0): rightmost card moves most, left cards move less
                // d=0 (rightmost): weight=0.5, d=1: weight=0.25, d=2: weight=0.17
                val d = (-relPos).coerceAtLeast(0f)
                0.65f / (d + 1f)
            } else {
                0f
            }
            // Use smooth damping: x/(1+|x|) gives diminishing returns without discontinuity
            val absOver = abs(overscroll)
            val dampedOverscroll = absOver / (1f + absOver * 0.8f)
            return baseX - overscroll.sign * dampedOverscroll * rightSpacingPx * overscrollWeight
        }

        // Calculate Z-axis sink (scale down) for right-edge overscroll.
        // Rightmost card shrinks slightly, left cards shrink more.
        fun overscrollSinkScale(index: Int, sp: Float = scrollPos): Float {
            val cSp = sp.coerceIn(0f, maxScrollIndex)
            val overscroll = sp - cSp
            if (overscroll <= 0f) return 1f // No sink when not overscrolling right

            val relPos = index.toFloat() - cSp
            // d=0 (rightmost): minimal sink, d>0 (left cards): more sink
            val d = (-relPos).coerceAtLeast(0f)
            // Rightmost card: sinkWeight=0.3, left cards: up to 1.0
            val sinkWeight = 0.3f + 0.7f * (d / (d + 0.8f))

            // Smooth damping for the sink amount
            val dampedOverscroll = overscroll / (1f + overscroll * 0.8f)
            // Scale down: left cards can shrink up to ~0.85, rightmost ~0.97
            return 1f - dampedOverscroll * 0.15f * sinkWeight
        }

        // Fade cards out during expand, fade in during shrink
        val expandFade = if (isAnimatingOverlay) 1f - overlayVisualProgress else 1f

        // Off-screen culling boundaries (with margin for scaled cards)
        val cullLeft = -cardWidthPx
        val cullRight = screenWidthPx + cardWidthPx

        // 1) Cards — iOS style with depth scaling on left cards
        backStack.forEachIndexed { index, entry ->
            val baseX = cardCenterX(index)

            // Skip cards that are completely off-screen
            if (baseX < cullLeft || baseX > cullRight) return@forEachIndexed

            // Hide cards that are fully past the left fade-out zone
            val relPosEarly = index.toFloat() - clampedSp
            if (relPosEarly <= -3f) return@forEachIndexed
            // Smooth fade: fully visible at relPos >= -2, fades to 0 at relPos = -3
            val leftFadeAlpha = if (relPosEarly < -2f) (3f + relPosEarly) else 1f

            key(entry.screenshotKey) {
                val totalHeight = cardHeightPx + titleHeightPx

                // iOS-style depth scale: left cards shrink with exponential decay,
                // converging to minScale like a geometric series.
                val relPos = index.toFloat() - clampedSp
                val scale = depthScale(relPos)

                // iOS-style: left-side (stacked) cards hide their title,
                // focused card and right-side cards show it.
                // Smooth 0→1 transition as a card slides from left to center.
                val titleAlpha = minOf(1f + relPos, 2f - relPos).coerceIn(0f, 1f)
                // Blur text during the 0.5→1 fade-in / 1→0.5 fade-out range.
                // At titleAlpha=1 → 0dp blur; at titleAlpha≤0.5 → max 10dp blur.
                val titleBlurRadius = ((1f - titleAlpha).coerceAtMost(0.5f) * 2f * 10f).dp
                // iOS-style dark overlay for left-stacked cards (depth shadow)
                val darkOverlayAlpha = if (relPos < 0f) {
                    ((-relPos) * 0.25f).coerceAtMost(0.50f)
                } else {
                    0f
                }

                Column(
                    modifier = Modifier
                        .zIndex(index.toFloat())
                        .offset {
                            IntOffset(
                                x = (baseX - cardWidthPx / 2f).roundToInt(),
                                y = (centerY - totalHeight / 2f).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            val finalScale = scale * overscrollSinkScale(index)
                            scaleX = finalScale
                            scaleY = finalScale
                            this.alpha = leftFadeAlpha * expandFade
                        }
                ) {
                    Text(
                        text = entry.route.getTitle(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(cardWidthDp)
                            .padding(bottom = 8.dp)
                            .graphicsLayer {
                                alpha = titleAlpha
                                if (Build.VERSION.SDK_INT >= 31 && titleBlurRadius > 0.dp) {
                                    val blurPx = titleBlurRadius.toPx()
                                    renderEffect =
                                        android.graphics.RenderEffect.createBlurEffect(
                                            blurPx,
                                            blurPx,
                                            android.graphics.Shader.TileMode.DECAL
                                        ).asComposeRenderEffect()
                                }
                            }
                    )
                    Box(
                        modifier = Modifier
                            .width(cardWidthDp)
                            .height(cardHeightDp)
                    ) {
                        AppSwitcherCard(
                            screenshot = screenshotStore.getScreenshot(entry.screenshotKey),
                            onClick = { },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (darkOverlayAlpha > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CardShape)
                                    .background(Color.Black.copy(alpha = darkOverlayAlpha))
                            )
                        }
                    }
                }
            }
        }

        // 2) Animated card overlay — shared by shrink-in (open) and expand-out (close).
        //    overlayVisualProgress: 0 = card bounds, 1 = fullscreen.
        val overlayIdx = when {
            isShrinking -> state.selectedIndex
            expandingIndex != null -> expandingIndex
            else -> null
        }
        if (overlayIdx != null && overlayIdx in backStack.indices) {
            val entry = backStack[overlayIdx]
            val screenshot = screenshotStore.getScreenshot(entry.screenshotKey)

            // Start geometry: card's current position and size
            val startCenterX = cardCenterX(overlayIdx)
            val startCenterY = centerY + titleHeightPx / 2f // card center (title is above)
            val relPos = overlayIdx.toFloat() - clampedSp
            val startScale = depthScale(relPos)
            val startW = cardWidthPx * startScale
            val startH = cardHeightPx * startScale

            // End geometry: fullscreen
            val endCenterX = screenWidthPx / 2f
            val endCenterY = screenHeightPx / 2f
            val endW = screenWidthPx
            val endH = screenHeightPx

            // Interpolate using overlayVisualProgress (0=card, 1=fullscreen)
            val p = overlayVisualProgress
            val currentW = startW + (endW - startW) * p
            val currentH = startH + (endH - startH) * p
            val currentCX = startCenterX + (endCenterX - startCenterX) * p
            val currentCY = startCenterY + (endCenterY - startCenterY) * p
            val cornerRadius = with(density) { (30.dp.toPx() * (1f - p)).toDp() }

            Box(
                modifier = Modifier
                    .zIndex(backStack.size.toFloat() + 1f)
                    .offset {
                        IntOffset(
                            x = (currentCX - currentW / 2f).roundToInt(),
                            y = (currentCY - currentH / 2f).roundToInt()
                        )
                    }
                    .requiredWidth(with(density) { currentW.toDp() })
                    .requiredHeight(with(density) { currentH.toDp() })
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.Black)
            ) {
                if (screenshot != null) {
                    Image(
                        bitmap = screenshot,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 3) Transparent gesture layer (rendered last = on top, intercepts all touch)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(Float.MAX_VALUE)
                .pointerInput(backStack.size) {
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var dragDirection: Int? = null
                    val velocityTracker = VelocityTracker()

                    detectDragGestures(
                        onDragStart = {
                            if (isShrinking || expandingIndex != null) return@detectDragGestures
                            flingJob?.cancel()
                            flingJob = null
                            isDragging = true
                            totalDragX = 0f
                            totalDragY = 0f
                            dragDirection = null
                            velocityTracker.resetTracking()
                            dragScrollPos = animScrollPos.value
                        },
                        onDragEnd = {
                            when (dragDirection) {
                                0 -> {
                                    val velocityX = velocityTracker.calculateVelocity().x
                                    // Convert pixel velocity to index-space velocity
                                    val velocityInIndex = -velocityX / dragSpacingPx
                                    val projected = dragScrollPos + velocityInIndex * 0.25f
                                    val targetIndex = projected.roundToInt()
                                        .coerceIn(0, backStack.size - 1)

                                    val maxIndex = (backStack.size - 1).coerceAtLeast(0)
                                    val isOverscrolled =
                                        dragScrollPos < 0f || dragScrollPos > maxIndex.toFloat()

                                    flingJob = coroutineScope.launch {
                                        animScrollPos.snapTo(dragScrollPos)
                                        isDragging = false
                                        animScrollPos.animateTo(
                                            targetValue = targetIndex.toFloat(),
                                            initialVelocity = if (isOverscrolled) 0f else velocityInIndex,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = 80f,
                                                visibilityThreshold = 0.0005f
                                            )
                                        )
                                        onSelectedIndexChange(targetIndex)
                                    }
                                }

                                else -> {
                                    isDragging = false
                                }
                            }
                        },
                        onDragCancel = {
                            val target = dragScrollPos.roundToInt()
                                .coerceIn(0, (backStack.size - 1).coerceAtLeast(0))
                            flingJob = coroutineScope.launch {
                                animScrollPos.snapTo(dragScrollPos)
                                isDragging = false
                                animScrollPos.animateTo(
                                    targetValue = target.toFloat(),
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow,
                                        visibilityThreshold = 0.0005f
                                    )
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y

                            if (dragDirection == null && (abs(totalDragX) > 15f || abs(totalDragY) > 15f)) {
                                dragDirection = if (abs(totalDragX) > abs(totalDragY)) 0 else 1
                            }

                            when (dragDirection) {
                                0 -> {
                                    val maxIndex = (backStack.size - 1).coerceAtLeast(0).toFloat()
                                    val delta = -dragAmount.x / dragSpacingPx
                                    // Base damping: heavier drag feel (70% of raw input)
                                    val baseFriction = 0.70f
                                    // Rubber-band: progressive resistance when past the edge.
                                    // iOS-style: initial overscroll is responsive, gets harder as you pull further.
                                    val overscrollAmount = when {
                                        dragScrollPos < 0f -> -dragScrollPos
                                        dragScrollPos > maxIndex -> dragScrollPos - maxIndex
                                        else -> 0f
                                    }
                                    val isOverscrolling = (dragScrollPos <= 0f && delta < 0f) ||
                                            (dragScrollPos >= maxIndex && delta > 0f)
                                    val edgeFriction = if (isOverscrolling) {
                                        // Progressive resistance: starts at 0.6, decreases as you pull further
                                        // At overscroll=0: friction=0.6, at overscroll=2: friction≈0.3
                                        0.6f / (1f + overscrollAmount * 0.5f)
                                    } else {
                                        1f
                                    }
                                    dragScrollPos += delta * baseFriction * edgeFriction
                                }

                            }
                        }
                    )
                }
                .pointerInput(backStack.size) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (isShrinking || expandingIndex != null) return@detectTapGestures

                            val tapX = offset.x
                            val tapY = offset.y
                            val totalHeight = cardHeightPx + titleHeightPx

                            // Read the latest scroll position at tap time
                            val sp = animScrollPos.value
                            var tappedIndex: Int? = null
                            // Check from highest z-index (rightmost) to lowest
                            for (i in backStack.indices.reversed()) {
                                val cx = cardCenterX(i, sp)
                                val cardLeft = cx - cardWidthPx / 2f
                                val cardRight = cx + cardWidthPx / 2f
                                val cardTop = centerY - totalHeight / 2f
                                val cardBottom = cardTop + totalHeight

                                if (tapX in cardLeft..cardRight && tapY in cardTop..cardBottom) {
                                    tappedIndex = i
                                    break
                                }
                            }

                            if (tappedIndex != null) {
                                flingJob?.cancel()
                                flingJob = null
                                expandingIndex = tappedIndex
                                coroutineScope.launch {
                                    expandProgress.snapTo(0f)
                                    expandProgress.animateTo(
                                        1f,
                                        tween(400, easing = iOSEasing)
                                    )
                                    // Navigate first (backStack change only, overlay stays visible).
                                    // The fullscreen screenshot keeps covering everything.
                                    onCardClick(tappedIndex)
                                    // Wait for the AnimatedContent crossfade (~310ms) to settle
                                    // before dismissing. The fullscreen screenshot covers this gap.
                                    delay(400)
                                    onDismiss()
                                }
                            } else {
                                animatedDismiss()
                            }
                        }
                    )
                }
        )
    }
}
