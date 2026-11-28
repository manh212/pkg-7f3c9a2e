# Guided photo

Open **Chụp ảnh có hướng dẫn** from the common command list or Live recognition in
settings. No category selection is required. The camera belongs to Meridian;
Pixel Guided Frame and Shizuku are not required.

## Recognition and guidance

Faces take priority over objects. All detected faces form one group, and Meridian
announces the number of faces. A face does not have to be centered or face exactly
forward. Tracking IDs can change without restarting the round. Otherwise the
nearest supported object is selected and retained while it remains visible;
document edges provide a fallback. A new subject needs two observations spanning
at least 250 ms before announcement. A changed group needs 650 ms of confirmation.

Guidance corrects actual clipping at the image edges, severe darkness, blur or
strong motion. Horizontal directions account for the mirrored front preview.
Distance is only a requirement for clipping or an extremely small analyzed region
(less than 24 pixels on either axis). Quality is measured separately inside each
face or the selected object, at up to 192 pixels on the longest side. Moving or
dark backgrounds do not enter those measurements. Motion compares normalized
subject crops after subtracting their average luminance, reducing false alarms
from exposure adjustments. Sharpness and darkness thresholds remain heuristics;
this is not a learned photographic-quality model.

Autofocus and exposure target an actual face in the group, or the selected object.
CameraX maps the cropped, rotated analysis coordinates into the preview, including
mirroring. Preview, analysis and capture share a ViewPort. An unsuccessful or
unsupported autofocus callback does not veto a visibly sharp subject. Analysis
prefers 1280 by 960 with supported lower-resolution fallback; saved JPEG resolution
is chosen separately by ImageCapture.

## Countdown and example

After the announcement completes and suitable observations span 250 ms, Meridian
queues **Hai**. The two-second clock starts from its actual speech-start callback,
not from when the utterance entered the speech queue. **Một** follows after at
least one second. Automatic capture requires both completed utterances, at least
two elapsed seconds and a current suitable observation. It happens once per round.

A missing subject for less than 650 ms or a quality fluctuation shorter than
450 ms pauses eligibility without restarting the count. The shutter never fires
on a missing, unsuitable or stale observation. A sustained problem cancels the
count. An analysis result older than two seconds cannot capture; an observation
gap over three seconds cancels an old count. Guidance must persist for 450 ms and
has a repeat cooldown, reducing contradictory chatter. Ordinary guidance is queued
without interrupting the subject announcement; cancellation stops obsolete counting.

Expected interaction for a group photograph:

1. Point either camera at the group. Hear **Phát hiện 3 khuôn mặt.**
2. If a detected face is clipped on the left, hear **Hãy dịch điện thoại sang trái
   một chút.** The front camera reverses the horizontal direction.
3. Once every detected face is in the frame and sufficiently clear, hear **Hai**,
   then **Một**. Small hand drift does not demand perfect centering.
4. After at least two seconds and a fresh suitable observation, hear the shutter
   and **Đã lưu ảnh vào thư mục Ảnh/Meridian.**
5. Choose **Chụp ảnh khác** for another round, or **Chụp ngay** for manual capture.

This describes implemented behavior, not a recorded physical-device demonstration.
A person entirely outside the frame cannot be detected or counted. Face detections
are not identity recognition. The mode cannot guarantee that everyone in a scene
has been found, that nobody blinked, or that every photo is sharp.

## Model choice

| Component | Current choice | Reason and limit |
| --- | --- | --- |
| Faces | Bundled ML Kit, ACCURATE mode | Supports multiple faces; available immediately without network. Switches to FAST after three consecutive face passes over 400 ms. |
| Objects | EfficientDet-Lite2 int8, input 448 square | Google documents greater general accuracy than Lite0, with more latency and memory. Still limited to 80 COCO classes. |
| Object fallback | Previously installed EfficientDet-Lite0 | Used while Lite2 is absent or cannot initialize. Other object-recognition features keep their existing model. |
| Documents | Existing OpenCV edge scanner | Runs only when faces and non-book objects are absent. It is a geometric heuristic, not general scene recognition. |

Lite2 is downloaded separately on first opening (7,515,971 bytes), with a pinned
SHA-256 verified by the existing downloader before publication of the file. Face
photography starts immediately and keeps working if the object download fails.
Download status and a retry control are accessible in the camera screen and do
not interrupt counting. The bundled face detector remains the explicit exception
to the downloaded-model asset policy; no new weights are packaged in the APK.

Google's published Pixel 6 CPU benchmarks list approximately 29.31 ms for Lite0
int8 and 70.91 ms for Lite2 int8. Those numbers are reference measurements, not
Meridian measurements or promises for another phone. ML Kit recommends roughly
100 by 100 face pixels for reliable detection; allowing smaller regions does not
make small or occluded faces equally reliable.

YuNet and newer YOLO variants were considered. There is no measured advantage over
ML Kit on this application's target phones. The latest OpenCV Zoo YuNet integration
and YOLO runtime/decoder or licensing differences add migration work without
solving the old controller's quality and countdown errors. No claim of parity
with Google's proprietary Guided Frame is made.

## Storage and lifetime

All image analysis runs on the device. Camera frames are not uploaded. JPEGs are
saved under Pictures/Meridian. Android 10 and later publish the pending MediaStore
row only after success; failed or cancelled saves discard it. Android 8 and 9 use
the existing storage permission and gallery scan. There is no automatic sharing.

ML Kit finishes before the frame can be released. MediaPipe's MPImage owns its
Bitmap until optional object/document analysis and all quality measurements end.
Camera startup and native-library failures are reported inside the camera screen.
Binding is deferred until RESUMED and pending requests cannot overlap. Leaving the
screen, pausing Meridian or locking the device stops the mode.

## Validation

GitHub Actions runs controller, region-quality, frame-lifetime, focus-transform,
dialog-lifecycle and model-group regression tests, plus existing permissions,
command discovery and save-cleanup coverage selected for the affected module.
Resource changes can widen the automatic selection to the talkback module.

The controller scenarios include groups, off-center faces, tracking-ID churn,
moderate drift, delayed speech start/completion, brief detection loss, sustained
clipping, membership changes, stale frames and bad quality at the shutter deadline.
Image-quality tests cover dark/moving backgrounds, exposure changes and smaller
face regions. Pipeline tests ensure faces skip both object and document analysis.
Focus tests cover off-center coordinates, mirroring and rotation. Lifecycle tests
cover Android 8 and 14 and camera startup without downloaded object weights.

The owner checks physical cameras: portrait/landscape, single/group faces, all four
edges, supported objects, low light, torch, focus, blur, interruptions, speech rate,
permission denial, gallery visibility and storage failure. CI cannot establish
real-world detection accuracy, battery use, or equivalence to Pixel Guided Frame.

## References

- [ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android)
- [ML Kit detector options](https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceDetectorOptions.Builder)
- [MediaPipe model comparison and benchmarks](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector)
- [CameraX coordinate transforms](https://developer.android.com/media/camera/camerax/transform-output)
- [OpenCV Zoo YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
- [Ultralytics licensing](https://www.ultralytics.com/license)
