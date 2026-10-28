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

package com.linagora.calendar.restapi.routes;

import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;
import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JacksonException;
import com.linagora.calendar.restapi.attachment.AttachmentStorageClient;
import com.linagora.calendar.restapi.attachment.AttachmentStorageClient.Upload;
import com.linagora.calendar.restapi.attachment.AttachmentStorageClient.UserNotProvisionedException;
import com.linagora.calendar.restapi.attachment.AttachmentUploadConfiguration;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/** Internal Sabre endpoint; its service credential is distinct from end-user authentication. */
public class AttachmentUploadRoute implements JMAPRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentUploadRoute.class);
    private static final int MAX_REQUEST_BYTES = 4 * ((AttachmentStorageClient.MAX_FILE_BYTES + 2) / 3) + 16384;

    private static class PayloadTooLarge extends IllegalArgumentException {
    }

    private final AttachmentUploadConfiguration configuration;
    private final AttachmentStorageClient client;
    private final MetricFactory metricFactory;

    @Inject
    public AttachmentUploadRoute(AttachmentUploadConfiguration configuration, AttachmentStorageClient client, MetricFactory metricFactory) {
        this.configuration = configuration;
        this.client = client;
        this.metricFactory = metricFactory;
    }

    @Override
    public Stream<JMAPRoute> routes() {
        // Keep the route registered when disabled so credentials never fall through to the proxy.
        return Stream.of(JMAPRoute.builder()
            .endpoint(new Endpoint(HttpMethod.POST, "/attachments"))
            .action((request, response) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(
                getClass().getSimpleName(), handle(request, response))))
            .noCorsHeaders());
    }

    private Mono<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        if (!configuration.enabled()) {
            return response.status(NOT_FOUND).send();
        }
        List<String> headers = request.requestHeaders().getAll("Authorization");
        if (headers.size() != 1 || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)
            || !MessageDigest.isEqual(configuration.secret().getBytes(StandardCharsets.UTF_8),
                headers.getFirst().substring(7).getBytes(StandardCharsets.UTF_8))) {
            return response.status(UNAUTHORIZED).header("WWW-Authenticate", "Bearer").send();
        }
        return request.receive().asByteArray()
            .reduceWith(ByteArrayOutputStream::new, (buffer, chunk) -> {
                if (buffer.size() + chunk.length > MAX_REQUEST_BYTES) {
                    throw new PayloadTooLarge();
                }
                buffer.writeBytes(chunk);
                return buffer;
            })
            .flatMap(buffer -> Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.readValue(buffer.toByteArray(), Upload.class)))
            .flatMap(upload -> client.upload(upload)
                .doOnError(error -> LOGGER.warn("Attachment upload failed for user {}, calendar {}, event {}: {}",
                    upload.userId(), upload.calendarPath(), upload.eventUid(), error.getClass().getSimpleName())))
            .flatMap(stored -> response.status(OK).headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(stored))).then())
            .onErrorResume(error -> {
                HttpResponseStatus status = error instanceof PayloadTooLarge ? REQUEST_ENTITY_TOO_LARGE
                    : error instanceof UserNotProvisionedException ? FORBIDDEN
                    : error instanceof IllegalArgumentException || error instanceof JacksonException ? BAD_REQUEST : BAD_GATEWAY;
                return response.status(status).send();
            });
    }
}
