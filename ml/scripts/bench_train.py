"""Measure raw training throughput of candidate models on GPU and CPU."""
import time

import torch
from ultralytics import YOLO


def total(o):
    if torch.is_tensor(o):
        return o.float().mean()
    if isinstance(o, dict):
        return sum(total(v) for v in o.values())
    if isinstance(o, (list, tuple)):
        return sum(total(v) for v in o)
    return 0


for name in ["weights/yolo11n-seg.pt", "weights/yolo26n-seg.pt"]:
    for dev, amp, bs in [("cuda", False, 8), ("cuda", True, 8), ("cpu", False, 4)]:
        m = YOLO(name).model.to(dev).train()
        for p in m.parameters():
            p.requires_grad_(True)
        x = torch.randn(bs, 3, 640, 640, device=dev)
        opt = torch.optim.SGD(m.parameters(), lr=1e-3)

        def step():
            with torch.autocast("cuda", enabled=amp and dev == "cuda", dtype=torch.float16):
                loss = total(m(x))
            opt.zero_grad()
            loss.backward()
            opt.step()

        try:
            t0 = time.time()
            step()
            if dev == "cuda":
                torch.cuda.synchronize()
            first = time.time() - t0
            t = time.time()
            for _ in range(5):
                step()
            if dev == "cuda":
                torch.cuda.synchronize()
            print(name, dev, "amp", amp, "first", round(first, 1), "img/s", round(5 * bs / (time.time() - t), 1), flush=True)
        except Exception as e:
            print(name, dev, amp, "ERR", repr(e)[:200], flush=True)
