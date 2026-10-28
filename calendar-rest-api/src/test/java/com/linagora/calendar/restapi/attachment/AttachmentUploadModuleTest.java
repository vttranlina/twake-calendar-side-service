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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.utils.PropertiesProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.linagora.calendar.restapi.routes.AttachmentUploadRoute;

class AttachmentUploadModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRegisterRouteWithoutRequiringDriveCredentialsWhenDisabled() throws Exception {
        Injector injector = injector(new PropertiesConfiguration());

        assertThat(injector.getInstance(AttachmentUploadConfiguration.class).enabled()).isFalse();
        assertThat(injector.getInstance(TdriveAttachmentStorageConfiguration.class).accounts()).isEmpty();
        assertThat(injector.getInstance(Key.get(new TypeLiteral<Set<JMAPRoutes>>() { })))
            .anyMatch(AttachmentUploadRoute.class::isInstance);
    }

    @Test
    void shouldBindTdriveClientAndLoadItsAccountsWhenEnabled() throws Exception {
        Path accounts = temporaryDirectory.resolve("accounts.json");
        Files.writeString(accounts, """
            {"bob":{"instanceUrl":"https://bob.example.test","driveUrl":"https://bob-drive.example.test","accessToken":"drive-token"}}
            """);
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty("attachment.api.enabled", true);
        configuration.setProperty("attachment.api.secret", "service-secret");
        configuration.setProperty("attachment.tdrive.accounts.file", accounts.toString());

        Injector injector = injector(configuration);

        AttachmentStorageClient client = injector.getInstance(AttachmentStorageClient.class);
        assertThat(client).isInstanceOf(TdriveAttachmentStorageClient.class);
        assertThat(injector.getInstance(AttachmentStorageClient.class)).isSameAs(client);
        TdriveAttachmentStorageConfiguration tdrive = injector.getInstance(TdriveAttachmentStorageConfiguration.class);
        assertThat(tdrive.accounts()).containsOnlyKeys("bob");
        assertThat(tdrive.accounts().get("bob").accessToken()).isEqualTo("drive-token");
        assertThat(tdrive.toString()).doesNotContain("drive-token");
        assertThat(tdrive.accounts().get("bob").toString()).doesNotContain("drive-token");
    }

    private Injector injector(PropertiesConfiguration configuration) throws Exception {
        PropertiesProvider properties = mock(PropertiesProvider.class);
        when(properties.getConfiguration("configuration")).thenReturn(configuration);
        return Guice.createInjector(new AttachmentUploadModule(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(PropertiesProvider.class).toInstance(properties);
                bind(MetricFactory.class).toInstance(new RecordingMetricFactory());
            }
        });
    }
}
