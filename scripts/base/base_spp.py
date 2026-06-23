#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XBot 底座经典蓝牙 SPP 服务端（运行在地瓜派 X5 开发板上）。

职责（与 App 端 docs/底座控制协议.md 对齐）：
  1. 注册 SPP 服务（RFCOMM，UUID 00001101-0000-1000-8000-00805F9B34AF），服务名 XBot-Base
  2. 接受 App 的 RFCOMM 连接，按 '\n' 切帧
  3. 每帧（一条 JSON 指令）带时间戳打印到 stdout + 日志文件（满足"接收指令"需求）
  4. 回送响应：
       - 通用：{"cmd":"ack","ok":true}
       - get_version：{"cmd":"version",...}
       - get_status：{"cmd":"status",...}
     每条响应均以 '\n' 结尾。

帧格式：单行 UTF-8 JSON，行尾 '\n'（0x0A）。这与 App 侧 base_protocol.dart 约定一致。

运行：
    python3 base_spp.py                 # 前台
    nohup python3 base_spp.py &         # 后台
    systemctl start xbot-base           # 经 systemd（见 xbot-base.service）

注意：BlueZ 5.x 下 advertise_service 依赖 bluetoothd 以 --compat 运行（见随附 systemd
覆盖配置）。配对 PIN 由 bluetoothctl/agent 处理，默认 1234。

