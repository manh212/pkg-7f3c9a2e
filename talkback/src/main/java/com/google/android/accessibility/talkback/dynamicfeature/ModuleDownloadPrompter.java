/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.dynamicfeature;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.dynamicfeature.Downloader.DownloadErrorCode;
import com.google.android.accessibility.talkback.dynamicfeature.Downloader.DownloadState;
import com.google.android.accessibility.talkback.dynamicfeature.Downloader.DownloadStateUpdateListener;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.DownloadDialogResources;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.ImageCaptionPreferenceKeys;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionConstants.UninstallDialogResources;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType;
import com.google.android.accessibility.talkback.imagecaption.Request;
import com.google.android.accessibility.talkback.imagecaption.Request.ErrorCode;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** Shows a confirmation dialog to guide users through the download of a module dynamically. */
public abstract class ModuleDownloadPrompter {

  /** A callback to handle the download state. */
  public interface DownloadStateListener {
    /** Called when the module has been installed. */
    void onInstalled();

    /** Called when the module download is failed. */
    void onFailed(@ErrorCode int errorCode);

    /** Called when the download has been accepted. */
    void onAccepted();

    /** Called when the download has been rejected. */
    void onRejected();

    /** Called when the dialog is dismissed. */
    void onDialogDismissed(@Nullable AccessibilityNodeInfoCompat queuedNode);
  }

  /** A callback to handle the uninstall state. */
  public interface UninstallStateListener {
    /** Called when the uninstallation has been accepted. */
    void onAccepted();

    /** Called when the uninstallation has been rejected. */
    void onRejected();
  }

  /** Requests the download in what situation. */
  public enum Requester {
    /** The request from TalkBack menu. */
    MENU,
    /** The request from TalkBack settings page. */
    SETTINGS,
    /** The request from onboarding page. */
    ONBOARDING
  }

  private static final int MAX_DOWNLOAD_DIALOG_SHOWN_TIMES = 3;

  private final Context context;
  private final Downloader downloader;
  @Nullable private final Downloader downloaderLegacy;
  private final ImmutableList<CaptionType> captionTypes;
  private final ImageCaptionPreferenceKeys preferenceKeys;
  private final DownloadDialogResources downloadDialogResources;
  private final UninstallDialogResources uninstallDialogResources;
  private final SharedPreferences prefs;

  @Nullable private Pipeline.FeedbackReturner pipeline;
  @Nullable private DownloadStateListener downloadStateListener;
  @Nullable private UninstallStateListener uninstallStateListener;
  @Nullable private AccessibilityNodeInfoCompat captionNode;

  private final DownloadStateUpdateListener downloadStateUpdateListener =
      this::onDownloadStateUpdate;

  public ModuleDownloadPrompter(
      Context context,
      Downloader downloader,
      Downloader downloaderLegacy,
      ImmutableList<CaptionType> captionTypes,
      ImageCaptionPreferenceKeys preferenceKeys,
      DownloadDialogResources downloadDialogResources,
      UninstallDialogResources uninstallDialogResources) {
    this.context = context;
    this.downloader = downloader;
    this.downloaderLegacy = downloaderLegacy;
    this.captionTypes = captionTypes;
    this.preferenceKeys = preferenceKeys;
    this.downloadDialogResources = downloadDialogResources;
    this.uninstallDialogResources = uninstallDialogResources;
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    downloader.registerListener(downloadStateUpdateListener);
  }

