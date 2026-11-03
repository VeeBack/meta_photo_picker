package com.example.meta_photo_picker.meta_photo_picker

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Creation date (ISO 8601, UTC) and orientation read from a photo's EXIF data. */
internal data class ExifMetadata(
  val creationDate: String?,
  val orientation: String
) {
  companion object {
    const val DEFAULT_ORIENTATION = "Up"
    val EMPTY = ExifMetadata(creationDate = null, orientation = DEFAULT_ORIENTATION)
  }
}

internal object ExifMetadataReader {
  private const val TAG = "ExifMetadataReader"

  /**
   * Reads the creation date and orientation of [file].
   *
   * Returns [ExifMetadata.EMPTY] when the EXIF data can't be read, so the photo is still picked.
   * That includes an [OutOfMemoryError]: ExifInterface sizes its buffer from a length stored in
   * the file, and a photo whose EXIF data has a broken length makes it ask for a single array far
   * larger than the heap. The allocation fails before any memory is taken, so it is safe to carry on.
   */
  fun read(file: File): ExifMetadata {
    return try {
      val exif = ExifInterface(file.absolutePath)
      ExifMetadata(
        creationDate = readCreationDate(exif),
        orientation = readOrientation(exif)
      )
    } catch (error: OutOfMemoryError) {
      Log.w(TAG, "Skipped EXIF data, not enough memory to read it: ${error.message}")
      ExifMetadata.EMPTY
    } catch (error: Exception) {
      Log.w(TAG, "Error reading EXIF data: ${error.message}")
      ExifMetadata.EMPTY
    }
  }

  private fun readCreationDate(exif: ExifInterface): String? {
    val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
      ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
      ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
      ?: return null

    return try {
      val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
      val date = exifDateFormat.parse(dateString) ?: return null
      formatAsIsoUtc(date)
    } catch (error: Exception) {
      Log.w(TAG, "Error parsing EXIF date: ${error.message}")
      null
    }
  }

  private fun readOrientation(exif: ExifInterface): String {
    val exifOrientation = exif.getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_NORMAL
    )

    return when (exifOrientation) {
      ExifInterface.ORIENTATION_NORMAL -> ExifMetadata.DEFAULT_ORIENTATION
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "UpMirrored"
      ExifInterface.ORIENTATION_ROTATE_180 -> "Down"
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> "DownMirrored"
      ExifInterface.ORIENTATION_TRANSPOSE -> "LeftMirrored"
      ExifInterface.ORIENTATION_ROTATE_90 -> "Right"
      ExifInterface.ORIENTATION_TRANSVERSE -> "RightMirrored"
      ExifInterface.ORIENTATION_ROTATE_270 -> "Left"
      else -> ExifMetadata.DEFAULT_ORIENTATION
    }
  }

  /** Formats [date] as ISO 8601 in UTC, e.g. 2026-01-15T10:30:00Z. */
  fun formatAsIsoUtc(date: Date): String {
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    isoFormat.timeZone = TimeZone.getTimeZone("UTC")
    return isoFormat.format(date)
  }
}
