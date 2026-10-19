package com.example.requestmanagement.application.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSearchCriteriaTest {

    @Test
    void normalizesBlankNameToNoFilter() {
        assertThat(new RequestSearchCriteria("  ", null, 0, 10).name()).isNull();
        assertThat(new RequestSearchCriteria(" loan ", null, 0, 10).name()).isEqualTo("loan");
    }

    @Test
    void rejectsInvalidPagination() {
        assertThatThrownBy(() -> new RequestSearchCriteria(null, null, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestSearchCriteria(null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestSearchCriteria(null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
