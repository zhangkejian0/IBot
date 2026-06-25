import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' show Offset, Rect;

import 'package:flutter/foundation.dart' show debugPrint;

import '../models/detection.dart';
import 'persona_logger.dart';

/// 人物日志的 HTTP 浏览服务。
///
/// 绑定到 `0.0.0.0`(所有网卡)，使**同一局域网内的电脑**可用浏览器访问，
/// 方便调试时查看/分析按天归档的日志，无需把设备连数据线导文件。
///
/// 路由：
/// - `GET /`                      → 内嵌单页看板(下拉选日期 + 过滤 + 表格)
/// - `GET /api/dates`             → 已有日志日期列表(JSON)
/// - `GET /api/logs?date=Y-M-D`   → 指定日期全部记录(JSON 数组)
/// - `POST /api/sample`           → 远程采样:抓取当前帧点位+动作标注存库
/// - `GET /healthz`               → 健康检查
///
/// 数据源为 [PersonaLogger]。采样能力由 [frameProvider] 提供(从 AppController
/// 取实时帧),允许在电脑网页上远程触发,无需触碰设备(避免改变采样动作姿态)。
class PersonaLogServer {
  PersonaLogServer(this.logger, {this.frameProvider});

  final PersonaLogger logger;

  /// 取当前检测帧的回调(由 AppController 注入)。为 null 表示无采样能力。
  /// 网页 POST /api/sample 时调用,远程抓取当前帧点位。
  final DetectionResult Function()? frameProvider;

  HttpServer? _server;

  /// 监听端口(启动后可用)。
  int? get port => _server?.port;

  /// 是否在运行。
  bool get isRunning => _server != null;

  /// 局域网可访问地址(`http://<lan-ip>:<port>`)，未启动或无网卡时为 null。
  String? _url;
  String? get url => _url;

  /// 启动服务。固定优先用 [preferredPort]，被占用则回退随机端口。
  /// 返回局域网可访问的 URL。
  Future<String> start({int preferredPort = 8090}) async {
    if (_server != null) return _url ?? 'http://localhost:${_server!.port}';

    HttpServer server;
    try {
      server = await HttpServer.bind(InternetAddress.anyIPv4, preferredPort);
    } on SocketException {
      // 首选端口被占用：回退到系统分配的随机端口。
      server = await HttpServer.bind(InternetAddress.anyIPv4, 0);
    }
    _server = server;
    server.listen(_handle, onError: (Object e) {
      debugPrint('[PersonaLogServer] listen error: $e');
    });

    final ip = await _lanIpv4();
    _url = 'http://${ip ?? 'localhost'}:${server.port}';
    debugPrint('[PersonaLogServer] started at $_url');
    return _url!;
  }

  /// 停止服务。
  Future<void> stop() async {
    await _server?.close(force: true);
    _server = null;
    _url = null;
    debugPrint('[PersonaLogServer] stopped');
  }

  Future<void> _handle(HttpRequest request) async {
    final res = request.response;
    // 允许跨域，便于在电脑上用其他工具拉取。
    res.headers.set('Access-Control-Allow-Origin', '*');
    try {
      final path = request.uri.path;
      if (path == '/' || path == '/index.html') {
        res.headers.contentType = ContentType.html;
        res.write(_dashboardHtml);
        await res.close();
        return;
      }
      if (path == '/healthz') {
        await _writeJson(res, {'ok': true});
        return;
      }
      if (path == '/api/dates') {
        final dates = await logger.availableDates();
        await _writeJson(res, {'dates': dates, 'dir': logger.directoryPath});
        return;
      }
      if (path == '/api/logs') {
        final date = request.uri.queryParameters['date'];
        if (date == null || !_validDate(date)) {
          res.statusCode = HttpStatus.badRequest;
          await _writeJson(res, {'error': 'missing or invalid date'});
          return;
        }
        if (request.method == 'DELETE') {
          await logger.deleteDate(date);
          await _writeJson(res, {'ok': true, 'deleted': date});
          return;
        }
        final entries = await logger.readDate(date);
        await _writeJson(res, {
          'date': date,
          'count': entries.length,
          'logs': entries.map((e) => e.toJson()).toList(),
        });
        return;
      }
      if (path == '/api/sample') {
        await _handleSample(request);
        return;
      }
      res.statusCode = HttpStatus.notFound;
      res.write('404 Not Found');
      await res.close();
    } catch (e) {
      debugPrint('[PersonaLogServer] handle error: $e');
      try {
        res.statusCode = HttpStatus.internalServerError;
        await res.close();
      } catch (_) {}
    }
  }

