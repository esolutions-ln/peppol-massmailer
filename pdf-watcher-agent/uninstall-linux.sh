#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# InvoiceDirect PDF Watcher Agent — Linux Uninstall
# ═══════════════════════════════════════════════════════════════

INSTALL_DIR="/opt/invoicedirect/watcher"
SERVICE_NAME="invoicedirect-watcher"

if [[ $EUID -ne 0 ]]; then
    echo "ERROR: This script must be run as root (sudo)." >&2
    exit 1
fi

echo "Stopping and disabling service..."
systemctl stop "$SERVICE_NAME" 2>/dev/null || true
systemctl disable "$SERVICE_NAME" 2>/dev/null || true

echo "Removing systemd service..."
rm -f "/etc/systemd/system/$SERVICE_NAME.service"
systemctl daemon-reload

if [[ -d "$INSTALL_DIR" ]]; then
    echo "Remove $INSTALL_DIR and all data? (y/N): "
    read -r CONFIRM
    if [[ "$CONFIRM" == "y" || "$CONFIRM" == "Y" ]]; then
        rm -rf "$INSTALL_DIR"
        echo "Removed $INSTALL_DIR"
    else
        echo "Preserved $INSTALL_DIR"
    fi
fi

echo "Removing system user 'invoicedirect'..."
userdel invoicedirect 2>/dev/null || true

echo "Uninstall complete."
