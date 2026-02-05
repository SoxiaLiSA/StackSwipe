package ceui.lisa.stackswipe.appswitcher

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.random.Random

private val demoTitles = listOf(
    "Home", "Explore", "Trending", "Latest", "Favorites",
    "Notifications", "Profile", "Settings", "Search", "Rankings",
    "Featured", "Following", "History", "Downloads", "Details",
    "Comments", "User", "Tags", "Timeline", "Create"
)

@Composable
fun AppSwitcherDemoScreen() {
    val random = remember { Random(42) }

    val demoColors = remember {
        List(20) {
            Color(
                red = random.nextFloat() * 0.6f + 0.2f,
                green = random.nextFloat() * 0.6f + 0.2f,
                blue = random.nextFloat() * 0.6f + 0.2f,
                alpha = 1f
            )
        }
    }

    val demoEntries = remember {
        List(20) { index ->
            BackStackEntry(
                id = index,
                route = NavRoute.Page(demoTitles[index % demoTitles.size])
            )
        }
    }

    val screenshotStore = remember { ScreenshotStore() }
    var selectedIndex by remember { mutableIntStateOf(19) }
    var isVisible by remember { mutableStateOf(true) }

    // Pre-populate screenshots with tiny 2x2 solid-color bitmaps.
    // FillBounds scaling stretches them to card size — identical to full-res
    // but uses ~320 bytes total instead of ~200 MB.
    remember {
        demoEntries.forEachIndexed { index, entry ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(demoColors[index].toArgb())
            screenshotStore.putScreenshot(entry.screenshotKey, bitmap.asImageBitmap())
        }
        true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )

        if (isVisible) {
            AppSwitcherOverlay(
                backStack = demoEntries,
                screenshotStore = screenshotStore,
                state = AppSwitcherState(isVisible = true, selectedIndex = selectedIndex),
                onCardClick = { index ->
                    selectedIndex = index
                },
                onSelectedIndexChange = { index ->
                    selectedIndex = index
                },
                onDismiss = {
                    isVisible = false
                }
            )
        }

        if (!isVisible) {
            // After dismiss, show tapped page color fullscreen; tap to reopen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(demoColors[selectedIndex.coerceIn(demoColors.indices)])
                    .pointerInput(Unit) {
                        detectTapGestures {
                            isVisible = true
                        }
                    }
            )
        }
    }
}
