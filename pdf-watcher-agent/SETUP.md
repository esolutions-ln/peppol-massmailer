# PDF Watcher Agent — Setup Guide

**pdf-watcher-agent** is a lightweight standalone Java agent that watches a directory for fiscalised invoice PDFs (with `.json` sidecar metadata) and forwards them to the InvoiceDirect mass-mailer API.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java JRE | 17+ (Java 25 recommended for `--enable-preview`) |
| Maven | 3.9+ (build only) |

### Build the JAR

```bash
# From the mass-mailer project root
mvn clean package -DskipTests -pl pdf-watcher-agent -am
```

The output JAR is at `pdf-watcher-agent/target/pdf-watcher-agent-1.0.0.jar`.

---

## Configuration

All settings go in `watcher.properties` (placed next to the JAR):

```properties
# ── API Connection ──
api.base.url=http://localhost:9199          # local dev; switch to production URL
api.key=replace-with-your-org-api-key       # from InvoiceDirect dashboard

# ── Organization (fallback when sidecar omits orgId) ──
# organization.id=00000000-0000-0000-0000-000000000000

# ── Directories ──
inbox.directory=/opt/invoicedirect/watcher/inbox
emailed.directory=/opt/invoicedirect/watcher/emailed
failed.directory=/opt/invoicedirect/watcher/failed
ledger.file=/opt/invoicedirect/watcher/ledger.json

# ── Timing ──
sidecar.wait.ms=3000
api.connect.timeout.seconds=10
api.read.timeout.seconds=60
```

> **Windows paths** (uncomment and adjust in `watcher.properties`):
> ```
> inbox.directory=C:/InvoiceDirect/inbox
> emailed.directory=C:/InvoiceDirect/emailed
> failed.directory=C:/InvoiceDirect/failed
> ledger.file=C:/InvoiceDirect/ledger.json
> ```

---

## Platform Installation

### Windows

**Option A — Windows Service (recommended)**

1. Open **Command Prompt as Administrator**.
2. Run the installer:
   ```batch
   pdf-watcher-agent\install-windows.bat
   ```
3. Select option `1` (Windows Service via WinSW).
4. Edit `C:\InvoiceDirect\watcher.properties` with your API key.
5. Start the service:
   ```batch
   net start InvoiceDirectWatcher
   ```
6. Check logs in `C:\InvoiceDirect\logs\`.

**Option B — Scheduled Task**

1. Run `install-windows.bat` and select option `2`.
2. The task `InvoiceDirectWatcher` is created (triggers at system startup).
3. Edit `C:\InvoiceDirect\watcher.properties`.
4. Test by running `C:\InvoiceDirect\run-watcher.bat`.

**Manual install (no script):**

```batch
mkdir C:\InvoiceDirect\inbox C:\InvoiceDirect\emailed C:\InvoiceDirect\failed C:\InvoiceDirect\logs
copy pdf-watcher-agent-1.0.0.jar C:\InvoiceDirect\
copy watcher.properties C:\InvoiceDirect\
```

---

### macOS

1. Run the install script:
   ```bash
   cd pdf-watcher-agent
   chmod +x install-macos.sh
   ./install-macos.sh
   ```

2. Edit `watcher.properties`:
   ```bash
   nano /opt/invoicedirect/watcher/watcher.properties
   ```

3. Start the agent:
   ```bash
   launchctl start com.invoicedirect.watcher
   ```

4. Check status:
   ```bash
   launchctl list com.invoicedirect.watcher
   ```

5. View logs:
   ```bash
   tail -f /opt/invoicedirect/watcher/logs/watcher.log
   ```

**Manual install:**

```bash
# Create directories
sudo mkdir -p /opt/invoicedirect/watcher/{inbox,emailed,failed,logs}

# Copy JAR
sudo cp pdf-watcher-agent-1.0.0.jar /opt/invoicedirect/watcher/

# Copy config
sudo cp watcher.properties /opt/invoicedirect/watcher/