  Future<void> _writeJson(HttpResponse res, Object data) async {
    res.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
    res.write(jsonEncode(data));
    await res.close();
  }

  /// 远程采样:抓取当前帧的全部点位 + 用户标注的动作名,存成 type='sample'。
  /// POST body: `{"action":"托腮"}`。
  Future<void> _handleSample(HttpRequest request) async {
    final res = request.response;
    try {
      // 解析动作名。
      final body = await _readBody(request);
      String action = '未标注';
      try {
        final map = jsonDecode(body);
        if (map is Map && map['action'] is String) {
          action = (map['action'] as String).trim();
        }
      } catch (_) {}
      if (action.isEmpty) action = '未标注';

      // 取当前帧。
      final provider = frameProvider;
      if (provider == null) {
        await _writeJson(res, {'ok': false, 'error': '采样未启用(无帧数据源)'});
        return;
      }
      final result = provider();
      if (result.faces.isEmpty &&
          result.hands.isEmpty &&
          result.poses.isEmpty &&
          result.objects.isEmpty) {
        await _writeJson(res, {'ok': false, 'error': '当前无检测数据,请确认检测在运行'});
        return;
      }

      // 序列化 + 存库。
      final extra = _serializeFrame(result);
      extra['action'] = action;
      logger.log(PersonaLogEntry(
        timestamp: DateTime.now(),
        type: 'sample',
        faceCount: result.faces.length,
        note: '采样: $action',
        extra: extra,
      ));
      await _writeJson(res, {
        'ok': true,
        'action': action,
        'summary':
            '脸 ${result.faces.length} · 手 ${result.hands.length} · '
            '姿态 ${result.poses.length} · 物体 ${result.objects.length}',
        'extra': extra,
      });
    } catch (e) {
      debugPrint('[PersonaLogServer] sample error: $e');
      await _writeJson(res, {'ok': false, 'error': '$e'});
    }
  }

  /// 读取请求体(UTF-8 字符串)。
  Future<String> _readBody(HttpRequest request) async {
    final bytes = await request.fold<List<int>>(
      <int>[],
      (acc, d) => acc..addAll(d),
    );
    return utf8.decode(bytes, allowMalformed: true);
  }

  /// 把一帧 DetectionResult 的全部坐标序列化成 extra Map。
  /// 与 App 内 PersonaLogScreen._serializeFrame 保持一致。
  Map<String, dynamic> _serializeFrame(DetectionResult result) {
    final face = result.face;
    return {
      'mirror': result.mirror,
      'face': face == null
          ? null
          : {
              'box': _rectToList(face.boundingBox),
              'gazeX': _round(face.gazeX),
              'gazeY': _round(face.gazeY),
              'eyeBlink': _round(face.eyeBlink),
              'mouthOpenness': _round(face.mouthOpenness),
            },
      'hands': result.hands
          .map((h) => {
                'handedness': h.handedness?.name,
                'box': _rectToList(h.boundingBox),
                'landmarks': h.landmarks.map(_offsetToList).toList(),
              })
          .toList(),
      'objects': result.objects
          .map((o) => {
                'label': o.label,
                'confidence': _round(o.confidence),
                'box': _rectToList(o.boundingBox),
                'heldByHand': o.heldByHand,
              })
          .toList(),
      'poses': result.poses
          .map((p) => {
                'landmarks': p.landmarks.map(_offsetToList).toList(),
                'visibilities':
                    p.visibilities.map((v) => _round(v)).toList(),
              })
          .toList(),
    };
  }

