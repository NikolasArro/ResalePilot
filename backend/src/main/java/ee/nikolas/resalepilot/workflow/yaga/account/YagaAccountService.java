package ee.nikolas.resalepilot.workflow.yaga.account;

import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class YagaAccountService {

    public static final String DEFAULT_SHOP_SLUG = "nik-ar";

    private final YagaAccountRepository repository;

    public YagaAccountService(YagaAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<YagaAccountResponse> list() {
        return repository.findAll()
                .stream()
                .map(YagaAccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<YagaAccount> autoRefreshAccounts() {
        return repository
                .findAllByEnabledTrueAndAutoRefreshEnabledTrueOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public YagaAccount getEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new YagaAccountNotFoundException(id));
    }

    @Transactional
    public YagaAccount lockForRefreshPlanning(Long id) {
        return repository.findForUpdateById(id)
                .orElseThrow(() -> new YagaAccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public YagaAccount defaultAccount() {
        return repository.findByShopSlug(DEFAULT_SHOP_SLUG)
                .orElseThrow(() ->
                        new YagaRefreshRequestInvalidException(
                                "Default Yaga account is not configured"
                        )
                );
    }

    @Transactional(readOnly = true)
    public YagaAccount requireByShopSlug(String shopSlug) {
        return repository.findByShopSlug(shopSlug)
                .orElseThrow(() ->
                        new YagaRefreshRequestInvalidException(
                                "Yaga account is not configured for shopSlug"
                        )
                );
    }

    @Transactional(readOnly = true)
    public YagaAccountResponse get(Long id) {
        return YagaAccountResponse.from(getEntity(id));
    }

    @Transactional
    public YagaAccountResponse create(YagaAccountRequest request) {
        validate(request);
        if (repository.existsByShopSlug(request.shopSlug())) {
            throw new YagaRefreshRequestInvalidException(
                    "Yaga shopSlug must be unique"
            );
        }
        YagaAccount account = new YagaAccount(
                request.name(),
                request.shopSlug(),
                blankToNull(request.authStatePath()),
                effectiveBatchSize(request.batchSize())
        );
        account.setEnabled(request.enabled() == null || request.enabled());
        account.setAutoRefreshEnabled(
                request.autoRefreshEnabled() == null ||
                        request.autoRefreshEnabled()
        );
        return YagaAccountResponse.from(repository.saveAndFlush(account));
    }

    @Transactional
    public YagaAccountResponse update(Long id, YagaAccountRequest request) {
        validate(request);
        YagaAccount account = getEntity(id);
        repository.findByShopSlug(request.shopSlug())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new YagaRefreshRequestInvalidException(
                            "Yaga shopSlug must be unique"
                    );
                });
        account.setName(request.name());
        account.setShopSlug(request.shopSlug());
        account.setAuthStatePath(blankToNull(request.authStatePath()));
        account.setEnabled(request.enabled() == null || request.enabled());
        account.setAutoRefreshEnabled(
                request.autoRefreshEnabled() == null ||
                        request.autoRefreshEnabled()
        );
        account.setBatchSize(effectiveBatchSize(request.batchSize()));
        return YagaAccountResponse.from(repository.saveAndFlush(account));
    }

    private void validate(YagaAccountRequest request) {
        if (request == null ||
                isBlank(request.name()) ||
                isBlank(request.shopSlug())) {
            throw new YagaRefreshRequestInvalidException(
                    "Yaga account name and shopSlug are required"
            );
        }
        if (request.batchSize() != null && request.batchSize() < 1) {
            throw new YagaRefreshRequestInvalidException(
                    "batchSize must be at least 1"
            );
        }
    }

    private int effectiveBatchSize(Integer batchSize) {
        return batchSize == null ? 10 : batchSize;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
