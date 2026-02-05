package ceui.lisa.stackswipe.appswitcher

/**
 * Represents a page in the app switcher.
 */
sealed interface NavRoute {
    /** Stable key for screenshot storage */
    val stableKey: String

    /** Display title for the app switcher */
    val title: String

    data class Page(
        override val title: String,
        override val stableKey: String = title
    ) : NavRoute
}

/**
 * Wraps a [NavRoute] with a unique ID so the same route appearing multiple
 * times in the back stack gets distinct screenshot keys.
 */
data class BackStackEntry(
    val id: Int,
    val route: NavRoute
) {
    /** Unique key for screenshot storage — distinguishes duplicate routes. */
    val screenshotKey: String get() = "${id}_${route.stableKey}"
}

/**
 * State for the app switcher overlay.
 */
data class AppSwitcherState(
    val isVisible: Boolean = false,
    val selectedIndex: Int = -1
)

/** Get the title for display in app switcher */
fun NavRoute.getTitle(): String = title
