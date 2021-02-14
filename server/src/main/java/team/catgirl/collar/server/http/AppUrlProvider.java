package team.catgirl.collar.server.http;

public interface AppUrlProvider {

    /**
     * @param size pixels
     * @return url
     */
    String logoUrl(int size);

    /**
     * @return homepage of the collar web app
     */
    String homeUrl();

    /**
     * @return user login page
     */
    String loginUrl();

    /**
     * @return user sign up page
     */
    String signupUrl();

    /**
     * URL for verifying the device
     * @param token to link identity and device
     * @return url
     */
    String deviceVerificationUrl(String token);

    /**
     * @param token for email verification
     * @return url
     */
    String emailVerificationUrl(String token);

    String resetPassword(String token);
}
