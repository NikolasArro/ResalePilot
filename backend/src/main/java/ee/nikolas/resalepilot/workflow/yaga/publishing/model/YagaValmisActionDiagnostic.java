package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

/** Bounded, content-free evidence from the single final publication action. */
public record YagaValmisActionDiagnostic(
        int buttonCount,
        Boolean visibleBefore,
        Boolean enabledBefore,
        Boolean boundingBoxPresentBefore,
        Boolean hitTargetBefore,
        Boolean formValidBefore,
        Integer invalidFieldCountBefore,
        String pathnameBefore,
        boolean clickInvocationStarted,
        boolean clickDispatched,
        boolean clickReturned,
        String clickExceptionClass,
        String waitExceptionClass,
        boolean publicationRequestObserved,
        int relevantPatchCount,
        int relevantPostCount,
        int unrecognizedRelevantRequestCount,
        String pathnameAfter,
        Boolean visibleAfter,
        Boolean enabledAfter,
        Boolean formValidAfter,
        Integer invalidFieldCountAfter,
        boolean errorToast,
        boolean successToast,
        boolean validationIndicator,
        boolean caseIdIndicator
) {
    public String safeSummary() {
        return "buttonCount=" + buttonCount + ";visibleBefore=" + visibleBefore +
                ";enabledBefore=" + enabledBefore + ";boundingBoxPresentBefore=" + boundingBoxPresentBefore +
                ";hitTargetBefore=" + hitTargetBefore + ";formValidBefore=" + formValidBefore +
                ";invalidFieldCountBefore=" + invalidFieldCountBefore + ";pathnameBefore=" + pathnameBefore +
                ";clickInvocationStarted=" + clickInvocationStarted + ";clickDispatched=" + clickDispatched +
                ";clickReturned=" + clickReturned + ";clickExceptionClass=" + clickExceptionClass +
                ";waitExceptionClass=" + waitExceptionClass +
                ";publicationRequestObserved=" + publicationRequestObserved +
                ";relevantPatchCount=" + relevantPatchCount + ";relevantPostCount=" + relevantPostCount +
                ";unrecognizedRelevantRequestCount=" + unrecognizedRelevantRequestCount +
                ";pathnameAfter=" + pathnameAfter + ";visibleAfter=" + visibleAfter +
                ";enabledAfter=" + enabledAfter + ";formValidAfter=" + formValidAfter +
                ";invalidFieldCountAfter=" + invalidFieldCountAfter + ";errorToast=" + errorToast +
                ";successToast=" + successToast + ";validationIndicator=" + validationIndicator +
                ";caseIdIndicator=" + caseIdIndicator;
    }
}
