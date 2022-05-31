from collections.abc import Iterable
import json
from flask import Flask, request
from typing import List, Tuple
import numpy as np
from collections import defaultdict
import conllu
import flair
from flair.models import SequenceTagger
from flair.data import Sentence

from time import sleep

app = Flask(__name__)


def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def ssplit(full_conllu: str, span_size: int=20, stride_size: int=10):
    """
    Given a document in a dictionary representing midas-loop json format,
    return probabilities that each token begins a new sentence (B) or not (O).
    """
    # Supertokens are filtered out of the sentence since they are not valid targets for annotation.
    toks = []
    ids = []
    parsed = conllu.parse(full_conllu)
    for sent in parsed:
        for tok in sent:
            if not is_supertoken(tok):
                toks.append(tok["form"])
                ids.append(tok["id"])

    # Load splitter model
    model = SequenceTagger.load("flair-splitter-sent.pt")

    # Get a fresh prediction
    final_mapping = {}  # Map each contextualized token to its (sequence_number, position)
    spans = []  # Holds flair Sentence objects for labeling

    # Hack tokens up into overlapping shingles
    wraparound = toks[-stride_size :] + toks + toks[: span_size]
    idx = 0
    mapping = defaultdict(set)
    snum = 0
    while idx < len(toks):
        if idx + span_size < len(wraparound):
            span = wraparound[idx : idx + span_size]
        else:
            span = wraparound[idx:]
        sent = Sentence(" ".join(span), use_tokenizer=lambda x: x.split())
        spans.append(sent)
        for i in range(idx - stride_size, idx + span_size - stride_size):
            # start, end, snum
            if i >= 0 and i < len(toks):
                mapping[i].add((idx - stride_size, idx + span_size - stride_size, snum))
        idx += stride_size
        snum += 1

    for idx in mapping:
        best = span_size
        for m in mapping[idx]:
            start, end, snum = m
            dist_to_end = end - idx
            dist_to_start = idx - start
            delta = abs(dist_to_end - dist_to_start)
            if delta < best:
                best = delta
                final_mapping[idx] = (snum, idx - start)  # Get sentence number and position in sentence

    # Predict
    model.predict(spans)

    preds = []
    for idx in final_mapping:
        snum, position = final_mapping[idx]
        if str(flair.__version__).startswith("0.4"):
            pred_tag = spans[snum].tokens[position].tags["ner"].value
            pred_proba = spans[snum].tokens[position].tags["ner"].score
        else:
            pred_tag = spans[snum].tokens[position].labels[0].value
            pred_proba = spans[snum].tokens[position].labels[0].score
        other_tag = "B-SENT" if pred_tag == "O" else "O"
        other_proba = 1-pred_proba
        tid = ids[idx]

        #preds.append({tid:{pred_tag:pred_proba,other_tag:other_proba}})
        preds.append({pred_tag:pred_proba,other_tag:other_proba})

    return preds


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": ssplit(data["full_conllu"])})


null = None
SAMPLE_DOC = """# s_type = frag
# newdoc id = AMALGUM_bio_aachen
# sent_id = AMALGUM_bio_aachen-1
# text = Master of the Aachen Altar
1       Master  master  NOUN    NN      Number=Sing     0       root    0:root  Discourse=preparation:1->4|Entity=(person-1
2       of      of      ADP     IN      _       5       case    5:case  _
3       the     the     DET     DT      Definite=Def|PronType=Art       5       det     5:det   Entity=(place-2
4       Aachen  Aachen  PROPN   NNP     Number=Sing     5       compound        5:compound      Entity=(place-3)
5       Altar   Altar   PROPN   NNP     Number=Sing     1       nmod    1:nmod:of       Entity=person-1)place-2)

# s_type = frag
# sent_id = AMALGUM_bio_aachen-2
# text = Aachen Altar : Centrepiece with the crucifixion of Christ
1       Aachen  Aachen  PROPN   NNP     Number=Sing     2       compound        2:compound      Discourse=preparation:2->3|Entity=(place-2(place-3)
2       Altar   Altar   PROPN   NNP     Number=Sing     0       root    0:root  Entity=place-2)
3       :       :       PUNCT   :       _       4       punct   4:punct _
4       Centrepiece     centrepiece     NOUN    NN      Number=Sing     2       parataxis       2:parataxis     Discourse=preparation:3->4|Entity=(object-4
5       with    with    ADP     IN      _       7       case    7:case  _
6       the     the     DET     DT      Definite=Def|PronType=Art       7       det     7:det   Entity=(object-5
7       crucifixion     crucifixion     NOUN    NN      Number=Sing     4       nmod    4:nmod:with     _
8       of      of      ADP     IN      _       9       case    9:case  _
9       Christ  Christ  PROPN   NNP     Number=Sing     7       nmod    7:nmod:of       Entity=object-4)object-5)(person-6)
"""


def debug():
    forms = []
    for form, probas in zip(forms, ssplit(SAMPLE_DOC)):
        print(form)
        print({x: y for x, y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    app.run(host="localhost", port=5556)
    #debug()


