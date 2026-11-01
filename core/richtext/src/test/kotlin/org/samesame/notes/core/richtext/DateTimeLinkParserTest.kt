package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DateTimeLinkParserTest {
    @Test fun parsesDayFirstNumericDate() {
        assertThat(DateTimeLinkParser.parse("12/03/2026"))
            .isEqualTo(ParsedDateTime(year = 2026, month = 3, day = 12))
        assertThat(DateTimeLinkParser.parse("3-11-26"))
            .isEqualTo(ParsedDateTime(year = 2026, month = 11, day = 3))
    }

    @Test fun parsesYearFirstNumericDate() {
        assertThat(DateTimeLinkParser.parse("2026.11.03"))
            .isEqualTo(ParsedDateTime(year = 2026, month = 11, day = 3))
    }

    @Test fun parsesMonthNameDates() {
        assertThat(DateTimeLinkParser.parse("3 Nov")).isEqualTo(ParsedDateTime(month = 11, day = 3))
        assertThat(DateTimeLinkParser.parse("21st March 2027"))
            .isEqualTo(ParsedDateTime(year = 2027, month = 3, day = 21))
        assertThat(DateTimeLinkParser.parse("November 5, 2026"))
            .isEqualTo(ParsedDateTime(year = 2026, month = 11, day = 5))
    }

    @Test fun parsesTimes() {
        assertThat(DateTimeLinkParser.parse("5:30 pm")).isEqualTo(ParsedDateTime(hour = 17, minute = 30))
        assertThat(DateTimeLinkParser.parse("12 am")).isEqualTo(ParsedDateTime(hour = 0, minute = 0))
        assertThat(DateTimeLinkParser.parse("12 pm")).isEqualTo(ParsedDateTime(hour = 12, minute = 0))
        assertThat(DateTimeLinkParser.parse("17:45")).isEqualTo(ParsedDateTime(hour = 17, minute = 45))
        assertThat(DateTimeLinkParser.parse("9am")).isEqualTo(ParsedDateTime(hour = 9, minute = 0))
    }

    @Test fun rejectsInvalidOrUnrelatedText() {
        assertThat(DateTimeLinkParser.parse("")).isNull()
        assertThat(DateTimeLinkParser.parse("31/02/2026")).isNull()
        assertThat(DateTimeLinkParser.parse("hello")).isNull()
        assertThat(DateTimeLinkParser.parse("25:99")).isNull()
    }
}
