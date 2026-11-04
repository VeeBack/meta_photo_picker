## 0.0.5

* **Android:** Fixed a crash when picking a photo whose EXIF data claims an impossibly large field (for example 544 MB). Reading that field made `ExifInterface` request a single array larger than the heap, and the resulting `OutOfMemoryError` closed the app. EXIF reading now lives in `ExifMetadataReader`, which also catches `OutOfMemoryError`: the photo is still picked, without its EXIF creation date and orientation (the creation date falls back to the pick time, orientation to `Up`).
* Added unit tests for `ExifMetadataReader`, including the oversized-EXIF case under a 256 MB test heap.

## 0.0.4

* **Major Architecture Change:**
  - **Android:** Replaced `wechat_assets_picker` with native file picker (ACTION_PICK intent)
  - **No permissions required:** Both iOS and Android now use privacy-preserving file pickers
  - **Removed dependencies:** Eliminated `wechat_assets_picker`, `photo_manager`, `permission_handler`, and `device_info_plus`
  - **Simplified API:** Removed `BuildContext` requirement from `pickPhotos()` and `pickSinglePhoto()`
* **Android Implementation:**
  - Native file picker using `ACTION_PICK` intent with `image/*` MIME type filtering
  - Full EXIF metadata extraction (dimensions, orientation, creation date, file type)
  - Intelligent filename resolution from MediaStore (handles numeric filenames from Google Photos)
  - Custom destination directory support with automatic duplicate handling
  - No storage permissions required - privacy-first approach
* **Breaking Changes:**
  - **Removed:** `checkPhotoAccessStatus()`, `requestPermission()`, `isPermissionGranted()` methods
  - **Removed:** `PhotoAccessStatus` enum
  - **Removed:** `context` parameter from `pickPhotos()` and `pickSinglePhoto()`
  - **Removed:** `PermissionStatus` export
  - **Android:** No longer requires storage permissions in AndroidManifest.xml
* **API Simplification:**
  - `pickPhotos()` now works without `BuildContext` on both platforms
  - `pickSinglePhoto()` now works without `BuildContext` on both platforms
  - Cleaner, more consistent API across iOS and Android
* **Dependencies:**
  - Added `androidx.exifinterface:exifinterface:1.3.7` to Android build.gradle
  - Removed 4 Flutter dependencies, reducing package size and complexity
* **Documentation:**
  - Complete README rewrite reflecting native file picker implementation
  - Removed all permission-related documentation and examples
  - Updated all code examples to remove `context` parameter
  - Updated platform comparison tables
  - Simplified setup instructions (no permissions needed)

## 0.0.3

* **Performance Improvements:**
  - **iOS optimization:** Photos now saved to disk with file paths instead of loading all bytes into memory
  - **Memory efficiency:** Added `filePath` field to `PhotoInfo` for better memory management
  - **EXIF metadata extraction:** iOS now extracts actual creation dates from EXIF/TIFF metadata without requiring photo library permission
  - **Improved file handling:** Optimized image processing pipeline on iOS for faster performance
* **New Features:**
  - **Load callbacks:** Added `onLoadStarted` and `onLoadEnded` callbacks to `pickPhotos()` and `pickSinglePhoto()` for showing loading indicators
  - **Custom destination directory:** Added `destinationDirectory` parameter to `PickerConfig` to save photos to a specific location
  - **Automatic duplicate handling:** Files with duplicate names are automatically renamed (e.g., "Image (1).jpg")
  - **File path support:** `imageData` is now optional - prefer using `filePath` for better memory usage
* **Breaking Changes:**
  - `PhotoInfo.imageData` is now nullable (`Uint8List?`) - check `filePath` first for optimal performance
  - Always prefer `Image.file(File(photo.filePath!))` over `Image.memory(photo.imageData!)`
* **Documentation Updates:**
  - Comprehensive README update reflecting all new features and improvements
  - Added examples for load callbacks and custom destination directory
  - Updated all code examples to use file paths instead of image data
  - Updated platform comparison tables to reflect all new features
  - Added FAQ entries for new features and best practices

## 0.0.2

* **Performance Improvements:**
  - **iOS optimization:** Photos now saved to disk with file paths instead of loading all bytes into memory
  - **Memory efficiency:** Added `filePath` field to `PhotoInfo` for better memory management
  - **EXIF metadata extraction:** iOS now extracts actual creation dates from EXIF/TIFF metadata without requiring photo library permission
  - **Improved file handling:** Optimized image processing pipeline on iOS for faster performance
* **New Features:**
  - **Load callbacks:** Added `onLoadStarted` and `onLoadEnded` callbacks to `pickPhotos()` and `pickSinglePhoto()` for showing loading indicators
  - **Custom destination directory:** Added `destinationDirectory` parameter to `PickerConfig` to save photos to a specific location
  - **Automatic duplicate handling:** Files with duplicate names are automatically renamed (e.g., "Image (1).jpg")
  - **File path support:** `imageData` is now optional - prefer using `filePath` for better memory usage
* **Breaking Changes:**
  - `PhotoInfo.imageData` is now nullable (`Uint8List?`) - check `filePath` first for optimal performance
  - Always prefer `Image.file(File(photo.filePath!))` over `Image.memory(photo.imageData!)`
* **Bug Fixes:**
  - Fixed Android picker showing "1/9999" count on confirm button - now shows reasonable default of 150 for unlimited selection
  - Disabled intrusive limited permission warning dialog on Android - picker now works smoothly without showing system warning overlay
* **Android Permission Improvements:**
  - Improved permission state handling for Android 14+ (API 34+)
  - Fixed `checkPhotoAccessStatus()` to use `Permission.photos.status` for more reliable state checking
  - Added `limitedPermissionOverlayPredicate` to prevent unwanted permission dialogs during photo selection
* **Documentation Updates:**
  - Comprehensive README update reflecting all new features and improvements
  - Added examples for load callbacks and custom destination directory
  - Updated all code examples to use file paths instead of image data
  - Added comprehensive documentation for Android limited permission behavior
  - Documented Android permission state caching and app restart requirement
  - Updated platform comparison tables to reflect all new features
  - Added FAQ entries for new features and best practices
  - Added troubleshooting section for permission status not updating
  - Included code examples for handling limited permission scenarios

## 0.0.1

* Initial release of meta_photo_picker
* **iOS implementation using PHPicker (iOS 14+)**
  - Privacy-preserving - no permission required
  - Shows all photos to user regardless of permission status
  - Native system picker UI
* **Android implementation using wechat_assets_picker (API 21+)**
  - Automatic permission handling
  - Version-aware permission requests (READ_MEDIA_IMAGES for Android 13+)
  - Direct selection without preview
* **Cross-platform features:**
  - Pick single or multiple images from photo library
  - Comprehensive photo metadata including:
    - File name, size (bytes and formatted), and type
    - Image dimensions (width, height) and aspect ratio
    - Creation date (ISO 8601 format)
    - Asset identifier
    - Image orientation and scale
    - Raw image data (Uint8List)
  - Configurable picker options:
    - Selection limit (single, multiple, unlimited)
    - Media type filters (images, videos, live photos)
    - Asset representation modes (iOS)
    - JPEG compression quality (default: no compression)
  - Permission status checking (checkPhotoAccessStatus, requestPermission, isPermissionGranted)
  - Type-safe Dart models (PhotoInfo, PickerConfig, PhotoDimensions, ImageOrientation)
* Complete example app with modern Material Design UI
* Comprehensive documentation and README
* Full test coverage
