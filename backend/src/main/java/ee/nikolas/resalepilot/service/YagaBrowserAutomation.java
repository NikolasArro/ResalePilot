package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaListingDraftData;

import java.util.List;

public interface YagaBrowserAutomation {

    YagaFormFillResult prepareForm(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    );
}
