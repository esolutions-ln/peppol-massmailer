#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
# InvoiceDirect PDF Watcher Agent — macOS Installation (launchd)
# ═══════════════════════════════════════════════════════════════

INSTALL_DIR="/opt/invoicedirect/watcher"
PLIST_NAME="com.invoicedirect.watcher"
PLIST_PATH="$HOME/Library/LaunchAgents/$PLIST_NAME.plist"
JAR_SRC="target/pdf-watcher-agent-1.0.0.jar"

echo "═══════════════════════════════════════════════════════"
echo " InvoiceDirect PDF Watcher — macOS Installation"
echo "═══════════════════════════════════════════════════════"

# ── Create directories ──
echo "Creating directories under $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"/{inbox,emailed,failed,logs}

# ── Copy JAR ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/$JAR_SRC"

if [[ ! -f "$JAR_PATH" ]]; then
    if [[ -f "$SCRIPT_DIR/../$JAR_SRC" ]]; then
        JAR_PATH="$SCRIPT_DIR/../$JAR_SRC"
    else
        echo "ERROR: Cannot find $JAR_SRC at $JAR_PATH" >&2
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
fi

# ── Copy and customise launchd plist ──
PLIST_SOURCE="$SCRIPT_DIR/com.invoicedirect.watcher.plist"
if [[ -f "$PLIST_SOURCE" ]]; then
    # Determine JAVA_HOME for the plist
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo "/Library/Java/JavaVirtualMachines/openjdk.jdk/Contents/Home")
    sed "s|/usr/local/openjdk-17|$JAVA_HOME|g" "$PLIST_SOURCE" > "$PLIST_PATH"
    echo "Installed launchd plist to $PLIST_PATH"
else
    echo "WARNING: plist template not found. Create $PLIST_PATH manually."
fi

# ── Set permissions ──
chmod -R 755 "$INSTALL_DIR"
chmod 644 "$PLIST_PATH"

# ── Load the agent ──
launchctl load "$PLIST_PATH" 2>/dev/null || true

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Installation complete."
echo ""
echo "  Next steps:"
    echo "    1. Edit $INSTALL_DIR/watcher.properties"
    echo "       with your organization's API key (from InvoiceDirect dashboard)."
echo ""
echo "    2. Start the agent:"
echo "       launchctl start $PLIST_NAME"
echo ""
echo "    3. Check status:"
echo "       launchctl list $PLIST_NAME"
echo ""
echo "    4. Watch logs:"
echo "       tail -f $INSTALL_DIR/logs/watcher.log"
echo ""
echo "    5. To stop:"
echo "       launchctl stop $PLIST_NAME"
echo "       launchctl unload $PLIST_PATH"
echo ""
echo "    6. Configure your ERP to write PDF + .json sidecar"
echo "       files into $INSTALL_DIR/inbox/"
echo "══════════════════════════════════════════════════════════════"
