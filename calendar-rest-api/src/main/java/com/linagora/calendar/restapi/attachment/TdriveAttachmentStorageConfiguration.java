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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.configuration2.Configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;

public record TdriveAttachmentStorageConfiguration(Map<String, DriveAccount> accounts) {
    public record DriveAccount(URI instanceUrl, URI driveUrl, String accessToken) {
        public DriveAccount {
            validateUrl(instanceUrl);
            validateUrl(driveUrl);
            Preconditions.checkArgument(accessToken != null && !accessToken.isBlank(), "Drive access token is required");
        }

        private static void validateUrl(URI uri) {
            Preconditions.checkArgument(uri != null && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPath().isEmpty() || uri.getPath().equals("/")), "Drive URL must be an origin");
            Preconditions.checkArgument("https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))),
                "Drive URLs require HTTPS (HTTP is allowed only on loopback for tests)");
        }

        @Override
        public String toString() {
            return "DriveAccount{credentials redacted}";
        }
    }

    public static TdriveAttachmentStorageConfiguration parse(Configuration configuration) throws IOException {
        String file = configuration.getString("attachment.tdrive.accounts.file");
        Preconditions.checkArgument(file != null && !file.isBlank(), "attachment.tdrive.accounts.file is required");
        Map<String, DriveAccount> accounts = new ObjectMapper().readValue(Path.of(file).toFile(), new TypeReference<>() { });
        return new TdriveAttachmentStorageConfiguration(accounts);
    }

    public TdriveAttachmentStorageConfiguration {
        accounts = Map.copyOf(accounts);
    }

    @Override
    public String toString() {
        return "TdriveAttachmentStorageConfiguration{accounts=" + accounts.size() + "}";
    }
}
