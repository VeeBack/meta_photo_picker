package com.example.meta_photo_picker.meta_photo_picker

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.*
import android.graphics.BitmapFactory
import android.util.Log

/** MetaPhotoPickerPlugin */
class MetaPhotoPickerPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
  private lateinit var channel : MethodChannel
  private var activity: Activity? = null
  private var pendingResult: Result? = null
  private var pickerConfig: Map<String, Any>? = null
  
  companion object {
    private const val REQUEST_CODE_PICK_IMAGES = 1001
    private const val TAG = "MetaPhotoPickerPlugin"
  }

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "meta_photo_picker")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${android.os.Build.VERSION.RELEASE}")
      }
      "pickPhotos" -> {
        if (activity == null) {
          result.error("NO_ACTIVITY", "Activity is not available", null)
          return
        }
        
        pendingResult = result
        pickerConfig = call.arguments as? Map<String, Any>
        
        val selectionLimit = (pickerConfig?.get("selectionLimit") as? Int) ?: 1
        
        // Use PickVisualMedia for image selection
        val intent = Intent(Intent.ACTION_PICK).apply {
          type = "image/*"
          putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
          
          if (selectionLimit != 1) {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
          }
        }
        
        activity?.startActivityForResult(
          Intent.createChooser(intent, "Select Images"),
          REQUEST_CODE_PICK_IMAGES
        )
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode == REQUEST_CODE_PICK_IMAGES) {
      if (resultCode == Activity.RESULT_OK && data != null) {
        channel.invokeMethod("onLoadStarted", null)
        
        val uris = mutableListOf<Uri>()
        
        if (data.clipData != null) {
          val clipData = data.clipData!!
          for (i in 0 until clipData.itemCount) {
            clipData.getItemAt(i).uri?.let { uris.add(it) }
          }
        } else if (data.data != null) {
          uris.add(data.data!!)
        }
        
        if (uris.isEmpty()) {
          pendingResult?.success(null)
          pendingResult = null
          return true
        }
        
        val photoInfoList = mutableListOf<Map<String, Any>>()
        
        for (uri in uris) {
          try {
            val photoInfo = processImageUri(uri)
            if (photoInfo != null) {
              photoInfoList.add(photoInfo)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
          }
        }
        
        channel.invokeMethod("onLoadEnded", null)
        
        if (photoInfoList.isEmpty()) {
          pendingResult?.success(null)
        } else {
          pendingResult?.success(photoInfoList)
        }
      } else {
        pendingResult?.success(null)
      }
      
      pendingResult = null
      return true
    }
    return false
  }
  
  private fun processImageUri(uri: Uri): Map<String, Any>? {
    val context = activity ?: return null
    
    try {
      val contentResolver = context.contentResolver
      
      var fileName = "image.jpg"
      var fileSize = 0L
      
      contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
          
          if (nameIndex != -1) {
            fileName = cursor.getString(nameIndex) ?: fileName
          }
          if (sizeIndex != -1) {
            fileSize = cursor.getLong(sizeIndex)
          }
        }
      }
      
      // Try to get real filename from MediaStore if needed
      if (fileName == "Unknown" || isNumericFilename(fileName)) {
        val realName = getRealFilenameFromMediaStore(context, uri)
        if (realName != null && !isNumericFilename(realName)) {
          fileName = realName
        }
      }
      
      val destinationDir = pickerConfig?.get("destinationDirectory") as? String
      val targetFile: File
      val finalFilePath: String
      
      if (destinationDir != null) {
        val destDir = File(destinationDir)
        if (!destDir.exists()) {
          destDir.mkdirs()
        }
        
        val fileNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "jpg")
        
        targetFile = getUniqueFile(destDir, fileNameWithoutExt, extension)
        finalFilePath = targetFile.absolutePath
      } else {
        val tempDir = File(context.cacheDir, "picked_photos")
        if (!tempDir.exists()) {
          tempDir.mkdirs()
        }
        
        val extension = fileName.substringAfterLast(".", "jpg")
        targetFile = File(tempDir, "picked_${UUID.randomUUID()}.$extension")
        finalFilePath = targetFile.absolutePath
      }
      
      contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(targetFile).use { output ->
          input.copyTo(output)
        }
      }
      
      if (fileSize == 0L) {
        fileSize = targetFile.length()
      }
      
      val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
      BitmapFactory.decodeFile(targetFile.absolutePath, options)
      
      val width = options.outWidth
      val height = options.outHeight
      
      var fileType = "JPEG"
      val mimeType = options.outMimeType
      if (mimeType != null) {
        when {
          mimeType.contains("png") -> fileType = "PNG"
          mimeType.contains("heic") || mimeType.contains("heif") -> fileType = "HEIC"
          mimeType.contains("gif") -> fileType = "GIF"
          mimeType.contains("webp") -> fileType = "WEBP"
        }
      }
      
      val exifMetadata = ExifMetadataReader.read(targetFile)
      val creationDate = exifMetadata.creationDate ?: ExifMetadataReader.formatAsIsoUtc(Date())
      val orientation = exifMetadata.orientation
      
      val fileSizeFormatted = formatBytes(fileSize.toDouble())
      
      return mapOf(
        "id" to UUID.randomUUID().toString(),
        "fileName" to (if (destinationDir != null) targetFile.name else fileName),
        "fileSizeBytes" to fileSize,
        "fileSize" to fileSizeFormatted,
        "dimensions" to mapOf(
          "width" to width,
          "height" to height
        ),
        "creationDate" to creationDate,
        "fileType" to fileType,
        "assetIdentifier" to uri.toString(),
        "filePath" to finalFilePath,
        "scale" to 1.0,
        "orientation" to orientation
      )
      
    } catch (e: Exception) {
      Log.e(TAG, "Error processing image URI: ${e.message}", e)
      return null
    }
  }
  
  private fun isNumericFilename(filename: String): Boolean {
    return filename.matches(Regex("^[0-9]+$")) ||
           filename.matches(Regex("^[0-9]+\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE))
  }
  
  private fun getRealFilenameFromMediaStore(context: android.content.Context, uri: Uri): String? {
    try {
      if (DocumentsContract.isDocumentUri(context, uri)) {
        val docId = DocumentsContract.getDocumentId(uri)
        val authority = uri.authority
        
        if (authority == "com.android.providers.media.documents") {
          val split = docId.split(":")
          if (split.size >= 2) {
            val type = split[0]
            val id = split[1]
            
            val contentUri = when (type) {
              "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
              "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
              "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
              else -> return null
            }
            
            val selection = "_id=?"
            val selectionArgs = arrayOf(id)
            
            return queryFilename(context, contentUri, selection, selectionArgs)
          }
        } else if (authority == "com.google.android.apps.photos.contentprovider" ||
                   authority?.contains("photos") == true) {
          return queryFilename(context, uri, null, null)
        }
      }
      
      val pathSegments = uri.pathSegments
      if (pathSegments.size >= 2) {
        val lastSegment = pathSegments.last()
        if (lastSegment.all { it.isDigit() }) {
          val mediaUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            lastSegment.toLong()
          )
          return queryFilename(context, mediaUri, null, null)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error getting real filename: ${e.message}", e)
    }
    return null
  }
  
  private fun queryFilename(context: android.content.Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
    val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATA)
    
    val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
    cursor?.use {
      if (it.moveToFirst()) {
        val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
        if (dataIndex != -1) {
          val filePath = it.getString(dataIndex)
          if (filePath != null) {
            val extractedName = filePath.substringAfterLast('/')
            if (extractedName.isNotEmpty() && !isNumericFilename(extractedName)) {
              return extractedName
            }
          }
        }
        
        val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
        if (nameIndex != -1) {
          val displayName = it.getString(nameIndex)
          if (displayName != null && !isNumericFilename(displayName)) {
            return displayName
          }
        }
      }
    }
    return null
  }
  
  private fun getUniqueFile(directory: File, fileName: String, extension: String): File {
    var file = File(directory, "$fileName.$extension")
    var counter = 1
    
    while (file.exists()) {
      file = File(directory, "$fileName ($counter).$extension")
      counter++
    }
    
    return file
  }
  
  private fun formatBytes(bytes: Double): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    
    return when {
      bytes >= gb -> String.format("%.2f GB", bytes / gb)
      bytes >= mb -> String.format("%.2f MB", bytes / mb)
      bytes >= kb -> String.format("%.2f KB", bytes / kb)
      else -> String.format("%.0f bytes", bytes)
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivity() {
    activity = null
  }
}
