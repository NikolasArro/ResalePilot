package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/accounts/{accountId}/conditions")
public class YagaUusConditionRepairController {

    private final YagaUusConditionRepairService service;

    public YagaUusConditionRepairController(
            YagaUusConditionRepairService service
    ) {
        this.service = service;
    }

    @PostMapping("/repair-uus")
    public YagaUusConditionRepairService.Result repair(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "false") boolean apply
    ) {
        return service.repair(accountId, apply);
    }
}