  List<double> _offsetToList(Offset o) => [_round(o.dx), _round(o.dy)];

  List<double> _rectToList(Rect r) =>
      [_round(r.left), _round(r.top), _round(r.right), _round(r.bottom)];

  double _round(double v) => (v * 1000).roundToDouble() / 1000;

  static bool _validDate(String s) =>
      RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(s);

  /// 探测局域网 IPv4 地址。
  ///
  /// 优先级：
  /// 1. Wi-Fi / 以太网接口的私有地址（192.168.x.x 最优先）
  /// 2. 其他接口的私有地址（10.x.x.x, 172.16-31.x.x）
  /// 3. 任意非回环、非链路本地地址
  ///
  /// 会跳过常见的虚拟网卡（ADB、VPN、Docker 等）。
  static Future<String?> _lanIpv4() async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLoopback: false,
      );

      // 虚拟网卡名称模式（小写匹配），优先跳过。
      const virtualPatterns = [
        'adb', 'android', 'veth', 'docker', 'br-', 'virbr', 'vmnet',
        'tun', 'tap', 'wg', 'utun', 'ipsec', 'vpn', 'clash', 'tailscale',
      ];

      String? bestWifi;      // 192.168.x.x (Wi-Fi 最常见)
      String? bestPrivate;   // 10.x.x.x 或 172.16-31.x.x
      String? fallback;      // 任意可用地址

      for (final iface in interfaces) {
        final name = iface.name.toLowerCase();
        final isVirtual = virtualPatterns.any((p) => name.contains(p));

        for (final addr in iface.addresses) {
          final ip = addr.address;
          // 跳过链路本地地址 (169.254.x.x)
          if (ip.startsWith('169.254.')) continue;

          // 第一个可用地址作为 fallback
          fallback ??= ip;

          // 检查是否为私有地址
          final is192 = ip.startsWith('192.168.');
          final is10 = ip.startsWith('10.');
          final is172 = ip.startsWith('172.') &&
              int.tryParse(ip.split('.')[1]) != null &&
              int.parse(ip.split('.')[1]) >= 16 &&
              int.parse(ip.split('.')[1]) <= 31;
          final isPrivate = is192 || is10 || is172;

          if (!isPrivate) continue;

          // 非虚拟网卡的 192.168.x.x 最优先
          if (is192 && !isVirtual) {
            bestWifi = ip;
            // 找到就立即返回，192.168 几乎总是 Wi-Fi
            return bestWifi;
          }

          // 非虚拟网卡的其他私有地址
          if (!isVirtual && bestPrivate == null) {
            bestPrivate = ip;
          }

          // 虚拟网卡的私有地址作为最后备选
          if (isVirtual && bestPrivate == null) {
            bestPrivate ??= ip;
          }
        }
      }

      return bestWifi ?? bestPrivate ?? fallback;
    } catch (e) {
      debugPrint('[PersonaLogServer] lan ip detect failed: $e');
      return null;
    }
  }

  /// 单页看板：纯静态 HTML + 原生 JS(无外部依赖)，深色主题。
  /// 拉取 /api/dates 填充下拉，选日期后拉 /api/logs 渲染表格，支持按
  /// 类型/人物/关键词过滤与自动刷新。
  static const String _dashboardHtml = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>XBot 人物日志</title>
