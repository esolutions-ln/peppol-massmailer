# SMTP Email Setup Guide

This guide explains how to configure the Mass Mailer to send invoice emails with PDF attachments to destination addresses.

## Overview

The system uses **Gmail SMTP with OAuth2 (XOAUTH2)** authentication to send fiscalised invoice emails. The `SmtpSendService` composes multipart MIME messages with HTML email bodies and PDF invoice attachments.

## Prerequisites

1. **Google Cloud Project** with Gmail API enabled
2. **OAuth2 Credentials** configured for your Gmail account
3. **Refresh Token** obtained via OAuth consent flow

## Step 1: Configure Google Cloud Project

### 1.1 Create OAuth2 Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
2. Create or select a project
3. Enable **Gmail API**
4. Create **OAuth 2.0 Client ID**:
   - Application type: **Web application**
   - Redirect URI: `https://ap.invoicedirect.biz` (or your domain)
   - Download the credentials JSON file

### 1.2 Obtain OAuth2 Refresh Token

1. Visit [OAuth Playground](https://developers.google.com/oauthplayground)
2. In Settings (gear icon), check **"Use my OAuth 2.0 credentials"**
3. Select scope: `https://mail.google.com/`
4. Authorize and exchange authorization code for tokens
5. Copy the **Refresh Token**

## Step 2: Configure Environment Variables

Add the following to your `.env` file:

```bash
# PostgreSQL Database
DB_URL=jdbc:postgresql://localhost:5432/massmailer
DB_USER=mailer
DB_PASS=your_password

# SMTP Configuration (Gmail OAuth2)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=no-reply@yourdomain.com

# OAuth2 Credentials
GOOGLE_OAUTH2_CREDENTIALS_PATH=google-oauth-credentials.json
GOOGLE_OAUTH2_REFRESH_TOKEN=your_refresh_token_here

# Sender Configuration
MAIL_FROM_NAME=eSolutions
from_address=no-reply@yourdomain.com

# Admin Credentials (for API access)
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your_secure_password

# Optional: Gmail Service Account (alternative to refresh token)
GOOGLE_SERVICE_ACCOUNT_KEY_PATH=service-account.json
```

### Environment Variable Reference

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `SMTP_HOST` | Gmail SMTP host | `smtp.gmail.com` | No |
| `SMTP_PORT` | SMTP port | `587` | No |
| `SMTP_USERNAME` | Sender email address | - | Yes |
| `SMTP_PASSWORD` | OAuth2 placeholder | - | No |
| `GOOGLE_OAUTH2_CREDENTIALS_PATH` | Path to OAuth2 credentials JSON | `google-oauth-credentials.json` | Yes |
| `GOOGLE_OAUTH2_REFRESH_TOKEN` | OAuth2 refresh token | - | Yes |
| `MAIL_FROM_ADDRESS` | From email address | `no-reply@esolutions.co.zw` | No |
| `MAIL_FROM_NAME` | From display name | `eSolutions` | No |
| `GOOGLE_SERVICE_ACCOUNT_KEY_PATH` | Service account JSON path | - | Optional |

## Step 3: Configure Credentials File

Place your OAuth2 credentials JSON file in the project root:

```json
{
  "web": {
    "client_id": "YOUR_CLIENT_ID.apps.googleusercontent.com",
    "client_secret": "YOUR_CLIENT_SECRET",
    "project_id": "your-project-id",
    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
    "token_uri": "https://oauth2.googleapis.com/token",
    "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
    "redirect_uris": ["https://your-domain.com"]
  }
}
```

## Step 4: Verify Configuration

### 4.1 Check Application Configuration

The system reads configuration from:
- `application.yml` - Main configuration
- `.env` - Environment variables
- `google-oauth-credentials.json` - OAuth2 credentials

### 4.2 Verify SMTP Settings

In `application.yml`:

```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: no-reply@yourdomain.com
    properties:
      mail:
        smtp:
          auth: true
          auth.mechanisms: XOAUTH2
          starttls:
            enable: true
            required: true
```

## Step 5: Test Email Sending

### 5.1 Start the Application

```bash
# Using Maven
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"

# Using Docker
docker compose up --build
```

### 5.2 Send a Test Email via API

#### Single Invoice Email

```bash
curl -X POST http://localhost:8080/api/v1/mail/invoice \
  -H "Content-Type: application/json" \
  -H "x-api-key: YOUR_ORG_API_KEY" \
  -d '{
    "to": "recipient@example.com",
    "recipientName": "John Doe",
    "subject": "Your Invoice INV-2026-0001",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0001",
    "invoiceDate": "2026-03-25",
    "dueDate": "2026-04-24",
    "totalAmount": 1250.00,
    "vatAmount": 187.50,
    "currency": "USD",
    "fiscalDeviceSerialNumber": "FD-SN-12345",
    "fiscalDayNumber": "42",
    "globalInvoiceCounter": "0001234",
    "verificationCode": "ABCD-EFGH-1234",
    "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234",
    "pdfFilePath": "/path/to/invoice.pdf",
    "variables": {
      "companyName": "eSolutions",
      "accountsEmail": "accounts@esolutions.co.zw",
      "companyAddress": "123 Samora Machel Ave, Harare"
    }
  }'
```

#### Campaign Email (Multiple Recipients)

```bash
curl -X POST http://localhost:8080/api/v1/campaigns \
  -H "Content-Type: application/json" \
  -H "x-api-key: YOUR_ORG_API_KEY" \
  -d '{
    "name": "March 2026 Invoices",
    "subject": "Your Invoice from eSolutions",
    "templateName": "invoice",
    "templateVariables": {
      "companyName": "eSolutions",
      "accountsEmail": "accounts@esolutions.co.zw"
    },
    "recipients": [
      {
        "email": "alice@example.com",
        "name": "Alice Moyo",
        "invoiceNumber": "INV-2026-0100",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 2400.00,
        "vatAmount": 360.00,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "fiscalDayNumber": "60",
        "globalInvoiceCounter": "10001",
        "verificationCode": "AAAA-BBBB-1111",
        "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111",
        "pdfFilePath": "/path/to/INV-2026-0100.pdf",
        "pdfFileName": "INV-2026-0100.pdf"
      },
      {
        "email": "bob@example.com",
        "name": "Bob Chirwa",
        "invoiceNumber": "INV-2026-0101",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 850.00,
        "vatAmount": 127.50,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "fiscalDayNumber": "60",
        "globalInvoiceCounter": "10002",
        "verificationCode": "CCCC-DDDD-2222",
        "pdfFilePath": "/path/to/INV-2026-0101.pdf",
        "pdfFileName": "INV-2026-0101.pdf"
      }
    ]
  }'
```

### 5.3 Check Response

**Single Invoice Response:**
```json
{
  "status": "delivered",
  "recipient": "recipient@example.com",
  "invoiceNumber": "INV-2026-0001",
  "messageId": "<abc123@smtp.gmail.com>",
  "error": null,
  "retryable": false
}
```

**Campaign Response:**
```json
{
  "campaignId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "message": "Invoice campaign queued for dispatch",
  "recipientCount": 2
}
```

## Step 6: Verify Email Delivery

### 6.1 Check Application Logs

```bash
# View logs for email sending
tail -f logs/spring.log | grep "Invoice"
```

Expected log output:
```
✓ Invoice INV-2026-0001 sent to recipient@example.com [msgId=<abc123@smtp.gmail.com>, pdf=12345bytes]
```

### 6.2 Check Recipient Inbox

- Verify the email was received at `recipient@example.com`
- Check that the PDF invoice is attached
- Verify the email body renders correctly with invoice details

## Troubleshooting

### Issue: "Could not connect to SMTP host"

**Cause:** SMTP credentials not configured or invalid

**Solution:**
1. Verify `GOOGLE_OAUTH2_REFRESH_TOKEN` is set
2. Check OAuth2 credentials file exists at specified path
3. Ensure Gmail API is enabled in Google Cloud Console

### Issue: "Invalid token" or "Authentication failed"

**Cause:** OAuth2 token expired or invalid

**Solution:**
1. Obtain a new refresh token from OAuth Playground
2. Update `GOOGLE_OAUTH2_REFRESH_TOKEN` in `.env`
3. Restart the application

### Issue: "PDF file not found"

**Cause:** PDF file path is incorrect or file doesn't exist

**Solution:**
1. Verify the PDF file exists at the specified path
2. Check file permissions
3. Use Base64 encoding instead of file path:
   ```json
   "pdfBase64": "JVBERi0xLjQK..."
   ```

### Issue: "Rate limit exceeded"

**Cause:** Too many emails sent in short period

**Solution:**
1. Reduce `massmailer.rate-limit` in `application.yml`
2. Use batch processing with delays between batches
3. Implement exponential backoff for retries

## Configuration Options

### Gmail OAuth2 Mode (Recommended)

Enable in `application.yml`:
```yaml
massmailer:
  gmail-oauth2:
    enabled: true
```

### Standard SMTP Mode (Basic Auth)

```yaml
massmailer:
  gmail-oauth2:
    enabled: false

spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: your-email@gmail.com
    password: your-app-password  # Use app-specific password
```

### Alternative SMTP Providers

```yaml
spring:
  mail:
    host: smtp.mailgun.org
    port: 587
    username: postmaster@yourdomain.com
    password: your-mailgun-password
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
```

## Testing Checklist

- [ ] OAuth2 credentials file exists and is valid
- [ ] Refresh token is configured and not expired
- [ ] SMTP host and port are correct
- [ ] Sender email address is valid
- [ ] PDF files are accessible (or Base64 encoded)
- [ ] Email templates are configured
- [ ] Test email received successfully with PDF attachment
- [ ] Campaign emails sent to multiple recipients
- [ ] Retry mechanism works for transient failures

## Security Notes

1. **Never commit credentials** to version control
2. Use **application-specific passwords** for SMTP
3. Rotate **OAuth2 refresh tokens** periodically
4. Enable **two-factor authentication** on Gmail accounts
5. Use **HTTPS** for all API endpoints
6. Validate **PDF files** before attachment to prevent malicious content

## References

- [Gmail SMTP Configuration](https://support.google.com/a/answer/2956491)
- [OAuth2 Playground](https://developers.google.com/oauthplayground)
- [JavaMail Documentation](https://docs.oracle.com/javase/javemail/)
- [Spring Boot Mail Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/email.html)
