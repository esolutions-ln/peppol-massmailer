"""Generate a sample ZIMRA-fiscalised PDF invoice into uat-documents/.

Used for UAT scenarios that require an actual PDF artifact to attach
(TC-08, TC-09, etc.). Includes the fiscal markers (FDMS, fiscal day,
verification code, QR) that PdfAttachmentResolver looks for.
"""
from datetime import date
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
)

OUT = Path(__file__).parent / "UAT-Invoice-INV-UAT-2026-0001.pdf"

INVOICE_NUMBER = "INV-UAT-2026-0001"
TOTAL = 1150.00
VAT = 150.00
NET = 1000.00
CURRENCY = "USD"
SELLER = "eSolutions (Pvt) Ltd"
SELLER_TIN = "2000123456"
SELLER_VAT = "200012345A"
BUYER = "Lucky Ncube"
BUYER_EMAIL = "lucky.ncube@gmail.com"
BUYER_COMPANY = "Acme Buyer Co."
BUYER_TIN = "2000999777"
FDMS_SERIAL = "FDMS-UAT-001"
FISCAL_DAY = 142
GLOBAL_COUNTER = 87654
VERIFICATION_CODE = "E960606BFCB6F08A"
QR_URL = "https://fdms.zimra.co.zw/verify?inv=INV-UAT-2026-0001&code=E960606BFCB6F08A"
DEVICE_ID = "1004212"
FISCAL_INVOICE_NUMBER = "FI-2026-87654"
GLOBAL_RECEIPT_NUMBER = "GR-87654"


def build():
    doc = SimpleDocTemplate(
        str(OUT), pagesize=A4,
        leftMargin=18*mm, rightMargin=18*mm,
        topMargin=18*mm, bottomMargin=18*mm,
    )
    # Disable stream compression so the ZIMRA fiscal markers remain visible as
    # literal bytes — the server-side validator scans the raw PDF without parsing.
    import reportlab.rl_config as rl
    rl.invariant = 1
    rl.pageCompression = 0
    styles = getSampleStyleSheet()
    h1, h2, n = styles["Title"], styles["Heading2"], styles["Normal"]
    story = []

    story.append(Paragraph("TAX INVOICE", h1))
    story.append(Paragraph(f"Invoice No: <b>{INVOICE_NUMBER}</b>", n))
    story.append(Paragraph(f"Date: {date.today().isoformat()}", n))
    story.append(Spacer(1, 6*mm))

    parties = Table(
        [
            ["Seller", "Buyer"],
            [
                f"{SELLER}\nTIN: {SELLER_TIN}\nVAT: {SELLER_VAT}",
                f"{BUYER_COMPANY}\nAttn: {BUYER}\nEmail: {BUYER_EMAIL}\nTIN: {BUYER_TIN}",
            ],
        ],
        colWidths=[85*mm, 85*mm],
    )
    parties.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.lightgrey),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
    ]))
    story.append(parties)
    story.append(Spacer(1, 6*mm))

    lines = Table(
        [
            ["#", "Description", "Qty", "Unit", "Line Total"],
            ["1", "Mass Mailer Annual Subscription", "1", f"{NET:,.2f}", f"{NET:,.2f}"],
        ],
        colWidths=[10*mm, 95*mm, 15*mm, 25*mm, 25*mm],
    )
    lines.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.lightgrey),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
        ("ALIGN", (2, 0), (-1, -1), "RIGHT"),
    ]))
    story.append(lines)
    story.append(Spacer(1, 4*mm))

    totals = Table(
        [
            ["Subtotal", f"{CURRENCY} {NET:,.2f}"],
            ["VAT (15%)", f"{CURRENCY} {VAT:,.2f}"],
            ["TOTAL", f"{CURRENCY} {TOTAL:,.2f}"],
        ],
        colWidths=[130*mm, 40*mm],
    )
    totals.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("FONTNAME", (0, -1), (-1, -1), "Helvetica-Bold"),
        ("BACKGROUND", (0, -1), (-1, -1), colors.lightgrey),
    ]))
    story.append(totals)
    story.append(Spacer(1, 8*mm))

    story.append(Paragraph("Fiscalisation (ZIMRA FDMS)", h2))
    fiscal = Table(
        [
            ["Device ID", DEVICE_ID],
            ["Fiscal Device Serial", FDMS_SERIAL],
            ["Fiscal Day", str(FISCAL_DAY)],
            ["Fiscal Invoice Number", FISCAL_INVOICE_NUMBER],
            ["Global Receipt Number", GLOBAL_RECEIPT_NUMBER],
            ["Global Invoice Counter", str(GLOBAL_COUNTER)],
            ["Verification Code", VERIFICATION_CODE],
            ["Verification URL", QR_URL],
        ],
        colWidths=[55*mm, 115*mm],
    )
    fiscal.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
        ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
        ("BACKGROUND", (0, 0), (0, -1), colors.lightgrey),
    ]))
    story.append(fiscal)
    story.append(Spacer(1, 4*mm))
    story.append(Paragraph(
        "This invoice was issued through a ZIMRA-approved fiscal device "
        "and is signed in accordance with the Fiscalisation Data Management "
        "System (FDMS) regulations.",
        n,
    ))

    doc.build(story)
    print(f"Wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
