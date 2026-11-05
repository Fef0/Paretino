"""Box-prompted SAM on image windows: turns teacher boxes into hold masks."""
import cv2
import numpy as np
import torch
from transformers import SamModel, SamProcessor


class BoxSam:
    def __init__(self, name="facebook/sam-vit-base", device="cuda", window=1024):
        self.processor = SamProcessor.from_pretrained(name)
        self.model = SamModel.from_pretrained(name).to(device).eval()
        self.device, self.window = device, window

    @torch.no_grad()
    def masks(self, bgr, boxes):
        """boxes Nx4 (image px) -> list of (polygon Nx2 or None, quality). One encoder pass serves every box
        inside a window, so small holds are seen near native resolution."""
        out = [(None, 0.0)] * len(boxes)
        h, w = bgr.shape[:2]
        todo = set(range(len(boxes)))
        side0 = self.window
        cols = max(1, int(np.ceil((w - side0 * 0.12) / (side0 * 0.88))))
        rows = max(1, int(np.ceil((h - side0 * 0.12) / (side0 * 0.88))))
        grid = [(int(c * max(w - side0, 0) / max(cols - 1, 1)), int(r * max(h - side0, 0) / max(rows - 1, 1)))
                for r in range(rows) for c in range(cols)]
        while todo:
            if grid:
                # Overlapping fixed windows take most boxes with few encoder passes.
                x0, y0 = grid.pop()
                x1, y1 = min(x0 + side0, w), min(y0 + side0, h)
                inside = [j for j in todo if boxes[j][0] >= x0 and boxes[j][1] >= y0 and boxes[j][2] <= x1 and boxes[j][3] <= y1]
                if not inside:
                    continue
            else:
                # Leftovers (big holds, window borders): a window centred on the box.
                i = min(todo)
                b = boxes[i]
                side = int(max(side0, 1.6 * max(b[2] - b[0], b[3] - b[1])))
                x0 = int(np.clip((b[0] + b[2]) / 2 - side / 2, 0, max(w - side, 0)))
                y0 = int(np.clip((b[1] + b[3]) / 2 - side / 2, 0, max(h - side, 0)))
                x1, y1 = min(x0 + side, w), min(y0 + side, h)
                inside = [j for j in todo if boxes[j][0] >= x0 and boxes[j][1] >= y0 and boxes[j][2] <= x1 and boxes[j][3] <= y1]
                if i not in inside:
                    inside.append(i)
            crop = np.ascontiguousarray(bgr[y0:y1, x0:x1, ::-1])
            scale = 1024 / max(crop.shape[:2])  # the processor resizes the long side to 1024
            pixels = self.processor(images=crop, return_tensors="pt")["pixel_values"].to(self.device)
            with torch.autocast("cuda", dtype=torch.float16):
                emb = self.model.get_image_embeddings(pixels)
                for k in range(0, len(inside), 32):
                    part = inside[k:k + 32]
                    rel = np.array([boxes[j][:4] for j in part], np.float32) - (x0, y0, x0, y0)
                    # Always 32 boxes: every new batch shape costs a ~35 s kernel compile on ROCm.
                    padded = np.zeros((32, 4), np.float32)
                    padded[:len(rel)] = rel * scale
                    padded[len(rel):] = padded[0]
                    prompt = torch.from_numpy(padded)[None].to(self.device)
                    o = self.model(image_embeddings=emb, input_boxes=prompt, multimask_output=True)
                    low = o.pred_masks[0].float().cpu().numpy()  # boxes x 3 x 256 x 256 on the padded 1024 square
                    scores = o.iou_scores[0].float().cpu().numpy()
                    for n, j in enumerate(part):
                        out[j] = self._pick(low[n], scores[n], rel[n], scale, (x0, y0))
            todo -= set(inside)
        return out

    @staticmethod
    def _pick(low, scores, box, scale, offset):
        """Among SAM's three masks prefer the one that fills the box: rejects whole-wall and sub-part masks.
        Works on a region around the box only, upscaling just that patch of the 256-px logits."""
        cell = 4 / scale  # crop pixels per logit cell
        pad = 0.35 * max(box[2] - box[0], box[3] - box[1]) + 2 * cell
        lx0, ly0 = max(int((box[0] - pad) / cell), 0), max(int((box[1] - pad) / cell), 0)
        lx1, ly1 = min(int((box[2] + pad) / cell) + 2, 256), min(int((box[3] + pad) / cell) + 2, 256)
        if lx1 - lx0 < 2 or ly1 - ly0 < 2:
            return None, 0.0
        size = (max(int((lx1 - lx0) * cell), 2), max(int((ly1 - ly0) * cell), 2))
        origin = np.array([lx0 * cell, ly0 * cell], np.float32)
        best, best_v = None, -1.0
        for logits, score in zip(low, scores):
            mask = (cv2.resize(logits[ly0:ly1, lx0:lx1], size, interpolation=cv2.INTER_LINEAR) > 0).astype(np.uint8)
            ys, xs = np.nonzero(mask)
            if len(xs) < 12:
                continue
            mb = (xs.min() + origin[0], ys.min() + origin[1], xs.max() + origin[0], ys.max() + origin[1])
            inter = max(0, min(mb[2], box[2]) - max(mb[0], box[0])) * max(0, min(mb[3], box[3]) - max(mb[1], box[1]))
            union = (mb[2] - mb[0]) * (mb[3] - mb[1]) + (box[2] - box[0]) * (box[3] - box[1]) - inter
            v = float(score) * inter / max(union, 1)
            if v > best_v:
                best_v, best = v, mask
        if best is None:
            return None, 0.0
        cs, _ = cv2.findContours(best, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        c = max(cs, key=cv2.contourArea)
        return c.reshape(-1, 2).astype(np.float32) + origin + np.array(offset, np.float32), best_v
