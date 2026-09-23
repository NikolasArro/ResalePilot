package ee.nikolas.resalepilot.workflow.yaga.account;

import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YagaAccountServiceTest {

    @Mock
    private YagaAccountRepository repository;

    private YagaAccountService service;

    @BeforeEach
    void setUp() {
        service = new YagaAccountService(repository);
    }

    @Test
    void patchDriveFolderOnlyPreservesOmittedFields() {
        YagaAccount account = account();
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(repository.saveAndFlush(any(YagaAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        YagaAccountResponse response = service.patch(
                1L,
                new YagaAccountPatchRequest(
                        null,
                        null,
                        null,
                        "drive-folder-2",
                        null,
                        null,
                        null
                )
        );

        assertThat(response.name()).isEqualTo("Nik AR");
        assertThat(response.shopSlug()).isEqualTo("nik-ar");
        assertThat(response.authStatePath()).isEqualTo("auth/nik-ar.json");
        assertThat(response.driveFolderId()).isEqualTo("drive-folder-2");
        assertThat(response.enabled()).isTrue();
        assertThat(response.autoRefreshEnabled()).isTrue();
        assertThat(response.batchSize()).isEqualTo(10);
    }

    @Test
    void patchUpdatesOptionalFields() {
        YagaAccount account = account();
        when(repository.findById(1L)).thenReturn(Optional.of(account));
        when(repository.saveAndFlush(any(YagaAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        YagaAccountResponse response = service.patch(
                1L,
                new YagaAccountPatchRequest(
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        4
                )
        );

        assertThat(response.enabled()).isFalse();
        assertThat(response.autoRefreshEnabled()).isFalse();
        assertThat(response.batchSize()).isEqualTo(4);
        assertThat(response.name()).isEqualTo("Nik AR");
        assertThat(response.shopSlug()).isEqualTo("nik-ar");
    }

    @Test
    void patchRejectsExplicitBlankName() {
        YagaAccount account = account();
        when(repository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.patch(
                1L,
                new YagaAccountPatchRequest(
                        " ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(YagaRefreshRequestInvalidException.class)
                .hasMessage("Yaga account name must not be blank");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void patchRejectsExplicitBlankShopSlug() {
        YagaAccount account = account();
        when(repository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.patch(
                1L,
                new YagaAccountPatchRequest(
                        null,
                        "",
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(YagaRefreshRequestInvalidException.class)
                .hasMessage("Yaga account shopSlug must not be blank");

        verify(repository, never()).saveAndFlush(any());
    }

    private YagaAccount account() {
        YagaAccount account = new YagaAccount(
                "Nik AR",
                "nik-ar",
                "auth/nik-ar.json",
                10
        );
        ReflectionTestUtils.setField(account, "id", 1L);
        account.setDriveFolderId("drive-folder-1");
        account.setEnabled(true);
        account.setAutoRefreshEnabled(true);
        return account;
    }
}
