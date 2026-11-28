# Guided photo

Open **Chụp ảnh có hướng dẫn** from the common command list or the Live recognition
screen in settings. The command can also be assigned through the existing command
pickers. This mode opens Meridian's own camera; it does not control another camera
application or require Pixel Guided Frame or Shizuku.

Point the camera at the desired subject. No category selection is required. The
supported subject nearest the center is selected automatically and announced after
two matching observations. Face tracking, object detection and document edges
provide the subject candidates. The interface guides left/right/up/down movement,
distance, face direction, document alignment, low light, sharpness, stability and
focus. Front-camera horizontal directions account for the mirrored preview.

After the announcement finishes and the image settles, Meridian says **Hai**, then
**Một**, and takes one photo after at least two seconds. Both numbers must finish
speaking. Each new observation must remain suitable: movement, framing or subject
changes cancel the countdown. A frame gap over 900 ms cannot complete an old
countdown. Select **Chụp ảnh khác** to start another round or **Chụp ngay** to capture
manually. Leaving the screen, pausing Meridian or locking the device stops the mode.

## Models and storage

Face detection uses the bundled ML Kit face detector. Objects use the existing
EfficientDet-Lite0 model, downloaded and verified on first use if unavailable.
The bundled face detector is an explicit exception to the downloaded-model asset
policy; the asset test permits only its four named TFLite weights and continues
to reject other packaged model weights.
Document edges use the existing OpenCV scanner. Analysis runs on the device; this
mode does not upload camera images to an AI service. Detection covers the model's
COCO classes, including common animals, vehicles and some foods. It cannot
identify every food, beverage, object or arbitrary scene.

Photos are JPEG files in `Pictures/Meridian`, visible in compatible gallery apps.
Android 10 and later publish a pending MediaStore row only after successful
capture. Failed or cancelled saves remove that row. Android 8 and 9 request the
existing storage permission after camera permission and scan the saved file into
the gallery. Camera choice is remembered; captured images remain until the user
deletes them. There is no automatic sharing.

## Validation

Controller tests cover subject selection and announcements, spoken countdown
acknowledgements, the full two-second delay, cancellation, stale frames, cumulative
movement, front-camera directions, quality gates, late callbacks and one photo per
round. Other tests cover image quality measurements, permission order, command
discovery and MediaStore/legacy save cleanup. CI also compiles and lints the camera
integration. These tests do not establish recognition accuracy or camera behavior
on physical devices.

On a phone, check both cameras in portrait and landscape, Vietnamese direction
prompts, faces and supported objects near each edge, documents, darkness, torch,
blur, movement during both countdown numbers, camera interruption, screen lock,
pause, closing during capture, gallery visibility, permission denial and storage
failure. Confirm that TalkBack focus follows instructions, status, camera controls,
capture/retry and close. Thresholds are initial heuristics and require device
validation; no accuracy benchmark is claimed.

- [ML Kit face detection for Android](https://developers.google.com/ml-kit/vision/face-detection/android)
- [MediaPipe object detection](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android)
- [CameraX image capture](https://developer.android.com/media/camera/camerax/take-photo)
