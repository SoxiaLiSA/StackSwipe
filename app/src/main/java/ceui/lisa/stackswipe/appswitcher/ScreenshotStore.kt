package ceui.lisa.stackswipe.appswitcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenshotStore {
    // Live GraphicsLayer references (only the currently composed page has one)
    private val layers = mutableMapOf<String, GraphicsLayer>()

    // Cached bitmaps for the app switcher
    private val screenshots: SnapshotStateMap<String, ImageBitmap?> = mutableStateMapOf()

    fun registerLayer(key: String, layer: GraphicsLayer) {
        layers[key] = layer
    }

    fun unregisterLayer(key: String) {
        layers.remove(key)
    }

    /** Capture all registered layers to bitmaps. Call before showing the switcher. */
    suspend fun captureAll() {
        layers.forEach { (key, layer) ->
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    layer.toImageBitmap()
                }
                screenshots[key] = bitmap
            } catch (_: Exception) {
            }
        }
    }

    /** Capture a single layer to bitmap (e.g., before navigating away). */
    suspend fun captureOne(key: String) {
        val layer = layers[key] ?: return
        try {
            val bitmap = withContext(Dispatchers.Default) {
                layer.toImageBitmap()
            }
            screenshots[key] = bitmap
        } catch (_: Exception) {
        }
    }

    fun putScreenshot(key: String, bitmap: ImageBitmap) {
        screenshots[key] = bitmap
    }

    fun getScreenshot(key: String): ImageBitmap? = screenshots[key]

    fun remove(key: String) {
        screenshots.remove(key)
        layers.remove(key)
    }

    fun clear() {
        screenshots.clear()
        layers.clear()
    }
}

/**
 * Wraps content and registers a GraphicsLayer for on-demand screenshot capture.
 */
@Composable
fun ScreenshotCapture(
    key: String,
    screenshotStore: ScreenshotStore,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val graphicsLayer = rememberGraphicsLayer()

    // Register/unregister the layer with the store
    DisposableEffect(key, graphicsLayer) {
        screenshotStore.registerLayer(key, graphicsLayer)
        onDispose {
            screenshotStore.unregisterLayer(key)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                graphicsLayer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(graphicsLayer)
            }
    ) {
        content()
    }
}
