# Introduction

**Midas Loop** is a web application for taking [Universal Dependencies](https://universaldependencies.org/) corpora and improving the quality of their annotations.
For more information on motivation, functionality, and supported workflows, please see [our paper](https://gucorpling.github.io/midas-loop/midas_loop.pdf).

See [documentation](https://gucorpling.github.io/midas-loop) and our [paper](https://gucorpling.github.io/midas-loop/midas_loop.pdf) for more details.

# Quick Start

To quickly get a server up and running:

1. Clone the repository.

```bash
git clone https://github.com/gucorpling/midas-loop.git
cd midas-loop
```   

2. You may now interact with Midas Loop by invoking the script `scripts/midas-loop.sh`.
   First, invoke it with no arguments to download the JAR and create a configuration file.
   
```bash
bash scripts/midas-loop.sh
```
   
3. Now, we will import a sample document into Midas Loop.
   
```bash
wget https://raw.githubusercontent.com/gucorpling/amalgum/master/amalgum/fiction/dep/AMALGUM_fiction_amontillado.conllu
bash scripts/midas-loop.sh import AMALGUM_fiction_amontillado.conllu
```

4. Now generate a token for yourself.

```bash
bash scripts/midas-loop.sh token add --name YOURNAME --email YOUREMAIL --quality gold
```

5. Start the server, and enter the token you generated for yourself.

```bash
bash scripts/midas-loop.sh run
```

6. If you wish to use Midas Loop with NLP services, refer to our [instructions for setup](services/README.md). 
After the services are set up, modify your `config.edn` to look like below and reimport any documents you imported.

```edn
{:port 3000,
 :midas-loop.server.xtdb/config {:main-db-dir "xtdb_data"},
 :midas-loop.server.tokens/config {:token-db-dir "xtdb_token_data"},
 :nlp-services [{:type :http :anno-type :xpos :url "http://localhost:5555"}
                {:type :http :anno-type :sentence :url "http://localhost:5556"}
                {:type :http :anno-type :head :url "http://localhost:5557"}]}
```

# Citation
If you use Midas Loop in your research, please cite it like so (URL to be added soon):

```
@InProceedings{gessler-etal-2022-midas-loop,
  author    = {Luke Gessler and Lauren Levine and Amir Zeldes},
  booktitle = {Proceedings of LREC 2022},
  title     = {{M}idas {L}oop: Prioritized {H}uman-in-the-{L}oop Annotation for Large Scale Multilayer Data},
  year      = {2022},
  address   = {Marseille, France}
}
```
