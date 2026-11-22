package com.lms.course;

import com.lms.common.error.ValidationException;
import com.lms.course.model.Slug;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlugTest {

    @ParameterizedTest
    @CsvSource({
            "'Spring Boot in Action', spring-boot-in-action",
            "'  Leading and trailing  ', leading-and-trailing",
            "'C++ for Beginners!', c-for-beginners",
            "'Café Déjà Vu', cafe-deja-vu",
            "'Multiple   spaces', multiple-spaces",
            "'Already-a-slug', already-a-slug"
    })
    void builds_a_url_safe_link_from_a_title(String title, String expected) {
        assertThat(Slug.from(title)).isEqualTo(expected);
    }

    @Test
    void rejects_a_title_with_nothing_usable_in_it() {
        assertThatThrownBy(() -> Slug.from("!!! ???"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no characters usable");
    }

    @Test
    void a_long_title_is_cut_at_a_word_boundary() {
        String slug = Slug.from("word ".repeat(60));

        assertThat(slug).hasSizeLessThanOrEqualTo(Slug.MAX_LENGTH).doesNotEndWith("-");
    }

    @Test
    void rejects_a_lookup_by_something_that_is_not_a_link() {
        assertThatThrownBy(() -> Slug.requireValid("Not A Slug"))
                .isInstanceOf(ValidationException.class);
    }
}
