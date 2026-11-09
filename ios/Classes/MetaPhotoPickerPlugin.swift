import Flutter
import UIKit
import PhotosUI
import Photos
import UniformTypeIdentifiers
import ImageIO

public class MetaPhotoPickerPlugin: NSObject, FlutterPlugin {
    private var flutterResult: FlutterResult?
    private var pickerConfig: [String: Any]?
    private var channel: FlutterMethodChannel?
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "meta_photo_picker", binaryMessenger: registrar.messenger())
        let instance = MetaPhotoPickerPlugin()
        instance.channel = channel
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "getPlatformVersion":
            result("iOS " + UIDevice.current.systemVersion)
        case "pickPhotos":
            guard let args = call.arguments as? [String: Any] else {
                result(FlutterError(code: "INVALID_ARGUMENTS",
                                  message: "Invalid arguments",
                                  details: nil))
                return
            }
            self.flutterResult = result
            self.pickerConfig = args
            presentPhotoPicker(config: args)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func presentPhotoPicker(config: [String: Any]) {
        guard #available(iOS 14, *) else {
            completePicking(FlutterError(code: "UNSUPPORTED_VERSION",
                                       message: "PHPicker requires iOS 14 or later",
                                       details: nil))
            return
        }
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            var configuration = PHPickerConfiguration(photoLibrary: .shared())
            
            // Set selection limit
            if let selectionLimit = config["selectionLimit"] as? Int {
                configuration.selectionLimit = selectionLimit
            }
            
            // Set filter
            if let filterString = config["filter"] as? String {
                switch filterString {
                case "images":
                    configuration.filter = .images
                case "videos":
                    configuration.filter = .videos
                case "livePhotos":
                    configuration.filter = .livePhotos
                case "any":
                    configuration.filter = .any(of: [.images, .videos, .livePhotos])
                default:
                    configuration.filter = .images
                }
            }
            
            // Set preferred asset representation mode
            if let modeString = config["preferredAssetRepresentationMode"] as? String {
                switch modeString {
                case "automatic":
                    configuration.preferredAssetRepresentationMode = .automatic
                case "current":
                    configuration.preferredAssetRepresentationMode = .current
                case "compatible":
                    configuration.preferredAssetRepresentationMode = .compatible
                default:
                    configuration.preferredAssetRepresentationMode = .current
                }
            }
            
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            picker.presentationController?.delegate = self
            
            if let viewController = UIApplication.shared.keyWindow?.rootViewController {
                viewController.present(picker, animated: true)
            }
        }
    }
    
    private func completePicking(_ value: Any?) {
        let result = flutterResult
        flutterResult = nil
        result?(value)
    }

    private func formatBytes(_ bytes: Double) -> String {
        let kb = bytes / 1024
        let mb = kb / 1024
        let gb = mb / 1024
        
        if gb >= 1 {
            return String(format: "%.2f GB", gb)
        } else if mb >= 1 {
            return String(format: "%.2f MB", mb)
        } else if kb >= 1 {
            return String(format: "%.2f KB", kb)
        } else {
            return String(format: "%.0f bytes", bytes)
        }
    }
    
    private func orientationString(_ orientation: Int) -> String {
        switch orientation {
        case 1: return "Up"
        case 2: return "UpMirrored"
        case 3: return "Down"
        case 4: return "DownMirrored"
        case 5: return "LeftMirrored"
        case 6: return "Right"
        case 7: return "RightMirrored"
        case 8: return "Left"
        default: return "Up"
        }
    }
    
    private func getFileType(from typeIdentifier: String) -> String {
        if typeIdentifier.lowercased().contains("png") {
            return "PNG"
        } else if typeIdentifier.lowercased().contains("heic") {
            return "HEIC"
        } else if typeIdentifier.lowercased().contains("gif") {
            return "GIF"
        } else if typeIdentifier.lowercased().contains("jpeg") || typeIdentifier.lowercased().contains("jpg") {
            return "JPEG"
        }
        return "JPEG"
    }
}

