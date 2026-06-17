import 'dart:ui';

/// 注视方向区域检测器。
///
/// 将屏幕划分为 3×3 = 9 个区域，根据人脸中心位置判断所在区域，
/// 并提供死区机制避免边界抖动。
class GazeZoneDetector {
  /// 水平方向区域数量
  static const int cols = 3;

  /// 垂直方向区域数量
  static const int rows = 3;

  /// 死区比例（0..0.2），在区域边界处的缓冲带
  static const double _deadZoneRatio = 0.05;

  /// 当前区域索引 [col, row]，null 表示尚未检测到人脸
  int? _currentCol;
  int? _currentRow;

  /// 变化计数器，用于检测状态变化
  int _changeCount = 0;

  /// 获取当前区域索引
  int? get currentCol => _currentCol;
  int? get currentRow => _currentRow;
  /// 获取变化计数器
  int get changeCount => _changeCount;

  /// 获取当前区域的中心坐标（归一化 -1..1，画面中心为原点）
  ///
  /// 返回 null 表示尚未检测到人脸。
  Offset? get currentZoneCenter {
    if (_currentCol == null || _currentRow == null) return null;
    return _zoneCenter(_currentCol!, _currentRow!);
  }

  /// 更新人脸位置，返回是否有区域变化。
  ///
  /// [normalizedX], [normalizedY] 为人脸中心归一化坐标（-1..1，画面中心为原点）。
  bool update(double normalizedX, double normalizedY) {
    // 转换到 0..1 坐标系
    final x01 = (normalizedX + 1) / 2;
    final y01 = (normalizedY + 1) / 2;

    // 计算所在区域（不含死区）
    final col = _calcZone(x01, cols);
    final row = _calcZone(y01, rows);

    // 首次检测，直接设置
    if (_currentCol == null || _currentRow == null) {
      _currentCol = col;
      _currentRow = row;
      return true;
    }

    // 区域未变化
    if (col == _currentCol && row == _currentRow) {
      return false;
    }

    // 区域变化了，检查是否在边界的死区内
    // 如果在死区内，保持原区域（避免边界抖动）
    if (_isInBoundaryDeadZone(x01, _currentCol!, col, cols) ||
        _isInBoundaryDeadZone(y01, _currentRow!, row, rows)) {
      return false;
    }

    // 区域变化，更新
    _currentCol = col;
    _currentRow = row;
    _changeCount++;
    return true;
  }

  /// 重置状态
  void reset() {
    _currentCol = null;
    _currentRow = null;
  }

  /// 计算坐标所在的区域索引（不含死区）
  int _calcZone(double value01, int divisions) {
    final clamped = value01.clamp(0.0, 1.0);
    return (clamped * divisions).floor().clamp(0, divisions - 1);
  }

  /// 检查坐标是否在两个区域边界的死区内
  bool _isInBoundaryDeadZone(
      double value01, int oldZone, int newZone, int totalZones) {
    // 只有相邻区域才需要检查死区
    if ((newZone - oldZone).abs() != 1) return false;

    final zoneWidth = 1.0 / totalZones;
    final deadZone = zoneWidth * _deadZoneRatio;

    // 计算边界位置
    final boundary = (newZone > oldZone)
        ? (oldZone + 1) * zoneWidth // 右边界
        : oldZone * zoneWidth; // 左边界

    // 检查是否在边界的死区内
    return (value01 - boundary).abs() < deadZone;
  }

  /// 获取指定区域的中心坐标（归一化 -1..1，画面中心为原点）
  Offset _zoneCenter(int col, int row) {
    final zoneWidth = 1.0 / cols;
    final zoneHeight = 1.0 / rows;

    // 区域中心（0..1 坐标系）
    final cx01 = (col + 0.5) * zoneWidth;
    final cy01 = (row + 0.5) * zoneHeight;

    // 转换到 -1..1 坐标系
    return Offset(
      cx01 * 2 - 1,
      cy01 * 2 - 1,
    );
  }

  /// 获取区域名称（用于调试）
  String zoneName(int col, int row) {
    const colNames = ['左', '中', '右'];
    const rowNames = ['上', '中', '下'];
    return '${colNames[col]}${rowNames[row]}';
  }

  /// 获取当前区域名称（用于调试）
  String? get currentZoneName {
    if (_currentCol == null || _currentRow == null) return null;
    return zoneName(_currentCol!, _currentRow!);
  }
}
