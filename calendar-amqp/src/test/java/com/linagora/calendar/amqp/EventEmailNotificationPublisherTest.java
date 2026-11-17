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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.CalendarUtil;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.Sender;

class EventEmailNotificationPublisherTest {
    private static final String MASTER = """
        BEGIN:VEVENT
        UID:series
        DTSTAMP:20261030T000000Z
        DTSTART:20261103T090000Z
        DTEND:20261103T100000Z
        RRULE:FREQ=WEEKLY;COUNT:2
        ORGANIZER:mailto:alice@example.org
        ATTENDEE;PARTSTAT=TENTATIVE:mailto:bob@example.org
        END:VEVENT
        """;

    private static final String MOVED_LAST_OCCURRENCE = """
        BEGIN:VEVENT
        UID:series
        DTSTAMP:20261030T000000Z
        RECURRENCE-ID:20261110T090000Z
        DTSTART:20261120T110000Z
        DTEND:20261120T120000Z
        ORGANIZER:mailto:alice@example.org
        ATTENDEE;PARTSTAT=TENTATIVE:mailto:bob@example.org
        END:VEVENT
        """;

    @Test
    void shouldPublishReplyWhenLastOccurrenceIsMovedPastTheEndOfTheSeries() throws Exception {
        assertPublishesReply(MOVED_LAST_OCCURRENCE + MASTER, Instant.parse("2026-11-17T00:00:00Z"));
    }

    @Test
    void shouldPublishReplyWhenOverrideHasUnknownProperty() throws Exception {
        String overrideWithUnknownProperty = MOVED_LAST_OCCURRENCE.replace("END:VEVENT", "FOOBAR:baz\nEND:VEVENT");
        assertPublishesReply(MASTER + overrideWithUnknownProperty, Instant.parse("2026-11-01T00:00:00Z"));
    }

    private void assertPublishesReply(String components, Instant now) throws Exception {
        ReactorRabbitMQChannelPool channelPool = mock(ReactorRabbitMQChannelPool.class);
        Sender sender = mock(Sender.class);
        when(channelPool.getSender()).thenReturn(sender);
        when(sender.send(any())).thenReturn(Mono.empty());

        EventEmailNotificationPublisher publisher = new EventEmailNotificationPublisher(channelPool, Clock.fixed(now, ZoneOffset.UTC));
        publisher.publishReply(
                CalendarUtil.parseIcs("""
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Test//Calendar//EN
                    {components}END:VCALENDAR
                    """.replace("{components}", components)),
                new MailAddress("bob@example.org"),
                new MailAddress("alice@example.org"),
                "/calendar/series.ics")
            .block();

        verify(sender).send(any());
    }
}
