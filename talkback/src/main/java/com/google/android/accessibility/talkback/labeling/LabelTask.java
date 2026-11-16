/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.labeling;

import android.os.AsyncTask;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/** LabelTask */
public class LabelTask<T> extends AsyncTask<Void, Void, T> {

  private static final String TAG = "LabelTask";

  /** TrackedTaskCallback */
  public interface TrackedTaskCallback {
    public void onTaskPreExecute(LabelClientRequest<?> request);

    public void onTaskPostExecute(LabelClientRequest<?> request);
  }

  private final LabelClientRequest<T> request;
  private final TrackedTaskCallback callback;
  private volatile boolean backgroundFailed;

  public LabelTask(LabelClientRequest<T> request, TrackedTaskCallback callback) {
    this.callback = callback;
    this.request = request;
  }

  @Override
  protected void onPreExecute() {
    if (callback != null) {
      callback.onTaskPreExecute(request);
    }
    super.onPreExecute();
  }

  /**
   * See {@link AsyncTask#onPostExecute}.
   *
   * <p>If overridden in a child class, this method should be invoked after any processing by the
   * child is complete. Failing to do so, or doing so out of order may result in failure to release
   * or premature release of resources.
   *
   * <p>When the background work throws, the request's own post-execute step is skipped: some
   * requests cannot handle a null result. The tracked-task callback still runs, so the task is
   * always accounted for and its resources released.
   */
  @Override
  protected void onPostExecute(T result) {
    if (!backgroundFailed) {
      request.onPostExecute(result);
    }
    if (callback != null) {
      callback.onTaskPostExecute(request);
    }
    super.onPostExecute(result);
  }

  @Override
  protected T doInBackground(Void... params) {
    try {
      return request.doInBackground();
    } catch (RuntimeException e) {
      backgroundFailed = true;
      LogUtils.e(TAG, e, "LabelTask failed in doInBackground");
      return null;
    }
  }
}
