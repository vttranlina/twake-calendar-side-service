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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.restapi.CalendarRestApiServer;
import com.linagora.calendar.restapi.DavProxy;
import com.linagora.calendar.restapi.FallbackProxy;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.attachment.AttachmentStorageClient.StoredAttachment;
import com.linagora.calendar.restapi.attachment.AttachmentStorageClient.Upload;
import com.linagora.calendar.restapi.attachment.TdriveAttachmentStorageConfiguration.DriveAccount;
import com.linagora.calendar.restapi.routes.AttachmentUploadRoute;

import io.netty.buffer.Unpooled;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

class AttachmentUploadRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] CONTENT = new byte[]{0, 1, 2, -1, 13, 10};
    private static final Upload UPLOAD = new Upload("bob", "calendars/bob/default", "event-1", "report.bin", "application/octet-stream",
        Base64.getEncoder().encodeToString(CONTENT));

    private DisposableServer drive;
    private CalendarRestApiServer api;
    private FallbackProxy fallback;
    private HttpClient client;
    private byte[] uploaded;
    private AtomicInteger createdFiles;
    private AtomicInteger shares;
    private boolean exists;
    private boolean shared;
    private boolean failShare;

    @BeforeEach
    void setUp() {
        createdFiles = new AtomicInteger();
        shares = new AtomicInteger();
        drive = HttpServer.create().port(0).handle((request, response) -> {
            assertThat(request.requestHeaders().get("Authorization")).isEqualTo("Bearer bob-drive-token");
            if (request.method().name().equals("POST") && request.uri().startsWith("/files/")) {
                return request.receive().aggregate().asByteArray().flatMap(bytes -> {
                    if (exists) {
                        return response.status(409).send();
                    }
                    assertThat(request.requestHeaders().get("Content-MD5")).isEqualTo(Base64.getEncoder().encodeToString(DigestUtils.md5(bytes)));
                    uploaded = bytes;
                    exists = true;
                    createdFiles.incrementAndGet();
                    return response.status(201).sendString(Mono.just(metadata())).then();
                });
            }
            if (request.uri().startsWith("/files/metadata?")) {
                return response.sendString(Mono.just(metadata()));
            }
            if (request.method().name().equals("GET")) {
                String data = shared ? """
                    {"data":[{"attributes":{"permissions":{"files":{"type":"io.cozy.files","verbs":["GET"],"values":["file-1"]}},"shortcodes":{"calendar":"test-share"}}}]}
                    """ : "{\"data\":[]}";
                return response.sendString(Mono.just(data));
            }
            if (failShare) {
                return response.status(503).send();
            }
            return request.receive().aggregate().asString().flatMap(body -> {
                assertThatJson(body).inPath("data.attributes.permissions.files.verbs").isArray().containsExactly("GET");
                assertThatJson(body).inPath("data.attributes.permissions.files.values").isArray().containsExactly("file-1");
                shared = true;
                shares.incrementAndGet();
                return response.sendString(Mono.just("{\"data\":{\"attributes\":{\"shortcodes\":{\"calendar\":\"test-share\"}}}}" )).then();
            });
        }).bindNow();
        TdriveAttachmentStorageConfiguration configuration = new TdriveAttachmentStorageConfiguration(Map.of("bob",
            new DriveAccount(URI.create("http://localhost:" + drive.port()), URI.create("https://bob-drive.example.test"), "bob-drive-token")));
        startApi(new AttachmentUploadConfiguration(true, "service-secret"), new TdriveAttachmentStorageClient(configuration));
    }

    private void startApi(AttachmentUploadConfiguration configuration, AttachmentStorageClient storageClient) {
        fallback = mock(FallbackProxy.class);
        AttachmentUploadRoute route = new AttachmentUploadRoute(configuration, storageClient, new RecordingMetricFactory());
        api = new CalendarRestApiServer(Set.of(route), fallback,
            RestApiConfiguration.builder().adminPassword(Optional.of("unused")).build(), mock(DavProxy.class));
        api.start();
        client = HttpClient.create().baseUrl("http://localhost:" + api.getPort().getValue());
    }

    @AfterEach
    void tearDown() {
        api.stop();
        drive.disposeNow();
    }

    private String metadata() {
        return """
            {"data":{"id":"file-1","attributes":{"size":"SIZE","md5sum":"MD5"}}}
            """.replace("SIZE", Integer.toString(CONTENT.length))
            .replace("MD5", Base64.getEncoder().encodeToString(DigestUtils.md5(CONTENT)));
    }

    private record Reply(int status, String body) { }

    private Reply post(String bearer, Object upload) throws Exception {
        return client.headers(headers -> headers.set("Authorization", "Bearer " + bearer))
            .post().uri("/attachments")
            .send(Mono.just(Unpooled.wrappedBuffer(MAPPER.writeValueAsBytes(upload))))
            .responseSingle((response, bytes) -> bytes.asString().defaultIfEmpty("").map(body -> new Reply(response.status().code(), body)))
            .block();
    }

    @Test
    void shouldUploadExactBinaryAndReuseFileAndShareOnRetry() throws Exception {
        Reply first = post("service-secret", UPLOAD);
        Reply retry = post("service-secret", UPLOAD);

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry).isEqualTo(first);
        assertThat(uploaded).isEqualTo(CONTENT);
        assertThat(createdFiles.get()).isEqualTo(1);
        assertThat(shares.get()).isEqualTo(1);
        assertThatJson(first.body()).inPath("uri").isEqualTo("https://bob-drive.example.test/public?sharecode=test-share#/");
        assertThatJson(first.body()).inPath("sha256").isEqualTo(DigestUtils.sha256Hex(CONTENT));
    }

    @Test
    void shouldAuthenticateBeforeReadingUpload() throws Exception {
        assertThat(post("wrong-secret", UPLOAD).status()).isEqualTo(401);
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldRejectUserWithoutProvisionedDriveInsteadOfUsingAnotherAccount() throws Exception {
        assertThat(post("service-secret", new Upload("alice", UPLOAD.calendarPath(), UPLOAD.eventUid(), UPLOAD.filename(), UPLOAD.contentType(), UPLOAD.contentBase64())).status())
            .isEqualTo(403);
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldRejectInvalidBase64BeforeUploading() throws Exception {
        assertThat(post("service-secret", new Upload("bob", UPLOAD.calendarPath(), UPLOAD.eventUid(), UPLOAD.filename(), UPLOAD.contentType(), "not base64")).status())
            .isEqualTo(400);
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldFailOnSharingErrorAndRecoverSameFileOnRetry() throws Exception {
        failShare = true;
        assertThat(post("service-secret", UPLOAD).status()).isEqualTo(502);
        failShare = false;
        assertThat(post("service-secret", UPLOAD).status()).isEqualTo(200);
        assertThat(createdFiles.get()).isEqualTo(1);
    }

    @Test
    void shouldBeDisabledByDefaultAndRequireSecretWhenEnabled() throws Exception {
        PropertiesConfiguration properties = new PropertiesConfiguration();
        assertThat(AttachmentUploadConfiguration.parse(properties).enabled()).isFalse();
        assertThatThrownBy(() -> new AttachmentUploadConfiguration(true, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AttachmentUploadConfiguration(true, "sensitive-value").toString()).doesNotContain("sensitive-value");
    }

    @Test
    void shouldUseAnotherStorageProviderWithoutTdriveAccounts() throws Exception {
        api.stop();
        AttachmentStorageClient alternative = upload -> Mono.just(new StoredAttachment(
            "https://other-storage.example.test/report.bin", upload.filename(), upload.contentType(), CONTENT.length, DigestUtils.sha256Hex(CONTENT)));
        startApi(new AttachmentUploadConfiguration(true, "service-secret"), alternative);

        Reply reply = post("service-secret", UPLOAD);

        assertThat(reply.status()).isEqualTo(200);
        assertThatJson(reply.body()).inPath("uri").isEqualTo("https://other-storage.example.test/report.bin");
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldReturnNotFoundWhenDisabledWithoutCallingStorageOrProxy() throws Exception {
        api.stop();
        AttachmentStorageClient storage = mock(AttachmentStorageClient.class);
        startApi(new AttachmentUploadConfiguration(false, ""), storage);

        assertThat(post("service-secret", UPLOAD).status()).isEqualTo(404);
        verifyNoInteractions(storage, fallback);
    }

    @Test
    void shouldAuthenticateBeforeParsingMalformedJson() {
        Reply reply = client.headers(headers -> headers.set("Authorization", "Bearer wrong-secret"))
            .post().uri("/attachments").send(Mono.just(Unpooled.wrappedBuffer(new byte[]{'{'})))
            .responseSingle((response, bytes) -> bytes.asString().defaultIfEmpty("").map(body -> new Reply(response.status().code(), body)))
            .block();

        assertThat(reply.status()).isEqualTo(401);
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldRejectOversizedRequestBeforeUploading() throws Exception {
        String oversized = "x".repeat(4 * ((AttachmentStorageClient.MAX_FILE_BYTES + 2) / 3) + 16385);

        assertThat(post("service-secret", oversized).status()).isEqualTo(413);
        assertThat(createdFiles.get()).isZero();
    }

    @Test
    void shouldRejectPathTraversalAndOversizedMetadata() {
        assertThatThrownBy(() -> new Upload("bob", "cal", "uid", "../report.txt", "text/plain", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Upload("bob", "cal", "uid", "report.txt", "text/plain\r\nX-Header: value", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
