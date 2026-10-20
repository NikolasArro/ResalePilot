package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairPreview;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaStrandedJobRepairService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/accounts/{accountId}/refresh-jobs/stranded-selected")
@ConditionalOnProperty(prefix = "yaga.refresh", name = "enabled", havingValue = "true")
public class YagaStrandedJobRepairController {
    private final YagaStrandedJobRepairService repair;

    public YagaStrandedJobRepairController(YagaStrandedJobRepairService repair) {
        this.repair = repair;
    }

    @GetMapping("/preview")
    public YagaStrandedJobRepairPreview preview(@PathVariable Long accountId) {
        return repair.preview(accountId);
    }

    @PostMapping("/apply")
    public YagaStrandedJobRepairResult apply(@PathVariable Long accountId,
            @Valid @RequestBody YagaStrandedJobRepairRequest request) {
        return repair.apply(accountId, request);
    }
}
