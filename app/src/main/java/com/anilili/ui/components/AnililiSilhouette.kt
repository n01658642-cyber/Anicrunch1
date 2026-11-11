package com.anilili.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Anilili mark as vector geometry, for the loaders that fill it.
 *
 * It replaced a pair of 512px PNGs — a fill mask and a separately traced outline — that were
 * scaled to every size the loader is drawn at. The mask carried a soft white halo around the whole
 * silhouette and the outline had visibly stepped edges, both of which the fill made obvious: the
 * water's edge sat inside a blurred rim. The path is the logo's own SVG (1254-unit canvas, the
 * black backdrop square dropped), so it is exact at every size.
 */
private const val SilhouetteBody =
    "M709.919 120.93c12.75-.745 27-.347 39.811-.115-69.446 17.857-125.11 53.602-164.785 113.84a368.53 368.53 0 0 1 208.244 27.189 376 376 0 0 1 168.565 149.904c13.761 22.661 28.641 53.045 38.306 77.818-56.626-65.245-121.024-111.796-207.021-129.538 154.458 103.567 250.701 264.373 211.131 453.405-5.194-16.536-7.14-28.133-14.289-45.358-26.867-64.736-62.889-110.105-118.692-151.343 7.593 17.284 13.193 29.545 19.168 47.644a309.26 309.26 0 0 1 11.262 147.046c-12.349 73.024-46.538 116.366-106.124 158.797 5.277-16.107 10.556-31.859 14.189-48.445a238.87 238.87 0 0 0-13.882-148.01c-6.563 115.628-76.039 189.075-174.628 240.446 20.042-1.51 34.388-2.49 54.237-5.51 95.617-14.547 180.717-60.546 250.693-126.557 16.752-15.803 31.903-32.888 47.159-50.223-18.14 57.944-56.076 104.78-101.664 143.871-82.036 70.349-195.52 110.489-303.738 101.779-82.93-6.67-164.738-42.23-227.938-96.057-20.892-17.792-38.911-36.838-56.675-57.641-36.161-44.845-66.761-95.828-80.395-152.285 20.857 37.19 36.465 60.821 66.496 92.451 70.568 74.324 178.141 119.696 280.126 122.478 28.093-29.391 48.951-75.334 49.422-116.387.275-23.92-10.972-41.668-35.21-45.119-17.795-2.534-29.825.493-44.33-13.87-5.983-6.004-10.467-14.38-11.63-22.886-2.628-19.209-1.581-40.456-1.569-59.902l.019-108.983c-25.951-22.783-39.473-40.322-58.482-68.232 4.222 26.728 14.128 49.039 23.995 74.069-45.088-36.665-82.932-87.676-100.689-143.337-1.617-5.069-2.293-10.842-3.954-16.098l-.183-.563a139 139 0 0 0-1.538 6.895c-4.204 20.801-4.606 42.828-3.685 63.926 2.762 63.266 20.326 107.68 54.884 159.74-13.701-7.32-23.393-14.06-35.727-23.249-13.731 21.03-30.511 30.688-51.52 43.348 4.24 14.061 17.284 25.867 25.848 37.563 8.399 11.47 7.625 17.757 4.648 30.694 3.58 9.325 12.556 12.229 15.723 19.856 1.761 4.24-1.487 9.23 1.301 13.421 2.899 4.357 9.099 5.651 12.125 11.273 7.766 14.426 9.672 31.41 16.029 44.849 1.711 3.617 4.003 7.278 7.791 8.957 21.85 9.682 99.495-27.234 110.843-21.323 9.101 4.741 23.582 44.824 26.329 54.634-8.07-13.129-16.829-32.176-32.525-38.347-30.115 5.018-72.045 26.489-103.79 23.866-28.909-4.198-26.142-37.688-35.909-56.717-8.61-16.772-22.773-12.886-18.998-36.7-29.273-25.304-8.804-26.169-16.906-42.511-7.624-15.377-23.817-25.817-29.148-42.837-1.786-5.702-1.359-11.396 2.347-16.237 3.741-4.885 9.81-7.78 14.987-10.873 15.329-9.156 28.186-19.11 36.866-34.895-14.24-15.416-21.251-25.044-32.905-42.095a259.6 259.6 0 0 0 17.29 50.684c-29.799-26.496-43.953-52.858-56.252-90.297-1.751 8.229-12.135 23.006-16.88 31.033-18.455 31.218-30.759 61.587-32.84 98.096-28.45-45.915-24.712-125.145-12.994-175.858 5.233-22.649 13.494-45.511 22.349-66.951-22.854 14.696-48.56 38.309-66.602 58.624 13.753-53.466 51.392-109.877 89.075-150.07 53.227-56.772 126.015-104.071 203.949-116.836 8.86-1.451 18.157-1.714 27.109-2 8.624-95.372 121.308-129.003 201.781-133.941"

/** The play triangle, cut out of [SilhouetteBody] rather than painted over it. */
private const val SilhouettePlay =
    "M545.517 452.584c2.474-.106 5.005-.248 7.453.149a27.1 27.1 0 0 1 8.847 3.128c11.568 6.547 28.756 18.396 40.33 25.844l80.933 52.183 71.215 45.792c13.33 8.481 26.741 16.704 39.932 25.551 13.545 9.14 14.932 31.43 2.109 41.797-8.355 6.756-20.064 13.291-29.369 19.286l-72.055 46.472c-33.55 21.177-66.579 43.24-99.963 64.632-7.751 4.966-31.489 21.467-38.462 23.835a23.77 23.77 0 0 1-18.373-1.302 24.78 24.78 0 0 1-12.171-14.774c-2.033-6.854-1.248-40.19-1.239-49.131l.084-90.048.049-114.921c.051-18.297-.048-36.66.292-54.947.235-12.681 8.717-20.643 20.388-23.546"

/** The mark in SVG units, triangle already removed. Parsed once; nothing mutates it. */
private val silhouette: Path by lazy {
    val body = PathParser().parsePathString(SilhouetteBody).toPath()
    val play = PathParser().parsePathString(SilhouettePlay).toPath()
    Path().apply { op(body, play, PathOperation.Difference) }
}

private val silhouetteBounds: Rect by lazy { silhouette.getBounds() }

/**
 * Where the mark lands inside a canvas of [canvas] size: centred, aspect kept, filling
 * [fraction] of the shorter side. The 0.9 default matches the margin the old bitmaps carried.
 */
internal fun silhouetteFrame(canvas: Size, fraction: Float = 0.9f): Rect {
    val source = silhouetteBounds
    val scale = min(canvas.width, canvas.height) * fraction / max(source.width, source.height)
    val width = source.width * scale
    val height = source.height * scale
    val left = (canvas.width - width) / 2f
    val top = (canvas.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * The mark rasterised once, opaque white on transparent, at exactly [canvas] pixels and placed at
 * [silhouetteFrame]. Tinted and used as an alpha source by the loader, so per frame there is no
 * path to fill and no clip to rasterise — only bitmap sampling.
 */
internal fun silhouetteMask(canvas: Size, fraction: Float = 0.9f): ImageBitmap {
    val width = canvas.width.roundToInt().coerceAtLeast(1)
    val height = canvas.height.roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    val frame = silhouetteFrame(Size(width.toFloat(), height.toFloat()), fraction)
    val source = silhouetteBounds
    val scale = frame.width / source.width
    Canvas(bitmap).apply {
        translate(frame.left, frame.top)
        scale(scale, scale)
        translate(-source.left, -source.top)
        drawPath(silhouette, Paint().apply { color = Color.White })
    }
    return bitmap
}
