#!/bin/bash
# XBot 底座蓝牙适配器初始化（开机/服务启动时执行）。
# 用 btmgmt 直接设置底层管理状态（比 bluetoothctl 非交互模式可靠）。
set -e

HCI=hci0
ALIAS="XBot-Base"

# 确保控制器上电并使能
btmgmt -i "$HCI" power on >/dev/null 2>&1 || true
btmgmt -i "$HCI" connectable on >/dev/null 2>&1 || true
btmgmt -i "$HCI" pairable on >/dev/null 2>&1 || true
# 一直可发现（discoverable-timeout=0 等价）
btmgmt -i "$HCI" discov on >/dev/null 2>&1 || true

# 友好名称（Android 蓝牙列表里显示这个）
bluetoothctl system-alias "$ALIAS" >/dev/null 2>&1 || true

echo "[setup_bt] $HCI ready: powered/connectable/discoverable/bondable, alias=$ALIAS"
