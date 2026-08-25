package com.depo.bulkimport.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkImportJobServiceTest {

    @Test
    void cancellingOneImportDoesNotAffectAnotherImport() {
        BulkImportJobService service = new BulkImportJobService();
        String firstId = "11111111-1111-1111-1111-111111111111";
        String secondId = "22222222-2222-2222-2222-222222222222";
        BulkImportCancellationToken first = service.start(firstId);
        BulkImportCancellationToken second = service.start(secondId);

        service.cancel(firstId);

        assertThatThrownBy(first::throwIfCancelled)
                .isInstanceOf(BulkImportCancelledException.class);
        assertThatCode(second::throwIfCancelled).doesNotThrowAnyException();
        service.finish(first);
        service.finish(second);
    }
}
