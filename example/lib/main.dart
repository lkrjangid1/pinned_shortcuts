import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pinned_shortcuts/flutter_pinned_shortcuts.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize the plugin
  await FlutterPinnedShortcuts.initialize();

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _shortcutStatus = 'No shortcut clicked yet';
  bool _isSupported = false;
  final String _shortcutId = 'test_shortcut';

  @override
  void initState() {
    super.initState();
    _checkSupport();
    _setupShortcutListener();
  }

  // Check if pinned shortcuts are supported on this device
  Future<void> _checkSupport() async {
    final isSupported = await FlutterPinnedShortcuts.isSupported();
    setState(() {
      _isSupported = isSupported;
    });
  }

  // Set up a listener for shortcut clicks
  void _setupShortcutListener() {
    // Make sure to keep reference to the subscription to prevent it from being garbage collected
    final subscription =
        FlutterPinnedShortcuts.onShortcutClick.listen((Map resultData) {
      debugPrint('Shortcut click received in Flutter: $resultData');
      if (mounted) {
        setState(() {
          _shortcutStatus =
              'Shortcut clicked: id : ${resultData['id']}, extraData: ${resultData['extraData']}';
        });
      }
    });

    // Store the subscription to cancel it when disposing
    _subscription = subscription;
  }

  // Add a field to store the subscription
  StreamSubscription<Map>? _subscription;

  // Create a pinned shortcut with a Flutter asset image
  Future<void> _createAssetShortcut() async {
    await FlutterPinnedShortcuts.createPinnedShortcut(
      id: _shortcutId,
      label: 'Asset Shortcut',
      imageSource: 'assets/icon.png',
      imageSourceType: ImageSourceType.asset,
      extraData: {'source': 'asset'},
    );
  }

  // Create a pinned shortcut with an Android resource image
  Future<void> _createResourceShortcut() async {
    debugPrint('Creating resource shortcut: ${_shortcutId}_res');
    final result = await FlutterPinnedShortcuts.createPinnedShortcut(
      id: '${_shortcutId}_res',
      label: 'Resource Shortcut',
      imageSource: 'drawable_asset_icon',
      imageSourceType: ImageSourceType.resource,
      extraData: {'source': 'resource'},
    );

    setState(() {
      _shortcutStatus = result
          ? 'Resource shortcut created successfully'
          : 'Failed to create resource shortcut';
    });
  }

  // Create a pinned shortcut with a network image
  Future<void> _createNetworkShortcut() async {
    debugPrint('Creating network shortcut: ${_shortcutId}_net');
    final result = await FlutterPinnedShortcuts.createPinnedShortcut(
      id: '${_shortcutId}_net',
      label: 'Network Shortcut',
      imageSource: 'https://cdn-icons-png.flaticon.com/256/8816/8816518.png',
      imageSourceType: ImageSourceType.network,
      extraData: {'source': 'network'},
    );

    setState(() {
      _shortcutStatus = result
          ? 'Network shortcut created successfully'
          : 'Failed to create network shortcut';
    });
  }

  // Create a pinned shortcut with adaptive icon
  Future<void> _createAdaptiveShortcut() async {
    debugPrint('Creating adaptive shortcut: ${_shortcutId}_adaptive');
    final result = await FlutterPinnedShortcuts.createPinnedShortcut(
      id: '${_shortcutId}_adaptive',
      label: 'Adaptive Icon',
      imageSource: 'https://i.imgur.com/2GDmYA4.png', // Legacy fallback
      imageSourceType: ImageSourceType.network,
      adaptiveIconForeground: 'https://i.imgur.com/2GDmYA4.png',
      adaptiveIconBackground: '#2196F3', // Material Blue
      adaptiveIconBackgroundType: AdaptiveIconBackgroundType.color,
      extraData: {'type': 'adaptive_color'},
    );

    setState(() {
      _shortcutStatus = result
          ? 'Adaptive shortcut created successfully'
          : 'Failed to create adaptive shortcut';
    });
  }

  // Create a pinned shortcut with adaptive icon with image background
  Future<void> _createAdaptiveImageShortcut() async {
    debugPrint('Creating adaptive image shortcut: ${_shortcutId}_adaptive_img');
    final result = await FlutterPinnedShortcuts.createPinnedShortcut(
      id: '${_shortcutId}_adaptive_img',
      label: 'Adaptive Image',
      imageSource: 'assets/icon.png', // Legacy fallback
      imageSourceType: ImageSourceType.asset,
      adaptiveIconForeground: 'assets/icon_foreground.png',
      adaptiveIconBackground: 'assets/icon_background.png',
      adaptiveIconBackgroundType: AdaptiveIconBackgroundType.image,
      extraData: {'type': 'adaptive_image'},
    );

    setState(() {
      _shortcutStatus = result
          ? 'Adaptive image shortcut created successfully'
          : 'Failed to create adaptive image shortcut';
    });
  }

  // Check if a shortcut is pinned
  Future<void> _checkShortcut() async {
    debugPrint('Checking if shortcut is pinned: $_shortcutId');
    final isPinned = await FlutterPinnedShortcuts.isPinned(_shortcutId);

    setState(() {
      _shortcutStatus = isPinned
          ? 'Shortcut $_shortcutId is pinned'
          : 'Shortcut $_shortcutId is not pinned';
    });

    debugPrint(
        'Shortcut $_shortcutId is ${isPinned ? "pinned" : "not pinned"}');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Flutter Pinned Shortcuts Example'),
        ),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  'Pinned Shortcuts ${_isSupported ? 'are supported' : 'are NOT supported'} on this device',
                  style: Theme.of(context).textTheme.titleMedium,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 20),
                Text(
                  _shortcutStatus,
                  style: Theme.of(context).textTheme.bodyLarge,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 40),
                ElevatedButton(
                  onPressed: _isSupported ? _createAssetShortcut : null,
                  child: const Text('Create Asset Shortcut'),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _isSupported ? _createResourceShortcut : null,
                  child: const Text('Create Resource Shortcut'),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _isSupported ? _createNetworkShortcut : null,
                  child: const Text('Create Network Shortcut'),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _isSupported ? _createAdaptiveShortcut : null,
                  child: const Text('Create Adaptive Shortcut'),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _isSupported ? _createAdaptiveImageShortcut : null,
                  child: const Text('Create Adaptive with image Shortcut'),
                ),
                const SizedBox(height: 10),
                ElevatedButton(
                  onPressed: _isSupported ? _checkShortcut : null,
                  child: const Text('Check If Shortcut Is Pinned'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    // Cancel the subscription when the widget is disposed
    _subscription?.cancel();
    FlutterPinnedShortcuts.dispose();
    super.dispose();
  }
}
