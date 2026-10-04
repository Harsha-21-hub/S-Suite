package com.hesi.slog

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * Runs the on-device reminder text generator (assets/reminder_model.tflite)
 * using the vocabulary in assets/vocab.json.
 *
 * Quality filters (same as ai/train_slog_model.py shows in samples.txt):
 *  - a message must finish on its own (end token), not be cut off by the length limit
 *  - every word must be one the model was trained on (vocab.json -> known_words)
 *  - the 7 messages of a week must not be near-copies of each other
 */
class ReminderInference(context: Context) {

    private val tok2id = HashMap<String, Int>()
    private val id2tok = HashMap<Int, String>()
    private val blocked = HashSet<Int>()
    private val knownWords = HashSet<String>()
    private val knownPairs = HashSet<String>()
    private var eosId = 1

    private val interpreter: Interpreter
    private val vocabSize: Int
    private val modelMaxLen: Int
    private val inputIsInt: Boolean
    private val intInput: Array<IntArray>?
    private val floatInput: Array<FloatArray>?
    private val outputBuf: Array<Array<FloatArray>>

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        interpreter = Interpreter(loadAsset(context, "reminder_model.tflite"), options)

        val text = context.assets.open("vocab.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)

        val t2i = json.getJSONObject("tok2id")
        val keys = t2i.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = t2i.getInt(key)
            tok2id[key] = id
            id2tok[id] = key
        }
        val blockedIds = json.getJSONArray("blocked_ids")
        for (i in 0 until blockedIds.length()) {
            blocked.add(blockedIds.getInt(i))
        }
        eosId = json.getInt("eos_id")
        json.optJSONArray("known_words")?.let { arr ->
            for (i in 0 until arr.length()) knownWords.add(arr.getString(i))
        }
        json.optJSONArray("known_pairs")?.let { arr ->
            for (i in 0 until arr.length()) knownPairs.add(arr.getString(i))
        }
        vocabSize = tok2id.size

        val inputTensor = interpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        val maxLen = if (shape.size >= 2 && shape[1] > 0) shape[1] else json.optInt("maxlen", 64)
        modelMaxLen = maxLen
        inputIsInt = inputTensor.dataType() == DataType.INT32

        interpreter.resizeInput(0, intArrayOf(1, maxLen))
        interpreter.allocateTensors()

        intInput = if (inputIsInt) arrayOf(IntArray(maxLen)) else null
        floatInput = if (inputIsInt) null else arrayOf(FloatArray(maxLen))
        outputBuf = arrayOf(Array(maxLen) { FloatArray(vocabSize) })
    }

    /** One message for [category], or null if it didn't finish within the length limit. */
    fun generate(category: String, maxNewTokens: Int = 48, temperature: Float = 0.7f): String? {
        val startId = tok2id["<$category>"] ?: return null
        val seq = ArrayList<Int>(modelMaxLen)
        seq.add(startId)
        val sb = StringBuilder()
        val limit = min(maxNewTokens, modelMaxLen - 1)

        var step = 0
        while (seq.size < modelMaxLen && step < limit) {
            fillInput(seq)
            val input: Any = if (inputIsInt) intInput!! else floatInput!!
            interpreter.run(input, outputBuf)

            val logits = outputBuf[0][seq.size - 1]
            for (b in blocked) logits[b] = -1.0e9f

            val next = sample(logits, temperature)
            if (next == eosId) return sb.toString()
            sb.append(id2tok[next] ?: "")
            seq.add(next)
            step++
        }
        return null // cut off by the length limit -> unusable
    }

    fun generateBatch(category: String, count: Int = 7): List<String> {
        val results = ArrayList<String>()
        var attempts = 0
        while (results.size < count && attempts < count * 12) {
            attempts++
            val msg = generate(category)?.replace(Regex("\\s+"), " ")?.trim() ?: continue
            if (msg.length < 6 || !usesKnownWords(msg)) continue
            if (results.any { tooSimilar(it, msg) }) continue
            results.add(msg)
        }
        return results
    }

    private fun wordsOf(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * No made-up words ("Deblet") and no odd combinations ("budget us calling"): every word and
     * every pair of neighbouring words must appear in the training data (^ = start, $ = end).
     */
    private fun usesKnownWords(msg: String): Boolean {
        val words = wordsOf(msg)
        if (knownWords.isNotEmpty() && !words.all { it in knownWords }) return false
        if (knownPairs.isEmpty()) return true
        val w = listOf("^") + words + listOf("$")
        return w.zipWithNext().all { (a, b) -> "$a $b" in knownPairs }
    }

    /** Word overlap >= 60% = basically the same message ("Focus 25 min, champ" / "..., legend"). */
    private fun tooSimilar(a: String, b: String): Boolean {
        val wa = wordsOf(a).toSet()
        val wb = wordsOf(b).toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        return (wa intersect wb).size.toDouble() / (wa union wb).size >= 0.6
    }

    fun close() {
        try {
            interpreter.close()
        } catch (_: Exception) {
        }
    }

    private fun fillInput(seq: List<Int>) {
        if (inputIsInt) {
            val arr = intInput!![0]
            for (i in arr.indices) arr[i] = if (i < seq.size) seq[i] else 0
        } else {
            val arr = floatInput!![0]
            for (i in arr.indices) arr[i] = if (i < seq.size) seq[i].toFloat() else 0f
        }
    }

    private fun sample(logits: FloatArray, temp: Float): Int {
        var maxLogit = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > maxLogit) maxLogit = v

        val probs = FloatArray(logits.size)
        var sum = 0f
        for (i in logits.indices) {
            val e = exp((logits[i] - maxLogit) / temp)
            probs[i] = e
            sum += e
        }
        var r = Random.nextFloat() * sum
        for (i in probs.indices) {
            r -= probs[i]
            if (r <= 0f) return i
        }
        return probs.size - 1
    }

    private companion object {
        val WORD = Regex("[a-z0-9']+")
    }

    private fun loadAsset(ctx: Context, name: String): MappedByteBuffer {
        val fd = ctx.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
