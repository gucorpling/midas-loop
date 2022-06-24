"""
Note: this module requires a privately trained model and some very specific package versions.
In order to run it, first install the packages:

    pip install "torch<1.6" "flair==0.6.1" "transformers==3.5.1" "flask" "protobuf<3.21"

Then download our model:

    https://drive.google.com/file/d/1UfWKc-Qg122xJGHcSym-jRVFyffuufXQ/view?usp=sharing

and place it in the working directory you intend to run this script from.
"""
from collections.abc import Iterable
import json, sys
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

# Load splitter model
model = SequenceTagger.load("flair-splitter-sent.pt")

cache = {}

def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def ssplit(full_conllu: str, sent_conllu: str, span_size: int=20, stride_size: int=10, sentence_index: int=-1):
    """
    Given a document in a dictionary representing midas-loop json format,
    return probabilities that each token begins a new sentence (B) or not (O).
    """
    # Supertokens are filtered out of the sentence since they are not valid targets for annotation.
    toks = []
    ids = []
    parsed = conllu.parse(full_conllu)
    target_begin = -1
    target_end = -1
    toknum = 0

    global cache
    if len(cache) > 1000:  # Prevent possible memory leak if service is run on thousands of documents
        for key in cache:
            del cache[key]
            break
    #sys.stderr.write("target:\n")
    #sys.stderr.write(sent_conllu + "\n")

    # TODO: remove hack for detecting sentence token offset position in doc conllu
    for i, sent in enumerate(parsed):
        if target_begin != -1 and target_end == -1:  # Beginning already set and new sent has started
            target_end = toknum
        if sentence_index > -1:  # System specified ordinal sentence index
            if i == sentence_index:
                target_begin = toknum
        else:
            if sent.metadata["sent_id"] + "\n" in sent_conllu:  # This is the target sent
                #sys.stderr.write("Found ID "+ sent.metadata["sent_id"] + "\n")
                target_begin = toknum
        for tok in sent:
            if not is_supertoken(tok) and not is_ellipsis(tok):
                toknum += 1
                toks.append(tok["form"])
                ids.append(tok["id"])

    if target_end == -1:
        target_end = toknum

    # Get a fresh prediction
    final_mapping = {}  # Map each contextualized token to its (sequence_number, position)
    spans = []  # Holds flair Sentence objects for labeling

    tok_string = " ".join(toks)
    if tok_string in cache:
        preds = cache[tok_string]
    else:
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
                pred_tag = spans[snum].tokens[position].tags["ner"].value.replace("-SENT","")
                pred_proba = spans[snum].tokens[position].tags["ner"].score
            else:
                pred_tag = spans[snum].tokens[position].labels[0].value.replace("-SENT","")
                pred_proba = spans[snum].tokens[position].labels[0].score
            other_tag = "B" if pred_tag == "O" else "O"
            other_proba = 1-pred_proba
            tid = ids[idx]

            #preds.append({tid:{pred_tag:pred_proba,other_tag:other_proba}})
            preds.append({pred_tag:pred_proba,other_tag:other_proba})
        cache[tok_string] = preds

    #sys.stderr.write(str(target_begin) + "\n")
    #sys.stderr.write(str(target_end)+ "\n")
    return preds[target_begin:target_end]


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": ssplit(data["full_conllu"],data["conllu"],sentence_index=data["sentence_index"])})


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

SAMPLE_SENT = """# newdoc id = AMALGUM_bio_aachen
# sent_id = AMALGUM_bio_aachen-1
# text = Master of the Aachen Altar
1       Master  master  NOUN    NN      Number=Sing     0       root    0:root  Discourse=preparation:1->4|Entity=(person-1
2       of      of      ADP     IN      _       5       case    5:case  _
3       the     the     DET     DT      Definite=Def|PronType=Art       5       det     5:det   Entity=(place-2
4       Aachen  Aachen  PROPN   NNP     Number=Sing     5       compound        5:compound      Entity=(place-3)
5       Altar   Altar   PROPN   NNP     Number=Sing     1       nmod    1:nmod:of       Entity=person-1)place-2)"""

def debug():
    forms = ["Master","of","the","Aachen","Altar"]
    for form, probas in zip(forms, ssplit(SAMPLE_DOC, SAMPLE_SENT)):
        print(form)
        print({x: y for x, y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    app.run(host="localhost", port=5556)
    #debug()


