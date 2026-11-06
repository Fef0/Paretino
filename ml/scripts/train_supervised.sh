#!/bin/bash
# Resumes a run after crashes (dense tiles can exhaust GPU memory). Exits on completion, a stall or too many crashes.
# usage: train_supervised.sh NAME EPOCHS [BATCH]   (run from ml/)
name=$1; epochs=$2; batch=${3:-8}; csv=runs/$name/results.csv
export MIOPEN_FIND_MODE=FAST
for attempt in 1 2 3 4; do
  log=runs/logs/${name}_resume$attempt.log
  ./.venv-rocm/Scripts/python -u scripts/train.py x x --resume runs/$name/weights/last.pt --batch $batch > "$log" 2>&1 &
  pid=$!
  while kill -0 $pid 2>/dev/null; do
    sleep 30
    age=$(( $(date +%s) - $(date -r "$log" +%s) ))
    if [ $age -gt 420 ]; then echo "STALL attempt $attempt: log unchanged ${age}s at $(date '+%d %H:%M')"; exit 2; fi
  done
  wait $pid; code=$?
  done_epochs=$(($(wc -l < "$csv")-1))
  echo "attempt $attempt exit $code at $(date '+%d %H:%M'), epochs $done_epochs/$epochs"
  [ "$done_epochs" -ge "$epochs" ] && exit 0
  sleep 20
done
exit 1
