package com.example.meta_photo_picker.meta_photo_picker

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ExifMetadataReaderTest {
  private val photoFiles = mutableListOf<File>()
  private val deviceTimeZone = TimeZone.getDefault()

  // EXIF dates have no time zone and are read as device time, so pin it for a stable result.
  @BeforeTest
  fun useUtcAsDeviceTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @AfterTest
  fun cleanUp() {
    TimeZone.setDefault(deviceTimeZone)
    photoFiles.forEach { it.delete() }
  }

  @Test
  fun read_photoWithDateAndOrientation_returnsThem() {
    val dateText = "2026:01:15 10:30:00\u0000".toByteArray(Charsets.US_ASCII)
    // IFD0 holds 2 entries, so its value area starts after: count (2) + 2 entries (24) + next IFD (4).
    val dateOffset = TIFF_HEADER_SIZE + 2 + 2 * IFD_ENTRY_SIZE + 4
    val photo = writeJpegWithExif(
      entries = listOf(
        IfdEntry(TAG_ORIENTATION, FORMAT_USHORT, 1, ORIENTATION_ROTATE_90 shl 16),
        IfdEntry(TAG_DATETIME, FORMAT_ASCII, dateText.size, dateOffset)
      ),
      valueArea = dateText
    )

    val metadata = ExifMetadataReader.read(photo)

    assertEquals("Right", metadata.orientation)
    assertEquals("2026-01-15T10:30:00Z", metadata.creationDate)
  }

  @Test
  fun read_photoWithoutExifData_returnsEmptyMetadata() {
    val photo = createPhotoFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

    assertEquals(ExifMetadata.EMPTY, ExifMetadataReader.read(photo))
  }

  @Test
  fun read_photoWhoseExifSizeIsLargerThanTheHeap_returnsEmptyMetadataInsteadOfCrashing() {
    // The size seen in the Crashlytics report: one 544 MiB array, more than the test heap (256 MB,
    // the same ceiling as the phones in the report).
    val claimedSize = 544 * 1024 * 1024
    val photo = writeJpegWithExif(
      entries = listOf(IfdEntry(TAG_IMAGE_DESCRIPTION, FORMAT_ASCII, claimedSize, TIFF_HEADER_SIZE))
    )

    assertEquals(ExifMetadata.EMPTY, ExifMetadataReader.read(photo))
  }

  private data class IfdEntry(val tag: Int, val format: Int, val count: Int, val valueOrOffset: Int)

  private fun writeJpegWithExif(entries: List<IfdEntry>, valueArea: ByteArray = ByteArray(0)): File {
    val tiff = ByteArrayOutputStream()
    DataOutputStream(tiff).apply {
      writeBytes("MM")
      writeShort(42)
      writeInt(TIFF_HEADER_SIZE)
      writeShort(entries.size)
      entries.forEach { entry ->
        writeShort(entry.tag)
        writeShort(entry.format)
        writeInt(entry.count)
        writeInt(entry.valueOrOffset)
      }
      writeInt(0)
      write(valueArea)
    }

    val exifPayload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.toByteArray()
    val jpeg = ByteArrayOutputStream()
    DataOutputStream(jpeg).apply {
      writeShort(0xFFD8)
      writeShort(0xFFE1)
      writeShort(exifPayload.size + 2)
      write(exifPayload)
      writeShort(0xFFD9)
    }
    return createPhotoFile(jpeg.toByteArray())
  }

  private fun createPhotoFile(bytes: ByteArray): File {
    val file = File.createTempFile("exif_metadata_reader_test", ".jpg")
    file.writeBytes(bytes)
    photoFiles.add(file)
    return file
  }

  private companion object {
    const val TIFF_HEADER_SIZE = 8
    const val IFD_ENTRY_SIZE = 12
    const val TAG_IMAGE_DESCRIPTION = 0x010E
    const val TAG_ORIENTATION = 0x0112
    const val TAG_DATETIME = 0x0132
    const val FORMAT_ASCII = 2
    const val FORMAT_USHORT = 3
    const val ORIENTATION_ROTATE_90 = 6
  }
}
