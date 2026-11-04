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

package com.linagora.calendar.amqp.model;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.SubjectRenderer;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

public record CalendarEventBookingConfirmedNotificationEmail(CalendarEventNotificationEmail base) implements CalendarEventBookingNotificationEmail {

    public static CalendarEventBookingConfirmedNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventBookingConfirmedNotificationEmail(CalendarEventNotificationEmail.from(dto));
    }

    public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay, I18NTranslator translator, MailAddress recipientEmail) throws Exception {
        MailAddress proposerEmail = proposerEmail();
        String proposerDisplayName = proposerDisplayName(proposerEmail, recipientEmail, translator);
        String bookingConfirmedMessage = SubjectRenderer.of(translator.get("booking_confirmed_message"))
            .render(Map.of("subject.proposal_owner", proposerDisplayName));

        return ImmutableMap.of(
            "content", ImmutableMap.of(
                "event", base.toPugModel(locale, zoneToDisplay),
                "bookingConfirmedMessage", bookingConfirmedMessage,
                "proposerEmail", proposerEmail.asString()),
            "subject.summary", EventParseUtils.getSummary(base.getFirstVEvent()).orElse(StringUtils.EMPTY),
            "subject.organizer", PersonModel.from(EventParseUtils.getOrganizer(base.getFirstVEvent())).displayName(),
            "subject.proposal_owner", proposerDisplayName);
    }
}
