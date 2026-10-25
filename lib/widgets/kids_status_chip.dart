import 'dart:async';

import 'package:flauncher/flauncher_channel.dart';
import 'package:flutter/material.dart';

/// Small status-bar chip showing how much Kids mode TV time is left today.
/// Hidden when Kids mode is off.
class KidsStatusChip extends StatefulWidget {
  const KidsStatusChip({super.key});

  @override
  State<KidsStatusChip> createState() => _KidsStatusChipState();
}

class _KidsStatusChipState extends State<KidsStatusChip> with WidgetsBindingObserver {
  final FLauncherChannel _channel = FLauncherChannel();
  Timer? _timer;
  Map<dynamic, dynamic> _status = const {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 20), (_) => _refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final status = await _channel.getKidsStatus();
    if (mounted) setState(() => _status = status);
  }

  String? _label() {
    if (_status['enabled'] != true) return null;
    if (_status['parentActive'] == true) return 'Parent mode';
    if (_status['allowed'] != true) return 'TV time is over';
    final int left = (_status['minutesLeft'] as int?) ?? -1;
    if (left < 0) return 'No limit today';
    final String endsAt = (_status['endsAt'] as String?) ?? '';
    final String time = left >= 60 ? '${left ~/ 60} h ${left % 60} min' : '$left min';
    return endsAt.isEmpty ? '$time left' : '$time left · TV off at $endsAt';
  }

  @override
  Widget build(BuildContext context) {
    final label = _label();
    if (label == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(right: 12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black.withOpacity(0.3),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0xFFF2B347).withOpacity(0.6), width: 1),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.timer_outlined, size: 18, color: Color(0xFFF2B347)),
            const SizedBox(width: 8),
            Text(
              label,
              style: const TextStyle(
                fontSize: 16,
                color: Colors.white,
                fontWeight: FontWeight.w600,
                shadows: [Shadow(color: Colors.black54, offset: Offset(0, 2), blurRadius: 4)],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
