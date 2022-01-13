#!/bin/bash
bash scripts/compile_docs.sh
git checkout gh-pages
mv target/book.html index.html
git add index.html
git commit -m 'update docs'
git push
git checkout master
