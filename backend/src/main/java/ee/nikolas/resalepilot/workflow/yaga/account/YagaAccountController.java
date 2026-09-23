package ee.nikolas.resalepilot.workflow.yaga.account;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/yaga/accounts")
public class YagaAccountController {

    private final YagaAccountService service;

    public YagaAccountController(YagaAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<YagaAccountResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<YagaAccountResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    public ResponseEntity<YagaAccountResponse> create(
            @Valid @RequestBody YagaAccountRequest request
    ) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<YagaAccountResponse> put(
            @PathVariable Long id,
            @Valid @RequestBody YagaAccountRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<YagaAccountResponse> patch(
            @PathVariable Long id,
            @Valid @RequestBody YagaAccountPatchRequest request
    ) {
        return ResponseEntity.ok(service.patch(id, request));
    }
}
