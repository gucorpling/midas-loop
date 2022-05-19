import random
from collections.abc import Iterable
import json
from flask import Flask, request
from typing import List, Tuple
import conllu
import numpy as np
import spacy

from time import sleep

app = Flask(__name__)


def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def random_splits(conllu_sentence: str):
    """
    Given an English sentence in conllu format, return POS tag probabilities for each token.
    """
    sentence = conllu.parse(conllu_sentence)[0]
    sentence = [t for t in sentence if is_plain_token(t)]
    labels = []
    for i, t in enumerate(sentence):
        v = random.choice((16*[0.01]) + [0.12, 0.98, 0.9, 0.88])
        softmax = {"B": v, "O": 1. - v}
        labels.append(softmax)

    return labels


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": random_splits(data["conllu"])})


SAMPLE = """# sent_id = AMALGUM_reddit_beatty-47
# s_type = decl
# text = Its a money making model anymore.
1-2	Its	_	_	_	_	_	_	_	_
1	It	it	PRON	PRP	Case=Nom|Gender=Neut|Number=Sing|Person=3|PronType=Prs	6	nsubj	6:nsubj	Discourse=evaluation:86->85|Entity=(abstract-129)
2	s	be	AUX	VBZ	Mood=Ind|Number=Sing|Person=3|Tense=Pres|VerbForm=Fin	6	cop	6:cop	_
3	a	a	DET	DT	Definite=Ind|PronType=Art	6	det	6:det	Entity=(abstract-129
4	money	money	NOUN	NN	Number=Sing	6	compound	6:compound	Entity=(abstract-124)
5	making	make	VERB	VBG	VerbForm=Ger	6	compound	6:compound	_
6	model	model	NOUN	NN	Number=Sing	0	root	0:root	_
7	anymore	anymore	ADV	RB	Degree=Pos	6	advmod	6:advmod	Entity=abstract-129)|SpaceAfter=No
8	.	.	PUNCT	.	_	6	punct	6:punct	_
"""


def debug():
    tokens = conllu.parse(SAMPLE)[0]

    for token, probas in zip([t for t in tokens if is_plain_token(t)], tag_conllu(SAMPLE)):
        print(token["form"])
        print({x:y for x,y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    # debug()
    app.run(host="localhost", port=5556)
