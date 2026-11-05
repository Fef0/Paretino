# Paretino: to do

## 1. Visual redesign (first priority)
The current UI is stock Material 3 with default components and reads as dated.
- [ ] Choose a design direction: a visual identity (type, colour, shape, motion), with the wall photo as the hero on every screen
- [ ] Home: bigger, edge-to-edge path cards; grade shown as a strong colour badge; less chrome; rethink the filter chip row
- [ ] Path detail: immersive full-bleed photo, collapsing header, better info hierarchy, nicer "Log ascent" bottom bar
- [ ] Path editor: floating role palette over the photo instead of a bottom sheet strip; clearer selected/unselected hold styles
- [ ] Master flow: step indicator (Photo → Corners → Holds → Save); clean up the progress overlay
- [ ] Logbook: real stats design (pyramid, progress over time)
- [ ] Custom typography and an icon set; fix the dark theme; empty-state illustrations
- [ ] Small fixes found while testing:
  - [ ] the "Active" chip on master cards is unreadable over the photo (give it a solid background)
  - [ ] the extended FABs ("New master", "New path") are missing from the accessibility tree (uiautomator can't see them): check TalkBack

## 2. Hold detection: test in harsh conditions, then probably replace OpenCV
OpenCV results so far (synthetic plywood walls, 7 lighting conditions): recall@IoU 0.5 ≈ 0.80, mean outline IoU ≈ 0.78. It struggles with: holds touching each other, white/grey holds on light wood, hard sun-stripe edges (false slivers), wooden holds, chalk and rubber marks.
- [ ] Collect **real photos of our wall** (20–50, different days, times and lighting; phone at different angles). This is the most important input
- [ ] Build a test set: label hold outlines on ~20 of them (CVAT / Roboflow / Label Studio)
- [ ] Measure the current OpenCV detector on that set (baseline)
- [ ] On-device model: **YOLO instance segmentation** (e.g. YOLO11n-seg / YOLOv8n-seg), exported to TFLite/LiteRT, int8 or fp16, 640 px input
  - [ ] start from a public climbing-hold dataset, fine-tune on our labelled photos
  - [ ] segmentation masks → polygons (outlines that follow the hold, as required)
  - [ ] new `TfliteHoldDetector` implementing `HoldDetector`; swap the Hilt binding in `di/AppModule.kt` (nothing else changes)
  - [ ] keep OpenCV for tap-to-add (flood fill) and wall-corner detection, or replace those too
  - [ ] check speed on a mid-range phone (target < 1.5 s) and APK size
- [ ] Possible hybrid: YOLO for detection, OpenCV to refine outlines

## 3. Unfinished from the current session
- [ ] Rebuild and re-test: the share image now also draws custom hold labels ("1", "L hand")
- [x] Detector experiment "hard zone switch" (each side of a sun stripe gets its own background): precision 0.949 → 0.953, recall unchanged. A small gain, not worth porting before the YOLO decision
- [ ] Finish the emulator test of **Master diff** (two masters exist: "Set of Nov 1" and "Autumn re-set": compare them, check added/removed/changed and the affected paths)
- [ ] Emulator tests not done yet: anchor positions grid + LED numbering, backup export/restore, V-scale switch, second climber, edit/duplicate/delete path, mirror, edit holds of an existing master, camera capture
- [ ] Release build: R8 rules check, APK size (OpenCV ≈ 100 MB debug), app signing

## 4. Later
- [ ] ESP32: BLE link (`BleEsp32Exporter` stub + `Esp32Protocol` frame are ready), pairing screen, "light on wall"
- [ ] Share paths via QR code between phones
- [ ] Automatic alignment between master photos (ArUco markers on the wall corners)
