from collections.abc import Iterable
import json
from flask import Flask, request
from typing import List, Tuple
import conllu
import numpy as np
from diaparser.parsers import Parser

from time import sleep

app = Flask(__name__)

PARSER = Parser.load('en_ewt-electra')


def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def depparse_conllu(conllu_sentence: str):
    """
    Given an English sentence in conllu format, return a fresh dependency parse with arc probabilities for each token.
    """
    # Parse the string and take its first sentence (the string only has one sentence)
    sentence = conllu.parse(conllu_sentence)[0]
    # Supertokens are filtered out of the sentence since they are not valid targets for annotation.
    sentence = [t for t in sentence if not is_supertoken(t)]
    # Get a fresh parse
    tokens = [t["form"] for t in sentence]
    dataset = PARSER.predict([tokens], prob=True)

    # Get probabilities from the parser
    # float32 isn't JSON serializable by Python's `json` module--make it 64

    prob_matrix = dataset.sentences[0].probs.numpy()
    column_values = dataset.sentences[0].values
    with_labels = []
    for i, probas in enumerate(prob_matrix):
        head, deprel = column_values[6][i], column_values[7][i]
        proba = np.float64(max(probas))  # float32 isn't JSON serializable by Python's `json` module--make it 64
        pred = {(head,deprel): proba}
        with_labels.append(pred)

    return with_labels


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": depparse_conllu(data["conllu"])})


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

    for token, probas in zip(tokens, depparse_conllu(SAMPLE)):
        print(token["form"])
        print({x:y for x,y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    app.run(host="localhost", port=5555)
    #debug()