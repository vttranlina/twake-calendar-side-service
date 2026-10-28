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

package com.linagora.calendar.restapi.attachment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linagora.calendar.restapi.attachment.TdriveAttachmentStorageConfiguration.DriveAccount;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class TdriveAttachmentStorageClient implements AttachmentStorageClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class TdriveException extends RuntimeException {
        TdriveException(String message) {
            super(message);
        }

        TdriveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final TdriveAttachmentStorageConfiguration configuration;
    private final HttpClient client;

    @Inject
    public TdriveAttachmentStorageClient(TdriveAttachmentStorageConfiguration configuration) {
        this.configuration = configuration;
        this.client = HttpClient.create().responseTimeout(Duration.ofSeconds(30)).disableRetry(true);
    }

    @Override
    public Mono<StoredAttachment> upload(Upload upload) {
        return Mono.defer(() -> {
            DriveAccount account = configuration.accounts().get(upload.userId());
            if (account == null) {
                return Mono.error(new UserNotProvisionedException(upload.userId()));
            }
            byte[] content = Base64.getDecoder().decode(upload.contentBase64());
            Preconditions.checkArgument(content.length <= MAX_FILE_BYTES, "Attachment too large");
            String md5 = Base64.getEncoder().encodeToString(DigestUtils.md5(content));
            String sha256 = DigestUtils.sha256Hex(content);
            // A stable name lets a retried DAV PUT recover the same file, including after a restart.
            String context = MAPPER.valueToTree(List.of(upload.userId(), upload.calendarPath(), upload.eventUid(), upload.filename(), upload.contentType(), sha256)).toString();
            String name = "calendar-" + DigestUtils.sha256Hex(context) + "-" + upload.filename();
            return uploadFile(account, name, upload.contentType(), content, md5)
                .flatMap(file -> share(account, file.path("id").asText()))
                .map(code -> new StoredAttachment(account.driveUrl().resolve("/public").toString()
                    + "?sharecode=" + encode(code) + "#/", upload.filename(), upload.contentType(), content.length, sha256));
        });
    }

    private Mono<JsonNode> uploadFile(DriveAccount account, String name, String contentType, byte[] content, String md5) {
        return request(account, HttpMethod.POST, "/files/io.cozy.files.root-dir?Type=file&Name=" + encode(name), contentType, content, md5)
            .flatMap(reply -> {
                if (reply.status() == 409) {
                    return request(account, HttpMethod.GET, "/files/metadata?Path=" + encode("/" + name), null, null, null)
                        .map(this::successfulJson);
                }
                return Mono.just(successfulJson(reply));
            })
            .map(json -> {
                JsonNode file = json.path("data");
                if (file.path("id").asText().isEmpty() || !file.path("attributes").path("md5sum").asText().equals(md5)
                    || file.path("attributes").path("size").asLong(-1) != content.length) {
                    throw new TdriveException("Drive upload metadata does not match content");
                }
                return file;
            });
    }

    private Mono<String> share(DriveAccount account, String fileId) {
        return request(account, HttpMethod.GET, "/permissions/doctype/io.cozy.files/shared-by-link", null, null, null)
            .map(this::successfulJson)
            .flatMap(json -> {
                for (JsonNode permission : json.path("data")) {
                    JsonNode attributes = permission.path("attributes");
                    JsonNode files = attributes.path("permissions").path("files");
                    JsonNode codes = attributes.path("shortcodes");
                    String code = codes.path("calendar").asText(codes.path("code").asText());
                    if (files.path("values").size() == 1 && files.path("values").path(0).asText().equals(fileId)
                        && files.path("type").asText().equals("io.cozy.files")
                        && files.path("verbs").size() == 1 && files.path("verbs").path(0).asText().equals("GET")
                        && attributes.path("permissions").size() == 1 && !code.isEmpty()
                        && !attributes.hasNonNull("expires_at")) {
                        return Mono.just(code);
                    }
                }
                byte[] payload = serialize(Map.of("data", Map.of("type", "io.cozy.permissions", "attributes", Map.of("permissions",
                    Map.of("files", Map.of("type", "io.cozy.files", "verbs", new String[]{"GET"}, "values", new String[]{fileId}))))));
                return request(account, HttpMethod.POST, "/permissions?codes=calendar", "application/vnd.api+json", payload, null)
                    .map(this::successfulJson)
                    .map(created -> {
                        String code = created.path("data").path("attributes").path("shortcodes").path("calendar").asText();
                        if (code.isBlank()) {
                            throw new TdriveException("Drive share response missing shortcode");
                        }
                        return code;
                    });
            });
    }

    private record Reply(int status, byte[] body) { }

    private Mono<Reply> request(DriveAccount account, HttpMethod method, String path, String contentType, byte[] body, String md5) {
        return client.headers(headers -> {
            headers.set("Authorization", "Bearer " + account.accessToken());
            headers.set("Accept", "application/vnd.api+json");
            if (contentType != null) {
                headers.set("Content-Type", contentType);
            }
            if (md5 != null) {
                headers.set("Content-MD5", md5);
            }
        }).request(method).uri(account.instanceUrl().resolve(path).toString())
            .send(body == null ? Mono.empty() : Mono.fromSupplier(() -> Unpooled.wrappedBuffer(body)))
            .responseSingle((response, bytes) -> bytes.asByteArray().defaultIfEmpty(new byte[0])
                .map(payload -> new Reply(response.status().code(), payload)))
            .onErrorMap(error -> !(error instanceof TdriveException),
                error -> new TdriveException("Drive transport failure: " + error.getClass().getSimpleName(), error));
    }

    private JsonNode successfulJson(Reply reply) {
        if (reply.status() < 200 || reply.status() >= 300) {
            // Upstream response bodies may carry tokens or sharing codes.
            throw new TdriveException("Drive API returned HTTP " + reply.status());
        }
        try {
            return MAPPER.readTree(reply.body());
        } catch (Exception e) {
            throw new TdriveException("Invalid Drive API JSON response");
        }
    }

    static byte[] serialize(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize attachment response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