# Install launchd plist
JAVA_HOME=$(/usr/libexec/java_home)
sed "s|/usr/local/openjdk-17|$JAVA_HOME|g" \
  com.invoicedirect.watcher.plist \
  > ~/Library/LaunchAgents/com.invoicedirect.watcher.plist

# Set permissions
sudo chmod -R 755 /opt/invoicedirect/watcher
chmod 644 ~/Library/LaunchAgents/com.invoicedirect.watcher.plist

# Load and start
launchctl load ~/Library/LaunchAgents/com.invoicedirect.watcher.plist
```

**Uninstall:**
```bash
./uninstall-macos.sh
# Or manually:
launchctl unload ~/Library/LaunchAgents/com.invoicedirect.watcher.plist
sudo rm -rf /opt/invoicedirect/watcher
rm ~/Library/LaunchAgents/com.invoicedirect.watcher.plist
```

---

### Linux (systemd)

1. Run the install script (as root):
   ```bash
   sudo ./pdf-watcher-agent/install-linux.sh
   ```

2. Edit `watcher.properties` with your API key:
   ```bash
   sudo nano /opt/invoicedirect/watcher/watcher.properties
   ```

3. Start the service:
   ```bash
   sudo systemctl start invoicedirect-watcher
   ```

4. Enable at boot:
   ```bash
   sudo systemctl enable invoicedirect-watcher
   ```

5. Check status:
   ```bash
   sudo systemctl status invoicedirect-watcher
   ```

6. View logs:
   ```bash
   sudo journalctl -u invoicedirect-watcher -f
   ```

**Manual install:**

```bash
# Create system user
sudo useradd --system --no-create-home --home-dir /opt/invoicedirect/watcher \
  --shell /usr/sbin/nologin invoicedirect

# Create directories
sudo mkdir -p /opt/invoicedirect/watcher/{inbox,emailed,failed,logs}

# Copy JAR and config
sudo cp pdf-watcher-agent-1.0.0.jar /opt/invoicedirect/watcher/
sudo cp watcher.properties /opt/invoicedirect/watcher/

# Install systemd unit
sudo cp invoicedirect-watcher.service /etc/systemd/system/

# Set ownership
sudo chown -R invoicedirect:invoicedirect /opt/invoicedirect/watcher
sudo chmod 750 /opt/invoicedirect/watcher
sudo chmod 640 /opt/invoicedirect/watcher/watcher.properties

# Reload and enable
sudo systemctl daemon-reload
sudo systemctl enable invoicedirect-watcher
sudo systemctl start invoicedirect-watcher
```

**Uninstall:**
```bash
sudo ./pdf-watcher-agent/uninstall-linux.sh
```

---

## Sidecar File Format

Drop a pair of files into the `inbox/` directory:

- `INV-001.pdf` — the fiscalised invoice PDF
- `INV-001.json` — sidecar metadata

```json
{
  "recipientEmail": "customer@example.com",
  "subject": "Invoice INV-001",
  "organizationId": "00000000-0000-0000-0000-000000000000",
  "templateName": "invoice",
  "invoiceNumber": "INV-001",
  "totalAmount": 250.00,
  "currency": "USD",
  "recipientName": "Acme Corp",
  "companyName": "Your Company",
  "fiscalCode": "XX-1234567",
  "controlCode": "AA12-BB34-CC56-DD78"
}
```

The agent picks up the pair, waits for the sidecar (configurable via `sidecar.wait.ms`), forwards the PDF to the API, then moves both to `emailed/` (or `failed/` on error).

---

## Testing

```bash
./test-local.sh
```

This uses a mock server to verify the agent picks up PDFs, sends them to the API, and handles failures.

---

## Directory Layout (all platforms)

```
/opt/invoicedirect/watcher/      (Linux/macOS)
C:\InvoiceDirect\                (Windows)
├── pdf-watcher-agent-1.0.0.jar
├── watcher.properties
├── inbox/          ← drop PDF + .json sidecar pairs here
├── emailed/        ← processed files land here
├── failed/         ← files that failed processing
├── logs/
│   ├── watcher.log
│   ├── stdout.log
│   └── stderr.log
└── ledger.json     ← deduplication ledger
```
