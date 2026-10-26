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

import static com.linagora.calendar.api.CalendarUtil.parseIcs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.property.Method;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class ItipSingleToRecurringReproductionTest {
    private static final String UID = "00000000-0000-0000-0000-000000000001";
    private static final URI EVENT_PATH = URI.create("/calendars/000000000000000000000002/" + UID + ".ics");
    private static final String RRULE = "RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR\n";

    // The rejection time from issue #1217 precedes the event's start at 09:30 UTC.
    private final ItipEmailNotificationPublisher testee = new ItipEmailNotificationPublisher(
        mock(Sender.class),
        body -> new OutboundMessage("exchange", "routingKey", body),
        Clock.fixed(Instant.parse("2026-10-26T08:50:17Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {"user2@example.com", "user3@example.com", "user4@example.com"})
    void convertingSingleEventToRecurringShouldSendRequest(String recipient) throws IOException {
        String oldMessage = fixture("old-single.ics");
        String message = fixture("new-recurring.ics");
        assertThat(EventParseUtils.isRecurringEvent(parseIcs(oldMessage))).isFalse();
        assertThat(EventParseUtils.isRecurringEvent(parseIcs(message))).isTrue();

        var notifications = testee.buildNotificationMessages(dto(message, Optional.of(oldMessage), recipient),
            EVENT_PATH, Optional.of(parseIcs(oldMessage)));

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
            assertThat(notification.recipientEmail()).isEqualTo(recipient);
            assertThat(notification.isNewEvent()).isTrue();
            assertThat(notification.event()).contains(RRULE.trim());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addingOnlyRecurrenceRuleShouldSendInvitation(boolean allDay) {
        String oldMessage = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:00000000-0000-0000-0000-000000000001
            DTSTART%s
            DTEND%s
            SUMMARY:Weekly sync
            ORGANIZER:mailto:user1@example.com
            ATTENDEE:mailto:user2@example.com
            END:VEVENT
            END:VCALENDAR
            """.formatted(allDay ? ";VALUE=DATE:20261026" : ":20261026T093000Z",
                allDay ? ";VALUE=DATE:20261027" : ":20261026T100000Z");
        String message = oldMessage.replace("END:VEVENT", RRULE + "END:VEVENT");

        var notifications = testee.buildNotificationMessages(dto(message, Optional.of(oldMessage), "user2@example.com"),
            EVENT_PATH, Optional.of(parseIcs(oldMessage)));

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
            assertThat(notification.isNewEvent()).isTrue();
            assertThat(notification.event()).isEqualTo(message);
        });
    }

    @Test
    void convertingToRecurringWithExcludedDateShouldNotCancelAnOccurrenceOfTheNewSeries() throws IOException {
        String oldMessage = fixture("old-single.ics");
        String message = fixture("new-recurring.ics")
            .replace(RRULE, RRULE + "EXDATE;TZID=Europe/Moscow:20261027T123000\n");

        var notifications = testee.buildNotificationMessages(dto(message, Optional.of(oldMessage), "user2@example.com"),
            EVENT_PATH, Optional.of(parseIcs(oldMessage)));

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
            assertThat(notification.isNewEvent()).isTrue();
            assertThat(notification.event()).contains("EXDATE;TZID=Europe/Moscow:20261027T123000");
        });
    }

    @Test
    void recurringRequestWithoutOldCalendarShouldSendRequest() throws IOException {
        String message = fixture("new-recurring.ics");

        var notifications = testee.buildNotificationMessages(dto(message, Optional.empty(), "user2@example.com"),
            EVENT_PATH, Optional.empty());

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
            assertThat(notification.recipientEmail()).isEqualTo("user2@example.com");
        });
    }

    @Test
    void singleEventUpdateShouldSendRequest() throws IOException {
        String oldMessage = fixture("old-single.ics");
        String message = fixture("new-recurring.ics").replace(RRULE, "")
            .replace("SUMMARY:Weekly sync", "SUMMARY:Weekly sync updated");

        var notifications = testee.buildNotificationMessages(dto(message, Optional.of(oldMessage), "user2@example.com"),
            EVENT_PATH, Optional.of(parseIcs(oldMessage)));

        assertThat(notifications).singleElement().satisfies(notification ->
            assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST));
    }

    private static ItipLocalDeliveryDTO dto(String message, Optional<String> oldMessage, String recipient) {
        return new ItipLocalDeliveryDTO("mailto:user1@example.com", Method.VALUE_REQUEST, UID,
            "000000000000000000000002", message, oldMessage, true, List.of("mailto:" + recipient));
    }

    private static String fixture(String name) throws IOException {
        try (var input = Objects.requireNonNull(ItipSingleToRecurringReproductionTest.class
            .getResourceAsStream("/itip/issue-1217/" + name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
