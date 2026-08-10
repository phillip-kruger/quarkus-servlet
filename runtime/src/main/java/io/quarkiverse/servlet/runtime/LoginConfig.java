package io.quarkiverse.servlet.runtime;

/**
 * The {@code <login-config>} of a deployment.
 *
 * @param authMethod {@code BASIC}, {@code FORM}, {@code DIGEST} or {@code CLIENT_CERT}
 * @param realmName realm reported in a BASIC challenge
 * @param formLoginPage page to show an unauthenticated caller under FORM authentication
 * @param formErrorPage page to show after a failed FORM login
 */
public record LoginConfig(String authMethod, String realmName, String formLoginPage,
        String formErrorPage) {

    public static final String BASIC = "BASIC";
    public static final String FORM = "FORM";

    public boolean isBasic() {
        return BASIC.equalsIgnoreCase(authMethod);
    }

    public boolean isForm() {
        return FORM.equalsIgnoreCase(authMethod);
    }
}
