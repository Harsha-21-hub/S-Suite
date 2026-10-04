import numpy as np, sys, base64, struct
from sklearn.neural_network import MLPClassifier
from sklearn.metrics import confusion_matrix, classification_report

names = ["NONE","LINE","ARROW","ELLIPSE","TRI","QUAD","PENT","HEX","STAR","ARC","POLY"]

def load(p):
    d = np.loadtxt(p, delimiter=",", dtype=np.float64)
    return d[:, 1:], d[:, 0].astype(int)

Xtr, ytr = load("train.csv")
Xte, yte = load("test.csv")
mu = Xtr.mean(0); sd = Xtr.std(0); sd[sd < 1e-6] = 1.0
Xs = (Xtr - mu) / sd; Xt = (Xte - mu) / sd

hidden = tuple(int(v) for v in sys.argv[1].split(",")) if len(sys.argv) > 1 else (64, 32)
clf = MLPClassifier(hidden_layer_sizes=hidden, activation="relu", alpha=1e-4,
                    learning_rate_init=2e-3, max_iter=300, early_stopping=True,
                    validation_fraction=0.1, n_iter_no_change=15, random_state=0)
clf.fit(Xs, ytr)
p = clf.predict(Xt)
print("test acc", (p == yte).mean())
print(classification_report(yte, p, target_names=names, digits=3))
print(confusion_matrix(yte, p))

# with confidence threshold 0.55 -> NONE
pr = clf.predict_proba(Xt)
mx = pr.max(1); am = pr.argmax(1)
am[mx < 0.55] = 0
print("acc with 0.55 threshold", (am == yte).mean())
neg = yte == 0
print("NONE false-positive rate", (am[neg] != 0).mean())

# export
def b64(a):
    a = np.asarray(a, dtype="<f4").reshape(-1)
    return base64.b64encode(a.tobytes()).decode()

layers = []
for W, b in zip(clf.coefs_, clf.intercepts_):
    layers.append((W.shape[0], W.shape[1], b64(W), b64(b)))
with open("model.txt", "w") as f:
    f.write(b64(mu) + "\n" + b64(sd) + "\n")
    for (i, o, w, b) in layers:
        f.write(f"{i} {o}\n{w}\n{b}\n")
print("layers", [(l[0], l[1]) for l in layers])
