# Hướng dẫn tích hợp Focus Memory v2 vào Meridian (nền build 2763 / `a9baa7a`)

Gói này cung cấp module nhớ tiêu điểm độc lập trong gói `com.google.android.accessibility.talkback.focusmanagement.memory` cùng 3 điểm móc nối tối giản vào mã nguồn hiện có trên nhánh `master` (không làm phình `FocusManagerInternal` và không tạo lại `APP_PLACED_FOCUS` gây nhiễu).

## Bước 1: Chép 5 tệp mới vào đúng đường dẫn trong kho lưu trữ

Giải nén gói này vào thư mục gốc của repo `SandFly-123` (`pkg-7f3c9a2e`) sao cho 5 tệp nằm đúng vị trí:

- `talkback/src/main/java/com/google/android/accessibility/talkback/focusmanagement/memory/SemanticScreenKey.java`
- `talkback/src/main/java/com/google/android/accessibility/talkback/focusmanagement/memory/FocusTargetFingerprint.java`
- `talkback/src/main/java/com/google/android/accessibility/talkback/focusmanagement/memory/ScreenFocusMemoryStore.java`
- `talkback/src/main/java/com/google/android/accessibility/talkback/focusmanagement/memory/FocusRestoreCoordinator.java`
- `talkback/src/test/java/com/google/android/accessibility/talkback/focusmanagement/memory/FocusMemoryV2Test.java`

## Bước 2: Móc nối vào `AccessibilityFocusActionHistory.java`

Tệp: `talkback/src/main/java/com/google/android/accessibility/talkback/focusmanagement/record/AccessibilityFocusActionHistory.java`

1. Thêm import:
```java
import com.google.android.accessibility.talkback.focusmanagement.memory.FocusRestoreCoordinator;
import com.google.android.accessibility.talkback.focusmanagement.memory.ScreenFocusMemoryStore;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
```

2. Tăng `MAXIMUM_WINDOW_MAP_SIZE` từ `10` lên `40`, đồng thời thêm trường `memoryStore` và `restoreCoordinator`:
```java
  private static final int MAXIMUM_WINDOW_MAP_SIZE = 40;

  private final ScreenFocusMemoryStore memoryStore = new ScreenFocusMemoryStore();
  private final FocusRestoreCoordinator restoreCoordinator =
      new FocusRestoreCoordinator(memoryStore);

  public ScreenFocusMemoryStore getMemoryStore() {
    return memoryStore;
  }

  public FocusRestoreCoordinator getRestoreCoordinator() {
    return restoreCoordinator;
  }
```
*(Nếu `FocusActorForScreenStateChange` chỉ giữ `AccessibilityFocusActionHistory.Reader`, thêm 2 getter chuyển tiếp tương ứng `getMemoryStore()` và `getRestoreCoordinator()` vào lớp `Reader`.)*

3. Trong `onAccessibilityFocusAction(node, extraData, actionTime, currentScreenState, originEventId)`, sau dòng `windowIdentifierToFocusActionRecordMap.put(...)`, ghi nhận vào `memoryStore` **chỉ khi là hành động có chủ đích của người dùng**:
```java
    if (currentScreenState != null && currentScreenState.getActiveWindow() != null) {
      AccessibilityNodeInfoCompat activeRoot =
          AccessibilityWindowInfoUtils.getRootCompat(currentScreenState.getActiveWindow());
      memoryStore.recordIfUserIntentional(
          activeRoot, node, extraData, currentScreenState, windowId, actionTime);
    }
```

4. Giữ lại bản sửa lỗi rất tốt từ commit `29dbc91` trong `getLastFocusActionRecordInWindow(int windowId)`: duyệt `orderedMap.entrySet()` chọn bản ghi có `getActionTime()` lớn nhất thay vì duyệt ngược LRU (vì `LruCache.get()` làm đảo thứ tự truy cập).

## Bước 3: Móc nối vào `FocusActorForScreenStateChange.java`

Tệp: `talkback/src/main/java/com/google/android/accessibility/talkback/actor/FocusActorForScreenStateChange.java`

