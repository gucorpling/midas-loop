asciidoctor -o target/book.html -b html5 -r asciidoctor-diagram docs/book.adoc
asciidoctor-pdf -o target/book.pdf -b pdf -r asciidoctor-diagram docs/book.adoc
