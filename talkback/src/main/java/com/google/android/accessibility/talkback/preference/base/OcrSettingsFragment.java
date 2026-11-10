/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.imagecaption.OcrModelDownload;
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr;

public class OcrSettingsFragment extends TalkbackBaseFragment {
  public OcrSettingsFragment() {
    super(R.xml.ocr_settings_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_ocr_settings);
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    Preference download = findPreference(getString(R.string.pref_ocr_data_download_key));
    if (download == null) {
      return;
    }
    download.setOnPreferenceClickListener(
        preference -> {
          OcrModelDownload.INSTANCE.start(requireContext());
          return true;
        });
    OcrModelDownload.INSTANCE.getStatus().observe(
        getViewLifecycleOwner(), status -> refreshDownload(download, status));
  }

  private void refreshDownload(Preference download, OcrModelDownload.Status status) {
    boolean ready = TesseractOcr.isDataDownloaded(requireContext());
    Integer progress = status.getProgress();
    download.setEnabled(progress == null && !ready);
    if (progress != null) {
      download.setSummary(getString(R.string.ocr_offline_download_progress, progress));
    } else if (ready) {
      download.setSummary(R.string.summary_ocr_data_downloaded);
    } else if (status.getFailed()) {
      download.setSummary(R.string.summary_ocr_data_download_failed);
    } else {
      download.setSummary(R.string.summary_ocr_data_not_downloaded);
    }
  }
}
