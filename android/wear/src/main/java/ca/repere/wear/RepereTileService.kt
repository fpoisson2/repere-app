package ca.repere.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

private const val RESOURCES_VERSION = "1"

/** A swipeable Tile: today's standard drinks + estimated BAC, tap opens the app. */
class RepereTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            runCatching { StateCache.refresh(this@RepereTileService) }
            val prefs = getSharedPreferences("repere", MODE_PRIVATE)
            val active = prefs.getBoolean("active", false)
            val today = prefs.getFloat("today_standard", 0f)
            val bac = prefs.getFloat("bac_g_per_l", 0f)
            val primary = if (today > 10) today.toInt().toString() else String.format(Locale.getDefault(), "%.1f", today)
            val secondary = if (active) "Consommation en cours" else "cons. standard aujourd'hui"
            val bacLine = String.format(Locale.getDefault(), "Alcoolémie estimée %.2f g/L", bac)

            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(5 * 60 * 1000L)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(
                        layout(primary, secondary, bacLine, requestParams.deviceConfiguration),
                    ),
                )
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }

    private fun layout(
        primary: String,
        secondary: String,
        bacLine: String,
        device: DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    ).build(),
            ).build()

        val content = LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.wrap())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(Text.Builder(this, primary).setTypography(Typography.TYPOGRAPHY_DISPLAY1).setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFFFFFFF.toInt())).build())
            .addContent(Text.Builder(this, secondary).setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFB8E0D2.toInt())).build())
            .addContent(Text.Builder(this, bacLine).setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFEAA33A.toInt())).build())
            .build()

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setContent(content)
            .setPrimaryChipContent(
                CompactChip.Builder(this, "Ouvrir", openApp, device)
                    .setChipColors(androidx.wear.protolayout.material.ChipColors.primaryChipColors(Colors.DEFAULT))
                    .build(),
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
