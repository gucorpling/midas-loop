These are sample [NLP services](https://gucorpling.github.io/midas-loop/#_nlp_services) for English.
If you are working with an English corpus, you may use them directly as you like.
For other languages, you will need to either modify them or implement your own services using the existing services for reference.

# Setup
For setup on these services, do the following:

1. Download our sentence splitting model from [here](https://drive.google.com/file/d/1UfWKc-Qg122xJGHcSym-jRVFyffuufXQ/view?usp=sharing)

2. Install dependencies
```
pip install diaparser spacy
pip install "torch<1.6" "flair==0.6.1" "transformers==3.5.1" "flask" "protobuf<3.21"
python -m spacy download en_core_web_sm
```
