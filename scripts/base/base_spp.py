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
import signal
import traceback
from datetime import datetime

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

    buf = b""  # 接收缓冲区（字节流，可能跨包）
    conn.settimeout(1.0)  # 带超时的 recv，便于周期性检查退出标志

    try:
        while not _stop.is_set():
            try:
                data = conn.recv(1024)
            except bluetooth.BluetoothError as e:
                # 蓝牙层错误（多半是连接断开）
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


def process_frame(conn, frame_text):
    """处理一帧（一行 JSON 文本）：打印 + 解析 + 回包。"""
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

    # 构造并回包
    resp = build_response(cmd, obj)
    if resp is not None:
        _send(conn, resp)


def _send(conn, obj):
    """发送一帧 JSON：jsonEncode + '\\n'。"""
    text = json.dumps(obj, ensure_ascii=False)
    log("TX", text)
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

    try:
        serve_forever()
    except Exception as e:
        log("ERR", "服务异常退出: %s\n%s" % (e, traceback.format_exc()))
        sys.exit(1)

    log("SYS", "==== 服务已退出 ====")


if __name__ == "__main__":
    main()
