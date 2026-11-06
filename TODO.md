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

## 2. Hold detection
YOLO is in the app on branch `feature/yolo-hold-detection`; pipeline and results are in `ml/README.md`. On held-out photos across 18 lighting and lens conditions, mean F1 went from 0.31 to 0.93 (real) and from 0.49 to 0.97 (plywood composites). Those figures come from the eval model `eval_v1`; the app ships `final_v1`, which also trained on the test photos and so has no score of its own.

Done:
- [x] Dataset: hand polygons, auto-labelled gym and Commons photos, plywood composites, phone degradations
- [x] Baseline and comparison: OpenCV vs YOLO on the same 20 test images
- [x] On-device model: yolo11n-seg through ONNX Runtime (not TFLite), tiled, with outlines
- [x] `YoloHoldDetector` bound in `di/AppModule.kt`
- [x] OpenCV removed from the app; mask geometry is plain Kotlin in `vision/MaskGeometry.kt`, and the Python pipeline still uses cv2
- [x] Kotlin matches Python (instrumented `HoldDetectionParityTest` + `ml/scripts/check_parity.py`)

Next, in order of value:
- [ ] **Real photos of our wall** (20-50, different days, times and lighting, phone at different angles): no real plywood photo has been tested yet; plywood numbers come from composites and may be optimistic
- [ ] Label hold outlines on ~20 of them, score the model, then fine-tune on them
- [ ] Measure speed on a real mid-range phone (target < 1.5 s); the ~2 s so far is an x86 emulator, not a phone
- [ ] Weak spots seen so far: very dark noisy photos (F1 0.78 on real), printed graphics and black holds on training boards, very large holds in close-ups, false detections on people, clocks, lamps and thin scuff marks
- [ ] Test the message shown when the model fails to load (detection reports an error, taps still add circles)
- [ ] Automatic wall corners: removed with OpenCV, so the user drags all four handles. Bring back only if dragging proves annoying
- [x] Repository licensed under AGPL-3.0, matching Ultralytics and the teacher; a closed-source release would need an Ultralytics licence or another architecture
- [x] Data sources credited in `README.md` and `ml/DATA_CREDITS.md`; keep both with any release
- [ ] Try int8 or fp16 to reduce model size and time

## 3. Unfinished from the current session
- [ ] Rebuild and re-test: the share image now also draws custom hold labels ("1", "L hand")
- [x] Detector experiment "hard zone switch" (each side of a sun stripe gets its own background): precision 0.949 → 0.953, recall unchanged. A small gain, not worth porting before the YOLO decision
- [ ] Finish the emulator test of **Master diff** (two masters exist: "Set of Nov 1" and "Autumn re-set": compare them, check added/removed/changed and the affected paths)
- [ ] Emulator tests not done yet: anchor positions grid + LED numbering, backup export/restore, V-scale switch, second climber, edit/duplicate/delete path, mirror, edit holds of an existing master, camera capture
- [x] Release build: R8 rules, one APK per CPU type, local signing through `keystore.properties` (not in git)
- [ ] Try the release on a real phone: speed, camera capture, handle dragging

## 4. Later
- [ ] ESP32: BLE link (`BleEsp32Exporter` stub + `Esp32Protocol` frame are ready), pairing screen, "light on wall"
- [ ] Share paths via QR code between phones
- [ ] Automatic alignment between master photos (ArUco markers on the wall corners)
