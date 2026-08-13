package com.thermal.tracker.processing

import org.opencv.android.Utils
import android.graphics.Bitmap
import org.opencv.core.Mat

/** Mat -> Android Bitmap（CV_8UC1/3/4 均可）。 */
fun matToBitmap(mat: Mat): Bitmap {
    val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, bmp)
    return bmp
}
