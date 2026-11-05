/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.EventUid;

class EventFieldConverterInvalidPropertiesTest {
    private static final String EVENT_PATH = "/calendars/base1/calendar1/event1.ics";

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "\"x\"", "\"\"", "null"})
    void fromShouldIndexEventWithoutAnUnreadableSequence(String sequence) {
        CalendarEventMessage message = message("""
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["dtstart", {}, "date-time", "2025-04-19T10:00:00Z"],
                ["sequence", {}, "integer", {sequence}]
            ], []]
            """.replace("{sequence}", sequence));

        EventFields fields = EventFieldConverter.from(message).events().iterator().next();

        assertThat(fields.uid()).isEqualTo(new EventUid("event-1"));
        assertThat(fields.sequence()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
    void fromShouldKeepAReadableSequence(int sequence) {
        CalendarEventMessage message = message("""
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["sequence", {}, "integer", {sequence}]
            ], []]
            """.replace("{sequence}", Integer.toString(sequence)));

        assertThat(EventFieldConverter.from(message).events())
            .singleElement().satisfies(fields -> assertThat(fields.sequence()).contains(sequence));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ", null", ", \"\"", ", \"   \"", ", 42"})
    void fromShouldRejectTheWholeSeriesWhenAnOverrideHasNoRecurrenceIdValue(String value) {
        CalendarEventMessage message = message("""
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["dtstart", {}, "date-time", "2025-04-19T10:00:00Z"],
                ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}]
            ], []],
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["dtstart", {}, "date-time", "2025-04-20T11:00:00Z"],
                ["recurrence-id", {}, "date-time"{value}]
            ], []]
            """.replace("{value}", value));

        assertThatThrownBy(() -> EventFieldConverter.from(message))
            .isInstanceOf(CalendarEventDeserializeException.class)
            .hasMessageContaining(EVENT_PATH)
            .hasRootCauseMessage("RECURRENCE-ID must have a non-blank date value");
    }

    @Test
    void fromShouldKeepAValidOverrideAlongsideItsMasterWithoutOptionalProperties() {
        CalendarEventMessage message = message("""
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["dtstart", {}, "date-time", "2025-04-19T10:00:00Z"],
                ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}]
            ], []],
            ["vevent", [
                ["uid", {}, "text", "event-1"],
                ["dtstart", {}, "date-time", "2025-04-20T11:00:00Z"],
                ["recurrence-id", {}, "date-time", "2025-04-20T10:00:00Z"]
            ], []]
            """);

        assertThat(EventFieldConverter.from(message).events())
            .hasSize(2)
            .anySatisfy(fields -> {
                assertThat(fields.isRecurrentMaster()).isTrue();
                assertThat(fields.recurrenceId()).isEmpty();
                assertThat(fields.sequence()).isEmpty();
            })
            .anySatisfy(fields -> {
                assertThat(fields.isRecurrentMaster()).isFalse();
                assertThat(fields.recurrenceId()).contains("2025-04-20T10:00:00Z");
            });
    }

    private CalendarEventMessage message(String components) {
        String json = """
            {"eventPath": "{eventPath}", "event": ["vcalendar", [], [{components}]], "import": false}
            """.replace("{eventPath}", EVENT_PATH)
            .replace("{components}", components);
        return CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
    }
}
