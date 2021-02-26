package team.catgirl.collar.server.mail;

import com.commit451.mailgun.Contact;
import com.commit451.mailgun.SendMessageRequest;
import com.google.common.io.BaseEncoding;
import okhttp3.*;
import team.catgirl.collar.api.http.HttpException;
import team.catgirl.collar.api.http.HttpException.UnmappedHttpException;
import team.catgirl.collar.server.http.AppUrlProvider;
import team.catgirl.collar.server.services.profiles.Profile;
import team.catgirl.collar.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleMailGunEmail extends AbstractEmail {

    private static final Logger LOGGER = Logger.getLogger(SimpleMailGunEmail.class.getName());

    private final String domain;
    private final String apiKey;

    public SimpleMailGunEmail(AppUrlProvider urlProvider, String domain, String apiKey) {
        super(urlProvider);
        this.domain = domain;
        this.apiKey = apiKey;
    }

    @Override
    public void send(Profile profile, String subject, String templateName, Map<String, Object> variables) {
        variables = prepareVariables(profile, variables);
        RequestBody formBody = new FormBody.Builder()
                .add("from", "no-reply@collarmc.com")
                .add("to", profile.email)
                .add("subject", subject)
                .add("html", renderHtml(templateName, variables))
                .add("text", renderText(templateName, variables))
                .build();

        String auth = BaseEncoding.base64().encode(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));
        Request request = new Request.Builder()
                .url("https://api.mailgun.net/v3/" + domain + " /messages")
                .addHeader("Authorization", "Basic " + auth)
                .post(formBody)
                .build();

        try (Response response = Utils.http().newCall(request).execute()) {
            if (response.code() != 200) {
                throw new UnmappedHttpException(response.code(), response.message());
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Connection issue", e);
        }
    }
}