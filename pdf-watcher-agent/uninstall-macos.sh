#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="/opt/invoicedirect/watcher"
PLIST_NAME="com.invoicedirect.watcher"
PLIST_PATH="$HOME/Library/LaunchAgents/$PLIST_NAME.plist"

echo "Stopping and unloading launchd agent..."
launchctl stop "$PLIST_NAME" 2>/dev/null || true
launchctl unload "$PLIST_PATH" 2>/dev/null || true

echo "Removing plist..."
rm -f "$PLIST_PATH"

if [[ -d "$INSTALL_DIR" ]]; then
    echo "Remove $INSTALL_DIR and all data? [y/N]: "
    read -r CONFIRM
    if [[ "$CONFIRM" == "y" || "$CONFIRM" == "Y" ]]; then
        rm -rf "$INSTALL_DIR"
        echo "Removed $INSTALL_DIR"
    else
        echo "Preserved $INSTALL_DIR"
    fi
fi

echo "Uninstall complete."
