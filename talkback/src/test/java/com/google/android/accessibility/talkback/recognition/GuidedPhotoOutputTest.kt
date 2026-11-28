package com.google.android.accessibility.talkback.recognition

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidedPhotoOutputTest {
  private lateinit var context: Context
  private lateinit var provider: PhotoProvider

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    provider = PhotoProvider(File.createTempFile("guided-photo", ".jpg", context.cacheDir))
    provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
    ShadowContentResolver.registerProviderInternal("media", provider)
  }

  @Test fun publishesOnlyItsOwnPendingPhotoAfterWriting() {
    val output = GuidedPhotoOutput.prepare(context)
    assertEquals(1, provider.inserted?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
    assertEquals("Pictures/Meridian", provider.inserted?.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
    output.stream!!.write(byteArrayOf(1, 2, 3))
    output.complete()
    assertEquals(0, provider.published?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
    assertEquals(provider.uri, provider.updated)
    assertTrue(provider.file.length() > 0)
    assertNull(provider.deleted)
    output.discard()
  }

  @Test fun failedStreamOpenRemovesThePendingRow() {
    provider.openFails = true
    assertThrows(FileNotFoundException::class.java) { GuidedPhotoOutput.prepare(context) }
    assertEquals(provider.uri, provider.deleted)
  }

  @Test fun discardingAnInterruptedCaptureNeverPublishesIt() {
    val output = GuidedPhotoOutput.prepare(context)
    output.stream!!.write(1)
    output.discard()
    assertEquals(provider.uri, provider.deleted)
    assertNull(provider.published)
  }

  @Test @Config(sdk = [28])
  fun legacyOutputUsesUniqueFilesAndDeletesCancelledCaptures() {
    val first = GuidedPhotoOutput.prepare(context)
    val second = GuidedPhotoOutput.prepare(context)
    assertNotEquals(first.file, second.file)
    assertEquals("Meridian", first.file!!.parentFile!!.name)
    first.file!!.writeBytes(byteArrayOf(1, 2, 3))
    first.complete()
    first.discard()
    second.discard()
    assertFalse(first.file!!.exists())
  }

  class PhotoProvider(val file: File) : ContentProvider() {
    val uri: Uri = Uri.parse("content://media/external/images/media/123")
    var inserted: ContentValues? = null
    var published: ContentValues? = null
    var updated: Uri? = null
    var deleted: Uri? = null
    var openFails = false
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
      inserted = ContentValues(values)
      return this.uri
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      if (openFails) throw FileNotFoundException("Storage unavailable")
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
      updated = uri
      published = ContentValues(values)
      return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
      deleted = uri
      file.delete()
      return 1
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/jpeg"
  }
}
