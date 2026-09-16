package ee.nikolas.resalepilot.workflow.yaga.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "yaga_accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_yaga_accounts_shop_slug",
                        columnNames = "shop_slug"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "shop_slug", nullable = false, length = 150)
    private String shopSlug;

    @Column(name = "auth_state_path", length = 500)
    private String authStatePath;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "auto_refresh_enabled", nullable = false)
    private boolean autoRefreshEnabled = true;

    @Column(name = "batch_size", nullable = false)
    private int batchSize = 10;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaAccount(
            String name,
            String shopSlug,
            String authStatePath,
            int batchSize
    ) {
        this.name = name;
        this.shopSlug = shopSlug;
        this.authStatePath = authStatePath;
        this.batchSize = batchSize;
    }
}
