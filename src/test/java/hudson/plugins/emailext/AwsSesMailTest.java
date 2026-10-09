package hudson.plugins.emailext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.plugins.emailext.plugins.recipients.ListRecipientProvider;
import hudson.plugins.emailext.plugins.trigger.SuccessTrigger;
import hudson.tasks.MailMessageIdAction;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

@WithJenkins
class AwsSesMailTest {

    @Test
    void sendsRawMessageThroughSesInsteadOfSmtp(JenkinsRule j) throws Exception {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-id-1").build());
        AtomicInteger clientsCreated = new AtomicInteger();
        ExtendedEmailPublisherDescriptor descriptor = configureSes(j, "us-west-2", (acc, run) -> {
            clientsCreated.incrementAndGet();
            return client;
        });
        descriptor.getMailAccount().setSesConfigurationSet("jenkins-events");

        FreeStyleBuild build = buildAndNotify(j, "to@example.com,cc:cc@example.com,bcc:bcc@example.com");

        assertEquals(1, clientsCreated.get());
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        verify(client).close();
        SendEmailRequest request = captor.getValue();
        assertThat(
                request.destination().toAddresses(),
                containsInAnyOrder("to@example.com", "cc@example.com", "bcc@example.com"));
        assertEquals("jenkins-events", request.configurationSetName());
        String raw = request.content().raw().data().asString(StandardCharsets.UTF_8);
        assertThat(raw, containsString("To: to@example.com"));
        assertThat(raw, containsString("Cc: cc@example.com"));
        assertThat(raw, not(containsString("bcc@example.com")));

        assertThat(build.getLog(100), hasItem("Sent email with Amazon SES, message id(s): ses-id-1"));
        MailMessageIdAction messageIdAction = build.getAction(MailMessageIdAction.class);
        assertNotNull(messageIdAction);
        assertEquals("<ses-id-1@us-west-2.amazonses.com>", messageIdAction.messageId);
        assertEquals(0, Mailbox.get("to@example.com").size(), "nothing must go through SMTP");
    }

    @Test
    void reportsSesErrorsInBuildLog(JenkinsRule j) throws Exception {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .message("Email address is not verified.")
                        .statusCode(400)
                        .build());
        configureSes(j, "eu-west-1", (acc, run) -> client);

        FreeStyleBuild build = buildAndNotify(j, "to@example.com");

        j.assertLogContains("Amazon SES error while sending email.", build);
        j.assertLogContains("Email address is not verified.", build);
        assertNull(build.getAction(MailMessageIdAction.class));
        verify(client).close();
    }

    @Test
    void refusesToSendWithoutRegion(JenkinsRule j) throws Exception {
        AtomicInteger clientsCreated = new AtomicInteger();
        configureSes(j, null, (acc, run) -> {
            clientsCreated.incrementAndGet();
            return mock(SesV2Client.class);
        });

        FreeStyleBuild build = buildAndNotify(j, "to@example.com");

        j.assertLogContains("Mail account uses Amazon SES but has no AWS region", build);
        assertEquals(0, clientsCreated.get());
    }

    @Test
    @WithoutJenkins
    void splitsLargeRecipientListsIntoBatches() throws Exception {
        SesV2Client client = mock(SesV2Client.class);
        AtomicInteger ids = new AtomicInteger();
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenAnswer(inv -> SendEmailResponse.builder()
                        .messageId("id-" + ids.incrementAndGet())
                        .build());
        MailAccount account = new MailAccount();
        account.setUseAwsSes(true);
        account.setAwsRegion("us-east-1");

        List<Address> recipients = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            recipients.add(new InternetAddress("user" + i + "@example.com"));
        }
        // duplicates (e.g. same address in To and Cc) must only be sent once
        recipients.add(new InternetAddress("Some User <user0@example.com>"));

        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom("jenkins@example.com");
        msg.setSubject("subject");
        msg.setText("body");

        List<String> messageIds = SesMailSender.send(client, account, msg, recipients.toArray(new Address[0]));

        assertEquals(List.of("id-1", "id-2"), messageIds);
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client, times(2)).sendEmail(captor.capture());
        assertEquals(
                SesMailSender.MAX_RECIPIENTS_PER_MESSAGE,
                captor.getAllValues().get(0).destination().toAddresses().size());
        assertEquals(
                10, captor.getAllValues().get(1).destination().toAddresses().size());
        assertNull(captor.getAllValues().get(0).configurationSetName());
    }

    @Test
    @WithoutJenkins
    void messageIdHeaderUsesRegionalSesDomain() {
        assertEquals("<abc@email.amazonses.com>", SesMailSender.toMessageIdHeader("us-east-1", "abc"));
        assertEquals("<abc@eu-central-1.amazonses.com>", SesMailSender.toMessageIdHeader("eu-central-1", "abc"));
    }

    @Test
    void createClientUsesConfiguredAwsCredentials(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new AWSCredentialsImpl(CredentialsScope.GLOBAL, "aws-ses", "AKIAEXAMPLE", "secret", "SES"));
        MailAccount account = new MailAccount();
        account.setUseAwsSes(true);
        account.setAwsRegion("us-east-1");
        account.setAwsCredentialsId("aws-ses");
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        try (SesV2Client client = SesMailSender.createClient(account, build)) {
            AwsCredentialsIdentity identity = client.serviceClientConfiguration()
                    .credentialsProvider()
                    .resolveIdentity()
                    .join();
            assertEquals("AKIAEXAMPLE", identity.accessKeyId());
            assertEquals(
                    "us-east-1", client.serviceClientConfiguration().region().id());
        }

        account.setAwsCredentialsId("does-not-exist");
        SdkClientException e = assertThrows(SdkClientException.class, () -> SesMailSender.createClient(account, build));
        assertTrue(e.getMessage().contains("does-not-exist"));
    }

    private static ExtendedEmailPublisherDescriptor configureSes(
            JenkinsRule j, String region, BiFunction<MailAccount, Run<?, ?>, SesV2Client> clientProvider) {
        ExtendedEmailPublisherDescriptor descriptor =
                j.jenkins.getDescriptorByType(ExtendedEmailPublisherDescriptor.class);
        MailAccount account = descriptor.getMailAccount();
        account.setUseAwsSes(true);
        account.setAwsRegion(region);
        descriptor.setSesClientProvider(clientProvider);
        return descriptor;
    }

    private static FreeStyleBuild buildAndNotify(JenkinsRule j, String recipients) throws Exception {
        ExtendedEmailPublisher publisher = new ExtendedEmailPublisher();
        publisher.setFrom("");
        publisher.setContentType("default");
        publisher.setDefaultSubject("$DEFAULT_SUBJECT");
        publisher.setDefaultContent("$DEFAULT_CONTENT");
        publisher.setAttachmentsPattern("");
        publisher.setRecipientList(recipients);
        publisher.setPresendScript("");
        publisher.setPostsendScript("");
        publisher.setReplyTo("");
        SuccessTrigger trigger = new SuccessTrigger(
                Collections.singletonList(new ListRecipientProvider()),
                "$DEFAULT_RECIPIENTS",
                "",
                "$DEFAULT_SUBJECT",
                "$DEFAULT_CONTENT",
                "",
                0,
                "project");
        publisher.getConfiguredTriggers().add(trigger);
        FreeStyleProject project = j.createFreeStyleProject();
        project.getPublishersList().add(publisher);
        return j.buildAndAssertSuccess(project);
    }
}
