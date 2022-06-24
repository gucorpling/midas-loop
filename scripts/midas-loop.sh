#!/bin/bash
# Set things up so services exit whenever this script exits
cleanup() {
    # kill all processes whose parent is this process
    pkill -P $$
}
for sig in INT QUIT HUP TERM; do
  trap "
    cleanup
    trap - $sig EXIT
    kill -s $sig "'"$$"' "$sig"
done
trap cleanup EXIT

# Check existence of files
if [[ ! -f "midas-loop.jar" ]]; then 
  echo "Did not find midas-loop.jar. Downloading at ./midas-loop.jar"
  curl -L "https://github.com/gucorpling/midas-loop/releases/download/v0.0.1/midas-loop.jar" -o "midas-loop.jar"
fi
if [[ ! -f "config.edn" ]]; then 
  echo "Did not find config.edn. Downloading at ./config.edn"
  curl -L "https://raw.githubusercontent.com/gucorpling/midas-loop/v0.0.1/env/prod/resources/config.edn" -o "config.edn"
fi

# start services
echo "Starting services..." 
python services/sample_head.py     > /dev/null 2>/dev/null &
python services/sample_xpos.py     > /dev/null 2>/dev/null &
python services/sample_sentence.py > /dev/null 2>/dev/null &
java -Dconf=config.edn -jar midas-loop.jar "$@"
