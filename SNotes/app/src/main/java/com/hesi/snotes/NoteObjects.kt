package com.hesi.snotes

import android.graphics.Bitmap

class TextObject(
    var text: String,
    var x: Float,
    var y: Float,
    var w: Float = 620f,
    var h: Float = 180f,
    var size: Float = 64f,
    var color: Int = 0xFF000000.toInt(),
    var rotation: Float = 0f
)

class ImageObject(
    var name: String,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var rotation: Float = 0f
) {
    var bitmap: Bitmap? = null
}
