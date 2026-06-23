# InvoiceDirect PEPPOL Developer Guide

## Document Overview

This comprehensive developer guide documents the PEPPOL e-delivery platform implementation within the InvoiceDirect Mass Mailer application.

**File:** `InvoiceDirect_PEPPOL_Developer_Guide.docx`  
**Format:** Microsoft Word (DOCX)  
**Size:** ~66 KB  
**Pages:** ~85 pages  
**Version:** 1.0  
**Date:** June 2026

## What's Included

### Core Content

1. **Executive Summary** - Platform overview and technology stack
2. **PEPPOL Overview** - 4-corner model, participant IDs, BIS 3.0
3. **Architecture** - Package structure, component interaction, database schema
4. **Data Model** - JPA entities with business rules
5. **API Reference** - Complete endpoint documentation with examples
6. **Implementation Components**:
   - UBL 2.1 Invoice Builder
   - Schematron Validator (EN16931)
   - AS4 Transport Client (XML-DSIG & XML-Enc)
   - PEPPOL Delivery Service
   - SMP Client
   - C4 Routing Job
7. **Integration Guide** - Step-by-step onboarding workflows
8. **Security & Compliance** - Authentication, XML security, audit findings
9. **Deployment Guide** - Docker, production checklist, environment variables
10. **Troubleshooting** - Common issues and diagnostics

### Appendices

- **Appendix A:** Glossary of PEPPOL terms
- **Appendix B:** Standards & specifications references
- **Appendix C:** Sample API workflows (bash scripts)
- **Appendix D:** PDF Email Delivery Endpoints (NEW)
  - Single invoice email
  - Bulk campaigns
  - ERP integration
  - Email templates
  - Delivery mode router
- **Appendix E:** PEPPOL Network Deep Dive (NEW)
  - Network architecture diagram
  - Document exchange flow (16 steps)
  - Test vs Production networks
  - Participant ID schemes
  - Error codes & diagnostics

## Key Features Documented

### PEPPOL Implementation
✅ 4-corner model routing  
✅ UBL 2.1 BIS Billing 3.0 generation  
✅ AS4 ebMS 3.0 transport with XML-DSIG signing  
✅ XML-Enc encryption (AES-256 + RSA-OAEP)  
✅ Schematron validation (EN16931)  
✅ SMP/SML lookup and caching  
✅ Customer self-registration via invitations  
✅ eRegistry for AP management  
✅ Certificate lifecycle management  
✅ C4 routing job for inbound documents  

### Email Delivery
✅ Single invoice email (JSON + Base64)  
✅ PDF upload multipart  
✅ Bulk campaign dispatch  
✅ ERP integration endpoints  
✅ Custom email templates (Thymeleaf)  
✅ Automatic EMAIL ↔ PEPPOL routing  
✅ Fallback delivery on PEPPOL failure  

## API Endpoints Documented

### PEPPOL
- Admin onboarding: `POST /api/v1/admin/orgs/{orgId}/peppol/onboard`
- Access points: `POST /api/v1/eregistry/access-points`
- Participant links: `POST /api/v1/eregistry/participant-links`
- Certificates: `POST /api/v1/admin/orgs/{orgId}/peppol/certs`
- Inbound receive: `POST /peppol/as4/receive`
- SMP endpoint: `GET /bdxr/smp/{participantId}/services/{docType}`

### Email Delivery
- Single invoice: `POST /api/v1/mail/invoice`
- Upload invoice: `POST /api/v1/mail/invoice/upload`
- Create campaign: `POST /api/v1/campaigns`
- Campaign status: `GET /api/v1/campaigns/{id}`
- ERP dispatch: `POST /api/v1/erp/dispatch`
- Retry failed: `POST /api/v1/campaigns/{id}/retry`

## Code Examples Included

- Complete onboarding bash script (7 steps)
- cURL examples for all major endpoints
- Java ERP adapter implementation
- C4 webhook endpoint example
- Certificate generation commands
- Database diagnostic queries
- Log analysis commands

## Standards Compliance

| Standard | Status | Notes |
|----------|--------|-------|
| PEPPOL BIS 3.0 | ✅ PASS | Full UBL 2.1 implementation |
| EN16931 | ⚠️ STUB | Production rules required |
| AS4 Profile 2.0 | ✅ PASS | XML-DSIG + XML-Enc |
| ISO 6523 | ✅ PASS | Participant ID schemes |

## Known Limitations

**CRITICAL:**
- Schematron stub (no real validation)
- Path traversal vulnerability
- Unauthenticated inbox endpoint
- UBL XML stored in plaintext

**Documented in Section 11 with remediation steps**

## Target Audience

- Backend developers extending PEPPOL functionality
- DevOps engineers deploying the platform
- Integration partners connecting ERP systems
- QA engineers testing compliance
- Security auditors reviewing implementation

## How to Use This Guide

1. **Quick Start:** Read sections 1-2 for overview
2. **API Integration:** Jump to sections 5 and Appendix D
3. **Deep Technical:** Read sections 6 and Appendix E
4. **Deployment:** Follow sections 9-10
5. **Troubleshooting:** Reference section 10 and diagnostic queries

## Related Files

- `massmailer.md` - Main platform documentation
- `peppol/peppol-e-delivery-documentation.md` - Technical PEPPOL docs
- `deploy.sh` - Deployment script
- `docker-compose.yml` - Container orchestration

## Contact & Support

- **Development Team:** dev@esolutions.co.zw
- **Support:** support@esolutions.co.zw
- **Production URL:** https://ap.invoicedirect.biz
- **Repository:** (internal GitLab)

---

**Document Status:** ✅ Complete and ready for distribution  
**Last Updated:** June 22, 2026  
**Maintained by:** eSolutions Development Team