<style>
  :root {
    --bg: #0b0c10; --panel: #16181d; --panel2: #1e2127; --line: #2a2e36;
    --txt: #e6e8ec; --sub: #9aa0aa; --dim: #6b7280;
    --accent: #4f9cf9; --green: #30d158; --orange: #ff9f0a; --purple: #bf5af2;
    --yellow: #ffd60a; --teal: #64d2ff; --red: #ff453a;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--txt);
    font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  header { position: sticky; top: 0; z-index: 10; background: var(--panel);
    border-bottom: 1px solid var(--line); padding: 14px 20px;
    display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
  header h1 { font-size: 18px; margin: 0; font-weight: 600; }
  header .spacer { flex: 1; }
  .sample-bar { display: inline-flex; align-items: center; gap: 6px; }
  #sample.sample-active { opacity: .6; pointer-events: none; }
  select, input, button { background: var(--panel2); color: var(--txt);
    border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px;
    font-size: 14px; outline: none; }
  button { cursor: pointer; }
  button:hover { border-color: var(--accent); }
  label.chk { font-size: 13px; color: var(--sub); display: flex;
    align-items: center; gap: 5px; cursor: pointer; }
  .stats { padding: 8px 20px; color: var(--sub); font-size: 13px;
    border-bottom: 1px solid var(--line); display: flex; gap: 18px; flex-wrap: wrap; }
  .stats b { color: var(--txt); }
  .wrap { padding: 14px 20px 60px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th { text-align: left; color: var(--dim); font-weight: 500; padding: 8px 10px;
    border-bottom: 1px solid var(--line); position: sticky; top: 0;
    background: var(--bg); white-space: nowrap; }
  td { padding: 9px 10px; border-bottom: 1px solid var(--line);
    vertical-align: top; }
  tr:hover td { background: var(--panel); }
  .time { color: var(--sub); white-space: nowrap; font-variant-numeric: tabular-nums; }
  .tag { display: inline-block; padding: 1px 8px; border-radius: 20px;
    font-size: 11px; font-weight: 600; }
  .t-perception { background: rgba(100,210,255,.15); color: var(--teal); }
  .t-conversation { background: rgba(48,209,88,.15); color: var(--green); }
  .t-event { background: rgba(255,159,10,.15); color: var(--orange); }
  .t-state { background: rgba(255,69,58,.15); color: var(--red); }
  .t-activity { background: rgba(191,90,242,.15); color: var(--purple); }
  .t-sample { background: rgba(100,210,255,.18); color: var(--teal); }
  details.sample { margin-top: 4px; }
  details.sample summary { cursor: pointer; color: var(--sub); font-size: 12px; }
  .sample-json { background: var(--panel2); border: 1px solid var(--line);
    border-radius: 6px; padding: 8px; margin: 6px 0 0; font-size: 11px;
    color: var(--teal); white-space: pre-wrap; word-break: break-all;
    max-height: 320px; overflow: auto; }
  .person { color: var(--purple); font-weight: 600; }
  .chips span { display: inline-block; background: var(--panel2);
    border: 1px solid var(--line); border-radius: 6px; padding: 1px 7px;
    margin: 1px 3px 1px 0; font-size: 12px; color: var(--sub); }
  .held { color: var(--orange); }
  .reply { color: var(--green); }
  .user { color: var(--txt); }
  .note { color: var(--yellow); }
  .muted { color: var(--dim); }
  .empty { text-align: center; color: var(--dim); padding: 60px 0; }
</style>
</head>
<body>
<header>
  <h1>🐾 XBot 人物日志</h1>
  <select id="date"></select>
  <select id="type">
    <option value="">全部类型</option>
    <option value="perception">感知</option>
    <option value="conversation">对话</option>
    <option value="state">状态</option>
    <option value="activity">活动</option>
    <option value="sample">采样</option>
    <option value="event">事件</option>
  </select>
  <input id="kw" placeholder="搜索(人物/物体/文本)" style="width:200px" />
  <span class="sample-bar">
    <input id="action" placeholder="动作名" value="托腮" style="width:90px" />
    <button id="sample" style="border-color:var(--green);color:var(--green);font-weight:600">📷 采样当前帧</button>
  </span>
  <button id="refresh">刷新</button>
  <button id="export">导出 JSON</button>
  <button id="clear" style="border-color:var(--red);color:var(--red)">清除当日</button>
  <label class="chk"><input type="checkbox" id="auto" /> 自动刷新(5s)</label>
  <span class="spacer"></span>
</header>
<div class="stats" id="stats"></div>
<div class="wrap">
  <table>
    <thead><tr>
      <th style="width:90px">时间</th>
      <th style="width:80px">类型</th>
      <th style="width:110px">人物</th>
      <th style="width:70px">表情</th>
      <th style="width:90px">手势</th>
      <th>物体 / 场景</th>
      <th>对话 / 分析</th>
    </tr></thead>
    <tbody id="rows"></tbody>
  </table>
  <div class="empty" id="empty" style="display:none">暂无记录</div>
</div>
<script>
var allLogs = [];
function el(id){ return document.getElementById(id); }
function fmtTime(ts){
  try { var d = new Date(ts);
    return d.toLocaleTimeString('zh-CN', {hour12:false}) +
      '.' + String(d.getMilliseconds()).padStart(3,'0');
  } catch(e){ return ts; }
}
function esc(s){ return (s==null?'':String(s))
  .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
// 采样记录的点位数量摘要。
function poseSummary(ex){
  var f = ex.face ? 1 : 0;
  var h = (ex.hands||[]).length;
  var p = (ex.poses||[]).length;
  var o = (ex.objects||[]).length;
  return '脸 '+f+' · 手 '+h+' · 姿态 '+p+' · 物体 '+o;
}

async function loadDates(){
  var r = await fetch('/api/dates'); var j = await r.json();
  var sel = el('date'); sel.innerHTML = '';
  (j.dates||[]).forEach(function(d){
    var o = document.createElement('option'); o.value=d; o.textContent=d;
    sel.appendChild(o);
  });
  if((j.dates||[]).length===0){
    var o=document.createElement('option'); o.textContent='(无日志)'; sel.appendChild(o);
  }
}
async function loadLogs(){
  var date = el('date').value;
  if(!date || date.indexOf('-')<0){ allLogs=[]; render(); return; }
  var r = await fetch('/api/logs?date='+encodeURIComponent(date));
  var j = await r.json(); allLogs = j.logs||[]; render();
}
function render(){
  var type = el('type').value;
  var kw = el('kw').value.trim().toLowerCase();
  var rows = el('rows'); rows.innerHTML='';
  var people = {}; var shown = 0;
  // 倒序：最新在上
  for(var i=allLogs.length-1; i>=0; i--){
    var e = allLogs[i];
    if(type && e.type!==type) continue;
    if(kw){
      var hay = JSON.stringify(e).toLowerCase();
      if(hay.indexOf(kw)<0) continue;
    }
    if(e.person) people[e.person]=1;
    shown++;
    var objs = (e.objects||[]).map(function(o){
      var name = typeof o === 'object' ? (o.name||'') : String(o);
      var conf = typeof o === 'object' ? Math.round((o.confidence||0)*100) : 0;
      return '<span>'+esc(name)+' ('+conf+'%)</span>';
    }).join('');
    var sceneObj = '';
    if(objs) sceneObj += '<div class="chips">'+objs+'</div>';
    if(e.heldObject) sceneObj += '<div class="held">✋ 手持: '+esc(e.heldObject)+'</div>';
    if(e.scene) sceneObj += '<div class="muted">'+esc(e.scene)+'</div>';
    var talk='';
    if(e.userText) talk += '<div class="user">🗣 '+esc(e.userText)+'</div>';
    if(e.replyText) talk += '<div class="reply">🤖 '+esc(e.replyText)+'</div>';
    if(e.robotState) talk += '<div class="muted">状态: '+esc(e.robotState)+'</div>';
    // 采样记录:渲染标注动作名 + 可折叠的完整点位 JSON(便于复制分析)。
    if(e.type==='sample' && e.extra){
      var act = e.extra.action ? '🎬 '+esc(e.extra.action) : '采样';
      talk += '<div class="person">'+act+'</div>';
      var pos = poseSummary(e.extra);
      if(pos) talk += '<div class="muted">'+pos+'</div>';
      var json = esc(JSON.stringify(e.extra, null, 1));
      talk += '<details class="sample"><summary>点位 JSON (点击展开/复制)</summary>'
        + '<pre class="sample-json">'+json+'</pre></details>';
    }
    if(e.note) talk += '<div class="note">💡 '+esc(e.note)+'</div>';
    var personCell = e.person ? '<span class="person">'+esc(e.person)+'</span>'
      + (e.relation? ' <span class="muted">('+esc(e.relation)+')</span>':'')
      : (e.faceCount!=null? '<span class="muted">'+e.faceCount+' 张脸</span>':'');
    var tr = document.createElement('tr');
    tr.innerHTML =
      '<td class="time">'+fmtTime(e.ts)+'</td>'+
      '<td><span class="tag t-'+esc(e.type)+'">'+esc(e.type)+'</span></td>'+
      '<td>'+personCell+'</td>'+
      '<td>'+esc(e.expression||'')+'</td>'+
      '<td>'+esc(e.gesture||'')+'</td>'+
      '<td>'+sceneObj+'</td>'+
      '<td>'+talk+'</td>';
    rows.appendChild(tr);
  }
  el('empty').style.display = shown? 'none':'block';
  el('stats').innerHTML = '共 <b>'+allLogs.length+'</b> 条 / 显示 <b>'+shown+
    '</b> 条 · 涉及人物 <b>'+Object.keys(people).length+'</b> 位';
}
var timer=null;
el('refresh').onclick=loadLogs;
el('date').onchange=loadLogs;
el('type').onchange=render;
el('kw').oninput=render;
// 远程采样:POST 动作名 → 服务端抓当前帧存库 → 刷新 + 弹出点位 JSON。
el('sample').onclick=async function(){
  var action = el('action').value.trim() || '未标注';
  var btn = el('sample');
  btn.classList.add('sample-active');
  btn.textContent = '采样中…';
  try{
    var r = await fetch('/api/sample',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({action: action})
    });
    var j = await r.json();
    if(j.ok){
      // 刷新日志让采样记录立即可见。
      await loadLogs();
      // 弹出点位 JSON 预览,方便即时核对/复制。
      var info = '🎬 '+j.action+'\n'+j.summary+'\n\n'+JSON.stringify(j.extra, null, 1);
      alert(info);
    } else {
      alert('采样失败: '+(j.error||'未知错误'));
    }
  } catch(e){
    alert('采样请求失败: '+e);
  } finally {
    btn.classList.remove('sample-active');
    btn.textContent = '📷 采样当前帧';
  }
};
el('auto').onchange=function(){
  if(this.checked){ timer=setInterval(loadLogs,5000); } else { clearInterval(timer); }
};
el('export').onclick=function(){
  var date = el('date').value || 'unknown';
  var type = el('type').value;
  var kw = el('kw').value.trim().toLowerCase();
  var filtered = [];
  for(var i=0; i<allLogs.length; i++){
    var e = allLogs[i];
    if(type && e.type!==type) continue;
    if(kw){
      var hay = JSON.stringify(e).toLowerCase();
      if(hay.indexOf(kw)<0) continue;
    }
    filtered.push(e);
  }
  var data = {date: date, exportedAt: new Date().toISOString(), count: filtered.length, logs: filtered};
  var blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'});
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = 'xbot_logs_' + date + '.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};
el('clear').onclick=async function(){
  var date = el('date').value;
  if(!date || date.indexOf('-')<0){ alert('请先选择日期'); return; }
  if(!confirm('确认清除 '+date+' 的全部日志？此操作不可恢复！')) return;
  try{
    var r = await fetch('/api/logs?date='+encodeURIComponent(date), {method:'DELETE'});
    var j = await r.json();
    if(j.ok){
      allLogs = [];
      render();
      await loadDates();
      alert('已清除 '+date+' 的日志');
    } else {
      alert('清除失败: '+(j.error||'未知错误'));
    }
  }catch(e){
    alert('清除失败: '+e.message);
  }
};
(async function(){ await loadDates(); await loadLogs(); })();
</script>
</body>
</html>''';
}
