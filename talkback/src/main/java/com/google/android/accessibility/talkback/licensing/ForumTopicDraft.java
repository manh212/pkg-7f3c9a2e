package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.content.SharedPreferences;
import io.meridian.licensing.ForumProtocol;
import java.util.UUID;

record ForumTopicDraft(String revision, String category, String title, String body, String poll) {
  private static final String FILE = "meridian_forum_draft";

  static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  static ForumTopicDraft load(SharedPreferences preferences) {
    return new ForumTopicDraft(
        preferences.getString("revision", ""),
        preferences.getString("category", ForumProtocol.categories().get(0)),
        preferences.getString("title", ""),
        preferences.getString("body", ""),
        preferences.getString("poll", ""));
  }

  static ForumTopicDraft save(
      SharedPreferences preferences, String category, String title, String body, String poll) {
    ForumTopicDraft current = load(preferences);
    if (current.category().equals(category) && current.title().equals(title)
        && current.body().equals(body) && current.poll().equals(poll)) {
      return current;
    }
    ForumTopicDraft draft = new ForumTopicDraft(
        UUID.randomUUID().toString(), category, title, body, poll);
    preferences.edit().putString("revision", draft.revision()).putString("category", category)
        .putString("title", title).putString("body", body).putString("poll", poll).apply();
    return draft;
  }

  boolean clearAfterPublishing(SharedPreferences preferences) {
    if (!revision.equals(preferences.getString("revision", ""))) return false;
    preferences.edit().clear().apply();
    return true;
  }
}
