# Hold detection model

How the on-device hold segmenter (`app/src/main/assets/models/holds.onnx`) is built and measured.
The app loads it in `YoloHoldDetector.kt`; `yolo_infer.py` is the Python twin of that class.

## Pipeline

| Step | Script | Output (in `datasets/` or `runs/`, not in git) |
|---|---|---|
| Auto-label photos: teacher boxes, then SAM outlines | `pseudo_label.py` | `pseudo/NAME/*.json` |
| Remove labels on people | `drop_people.py` | same files, edited in place |
| Harvest free-licence wall photos | `fetch_commons.py` | `commons/`, with `manifest.json` |
| Plywood scenes from real hold cut-outs | `composite.py` | `composite_train/`, `composite_test/` |
| Phone degradations and tiles | `phone_aug.py`, `build_dataset.py` | `yolo_eval/`, `yolo_final/` |
| Training | `train.py` | `runs/NAME/` |
| OpenCV vs YOLO comparison | `benchmark.py`, `opencv_baseline.py` | `runs/benchmark.json` |

Run everything from `ml/scripts`, except `train.py`, which runs from `ml`. Stored image paths are relative to `ml/scripts`.

## Environment (AMD, Windows)

- `.venv-rocm`: Python 3.12 with AMD's official wheels from `repo.radeon.com/rocm/windows/rocm-rel-7.2.1/`.
  The Radeon 880M (gfx1150) appears as CUDA device 0. Set `MIOPEN_FIND_MODE=FAST`.
- There is no pip in that environment: `./.venv-tools/Scripts/uv pip install --python .venv-rocm/Scripts/python.exe ...`
- ONNX inference on the same GPU uses `onnxruntime-directml`.
- The first training step compiles kernels for about 150 s; each new tensor shape costs about 35 s.
- Real training speed is about 5-7 tiles/s. Dense walls cost far more than a synthetic speed test suggests.
- Any script that starts data-loader workers needs an `if __name__ == "__main__"` guard. Without it, Windows
  workers re-run the script and the job hangs without exiting.

## Data

| Source | Use | Licence |
|---|---|---|
| Kaggle `tomasslama/indoor-climbing-gym-hold-segmentation` (gyms bh, sm, bh-phone) | Hand polygons, photos | CC BY-SA 4.0 |
| Wikimedia Commons climbing categories | Auto-labelled photos | Per file, see `datasets/commons/manifest.json` (CC0, PD, CC BY, CC BY-SA) |
| Teacher `jwlarocque/yolov8n-freeclimbs-detect-2` | Boxes for auto-labelling only | AGPL-3.0 |
| `facebook/sam-vit-base` | Outlines for auto-labelling only | Apache-2.0 |
| Ultralytics `yolo11n-seg` | Starting weights and trainer | AGPL-3.0 |

Filters that matter:

- Gold images with fewer than 30 holds and Commons images with fewer than 30 auto-labels are left out. Those pictures
  are only partly labelled, and unlabelled holds teach the model to ignore holds.
- Labels mostly on a person are removed (the teacher marks helmets and heads as holds).

## Evaluation rules

- The eval model never sees gym `sm`, any `bh-phone` photo, or the `sm`/phone cut-outs used for the test composites.
- Test set: 4 real gold photos (sm 108, 082; bh-phone 126, 182) and 16 plywood composites, 1885 holds in total.
- Model choices use validation tiles only, never the test set.
- Metric: one-to-one mask matching at IoU 0.5. Real photos are scored inside the labelled area, and detections on
  volumes are not penalised.
- The shipping model is then fine-tuned on everything, so the published numbers belong to the eval model.

Limits of these numbers: only 4 real test photos, no labelled real plywood photos, and the stress conditions come from
the same family of degradations used for training. Real photos of our own wall are still the most valuable next data.

## Model choice

Eight epochs on half the tiles, scored on validation tiles:

| | Mask precision | Mask recall | mAP50 | mAP50-95 | Training | CPU per tile |
|---|---|---|---|---|---|---|
| yolo11n-seg | 0.946 | 0.898 | 0.948 | 0.733 | 48 min | 61 ms |
| yolo26n-seg | 0.929 | 0.904 | 0.948 | 0.732 | 64 min | 74 ms |

Accuracy was a tie on 72 tiles, so yolo11n-seg was chosen for speed.

`mask_ratio=2` is deliberate: this Ultralytics version upsamples the prototypes to the target size in the loss, so it
really trains on finer outlines, at a real cost in speed.

## Licensing

Ultralytics and the teacher are AGPL-3.0. That is fine for personal use, but distributing a closed-source app needs an
Ultralytics licence or a different architecture. Dataset attribution (BY and BY-SA) must be kept with any release.
