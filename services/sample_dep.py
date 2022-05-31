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
    return t["token-type"] != "token"


def is_ellipsis(t):
    return isinstance(t["id"], Iterable) and t["id"][1] == "."


def is_plain_token(t):
    return not isinstance(t["id"], Iterable)


def depparse_conllu(sentence: dict):
    """
    Given an English sentence in a dictionary representing midas-loop json format,
    return a fresh dependency parse with arc probabilities for each token.
    """
    # Supertokens are filtered out of the sentence since they are not valid targets for annotation.
    sentence_tokens = [t for t in sentence["tokens"] if not is_supertoken(t)]
    # Get a fresh parse
    forms = [t["form"]["value"] for t in sentence_tokens]
    id_mapping = {i+1:t["id"] for i, t in enumerate(sentence_tokens)}
    id_mapping[0] = 0
    # Get probabilities from the parser
    dataset = PARSER.predict([forms], prob=True)

    # float32 isn't JSON serializable by Python's `json` module--make it 64

    prob_matrix = dataset.sentences[0].probs.numpy()
    #column_values = dataset.sentences[0].values
    output = []
    for tok_idx, probas in enumerate(prob_matrix):
        #head, deprel = column_values[6][i], column_values[7][i]
        pred_proba = {}
        for head_idx, j in enumerate(probas):
            pred_proba[id_mapping[head_idx]] = np.float64(j)  # float32 isn't JSON serializable by Python's `json` module--make it 64
        output.append(pred_proba)

    return output


@app.route("/", methods=["POST"])
def get():
    data = request.json
    return json.dumps({"probabilities": depparse_conllu(data["json"])})


