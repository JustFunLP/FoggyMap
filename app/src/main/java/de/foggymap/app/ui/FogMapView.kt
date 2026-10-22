package de.foggymap.app.ui

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** MapLibre map hosted in Compose, with its lifecycle wired to the activity. */
@Composable
fun FogMapView(modifier: Modifier, topInsetPx: Int, onReady: (FogMapController) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val ready = rememberUpdatedState(onReady)
    val mapView = remember { MapView(context).also { it.onCreate(null) } }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                // Left edge, vertically centred: never covered by the status line or the bottom card.
                attributionGravity = Gravity.START or Gravity.CENTER_VERTICAL
                val m = (12 * density.density).toInt()
                setAttributionMargins(m, 0, m, 0)
                isTiltGesturesEnabled = true
                isRotateGesturesEnabled = true
            }
            map.setMinZoomPreference(2.5)
            map.setMaxZoomPreference(19.5)
            map.setStyle(Style.Builder().fromUri("asset://style.json")) { style ->
                val controller = FogMapController(context, map, style)
                controller.install()
                ready.value(controller)
            }
        }
    }
}

