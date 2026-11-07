/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.menurules

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.annotation.RequiresApi
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.contextmenu.ContextMenu
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem
import com.google.android.accessibility.talkback.contextmenu.OnContextMenuItemClickListener
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class TextEntityMenu(private val pipeline: Pipeline.FeedbackReturner) : NodeMenu {
  sealed interface Entity {
    val text: String

    data class Web(override val text: String, val url: String) : Entity

    data class Email(override val text: String, val address: String) : Entity

    data class Phone(override val text: String, val number: String) : Entity

    data class Code(override val text: String) : Entity
  }

  data class Classified(val start: Int, val end: Int, val type: String)

  override fun accept(context: Context, node: AccessibilityNodeInfoCompat): Boolean {
    if (mayHold(node.text) || mayHold(node.contentDescription)) return true
    for (i in 0 until minOf(node.childCount, MAX_CHILDREN_CHECKED)) {
      val child = node.getChild(i) ?: continue
      if (mayHold(child.text) || mayHold(child.contentDescription)) return true
    }
    return false
  }

  override fun getMenuItemsForNode(
    context: Context,
    node: AccessibilityNodeInfoCompat,
    includeAncestors: Boolean,
  ): List<ContextMenuItem> {
    if (!includeAncestors) return emptyList()
    val text = textOf(node)
    val classified = classify(context, text, wait = noWait.get() != true)
    return find(text, linkedUrls(node), classified).mapIndexed { index, entity ->
      val label =
        when (entity) {
          is Entity.Web,
          is Entity.Email -> entity.text
          is Entity.Phone -> context.getString(R.string.menu_item_call_number, entity.text)
          is Entity.Code -> context.getString(R.string.menu_item_copy_code, entity.text)
        }
      ContextMenu.createMenuItem(context, R.id.group_links, FIRST_ITEM_ID + index, Menu.NONE, label)
        .apply {
          setOnMenuItemClickListener(
            object : OnContextMenuItemClickListener {
              override fun onMenuItemClick(item: MenuItem): Boolean = choose(context, entity)
            }
          )
        }
    }
  }

  private fun choose(context: Context, entity: Entity): Boolean {
    val intent =
      when (entity) {
        is Entity.Web -> Intent(Intent.ACTION_VIEW, Uri.parse(entity.url))
        is Entity.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + entity.address))
        is Entity.Phone -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(entity.number)))
        is Entity.Code -> {
          val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
          clipboard.setPrimaryClip(ClipData.newPlainText(entity.text, entity.text))
          pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.speech(context.getString(R.string.template_text_copied, entity.text)),
          )
          return true
        }
      }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      LogUtils.w(TAG, "Nothing opens %s", entity)
      false
    }
  }

  companion object {
    private const val TAG = "TextEntityMenu"
    private const val FIRST_ITEM_ID = 1000
    private const val MAX_NODES = 40
    private const val MAX_CHARACTERS = 4_000
    private const val MAX_ENTITIES = 20
    private const val MAX_CHILDREN_CHECKED = 10
    private const val CLASSIFY_TIMEOUT_MS = 1_000L
    private val MAY_HOLD = Regex("\\p{Nd}{4}|@|://|[a-zA-Z0-9]\\.[a-zA-Z]{2,}")

    private fun mayHold(text: CharSequence?): Boolean =
      text != null && text.length >= 4 && MAY_HOLD.containsMatchIn(text)

    private val CODE = Regex("(?<!\\p{Nd})\\p{Nd}{4,8}(?!\\p{Nd})")

    private val CLASSIFIED_TYPES =
      listOf(
        TextClassifier.TYPE_PHONE,
        TextClassifier.TYPE_DATE,
        TextClassifier.TYPE_DATE_TIME,
        TextClassifier.TYPE_ADDRESS,
        TextClassifier.TYPE_FLIGHT_NUMBER,
        TextClassifier.TYPE_URL,
        TextClassifier.TYPE_EMAIL,
      )

    private val classifierThread =
      ThreadPoolExecutor(
          1,
          1,
          30,
          TimeUnit.SECONDS,
          ArrayBlockingQueue(1),
          RejectedExecutionHandler { task, executor ->
            if (executor.isShutdown) return@RejectedExecutionHandler
            (executor.queue.poll() as? Future<*>)?.cancel(false)
            executor.queue.offer(task)
          },
        )
        .apply { allowCoreThreadTimeOut(true) }
    private val lock = Any()
    private var pendingText: String? = null
    private var pending: Future<List<Classified>?>? = null
    private var pendingSince = 0L
    private var lastText: String? = null
    private var lastClassified: List<Classified>? = null
    private val noWait = ThreadLocal<Boolean>()
    @Volatile private var classifiedListener: Runnable? = null

    @JvmStatic
    fun setClassifiedListener(listener: Runnable?) {
      classifiedListener = listener
    }

    @JvmStatic
    fun isClassifying(): Boolean =
      synchronized(lock) {
        pending?.isDone == false &&
          SystemClock.uptimeMillis() - pendingSince < CLASSIFY_TIMEOUT_MS
      }

    @JvmStatic
    fun <T> withoutWaiting(block: Supplier<T>): T {
      val previous = noWait.get()
      noWait.set(true)
      try {
        return block.get()
      } finally {
        noWait.set(previous)
      }
    }

    @JvmStatic
    fun classify(context: Context, text: String, wait: Boolean): List<Classified>? {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || text.isBlank()) return null
      val future =
        synchronized(lock) {
          if (text == lastText && (lastClassified != null || !wait)) return lastClassified
          val current = pending
          if (text == pendingText && current != null && !current.isDone) {
            current
          } else {
            classifierThread
              .submit<List<Classified>?> { classifyNow(context, text) }
              .also {
                pending = it
                pendingText = text
                pendingSince = SystemClock.uptimeMillis()
              }
          }
        }
      if (!wait) return null
      return try {
        future.get(CLASSIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
      } catch (e: Exception) {
        LogUtils.w(TAG, "No text classification: %s", e)
        null
      }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun classifyNow(context: Context, text: String): List<Classified>? {
      val result =
        try {
          generateLinks(context, text)
        } catch (e: Throwable) {
          LogUtils.w(TAG, "Text classification failed: %s", e)
          null
        }
      synchronized(lock) {
        lastText = text
        lastClassified = result
      }
      classifiedListener?.run()
      return result
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateLinks(context: Context, text: String): List<Classified>? {
      val classifier =
        context.getSystemService(TextClassificationManager::class.java)?.textClassifier
          ?: return null
      if (classifier === TextClassifier.NO_OP) return null
      val request =
        TextLinks.Request.Builder(text)
          .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(CLASSIFIED_TYPES))
          .setDefaultLocales(LocaleList.getDefault())
          .build()
      return classifier.generateLinks(request).links.mapNotNull { link ->
        if (link.entityCount == 0) return@mapNotNull null
        val types = (0 until link.entityCount).map(link::getEntity)
        val type = if (TextClassifier.TYPE_PHONE in types) TextClassifier.TYPE_PHONE else types[0]
        Classified(link.start, link.end, type)
      }
    }

    @JvmStatic
    fun textOf(node: AccessibilityNodeInfoCompat): String {
      val text = StringBuilder()
      val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
      queue.add(node)
      var visited = 0
      while (queue.isNotEmpty() && visited < MAX_NODES && text.length < MAX_CHARACTERS) {
        val current = queue.removeFirst()
        visited++
        current.text?.let { text.append(it).append('\n') }
        current.contentDescription?.let { text.append(it).append('\n') }
        for (i in 0 until current.childCount) {
          current.getChild(i)?.let(queue::add)
        }
      }
      return text.take(MAX_CHARACTERS).toString()
    }

    private fun linkedUrls(node: AccessibilityNodeInfoCompat): Set<String> {
      val text = node.text as? Spanned ?: return emptySet()
      return text.getSpans(0, text.length, URLSpan::class.java).mapNotNull { it.url }.toSet()
    }

    private fun joinedBefore(text: String, start: Int): Boolean {
      val c = text.getOrNull(start - 1) ?: return false
      if (c.isLetterOrDigit() || c in "_@/\\#&=%+.,'–—" || isCurrency(c)) return true
      if (c == ':') return text.getOrNull(start - 2)?.isDigit() == true
      if (c == '-') {
        val p = text.getOrNull(start - 2) ?: return false
        if (!p.isLetterOrDigit()) return false
        return p.isDigit() || text.getOrNull(start - 3)?.isLetterOrDigit() == true
      }
      val p = if (c.isWhitespace()) text.getOrNull(start - 2) else c
      return isCurrency(p) || p == '©'
    }

    private fun joinedAfter(text: String, end: Int): Boolean {
      val c = text.getOrNull(end) ?: return false
      if (c.isLetterOrDigit() || c in "_@/\\%" || isCurrency(c)) return true
      if (c in ".,:-–—'") return text.getOrNull(end + 1)?.isLetterOrDigit() == true
      return c.isWhitespace() && isCurrency(text.getOrNull(end + 1))
    }

    private fun isCurrency(c: Char?): Boolean =
      c != null && Character.getType(c) == Character.CURRENCY_SYMBOL.toInt()

    private fun dialledNumber(shown: String): String {
      val number = if (shown.trimStart().startsWith('+')) shown.replace("(0)", "") else shown
      return PhoneNumberUtils.normalizeNumber(number)
    }

    @JvmStatic
    fun find(
      text: String,
      linked: Set<String> = emptySet(),
      classified: List<Classified>? = null,
    ): List<Entity> {
      if (text.isBlank()) return emptyList()
      val spannable = SpannableString(text)
      Linkify.addLinks(spannable, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
      val found = LinkedHashMap<String, Entity>()
      val taken = mutableListOf<IntRange>()
      val spans =
        spannable.getSpans(0, spannable.length, URLSpan::class.java).sortedBy {
          spannable.getSpanStart(it)
        }
      for (span in spans) {
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        val shown = text.substring(start, end)
        val url = span.url ?: continue
        taken += start until end
        val entity =
          when {
            url.startsWith("mailto:") -> Entity.Email(shown, url.removePrefix("mailto:"))
            url in linked || shown in linked -> null
            else -> Entity.Web(shown, url)
          }
        if (entity != null) found.putIfAbsent(entity.text, entity)
      }
      if (classified == null) return found.values.take(MAX_ENTITIES)
      for (item in classified.sortedBy { it.start }) {
        if (item.start < 0 || item.end > text.length || item.start >= item.end) continue
        taken += item.start until item.end
        if (item.type != TextClassifier.TYPE_PHONE) continue
        val shown = text.substring(item.start, item.end)
        val number = dialledNumber(shown)
        if (number.isNotEmpty()) found.putIfAbsent(shown, Entity.Phone(shown, number))
      }
      for (match in CODE.findAll(text)) {
        val range = match.range
        if (taken.any { range.first in it || range.last in it }) continue
        if (joinedBefore(text, range.first) || joinedAfter(text, range.last + 1)) continue
        found.putIfAbsent(match.value, Entity.Code(match.value))
      }
      return found.values.take(MAX_ENTITIES)
    }
  }
}
