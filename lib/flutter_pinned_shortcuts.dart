import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';

/// Main class for the Flutter Pinned Shortcuts plugin.
class FlutterPinnedShortcuts {
  static const MethodChannel _channel =
      MethodChannel('pinned_shortcuts');

  static final StreamController<Map> _shortcutClickController =
      StreamController<Map>.broadcast();

  /// Stream for listening to shortcut clicks.
  /// {
  /// "id" : "test_id",
  /// "extraData" : {"data": "input map data"}
  /// }
  static Stream<Map> get onShortcutClick => _shortcutClickController.stream;

  static bool _isInitialized = false;

  /// Initialize the plugin and set up shortcut click listener.
  static Future<void> initialize() async {
    if (_isInitialized) return;

    // Set the method call handler
    _channel.setMethodCallHandler((call) async {
      debugPrint('Received method call: ${call.method}');

      switch (call.method) {
        case 'onShortcutClick':
          final returnData = call.arguments as Map;
          debugPrint('Shortcut clicked: $returnData');
          _shortcutClickController.add(returnData);
          break;
        default:
          debugPrint('Unknown method call: ${call.method}');
          break;
      }
    });

    _isInitialized = true;
  }

  /// Check if pinned shortcuts are supported on the device.
  static Future<bool> isSupported() async {
    try {
      return await _channel.invokeMethod('isSupported') ?? false;
    } catch (e) {
      return false;
    }
  }

  /// Get cold start shortcut intent data.
  static Future<Map<dynamic, dynamic>?> getInitialShortcut() async {
    try {
      return await _channel.invokeMethod('getInitialShortcut');
    } catch(_) {
      return null;
    }
  }

  /// Create a pinned shortcut with various image source options.
  ///
  /// Parameters:
  /// - [id]: Unique identifier for the shortcut
  /// - [label]: Text shown under the shortcut icon
  /// - [imageSource]: Source of the icon image
  /// - [imageSourceType]: Type of image source (asset, resource, network, file)
  /// - [longLabel]: Optional longer description for the shortcut
  /// - [extraData]: Optional data to be passed back when shortcut is clicked
  /// - [adaptiveIconForeground]: Optional foreground image for adaptive icon (Android 8.0+)
  /// - [adaptiveIconBackground]: Optional background color or image for adaptive icon (Android 8.0+)
  /// - [adaptiveIconBackgroundType]: Type of background (color or image)
  static Future<bool> createPinnedShortcut({
    required String id,
    required String label,
    required String imageSource,
    required ImageSourceType imageSourceType,
    String? longLabel,
    Map<String, dynamic>? extraData,
    String? adaptiveIconForeground,
    String? adaptiveIconBackground,
    AdaptiveIconBackgroundType adaptiveIconBackgroundType =
        AdaptiveIconBackgroundType.color,
  }) async {
    try {
      debugPrint('Creating pinned shortcut: $id');
      String? finalImagePath = imageSource;
      String? finalForegroundPath;

      // Handle different image source types
      if (imageSourceType == ImageSourceType.network) {
        // Download and save network image
        finalImagePath = await _downloadImage(imageSource, id);
      } else if (imageSourceType == ImageSourceType.asset) {
        // No additional processing needed for assets
        // as they will be handled by the native side
      }

      // Handle adaptive icon foreground if provided
      if (adaptiveIconForeground != null) {
        if (imageSourceType == ImageSourceType.network) {
          finalForegroundPath =
              await _downloadImage(adaptiveIconForeground, "${id}_fg");
        } else {
          finalForegroundPath = adaptiveIconForeground;
        }
      }

      final result = await _channel.invokeMethod<bool>(
        'createPinnedShortcut',
        {
          'id': id,
          'label': label,
          'longLabel': longLabel ?? label,
          'imageSource': finalImagePath,
          'imageSourceType': imageSourceType.name,
          'extraData': extraData != null ? jsonEncode(extraData) : null,
          'useAdaptiveIcon': adaptiveIconForeground != null,
          'adaptiveIconForeground': finalForegroundPath,
          'adaptiveIconBackground': adaptiveIconBackground,
          'adaptiveIconBackgroundType': adaptiveIconBackgroundType.name,
        },
      );

      debugPrint('Create shortcut result: ${result ?? false}');
      return result ?? false;
    } catch (e) {
      debugPrint('Error creating pinned shortcut: $e');
      return false;
    }
  }

  /// Check if a shortcut with the given ID is pinned
  static Future<bool> isPinned(String id) async {
    try {
      debugPrint('Checking if shortcut is pinned: $id');
      final result = await _channel.invokeMethod<bool>(
        'isPinned',
        {'id': id},
      );

      debugPrint('Is shortcut pinned result: ${result ?? false}');
      return result ?? false;
    } catch (e) {
      debugPrint('Error checking if shortcut is pinned: $e');
      return false;
    }
  }

  /// Download an image from a URL and save it locally
  static Future<String> _downloadImage(String url, String id) async {
    try {
      final response = await http.get(Uri.parse(url));

      if (response.statusCode != 200) {
        throw Exception('Failed to download image: ${response.statusCode}');
      }

      final tempDir = await getTemporaryDirectory();
      final filePath = path.join(tempDir.path, 'pinned_shortcut_$id.png');

      final file = File(filePath);
      await file.writeAsBytes(response.bodyBytes);

      return filePath;
    } catch (e) {
      debugPrint('Error downloading image: $e');
      rethrow;
    }
  }

  /// Dispose resources used by the plugin
  static void dispose() {
    _shortcutClickController.close();
  }
}

/// Enum for different types of image sources
enum ImageSourceType {
  /// Flutter asset from the assets folder
  asset,

  /// Android resource from res folder
  resource,

  /// Image from a network URL
  network,

  /// Image from a file path
  file
}

/// Enum for different types of adaptive icon backgrounds
enum AdaptiveIconBackgroundType {
  /// Color value (e.g. #FFFFFF or #AARRGGBB)
  color,

  /// Image source (same type as the main imageSourceType)
  image
}
