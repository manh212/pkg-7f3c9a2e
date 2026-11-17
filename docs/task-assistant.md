# Task assistant

The owner approved this feature for `feat/vietnamese-task-assistant`, overriding
this repository's usual master-only workflow for this task. It is a working
implementation that executes Android accessibility actions, not a simulated demo.
Do not merge or publish it as the rolling dev build without the owner's request.

Branch APKs use a build number at least one greater than the fetched
`origin/master` commit count, with the branch's own count retained if higher.
The same effective number feeds Android's `versionCode` and the app's build
metadata. CI verifies the version in every generated branch APK before uploading
artifacts. This prevents Android rejecting the task build as a downgrade from a
current master build. The branch requires a fetched `origin/master` reference.

## Entry and input

Open **Giao việc cho Meridian** from the Meridian menu or Apps settings. Users can
also assign the action to a gesture; no default gesture is replaced. Type a
Vietnamese goal or dictate it, review and edit the recognized text,
choose the processing mode, then press **Thực hiện**. Both online and offline
voice input return to the entry form. Only **Thực hiện** starts a task.

The shared picker in the entry form and task settings provides Online and Offline
modes. Online mode exposes the service, selected model, configuration link and a
refresh action. It retrieves eligible text-generation models from the service,
including paginated Gemini and Claude lists, and caches their names against a
hash of the service address and credentials. Changing credentials invalidates the
cached list. No API key is stored in the model cache. Previously selected aliases
remain visible even when they are absent from the returned list.

The cloud model is remembered separately for each service. Gemini's global
Automatic setting resolves to the build's concrete default instead of an empty
model path. Gemini requests ask for a structured task object. Errors distinguish
access rejection, missing endpoints or models, quota limits, connectivity and
incomplete responses without showing raw server payloads.

Offline mode lists the complete existing model catalog with file size and
installed/compatibility status. Only models marked as supporting structured
answers and compatible with the device can be selected. Download, pause and
resume reuse Meridian's existing download manager and file verification. A download
may continue when the picker closes. The default task candidate is Gemma 4 E2B;
E4B is also available on compatible phones. These are not device performance or
task-accuracy guarantees.

The task local model is independent of the general AI model preference. A task
client binds to the existing inference service with the selected model file;
it does not replace the preferred model used by other features. GPU preference
and existing memory safeguards remain shared. There is no silent cloud fallback.
Local input uses a compact prompt capped at 16,000 UTF-8 bytes, prioritizes
interactive nodes and relevant app names, and marks incomplete catalogs. This
reduces prefill work but is not an exact tokenizer count. Goals that cannot fit
are rejected rather than silently shortened.

## Offline commands on master

An unconfigured installation starts in Offline mode. Choosing Online switches
to the selected provider. Offline typed commands are available immediately.
Offline speech uses the shared Vietnamese Zipformer download and passes the
transcript directly to the interpreter. Download the speech model once from
the spoken input button or the Offline picker. The picker shows the speech
model separately from the optional AI model, with download progress, failure
and installed states. Completion updates the visible status and its polite
accessibility announcement. The configured Meridian license still applies.

Examples for the direct interpreter:

- `Vào YouTube rồi nhấn tìm kiếm`
- `Mở YouTube, tìm nhạc Trịnh rồi mở video đầu tiên`
- `Soạn tin nhắn Zalo cho Lan nội dung Mình đến muộn 15 phút`
- `Soạn tin nhắn cho Lan trên Zalo`
- `Soạn tin nhắn SMS cho Lan, Mình đến muộn 15 phút`
- `Gọi cho mẹ` or `Gọi đến số` followed by a number
- `Cuộn xuống hai lần`, `Quay lại`, `Đóng bàn phím`
- `Nhấn giữ tên mục`, `Chọn mục thứ ba`, `Mở video thứ hai`
- `Nhập vào ô Tìm kiếm nội dung Lan`, `Thêm mình đến muộn`
- `Chọn tất cả`, `Sao chép`, `Cắt văn bản`, `Dán`, `Xóa nội dung`
- `Bật tên công tắc`, `Tắt tên công tắc`
- `Mở ứng dụng gần đây`, `Mở thông báo`, `Mở cài đặt nhanh`
- `Mở Zalo rồi nhấn Tìm kiếm`

The **Lệnh điều khiển** button on the entry form lists these control commands.
The interpreter supports screen interaction. Alarm scheduling and direct volume
adjustment belong to the existing phone voice commands.
Text input and search preserve dictated content, including connector words.
Multi-step control commands are validated before execution. Text replacement
and switch changes are checked against a fresh screen before continuing.

Messaging follows the app named in the command. Zalo drafts require a single
matching recipient, the matching chat header, and an identifiable message
field. An existing draft is preserved. SMS opens the system composer. Both
leave the draft for the user to send. Calls show the existing confirmation
dialog. When multiple phone numbers match a name, the user chooses the number
first. Calls and services such as YouTube or Zalo need their own connections.

The direct workflow uses accessibility events and bounded screen checks.
Target selection takes account of the current app, node labels and tree
relationships. If an installed local planner handles an unfamiliar phrasing,
it can launch apps, navigate or operate a labeled search control. The offline
workflow keeps speech and screen content on the phone.

## Execution

The controller observes the active non-overlay window, sends bounded accessibility
content and the launcher app catalog to the selected AI, validates one structured
action, checks a fresh screen, executes, then observes again. Supported actions
include launching apps, clicking, long pressing, replacing editable text, exposed
IME submission, scrolling, Back and Home. It can ask a question and continue with
the answer. It cannot execute scripts, arbitrary intents, coordinates or package
names absent from the installed launcher catalog.

Screen content is shared with the chosen provider when cloud AI is used. Password
nodes, identified credential inputs and their children are excluded. The planner is instructed to treat app
content as data, avoid unrelated actions and leave credentials to the user.
Credential questions pause for private entry instead of collecting an answer.

Sending, posting, deleting, payments, installing, granting permissions and other
important actions require confirmation. Native checks also require confirmation
for unknown buttons, long presses, IME submission and sensitive screen contexts,
even if the AI marks them safe. Confirmation is conservative; it is not a proof
that arbitrary third-party labels correctly describe their effects.

Manual touch, gestures or key presses invalidate pending work. Meridian asks each
time whether to pause or cancel. Resume captures the current screen and replans.
Late responses and callbacks cannot resume a stopped task. Locking the phone or
pausing Meridian prevents further execution. Stale screens are not acted upon.

Completion requires visible evidence in the current snapshot and a further fresh
observation. An action returning success alone does not complete the goal. The
controller stops after repeated failures, unchanged results, loops, 40 steps or
ten active minutes. Paused time is excluded from that time limit.

## Limits and validation

This implementation depends on apps exposing meaningful accessibility nodes and
actions. Canvas-only controls, unsupported embedded content and apps that reject
accessibility actions may require manual completion. It does not claim universal
app compatibility or use OCR coordinates as a fallback. AI interpretation and
completion evidence still need owner validation on a phone.

Unit coverage targets strict action validation, confirmation rules, current-screen
completion evidence, cancellation, manual interruption, stale callbacks, unchanged
results, loop prevention and independent AI selection. GitHub Actions performs
checks, tests, lint and APK packaging. The feature branch produces the `apks`
artifact and must skip dev publication. Shared CI workflow changes can broaden
test selection beyond the task assistant classes. No local build or test is run.
