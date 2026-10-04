import sys, textwrap

lines = open("model.txt").read().split("\n")
mu, sd = lines[0], lines[1]
layers = []
i = 2
while i + 2 < len(lines) and lines[i].strip():
    a, b = map(int, lines[i].split())
    layers.append((a, b, lines[i + 1], lines[i + 2]))
    i += 3

def chunks(s, n=4000):
    parts = [s[j:j + n] for j in range(0, len(s), n)]
    return " +\n            ".join(f'"{p}"' for p in parts)

out = []
out.append('''package com.hesi.snotes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * GENERATED FILE - weights of the shape detector's neural network.
 *
 * A multilayer perceptron (%s, ReLU hidden layers, softmax over %d classes)
 * trained offline with scikit-learn on 156,000 synthetic hand-drawn strokes (see
 * tools/shape-model/ in the project). Features are standardised with the
 * training mean/std below. Inference is ~%d multiply-adds - well under a
 * millisecond on a phone.
 */
object ShapeModel {
''' % (" -> ".join([str(layers[0][0])] + [str(l[1]) for l in layers]), layers[-1][1],
       sum(l[0] * l[1] for l in layers)))
out.append(f'    private const val MU = {chunks(mu)}\n')
out.append(f'    private const val SD = {chunks(sd)}\n')
for k, (a, b, w, bias) in enumerate(layers):
    out.append(f'    private const val W{k} = {chunks(w)}\n')
    out.append(f'    private const val B{k} = {chunks(bias)}\n')
dims = ", ".join(f"intArrayOf({a}, {b})" for a, b, _, _ in layers)
ws = ", ".join(f"W{k}" for k in range(len(layers)))
bs = ", ".join(f"B{k}" for k in range(len(layers)))
out.append(f'''
    private fun floats(b64: String): FloatArray {{
        val bytes = Base64.getDecoder().decode(b64)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also {{ fb.get(it) }}
    }}

    private val dims = arrayOf({dims})
    private val mu by lazy {{ floats(MU) }}
    private val sd by lazy {{ floats(SD) }}
    private val weights by lazy {{ arrayOf({", ".join(f"floats({w})" for w in [f"W{k}" for k in range(len(layers))])}) }}
    private val biases by lazy {{ arrayOf({", ".join(f"floats(B{k})" for k in range(len(layers)))}) }}

    /** Class probabilities (ShapeRecognizer.Kind order) for a feature vector. */
    fun predict(features: FloatArray): FloatArray {{
        var x = FloatArray(features.size) {{ (features[it] - mu[it]) / sd[it] }}
        for (l in dims.indices) {{
            val nIn = dims[l][0]; val nOut = dims[l][1]
            val w = weights[l]; val b = biases[l]
            val y = FloatArray(nOut)
            for (o in 0 until nOut) {{
                var s = b[o]
                // scikit-learn stores coefs as [in][out] (row-major)
                for (i in 0 until nIn) s += x[i] * w[i * nOut + o]
                y[o] = if (l < dims.size - 1) (if (s > 0f) s else 0f) else s
            }}
            x = y
        }}
        // softmax
        var m = x[0]
        for (v in x) if (v > m) m = v
        var sum = 0f
        for (i in x.indices) {{ x[i] = kotlin.math.exp(x[i] - m); sum += x[i] }}
        for (i in x.indices) x[i] /= sum
        return x
    }}
}}
''')
open(sys.argv[1], "w").write("".join(out))
