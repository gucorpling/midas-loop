import json
from flask import Flask, request
import random

app = Flask(__name__)


def get_head_probas(sentence):
    """
    Given an English sentence in conllu format, return POS tag probabilities for each token.
    """
    # "token-type" can be "super", "empty", or "token"--we want everything that's not a supertoken
    tokens = [t for t in sentence["sentence/tokens"] if t["token/token-type"] != "super"]
    token_forms = [t["token/form"]["form/value"] for t in tokens]
    potential_heads = ["root"] + [t["token/id"] for t in tokens]

    labels = []
    for token in tokens:
        probs = [random.random() for _ in potential_heads]
        probs = [x / sum(probs) for x in probs]
        labels.append({head: prob for head, prob in zip(potential_heads, probs)})

    return labels


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": get_head_probas(data["json"])})


SAMPLE_SENTENCE = """
{'sentence/id': '7cd41ba9-b2f0-43e6-bfa6-67a84d4576a5', 'sentence/conllu-metadata': [{'conllu-metadata/id': '41520c71-f494-42cb-94c1-f4554da7f03e', 'conllu-metadata/key': 's_type', 'conllu-metadata/value': 'frag'}, {'conllu-metadata/id': 'ebde9478-c2b0-4d88-9eac-6eeccc50cf79', 'conllu-metadata/key': 'newdoc id', 'conllu-metadata/value': 'AMALGUM_academic_adoption'}, {'conllu-metadata/id': '88d83d72-8540-4dda-8dc9-bbb62b518dc2', 'conllu-metadata/key': 'sent_id', 'conllu-metadata/value': 'AMALGUM_academic_adoption-1'}, {'conllu-metadata/id': '1ac53850-64c7-4f25-8573-e536c6d4867a', 'conllu-metadata/key': 'text', 'conllu-metadata/value': '6. Conclusions'}], 'sentence/tokens': [{'token/lemma': {'lemma/id': '7303ec56-7f98-4eee-a8f9-0825967b1e26', 'lemma/value': '6.'}, 'token/form': {'form/id': 'c43d243e-db35-4fc5-8f18-e56ddb761119', 'form/value': '6.'}, 'token/deps': [], 'token/token-type': 'token', 'token/feats': [], 'token/misc': [{'misc/id': '149bf599-b499-460d-82e0-e00a8da5c5be', 'misc/key': 'Discourse', 'misc/value': 'preparation:1->11'}], 'token/deprel': {'deprel/id': '988fde72-1bba-4a72-9202-ca2383138714', 'deprel/value': None}, 'token/xpos': {'xpos/id': '2420f8a3-1978-4751-b010-2b8f7d97c9c0', 'xpos/value': 'LS', 'xpos/probas': {'PDT': 6.215595504954763e-08, 'JJ': 8.307100642923615e-07, 'VBN': 9.325848623120692e-06, 'XX': 2.7594851417234167e-06, 'POS': 1.6235782140938682e-06, 'UH': 4.717720457847463e-06, 'ADD': 2.3960581074788934e-06, 'JJS': 8.332677680300549e-06, 'PRP$': 9.705374395707622e-05, '_SP': 3.380160151778e-08, 'SYM': 3.5893557651434094e-05, 'NNP': 0.00024372425104957074, 'WP$': 9.125631805773082e-08, 'VB': 7.399569312838139e-08, 'HYPH': 7.309034266711478e-09, 'CD': 8.249736856669188e-05, 'EX': 6.722148526705496e-08, '``': 3.789539186982438e-05, 'NNPS': 1.772038740455173e-05, 'RBS': 7.60539307975705e-07, 'LS': 0.9988992214202881, 'VBZ': 2.5020798943842237e-07, 'WDT': 1.795354728528764e-05, 'AFX': 1.8575816795873834e-07, 'WRB': 1.2307759789109696e-07, 'WP': 1.5288526356016519e-06, 'VBD': 2.5820495466177817e-06, '-LRB-': 3.077295218645304e-08, 'CC': 1.274101009585138e-06, 'TO': 1.896996036521159e-05, 'FW': 5.739284006267553e-06, 'NFP': 7.462745998054743e-05, ':': 6.941259016457479e-07, 'VBG': 5.7388895413623686e-08, 'RB': 9.76289243226347e-07, '.': 5.290456215334416e-07, ',': 8.81676669450826e-07, 'MD': 6.48225613986142e-05, 'RP': 1.4243436226024642e-06, 'PRP': 1.8356416831011302e-06, 'JJR': 5.747466730099404e-06, 'VBP': 4.235502046867623e-07, 'DT': 1.8707293065745034e-06, "''": 3.4851422014980926e-07, 'RBR': 4.586207751344773e-07, 'NN': 0.00018341369286645204, 'IN': 7.929786988825072e-06, 'NNS': 0.00012935063568875194, '$': 3.0926039471523836e-05, '-RRB-': 1.8651286737281225e-08}}, 'token/head': {'head/id': '6fb304be-e6a6-4cbf-ad2e-f317dfc83eb2', 'head/value': None}, 'token/id': '4e9c9555-4bc6-4b1a-8283-96187b5df23b', 'sentence/probas': {'B': 0.12, 'O': 0.88}, 'token/upos': {'upos/id': '1de054d8-d84a-40e7-92b0-d88a53afaec0', 'upos/value': 'X'}}]}"""


def debug():
    sentence = eval(SAMPLE_SENTENCE)
    res = get_head_probas(sentence)
    print(res)


if __name__ == "__main__":
    # debug()
    app.run(host="localhost", port=5557)
