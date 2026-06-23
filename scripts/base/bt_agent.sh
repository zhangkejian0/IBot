#!/bin/bash
# XBot 底座蓝牙配对 agent。
#
# 维持一个常驻 bluetoothctl 进程，对配对请求自动确认/信任。
# 采用 SSP（Secure Simple Pairing）：Android 发起配对时弹出确认，
# 本脚本通过向 bluetoothctl 输入 "yes" 自动应答。
#
# 配对 PIN：经典蓝牙 2.1+ 走 SSP，通常无需固定 PIN；若对端是老式设备
# 要求 PIN，则回 "1234"（与协议约定的默认 PIN 一致）。
set -u

PIN="1234"

# coproc 维持与 bluetoothctl 的双向管道
coproc BC { bluetoothctl 2>/dev/null; }

# 等管道就绪
sleep 1

# 注册默认 agent 并配置适配器
echo "agent on"        >&${BC[1]}
echo "default-agent"   >&${BC[1]}
echo "power on"        >&${BC[1]}
echo "discoverable on" >&${BC[1]}
echo "pairable on"     >&${BC[1]}

echo "[bt_agent] agent 已就绪，等待配对请求（PIN=$PIN）"

# 轮询 bluetoothctl 输出，识别配对请求并自动应答
while IFS= read -r line <&${BC[0]}; do
    # 去除 ANSI 颜色码便于匹配
    clean=$(echo "$line" | sed 's/\x1b\[[0-9;]*m//g')

    # 用关键字匹配（不含空格模式，避免 case 解析歧义）
    if echo "$clean" | grep -qi "RequestConfirmation\|confirm"; then
        echo "[bt_agent] SSP 确认请求 -> yes"
        echo "yes" >&${BC[1]}
    elif echo "$clean" | grep -qi "RequestPinCode\|PIN code"; then
        echo "[bt_agent] PIN 请求 -> $PIN"
        echo "$PIN" >&${BC[1]}
    elif echo "$clean" | grep -qi "RequestPasskey\|passkey"; then
        echo "[bt_agent] Passkey 请求 -> $PIN"
        echo "$PIN" >&${BC[1]}
    elif echo "$clean" | grep -qi "paired: yes\|Paired: yes"; then
        echo "[bt_agent] 设备已配对: $clean"
    fi
done

echo "[bt_agent] bluetoothctl 已退出"
