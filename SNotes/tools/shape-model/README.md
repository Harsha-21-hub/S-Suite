# Shape detector model

Offline pipeline that produces `app/src/main/java/com/hesi/snotes/ShapeModel.kt`
(the weights of the small neural network used by `ShapeRecognizer`).
Nothing here is part of the app build.

1. `python3 gen.py 5000 11 train.txt` - synthetic hand-drawn strokes
   (one line per stroke: `label x0 y0 x1 y1 ...`). Run it a few times with
   different seeds and concatenate for more data; make a test set the same way.
2. Compile the feature dumper together with the app's recogniser (pure Kotlin):
   `kotlinc ShapeRecognizer.kt ShapeModel.kt FeatureDump.kt Eval.kt -include-runtime -d tools.jar`
   then `java -cp tools.jar FeatureDumpKt train.txt train.csv` (same for test).
   Using the app's own Kotlin code guarantees training and inference features match.
3. `python3 train.py 128,64` - trains the MLP (scikit-learn), prints the test
   report, writes `model.txt`.
4. `python3 export_kt.py ShapeModel.kt` - writes the Kotlin model file.
5. `java -cp tools.jar EvalKt test.txt` - end-to-end check (classify + fit + verify).

Classes (ShapeRecognizer.Kind order): NONE, LINE, ARROW, ELLIPSE, TRIANGLE, QUAD,
PENTAGON, HEXAGON, STAR, ARC, POLYLINE.

Results of the shipped model on 18,000 held-out strokes:

| class | correct | left as ink |
|---|---|---|
| line, ellipse/circle, star | 100% | 0% |
| triangle, quad, pentagon, hexagon, arc | 99.7-99.9% | <=0.2% |
| arrow | 98.9% | 1.1% |
| open polyline | 92.3% | 7.6% |
| scribble/handwriting (should stay ink) | 97.8% stay ink | - |
