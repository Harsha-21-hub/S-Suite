import com.hesi.snotes.ShapeRecognizer
import java.io.File

fun main(args: Array<String>) {
    val out = File(args[1]).bufferedWriter()
    var bad = 0
    File(args[0]).forEachLine { line ->
        val parts = line.trim().split(" ")
        val label = parts[0].toInt()
        val n = (parts.size - 1) / 2
        val xy = FloatArray(n * 2) { parts[it + 1].toFloat() }
        val f = ShapeRecognizer.featureVector(xy, n)
        if (f == null || f.any { it.isNaN() || it.isInfinite() }) { bad++; return@forEachLine }
        out.write(label.toString())
        for (v in f) { out.write(","); out.write(v.toString()) }
        out.write("\n")
    }
    out.close()
    System.err.println("skipped=$bad")
}
