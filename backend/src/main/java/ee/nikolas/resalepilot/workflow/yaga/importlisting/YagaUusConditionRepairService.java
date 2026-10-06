package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class YagaUusConditionRepairService {

    private static final String SOURCE_PREDICATE = """
            l.yaga_account_id = ?
            and l.marketplace = 'YAGA'
            and l.status = 'PUBLISHED'
            and l.is_current = true
            and l.external_condition_id = 1
            and l.external_condition_name = 'Uus'
            """;

    private static final String NO_CONFLICTING_CURRENT_LISTING = """
            not exists (
                select 1 from marketplace_listings other
                where other.product_id = p.id
                  and other.marketplace = 'YAGA'
                  and other.status = 'PUBLISHED'
                  and other.is_current = true
                  and (other.external_condition_id is distinct from 1
                       or other.external_condition_name is distinct from 'Uus')
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final YagaAccountService accountService;

    public YagaUusConditionRepairService(
            JdbcTemplate jdbcTemplate,
            YagaAccountService accountService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
    }

    @Transactional
    public Result repair(Long accountId, boolean apply) {
        accountService.getEntity(accountId);

        // Hold source rows stable until the guarded Product update commits.
        if (apply) {
            jdbcTemplate.queryForList("""
                    select l.id from marketplace_listings l
                    where
                    """ + SOURCE_PREDICATE + "\nfor update of l", Long.class,
                    accountId);
        }

        int examined = jdbcTemplate.queryForObject("""
                select count(distinct p.id)
                from products p
                join marketplace_listings l on l.product_id = p.id
                where
                """ + SOURCE_PREDICATE, Integer.class, accountId);

        int eligible = jdbcTemplate.queryForObject("""
                select count(distinct p.id)
                from products p
                join marketplace_listings l on l.product_id = p.id
                where
                """ + SOURCE_PREDICATE + "\n" + """
                and p.condition = 'NEW_WITHOUT_TAGS'
                and
                """ + NO_CONFLICTING_CURRENT_LISTING,
                Integer.class, accountId);

        if (!apply) {
            return new Result(accountId, false, examined, eligible, 0,
                    examined - eligible);
        }

        List<Long> updatedIds = jdbcTemplate.query("""
                update products p
                set condition = 'NEW_WITH_TAGS', updated_at = now()
                where p.condition = 'NEW_WITHOUT_TAGS'
                  and exists (
                      select 1 from marketplace_listings l
                      where l.product_id = p.id and
                      """ + SOURCE_PREDICATE + "\n" + """
                  )
                  and
                  """ + NO_CONFLICTING_CURRENT_LISTING + "\n" + """
                returning p.id
                """, (rs, rowNum) -> rs.getLong(1), accountId);

        return new Result(accountId, true, examined, eligible,
                updatedIds.size(), examined - updatedIds.size());
    }

    public record Result(Long accountId, boolean applied, int examined,
                         int eligible, int updated, int skipped) {
    }
}
