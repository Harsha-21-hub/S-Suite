package com.hesi.snotes

import android.graphics.Bitmap

/** Undo/redo history entries. */
sealed class Action {
    class Add(val strokes: List<Stroke>) : Action()
    class Remove(val items: List<Pair<Int, Stroke>>) : Action()
    class Move(val strokes: List<Stroke>, val dx: Float, val dy: Float) : Action()
    class Scale(val strokes: List<Stroke>, val f: Float, val px: Float, val py: Float) : Action()
    class Clear(val old: List<Stroke>) : Action()

    /**
     * Full-document history entry used by text/image objects. Strokes are included
     * in the snapshot so undo/redo remains correct even when an object edit happens
     * between two drawing/erasing operations.
     */
    class Document(val before: DocumentState, val after: DocumentState) : Action()
}

data class StrokeState(
    val points: ArrayList<Float>,
    val color: Int,
    val width: Float,
    val eraser: Boolean,
    val straight: Boolean,
    val pressures: ArrayList<Float>?
)

data class TextState(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val size: Float,
    val color: Int,
    val rotation: Float
)

data class ImageState(
    val name: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotation: Float,
    val bitmap: Bitmap?
)

data class DocumentState(
    val strokes: List<StrokeState>,
    val texts: List<TextState>,
    val images: List<ImageState>
)
