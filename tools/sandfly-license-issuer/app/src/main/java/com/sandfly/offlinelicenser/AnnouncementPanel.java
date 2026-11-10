package com.sandfly.offlinelicenser;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.meridian.licensing.FirebaseRest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

/**
 * The owner's announcements to every Meridian user. They are stored on Firebase as a
 * list; publishing adds a new one, and each user app shows the ones a user has not seen
 * yet, oldest first, one dialog at a time. Withdrawing removes one for everyone who has
 * not seen it yet. Publishing keeps at most the thirty newest.
 */
final class AnnouncementPanel {
  private static final int MAX_STORED = 30;

  private final MainActivity activity;
  private TextView status;
  private EditText input;
  private CheckBox latestOnly;
  private LinearLayout storedList;

  AnnouncementPanel(MainActivity activity) {
    this.activity = activity;
  }

  /** One stored announcement, as the list shows it. */
  private static final class Stored {
    final String id;
    final String text;
    final long updatedAt;
    final boolean requireLatest;

    Stored(String id, String text, long updatedAt, boolean requireLatest) {
      this.id = id;
      this.text = text;
      this.updatedAt = updatedAt;
      this.requireLatest = requireLatest;
    }
  }

  void open() {
    LinearLayout root = column();
    text(root, "Mỗi thông báo được lưu lại. Người dùng mở trình đọc màn hình hoặc mở cài "
        + "đặt sẽ thấy lần lượt từng thông báo họ chưa xem, từ cũ đến mới.");
    status = text(root, "Đang đọc các thông báo đã lưu…");
    input = new EditText(activity);
    input.setHint("Nội dung thông báo");
    input.setMinLines(4);
    input.setGravity(Gravity.TOP);
    root.addView(input);
    latestOnly = new CheckBox(activity);
    latestOnly.setText("Tính năng mới");
    latestOnly.setChecked(true);
    root.addView(latestOnly);
    text(root, "Đánh dấu “Tính năng mới”: thông báo chỉ hiện cho người dùng đã cập nhật "
        + "lên bản mới nhất; người chưa cập nhật sẽ thấy nó sau khi cập nhật.");
    button(root, "Đăng thông báo", this::publish);
    storedList = new LinearLayout(activity);
    storedList.setOrientation(LinearLayout.VERTICAL);
    root.addView(storedList);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    new AlertDialog.Builder(activity).setTitle("Thông báo cho người dùng")
        .setView(scroll).setNegativeButton("Đóng", null).show();
    load();
  }

  private void load() {
    SyncCoordinator.WORKER.execute(() -> {
      try {
        List<Stored> stored = fetch(authorized());
        ui(() -> show(stored));
      } catch (Exception e) {
        ui(() -> status.setText(message(e)));
      }
    });
  }

  private void show(List<Stored> stored) {
    storedList.removeAllViews();
    if (stored.isEmpty()) {
      status.setText("Chưa có thông báo nào đang lưu.");
      return;
    }
    status.setText("Đang lưu " + stored.size() + " thông báo:");
    for (Stored item : stored) {
      String when = DateFormat.getDateTimeInstance().format(new Date(item.updatedAt));
      text(storedList, when + (item.requireLatest ? " • Tính năng mới" : "") + "\n" + item.text);
      button(storedList, "Gỡ thông báo này", () -> confirmWithdraw(item));
    }
  }

  private void publish() {
    String value = input.getText().toString().trim();
    if (value.isEmpty()) {
      status.setText("Hãy nhập nội dung thông báo trước.");
      return;
    }
    if (value.length() > 4000) {
      status.setText("Thông báo quá dài (tối đa 4.000 ký tự).");
      return;
    }
    boolean requireLatest = latestOnly.isChecked();
    status.setText("Đang đăng…");
    SyncCoordinator.WORKER.execute(() -> {
      try {
        FirebaseRest service = authorized();
        service.put("appAnnouncements/" + System.currentTimeMillis(), new JSONObject()
            .put("text", value).put("updatedAt", FirebaseRest.timestamp())
            .put("requireLatest", requireLatest));
        List<Stored> stored = fetch(service);
        // The list is newest first; everything past the newest thirty goes away.
        for (int i = MAX_STORED; i < stored.size(); i++) {
          service.deleteAnnouncement(stored.get(i).id);
        }
        List<Stored> current = fetch(service);
        ui(() -> {
          input.setText("");
          show(current);
        });
      } catch (Exception e) {
        ui(() -> status.setText(message(e)));
      }
    });
  }

  private void confirmWithdraw(Stored item) {
    new AlertDialog.Builder(activity)
        .setMessage("Gỡ thông báo này? Người dùng chưa xem sẽ không thấy nữa.")
        .setNegativeButton("Hủy", null)
        .setPositiveButton("Gỡ thông báo", (dialog, which) -> withdraw(item))
        .show();
  }

  private void withdraw(Stored item) {
    status.setText("Đang gỡ…");
    SyncCoordinator.WORKER.execute(() -> {
      try {
        FirebaseRest service = authorized();
        service.deleteAnnouncement(item.id);
        List<Stored> current = fetch(service);
        ui(() -> show(current));
      } catch (Exception e) {
        ui(() -> status.setText(message(e)));
      }
    });
  }

  /** Every stored announcement, newest first. */
  private List<Stored> fetch(FirebaseRest service) throws Exception {
    JSONObject stored = service.get("appAnnouncements");
    List<Stored> result = new ArrayList<>();
    Iterator<String> keys = stored.keys();
    while (keys.hasNext()) {
      String id = keys.next();
      JSONObject entry = stored.optJSONObject(id);
      if (entry == null) continue;
      result.add(new Stored(id, entry.optString("text", ""), entry.optLong("updatedAt", 0L),
          entry.optBoolean("requireLatest", false)));
    }
    result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
    return Collections.unmodifiableList(result);
  }

  private FirebaseRest authorized() throws Exception {
    FirebaseRest firebase = EnrollmentService.create(activity);
    String uid = firebase.uid();
    if (!firebase.flag("admins/" + uid)) {
      throw new SecurityException("Chỉ chủ sở hữu được đăng thông báo.");
    }
    return firebase;
  }

  private void ui(Runnable action) {
    activity.runOnUiThread(() -> {
      if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
    });
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    layout.setPadding(padding, padding, padding, padding);
    return layout;
  }

  private TextView text(LinearLayout layout, String value) {
    TextView label = new TextView(activity);
    label.setText(value);
    label.setTextSize(16);
    label.setPadding(0, 8, 0, 8);
    layout.addView(label);
    return label;
  }

  private Button button(LinearLayout layout, String value, Runnable action) {
    Button view = new Button(activity);
    view.setText(value);
    view.setAllCaps(false);
    view.setOnClickListener(v -> action.run());
    layout.addView(view);
    return view;
  }

  private static String message(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isEmpty() ? exception.toString() : message;
  }
}
