package ee.nikolas.resalepilot.workflow.yaga.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface YagaAccountRepository
        extends JpaRepository<YagaAccount, Long> {

    Optional<YagaAccount> findByShopSlug(String shopSlug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
            from YagaAccount account
            where account.id = :id
            """)
    Optional<YagaAccount> findForUpdateById(Long id);

    boolean existsByShopSlug(String shopSlug);

    List<YagaAccount> findAllByEnabledTrueAndAutoRefreshEnabledTrueOrderByIdAsc();
}
