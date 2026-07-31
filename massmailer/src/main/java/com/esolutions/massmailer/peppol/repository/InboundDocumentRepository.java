package com.esolutions.massmailer.peppol.repository;

import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.model.InboundDocument.RoutingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboundDocumentRepository extends JpaRepository<InboundDocument, UUID> {

    List<InboundDocument> findByRoutingStatusOrderByReceivedAtAsc(RoutingStatus status);

    List<InboundDocument> findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
            RoutingStatus status, int maxRetryCount, Pageable pageable);

    List<InboundDocument> findByReceiverOrganizationIdOrderByReceivedAtDesc(UUID organizationId);

    List<InboundDocument> findByReceiverOrganizationIdOrderByReceivedAtDesc(UUID organizationId, Pageable pageable);

    List<InboundDocument> findBySenderParticipantIdOrderByReceivedAtDesc(String senderParticipantId);

    List<InboundDocument> findBySenderParticipantIdOrderByReceivedAtDesc(String senderParticipantId, Pageable pageable);

    List<InboundDocument> findAllByOrderByReceivedAtDesc(Pageable pageable);

    boolean existsByInvoiceNumberAndSenderParticipantId(String invoiceNumber, String senderParticipantId);

    Optional<InboundDocument> findByPayloadHash(String payloadHash);
}
