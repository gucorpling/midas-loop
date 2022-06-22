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
  echo "Please place midas-loop.jar at ./midas-loop.jar"
  exit 2
fi
if [[ ! -f "config.edn" ]]; then 
  echo "Please supply a config at ./config.edn"
  exit 2
fi

# start services
echo "Starting services..." 
python services/sample_head.py     > /dev/null 2>/dev/null &
python services/sample_xpos.py     > /dev/null 2>/dev/null &
python services/sample_sentence.py > /dev/null 2>/dev/null &
java -Dconf=config.edn -jar midas-loop.jar "$@"
