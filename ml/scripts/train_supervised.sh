#!/bin/bash
# Resumes a run after crashes (dense tiles can exhaust GPU memory). Exits on completion, a stall or too many crashes.
# usage: train_supervised.sh NAME EPOCHS [BATCH] [DATA MODEL LR0]   (run from ml/; DATA and MODEL start a new run)
name=$1; epochs=$2; batch=${3:-8}; data=$4; model=$5; lr0=${6:-0.01}; csv=runs/$name/results.csv
export MIOPEN_FIND_MODE=FAST
for attempt in 1 2 3 4; do
  log=runs/logs/${name}_resume$attempt.log
  if [ -f runs/$name/weights/last.pt ]; then
    ./.venv-rocm/Scripts/python -u scripts/train.py x x --resume runs/$name/weights/last.pt --batch $batch > "$log" 2>&1 &
  else
    ./.venv-rocm/Scripts/python -u scripts/train.py "$data" $name --model "$model" --epochs $epochs --batch $batch --lr0 $lr0 > "$log" 2>&1 &
  fi
  pid=$!
  while kill -0 $pid 2>/dev/null; do
    sleep 30
    age=$(( $(date +%s) - $(date -r "$log" +%s) ))
    if [ $age -gt 420 ]; then echo "STALL attempt $attempt: log unchanged ${age}s at $(date '+%d %H:%M')"; exit 2; fi
  done
  wait $pid; code=$?
  done_epochs=$(( $(wc -l < "$csv" 2>/dev/null || echo 1) - 1 ))
  echo "attempt $attempt exit $code at $(date '+%d %H:%M'), epochs $done_epochs/$epochs"
  [ "$done_epochs" -ge "$epochs" ] && exit 0
  sleep 20
done
exit 1