SAMPLE_SENTENCE = {"id":"e787690a-301a-4bd2-916b-bc7c74bbb869","conllu-metadata":[{"id":"929f82cf-c43b-4e4c-9aa7-a6ee14bc32d3","key":"newdoc id","value":"GUM_academic_art"},{"id":"f27286aa-7104-49cc-9c5a-b08bdcef10c5","key":"global.Entity","value":"GRP-etype-infstat-centering-minspan-link-identity"},{"id":"6ac4916e-1f20-4c4f-bdb7-eed77dda6ddf","key":"meta::dateCollected","value":"2017-09-13"},{"id":"fb99921b-5b9c-458d-99e1-a5f32fb8dd72","key":"meta::dateCreated","value":"2017-08-08"},{"id":"71e95b11-c7aa-45ee-b1f7-a6cbe4878502","key":"meta::dateModified","value":"2017-09-13"},{"id":"09935a91-35c1-4ad2-a5ec-8991d29c551c","key":"meta::sourceURL","value":"https://dh2017.adho.org/abstracts/333/333.pdf"},{"id":"4485c2df-be2a-42f8-8e8e-b6da0f7bcc79","key":"meta::speakerCount","value":"0"},{"id":"fba2500d-dc40-4cda-8fb9-920b9314b1b5","key":"meta::title","value":"Aesthetic Appreciation and Spanish Art: Insights from Eye-Tracking"},{"id":"4c4d1754-8595-4aaa-89b6-e759322421d1","key":"sent_id","value":"GUM_academic_art-1"},{"id":"13fc8da0-d505-4d32-93b2-2be5009ac5c9","key":"s_prominence","value":"2"},{"id":"613b544a-57fb-4f47-a490-22bebedfb08b","key":"s_type","value":"frag"},{"id":"53fbda5a-0d21-4cc2-897c-4addf71c8422","key":"transition","value":"establishment"},{"id":"5a3e4c9c-8e4f-4fdc-bdea-0c784097dfe0","key":"text","value":"Aesthetic Appreciation and Spanish Art:"},{"id":"2637cc74-5f81-40a5-8da2-cde171cd0cb9","key":"newpar_block","value":"head (2 s) | hi rend:::\"bold blue\" (2 s)"}],"tokens":[{"lemma":{"id":"f3710c19-d94d-437f-a6c8-7b3a8603e566","value":"aesthetic"},"head":{"id":"d9089c40-6ee9-445c-bd59-db5908c33519","value":"b1961e37-c6ba-4e9c-8681-f44f4b898298"},"upos":{"id":"7579d107-f36b-4efe-95bd-27e95bf1bce0","value":"ADJ"},"feats":[{"id":"2c91e3e4-f61f-409d-b267-481e074e9717","key":"Degree","value":"Pos"}],"id":"1d138566-7f11-4688-9b7b-4fa3588cee8c","token-type":"token","misc":[{"id":"831b81bc-defc-4004-8d6c-7348f82d678f","key":"Discourse","value":"organization-heading:1->57:8"},{"id":"20df59d1-3468-41e5-9055-ca62dddddc7f","key":"Entity","value":"(1-abstract-new-cf1-2-sgl"}],"deprel":{"id":"f6784c87-dc27-4263-b038-6828dd90d962","value":"amod"},"form":{"id":"ad5e2207-5de7-4832-b53f-c4ff54a695a9","value":"Aesthetic"},"xpos":{"id":"09d07787-92c1-4b3e-96b6-d9933762a7fb","value":"JJ"},"deps":[{"id":"90aebc7d-b6ac-406b-add8-fb9f457c7478","key":"b1961e37-c6ba-4e9c-8681-f44f4b898298","value":"amod"}]},{"lemma":{"id":"b0912b87-635c-471d-bbd8-b688d14cd460","value":"appreciation"},"head":{"id":"ec431107-5d78-4b9a-82a1-ff067b4b17f6","value":"root"},"upos":{"id":"a9edb169-5e36-470b-a1bf-003e8228e675","value":"NOUN"},"feats":[{"id":"5b9b0153-a36b-43af-b7c1-9bce3a0e9c8d","key":"Number","value":"Sing"}],"id":"b1961e37-c6ba-4e9c-8681-f44f4b898298","token-type":"token","misc":[{"id":"2991d34a-a462-4803-a115-45a638b11d54","key":"Entity","value":"1)"}],"deprel":{"id":"5d882126-7431-483d-8127-8f11fce60ea9","value":"root"},"form":{"id":"afb1aef5-4ff1-4214-b579-eb5de4ba11eb","value":"Appreciation"},"xpos":{"id":"cba156af-7465-49da-9a1d-ff40d7b477fa","value":"NN"},"deps":[{"id":"35bb6d65-adbf-40b8-b12e-4840a1ceaa79","key":"root","value":"root"}]},{"lemma":{"id":"3ed6de70-f6bf-48c3-83f3-06a9a43fa27e","value":"and"},"head":{"id":"ff023659-0d52-4bdd-82bf-85bfcebcbeee","value":"13031752-101a-4876-909d-09687437aba5"},"upos":{"id":"3a000026-912c-4c9e-b254-549cd29d27eb","value":"CCONJ"},"feats":[],"id":"8236e2fb-8d2e-4a03-9314-4fb70c345e16","token-type":"token","misc":[],"deprel":{"id":"37bb2041-80a0-46e3-8e47-9749d164f2db","value":"cc"},"form":{"id":"c6017ce5-ea89-4dc1-bb68-fb4cbbf3f08b","value":"and"},"xpos":{"id":"c07117d6-d041-4112-8593-d14ae2f88319","value":"CC"},"deps":[{"id":"92cb58f2-b5e5-4145-9a7d-96c8a8f47e57","key":"13031752-101a-4876-909d-09687437aba5","value":"cc"}]},{"lemma":{"id":"bc83f624-d4f5-4753-bdd8-0cc39b3532ba","value":"Spanish"},"head":{"id":"c3b1e859-79cd-4e6f-8c0a-7382958d6e02","value":"13031752-101a-4876-909d-09687437aba5"},"upos":{"id":"950d4169-bad0-4fe9-b034-ea188710efed","value":"ADJ"},"feats":[{"id":"8aba512f-29fe-4b23-878a-8439e45cf6f7","key":"Degree","value":"Pos"}],"id":"77815a3d-512e-44eb-9228-2838c2400f5e","token-type":"token","misc":[{"id":"2d5784d6-7636-4a24-9415-0fd5273bac68","key":"Entity","value":"(2-abstract-new-cf2-2-sgl"}],"deprel":{"id":"6728aa6d-317f-4a1b-b4d7-5bd27f68f597","value":"amod"},"form":{"id":"5f420ba2-31aa-4524-bf3c-71c17a2386ed","value":"Spanish"},"xpos":{"id":"32ef44eb-51f0-42c0-941f-2ac091a08725","value":"JJ"},"deps":[{"id":"65d75fd0-f40d-461d-a773-cf650fe77ebd","key":"13031752-101a-4876-909d-09687437aba5","value":"amod"}]},{"lemma":{"id":"97b752f4-fda2-44e3-a719-117315bac841","value":"art"},"head":{"id":"5c55b90c-b264-470b-983b-5cf70de616d2","value":"b1961e37-c6ba-4e9c-8681-f44f4b898298"},"upos":{"id":"e7747d47-fde5-4ac5-9e5d-a35aff094936","value":"NOUN"},"feats":[{"id":"772d81ce-f0a9-4f74-a8eb-1ba0ea3471e8","key":"Number","value":"Sing"}],"id":"13031752-101a-4876-909d-09687437aba5","token-type":"token","misc":[{"id":"bf911b94-0163-44b5-9473-f00c90dd0138","key":"Entity","value":"2)"},{"id":"53b0ec93-8d06-4064-bd2a-e7a758512c0b","key":"SpaceAfter","value":"No"}],"deprel":{"id":"d65990a0-8862-4cc0-94ed-b074d5eff8bf","value":"conj"},"form":{"id":"c7ab19da-a64e-43c9-acdd-4b611a9d05d8","value":"Art"},"xpos":{"id":"fcb61224-c4b0-4670-8cd0-c09d05f852ce","value":"NN"},"deps":[{"id":"181ef1e4-8290-4edc-881e-19babcf98205","key":"b1961e37-c6ba-4e9c-8681-f44f4b898298","value":"conj:and"}]},{"lemma":{"id":"d8274de1-c42e-4aab-88c6-b1902b41299d","value":":"},"head":{"id":"1268c9dc-53d1-4568-8371-aecfc3a8a26a","value":"b1961e37-c6ba-4e9c-8681-f44f4b898298"},"upos":{"id":"eb11e322-bf52-456f-93f4-098eac113898","value":"PUNCT"},"feats":[],"id":"e09f6390-1937-4de4-83e5-6c9fe5f2ff44","token-type":"token","misc":[],"deprel":{"id":"54f51b55-d366-4306-89f7-436d00efdfb9","value":"punct"},"form":{"id":"6bb9f2fb-7649-4ce4-aa5b-e176b009f944","value":":"},"xpos":{"id":"026db4c5-c46d-4eb4-bf72-b04658b45df7","value":":"},"deps":[{"id":"0788f186-af9b-4515-816d-193d44d65054","key":"b1961e37-c6ba-4e9c-8681-f44f4b898298","value":"punct"}]}]}


def debug():
    sentence = SAMPLE_SENTENCE
    tokens = [t for t in sentence["tokens"]]
    forms = [t["form"]["value"] for t in tokens]

    for form, probas in zip(forms, depparse_conllu(sentence)):
        print(form)
        print({x:y for x,y in probas.items() if y > 0.0001})
        print()


if __name__ == "__main__":
    app.run(host="localhost", port=5555)
    #debug()