  public void setPipeline(@Nullable Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  public void setDownloadStateListener(@Nullable DownloadStateListener downloadStateListener) {
    this.downloadStateListener = downloadStateListener;
  }

  public void setUninstallStateListener(@Nullable UninstallStateListener uninstallStateListener) {
    this.uninstallStateListener = uninstallStateListener;
  }

  public void setCaptionNode(@Nullable AccessibilityNodeInfoCompat node) {
    captionNode = node;
  }

  public boolean isLegacyModuleAvailable() {
    if (downloaderLegacy == null) {
      return false;
    }
    for (CaptionType captionType : captionTypes) {
      if (!downloaderLegacy.isInstalled(downloaderLegacy.getModuleName(captionType))) {
        return false;
      }
    }
    return true;
  }

  public boolean isModuleAvailable() {
    for (CaptionType captionType : captionTypes) {
      if (!downloader.isInstalled(downloader.getModuleName(captionType))) {
        return false;
      }
    }
    return true;
  }

  public boolean isModuleDownloading() {
    for (CaptionType captionType : captionTypes) {
      if (downloader.isDownloading(downloader.getModuleName(captionType))) {
        return true;
      }
    }
    return false;
  }

  public boolean isUninstalled() {
    return SharedPreferencesUtils.getBooleanPref(
        prefs, context.getResources(), preferenceKeys.uninstalledKey, false);
  }

  public boolean needDownloadDialog(Requester requester) {
    if (isModuleAvailable() || isLegacyModuleAvailable()) {
      return false;
    }
    if (SharedPreferencesUtils.getBooleanPref(
        prefs, context.getResources(), preferenceKeys.doNotShowKey, false)) {
      return false;
    }
    String shownTimesKey = context.getString(preferenceKeys.downloadShownTimesKey);
    return prefs.getInt(shownTimesKey, /* defValue= */ 0) < MAX_DOWNLOAD_DIALOG_SHOWN_TIMES;
  }

  public void showDownloadDialog(Requester requester) {
    DownloadDialog dialog =
        new DownloadDialog(context, downloadDialogResources) {
          @Override
          public void handleDialogClick(int buttonClicked) {
            if (buttonClicked == DialogInterface.BUTTON_POSITIVE) {
              if (isNotShowAgain()) {
                SharedPreferencesUtils.putBooleanPref(
                    prefs, context.getResources(), preferenceKeys.doNotShowKey, true);
              }
              if (downloadStateListener != null) {
                downloadStateListener.onAccepted();
              }
              downloadModules();
            } else if (buttonClicked == DialogInterface.BUTTON_NEGATIVE) {
              if (downloadStateListener != null) {
                downloadStateListener.onRejected();
              }
            }
          }

          @Override
          public void handleDialogDismiss() {
            if (downloadStateListener != null) {
              downloadStateListener.onDialogDismissed(captionNode);
            }
          }
        };
    dialog.showDialog(requester);
  }

  public void showUninstallDialog() {
    UninstallDialog dialog =
        new UninstallDialog(
            context,
            uninstallDialogResources.uninstallTitleRes,
            uninstallDialogResources.uninstallMessageRes) {
          @Override
          public void handleDialogClick(int buttonClicked) {
            if (buttonClicked == DialogInterface.BUTTON_POSITIVE) {
              for (CaptionType captionType : captionTypes) {
                downloader.uninstall(context, downloader.getModuleName(captionType));
              }
              SharedPreferencesUtils.putBooleanPref(
                  prefs, context.getResources(), preferenceKeys.uninstalledKey, true);
              SharedPreferencesUtils.putBooleanPref(
                  prefs, context.getResources(), preferenceKeys.installedKey, false);
              if (uninstallStateListener != null) {
                uninstallStateListener.onAccepted();
              }
            } else if (buttonClicked == DialogInterface.BUTTON_NEGATIVE) {
              if (uninstallStateListener != null) {
                uninstallStateListener.onRejected();
              }
            }
          }
        };
    dialog.showDialog();
  }

  public void shutdown() {
    downloader.unregisterListener(downloadStateUpdateListener);
  }

  public boolean installModule() {
    boolean installed = true;
    for (CaptionType captionType : captionTypes) {
      installed &= downloader.install(context, captionType);
    }
    return installed;
  }

  public abstract @StringRes int getDownloadSuccessfulHint();

  public abstract @StringRes int getDownloadingHint();

  protected abstract boolean initModule();

  protected boolean returnFeedback(Feedback.Part.Builder feedback) {
    return pipeline != null
        && pipeline.returnFeedback(Performance.EVENT_ID_UNTRACKED, feedback);
  }

  private void downloadModules() {
    List<String> moduleNames = new ArrayList<>();
    for (CaptionType captionType : captionTypes) {
      moduleNames.add(downloader.getModuleName(captionType));
    }
    downloader.download(context, moduleNames.toArray(new String[0]));
  }

  private void onDownloadStateUpdate(DownloadState downloadState) {
    if (!isForMyModules(downloadState)) {
      return;
    }
    switch (downloadState.downloadStatus()) {
      case INSTALLED -> {
        SharedPreferencesUtils.putBooleanPref(
            prefs, context.getResources(), preferenceKeys.installedKey, true);
        SharedPreferencesUtils.putBooleanPref(
            prefs, context.getResources(), preferenceKeys.uninstalledKey, false);
        initModule();
        if (downloadStateListener != null) {
          downloadStateListener.onInstalled();
        }
      }
      case FAILED -> {
        if (downloadStateListener != null) {
          downloadStateListener.onFailed(toErrorCode(downloadState.downloadErrorCode()));
        }
      }
      default -> {}
    }
  }

  private boolean isForMyModules(DownloadState downloadState) {
    for (CaptionType captionType : captionTypes) {
      if (downloadState.moduleNames().contains(downloader.getModuleName(captionType))) {
        return true;
      }
    }
    return false;
  }

  private static @ErrorCode int toErrorCode(DownloadErrorCode downloadErrorCode) {
    return switch (downloadErrorCode) {
      case NETWORK_ERROR -> Request.ERROR_NETWORK_ERROR;
      case INSUFFICIENT_STORAGE -> Request.ERROR_INSUFFICIENT_STORAGE;
      default -> Request.ERROR_UNKNOWN;
    };
  }
}
