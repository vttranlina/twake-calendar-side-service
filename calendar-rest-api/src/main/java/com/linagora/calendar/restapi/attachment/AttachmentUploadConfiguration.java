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

import org.apache.commons.configuration2.Configuration;

import com.google.common.base.Preconditions;

public record AttachmentUploadConfiguration(boolean enabled, String secret) {
    public static AttachmentUploadConfiguration parse(Configuration configuration) {
        return new AttachmentUploadConfiguration(configuration.getBoolean("attachment.api.enabled", false),
            configuration.getString("attachment.api.secret", ""));
    }

    public AttachmentUploadConfiguration {
        Preconditions.checkArgument(!enabled || (secret != null && !secret.isBlank()), "attachment.api.secret is required");
    }

    @Override
    public String toString() {
        return "AttachmentUploadConfiguration{enabled=" + enabled + "}";
    }
}
