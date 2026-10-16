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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.mail.internet.InternetAddress;

import org.apache.commons.io.IOUtils;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

class MessageGeneratorTest {
    private static final MimeAttachment LOGO = MimeAttachment.builder()
        .contentType(ContentType.of("image/png"))
        .cid(Cid.from("logo"))
        .content("logo".getBytes(StandardCharsets.UTF_8))
        .fileName("logo.png")
        .inline()
        .build();
    private static final MimeAttachment ICS = MimeAttachment.builder()
        .contentType(ContentType.of("text/calendar; charset=UTF-8"))
        .content("BEGIN:VCALENDAR".getBytes(StandardCharsets.UTF_8))
        .fileName("meeting.ics")
        .dispositionType(MimeAttachment.ATTACHMENT_DISPOSITION_TYPE)
        .build();
    private static final Map<String, Object> MODEL = ImmutableMap.of(
        "content", ImmutableMap.of(
            "baseUrl", "https://contacts.example.com",
            "jobSucceedCount", 9,
            "jobFailedCount", 0));

    private Path templateDirectory;
    private I18NTranslator translator;
    private HtmlBodyRenderer htmlBodyRenderer;

    @BeforeEach
    void setUp() throws Exception {
        templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "import-contacts");
        translator = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile())
            .forLocale(Locale.ENGLISH);
        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toString());
    }

    @Test
    void generateShouldWrapTextAndHtmlIntoMultipartAlternative() throws Exception {
        Message message = reparse(generate(textBodyRenderer(), List.of()));

        Multipart alternative = multipart(multipart(message).getBodyParts().getFirst());

        assertThat(alternative.getSubType()).isEqualTo("alternative");
        assertThat(alternative.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/plain", "text/html");
    }

    @Test
    void textAlternativeShouldBeRenderedFromTextTemplate() throws Exception {
        Message generated = generate(textBodyRenderer(), List.of());
        assertThat(new String(DefaultMessageWriter.asBytes(generated), StandardCharsets.US_ASCII))
            .doesNotContainPattern("(?<!\\r)\\n|\\r(?!\\n)");
        Message message = reparse(generated);

        Entity textPart = multipart(multipart(message).getBodyParts().getFirst()).getBodyParts().getFirst();

        assertThat(textPart.getCharset()).isEqualTo("UTF-8");
        assertThat(textPart.getHeader().getField("Content-Language").getBody()).isEqualTo("en");
        assertThat(IOUtils.toString(((TextBody) textPart.getBody()).getReader()))
            .isEqualTo("""
                Import Report

                Your Import is done see below the report :
                - 9 Contact(s) imported successfully
                - 0 Contact(s) not imported
                """.replace("\n", "\r\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void textAlternativeShouldPreserveWhitespaceAndUnicodeWithCanonicalLineBreaks(String newline) throws Exception {
        String text = "Notes: café & <meeting> " + newline + "Bring snacks.\t" + newline
            + "https://calendar.example.com/event/" + "a".repeat(120) + "?yes=1&lang=fr" + newline;
        TextBodyRenderer renderer = mock(TextBodyRenderer.class);
        when(renderer.render(anyMap())).thenReturn(text);

        Message generated = generate(Optional.of(renderer), List.of());
        Multipart alternative = multipart(multipart(generated).getBodyParts().getFirst());
        Entity textPart = alternative.getBodyParts().getFirst();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new DefaultMessageWriter().writeEntity(textPart, output);
        String encodedPart = output.toString(StandardCharsets.US_ASCII);

        assertThat(encodedPart)
            .doesNotContainPattern("(?<!\\r)\\n|\\r(?!\\n)")
            .contains("=20\r\n", "=09\r\n");
        String encodedBody = encodedPart.substring(encodedPart.indexOf("\r\n\r\n") + 4);
        assertThat(encodedBody.split("\r\n"))
            .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(76));
        Entity decodedTextPart = multipart(multipart(reparse(generated)).getBodyParts().getFirst()).getBodyParts().getFirst();
        assertThat(IOUtils.toString(((TextBody) decodedTextPart.getBody()).getReader()))
            .isEqualTo(text.replace(newline, "\r\n"));
    }

    @Test
    void inlineAttachmentsShouldBeRelatedToTheHtmlAlternative() throws Exception {
        Message message = reparse(generate(textBodyRenderer(), List.of(LOGO, ICS)));

        Multipart mixed = multipart(message);
        Multipart alternative = multipart(mixed.getBodyParts().getFirst());
        Multipart related = multipart(alternative.getBodyParts().get(1));

        assertThat(mixed.getSubType()).isEqualTo("mixed");
        assertThat(mixed.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("multipart/alternative", "text/calendar");
        assertThat(alternative.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/plain", "multipart/related");
        assertThat(related.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/html", "image/png");
        ContentTypeField relatedContentType = (ContentTypeField) related.getParent().getHeader().getField("Content-Type");
        assertThat(relatedContentType.getParameter("type")).isEqualTo("text/html");
    }

    @Test
    void generateShouldSendHtmlOnlyWhenNoTextTemplate() throws Exception {
        Message message = reparse(generate(Optional.empty(), List.of(LOGO)));

        Multipart mixed = multipart(message);
        Multipart related = multipart(mixed.getBodyParts().getFirst());

        assertThat(mixed.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("multipart/related");
        assertThat(related.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/html", "image/png");
        ContentTypeField relatedContentType = (ContentTypeField) related.getParent().getHeader().getField("Content-Type");
        assertThat(relatedContentType.getParameter("type")).isEqualTo("text/html");
    }

    private Optional<TextBodyRenderer> textBodyRenderer() throws IOException {
        return TextBodyRenderer.forPath(templateDirectory.toString());
    }

    private Message generate(Optional<TextBodyRenderer> textBodyRenderer, List<MimeAttachment> attachments) throws Exception {
        MessageGenerator testee = new MessageGenerator(translator, htmlBodyRenderer, textBodyRenderer, null);
        return testee.generate(new InternetAddress("bob@domain.tld"), new InternetAddress("no-reply@domain.tld"), MODEL, attachments)
            .block();
    }

    private Message reparse(Message message) throws IOException {
        byte[] bytes = DefaultMessageWriter.asBytes(message);
        return new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(bytes));
    }

    private Multipart multipart(Entity entity) {
        return (Multipart) entity.getBody();
    }
}
