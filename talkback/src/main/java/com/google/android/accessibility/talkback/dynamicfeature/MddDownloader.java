/*
 * Copyright (C) 2024 Google Inc.
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

import android.app.Application;
import android.content.Context;
import com.google.android.accessibility.talkback.imagecaption.ImageCaptionUtils.CaptionType;

/**
 * Downloads the dynamic feature via {@link MddManager}. There is no real MDD backend in this
 * fork, so every call is delegated to {@link SplitApkDownloader}, which reports the bundled
 * modules as installed.
 */
public class MddDownloader implements Downloader {

  private final SplitApkDownloader delegate;

  private MddDownloader(Context context) {
    delegate = SplitApkDownloader.getInstance(context);
  }

  public static MddDownloader getInstance(Application application) {
    return new MddDownloader(application);
  }

  @Override
  public String getModuleName(CaptionType captionType) {
    return delegate.getModuleName(captionType);
  }

  @Override
  public void download(Context context, String... name) {
    delegate.download(context, name);
  }

  @Override
  public void uninstall(Context context, String... name) {
    delegate.uninstall(context, name);
  }

  @Override
  public boolean isDownloading(String name) {
    return delegate.isDownloading(name);
  }

  @Override
  public boolean isInstalled(String name) {
    return delegate.isInstalled(name);
  }

  @Override
  public void registerListener(DownloadStateUpdateListener listener) {
    delegate.registerListener(listener);
  }

  @Override
  public void unregisterListener(DownloadStateUpdateListener listener) {
    delegate.unregisterListener(listener);
  }

  @Override
  public DownloadStatus getDownloadStatus(String name) {
    return delegate.getDownloadStatus(name);
  }

  @Override
  public void updateDownloadStatus(String name, DownloadStatus status) {
    delegate.updateDownloadStatus(name, status);
  }

  @Override
  public void updateAllDownloadStatus() {
    delegate.updateAllDownloadStatus();
  }

  @Override
  public boolean install(Context context, CaptionType captionType) {
    return delegate.install(context, captionType);
  }

  @Override
  public void initialize(Context context) {
    delegate.initialize(context);
  }
}
