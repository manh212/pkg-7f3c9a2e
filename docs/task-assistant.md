# Task assistant

This feature ships on master. It is a working implementation that executes Android accessibility actions, not a simulated demo.

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
**Cài đặt giao việc**. The settings picker shows the speech
model separately from the optional AI model, with download progress, failure
and installed states. Completion updates the visible status and its polite
accessibility announcement. The configured Meridian license still applies.

Examples for the screen interpreter:

- `Nhấn vào Điện thoại rồi nhấn vào Tin nhắn`
- `Nhập Mình đến muộn 15 phút rồi nhấn nút Gửi sau đó nhấn Trang chủ`
- `Nhập "Mình xong rồi nhấn nút Gửi" rồi nhấn Gửi`
- `Nhấn giữ tên mục`, `Chọn mục thứ ba`, `Cuộn xuống hai lần`
- `Nhập vào ô Tin nhắn nội dung Mình đến muộn`
- `Nhấn Sao chép` when the copy control is visible
- `Bật tên công tắc`, `Tắt tên công tắc`
- `Mở Zalo, tìm Lan rồi mở kết quả đầu tiên`
- `Vào Cài đặt rồi vào Wi-Fi tắt Wi-Fi`

The entry form contains the request field, **Nói yêu cầu**, and **Thực hiện**.
Processing mode, model selection, speech downloads and screen-command examples
are available under **Cài đặt giao việc** in task settings.

Every task action uses the visible accessibility tree. `Mở Zalo` clicks its
visible icon, or opens it from the installed launcher catalog when the icon is
not on the current screen. `Nhấn Trang chủ` clicks a visible button with that name. The task controller resolves each step against the current visible nodes.
The voice-command feature handles gesture catalog actions, contact lookups and
direct phone calls. Screen commands can explicitly click **Gửi** after entering text.

The interpreter splits multi-step requests at commas, `rồi` or `sau đó` when a
screen command follows, and also at a command verb inside one request, so
`vào Wi-Fi tắt Wi-Fi` runs as two steps. Quoted text preserves command-like words literally.
Spoken names are matched forgivingly: an exact name wins, then a name that
contains or is contained in the spoken words, then a name one or two letters
away; when several items match equally well the task stops and asks instead of
guessing. Typing is verified on a fresh screen before the next step runs. Missing or
ambiguous targets stop the sequence. Manual input cancels pending work.

The direct workflow uses accessibility events and bounded screen checks. Local
AI fallback and online AI use the same screen-only action set. The Gemini
response schema supports both a single screen action and a sequence.

## Execution

The controller observes the active non-overlay window, sends bounded accessibility
content and the launcher app catalog to the selected AI, validates one structured
action, checks a fresh screen, executes, then observes again. The screen is
re-read fresh before every plan and every action, and the target is re-resolved
on that fresh screen before it is acted on, so a tap never lands on a stale node.
For the local model the prompt budget is 16,000 bytes; nodes whose labels match
words in the goal are sent first, so the item the user asked for is never the one
dropped when a large screen is truncated. Supported actions
include opening any installed app from the launcher catalog, clicking, long pressing,
dragging an item in a direction, replacing editable text, exposed IME
submission, scrolling, and going back or home. It can ask a question and continue with
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

Current limits: the offline command parser only understands its listed Vietnamese
patterns; anything else falls through to the AI, or is rejected when no model is
available. Drag moves one item in one direction; dragging one item onto another
is not supported yet. Opening an app needs it in the installed launcher catalog.
The local model sees at most 16,000 bytes of screen and apps per plan, with
goal-matching nodes first. A task stops after repeated failures, unchanged
results, loops, 40 steps or ten active minutes.

Future upgrades: drag one item onto another item; swipe without a target;
a larger local context, or the E4B model on compatible phones, for denser
screens; learning the user's corrections when a tap lands on the wrong item.

Unit coverage targets strict action validation, confirmation rules, current-screen
completion evidence, cancellation, manual interruption, stale callbacks, unchanged
results, loop prevention and independent AI selection. GitHub Actions performs
checks, tests, lint and APK packaging. The feature branch produces the `apks`
artifact and must skip dev publication. Shared CI workflow changes can broaden
test selection beyond the task assistant classes. No local build or test is run.
