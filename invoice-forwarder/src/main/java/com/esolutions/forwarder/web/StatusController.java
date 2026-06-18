package com.esolutions.forwarder.web;

import com.esolutions.forwarder.store.CampaignLedger;
import com.esolutions.forwarder.watcher.PdfBatchWatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/status")
public class StatusController {

    private final PdfBatchWatcher watcher;
    private final CampaignLedger ledger;

    public StatusController(PdfBatchWatcher watcher, CampaignLedger ledger) {
        this.watcher = watcher;
        this.ledger = ledger;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of(
                "processed", watcher.processed(),
                "failed", watcher.failed(),
                "pendingInBatch", watcher.pendingSize(),
                "recent", recent()
        );
    }

    @GetMapping("/recent")
    public List<CampaignLedger.LedgerEntry> recent() {
        return ledger.findRecent(20);
    }
}
