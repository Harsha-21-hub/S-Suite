import com.hesi.snotes.ShapeRecognizer
import java.io.File

fun main(args: Array<String>) {
    val names = ShapeRecognizer.Kind.values()
    val total = IntArray(11); val correct = IntArray(11); val rejected = IntArray(11)
    val conf = Array(11) { IntArray(11) }
    val dump = if (args.size > 1) File(args[1]).bufferedWriter() else null
    var t0 = System.nanoTime()
    var count = 0
    File(args[0]).forEachLine { line ->
        val parts = line.trim().split(" ")
        val label = parts[0].toInt()
        val n = (parts.size - 1) / 2
        val xy = FloatArray(n * 2) { parts[it + 1].toFloat() }
        val r = ShapeRecognizer.recognize(xy, n)
        count++
        total[label]++
        val got = r?.kind?.ordinal ?: 0
        conf[label][got]++
        if (got == label) correct[label]++
        if (r == null && label != 0) rejected[label]++
        if (dump != null && total[label] <= 12) {
            dump.write("$label ${r?.label ?: "-"} " + line.substringAfter(" ").trim() + " | " + (r?.points?.joinToString(" ") ?: "") + "\n")
        }
    }
    dump?.close()
    val ms = (System.nanoTime() - t0) / 1e6
    for (i in 0 until 11) {
        println("%-9s n=%5d correct=%.3f rejected=%.3f".format(names[i], total[i], correct[i].toDouble() / total[i], rejected[i].toDouble() / total[i]))
    }
    println("confusion (rows=truth, cols=result; col0 = left as ink)")
    for (i in 0 until 11) println(names[i].name.padEnd(9) + conf[i].joinToString(" ") { it.toString().padStart(5) })
    println("avg ms/stroke = %.3f".format(ms / count))
}
