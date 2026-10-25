/*
 * FLauncher
 * Copyright (C) 2021  Étienne Fesser
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import 'dart:io';
import 'dart:async';

import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/gradients.dart';
import 'package:flauncher/providers/settings_service.dart';
import 'package:flutter/cupertino.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

class WallpaperService extends ChangeNotifier {
  final FLauncherChannel _fLauncherChannel;
  final SettingsService _settingsService;

  late File _wallpaperFile;
  late File _wallpaperDayFile;
  late File _wallpaperNightFile;
  Timer? _timer;

  // Photo slideshow: every image in <app external files>/Photos is shown in turn.
  static const Duration slideInterval = Duration(seconds: 45);
  Directory? _slideDir;
  List<File> _slides = [];
  int _slideIndex = 0;
  int _slideTicks = 0;
  Timer? _slideTimer;

  /// Folder to copy photos into (with `adb push`), or null before init.
  String? get slideshowFolder => _slideDir?.path;
  int get slideCount => _slides.length;

  ImageProvider? _wallpaper;
  int _version = 0;
  int _updateWallpaperCallCount = 0;

  ImageProvider?  get wallpaper     => _wallpaper;
  int             get version       => _version;

  FLauncherGradient get gradient => FLauncherGradients.all.firstWhere(
        (gradient) => gradient.uuid == _settingsService.gradientUuid,
        orElse: () => FLauncherGradients.pitchBlack,
      );

  WallpaperService(this._fLauncherChannel, this._settingsService) :
    _wallpaper = null
  {
    _settingsService.addListener(_onSettingsChanged);
    _init();
  }

  bool _lastTimeBasedEnabled = false;

  void _onSettingsChanged() {
    final enabled = _settingsService.timeBasedWallpaperEnabled;
    if (enabled != _lastTimeBasedEnabled) {
      _lastTimeBasedEnabled = enabled;
      _updateTimerState();
      _updateWallpaper();
    }
  }

  @override
  void dispose() {
    _settingsService.removeListener(_onSettingsChanged);
    _timer?.cancel();
    _slideTimer?.cancel();
    super.dispose();
  }

  Future<void> _init() async {
    final directory = await getApplicationDocumentsDirectory();
    _wallpaperFile = File("${directory.path}/wallpaper");
    _wallpaperDayFile = File("${directory.path}/wallpaper_day");
    _wallpaperNightFile = File("${directory.path}/wallpaper_night");

    try {
      final external = await getExternalStorageDirectory();
      if (external != null) {
        _slideDir = Directory("${external.path}/Photos");
        await _slideDir!.create(recursive: true);
        await _scanSlides();
      }
    } catch (_) {
      _slideDir = null;
    }
    _slideTimer = Timer.periodic(slideInterval, (_) => _nextSlide());

    _lastTimeBasedEnabled = _settingsService.timeBasedWallpaperEnabled;
    await _updateWallpaper();
    _updateTimerState();
  }

  void _updateTimerState() {
    final enabled = _settingsService.timeBasedWallpaperEnabled;
    if (enabled && (_timer == null || !_timer!.isActive)) {
      _timer = Timer.periodic(const Duration(minutes: 1), (_) => _updateWallpaper());
    } else if (!enabled && _timer != null) {
      _timer?.cancel();
      _timer = null;
    }
  }

  Future<void> _scanSlides() async {
    final dir = _slideDir;
    if (dir == null || !await dir.exists()) {
      _slides = [];
      return;
    }
    final files = <File>[];
    await for (final entity in dir.list(recursive: true, followLinks: false)) {
      if (entity is File) {
        final name = entity.path.toLowerCase();
        if (name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.png') || name.endsWith('.webp')) {
          files.add(entity);
        }
      }
    }
    files.shuffle();
    _slides = files;
    if (_slideIndex >= _slides.length) _slideIndex = 0;
  }

  Future<void> _nextSlide() async {
    // Pick up newly copied photos every ~10 minutes.
    if (++_slideTicks % 13 == 0 || _slides.isEmpty) {
      final hadSlides = _slides.isNotEmpty;
      await _scanSlides();
      if (_slides.isEmpty && !hadSlides) return;
    }
    if (_slides.isEmpty) {
      await _updateWallpaper(force: true);
      return;
    }
    _slideIndex = (_slideIndex + 1) % _slides.length;
    _version++;
    await _updateWallpaper(force: true);
  }

  Future<void> _updateWallpaper({bool force = false}) async {
    final callId = ++_updateWallpaperCallCount;
    final now = DateTime.now();
    final isDay = now.hour >= 6 && now.hour < 18;
    final enabled = _settingsService.timeBasedWallpaperEnabled;

    ImageProvider? newWallpaper;

    if (_slides.isNotEmpty) {
      // Decode at screen size so large phone photos don't exhaust the TV's memory.
      newWallpaper = ResizeImage(FileImage(_slides[_slideIndex % _slides.length]), width: 1920);
    } else if (enabled) {
      if (isDay && await _wallpaperDayFile.exists()) {
        newWallpaper = FileImage(_wallpaperDayFile);
      } else if (!isDay && await _wallpaperNightFile.exists()) {
        newWallpaper = FileImage(_wallpaperNightFile);
      } else if (await _wallpaperFile.exists()) {
        newWallpaper = FileImage(_wallpaperFile); // Fallback
      }
    } else {
      if (await _wallpaperFile.exists()) {
        newWallpaper = FileImage(_wallpaperFile);
      }
    }

    if (callId == _updateWallpaperCallCount) {
      if (_wallpaper != newWallpaper || force) {
        _wallpaper = newWallpaper;
        notifyListeners();
      }
    }
  }

  Future<void> pickWallpaper() async {
    await _pickAndSave(_wallpaperFile);
  }

  Future<void> pickWallpaperDay() async {
    await _pickAndSave(_wallpaperDayFile);
  }

  Future<void> pickWallpaperNight() async {
    await _pickAndSave(_wallpaperNightFile);
  }

  Future<void> _pickAndSave(File targetFile) async {
    if (!await _fLauncherChannel.checkForGetContentAvailability()) {
      throw NoFileExplorerException();
    }

    final imagePicker = ImagePicker();
    final pickedFile = await imagePicker.pickImage(source: ImageSource.gallery);
    if (pickedFile != null) {
      // Use stream for memory efficiency
      final readStream = pickedFile.openRead();
      final writeStream = targetFile.openWrite();
      await readStream.cast<List<int>>().pipe(writeStream);

      // Evict from cache to ensure UI updates
      await FileImage(targetFile).evict();

      _version++;
      await _updateWallpaper(force: true);
    }
  }

  Future<void> setGradient(FLauncherGradient fLauncherGradient) async {
    if (await _wallpaperFile.exists()) {
      await _wallpaperFile.delete();
    }

    _settingsService.setGradientUuid(fLauncherGradient.uuid);
    notifyListeners();
  }
}

class NoFileExplorerException implements Exception {}
