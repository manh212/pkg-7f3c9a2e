package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.SearchView;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import java.util.ArrayList;
import java.util.List;

final class SettingsSearchController {
  private final TalkBackPreferenceFragment fragment;
  private final PreferenceScreen original;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final SettingsSearchIndex.Builder builder;
  private final PreferenceScreen results;
  private List<SettingsSearchIndex.Entry> entries = new ArrayList<>();
  private List<SettingsSearchIndex.Entry> shownEntries;
  private boolean indexed;
  private boolean shownIndexed;
  private String query;
  private SearchView search;
  private boolean stopped;

  SettingsSearchController(TalkBackPreferenceFragment fragment, String query) {
    this.fragment = fragment;
    this.query = query;
    original = fragment.getPreferenceScreen();
    builder = new SettingsSearchIndex.Builder(fragment.requireContext());
    results = fragment.getPreferenceManager().createPreferenceScreen(fragment.requireContext());
  }

  View wrap(View preferences) {
    Context context = fragment.requireContext();
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    search = new SearchView(context);
    search.setIconifiedByDefault(true);
    search.setQueryHint(context.getString(R.string.settings_search_title));
    search.setSaveFromParentEnabled(false);
    search.setQuery(query, false);
    search.clearFocus();
    search.setOnQueryTextListener(
        new SearchView.OnQueryTextListener() {
          @Override
          public boolean onQueryTextSubmit(String text) {
            dismissKeyboard();
            return true;
          }

          @Override
          public boolean onQueryTextChange(String text) {
            query = text;
            showResults();
            return true;
          }
        });
    layout.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    layout.addView(preferences, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    handler.post(this::indexNext);
    showResults();
    return layout;
  }

  String query() {
    return query;
  }

  void stop() {
    stopped = true;
    search.setOnQueryTextListener(null);
    handler.removeCallbacksAndMessages(null);
  }

  private void indexNext() {
    if (stopped || !fragment.isAdded()) {
      return;
    }
    boolean more = builder.step();
    entries = builder.entries();
    indexed = !more;
    showResults();
    if (more) {
      handler.post(this::indexNext);
    }
  }

  private void showResults() {
    if (stopped) {
      return;
    }
    if (SearchMatcher.normalize(query).isEmpty()) {
      shownEntries = null;
      if (fragment.getPreferenceScreen() != original) {
        fragment.setPreferenceScreen(original);
      }
      return;
    }
    List<SettingsSearchIndex.Entry> matches = SettingsSearchIndex.search(entries, query);
    if (matches.equals(shownEntries) && shownIndexed == indexed) {
      return;
    }
    shownEntries = matches;
    shownIndexed = indexed;
    Context context = fragment.requireContext();
    results.removeAll();
    for (SettingsSearchIndex.Entry entry : matches) {
      Preference row = new Preference(context);
      row.setTitle(entry.title);
      row.setSingleLineTitle(false);
      row.setIconSpaceReserved(false);
      if (!entry.path.isEmpty()) {
        row.setSummary(entry.path);
      }
      row.setOnPreferenceClickListener(
          preference -> {
            dismissKeyboard();
            ((TalkBackPreferencesActivity) fragment.requireActivity())
                .openSearchResult(entry);
            return true;
          });
      results.addPreference(row);
    }
    if (!indexed) {
      addMessage(results, R.string.settings_search_loading);
    } else if (matches.isEmpty()) {
      addMessage(results, R.string.settings_search_empty);
    }
    if (fragment.getPreferenceScreen() != results) {
      fragment.setPreferenceScreen(results);
    }
  }

  private void addMessage(PreferenceScreen screen, int title) {
    Preference message = new Preference(fragment.requireContext());
    message.setTitle(title);
    message.setSelectable(false);
    message.setIconSpaceReserved(false);
    screen.addPreference(message);
  }

  private void dismissKeyboard() {
    search.clearFocus();
    InputMethodManager keyboard =
        (InputMethodManager) fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null) {
      keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
  }
}
