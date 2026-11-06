"""Trains the hold segmenter on the tile dataset. Runs on the AMD iGPU through ROCm (device 0).

usage: train.py DATA.yaml NAME --model weights/yolo11n-seg.pt --epochs 40 [--resume last.pt --batch 8]
"""
import argparse
import pathlib

from ultralytics import YOLO


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("name")
    ap.add_argument("--model", default="weights/yolo11n-seg.pt")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--lr0", type=float, default=0.01)
    ap.add_argument("--fraction", type=float, default=1.0)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--resume", help="last.pt of an interrupted run; keeps its settings except batch and workers")
    a = ap.parse_args()

    root = pathlib.Path(__file__).resolve().parent.parent
    if a.resume:
        YOLO(a.resume).train(resume=True, batch=a.batch, workers=a.workers)
        return
    YOLO(a.model).train(
        data=a.data, name=a.name, project=str(root / "runs"), exist_ok=True,
        imgsz=640, epochs=a.epochs, batch=a.batch, device=0, workers=a.workers, amp=True, fraction=a.fraction,
        optimizer="AdamW", lr0=a.lr0 * 0.1, lrf=0.05, cos_lr=True, warmup_epochs=1, patience=0, seed=1,
        single_cls=True, overlap_mask=True, mask_ratio=2, max_det=300, plots=True, save_period=5,
        # Tiles already carry phone degradations; online augmentation only adds cheap geometry and mild colour.
        hsv_h=0.015, hsv_s=0.3, hsv_v=0.2, degrees=10, translate=0.1, scale=0.3, shear=2, perspective=0.0002,
        fliplr=0.5, flipud=0.1, mosaic=0.5, close_mosaic=5, mixup=0.0, copy_paste=0.0, erasing=0.0,
    )


# Required on Windows: data-loader workers are spawned and re-import this file.
if __name__ == "__main__":
    main()
