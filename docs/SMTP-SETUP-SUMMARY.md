# SMTP Setup Summary

## Quick Start

### 1. Configure `.env` File

```bash
# SMTP Configuration
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=no-reply@esolutions.co.zw
SMTP_PASSWORD=placeholder

# OAuth2 Credentials
GOOGLE_OAUTH2_CREDENTIALS_PATH=google-oauth-credentials.json
GOOGLE_OAUTH2_REFRESH_TOKEN=your_refresh_token_here

# Sender Configuration
MAIL_FROM_NAME=eSolutions
from_address=no-reply@esolutions.co.zw
```

### 2. Obtain OAuth2 Refresh Token

1. Visit https://developers.google.com/oauthplayground
2. Select scope: `https://mail.google.com/`
3. Authorize and copy the **Refresh Token**
4. Add to `.env` file: `GOOGLE_OAUTH2_REFRESH_TOKEN=<token>`

### 3. Start Application

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"
```

### 4. Send Test Email

```bash
curl -X POST http://localhost:8080/api/v1/mail/invoice \
    -H "Content-Type: application/json" \
    -H "x-api-key: YOUR_ORG_API_KEY" \
    -d '{
      "to": "recipient@example.com",
      "recipientName": "Test Recipient",
      "subject": "Your Invoice",
      "templateName": "invoice",
      "invoiceNumber": "INV-TEST-001",
      "totalAmount": 1250.00,
      "currency": "USD",
      "pdfFilePath": "/tmp/test-invoice.pdf",
      "variables": {
        "companyName": "eSolutions"
      }
    }'
```

## Architecture

### Email Sending Flow

```
API Request (POST /api/v1/mail/invoice)
    ↓
SingleMailController
    ↓
SmtpSendService.send()
    ↓
JavaMailSender (OAuth2 XOAUTH2)
    ↓
Gmail SMTP (smtp.gmail.com:587)
    ↓
Email Delivered with PDF Attachment
```

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `SingleMailController` | `SingleMailController.java` | REST endpoint for single invoice email |
| `SmtpSendService` | `SmtpSendService.java` | SMTP send logic with retry & rate limiting |
| `GmailOAuth2MailConfig` | `GmailOAuth2MailConfig.java` | OAuth2 SMTP configuration |
| `GmailOAuth2TokenProvider` | `GmailOAuth2TokenProvider.java` | OAuth2 token refresh |
| `invoice.html` | `templates/email/invoice.html` | Email template with invoice details |

## Configuration

### SMTP Settings (`application.yml`)

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

### OAuth2 Settings (`.env`)

```bash
GOOGLE_OAUTH2_CREDENTIALS_PATH=google-oauth-credentials.json
GOOGLE_OAUTH2_REFRESH_TOKEN=your_refresh_token
```

### Sender Settings (`.env`)

```bash
MAIL_FROM_NAME=eSolutions
from_address=no-reply@esolutions.co.zw
```

## Testing

### Test Script

```bash
./scripts/send-test-email.sh
```

This script:
1. Creates a test PDF invoice
2. Sends a single invoice email
3. Sends a campaign email to multiple recipients
4. Cleans up test files

### Verify Delivery

1. Check recipient inbox
2. Verify PDF attachment is present
3. Check application logs:
    ```bash
    tail -f logs/spring.log | grep "Invoice"
    ```

Expected log:
```
✓ Invoice INV-2026-0001 sent to recipient@example.com [msgId=<abc123@smtp.gmail.com>, pdf=12345bytes]
```

## API Endpoints

### Send Single Invoice

```
POST /api/v1/mail/invoice
```

### Send Campaign

```
POST /api/v1/campaigns
```

### Check Campaign Status

```
GET /api/v1/campaigns/{id}
```

### Retry Failed Campaign

```
POST /api/v1/campaigns/{id}/retry
```

## Features

- **OAuth2 Authentication**: Gmail SMTP with XOAUTH2 token
- **PDF Attachments**: File path or Base64-encoded PDF
- **Rate Limiting**: Configurable rate limit to avoid SMTP throttling
- **Retry Logic**: Automatic retry on transient failures (3 attempts)
- **Fiscal Compliance**: ZIMRA fiscal fields in email body
- **Template System**: Thymeleaf email templates
- **Batch Processing**: Campaign dispatch in configurable batches

## Documentation Files

| File | Description |
|------|-------------|
| `docs/smtp-setup.md` | Complete SMTP setup guide |
| `docs/email-sending-guide.md` | Quick reference for sending emails |
| `docs/SMTP-SETUP-SUMMARY.md` | This summary |
| `scripts/send-test-email.sh` | Test email script |

## Next Steps

1. **Configure OAuth2**: Get refresh token from OAuth Playground
2. **Update .env**: Add SMTP credentials and refresh token
3. **Start Application**: `mvn spring-boot:run`
4. **Test Email**: Run `./scripts/send-test-email.sh`
5. **Verify Delivery**: Check recipient inbox for email with PDF

## Security Notes

- Never commit credentials to version control
- Use application-specific passwords
- Rotate OAuth2 tokens periodically
- Enable two-factor authentication
- Use HTTPS for all API endpoints
