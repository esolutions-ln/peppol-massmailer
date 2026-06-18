package com.esolutions.watcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(LedgerStore.class);

    private final Path ledgerFile;
    private final ObjectMapper json;
    private final List<LedgerEntry> entries;

    public LedgerStore(Path ledgerFile) {
        this.ledgerFile = ledgerFile;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
        this.entries = new ArrayList<>();
        load();
    }

    public synchronized boolean alreadySent(String invoiceNumber) {
        return entries.stream().anyMatch(e -> e.invoiceNumber.equals(invoiceNumber));
    }

    public synchronized void recordSent(UUID campaignId, String invoiceNumber, String pdfName, String recipient) {
        entries.add(new LedgerEntry(campaignId, invoiceNumber, pdfName, recipient, Instant.now()));
        save();
    }

    public synchronized List<LedgerEntry> recent(int limit) {
        int from = Math.max(0, entries.size() - limit);
        return new ArrayList<>(entries.subList(from, entries.size()));
    }

    public synchronized int totalSent() {
        return entries.size();
    }

    private void load() {
        if (!Files.exists(ledgerFile)) return;
        try {
            byte[] data = Files.readAllBytes(ledgerFile);
            List<LedgerEntry> loaded = json.readValue(data, new TypeReference<List<LedgerEntry>>() {});
            entries.addAll(loaded);
            log.info("Loaded {} sent records from ledger", entries.size());
        } catch (Exception e) {
            log.warn("Could not load ledger from {}: {} — starting fresh", ledgerFile, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(ledgerFile.getParent());
            json.writeValue(ledgerFile.toFile(), entries);
        } catch (IOException e) {
            log.error("Could not save ledger: {}", e.getMessage());
        }
    }

    public record LedgerEntry(
            UUID campaignId,
            String invoiceNumber,
            String pdfName,
            String recipient,
            Instant sentAt
    ) {}
}
