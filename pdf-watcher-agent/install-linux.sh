#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# InvoiceDirect PDF Watcher Agent — Linux Installation (systemd)
# ═══════════════════════════════════════════════════════════════

INSTALL_DIR="/opt/invoicedirect/watcher"
SERVICE_NAME="invoicedirect-watcher"
JAR_SRC="target/pdf-watcher-agent-1.0.0.jar"

# Must run as root
if [[ $EUID -ne 0 ]]; then
    echo "ERROR: This script must be run as root (sudo)." >&2
    exit 1
fi

echo "═══════════════════════════════════════════════════════"
echo " InvoiceDirect PDF Watcher — Linux Installation"
echo "═══════════════════════════════════════════════════════"

# ── Create user ──
if ! id -u invoicedirect &>/dev/null; then
    echo "Creating system user 'invoicedirect'..."
    useradd --system --no-create-home --home-dir "$INSTALL_DIR" \
            --shell /usr/sbin/nologin invoicedirect
fi

# ── Create directories ──
echo "Creating directories under $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"/{inbox,emailed,failed,logs}

# ── Copy JAR ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/$JAR_SRC"

if [[ ! -f "$JAR_PATH" ]]; then
    # Also check relative to the script dir
    if [[ -f "$SCRIPT_DIR/../$JAR_SRC" ]]; then
        JAR_PATH="$SCRIPT_DIR/../$JAR_SRC"
    else
        echo "ERROR: Cannot find $JAR_SRC" >&2
        echo "Build it first: mvn clean package -DskipTests" >&2
        exit 1
    fi
fi

echo "Installing JAR..."
cp "$JAR_PATH" "$INSTALL_DIR/pdf-watcher-agent-1.0.0.jar"

# ── Create watcher.properties from template ──
PROPS_TEMPLATE="$SCRIPT_DIR/src/main/resources/watcher.properties"
if [[ -f "$PROPS_TEMPLATE" ]]; then
    if [[ ! -f "$INSTALL_DIR/watcher.properties" ]]; then
        cp "$PROPS_TEMPLATE" "$INSTALL_DIR/watcher.properties"
        echo "Created $INSTALL_DIR/watcher.properties — EDIT THIS FILE with your organization's API key."
    else
        echo "watcher.properties already exists — skipping."
    fi
else
    echo "WARNING: watcher.properties template not found. Create it manually."
fi

# ── Copy systemd service ──
echo "Installing systemd service..."
cp "$SCRIPT_DIR/invoicedirect-watcher.service" /etc/systemd/system/

# ── Set ownership ──
chown -R invoicedirect:invoicedirect "$INSTALL_DIR"
chmod 750 "$INSTALL_DIR"
chmod 640 "$INSTALL_DIR/watcher.properties"

# ── Reload and enable ──
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Installation complete."
echo ""
echo "  Next steps:"
    echo "    1. Edit $INSTALL_DIR/watcher.properties"
    echo "       with your organization's API key (from InvoiceDirect dashboard)."
echo ""
echo "    2. Start the service:"
echo "       sudo systemctl start $SERVICE_NAME"
echo ""
echo "    3. Check status:"
echo "       sudo systemctl status $SERVICE_NAME"
echo ""
echo "    4. Watch logs:"
echo "       sudo journalctl -u $SERVICE_NAME -f"
echo ""
echo "    5. Configure your ERP to write PDF + .json sidecar"
echo "       files into $INSTALL_DIR/inbox/"
echo "══════════════════════════════════════════════════════════════"