作者：ZBot
"""

import sys
import os
import time
import json
import queue
import signal
import threading
import traceback
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import subprocess

import bluetooth
from bluetooth import BluetoothSocket, RFCOMM
# 注意：不使用 PyBluez 的 advertise_service——系统 python3-bluez 0.23 在
# Python 3.10 上 sdp_advertise_service 存在 "PY_SSIZE_T_CLEAN" bug 会崩溃，
# 且 aarch64 无可用的较新 wheel。这里改用 `sdptool add SP` 注册 SPP 的
# SDP 记录（标准 Serial Port Profile，通道 1），Python 只负责 RFCOMM 收发。

# ==================== 配置 ====================

# 标准 SPP UUID（Serial Port Profile）。flutter_bluetooth_serial 固定用此 UUID 发现服务。
SPP_UUID = "00001101-0000-1000-8000-00805F9B34AF"
SPP_SERVICE_NAME = "XBot-Base"
SPP_SERVICE_DESC = "XBot Base SPP Service"
RFCOMM_PORT = 1  # RFCOMM 通道号，SPP 约定为 1

LOG_FILE = "/var/log/xbot-base.log"

# 可视化 HTTP 服务端口（浏览器访问 http://<板子IP>:HTTP_PORT/）
# 注：板子 8080 被 llama-server 占用，改用 8090。
HTTP_PORT = 8090
# 可视化 HTML 页面路径（与脚本同目录）
VIZ_HTML_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "base_viz.html")

# 设备能力（回复 version/status 时上报给 App）
DEVICE_DOF = 3              # 自由度 2/3
DEVICE_HW = "XBot-Base-DiguaX5"
VERSION = {"major": 1, "minor": 0, "patch": 0, "build": 1}

# ==================== 日志 ====================

_log_fp = None


def _open_log():
    global _log_fp
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        _log_fp = open(LOG_FILE, "a", encoding="utf-8", buffering=1)  # 行缓冲
    except Exception as e:
        # 日志文件不可写不致命，退化为仅 stdout
        sys.stderr.write("[WARN] 无法打开日志文件 %s: %s（仅输出到 stdout）\n" % (LOG_FILE, e))
        _log_fp = None


def log(level, msg):
    """统一日志：stdout + 文件，带时间戳。level ∈ {RX, TX, SYS, ERR}。"""
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    line = "[%s] %s %s" % (ts, level, msg)
    print(line, flush=True)
    if _log_fp:
        try:
            _log_fp.write(line + "\n")
        except Exception:
            pass


# ==================== 可视化（HTTP + SSE）====================
#
# 浏览器访问 http://<板子IP>:HTTP_PORT/ 打开 3D 可视化页面；
# 页面通过 SSE（/events）实时接收 SPP 收到的指令，驱动云台动画。
# SPP 收到指令时调用 broadcast_frame() 把帧推给所有已连接的浏览器。

class VizState:
    """当前底座姿态快照（供 SSE 新连接发送 snapshot）。线程安全。"""

    def __init__(self):
        self._lock = threading.Lock()
        self.data = {
            "yaw": 0.0, "pitch": 0.0, "roll": 0.0,
            "mode": "idle", "moving": False,
            "dof": DEVICE_DOF, "hw": DEVICE_HW,
        }

    def update_from_frame(self, obj):
        """根据收到的指令更新姿态快照（move/move_rel/home/set_mode 等）。"""
        cmd = obj.get("cmd")
        with self._lock:
            if cmd == "move":
                if isinstance(obj.get("yaw"), (int, float)):
                    self.data["yaw"] = float(obj["yaw"])
                if isinstance(obj.get("pitch"), (int, float)):
                    self.data["pitch"] = float(obj["pitch"])
                if isinstance(obj.get("roll"), (int, float)):
                    self.data["roll"] = float(obj["roll"])
            elif cmd == "move_rel":
                if isinstance(obj.get("dyaw"), (int, float)):
                    self.data["yaw"] += float(obj["dyaw"])
                if isinstance(obj.get("dpitch"), (int, float)):
                    self.data["pitch"] += float(obj["dpitch"])
                if isinstance(obj.get("droll"), (int, float)):
                    self.data["roll"] += float(obj["droll"])
            elif cmd == "home":
                self.data["yaw"] = self.data["pitch"] = self.data["roll"] = 0.0
            elif cmd == "set_mode":
                if isinstance(obj.get("mode"), str):
                    self.data["mode"] = obj["mode"]

    def snapshot_json(self):
        with self._lock:
            return json.dumps(self.data, ensure_ascii=False)


# 全局：姿态快照 + SSE 客户端队列列表
viz_state = VizState()
viz_clients = []          # list[queue.Queue]
viz_clients_lock = threading.Lock()
viz_peer = {"addr": None}  # 当前连接的对端（SPP 客户端）


def broadcast_frame(obj, direction="RX"):
    """把一帧推送给所有已连接的浏览器（SSE event: frame）。"""
    payload = dict(obj)
    payload["__dir"] = direction  # 告诉前端这帧方向
    data = json.dumps(payload, ensure_ascii=False)
    with viz_clients_lock:
        dead = []
        for i, q in enumerate(viz_clients):
            try:
                q.put_nowait(("frame", data))
            except Exception:
                dead.append(i)
        for i in reversed(dead):
            try:
                viz_clients.pop(i)
            except Exception:
                pass


def broadcast_peer(addr):
    """通知所有浏览器对端连接变化。"""
    with viz_clients_lock:
        for q in viz_clients:
            try:
                q.put_nowait(("peer", addr or ""))
            except Exception:
                pass


class VizHTTPHandler(BaseHTTPRequestHandler):
    """HTTP 处理器：/ 返回页面，/events 返回 SSE 流，/status 返回 JSON 快照。"""

    def log_message(self, *args):
        # 静默 HTTP 访问日志，避免刷屏
        pass

    def _no_cache_headers(self):
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/" or path == "/index.html":
            self._serve_html()
        elif path == "/events":
            self._serve_sse()
        elif path == "/status":
            self._serve_status()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        """POST /inject：无蓝牙时手动注入一帧指令用于测试页面动画。

        Body: 一行 JSON 指令，如 {"cmd":"move","yaw":45,"pitch":-15}
        """
        path = urlparse(self.path).path
        if path != "/inject":
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            obj = json.loads(raw)
        except Exception as e:
            self._json(400, {"ok": False, "error": "invalid json: %s" % e})
            return
        # 更新姿态快照 + 广播给浏览器（模拟 SPP 收到该帧）
        viz_state.update_from_frame(obj)
        broadcast_frame(obj, direction="RX")
        log("SYS", "[inject] 注入指令: %s" % obj.get("cmd"))
        self._json(200, {"ok": True})

    def _json(self, code, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self._no_cache_headers()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_html(self):
        try:
            with open(VIZ_HTML_PATH, "r", encoding="utf-8") as f:
                html = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self._no_cache_headers()
            self.send_header("Content-Length", str(len(html.encode("utf-8"))))
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))
        except Exception as e:
            msg = "HTML 读取失败: %s (%s)" % (e, VIZ_HTML_PATH)
            self.send_response(500)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(msg.encode("utf-8"))

    def _serve_status(self):
        data = viz_state.snapshot_json().encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self._no_cache_headers()
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_sse(self):
        # 为这个连接建一个队列，加入全局客户端列表
        q = queue.Queue()
        with viz_clients_lock:
            viz_clients.append(q)
        client_addr = self.client_address[0]
        log("SYS", "可视化浏览器已连接: %s（当前 %d 个）"
            % (client_addr, len(viz_clients)))

        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            # 先发当前快照 + peer
            self._sse_write("snapshot", viz_state.snapshot_json())
            self._sse_write("peer", viz_peer["addr"] or "")

            # 循环推送队列中的事件
            while True:
                try:
                    evt_name, evt_data = q.get(timeout=15)
                except queue.Empty:
                    # 心跳保活
                    self._sse_write("ping", str(int(time.time())))
                    continue
                self._sse_write(evt_name, evt_data)
        except Exception as e:
            log("ERR", "SSE 连接异常: %s" % e)
        finally:
            with viz_clients_lock:
                if q in viz_clients:
                    viz_clients.remove(q)
            log("SYS", "可视化浏览器断开（剩余 %d 个）" % len(viz_clients))

    def _sse_write(self, event, data):
        """写一条 SSE 事件。"""
        chunk = "event: %s\ndata: %s\n\n" % (event, data)
        self.wfile.write(chunk.encode("utf-8"))
        self.wfile.flush()


def start_http_server():
    """在独立线程中启动 HTTP 服务器（提供可视化页面 + SSE）。"""
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), VizHTTPHandler)
        log("SYS", "可视化 HTTP 服务已启动: http://0.0.0.0:%d/  (页面: %s)"
            % (HTTP_PORT, VIZ_HTML_PATH))
        srv.serve_forever()
    except Exception as e:
        log("ERR", "HTTP 服务启动失败: %s" % e)





# ==================== 协议响应 ====================

def build_response(cmd, params):
    """根据收到的指令构造回包 dict。返回 None 表示不回包（仅 ack 兜底在调用处处理）。"""
    if cmd == "get_version":
        return {
            "cmd": "version",
            "major": VERSION["major"],
            "minor": VERSION["minor"],
            "patch": VERSION["patch"],
            "build": VERSION["build"],
            "dof": DEVICE_DOF,
            "hw": DEVICE_HW,
        }
    if cmd == "get_status":
        return {
            "cmd": "status",
            "mode": "manual",
            "yaw": 0.0,
            "pitch": 0.0,
            "roll": 0.0,
            "dof": DEVICE_DOF,
            "moving": False,
            "calibrated": True,
            "error": None,
        }
    if cmd == "heartbeat":
        # 心跳原样回
        return {"cmd": "heartbeat", "ts": int(time.time())}
    # 其余指令（move/move_rel/home/stop/set_mode/set_speed/calibrate）回通用 ack
    return {"cmd": "ack", "ok": True}


# ==================== 单连接处理 ====================

def handle_client(conn, client_info):
    """处理一个已建立的 RFCOMM 连接：读字节流 -> 按 \\n 切帧 -> 处理 -> 回包。

    连接断开或出错时返回，由主循环重新 accept。
    """
    peer = "%s:%s" % client_info if client_info else "unknown"
    log("SYS", "客户端已连接: %s" % peer)
    viz_peer["addr"] = peer
    broadcast_peer(peer)

    buf = b""  # 接收缓冲区（字节流，可能跨包）
    conn.settimeout(1.0)  # 带超时的 recv，便于周期性检查退出标志

    try:
        while not _stop.is_set():
            try:
                data = conn.recv(1024)
            except bluetooth.BluetoothError as e:
                msg = str(e).lower()
                if "timed out" in msg or "timeout" in msg:
                    # recv 超时：连接正常，只是暂无数据，继续等待
                    continue
                # 其余蓝牙层错误（多半是连接断开）
                log("ERR", "recv BluetoothError: %s" % e)
                break
            except TimeoutError:
                # recv 超时：正常，继续循环检查 _stop
                continue

            if not data:
                # 对端关闭
                log("SYS", "客户端断开: %s" % peer)
                break

            buf += data

            # 按 '\n' 切帧：可能一次收到多帧，也可能一帧分多次到达
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                frame_text = raw.decode("utf-8", errors="replace").strip()
                if not frame_text:
                    continue  # 空行跳过
                process_frame(conn, frame_text)

    except Exception as e:
        log("ERR", "handle_client 异常: %s\n%s" % (e, traceback.format_exc()))
    finally:
        try:
            conn.close()
        except Exception:
            pass
        log("SYS", "连接结束: %s" % peer)
        viz_peer["addr"] = None
        broadcast_peer("")


def process_frame(conn, frame_text):
    """处理一帧（一行 JSON 文本）：打印 + 解析 + 回包 + 可视化广播。"""
    # —— 打印收到的指令（核心需求）——
    log("RX", frame_text)

    # 解析 JSON
    try:
        obj = json.loads(frame_text)
    except json.JSONDecodeError as e:
        log("ERR", "JSON 解析失败: %s" % e)
        _send(conn, {"cmd": "error", "code": "invalid_param",
                     "msg": "invalid json: %s" % e})
        return

    cmd = obj.get("cmd", "")
    req_id = obj.get("id")

    # —— 更新可视化姿态快照 + 广播给浏览器 ——
    viz_state.update_from_frame(obj)
    broadcast_frame(obj, direction="RX")

    # 构造并回包
    resp = build_response(cmd, obj)
    if resp is not None:
        _send(conn, resp)


def _send(conn, obj):
    """发送一帧 JSON：jsonEncode + '\\n'。"""
    text = json.dumps(obj, ensure_ascii=False)
    log("TX", text)
    # 回包也广播给可视化（前端日志显示设备回了什么）
    broadcast_frame(obj, direction="TX")
    try:
        conn.sendall((text + "\n").encode("utf-8"))
    except Exception as e:
        log("ERR", "send 失败: %s" % e)


# ==================== 主循环 ====================

_stop = None  # threading.Event 由 main 注入


def _ensure_sdp_record():
    """用 sdptool 注册标准 SPP (Serial Port Profile) 的 SDP 记录到通道 1。

    替代损坏的 PyBluez advertise_service。sdptool 需要 bluetoothd 以 --compat
    运行（已在 systemd drop-in 配置）。
    """
    # 清理可能残留的旧记录，避免重复注册
    subprocess.run(["sdptool", "del", "0x10001"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    result = subprocess.run(["sdptool", "add", "--channel=%d" % RFCOMM_PORT, "SP"],
                            capture_output=True, text=True)
    out = (result.stdout or "").strip()
    err = (result.stderr or "").strip()
    if result.returncode != 0:
        log("ERR", "sdptool 注册 SP 失败: rc=%s stderr=%s（bluetoothd 是否 --compat?）"
            % (result.returncode, err))
        return False
    log("SYS", "SDP 记录已注册 (SP, channel=%d): %s" % (RFCOMM_PORT, out.splitlines()[0] if out else "ok"))
    return True


def serve_forever():
    """监听 RFCOMM，注册 SPP 服务，循环 accept。"""
    # 先注册 SDP 记录（让 Android 能发现 SPP 服务）
    _ensure_sdp_record()

    sock = BluetoothSocket(RFCOMM)
    sock.bind(("", RFCOMM_PORT))
    sock.listen(1)

    log("SYS", "RFCOMM 监听就绪: name=%s uuid=%s port=%d"
        % (SPP_SERVICE_NAME, SPP_UUID, RFCOMM_PORT))

    try:
        while not _stop.is_set():
            log("SYS", "等待 App 连接...")
            try:
                conn, client_info = sock.accept()
            except bluetooth.BluetoothError as e:
                log("ERR", "accept 失败: %s（重试）" % e)
                time.sleep(1)
                continue
            handle_client(conn, client_info)
    finally:
        try:
            sock.close()
        except Exception:
            pass
        # 清理 SDP 记录
        subprocess.run(["sdptool", "del", "0x10001"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        log("SYS", "服务已停止")


def main():
    import threading
    global _stop
    _stop = threading.Event()

    _open_log()

    def on_signal(signum, frame):
        log("SYS", "收到信号 %d，准备退出..." % signum)
        _stop.set()

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    log("SYS", "==== XBot 底座 SPP 服务启动 (PID %d) ====" % os.getpid())
    log("SYS", "服务名=%s  PIN=1234（由 bluetoothctl agent 处理配对）"
        % SPP_SERVICE_NAME)

    # 启动可视化 HTTP 服务（独立线程，非守护以避免被提前回收）
    http_thread = threading.Thread(target=start_http_server, name="VizHTTP", daemon=True)
    http_thread.start()

    try:
        serve_forever()
    except Exception as e:
        log("ERR", "服务异常退出: %s\n%s" % (e, traceback.format_exc()))
        sys.exit(1)

    log("SYS", "==== 服务已退出 ====")


if __name__ == "__main__":
    main()
