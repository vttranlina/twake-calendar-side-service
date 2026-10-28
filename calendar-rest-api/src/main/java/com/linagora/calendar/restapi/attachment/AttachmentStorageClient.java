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

import java.nio.charset.StandardCharsets;

import com.google.common.base.Preconditions;

import reactor.core.publisher.Mono;

/** Uploads calendar attachments to an external provider and returns a public read-only URI. */
public interface AttachmentStorageClient {
    int MAX_FILE_BYTES = 5 * 1024 * 1024;

    record Upload(String userId, String calendarPath, String eventUid, String filename, String contentType, String contentBase64) {
        public Upload {
            Preconditions.checkArgument(userId != null && !userId.isBlank() && userId.length() <= 256, "Invalid userId");
            Preconditions.checkArgument(calendarPath != null && !calendarPath.isBlank() && calendarPath.length() <= 2048, "Invalid calendarPath");
            Preconditions.checkArgument(eventUid != null && !eventUid.isBlank() && eventUid.length() <= 1024, "Invalid eventUid");
            Preconditions.checkArgument(filename != null && !filename.isBlank() && filename.getBytes(StandardCharsets.UTF_8).length <= 160
                && filename.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\'), "Invalid filename");
            Preconditions.checkArgument(contentType != null && contentType.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"), "Invalid contentType");
            Preconditions.checkArgument(contentBase64 != null && contentBase64.length() <= 4 * ((MAX_FILE_BYTES + 2) / 3), "Attachment too large");
        }

        @Override
        public String toString() {
            return "Upload{userId=" + userId + ", content redacted}";
        }
    }

    record StoredAttachment(String uri, String filename, String contentType, long size, String sha256) { }

    class UserNotProvisionedException extends RuntimeException {
        public UserNotProvisionedException(String userId) {
            super("No attachment storage account provisioned for user " + userId);
        }
    }

    Mono<StoredAttachment> upload(Upload upload);
}