@available(iOS 13, *)
extension MetaPhotoPickerPlugin: UIAdaptivePresentationControllerDelegate {
    public func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        completePicking(nil)
    }
}

@available(iOS 14, *)
extension MetaPhotoPickerPlugin: PHPickerViewControllerDelegate {
    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.presentationController?.delegate = nil
        picker.dismiss(animated: true)
        
        guard !results.isEmpty else {
            completePicking(nil) // User cancelled
            return
        }
        
        // Notify start of loading
        self.channel?.invokeMethod("onLoadStarted", arguments: nil)
        
        let group = DispatchGroup()
        let dataQueue = DispatchQueue(label: "com.metaphotopicker.dataQueue")
        var photoInfoList: [[String: Any]] = []
        // Limit concurrency to avoid memory spikes
        let semaphore = DispatchSemaphore(value: 3)
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            for result in results {
                group.enter()
                semaphore.wait()
                
                self.processResult(result) { info in
                    if let info = info {
                        dataQueue.sync {
                            photoInfoList.append(info)
                        }
                    }
                    semaphore.signal()
                    group.leave()
                }
            }
            
            group.notify(queue: .main) {
                // Notify end of loading
                self.channel?.invokeMethod("onLoadEnded", arguments: nil)
                self.completePicking(photoInfoList)
            }
        }
    }
    
    private func processResult(_ result: PHPickerResult, completion: @escaping ([String: Any]?) -> Void) {
        let itemProvider = result.itemProvider
        let assetIdentifier = result.assetIdentifier
        
        // Ensure we only process images to avoid memory issues with videos
        if !itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            completion(nil)
            return
        }
        
        // Find the best type identifier to load
        // Prefer explicit types over general "public.image"
        var typeIdentifierToLoad = UTType.image.identifier
        
        // Check for specific types we support
        if itemProvider.hasItemConformingToTypeIdentifier(UTType.png.identifier) {
            typeIdentifierToLoad = UTType.png.identifier
        } else if itemProvider.hasItemConformingToTypeIdentifier(UTType.jpeg.identifier) {
            typeIdentifierToLoad = UTType.jpeg.identifier
        } else if itemProvider.hasItemConformingToTypeIdentifier(UTType.heic.identifier) {
            typeIdentifierToLoad = UTType.heic.identifier
        } else if let firstType = itemProvider.registeredTypeIdentifiers.first {
             typeIdentifierToLoad = firstType
        }
        
        itemProvider.loadFileRepresentation(forTypeIdentifier: typeIdentifierToLoad) { [weak self] url, error in
            guard let self = self, let url = url else {
                if let error = error {
                    NSLog("Error loading file representation: \(error.localizedDescription)")
                }
                completion(nil)
                return
            }
            
            do {
                // Determine file type and name
                let fileType = self.getFileType(from: typeIdentifierToLoad)
                
                var originalFileName = "Unknown"
                if let suggestedName = itemProvider.suggestedName {
                    originalFileName = suggestedName
                } else {
                    originalFileName = url.lastPathComponent
                }
                
                // Determine destination
                let targetDir: URL
                let targetUrl: URL
                let finalFileName: String
                
                if let customPath = self.pickerConfig?["destinationDirectory"] as? String {
                    // Use custom directory
                    let customDirUrl = URL(fileURLWithPath: customPath)
                    
                    // Create directory if it doesn't exist
                    if !FileManager.default.fileExists(atPath: customPath) {
                        try? FileManager.default.createDirectory(at: customDirUrl, withIntermediateDirectories: true, attributes: nil)
                    }
                    
                    targetDir = customDirUrl
                    
                    // Separate name and extension
                    let fileExtension = (originalFileName as NSString).pathExtension
                    let fileNameWithoutExt = (originalFileName as NSString).deletingPathExtension
                    let ext = fileExtension.isEmpty ? fileType.lowercased() : fileExtension
                    
                    // Get unique filename
                    targetUrl = self.getUniqueDestinationUrl(in: targetDir, fileName: fileNameWithoutExt, fileExtension: ext)
                    finalFileName = targetUrl.lastPathComponent
                    
                } else {
                    // Use temporary directory
                    targetDir = self.getTemporaryDirectory()
                    let targetFileName = "picked_\(UUID().uuidString).\(fileType.lowercased())"
                    targetUrl = targetDir.appendingPathComponent(targetFileName)
                    finalFileName = originalFileName // Use original name in metadata, even if file on disk is UUID
                }
                
                try FileManager.default.copyItem(at: url, to: targetUrl)
                
                // Get dimensions efficiently without loading full image
                var width = 0
                var height = 0
                var orientation = "Up"
                var scale: CGFloat = 1.0
                var creationDate: String?
                
                // Read properties from the saved file
                if let source = CGImageSourceCreateWithURL(targetUrl as CFURL, nil) {
                    if let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] {
                        
                        // Dimensions
                        width = properties[kCGImagePropertyPixelWidth as String] as? Int ?? 0
                        height = properties[kCGImagePropertyPixelHeight as String] as? Int ?? 0
                        
                        // Orientation
                        let orientationKey = kCGImagePropertyOrientation as String
                        if let orientationNum = properties[orientationKey] as? Int {
                             orientation = self.orientationString(orientationNum)
                        }
                        
                        // Creation Date
                        var dateString: String?
                        
                        // Try EXIF first
                        if let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] {
                            if let exifDate = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String {
                                dateString = exifDate
                            } else if let exifDate = exif[kCGImagePropertyExifDateTimeDigitized as String] as? String {
                                dateString = exifDate
                            }
                        }
                        
                        // Try TIFF if EXIF failed
                        if dateString == nil {
                            if let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
                                if let tiffDate = tiff[kCGImagePropertyTIFFDateTime as String] as? String {
                                    dateString = tiffDate
                                }
                            }
                        }
                        
                        // Parse EXIF date format "yyyy:MM:dd HH:mm:ss" to ISO8601
                        if let dateString = dateString {
                            let dateFormatter = DateFormatter()
                            dateFormatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
                            if let date = dateFormatter.date(from: dateString) {
                                creationDate = ISO8601DateFormatter().string(from: date)
                            }
                        }
                    }
                }
                
                // Calculate file size from attributes
                let resources = try targetUrl.resourceValues(forKeys: [.fileSizeKey])
                let fileSizeInt = resources.fileSize ?? 0
                let fileSize = self.formatBytes(Double(fileSizeInt))
                
                let finalCreationDate = creationDate ?? ISO8601DateFormatter().string(from: Date())
                
                let photoInfo: [String: Any] = [
                    "id": UUID().uuidString,
                    "fileName": finalFileName,
                    "fileSizeBytes": fileSizeInt,
                    "fileSize": fileSize,
                    "dimensions": [
                        "width": width,
                        "height": height
                    ],
                    "creationDate": finalCreationDate as Any,
                    "fileType": fileType,
                    "assetIdentifier": assetIdentifier as Any,
                    "filePath": targetUrl.path,
                    "scale": scale,
                    "orientation": orientation
                ]
                
                completion(photoInfo)
                
            } catch {
                NSLog("Error processing file data: \(error.localizedDescription)")
                completion(nil)
            }
        }
    }
    
    private func getTemporaryDirectory() -> URL {
        let tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("picked_photos")
        if !FileManager.default.fileExists(atPath: tempDirectory.path) {
            try? FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true, attributes: nil)
        }
        return tempDirectory
    }
    
    private func getUniqueDestinationUrl(in directory: URL, fileName: String, fileExtension: String) -> URL {
        var finalName = "\(fileName).\(fileExtension)"
        var destinationUrl = directory.appendingPathComponent(finalName)
        var counter = 1
        
        while FileManager.default.fileExists(atPath: destinationUrl.path) {
            finalName = "\(fileName) (\(counter)).\(fileExtension)"
            destinationUrl = directory.appendingPathComponent(finalName)
            counter += 1
        }
        
        return destinationUrl
    }
}

