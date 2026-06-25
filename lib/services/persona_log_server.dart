import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart' show debugPrint;

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
/// - `GET /healthz`               → 健康检查
///
/// 数据源为 [PersonaLogger]，服务只读，不写日志。
class PersonaLogServer {
  PersonaLogServer(this.logger);

  final PersonaLogger logger;

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
    <option value="event">事件</option>
  </select>
  <input id="kw" placeholder="搜索(人物/物体/文本)" style="width:200px" />
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
