from collections.abc import Iterable
import json
from flask import Flask, request
from typing import List, Tuple
import conllu
import numpy as np
import spacy

from time import sleep

app = Flask(__name__)

class WhitespaceTokenizer:
    def __init__(self, vocab):
        self.vocab = vocab

    def __call__(self, text):
        words = text.split(" ")
        spaces = [True] * len(words)
        # Avoid zero-length tokens
        for i, word in enumerate(words):
            if word == "":
                words[i] = " "
                spaces[i] = False
        # Remove the final trailing space
        if words[-1] == " ":
            words = words[0:-1]
            spaces = spaces[0:-1]
        else:
           spaces[-1] = False

        return spacy.tokens.Doc(self.vocab, words=words, spaces=spaces)


MODEL = spacy.load("en_core_web_sm")
TAGGER = MODEL.get_pipe("tagger")
MODEL.tokenizer = WhitespaceTokenizer(MODEL.vocab)

def is_supertoken(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "-"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def tag_conllu(conllu_sentence: str):
    """
    Given an English sentence in conllu format, return POS tag probabilities for each token.
    """
    # Parse the string and take its first sentence (the string only has one sentence)
    sentence = conllu.parse(conllu_sentence)[0]
    # Supertokens are filtered out of the sentence since they are not valid targets for annotation.
    sentence = [t for t in sentence if not is_supertoken(t)]
    # Make a spacy doc from the token forms
    #doc = spacy.tokens.Doc(MODEL.vocab, words=[t["form"] for t in sentence], spaces=spaces)
    doc = MODEL(" ".join([t["form"] for t in sentence]))

    # Get probabilities from the tagger
    # float32 isn't JSON serializable by Python's `json` module--make it 64
    token_probas = np.float64(TAGGER.model.predict([doc])[0])
    normalized_token_probas = [np.interp(a, (a.min(), a.max()), (0, 1)) for a in token_probas]

    # Merge probabilities with the class labels
    labels = TAGGER.labels

    # Make label-proba pairs and sort them
    with_labels = [
        {label: proba for label, proba in zip(labels, probas)}
        for probas in normalized_token_probas
    ]
    return with_labels


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": tag_conllu(data["conllu"])})


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

    for token, probas in zip(tokens, tag_conllu(SAMPLE)):
        print(token["form"])
        print({x:y for x,y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    app.run(host="localhost", port=5555)
    #debug()
