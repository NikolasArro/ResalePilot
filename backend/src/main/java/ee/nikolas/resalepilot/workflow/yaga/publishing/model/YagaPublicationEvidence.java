package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

/** Safe, allowlisted evidence only. No request payloads or raw response messages. */
public record YagaPublicationEvidence(
        Outcome outcome,
        Integer httpStatus,
        String caseId,
        String errorCode,
        String reason,
        Long externalListingId,
        String productSlug,
        boolean confirmationAllowed
) {
    public enum Outcome { CONFIRMED_SUCCESS, CONFIRMED_FAILURE, RESULT_UNKNOWN }
}
