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
import java.util.Map;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.restapi.routes.AttachmentUploadRoute;

public class AttachmentUploadModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(AttachmentStorageClient.class).to(TdriveAttachmentStorageClient.class).in(Scopes.SINGLETON);
        Multibinder.newSetBinder(binder(), JMAPRoutes.class)
            .addBinding().to(AttachmentUploadRoute.class);
    }

    @Provides
    @Singleton
    AttachmentUploadConfiguration configuration(PropertiesProvider properties) throws IOException, ConfigurationException {
        return AttachmentUploadConfiguration.parse(properties.getConfiguration("configuration"));
    }

    @Provides
    @Singleton
    TdriveAttachmentStorageConfiguration tdriveConfiguration(PropertiesProvider properties, AttachmentUploadConfiguration configuration)
            throws IOException, ConfigurationException {
        return configuration.enabled()
            ? TdriveAttachmentStorageConfiguration.parse(properties.getConfiguration("configuration"))
            : new TdriveAttachmentStorageConfiguration(Map.of());
    }
}