1. Thêm import:
```java
import android.os.SystemClock;
import com.google.android.accessibility.talkback.focusmanagement.memory.SemanticScreenKey;
```

2. Trong `restoreLastFocusedNode(ScreenState screenState, EventId eventId)`:
   - Thay vì `return false;` ngay khi `lastFocusAction == null` (sau bước fallback `history.getLastFocusActionRecordInWindow(windowId)`), cho phép đi tiếp xuống bước tìm `root`.
   - Sau khi `nodeToRestoreFocus` được tìm từ `lastFocusAction` (hoặc khi `lastFocusAction == null`), nếu `nodeToRestoreFocus == null`:
```java
    if (nodeToRestoreFocus == null) {
      nodeToRestoreFocus =
          history.getMemoryStore().findRestoreNode(root, screenState, windowId);
    }
    if (nodeToRestoreFocus == null) {
      SemanticScreenKey screenKey = SemanticScreenKey.create(root, screenState, windowId);
      history.getRestoreCoordinator().armPendingRestore(screenKey, SystemClock.uptimeMillis());
      return false;
    }
```
   - Khi khôi phục thành công (`if (success)`), đánh dấu đã hoàn tất để không khôi phục lặp:
```java
    if (success) {
      history
          .getRestoreCoordinator()
          .markRestored(SemanticScreenKey.create(root, screenState, windowId));
    }
```

## Bước 4: Móc nối vào `FocusManagerInternal.java`

Tệp: `talkback/src/main/java/com/google/android/accessibility/talkback/actor/FocusManagerInternal.java`

1. Trong `renewEnsureFocus()`, hủy ngay mọi yêu cầu thử lại đang chờ khi người dùng chạm tay hoặc vuốt:
```java
  public void renewEnsureFocus() {
    skipRefocus = false;
    sameItemRestores = 0;
    history.getRestoreCoordinator().cancelOnUserInteraction();
  }
```

2. Trong `maybeRestorePaneRecordAfterPaneChange(ScreenState state, AccessibilityNodeInfoCompat currentFocus, EventId eventId)`, thêm đường dẫn kiểm tra qua `FocusRestoreCoordinator` ngay sau khi lấy `root`:
```java
    AccessibilityNodeInfoCompat semanticTarget =
        history
            .getRestoreCoordinator()
            .tryResolvePendingOrPaneChange(
                root, currentFocus, state, activeWindow.getId(), SystemClock.uptimeMillis());
    if (semanticTarget != null) {
      skipRefocus = true;
      return focusNodeFoundFromRecord(
          semanticTarget, FocusActionInfo.RESTORED_LAST_FOCUS, state, eventId);
    }
```

3. Trong `ensureFocusOnScreen(EventId eventId)`: giữ nguyên luồng hiện tại ở build 2763; khi `currentFocus != null`, gọi `maybeRestorePaneRecordAfterPaneChange(state, currentFocus, eventId)` như cũ — hàm này giờ đã tự xử lý cả hàng đợi thử lại ngắn (450 ms) lẫn chuyển pane ngữ nghĩa trong cùng `windowId`.

## Bước 5: Kiểm thử và xác minh bằng nhật ký

1. Chạy unit test:
   ```bash
   ./gradlew :talkback:testDebugUnitTest --tests "com.google.android.accessibility.talkback.focusmanagement.memory.FocusMemoryV2Test"
   ```
2. Bật Debug log trên Meridian, lọc theo tag `FocusMemoryV2Store` và `FocusMemoryV2Coord` để quan sát:
   - `Recorded SemanticScreenKey{...}` khi bạn vuốt/chạm chọn một mục.
   - `Restore lookup for ... -> HIT(...)` khi quay lại màn hình đó kể cả sau khi đổi app (`windowId` mới) hoặc Back từ phòng chat ra danh sách chat (cùng `windowId`).
   - `Cancelled pending restore on user interaction` khi bạn chạm/vuốt trước lúc hết 450 ms.
